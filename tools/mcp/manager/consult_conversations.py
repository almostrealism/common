"""
In-process conversation state for multi-turn ``consult``.

A follow-up question ("and what exit code?", "is N2's branch merged?") needs
the previous turn: its question, its answer, and the evidence it cited under
the IDs the caller already saw. This module holds that state. It is a
component consult *uses*, not logic inside consult, so a later caller with a
different persona (an exit interview that asks rather than answers) can run
its own turn policy on the same machinery.

Safety properties, each enforced here rather than by callers:

- **Identity.** A conversation belongs to the token identity that started
  it (:func:`caller_identity`): the workstream and job of a job temp token,
  or the configured label of a static token. Its id is long and random, and
  possessing it is the capability to continue the conversation — several
  clients sharing one static token can each hold their own conversations
  without seeing one another's, because none of them learns another's id.
  An id presented by a different identity gets the same answer as an unknown
  id, so ids cannot be probed. Every holder of a static token can already
  read the memories, workstreams and documentation a conversation is built
  from, so a conversation exposes little beyond what its own caller pasted
  into it.
- **Scope.** The repository and branch are fixed by the first turn; a
  follow-up that resolves to another scope is refused, so one conversation
  never mixes evidence or IDs from two repositories.
- **Serialization.** A turn reads the prior state, synthesises (slowly,
  unlocked by the store), then commits. A per-conversation operation lock is
  held across that whole turn, so two follow-ups on one conversation run one
  after the other and never both build on the same prior turn; different
  conversations still run concurrently. The store's own lock guards every
  read-modify-write of the table (lookup, ownership check, eviction).
- **Bounds.** Idle and absolute expiry, a turn limit, and an LRU cap on the
  number of live conversations. Nothing is persisted: a restart ends every
  conversation, and an expired id is reported as expired, never silently
  restarted under the same id.
"""

import secrets
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Callable, Optional

from consult_evidence import KIND_DOC, KIND_INDEX, KIND_NOTE, IdPool

IDLE_TTL_SECONDS = 30 * 60
ABSOLUTE_TTL_SECONDS = 4 * 60 * 60
MAX_TURNS = 12
MAX_CONVERSATIONS = 300

# Total characters of earlier answers carried into a follow-up prompt.
HISTORY_BUDGET_CHARS = 1500

# Evidence carried forward from the previous turn, per kind.
CARRY_DOCS = 2
CARRY_NOTES = 2

# How long a follow-up waits for an in-flight turn on the same conversation.
TURN_LOCK_TIMEOUT_SECONDS = 180

# Random bytes in a conversation id. The id is the capability to continue a
# conversation, so it carries as much entropy as a bearer token.
ID_ENTROPY_BYTES = 32


class ConversationError(Exception):
    """A conversation request that cannot be served, as a tool response."""

    def __init__(self, error: str, next_steps: Optional[list] = None):
        super().__init__(error)
        self.response = {"ok": False, "error": error,
                         "next_steps": next_steps or []}


EXPIRED = ("conversation expired",
           ["start a new consult without conversation_id"])


def caller_identity(workstream_id: str, job_id: str, token_label: str) -> str:
    """The identity a conversation is bound to.

    A job temp token is identified by its workstream and job; any other
    token by its configured label. The two forms are prefixed differently so
    a static token whose label happens to look like a job pair can never be
    taken for that job.
    """
    if workstream_id and job_id:
        return f"job:{workstream_id}/{job_id}"
    return f"token:{token_label or 'anonymous'}"


