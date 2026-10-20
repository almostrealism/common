#!/usr/bin/env python3
"""Runner for the consult evaluation set.

Three modes:

- ``replay`` (default; reproducible, no backend) grades the frozen response in
  ``fixtures/observed/<id>.json`` for each case — exactly what a deployed
  consult returned on the date the case records, with no pipeline re-run.
  This is how the committed baseline report is produced.
- ``scripted`` (reproducible, no backend) runs the real ``consult`` pipeline
  in-process — retrieval over the staged corpus, the snapshot index, tiering,
  the citation verifier, answer-basis computation — with a stub model that
  returns the case's canned answer (``fixtures/scripted/<id>.txt``). The
  canned answers reproduce the failure modes consult was observed making, in
  the citation style the current prompt asks for, so this mode measures what
  the pipeline does with such an answer: whether it is flagged, demoted or
  refused. It cannot measure whether a model still makes the mistake.
- ``local`` (needs an inference backend) runs the same in-process pipeline
  against the backend selected by ``AR_CONSULTANT_BACKEND``.

``scripted`` and ``local`` evaluate against the same pruned documentation
corpus the Dockerfile ships (staged from the checkout using the Dockerfile's
own ``COPY`` directives) and a snapshot index built from the same trees, so a
coverage regression in those directives shows up in their results. Everything
else that tiering reads is frozen per case, so two runs agree: the clock
(``now``), the merge state of note branches (``branch_states``), the caller's
branch and scopes (``caller``), and the corpus identity (commit ``checkout``,
built at the case's ``now``). The case's memories are injected through a
stub memory client. Each case runs ``--k`` times (``scripted`` once, since its
model is deterministic), every repetition is graded, and the report records
the pass fraction.

Every mode exits 0 by default, since the committed baseline is known to fail;
pass ``--check`` to make the run a gate that exits 1 unless every case passed.

A ``live`` mode (calling a deployed ar-manager over MCP) is described in the
plan but not implemented here; its numbers are not comparable across runs.

The functions ``load_cases``, ``build_report`` and ``render_report`` are
imported by ``test_consult_eval.py`` so the graders and the report shape are
themselves tested.
"""

import argparse
import importlib
import json
import os
import re
import shutil
import sys
import tempfile

_EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
_MANAGER_DIR = os.path.dirname(_EVAL_DIR)
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(_MANAGER_DIR)))
_DOCKERFILE = os.path.join(_MANAGER_DIR, "Dockerfile")
if _MANAGER_DIR not in sys.path:
    sys.path.insert(0, _MANAGER_DIR)
if _EVAL_DIR not in sys.path:
    sys.path.insert(0, _EVAL_DIR)
# The consult pipeline's shared modules (consult_engine imports them). The
# real server adds this directory itself; adding it here keeps the runner
# importable before, or without, the real server module.
_COMMON_DIR = os.path.join(os.path.dirname(_MANAGER_DIR), "common")
if _COMMON_DIR not in sys.path:
    sys.path.append(_COMMON_DIR)

from graders import (  # noqa: E402
    grade, resolve_expect, response_outcome, validate_case, is_tool_failure,
)

CASES_FILE = os.path.join(_EVAL_DIR, "consult_cases.json")


def _load_json(relpath: str):
    with open(os.path.join(_EVAL_DIR, relpath), encoding="utf-8") as handle:
        return json.load(handle)


def load_cases() -> list[dict]:
    """Load and schema-validate the cases; raise ``ValueError`` on any schema
    error, including a top-level document that is not an object or has no
    ``cases`` list."""
    data = _load_json("consult_cases.json")
    cases = data.get("cases") if isinstance(data, dict) else None
    if not isinstance(cases, list) or not cases:
        raise ValueError(
            "invalid consult_cases.json:\n"
            "'cases' must be a non-empty list")
    errors: list[str] = []
    seen_ids: set[str] = set()
    for case in cases:
        errors.extend(validate_case(case))
        # Responses are keyed by id, so a reused id would grade two cases
        # against one fixture; validate_case already reports a missing or
        # non-string id, so only well-formed ids are checked for reuse.
        cid = case.get("id") if isinstance(case, dict) else None
        if not isinstance(cid, str) or not cid:
            continue
        if cid in seen_ids:
            errors.append(f"{cid}: duplicate case id")
        else:
            seen_ids.add(cid)
    if errors:
        raise ValueError("invalid consult_cases.json:\n" + "\n".join(errors))
    return cases


