"""
The consult pipeline: retrieval, evidence, synthesis and verification.

``consult`` (``memory_tools``) validates its arguments and hands the request
here. One turn runs:

1. **Nonexistence.** A question whose every named file is missing from the
   snapshot index, and which asks how those files behave rather than where to
   create one, is answered directly — "no file named X exists in the
   snapshot" — without asking the model to describe something that is not
   there. A question that also names a file that does exist, or that asks
   where to place a new one, keeps its answer.
2. **Retrieval.** Documentation chunks (with ranges and heading trails);
   notes from every semantic namespace, merged by distance; and, for the
   scripts, types and constants the question names, facts from the snapshot
   index.
3. **Tiering.** Each note gets a tier from :func:`consult_tiers.tier`, using
   its age, its branch's merge state and whether the names it uses still
   exist. At most one historical or stale note is presented.
4. **Synthesis** over the evidence list, citing items by ID.
5. **Post-processing.** The ``BASIS:``/``CONFLICTS:`` trailer is parsed (and
   never trusted as written: the basis is recomputed from the IDs actually
   cited); the citation verifier marks unsupported sentences; sentences that
   rest only on historical notes move to ``history``; IDs are expanded to the
   locations they stand for; abstention is reported with what was searched.

Everything reached in ``server`` goes through the module, so tests that patch
``server._get_llm`` and friends still apply.
"""

import logging
import os
import re
from datetime import datetime, timezone
from typing import Callable, Optional

import server
from branch_state import DEGRADED_REASON as BRANCH_STATE_REASON
from branch_state import BranchStateResolver
from citation_verifier import REASON_TIER, salient_tokens, verify
from consult_evidence import (
    HISTORY_ONLY_TIERS, KIND_DOC, KIND_INDEX, KIND_NOTE, NOTE_FACT_TIERS,
    TIER_CURRENT, TIER_ORDER, TIER_STALE, EvidenceList, marker_ids,
)
from consult_tiers import Caller, classify_mentions, extract_mentions, tier
from snapshot_index import SnapshotIndex

# Notes retrieved per namespace, and the most presented after tier selection.
MEMORY_CANDIDATES = 8
MEMORY_PRESENTED = 5

# Namespaces searched at most per consult. Namespaces are caller-defined, so
# their number is not bounded by anything consult controls.
MAX_NAMESPACES = 6

# Index facts presented at most.
MAX_INDEX_FACTS = 4

# The sentence that opens an answer resting on notes. Generated here, never
# by the model, so a caller can rely on its wording.
NOTES_BASIS_SENTENCE = ("The documentation snapshot does not cover this. "
                        "The following rests on agent notes:")

# The sentence that opens a notes-only answer whose central claim the verifier
# could not support. Generated here, never by the model, so a weak model's
# confident "Yes"/"No" lead is replaced rather than passed through.
HEDGE_SENTENCE = ("This is unclear from the available evidence, and the notes "
                  "below are unverified — treat the following as unconfirmed:")
_LEADING_YES_NO = re.compile(r"^\s*(?:yes|no)\b[\s,.:;!—–-]*",
                             re.IGNORECASE)

BASIS_VALUES = ("documentation", "documentation+notes", "notes", "none")

_BASIS_LINE = re.compile(r"^\s*BASIS\s*:\s*(.+?)\s*$", re.IGNORECASE | re.MULTILINE)
_CONFLICTS_LINE = re.compile(r"^\s*CONFLICTS\s*:\s*(.*?)\s*$",
                             re.IGNORECASE | re.MULTILINE)
_NOT_DOCUMENTED = re.compile(r"^\s*\**not documented\**\s*[.!]?\s*", re.IGNORECASE)
_FILE_MENTION = re.compile(r"\.(?:sh|py|java|ya?ml|md|json|toml)$")
# A request asking where to put, create, or move a file, rather than how an
# existing one behaves. Such a request names a file the snapshot is expected
# not to contain yet, so a missing name is the premise, not a dead end.
_PLACEMENT_QUESTION = re.compile(
    r"\b(where|create|creating|put|place|placement|belongs?|rename|renaming|"
    r"relocate|move|moving|new file|add(?:ing)? a)\b", re.IGNORECASE)
_PRESENT_TENSE = re.compile(
    r"\b(exists?|is the|owns|handles|writes|runs|implements|currently|now)\b",
    re.IGNORECASE)

