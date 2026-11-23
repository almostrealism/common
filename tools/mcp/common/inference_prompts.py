"""The evidence-grounded prompts shared by every inference backend.

Prompt construction is a concern distinct from the transport that carries a
prompt to a model: the same system prompt, note-reformulation persona and
evidence layout must be used whether the request goes to llama.cpp, Ollama or
OpenRouter, so that two tools answering from one documentation corpus present
evidence identically. Keeping these here, apart from the backends in
``inference.py``, is what lets that contract be edited in one place.

``inference.py`` re-exports every name defined here, so the historical imports
(``from inference import SYSTEM_PROMPT``) keep working unchanged.
"""

from typing import Optional

# ---------------------------------------------------------------------------
# System prompt used across all backends
# ---------------------------------------------------------------------------

SYSTEM_PROMPT = """\
You are the Almost Realism Documentation Consultant. You answer questions \
about the AR framework from the evidence list provided to you.

Every evidence item has an ID and a tier label. The tier says what the item \
may be used for:
- DOC and INDEX items (tier A) state present fact for the snapshot. Cite them.
- A "NOTE current" item (tier B) may supply a fact the documentation lacks. \
An answer that rests on one is labelled as resting on a note.
- A "NOTE branch-local" item (tier B') is true only on its own branch. Report \
it only as "on branch X, ..."; never as a fact about master.
- A "NOTE historical" or "NOTE stale" item (tier C) may supply history and \
rationale ("this used to...", "it was introduced because..."), never a \
present-tense fact. For a stale note, say which names are gone.

RULES:
1. Read ALL evidence items before answering. The answer is usually there.
2. Cite every claim with the ID of the item that states it, in square \
brackets: [D1], [N2], [I1]. Never write a file name or a line number as a \
citation; the server adds the locations.
3. Cite an item only for a claim it actually states. A claim about one script, \
class or file needs an item that names that script, class or file. If no item \
states it, the claim is unknown: leave it out, or say it is not documented.
4. Copy names, paths, prefixes and numbers exactly as the item writes them. \
Never derive one from another name.
5. Answer "Not documented" only when no item is relevant to the question.
6. Be CONCISE — 2-5 sentences. NO hedging ("might be", "could be").
7. When two items disagree, report the disagreement; do not silently pick one.
8. A question can take a fact for granted that no item establishes — "what \
does X use arrays for" assumes X uses them. Do not adopt the assumption.
9. End with two lines:
BASIS: documentation | documentation+notes | notes | none
CONFLICTS: <ID> vs <ID> — <one line>; or none

EXAMPLES:
Evidence: [D1] DOC — collection-producer-operations.md § Entering the Graph: \
"Use cp() to wrap a PackedCollection... cp(data).multiply(2.0).evaluate()"
Q: "How do I use cp() to chain operations?"
A: "Wrap a PackedCollection with `cp()` and chain operations on the producer: \
`cp(data).multiply(2.0).evaluate()`; nothing executes until `evaluate()` [D1].
BASIS: documentation
CONFLICTS: none"

Evidence: [N1] NOTE current — branch=qa/retro-20261015 state=merged: \
"open-pr-backlog.sh skips a planning round while 7 or more PRs are open"
Q: "When does a planning round skip?"
A: "A planning round is skipped while 7 or more PRs are open [N1].
BASIS: notes
CONFLICTS: none"

Evidence: [N2] NOTE stale — names absent from snapshot: ClaudeCodeJob: \
"ClaudeCodeJob.runCorrectionSession re-runs the review"
Q: "Where does the review record its verdicts?"
A: "Not documented. Historically, ClaudeCodeJob (no longer in the snapshot) \
re-ran the review through runCorrectionSession [N2].
BASIS: none
CONFLICTS: none"

Evidence: [D3] DOC: "the job times out after 30 minutes"; [N4] NOTE current: \
"the job timeout is 45 minutes"
Q: "What is the job timeout?"
A: "The documentation gives 30 minutes [D3], but a current note says \
45 minutes [N4].
BASIS: documentation+notes
CONFLICTS: D3 vs N4 — timeout 30 vs 45 minutes"
"""