def case_memories(case: dict) -> list[dict]:
    """The frozen memory set for a case (empty when none is referenced)."""
    rel = case.get("memories")
    return _load_json(rel) if rel else []


def observed_response(case: dict):
    """The frozen observed response for a case, or ``None`` for a case that
    records no deployed response (one written from a reported failure, which
    only the scripted and local modes can run)."""
    rel = case.get("observed_response")
    return _load_json(rel) if rel else None


def build_report(cases: list[dict], responses_by_id: dict,
                 milestone: str = "baseline") -> dict:
    """Grade each case's response(s) and summarise the run.

    A case's entry in ``responses_by_id`` is either a single response dict
    (replay mode) or a list of responses — the ``k`` repetitions of local
    mode. Every repetition is graded, and the case counts as passed only when
    *all* of them pass, so a single lucky run cannot mask a failing one; the
    per-case ``pass_fraction`` records how many passed. The first repetition is
    the representative used to classify the outcome, and the reported failing
    checks are the union across *every* repetition, so a run that fails while
    the representative passes still names its failures.

    A repetition that is a failed tool call (``ok: False`` — the backend or
    corpus was unavailable) fails grading outright, and a case with *any* such
    repetition is reported with outcome ``error`` whichever repetition it was;
    it is never counted as a (false) abstention or answer, so an environment
    failure cannot be mistaken for a tool behaviour.

    ``false_abstention`` and ``false_answer`` are counted separately, as the
    plan requires: a change that trades one for the other must be visible as a
    trade-off, not mistaken for an improvement.
    """
    per_case = []
    passed = false_abstention = false_answer = 0
    for case in cases:
        cid = case["id"]
        raw = responses_by_id.get(cid)
        runs = raw if isinstance(raw, list) else [raw]
        runs = [r for r in runs if r is not None]
        if not runs:
            per_case.append({"id": cid, "status": "no-response"})
            continue
        expect = resolve_expect(case, milestone)
        graded = [grade(r, expect) for r in runs]
        passed_runs = sum(1 for g in graded if g.passed)
        case_passed = passed_runs == len(runs)
        representative, first = runs[0], graded[0]
        any_failed = any(is_tool_failure(r) for r in runs)
        outcome = "error" if any_failed else response_outcome(representative)
        is_false_abstention = (
            outcome == "abstain" and case.get("answerable") is True)
        is_false_answer = outcome == "answer" and not first.passed
        # Union of failing checks across every repetition, in first-seen order,
        # so a failing run is named even when the representative passed.
        seen: set[str] = set()
        failures: list[str] = []
        for g in graded:
            for detail in g.failures:
                if detail not in seen:
                    seen.add(detail)
                    failures.append(detail)
        if case_passed:
            passed += 1
        if is_false_abstention:
            false_abstention += 1
        if is_false_answer:
            false_answer += 1
        per_case.append({
            "id": cid,
            "outcome": outcome,
            "passed": case_passed,
            "runs": len(runs),
            "passed_runs": passed_runs,
            "pass_fraction": passed_runs / len(runs),
            "failures": failures,
            "false_abstention": is_false_abstention,
            "false_answer": is_false_answer,
        })
    return {
        "milestone": milestone,
        "total": len(cases),
        "passed": passed,
        "false_abstention": false_abstention,
        "false_answer": false_answer,
        "cases": per_case,
    }


