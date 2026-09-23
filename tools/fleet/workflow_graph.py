#!/usr/bin/env python3
# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""The ``needs:`` graph of a workflow file, and the mapping from the jobs the
GitHub API reports back to the jobs the file declares.

The workflow-jobs API says nothing about dependencies. Whether a job's
``started_at - created_at`` measured time waiting for a runner or time
blocked behind an upstream job is only knowable from the workflow YAML, so
the poller (:mod:`tools.fleet.github_poller`) fetches the run's workflow
file at the run's commit and reads two fields per job: ``needs`` and
``name``. :class:`WorkflowGraph` is that parsed file; :class:`WorkflowGraphResolver`
fetches and caches one per ``(path, commit)`` and answers the two questions
the poller asks — "what does this job need?" and "when did the last thing it
needed finish?" — so ``queue_wait_seconds`` can be computed for dependent
jobs as well as entry-point ones.

**Job names.** The API's ``name`` is the job's rendered display name, not
its YAML key, and rendering follows a handful of rules:

- no ``name:`` → the key (``test-mac``);
- a ``name:`` with no ``${{ }}`` expression → that string verbatim;
- a matrix job → the display name followed by the matrix values in
  parentheses, ``test-mac (group-3)``, unless the ``name:`` itself used a
  matrix expression, in which case only the rendered ``name:`` appears;
- a job that calls a reusable workflow → ``caller / inner job``.

:meth:`WorkflowGraph.resolve` (and its convenience wrapper
:meth:`WorkflowGraph.job_key`) inverts those rules. A ``name:`` that
contains an expression cannot be matched verbatim, so it becomes a pattern
with each ``${{ }}`` standing for any text; the literal and pattern
candidates across every rendering form are one combined set of hypotheses,
and the job is reported unknown (``None``) unless exactly one of them
matches. The ``caller / inner`` form is only recognized when the caller
job's own definition has ``uses:`` — evidence it actually calls a reusable
workflow — so an ordinary job whose literal name happens to contain
``" / "`` still resolves on its own name. Unknown is always preferred over
a guess: a wrong dependency list would silently misreport queue wait, an
unresolved one is an honest ``NULL``.