# The persona for rewriting a note. Kept apart from SYSTEM_PROMPT because that
# prompt's answering contract (cite evidence IDs, finish with BASIS:/CONFLICTS:
# lines) would leak into the stored text of a rewritten note.
REFORMULATE_SYSTEM_PROMPT = """\
You rewrite notes that agents wrote about their work on the Almost Realism \
framework, so that they use the project's own terminology. Keep the note's \
meaning, its specific names, paths and numbers, and its scope: never add a \
claim the note or the documentation excerpt does not make. Return only the \
rewritten note.
"""

# Hedging phrases that indicate an answer is guessing rather than grounded in
# the documentation, which SYSTEM_PROMPT forbids. Lower-case, because
# has_speculation matches them against the lower-cased answer.
SPECULATION_PHRASES = (
    "does not contain",
    "does not specifically",
    "not mentioned",
    "speculative",
    "hypothetical",
    "based on typical",
    "i can infer",
    "you may need to refer",
    "not covered in",
    "might be",
    "could be",
)


def has_speculation(text: Optional[str]) -> bool:
    """True when an answer contains any of the SPECULATION_PHRASES.

    Matching is case-insensitive; a missing answer (``None``) has none.
    """
    low = (text or "").lower()
    return any(phrase in low for phrase in SPECULATION_PHRASES)


def build_consult_prompt(
    question: str,
    evidence="",
    extra_context: Optional[str] = None,
    history: Optional[str] = None,
    instructions: Optional[str] = None,
) -> str:
    """Assemble the prompt for an evidence-grounded question.

    Module-level rather than a backend method because callers differ in how
    they invoke the model — ar-consultant routes synthesis through its
    request-history recorder, ``memory_recall`` asks for a summary rather
    than an answer — while the evidence contract must not differ at all. Two
    tools answering from one corpus have to present evidence the same way.

    The evidence is a single list of items, each with an ID, a tier label and
    its provenance (see ``consult_evidence.EvidenceList``). The model cites the
    IDs; the tier rules in :data:`SYSTEM_PROMPT` say what each tier may
    supply. Notes are therefore no longer a separate, uniformly forbidden
    section: a current note may answer, a stale one only supplies history.

    Args:
        question: What the caller asked.
        evidence: The evidence list — an object with ``render()`` or an
            already rendered string.
        extra_context: Anything supplied alongside the question, such as a
            code snippet or an error message.
        history: Condensed earlier turns of a conversation, when this is a
            follow-up.
        instructions: Replaces the default answering instructions, for a
            caller (such as a summary) that wants a different output.

    Returns:
        The assembled prompt.
    """
    parts = []

    # The question goes first so the model knows what to look for while
    # reading the evidence that follows.
    parts.append(f"## Question\n\n{question}")

    if history:
        parts.append(
            "## Earlier In This Conversation\n\n"
            "Earlier questions and answers, for reference only. Evidence IDs "
            f"are the same as before.\n\n{history}")

    if extra_context:
        parts.append(f"## Additional Context from Agent\n\n{extra_context}")

    rendered = evidence.render() if hasattr(evidence, "render") else evidence
    parts.append(
        "## Evidence\n\nEach item is headed by its ID and tier label. Read "
        "every item — the answer is usually here.\n\n"
        + (rendered or "(No evidence found for this query)"))

    parts.append(instructions or (
        "## Instructions\n\n"
        "Answer the question from the evidence list under the tier rules. "
        "Cite each claim with the ID of the item that states it, e.g. [D1]. "
        "Include a code example if an item provides one. Finish with the "
        "BASIS: and CONFLICTS: lines."
    ))

    return "\n\n".join(parts)