def render_report(summary: dict, header: dict) -> str:
    """Render a report dict as committed markdown.

    The heading names the milestone rather than a fixed ``baseline`` label, so
    a report produced for a later milestone (``--milestone step2``) is not
    mistaken for the committed baseline report.
    """
    milestone = summary["milestone"]
    if milestone == "baseline":
        subtitle = ("This records a starting point; later steps re-run the "
                    "set and show movement against it.")
    else:
        subtitle = ("This re-runs the set at a later milestone; compare it "
                    "against the committed baseline report.")
    lines = [
        f"# Consult evaluation report — milestone `{milestone}`",
        "",
        "Generated by `tools/mcp/manager/eval/run_consult_eval.py`. "
        + subtitle,
        "",
    ]
    for key, value in header.items():
        lines.append(f"- **{key}**: {value}")
    lines.extend([
        "",
        f"- **cases**: {summary['total']}",
        f"- **passed**: {summary['passed']}",
        f"- **false abstentions**: {summary['false_abstention']}",
        f"- **false answers**: {summary['false_answer']}",
        "",
        "| Case | Outcome | Passed | Pass frac | False-abstention | False-answer | Failing checks |",
        "|------|---------|--------|-----------|------------------|--------------|----------------|",
    ])
    for c in summary["cases"]:
        if c.get("status") == "no-response":
            lines.append(f"| {c['id']} | — | — | — | — | — | (no response) |")
            continue
        failing = "; ".join(c["failures"]) if c["failures"] else "—"
        frac = f"{c['passed_runs']}/{c['runs']}"
        lines.append(
            f"| {c['id']} | {c['outcome']} | {'yes' if c['passed'] else 'no'} "
            f"| {frac} "
            f"| {'yes' if c['false_abstention'] else 'no'} "
            f"| {'yes' if c['false_answer'] else 'no'} | {failing} |"
        )
    lines.append("")
    return "\n".join(lines)