_branch_states = BranchStateResolver()
_index_cache: dict = {}


def _now() -> datetime:
    """The current time. Replaced by the evaluation to freeze the clock."""
    return datetime.now(timezone.utc)


clock: Callable[[], datetime] = _now


def snapshot_index() -> Optional[SnapshotIndex]:
    """The snapshot index named by ``AR_SNAPSHOT_INDEX``, loaded once per
    path; ``None`` when no index ships with this server (a checkout)."""
    path = os.environ.get("AR_SNAPSHOT_INDEX", "").strip()
    if not path:
        return None
    if path not in _index_cache:
        _index_cache[path] = SnapshotIndex.load(path)
    return _index_cache[path]


def corpus_identity(index: Optional[SnapshotIndex]) -> dict:
    """``{commit, built_at}`` of the corpus: from the index when present,
    otherwise an explicit ``unknown`` — never an empty value that looks like
    an identity."""
    if index is not None:
        return index.corpus
    return {"commit": "unknown", "built_at": ""}


def resolve_caller(repo_url: str, branch: str, workstream_id: str) -> Caller:
    """The caller's own branch and the target branch of the question.

    The target is the workstream's ``baseBranch`` (default ``master``); the
    caller's branch is the explicit ``branch`` or the workstream's default
    branch. The workstream is the explicit one or the one bound to the
    request's temp token.
    """
    ws_id = workstream_id
    if not ws_id and not repo_url:
        try:
            ws_id = server._get_token_workstream_id() or ""
        except Exception:  # noqa: BLE001 - caller context is best effort
            ws_id = ""
    ws = None
    if ws_id:
        try:
            ws = server._find_workstream(ws_id)
        except Exception:  # noqa: BLE001
            ws = None
    ws = ws or {}
    return Caller(branch=branch or ws.get("defaultBranch", "") or "",
                  target_branch=ws.get("baseBranch") or "master")


def _non_semantic_namespaces() -> set:
    raw = os.environ.get("AR_MEMORY_NON_SEMANTIC_NAMESPACES", "messages")
    return {n.strip() for n in raw.split(",") if n.strip()}


def search_memories(client, query: str, repo: str, branch: str) -> tuple[list, list, list]:
    """Search every semantic namespace and merge by distance.

    Returns ``(memories, namespaces_searched, degraded_reasons)``. The
    namespace list comes from the store, newest write first; ``default`` is
    always searched; at most :data:`MAX_NAMESPACES` are, and a cut is
    reported as ``memory_namespaces_capped`` rather than made silently.
    Every search carries the same resolved repo/branch filters.
    """
    reasons: list = []
    names: list = []
    try:
        stats = client.namespace_stats(repo_url=repo or None, branch=None)
        if isinstance(stats, list):
            names = [s["namespace"] for s in stats
                     if isinstance(s, dict) and isinstance(s.get("namespace"), str)]
    except Exception:  # noqa: BLE001 - fall back to the default namespace
        names = []
    skip = _non_semantic_namespaces()
    semantic = ["default"] + [n for n in names if n not in skip and n != "default"]
    if len(semantic) > MAX_NAMESPACES:
        reasons.append("memory_namespaces_capped")
        semantic = semantic[:MAX_NAMESPACES]

    merged: dict = {}
    for namespace in semantic:
        try:
            found = client.search(query=query, namespace=namespace,
                                  limit=MEMORY_CANDIDATES,
                                  repo_url=repo or None, branch=branch or None)
        except ConnectionError as e:
            logging.getLogger("ar-manager").warning(
                "Memory search failed during consult: %s", e)
            if "memory_search" not in reasons:
                reasons.append("memory_search")
            continue
        for memory in found or []:
            if not isinstance(memory, dict):
                continue
            memory = dict(memory)
            memory.setdefault("namespace", namespace)
            key = memory.get("id") or memory.get("content")
            if key not in merged:
                merged[key] = memory
    ordered = sorted(merged.values(), key=lambda m: (
        m.get("score") if isinstance(m.get("score"), (int, float)) else float("inf")))
    return ordered[:MEMORY_CANDIDATES], semantic, reasons