``PyYAML`` is imported only when a file is actually parsed, mirroring how
``psycopg`` is optional in :mod:`tools.fleet.store`; :func:`yaml_available`
lets the poller decide up front whether to resolve dependencies at all.
"""

from __future__ import annotations

import base64
import re
import sys
import urllib.error
from typing import Callable, Dict, List, Optional, Set, Tuple

GITHUB_API_BASE = "https://api.github.com"

_EXPRESSION = re.compile(r"\$\{\{.*?\}\}")


def yaml_available() -> bool:
    """Whether ``PyYAML`` can be imported, and so whether workflow files can be parsed."""
    try:
        import yaml  # noqa: F401 — optional dependency, probed only here
    except ImportError:
        return False
    return True


class WorkflowGraph:
    """The jobs a workflow file declares: each key's display name and ``needs``."""

    def __init__(self, jobs: Dict[str, Dict]):
        """Build from the ``jobs:`` mapping of a parsed workflow (key → job definition)."""
        self._needs: Dict[str, List[str]] = {}
        self._display: Dict[str, str] = {}
        self._uses: Dict[str, bool] = {}
        self._matrix: Dict[str, bool] = {}
        for key, definition in (jobs or {}).items():
            definition = definition or {}
            needs = definition.get("needs") or []
            if isinstance(needs, str):
                needs = [needs]
            self._needs[key] = [str(n) for n in needs]
            name = definition.get("name")
            self._display[key] = str(name) if name else key
            self._uses[key] = "uses" in definition
            self._matrix[key] = bool((definition.get("strategy") or {}).get("matrix"))

    @classmethod
    def from_yaml(cls, text: str) -> "WorkflowGraph":
        """Parse a workflow file's text; needs ``PyYAML``."""
        try:
            import yaml
        except ImportError as exc:
            raise RuntimeError(
                "parsing a workflow file needs the 'PyYAML' package (pip install pyyaml)"
            ) from exc
        document = yaml.safe_load(text) or {}
        jobs = document.get("jobs") if isinstance(document, dict) else None
        return cls(jobs if isinstance(jobs, dict) else {})

    @property
    def keys(self) -> List[str]:
        """Every job key the file declares, in file order."""
        return list(self._needs)

    def needs(self, key: str) -> Optional[List[str]]:
        """The ``needs:`` list of job *key* (``[]`` for an entry point), or ``None`` if no such job."""
        return list(self._needs[key]) if key in self._needs else None

    def display_name(self, key: str) -> Optional[str]:
        """The un-rendered display name of *key*: its ``name:`` if set (expressions intact), else the key."""
        return self._display.get(key)

    def job_key(self, api_name: str) -> Optional[str]:
        """The YAML key that produced *api_name*; ``None`` if unknown or ambiguous.

        Equivalent to ``resolve(api_name)[0]`` — see :meth:`resolve` for the
        rules that govern this mapping.
        """
        return self.resolve(api_name)[0]

    def resolve(self, api_name: str) -> Tuple[Optional[str], bool]:
        """Map the ``name`` the jobs API reports back to the YAML key that produced it.

        Returns ``(key, is_inner_job_reference)``. ``is_inner_job_reference``
        is true when *api_name* is the API's ``caller / inner`` rendering for
        a job inside a workflow *key* calls via ``uses:`` — the inner job's
        own ``needs:`` live in the called workflow file, not in this graph,
        so a caller resolving dependencies must treat that case as unknown
        even though a key was found (see
        :meth:`WorkflowGraphResolver.needs`).

        Tries the full name first — exact, then as a pattern for a
        ``name:`` that contains a ``${{ }}`` expression. If that name ends
        in a ``(...)`` suffix, the stripped form is tried too, but only
        against jobs whose own definition declares a ``strategy.matrix`` —
        only an actual matrix job can render with that suffix in the API at
        all, so a non-matrix job is never a candidate for the stripped form,
        even when its literal name happens to end in parentheses on its
        own. Every surviving form is a competing hypothesis about what
        *api_name* actually is, not an independent fallback to try in
        order: a matrix job ``foo`` and an unrelated literal job
        ``name: foo (bar)`` are both exact matches for the api_name
        ``"foo (bar)"`` (one via the matrix-suffix-stripped form, one via
        the literal form), so matching on the literal form first and
        returning immediately would silently prefer it over the equally
        valid matrix-job match instead of reporting the ambiguity. Exact and
        pattern matches are candidates for the same job, not two independent
        tiers to try in priority order: a literal job ``name: "test ubuntu"``
        and a pattern job ``name: "test ${{ matrix.os }}"`` are both
        plausible explanations for the api_name ``"test ubuntu"``, so
        matching the literal form and returning immediately would silently
        prefer it over the equally valid pattern match. Every form's exact
        and pattern matches are collected into one set, and a key is
        returned only when that combined set is a singleton.

        Only when the full name matches nothing at all is a ``caller /
        inner`` name split and the caller half tried the same way — and even
        then only among jobs whose own definition has ``uses:``, since a
        normal top-level job whose literal name happens to contain ``" / "``
        (for example ``name: "build / test"``) must resolve on its own
        name, not be reattributed to a caller it never had.
        """
        if not api_name:
            return None, False
        key, matched = self._match(api_name)
        if matched:
            return key, False
        if " / " in api_name:
            caller = api_name.split(" / ", 1)[0]
            key, matched = self._match(caller, require_uses=True)
            if matched:
                return key, True
        return None, False

    def _match(self, name: str, require_uses: bool = False) -> Tuple[Optional[str], bool]:
        """Every key *name* matches, merged into one set.

        Combines exact and pattern matches against *name* itself with exact
        and pattern matches against its matrix-suffix-stripped form — the
        latter restricted to jobs that actually declare a
        ``strategy.matrix``, since only a matrix job can render with a
        ``(values)`` suffix in the API at all; a non-matrix job whose
        literal name simply ends in parentheses on its own is only ever a
        candidate through the unstripped form.

        Returns ``(key, True)`` when that set is a singleton, ``(None, True)``
        when it has more than one member (a real ambiguity), and
        ``(None, False)`` when it is empty — the caller uses the second
        element to tell "ambiguous" from "no evidence at all", since only
        the latter should fall back to a weaker form of matching.
        """
        matches: Set[str] = self._exact_matches(name) | self._pattern_matches(name)
        stripped = self._without_matrix_suffix(name)
        if stripped != name:
            matches.update(
                key for key in self._exact_matches(stripped) | self._pattern_matches(stripped)
                if self._matrix.get(key)
            )
        if require_uses:
            matches = {key for key in matches if self._uses.get(key)}
        if not matches:
            return None, False
        return (next(iter(matches)) if len(matches) == 1 else None), True

    @staticmethod
    def _without_matrix_suffix(name: str) -> str:
        """``test-mac (group-3, metal)`` → ``test-mac``; a name with no such suffix is returned as is."""
        if name.endswith(")") and " (" in name:
            return name[:name.rfind(" (")]
        return name

    def _exact_matches(self, name: str) -> Set[str]:
        """Every key whose key or literal (expression-free) display name is *name*.

        A key match and a literal display-name match are candidates for the
        same job, not two independent tiers to try in priority order: a
        ``build: {}`` job and a separate ``other: {name: build}`` job both
        render as ``"build"`` in the jobs API and are genuinely
        indistinguishable from *name* alone, so preferring the key match
        would silently pick one over the other instead of reporting the
        ambiguity to the caller.
        """
        matches = {key for key in self._needs if key == name}
        matches.update(
            key for key, display in self._display.items()
            if display == name and not _EXPRESSION.search(display)
        )
        return matches

    def _pattern_matches(self, name: str) -> Set[str]:
        """Every key whose expression-bearing display name can render to *name*."""
        return {
            key for key, display in self._display.items()
            if _EXPRESSION.search(display) and re.fullmatch(self._pattern(display), name)
        }

    @staticmethod
    def _pattern(display: str) -> str:
        """A regex for every string an expression-bearing ``name:`` could render to."""
        parts = []
        position = 0
        for match in _EXPRESSION.finditer(display):
            parts.append(re.escape(display[position:match.start()]))
            parts.append(".*?")
            position = match.end()
        parts.append(re.escape(display[position:]))
        return "".join(parts)