def _docs_stage_copies() -> list[str]:
    """The build-context sources the Dockerfile's ``docs`` stage copies.

    Parsed from the Dockerfile itself so the staged corpus tracks the shipped
    one: a ``COPY`` added or removed there changes what local evaluation sees,
    which is the whole point of staging rather than reading the live checkout.
    Only plain (non-``--from``) copies in the ``AS docs`` stage are returned,
    as repo-root-relative source paths. Every ``FROM`` — named or not — ends
    the current stage, so copies in an unnamed runtime stage that follows
    ``docs`` are not mistaken for corpus copies.
    """
    sources: list[str] = []
    in_docs_stage = False
    with open(_DOCKERFILE, encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if re.match(r"(?i)^FROM\s", stripped):
                alias = re.search(r"(?i)\bAS\s+(\S+)\s*$", stripped)
                in_docs_stage = (alias is not None
                                 and alias.group(1).lower() == "docs")
                continue
            if not in_docs_stage:
                continue
            copy_match = re.match(r"(?i)^COPY\s+(\S+)\s+(\S+)", stripped)
            if copy_match and not stripped.lower().startswith("copy --from"):
                sources.append(copy_match.group(1).rstrip("/"))
    return sources


def _stage_corpus() -> str:
    """Stage the pruned documentation corpus into a temp directory.

    Mirrors the Dockerfile ``docs`` stage: copy each listed source from the
    checkout, keeping only ``*.md``/``*.html`` (the ``DocsRetriever`` prune).
    Returns the staging root; ``AR_DOCS_DIR`` should point at its ``docs``
    subdirectory. The caller owns the directory and must remove it.
    """
    staging = tempfile.mkdtemp(prefix="consult-eval-corpus-")
    for src in _docs_stage_copies():
        abs_src = os.path.join(_REPO_ROOT, src)
        if os.path.isdir(abs_src):
            for root, _dirs, files in os.walk(abs_src):
                for name in files:
                    if not name.endswith((".md", ".html")):
                        continue
                    rel = os.path.relpath(os.path.join(root, name), _REPO_ROOT)
                    dest = os.path.join(staging, rel)
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    shutil.copyfile(os.path.join(root, name), dest)
        elif os.path.isfile(abs_src) and src.endswith((".md", ".html")):
            dest = os.path.join(staging, src)
            os.makedirs(os.path.dirname(dest) or staging, exist_ok=True)
            shutil.copyfile(abs_src, dest)
    return staging


# The date every case without its own ``now`` is evaluated at.
DEFAULT_EVAL_NOW = "2026-10-19T12:00:00+00:00"


class FixtureMemoryClient:
    """A memory client answering from a case's frozen notes.

    ``search`` honours the namespace (a note without one is in ``default``),
    as the real store does, so namespace widening is measured rather than
    assumed; ``namespace_stats`` reports the namespaces the notes use.
    """

    def __init__(self, memories: list[dict]):
        self.memories = memories
        self.searched: list[str] = []

    def namespace_stats(self, repo_url=None, branch=None):
        names: list[str] = []
        for memory in self.memories:
            name = memory.get("namespace", "default")
            if name not in names:
                names.append(name)
        return [{"namespace": n, "count": 1} for n in names]

    def search(self, query, namespace="default", limit=5, repo_url=None,
               branch=None):
        self.searched.append(namespace)
        return [m for m in self.memories
                if m.get("namespace", "default") == namespace][:limit]

    def search_by_branch(self, **_kwargs):
        return []


class ScriptedModel:
    """A stub model whose answer is fixed per case.

    The script names evidence by what it is rather than by ID, since IDs are
    assigned at retrieval time: ``[D:<path>]`` is the first documentation
    chunk from that file, ``[I:<path>]`` the first index fact about it,
    ``[N:<memory id>]`` that note; ``[D:<path>@<line>]`` narrows to the item
    covering that line. A placeholder naming evidence that was not
    retrieved becomes an ID that was never presented (``[D99]``) — what a
    model inventing a citation produces — so the verifier sees it as such.
    """

    _PLACEHOLDER = re.compile(r"\[([DIN]):([^\]@]+)(?:@(\d+))?\]")

    def __init__(self, text: str):
        self.text = text
        self.prompts: list[str] = []

    def resolve(self, evidence) -> str:
        """The script with its placeholders replaced by evidence IDs."""
        def _id(match: re.Match) -> str:
            kind, ref = match.group(1), match.group(2).strip()
            line = int(match.group(3)) if match.group(3) else 0
            for item in evidence.items():
                if kind == "N" and item.kind == "note" and item.memory_id == ref:
                    return f"[{item.id}]"
                if kind in "DI" and item.kind == ("doc" if kind == "D" else "index") \
                        and (item.file == ref or item.file.endswith("/" + ref)) \
                        and (not line or item.start <= line <= item.end):
                    return f"[{item.id}]"
            return f"[{kind}99]"
        return self._PLACEHOLDER.sub(_id, self.text)

    def consult(self, question, evidence="", extra_context=None, history=None):
        from inference import Synthesis, build_consult_prompt
        self.prompts.append(build_consult_prompt(
            question, evidence, extra_context, history=history))
        return Synthesis(self.resolve(evidence), "scripted")

    def synthesize(self, prompt, system=None, max_tokens=1024, temperature=0.3):
        from inference import Synthesis
        return Synthesis("(scripted summary)", "scripted")


def _pinned_index(index, now: str):
    """The checkout's snapshot index with its corpus identity pinned to the
    case: commit ``checkout`` built at ``now``, so the recency window is
    measured from a fixed date."""
    from snapshot_index import SnapshotIndex
    if index is None:
        return None
    data = dict(index.data)
    data["corpus"] = {"commit": "checkout", "built_at": now}
    return SnapshotIndex(data)


def _run_inprocess(cases: list[dict], k: int, model_for) -> dict:
    """Run the real consult tool k times per case with frozen inputs.

    ``model_for(case)`` returns the model to synthesise with (a backend, or a
    :class:`ScriptedModel`). The corpus is staged from the Dockerfile's own
    ``COPY`` directives and ``AR_DOCS_DIR`` is pointed at it before the
    server is imported, so ``DocsRetriever`` reads the shipped corpus rather
    than the live checkout; the snapshot index is built from the same trees.
    Returns, per case, the list of all ``k`` responses.

    Each repetition seeds a synthetic ``eval`` scope (the case's ``caller``
    scopes, ``memory-read`` by default) with no job token, so the call is
    stateless; the caller's scope context is snapshotted first and restored
    on the way out, so a later tool call in the same process does not
    inherit that authorization.
    """
    from datetime import datetime
    from unittest import mock

    import auth

    staging = _stage_corpus()
    prior_docs_dir = os.environ.get("AR_DOCS_DIR")
    prior_scopes = auth._snapshot_scopes()
    os.environ["AR_DOCS_DIR"] = os.path.join(staging, "docs")
    try:
        import server  # imported after AR_DOCS_DIR is set so the corpus binds
        import consult_engine
        from branch_state import FixedBranchStates
        from consult_tiers import parse_time
        from snapshot_index import SnapshotIndex, build

        # ``_get_docs`` memoises the retriever in a module global. A prior local
        # run in this process may have bound it to a staging tree that has since
        # been removed, so clear the cache to force a rebind against this run's
        # freshly staged corpus rather than reading an emptied one.
        server._docs_retriever = None
        server._docs_init_failed = False
        _rebind_docs_retriever()
        checkout_index = SnapshotIndex(build(
            _REPO_ROOT, "checkout", DEFAULT_EVAL_NOW, _docs_stage_copies()))

        responses: dict = {}
        for case in cases:
            now = case.get("now", DEFAULT_EVAL_NOW)
            caller = case.get("caller", {})
            client = FixtureMemoryClient(case_memories(case))
            runs: list[dict] = []
            for _ in range(max(1, k)):
                # consult enforces memory-read; seed the request scope the auth
                # middleware would normally establish before each invocation.
                server._set_scopes(caller.get("scopes", ["memory-read"]),
                                   label="eval")
                with mock.patch.object(server, "_get_memory_client",
                                       return_value=client), \
                        mock.patch.object(server, "_get_token_workstream_id",
                                          return_value="", create=True), \
                        mock.patch.object(server, "_get_token_job_id",
                                          return_value="", create=True), \
                        mock.patch.object(server, "_get_llm",
                                          return_value=model_for(case),
                                          create=True), \
                        mock.patch.object(consult_engine, "snapshot_index",
                                          return_value=_pinned_index(
                                              checkout_index, now)), \
                        mock.patch.object(consult_engine, "clock",
                                          return_value=parse_time(now) or
                                          datetime.fromisoformat(DEFAULT_EVAL_NOW)), \
                        mock.patch.object(consult_engine, "_branch_states",
                                          FixedBranchStates(
                                              case.get("branch_states"))), \
                        mock.patch.object(consult_engine, "resolve_caller",
                                          return_value=_caller(caller)):
                    runs.append(server.consult(
                        question=case["question"],
                        keywords=case.get("keywords"),
                        repo_url="git@github.com:almostrealism/common.git",
                    ))
            responses[case["id"]] = runs
        return responses
    finally:
        shutil.rmtree(staging, ignore_errors=True)
        # Drop the retriever bound to the staging tree we just removed, and
        # restore AR_DOCS_DIR, so a later run (or any later consult) in this
        # process does not read a deleted corpus or inherit this run's env.
        server_mod = sys.modules.get("server")
        if server_mod is not None:
            server_mod._docs_retriever = None
            server_mod._docs_init_failed = False
        if prior_docs_dir is None:
            os.environ.pop("AR_DOCS_DIR", None)
        else:
            os.environ["AR_DOCS_DIR"] = prior_docs_dir
        _rebind_docs_retriever()
        auth._restore_scopes(prior_scopes)


def _caller(spec: dict):
    """The frozen ``Caller`` of a case (target ``master`` by default)."""
    from consult_tiers import Caller
    return Caller(branch=spec.get("branch", ""),
                  target_branch=spec.get("target_branch", "master"))


def scripted_answer(case: dict) -> str:
    """The canned model answer for a case; ``Not documented.`` when the case
    names none, which is what a model with nothing to go on should say."""
    rel = case.get("scripted")
    if not rel:
        return "Not documented.\nBASIS: none\nCONFLICTS: none"
    with open(os.path.join(_EVAL_DIR, rel), encoding="utf-8") as handle:
        return handle.read()


def _run_scripted(cases: list[dict]) -> dict:
    """Run every case once through the real pipeline with its canned answer."""
    return _run_inprocess(cases, 1, lambda case: ScriptedModel(scripted_answer(case)))


def _run_local(cases: list[dict], k: int) -> dict:
    """Run every case k times through the real pipeline and a real backend
    (the one ``AR_CONSULTANT_BACKEND`` selects)."""
    from inference import create_backend
    backend: list = []

    def _backend(_case):
        if not backend:
            backend.append(create_backend())
        return backend[0]
    return _run_inprocess(cases, k, _backend)


def _rebind_docs_retriever() -> None:
    """Re-read ``AR_DOCS_DIR`` into an already-imported ``docs_retriever``.

    ``docs_retriever`` resolves its default corpus root (``COMMON_DIR``) from
    ``AR_DOCS_DIR`` once, at import time, so clearing the server's cached
    retriever alone would rebuild it against whichever tree was current when
    the module was first imported. Reloading makes the next ``DocsRetriever``
    bind to the current environment; a module not yet imported reads it fresh
    on first import and needs nothing.
    """
    module = sys.modules.get("docs_retriever")
    if module is not None:
        importlib.reload(module)


def report_passed(summary: dict) -> bool:
    """Whether every case in a report has a response and passed.

    A ``no-response`` case counts as a failure: a gate that skipped a case
    must not report success for it. A report with no cases at all is a
    failure too: ``all(...)`` is vacuously true for an empty list, so without
    this guard a malformed or accidentally emptied case set would let
    ``--check`` exit 0 having graded nothing.
    """
    cases = summary["cases"]
    return bool(cases) and all(c.get("passed") is True for c in cases)


def main(argv=None) -> int:
    """Run the evaluation set and write or print the report.

    Returns 0 by default, because the committed baseline is known to fail
    (it records the starting point, not a target). With ``--check`` the run is
    a gate: it returns 1 unless every case passed, so a caller such as CI or a
    milestone script cannot mistake a failing evaluation for a passing one.
    """
    parser = argparse.ArgumentParser(description="Run the consult eval set.")
    parser.add_argument("--mode", choices=["replay", "scripted", "local"],
                        default="replay")
    parser.add_argument("--milestone", default="baseline")
    parser.add_argument("--k", type=int, default=3,
                        help="repetitions per case in local mode")
    parser.add_argument("--out", default="",
                        help="write the markdown report to this path")
    parser.add_argument("--check", action="store_true",
                        help="exit 1 unless every case passed")
    parser.add_argument("--dump", default="",
                        help="also write the raw responses as JSON here")
    args = parser.parse_args(argv)

    cases = load_cases()
    if args.mode == "replay":
        responses = {c["id"]: observed_response(c) for c in cases}
        backend = "replay (frozen observed responses)"
    elif args.mode == "scripted":
        responses = _run_scripted(cases)
        backend = "scripted (canned answers through the live pipeline)"
    else:
        responses = _run_local(cases, args.k)
        backend = os.environ.get("AR_CONSULTANT_BACKEND", "auto")

    if args.dump:
        with open(args.dump, "w", encoding="utf-8") as handle:
            json.dump(responses, handle, indent=2, default=str)
    summary = build_report(cases, responses, args.milestone)
    header = {"mode": args.mode, "backend": backend,
              "cases_file": "consult_cases.json"}
    report = render_report(summary, header)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(report)
    else:
        print(report)
    if args.check and not report_passed(summary):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