def select_notes(tiered: list[tuple[dict, str]], caller: Caller) -> list:
    """Choose the notes to present from ``(memory, tier)`` pairs.

    Ordered by tier (current first), the caller's own branch first within a
    tier, then by distance; at most one historical or stale note; at most
    :data:`MEMORY_PRESENTED` in all.
    """
    def rank(pair):
        memory, item_tier = pair
        own = 0 if caller.branch and memory.get("branch") == caller.branch else 1
        score = memory.get("score")
        return (TIER_ORDER.index(item_tier), own,
                score if isinstance(score, (int, float)) else float("inf"))

    chosen: list = []
    history_taken = False
    for pair in sorted(tiered, key=rank):
        if pair[1] in HISTORY_ONLY_TIERS:
            if history_taken:
                continue
            history_taken = True
        chosen.append(pair)
        if len(chosen) >= MEMORY_PRESENTED:
            break
    return chosen


def file_mentions(question: str, keywords, index) -> tuple[list[str], list[str]]:
    """The file-shaped names the request uses, split into those the snapshot
    contains (``present``) and those it lacks (``missing``)."""
    present: list[str] = []
    missing: list[str] = []
    if index is None:
        return present, missing
    text = " ".join([question] + [k for k in (keywords or []) if isinstance(k, str)])
    for mention in extract_mentions(text):
        if not _FILE_MENTION.search(mention):
            continue
        bucket = present if index.has_path(mention) else missing
        if mention not in bucket:
            bucket.append(mention)
    return present, missing


def nonexistent_mentions(question: str, keywords, index) -> list[str]:
    """Files the request names that warrant a direct "no such file" answer.

    Only when *every* file the request names is missing, and the request asks
    about those files rather than where to create or move one, is answering
    the model the wrong move. When a named file does exist (an existing file
    named alongside a planned one) or the request is about placing a file, the
    missing names are left for the model to address with the retrieved
    evidence instead of short-circuiting the whole answer.
    """
    present, missing = file_mentions(question, keywords, index)
    if not missing or present:
        return []
    if _PLACEMENT_QUESTION.search(question):
        return []
    return missing


def parse_trailer(text: str) -> tuple[str, Optional[str], list, bool]:
    """Split the model's output into body and trailer.

    Returns ``(body, stated_basis, reported_conflicts, trailer_present)``;
    ``stated_basis`` is ``None`` when the line is missing or names no known
    basis.
    """
    basis_match = _BASIS_LINE.search(text)
    conflicts_match = _CONFLICTS_LINE.search(text)
    stated = None
    if basis_match:
        value = basis_match.group(1).strip().strip("`*").lower().replace(" ", "")
        stated = value if value in BASIS_VALUES else None
    conflicts: list = []
    if conflicts_match:
        for part in re.split(r";\s*", conflicts_match.group(1)):
            if not part.strip() or part.strip().lower().rstrip(".") == "none":
                continue
            conflicts.append(part.strip())
    body = _CONFLICTS_LINE.sub("", _BASIS_LINE.sub("", text)).strip()
    return body, stated, conflicts, bool(basis_match or conflicts_match)


def compute_basis(ids: list, evidence: EvidenceList) -> str:
    """The answer basis from the evidence IDs the answer validly cites.

    Documentation and index facts count as documentation; a note counts only
    when it is current or branch-local, because historical and stale notes
    are forbidden from supplying present fact. An ID that names no presented
    item cannot raise the basis.
    """
    docs = notes = False
    for item_id in ids:
        item = evidence.get(item_id)
        if item is None:
            continue
        if item.kind in (KIND_DOC, KIND_INDEX):
            docs = True
        elif item.tier in NOTE_FACT_TIERS:
            notes = True
    if docs and notes:
        return "documentation+notes"
    if docs:
        return "documentation"
    return "notes" if notes else "none"


def reported_conflicts(lines: list, evidence: EvidenceList) -> list[dict]:
    """``CONFLICTS:`` entries whose IDs were all presented."""
    out = []
    for line in lines:
        ids = re.findall(r"\b[DNI]\d+\b", line)
        if len(ids) >= 2 and all(evidence.get(i) is not None for i in ids):
            summary = re.sub(r"^[^—\-:]*[—\-:]\s*", "", line).strip() or line
            out.append({"items": ids, "kind": "reported", "summary": summary})
    return out