class _TransientLoadFailure(Exception):
    """Internal signal that fetching a workflow file failed for a reason
    that might not recur (a network error, a 5xx, an exhausted rate
    limit). :meth:`WorkflowGraphResolver.graph` catches this and does not
    cache the outcome, unlike a deterministic 404 or an unparsable file.
    """


class WorkflowGraphResolver:
    """Fetches each run's workflow file once and answers the poller's dependency questions.

    Constructed with the repository and a ``fetch_json(url)`` callable that
    already carries the credential (the poller's own authenticated getter),
    so this class neither sees the token nor imports the poller. Files are
    cached per ``(path, head_sha)`` — every job of a run shares one, and
    successive polls re-read the same recent runs — and a file that cannot
    be fetched or parsed is cached as absent, so a run whose workflow the
    API will not serve costs one request, not one per job per cycle. The
    cache keeps the :data:`MAX_CACHED_GRAPHS` most recently first-seen
    files — a poller that lives for months sees a new commit per push, and
    the recent window it re-reads never spans more than a few hundred.
    """

    MAX_CACHED_GRAPHS = 512

    def __init__(self, repo: str, fetch_json: Callable[[str], Dict], api_base: str = GITHUB_API_BASE):
        self._repo = repo
        self._fetch_json = fetch_json
        self._api_base = api_base
        self._graphs: Dict[Tuple[str, str], Optional[WorkflowGraph]] = {}

    def graph(self, run: Dict) -> Optional[WorkflowGraph]:
        """The parsed workflow file for *run*, or ``None`` if it cannot be had.

        A transient fetch failure (a network error, a 5xx, an exhausted
        rate limit) is not cached: the next poll cycle's call to this
        method retries the fetch instead of the graph staying unknown
        until :data:`MAX_CACHED_GRAPHS` evicts the entry. A deterministic
        outcome — the file does not exist at this commit, or does not
        parse — is cached, since retrying it would only reproduce the same
        result on every future poll.
        """
        path = run.get("path")
        sha = run.get("head_sha")
        if not path or not sha:
            return None
        cache_key = (path, sha)
        if cache_key not in self._graphs:
            try:
                loaded = self._load(path, sha)
            except _TransientLoadFailure:
                return None
            if len(self._graphs) >= self.MAX_CACHED_GRAPHS:
                del self._graphs[next(iter(self._graphs))]
            self._graphs[cache_key] = loaded
        return self._graphs[cache_key]

    def _load(self, path: str, sha: str) -> Optional[WorkflowGraph]:
        """Fetch and parse the workflow file at *path*@*sha*.

        Returns ``None`` — a cacheable "no such graph" — for a 404, a
        non-retryable HTTP error (see :meth:`_is_retryable`), a response
        with no ``content``, or invalid YAML. Raises
        :class:`_TransientLoadFailure` for any other fetch error, since
        those are not guaranteed to recur on the next attempt.
        """
        url = "%s/repos/%s/contents/%s?ref=%s" % (self._api_base, self._repo, path, sha)
        try:
            payload = self._fetch_json(url)
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            if not self._is_retryable(exc):
                self._log_unavailable(path, sha, exc, retrying=False)
                return None
            self._log_unavailable(path, sha, exc, retrying=True)
            raise _TransientLoadFailure(str(exc)) from exc
        except Exception as exc:  # noqa: BLE001 — a non-HTTP fetch failure (network error, timeout) is also transient
            self._log_unavailable(path, sha, exc, retrying=True)
            raise _TransientLoadFailure(str(exc)) from exc
        try:
            content = payload.get("content")
            if not content:
                return None
            text = base64.b64decode(content).decode("utf-8")
            return WorkflowGraph.from_yaml(text)
        except Exception as exc:  # noqa: BLE001 — an unparsable file means "unknown graph", never a retry
            self._log_unavailable(path, sha, exc, retrying=False)
            return None

    @staticmethod
    def _is_retryable(exc: urllib.error.HTTPError) -> bool:
        """Whether *exc* might succeed on a later poll, rather than reproduce forever.

        A 5xx is always transient. A 429 is always a rate limit, and so is
        always transient. A 403 is ambiguous on the GitHub API — it is
        returned both for a secondary rate limit and for plain
        permission-denied — so it is only treated as transient when it
        carries GitHub's rate-limit signal: a ``Retry-After`` header, or
        ``X-RateLimit-Remaining: 0``. This mirrors
        :func:`tools.fleet.github_poller._is_retryable_rate_limit`, which
        ``_get_json`` uses to retry the same statuses on the request itself;
        by the time that retry budget is exhausted and this method sees the
        exception, a headerless 429 must still be treated as transient here,
        or it is cached as a permanent failure despite being retried as a
        rate limit at the transport layer. A 401 or a headerless 403 is a
        persistent auth/permission failure (a missing scope, a revoked
        token): caching it means one failed request per run instead of one
        per job per poll cycle, repeated forever for a file the token will
        never be allowed to read.
        """
        if exc.code >= 500:
            return True
        if exc.code == 429:
            return True
        if exc.code == 403:
            headers = exc.headers
            if headers is not None and (headers.get("Retry-After") or headers.get("X-RateLimit-Remaining") == "0"):
                return True
            return False
        return False

    @staticmethod
    def _log_unavailable(path: str, sha: str, exc: Exception, retrying: bool) -> None:
        print(
            "fleet poller: workflow %s@%s unavailable (%s); %s"
            % (
                path, sha[:12], exc,
                "will retry next poll" if retrying else "dependency graph unknown for its jobs",
            ),
            file=sys.stderr,
        )

    def needs(self, run: Dict, job: Dict) -> Optional[List[str]]:
        """*job*'s ``needs:`` list (``[]`` for an entry point), or ``None`` when unknown.

        A job inside a called reusable workflow (the API's ``caller / inner``
        name — see the module docstring) is always unknown here:
        ``WorkflowGraph.resolve`` attributes that name to the *calling*
        job's key (see its own docstring — that attribution serves other
        purposes, such as identifying which top-level job a nested run
        belongs to) and flags it as an inner-job reference, but the inner
        job's own dependencies are declared inside the *called* workflow
        file, which this class does not fetch or parse. Reporting the
        caller's ``needs:`` here would attribute a dependency list to a job
        that never declared it.

        This is the ``resolve_needs`` callback :func:`tools.fleet.github_poller.poll_and_store` takes.
        """
        graph = self.graph(run)
        if graph is None:
            return None
        key, is_inner_job_reference = graph.resolve(job.get("name") or "")
        if key is None or is_inner_job_reference:
            return None
        return graph.needs(key)

    def dependency_completed_at(self, run: Dict, job: Dict, run_jobs: List[Dict]) -> Optional[str]:
        """When the last job *job* depends on finished, as the API's timestamp string, or ``None``.

        Every ``needs`` key must map to at least one job in *run_jobs* (a
        matrix key maps to several) and every one of those must have a
        ``completed_at``; otherwise the eligibility time is not knowable and
        ``None`` is returned rather than a partial maximum. This is the
        ``resolve_dependency_completed_at`` callback
        :func:`tools.fleet.github_poller.poll_and_store` takes.
        """
        needs = self.needs(run, job)
        if not needs:
            return None
        graph = self.graph(run)
        if graph is None:
            return None
        by_key: Dict[str, List[Dict]] = {}
        for sibling in run_jobs:
            sibling_key = graph.job_key(sibling.get("name") or "")
            if sibling_key is not None:
                by_key.setdefault(sibling_key, []).append(sibling)
        latest: Optional[str] = None
        for needed in needs:
            upstream = by_key.get(needed)
            if not upstream:
                return None
            for dependency in upstream:
                completed = dependency.get("completed_at")
                if not completed:
                    return None
                if latest is None or completed > latest:
                    latest = completed
        return latest
