"""
Merge state of the branches consult's notes were written on.

A note's tier depends on whether its branch reached the branch the question is
about, which only GitHub knows. This module looks that up from the most recent
pull request for the note's head branch and caches it.

Authorization is a precondition for touching the cache, not part of its key.
``consult`` requires only ``memory-read``, so the lookup runs only for a caller
whose token also holds ``github``, and only for the note's own repository after
the workspace/org gate (``_require_org_in_scope``) has passed — so a
``github``-scoped workspace token cannot read another organization's PR state
through that organization's configured PAT. A caller that fails either gate
gets ``unknown`` and neither reads nor writes the cache, so a skipped lookup
never poisons a later authorised one, and a privileged entry is never served to
a caller that has not just passed the same gate.

The cache stores only objective repository state — the most recent PR's
number, head SHA, base and merge status — never a caller-derived ``unknown``.
Open entries expire quickly; merged and closed entries are revalidated after a
day, and a revalidation that finds a different most-recent PR replaces the
entry.
"""

import re
import threading
import time
from typing import Callable, Optional

import server
from consult_tiers import (
    BranchState, Caller, STATE_CLOSED, STATE_MERGED, STATE_MERGED_ELSEWHERE,
    STATE_OPEN, STATE_TARGET, STATE_UNKNOWN,
)

OPEN_TTL_SECONDS = 600
SETTLED_TTL_SECONDS = 86400

# Reason recorded in ``degraded_reasons`` when a lookup was skipped or failed.
DEGRADED_REASON = "branch_state"

_REPO_URL = re.compile(r"github\.com[:/]+([^/]+)/([^/]+?)(?:\.git)?/?$")


def parse_repo_url(repo_url: str) -> tuple[str, str]:
    """``(owner, repo)`` of a GitHub URL in SSH or HTTPS form, or empty."""
    match = _REPO_URL.search(repo_url or "")
    return (match.group(1), match.group(2)) if match else ("", "")


class BranchStateResolver:
    """Resolves and caches the merge state of note branches."""

    def __init__(self, clock: Callable[[], float] = time.time):
        self._clock = clock
        self._cache: dict[tuple[str, str], dict] = {}
        self._lock = threading.Lock()

    def resolve(self, repo_url: str, branch: str,
                caller: Caller) -> tuple[BranchState, bool]:
        """The state of ``branch`` in ``repo_url``, relative to the caller's
        target branch.

        Returns:
            ``(state, degraded)``: ``degraded`` is true when a lookup was
            needed but skipped (authorization) or failed (GitHub unreachable),
            so the caller can report ``branch_state`` in ``degraded_reasons``.
        """
        if not branch:
            return BranchState(), False
        if branch == caller.target_branch:
            return BranchState(state=STATE_TARGET, base=branch), False
        owner, repo = parse_repo_url(repo_url)
        if not owner or "github" not in (server._get_scopes() or []):
            return BranchState(), True
        try:
            server._require_org_in_scope(owner)
        except PermissionError:
            return BranchState(), True

        entry = self._cached(owner, repo, branch)
        if entry is None:
            entry = self._fetch(owner, repo, branch)
            if entry is None:
                return BranchState(), True
        return self._state(entry, caller), False

    def _cached(self, owner: str, repo: str, branch: str) -> Optional[dict]:
        with self._lock:
            entry = self._cache.get((f"{owner}/{repo}", branch))
        if entry is None:
            return None
        ttl = OPEN_TTL_SECONDS if entry["state"] == "open" else SETTLED_TTL_SECONDS
        return entry if self._clock() - entry["fetched_at"] < ttl else None

    def _fetch(self, owner: str, repo: str, branch: str) -> Optional[dict]:
        """Look the branch's most recent PR up and cache it; ``None`` on
        failure (nothing is cached for a failed lookup)."""
        try:
            _, _, _, err = server._resolve_github_repo(owner=owner, repo=repo)
            if err:
                return None
            found = server._find_recent_pr_by_branch(owner, repo, branch)
        except Exception:  # noqa: BLE001 - a lookup failure never fails consult
            return None
        if not isinstance(found, dict) or not found.get("ok"):
            return None
        if not found.get("found"):
            entry = {"state": "none", "pr_number": 0, "head_sha": "",
                     "base": "", "merged_at": "", "fetched_at": self._clock()}
        else:
            pr = found.get("pr") or {}
            entry = {
                "state": pr.get("state", ""),
                "pr_number": pr.get("number", 0),
                "head_sha": (pr.get("head") or {}).get("sha", ""),
                "base": (pr.get("base") or {}).get("ref", ""),
                "merged_at": pr.get("merged_at") or "",
                "fetched_at": self._clock(),
            }
        with self._lock:
            self._cache[(f"{owner}/{repo}", branch)] = entry
        return entry

    @staticmethod
    def _state(entry: dict, caller: Caller) -> BranchState:
        """Map a cached PR record onto a :class:`BranchState`."""
        number = entry.get("pr_number", 0)
        if entry.get("merged_at"):
            state = (STATE_MERGED if entry.get("base") == caller.target_branch
                     else STATE_MERGED_ELSEWHERE)
            return BranchState(state, entry["merged_at"], entry.get("base", ""), number)
        if entry.get("state") == "open":
            return BranchState(STATE_OPEN, "", entry.get("base", ""), number)
        if entry.get("state") == "closed":
            return BranchState(STATE_CLOSED, "", entry.get("base", ""), number)
        return BranchState(STATE_UNKNOWN)


class FixedBranchStates:
    """A resolver answering from a fixed map, for reproducible evaluation.

    ``states`` maps a branch name to ``{state, merged_at?, base?}``; a branch
    not in the map is ``unknown``, and the target branch is always itself.
    """

    def __init__(self, states: Optional[dict] = None):
        self._states = states or {}

    def resolve(self, repo_url: str, branch: str,
                caller: Caller) -> tuple[BranchState, bool]:
        if branch and branch == caller.target_branch:
            return BranchState(state=STATE_TARGET, base=branch), False
        spec = self._states.get(branch)
        if not spec:
            return BranchState(), False
        return BranchState(spec.get("state", STATE_UNKNOWN),
                           spec.get("merged_at", ""), spec.get("base", ""),
                           spec.get("pr_number", 0)), False