def structural_conflicts(evidence: EvidenceList) -> list[dict]:
    """Conflicts code can see without the model.

    A stale note is a structural conflict only when it asserts that a name
    the snapshot lacks is present or current; a note that frames the name as
    history ("removed", "renamed to") is stale but contradicts nothing. Two
    current or branch-local notes from different branches naming the same
    symbol are listed together as a potential conflict.
    """
    out = []
    notes = evidence.of_kind(KIND_NOTE)
    for note in notes:
        absent = note.named_symbols.get("absent", [])
        if note.tier == TIER_STALE and absent and _PRESENT_TENSE.search(note.text) \
                and not re.search(r"\b(removed|renamed|deleted|formerly|used to)\b",
                                  note.text, re.IGNORECASE):
            out.append({"items": [note.id], "kind": "structural",
                        "summary": "asserts as current: " + ", ".join(absent)
                        + " — absent from the snapshot"})
    live = [n for n in notes if n.tier in NOTE_FACT_TIERS]
    for i, first in enumerate(live):
        for second in live[i + 1:]:
            if first.branch == second.branch:
                continue
            shared = (set(first.named_symbols.get("present", []))
                      & set(second.named_symbols.get("present", [])))
            if shared:
                out.append({"items": [first.id, second.id], "kind": "potential",
                            "summary": "notes from branches "
                            f"{first.branch} and {second.branch} both describe "
                            + ", ".join(sorted(shared))})
    return out


_EXIT_CLAIM = re.compile(
    r"\bexit(?:s|ed|ing)?(?:\s+with)?(?:\s+(?:status|code))?\s+`?(\d+)`?",
    re.IGNORECASE)


def value_conflicts(evidence: EvidenceList) -> tuple[list[dict], dict]:
    """Value-level conflicts between notes and snapshot facts, in code.

    Today this covers one value kind: a script's exit code. A note sentence
    that names a script and says it exits with a code the script contains no
    literal ``exit`` for is contradicted by the snapshot index fact for that
    script. Returns the conflicts and, per note ID, the contradicted values,
    so the verifier can flag an answer that repeats them.
    """
    conflicts: list = []
    contradicted: dict = {}
    for fact in evidence.of_kind(KIND_INDEX):
        if fact.label != "exit codes":
            continue
        codes = set(re.findall(r"exit (\d+)", fact.text))
        script = fact.file.rsplit("/", 1)[-1]
        for note in evidence.of_kind(KIND_NOTE):
            claimed: set = set()
            for sentence in re.split(r"(?<=[.!?])\s+|\n+", note.text):
                if script in sentence:
                    claimed |= {m.group(1) for m in _EXIT_CLAIM.finditer(sentence)}
            claimed -= codes
            if claimed:
                contradicted.setdefault(note.id, set()).update(claimed)
                conflicts.append({
                    "items": [note.id, fact.id], "kind": "value",
                    "summary": f"{note.id} says {script} exits "
                    f"{', '.join(sorted(claimed))}; the snapshot's literal exit "
                    f"codes for it are {', '.join(sorted(codes))}"})
    return conflicts, contradicted


def _relevant_current_note(question: str, keywords, evidence: EvidenceList) -> bool:
    """Whether some current note shares a salient term with the question —
    a candidate missed answer when the model abstains anyway."""
    terms = {t.lower() for t in extract_mentions(question)}
    for kw in keywords or []:
        if isinstance(kw, str) and len(kw) >= 4:
            terms.add(kw.lower())
    for note in evidence.of_kind(KIND_NOTE):
        if note.tier == TIER_CURRENT and any(t in note.text.lower() for t in terms):
            return True
    return False


def _uncovered_terms(question: str, answer: str, cited: list) -> list[str]:
    """Checkable names from the question that neither the answer nor any
    cited item mentions — what a partial answer leaves uncovered."""
    haystack = (answer + "\n" + "\n".join(it.haystack() for it in cited)).lower()
    subjects, _ = salient_tokens(question)
    return [s for s in subjects if s.lower() not in haystack]


