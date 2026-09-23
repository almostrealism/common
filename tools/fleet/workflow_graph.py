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

:meth:`WorkflowGraph.job_key` inverts those rules. A ``name:`` that contains
an expression cannot be matched verbatim, so it becomes a pattern with each
``${{ }}`` standing for any text; the pattern must match exactly one job,
or the job is reported unknown (``None``). Unknown is always preferred over
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
from typing import Callable, Dict, List, Optional, Tuple

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
        for key, definition in (jobs or {}).items():
            definition = definition or {}
            needs = definition.get("needs") or []
            if isinstance(needs, str):
                needs = [needs]
            self._needs[key] = [str(n) for n in needs]
            name = definition.get("name")
            self._display[key] = str(name) if name else key

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
        """Map the ``name`` the jobs API reports back to the YAML key that produced it.

        Tries the rendering rules in the module docstring in order — exact,
        then with a matrix suffix removed, then the caller half of a reusable
        workflow name — and finally treats ``name:`` values that contain
        ``${{ }}`` expressions as patterns. Returns ``None`` when nothing
        matches, or when more than one job could have produced *api_name*.
        """
        if not api_name:
            return None
        for candidate in self._candidates(api_name):
            key = self._exact(candidate)
            if key is not None:
                return key
        for candidate in self._candidates(api_name):
            key = self._by_pattern(candidate)
            if key is not None:
                return key
        return None

    @staticmethod
    def _candidates(api_name: str) -> List[str]:
        """*api_name* and the progressively shorter forms the rendering rules can produce."""
        forms = [api_name]
        stripped = WorkflowGraph._without_matrix_suffix(api_name)
        if stripped != api_name:
            forms.append(stripped)
        if " / " in api_name:
            caller = api_name.split(" / ", 1)[0]
            forms.append(caller)
            stripped_caller = WorkflowGraph._without_matrix_suffix(caller)
            if stripped_caller != caller:
                forms.append(stripped_caller)
        return forms

    @staticmethod
    def _without_matrix_suffix(name: str) -> str:
        """``test-mac (group-3, metal)`` → ``test-mac``; a name with no such suffix is returned as is."""
        if name.endswith(")") and " (" in name:
            return name[:name.rfind(" (")]
        return name

    def _exact(self, name: str) -> Optional[str]:
        """The key whose key or literal (expression-free) display name is *name*, if exactly one.

        A key match and a literal display-name match are candidates for the
        same job, not two independent tiers to try in priority order: a
        ``build: {}`` job and a separate ``other: {name: build}`` job both
        render as ``"build"`` in the jobs API and are genuinely
        indistinguishable from *name* alone, so returning the key match
        first would silently prefer one over the other instead of reporting
        the ambiguity. Every candidate key is collected into one set and a
        result is returned only when it is unique.
        """
        candidates = {key for key in self._needs if key == name}
        candidates.update(
            key for key, display in self._display.items()
            if display == name and not _EXPRESSION.search(display)
        )
        return next(iter(candidates)) if len(candidates) == 1 else None

    def _by_pattern(self, name: str) -> Optional[str]:
        """The key whose expression-bearing display name can render to *name*, if exactly one."""
        matches = []
        for key, display in self._display.items():
            if not _EXPRESSION.search(display):
                continue
            if re.fullmatch(self._pattern(display), name):
                matches.append(key)
        return matches[0] if len(matches) == 1 else None

    @staticmethod
    def _pattern(display: str) -> str:
        """A regex for every string an expression-bearing ``name:`` could render to."""
        parts = []
        position = 0
        for match in _EXPRESSION.finditer(display):
            parts.append(re.escape(display[position:match.start()]))
            parts.append(".+?")
            position = match.end()
        parts.append(re.escape(display[position:]))
        return "".join(parts)


def _is_reusable_workflow_job_name(api_name: str) -> bool:
    """Whether *api_name* is the ``caller / inner`` form the jobs API
    renders for a job inside a called reusable workflow (see this module's
    docstring). The inner job's own ``needs:`` live in the called workflow
    file, not in *this* file's graph, so this is how
    :meth:`WorkflowGraphResolver.needs` recognises it must answer "unknown"
    rather than substitute the calling job's dependencies.
    """
    return " / " in api_name


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
        """The parsed workflow file for *run*, or ``None`` if it cannot be had."""
        path = run.get("path")
        sha = run.get("head_sha")
        if not path or not sha:
            return None
        cache_key = (path, sha)
        if cache_key not in self._graphs:
            if len(self._graphs) >= self.MAX_CACHED_GRAPHS:
                del self._graphs[next(iter(self._graphs))]
            self._graphs[cache_key] = self._load(path, sha)
        return self._graphs[cache_key]

    def _load(self, path: str, sha: str) -> Optional[WorkflowGraph]:
        url = "%s/repos/%s/contents/%s?ref=%s" % (self._api_base, self._repo, path, sha)
        try:
            payload = self._fetch_json(url)
            content = payload.get("content")
            if not content:
                return None
            text = base64.b64decode(content).decode("utf-8")
            return WorkflowGraph.from_yaml(text)
        except Exception as exc:  # noqa: BLE001 — an unreadable file means "unknown graph", never a failed poll
            print(
                "fleet poller: workflow %s@%s unavailable (%s); dependency graph unknown for its jobs"
                % (path, sha[:12], exc),
                file=sys.stderr,
            )
            return None

    def needs(self, run: Dict, job: Dict) -> Optional[List[str]]:
        """*job*'s ``needs:`` list (``[]`` for an entry point), or ``None`` when unknown.

        A job inside a called reusable workflow (the API's ``caller / inner``
        name — see the module docstring) is always unknown here:
        ``WorkflowGraph.job_key`` attributes that name to the *calling* job's
        key (see its own docstring — that attribution serves other
        purposes, such as identifying which top-level job a nested run
        belongs to), but the inner job's own dependencies are declared
        inside the *called* workflow file, which this class does not fetch
        or parse. Reporting the caller's ``needs:`` here would attribute a
        dependency list to a job that never declared it.

        This is the ``resolve_needs`` callback :func:`tools.fleet.github_poller.poll_and_store` takes.
        """
        api_name = job.get("name") or ""
        if _is_reusable_workflow_job_name(api_name):
            return None
        graph = self.graph(run)
        if graph is None:
            return None
        key = graph.job_key(api_name)
        return None if key is None else graph.needs(key)

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