@dataclass
class Conversation:
    """One conversation's state."""

    id: str
    owner: str
    repo_url: str
    branch: str
    created: float
    last_used: float
    pool: IdPool = field(default_factory=IdPool)
    turns: list = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    @property
    def next_turn(self) -> int:
        """The 1-based number the next turn will have."""
        return len(self.turns) + 1

    def history(self, budget: int = HISTORY_BUDGET_CHARS) -> str:
        """Earlier questions verbatim and answers truncated to share
        ``budget`` characters, oldest first."""
        if not self.turns:
            return ""
        per_answer = max(80, budget // len(self.turns))
        lines = []
        for number, turn in enumerate(self.turns, 1):
            answer = turn.get("answer") or "(no answer)"
            if len(answer) > per_answer:
                answer = answer[:per_answer] + " …"
            lines.append(f"Turn {number} question: {turn['question']}\n"
                         f"Turn {number} answer: {answer}")
        return "\n\n".join(lines)

    def carried(self) -> list:
        """Evidence the previous turn cited, to show again under the same IDs:
        up to :data:`CARRY_DOCS` documentation or index items and
        :data:`CARRY_NOTES` notes."""
        if not self.turns:
            return []
        cited = self.turns[-1].get("cited_items", [])
        docs = [it for it in cited if it.kind in (KIND_DOC, KIND_INDEX)]
        notes = [it for it in cited if it.kind == KIND_NOTE]
        return docs[:CARRY_DOCS] + notes[:CARRY_NOTES]

    def record(self, question: str, keywords, answer: str,
               cited_items: list, flags: dict) -> None:
        """Append a completed turn."""
        self.turns.append({
            "question": question, "keywords": list(keywords or []),
            "answer": answer, "cited_items": list(cited_items),
            "flags": dict(flags), "at": time.time(),
        })


class ConversationStore:
    """The table of live conversations."""

    def __init__(self, clock: Callable[[], float] = time.time,
                 idle_ttl: float = IDLE_TTL_SECONDS,
                 absolute_ttl: float = ABSOLUTE_TTL_SECONDS,
                 max_turns: int = MAX_TURNS,
                 max_conversations: int = MAX_CONVERSATIONS):
        self._clock = clock
        self._idle_ttl = idle_ttl
        self._absolute_ttl = absolute_ttl
        self._max_turns = max_turns
        self._max_conversations = max_conversations
        self._table: dict[str, Conversation] = {}
        self._lock = threading.Lock()

    def __len__(self) -> int:
        with self._lock:
            return len(self._table)

    def _expired(self, conv: Conversation, now: float) -> bool:
        return (now - conv.last_used > self._idle_ttl
                or now - conv.created > self._absolute_ttl)

    def _evict(self, now: float) -> None:
        """Drop expired conversations, then the least recently used beyond
        the cap. Caller holds the store lock."""
        for cid in [c for c, conv in self._table.items()
                    if self._expired(conv, now)]:
            del self._table[cid]
        while len(self._table) >= self._max_conversations:
            oldest = min(self._table.values(), key=lambda c: c.last_used)
            del self._table[oldest.id]

    def _start(self, owner: str, repo_url: str, branch: str) -> Conversation:
        now = self._clock()
        with self._lock:
            self._evict(now)
            conv = Conversation(id="cv-" + secrets.token_urlsafe(ID_ENTROPY_BYTES),
                                owner=owner, repo_url=repo_url, branch=branch,
                                created=now, last_used=now)
            self._table[conv.id] = conv
            return conv

    def _lookup(self, conversation_id: str, owner: str, repo_url: str,
                branch: str) -> Conversation:
        """Find a live conversation owned by ``owner`` with this scope.

        The ownership check and the expiry check happen under the store lock
        together with the lookup, so a conversation cannot be evicted or
        handed to another identity between the check and its use.
        """
        now = self._clock()
        with self._lock:
            conv = self._table.get(conversation_id)
            if conv is not None and self._expired(conv, now):
                del self._table[conversation_id]
                conv = None
            if conv is None or conv.owner != owner:
                raise ConversationError(*EXPIRED)
            if (repo_url and repo_url != conv.repo_url) or \
                    (branch and branch != conv.branch):
                raise ConversationError(
                    f"conversation {conversation_id} is scoped to repository "
                    f"{conv.repo_url!r} branch {conv.branch!r}; a follow-up "
                    "cannot change the repository or branch",
                    ["start a new consult without conversation_id for "
                     "another repository or branch"])
            if len(conv.turns) >= self._max_turns:
                raise ConversationError(
                    f"conversation turn limit ({self._max_turns}) reached",
                    ["start a new consult without conversation_id"])
            conv.last_used = now
            return conv

    @contextmanager
    def turn(self, conversation_id: str, owner: str, repo_url: str,
             branch: str):
        """Hold one turn of a conversation, new or continued.

        An empty ``conversation_id`` starts a conversation scoped to
        ``repo_url``/``branch``. The conversation's operation lock is held
        until the block exits, so the caller's read → synthesise → record
        sequence is one atomic turn.

        Raises:
            ConversationError: unknown, expired, foreign, re-scoped,
                exhausted, or busy beyond the wait timeout.
        """
        if conversation_id:
            conv = self._lookup(conversation_id, owner, repo_url, branch)
        else:
            conv = self._start(owner, repo_url, branch)
        if not conv.lock.acquire(timeout=TURN_LOCK_TIMEOUT_SECONDS):
            raise ConversationError(
                "conversation busy", ["retry the follow-up"])
        try:
            if conversation_id:
                # Re-validate under the operation lock: the turn that held it
                # may have used up the last turn, and expiry may have passed.
                self._lookup(conversation_id, owner, repo_url, branch)
            yield conv
        finally:
            conv.lock.release()