class ConsultTurn:
    """One consult turn over fixed inputs.

    Args:
        question, context, keywords: The request.
        repo, branch: Resolved memory scope; ``scope_error`` set when the
            scope resolver failed, in which case notes are not searched.
        caller: Caller and target branch for tiering.
        conversation: The conversation this turn belongs to, if any.
    """

    def __init__(self, question: str, context: str, keywords, repo: str,
                 branch: str, scope_error: bool, caller: Caller,
                 conversation=None):
        self.question = question
        self.context = context
        self.keywords = keywords
        self.repo = repo
        self.branch = branch
        self.scope_error = scope_error
        self.caller = caller
        self.conversation = conversation
        self.index = snapshot_index()
        self.corpus = corpus_identity(self.index)
        self.degraded_reasons: list = []
        self.evidence = EvidenceList(
            self.corpus, conversation.pool if conversation else None)
        self.namespaces: list = []
        # The terms this turn actually retrieves on, set in run(): the request's
        # keywords merged with those carried from earlier conversation turns.
        self.retrieval_keywords: list = []

    def _retrieval_terms(self) -> list:
        """The terms this turn retrieves on.

        The caller's keywords (or, on a follow-up that supplies none, the new
        question's own terms), merged with the keywords and resolved subjects
        carried from earlier turns, so a follow-up retrieves with the
        conversation's context rather than the new question's words alone. On
        the first turn there is nothing to carry, so this returns the caller's
        keywords unchanged — empty when none were given, which keeps the
        whole-question retrieval path for a plain first question.
        """
        from docs_retriever import _extract_keywords

        own = [k for k in (self.keywords or []) if isinstance(k, str) and k.strip()]
        carried = self.conversation.carried_terms() if self.conversation else []
        if not carried:
            return own
        if not own:
            own = _extract_keywords(self.question)
        merged: list = []
        for term in own + carried:
            if term not in merged:
                merged.append(term)
        return merged

    def run(self, docs, client, llm) -> dict:
        """Run the turn and return the tool response."""
        from docs_retriever import keyword_guidance

        missing = nonexistent_mentions(self.question, self.keywords, self.index)
        if missing:
            return self._nonexistent(missing, keyword_guidance(self.keywords))

        self.retrieval_keywords = self._retrieval_terms()
        try:
            retrieval = (docs.get_context_for_keywords(self.retrieval_keywords)
                         if self.retrieval_keywords
                         else docs.get_context_for_query(self.question))
        except OSError as e:
            return {"ok": False, "error": f"Documentation search failed: {e}"}
        doc_results = retrieval.get("markdown_results", [])
        html_refs = retrieval.get("html_refs", [])

        if self.conversation is not None:
            for item in self.conversation.carried():
                self.evidence.add(item)
        for r in doc_results:
            self.evidence.add_doc(r)
        if self.index is not None:
            mentions = extract_mentions(
                " ".join([self.question] + self.retrieval_keywords))
            # A keyword can name a script by its stem (``drain-agent-jobs``),
            # which extract_mentions does not surface; offer the terms
            # themselves so the index can resolve such a stem to its script.
            for term in self.retrieval_keywords:
                if term not in mentions:
                    mentions.append(term)
            for fact in self.index.facts_for(mentions, MAX_INDEX_FACTS):
                self.evidence.add_index(fact)
        presented = self._notes(client)

        result = {
            "ok": True,
            "sources": sorted({r["file"] for r in doc_results}),
            "html_refs": html_refs,
            "related_memories": [self._related(m, it) for m, it in presented],
            "corpus": self.corpus,
        }

        if llm is None:
            synthesis, reason = None, "no inference backend could be constructed"
        else:
            history = self.conversation.history() if self.conversation else None
            synthesis = llm.consult(self.question, evidence=self.evidence,
                                    extra_context=self.context or None,
                                    history=history or None)
            reason = synthesis.reason if synthesis.degraded else None

        result["evidence"] = self.evidence.to_response()
        if synthesis is None or synthesis.degraded:
            result["degraded"] = True
            result["note"] = (
                f"No answer was synthesized ({reason}). The sources, html_refs, "
                "evidence and related_memories fields were retrieved "
                "successfully and hold the documentation matching this "
                "question — read them directly." + keyword_guidance(self.keywords))
            self._finish(result, [])
            return result

        cited = self._post_process(synthesis.text, result, keyword_guidance)
        self._finish(result, cited)
        return result

    def _notes(self, client) -> list:
        """Search, tier and select the notes; add them to the evidence."""
        if client is None:
            return []
        if self.scope_error:
            self.degraded_reasons.append("memory_scope")
            return []
        query = " ".join([self.question] + self.retrieval_keywords)
        memories, self.namespaces, reasons = search_memories(
            client, query, self.repo, self.branch)
        self.degraded_reasons.extend(reasons)
        from memory_text import prefers_reformulated, present
        memories, _ = present(memories, reformulated=prefers_reformulated())

        tiered = []
        branch_degraded = False
        for memory in memories:
            symbols = classify_mentions(
                extract_mentions(memory.get("content", "")), self.index)
            state, degraded = _branch_states.resolve(
                memory.get("repo_url") or self.repo,
                str(memory.get("branch") or ""), self.caller)
            branch_degraded = branch_degraded or degraded
            item_tier, why = tier(memory, state, self.caller,
                                  self.corpus.get("built_at", ""),
                                  symbols["absent"], now=clock())
            tiered.append((memory, item_tier, state, symbols, why))
        if branch_degraded:
            self.degraded_reasons.append(BRANCH_STATE_REASON)

        chosen = select_notes([(t[0], t[1]) for t in tiered], self.caller)
        lookup = {id(t[0]): t for t in tiered}
        presented = []
        for memory, _ in chosen:
            _, item_tier, state, symbols, why = lookup[id(memory)]
            item = self.evidence.add_note(memory, item_tier, state.describe(),
                                          symbols, why)
            presented.append((memory, item))
        return presented

    @staticmethod
    def _related(memory: dict, item) -> dict:
        """A ``related_memories`` entry: as before, plus provenance. ``score``
        is an L2 distance — lower is closer — and is also given as
        ``distance`` so it cannot be read as a similarity."""
        return {
            "content": memory.get("content", ""), "score": memory.get("score"),
            "distance": memory.get("score"), "id": memory.get("id"),
            "created_at": memory.get("created_at"),
            "branch": memory.get("branch"), "namespace": memory.get("namespace"),
            "tier": item.tier, "evidence_id": item.id,
        }

    def _post_process(self, text: str, result: dict, keyword_guidance) -> list:
        """Turn the model's output into the answer fields. Returns the cited
        evidence items."""
        # The stated basis is parsed only so the line leaves the answer. The
        # computed basis is authoritative, and the model cannot predict it:
        # it rests on note tiers and on which sentences the verifier keeps,
        # so a disagreement is the expected case, not a format fault worth
        # reporting to the caller.
        body, _, conflict_lines, trailer_present = parse_trailer(text)
        warnings = []
        if not trailer_present:
            warnings.append("trailer missing")

        abstained_by_model = bool(_NOT_DOCUMENTED.match(body))
        if abstained_by_model:
            body = _NOT_DOCUMENTED.sub("", body, count=1).strip()

        found_value_conflicts, contradicted = value_conflicts(self.evidence)
        report = verify(body, self.evidence, contradicted)
        basis = compute_basis(report.supporting_ids(), self.evidence)

        # Sentences resting only on historical or stale notes are history,
        # whatever their tense: those notes never supply present fact.
        history_sentences = [s for s in report.sentences_resting_on(HISTORY_ONLY_TIERS)
                             if REASON_TIER not in s.reasons]
        support_gone = _support_gone(basis, report, history_sentences)
        answer = report.annotated
        for s in sorted(history_sentences, key=lambda s: s.start, reverse=True):
            answer = _remove_span(answer, s)
        answer = answer.strip()
        if history_sentences:
            result["history"] = self.evidence.expand(
                " ".join(s.text.strip() for s in history_sentences))

        cited_ids = []
        for item_id in marker_ids(body):
            if self.evidence.get(item_id) is not None and item_id not in cited_ids:
                cited_ids.append(item_id)
        cited = [self.evidence.get(i) for i in cited_ids]

        result["answer_basis"] = basis
        result["citation_warnings"] = report.warnings
        result["uncited_claims"] = report.uncited_claims
        conflicts = reported_conflicts(conflict_lines, self.evidence) + \
            structural_conflicts(self.evidence) + found_value_conflicts
        result["conflicts"] = conflicts
        if warnings:
            result["format_warnings"] = warnings

        if abstained_by_model or not answer:
            self._abstain(result, keyword_guidance)
            return cited

        answer = self.evidence.expand(answer)
        if basis == "notes":
            answer = (_hedge_lead(answer) if support_gone
                      else f"{NOTES_BASIS_SENTENCE} {answer}")
        uncovered = _uncovered_terms(self.question, answer, cited)
        if uncovered and cited:
            answer += " Not covered by the evidence: " + ", ".join(
                f"`{t}`" for t in uncovered) + "."
        result["answer"] = answer
        result["abstained"] = False
        return cited

    def _abstain(self, result: dict, keyword_guidance) -> None:
        """Fill the fields of a full abstention."""
        result["abstained"] = True
        with_evidence = _relevant_current_note(self.question, self.keywords,
                                               self.evidence)
        result["abstained_with_evidence"] = with_evidence
        result["searched"] = self._searched()
        result["note"] = (
            "No direct answer was synthesized, but the sources and html_refs "
            "fields contain related documentation worth exploring."
            if result["sources"] or result["html_refs"]
            else "No documentation found for this query."
        ) + keyword_guidance(self.keywords)

    def _nonexistent(self, missing: list, guidance: str) -> dict:
        """The response for a question about files the snapshot lacks."""
        commit = self.corpus.get("commit", "unknown")
        names = ", ".join(f"`{m}`" for m in missing)
        noun = "file named" if len(missing) == 1 else "files named"
        result = {
            "ok": True, "sources": [], "html_refs": [], "related_memories": [],
            "corpus": self.corpus, "evidence": [],
            "abstained": True, "nonexistent": True,
            "abstention_kind": "nonexistent", "answer_basis": "none",
            "note": f"No {noun} {names} exists in the snapshot {commit[:12]}."
                    + guidance,
            "searched": self._searched(),
        }
        self._finish(result, [])
        return result

    def _searched(self) -> dict:
        """What was looked through, so an abstention says what was not found."""
        by_tier: dict = {}
        for item in self.evidence.items():
            by_tier[item.tier] = by_tier.get(item.tier, 0) + 1
        return {
            "keywords": list(self.retrieval_keywords) or
            [w for w in re.findall(r"[\w./\-]{3,}", self.question)][:8],
            "corpus": self.corpus, "namespaces": list(self.namespaces),
            "evidence_by_tier": by_tier,
        }

    def _finish(self, result: dict, cited: list) -> None:
        """Common tail: degraded reasons and conversation bookkeeping."""
        if self.degraded_reasons:
            result["degraded_reasons"] = sorted(set(self.degraded_reasons))
        if self.conversation is not None:
            self.conversation.record(
                self.question, self.keywords, result.get("answer", ""), cited,
                {k: result.get(k) for k in ("answer_basis", "abstained")})
            result["conversation_id"] = self.conversation.id
            result["turn"] = len(self.conversation.turns)


def _support_gone(basis: str, report, history_sentences: list) -> bool:
    """Whether an answer rests only on notes and its central claim is
    unsupported — the case where a confident lead misrepresents the evidence.

    True when the basis is notes and the lead claim sentence (the first that
    cites, history aside) is flagged unsupported, or every claim sentence is.
    A notes answer whose claim the verifier *can* support is not hedged; only
    one whose support the verifier could not find.
    """
    if basis != "notes":
        return False
    history_ids = {id(s) for s in history_sentences}
    claims = [s for s in report.sentences if s.cited and id(s) not in history_ids]
    if not claims:
        return False
    return claims[0].marked or all(s.marked for s in claims)


def _hedge_lead(answer: str) -> str:
    """Open a notes-only answer whose central claim is unsupported with the
    fixed hedge instead of the model's lead.

    A leading "Yes"/"No" is dropped so the response does not open by asserting
    what the notes do not establish, and :data:`HEDGE_SENTENCE` states the
    standing in its place; the rest of the answer (its unsupported sentences
    already marked) follows unchanged.
    """
    stripped = _LEADING_YES_NO.sub("", answer, count=1).lstrip()
    if stripped and stripped[0].islower():
        stripped = stripped[0].upper() + stripped[1:]
    return f"{HEDGE_SENTENCE} {stripped}".strip()


def _remove_span(answer: str, sentence) -> str:
    """Remove one sentence from the annotated answer.

    The annotated text has marks inserted after earlier-failing sentences,
    so the sentence is located by its text rather than its original offset.
    """
    idx = answer.rfind(sentence.text)
    if idx < 0:
        return answer
    left = answer[:idx]
    right = answer[idx + len(sentence.text):]
    # Collapse only the spaces left adjacent at the cut; indentation elsewhere
    # (nested markdown lists, fenced code blocks) must survive untouched, so a
    # global "  " -> " " replace is wrong here.
    if left.endswith(" ") and right.startswith(" "):
        right = right.lstrip(" ")
    return left + right

