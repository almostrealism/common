#!/usr/bin/env python3
"""Make the agent look at a repository's open pull requests before it opens
another one, and make it confirm a second pull request deliberately.

Every pull request runs the whole CI pipeline on the shared runner fleet.
Splitting one piece of work into several small pull requests multiplies the
runner time it costs, so new work for a repository whose earlier pull request
is still open belongs on that pull request's branch. This gate puts that in
front of the agent at the moment it reaches for a new pull request, because
an unsupervised session will not otherwise stop to think about it.

It is invoked by ``.claude/hooks/pr-gate.sh`` in three modes:

  record   PostToolUse on ``github_list_open_prs``. Stores the repository's
           open pull requests, as ar-manager returned them, with the time.
  gate     PreToolUse on ``github_create_pr``. Denies the call unless the
           repository's open pull requests were listed in the last
           ``FRESHNESS_SECONDS``. When another pull request is open, denies
           the first attempt with a reminder naming it; the identical call
           repeated within ``CONFIRM_SECONDS`` goes through and is written to
           the override ledger.
  bash     PreToolUse on Bash. Denies ``gh pr create``, which would bypass
           the gate, and points at ``github_create_pr``.

The decision functions are pure (state in, state out); ``main`` owns the
files. State lives in ``.claude/pr-gate.local.json`` and the override ledger
in ``.claude/pr-gate-overrides.local.json``, both git-ignored, so the gate
writes nothing outside the working tree. A malfunction allows the call and
says so on stderr: the gate must never stop work because it is broken.
"""
import json
import os
import re
import shlex
import sys
import time

FRESHNESS_SECONDS = 600
CONFIRM_SECONDS = 600

GH_PR_CREATE = re.compile(r"\bgh\b[^;&|\n]*\bpr\s+create\b")
GH_COMMAND = re.compile(r"\bgh\b")


def context_key(tool_input):
    """Returns the state key for the repository context a github tool call addresses.

    ``github_create_pr`` and ``github_list_open_prs`` resolve a repository the same
    way: an explicit ``org``/``repo`` pair addresses it directly, otherwise a
    ``workstream_id`` resolves it, otherwise the token context does. The gate keys
    its state the same way so that a listing and a create made in the same context
    share a key. The result is never ``None``: a token-context call is a normal,
    supported form, not a missing-argument error.
    """
    org = (tool_input.get("org") or "").strip()
    repo = (tool_input.get("repo") or "").strip()
    if org and repo:
        return (org + "/" + repo).lower()
    workstream_id = (tool_input.get("workstream_id") or "").strip()
    if workstream_id:
        return "ws:" + workstream_id
    return "token-context"


def list_hint(tool_input):
    """Returns how the agent should phrase the github_list_open_prs call for this context."""
    org = (tool_input.get("org") or "").strip()
    repo = (tool_input.get("repo") or "").strip()
    if org and repo:
        return "github_list_open_prs with org=\"" + org + "\" repo=\"" + repo + "\""
    return "github_list_open_prs for the same repository (same workstream_id or token context)"


def parse_open_prs(tool_response):
    """Returns the open pull requests in an ar-manager response, or None when it holds none.

    The harness may hand the response over as a dict, as a JSON string, or as a
    list of MCP content blocks whose text is the JSON.
    """
    candidates = [tool_response]
    if isinstance(tool_response, list):
        candidates = [b.get("text") for b in tool_response if isinstance(b, dict)]
    elif isinstance(tool_response, dict) and isinstance(tool_response.get("content"), list):
        candidates = [b.get("text") for b in tool_response["content"] if isinstance(b, dict)]
    for candidate in candidates:
        data = candidate
        if isinstance(candidate, str):
            try:
                data = json.loads(candidate)
            except ValueError:
                continue
        if isinstance(data, dict) and data.get("ok") and isinstance(data.get("prs"), list):
            return [{"number": p.get("number"), "head": p.get("head", ""), "title": p.get("title", "")}
                    for p in data["prs"] if isinstance(p, dict)]
    return None


def record(state, tool_input, tool_response, now):
    """Returns the state with the listed repository's open pull requests stored.

    A listing filtered by ``base`` sees only the pull requests against that one
    base branch, so it cannot answer the repository-wide question the gate asks
    ("is any pull request open on this repository?"). Such a listing is ignored
    rather than recorded as authoritative.
    """
    if (tool_input.get("base") or "").strip():
        return state
    key = context_key(tool_input)
    prs = parse_open_prs(tool_response)
    if prs is None:
        return state
    new_state = dict(state)
    repos = dict(new_state.get("repos", {}))
    repos[key] = {"ts": now, "prs": prs}
    new_state["repos"] = repos
    return new_state


def deny(reason):
    """Returns a decision that denies the call, with the reason shown to the agent."""
    return {"action": "deny", "reason": reason}


def gate(state, tool_input, now, head=None):
    """Decides a ``github_create_pr`` call.

    ``head`` is the effective head branch: the ``head`` the call passes, or, when
    it omits one, the branch ar-manager would default to (the current checkout).
    ``main`` resolves it; when called without one this falls back to the value in
    ``tool_input`` so the pure function stays usable on its own.

    Returns ``(decision, new_state, override)`` where ``override`` is the ledger
    entry to append when a confirmed second pull request goes through, else None.
    """
    key = context_key(tool_input)
    if head is None:
        head = (tool_input.get("head") or "").strip()

    listed = state.get("repos", {}).get(key)
    if listed is None or now - listed.get("ts", 0) > FRESHNESS_SECONDS:
        return deny("Before opening a pull request on " + key + ", call " + list_hint(tool_input)
                    + ". Every pull request runs the whole CI pipeline on the shared runners, so check "
                    "whether this work belongs on a pull request that is already open."), state, None

    others = [p for p in listed.get("prs", []) if p.get("head") != head]
    if not others:
        return {"action": "allow"}, state, None

    fingerprint = key + "|" + head + "|" + json.dumps(tool_input, sort_keys=True, default=str)
    pending = dict(state.get("pending", {}))
    asked = pending.pop(fingerprint, None)
    if asked is not None and now - asked <= CONFIRM_SECONDS:
        new_state = dict(state)
        new_state["pending"] = pending
        override = {"ts": now, "repo": key, "head": head, "title": tool_input.get("title", ""),
                    "open": [p.get("number") for p in others]}
        return {"action": "allow"}, new_state, override

    pending[fingerprint] = now
    new_state = dict(state)
    new_state["pending"] = pending
    listing = "; ".join("#" + str(p.get("number")) + " (" + str(p.get("head")) + ", \""
                        + str(p.get("title")) + "\")" for p in others[:10])
    return deny(key + " already has open pull requests: " + listing + ". A new pull request runs a "
                "whole CI pipeline on the shared runners, and splitting one round of work into several "
                "small pull requests multiplies that cost. Unless this change truly has to be reviewed "
                "or merged separately from those, commit it to the open branch and push. If it does, "
                "state why in your next message, then submit the identical github_create_pr call again "
                "within " + str(CONFIRM_SECONDS // 60) + " minutes; that override is logged for the "
                "developer."), new_state, None


def bash(command):
    """Decides a Bash command: ``gh pr create`` is denied because it would bypass the gate.

    The command is matched both as written and after shell-aware tokenization, so
    that quoted or escaped spellings the shell still runs as ``gh pr create`` --
    ``gh "pr" create``, ``gh pr cre'ate'`` -- do not slip past a match on the raw
    text. A command that mentions ``gh`` but cannot be tokenized (unbalanced
    quotes) fails closed: the gate cannot prove it is safe, so it denies it.
    """
    command = command or ""
    joined = command.replace("\\\n", "")  # the shell removes a backslash-newline line continuation
    try:
        normalized = " ".join(shlex.split(joined))
    except ValueError:
        normalized = None
    if GH_PR_CREATE.search(joined) or (normalized is not None and GH_PR_CREATE.search(normalized)):
        return deny("Open pull requests with github_create_pr, not gh pr create, so the pull request "
                    "gate can check the repository's open pull requests first.")
    if normalized is None and GH_COMMAND.search(command):
        return deny("This gh command could not be parsed, so the pull request gate cannot confirm it "
                    "is not gh pr create. Open pull requests with github_create_pr instead.")
    return {"action": "allow"}


def current_branch(project_dir):
    """Returns the current checkout's branch from ``.git/HEAD``, or "" when unavailable.

    This is the branch ar-manager defaults ``head`` to when ``github_create_pr`` is
    called without one, so the gate resolves it the same way to avoid mistaking the
    call's own branch for a different-branch pull request.
    """
    try:
        with open(os.path.join(project_dir, ".git", "HEAD"), "r", encoding="utf-8") as f:
            ref = f.read().strip()
    except OSError:
        return ""
    prefix = "ref: refs/heads/"
    return ref[len(prefix):] if ref.startswith(prefix) else ""


def load(path, default):
    """Reads a JSON file, returning the default when it is missing or malformed."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, type(default)) else default
    except (OSError, ValueError):
        return default


def save(path, data):
    """Writes a JSON file atomically, group-writable like the rest of the working tree."""
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    os.chmod(tmp, 0o664)
    os.replace(tmp, path)


def main(mode):
    """Runs one hook invocation: reads the harness payload from stdin and prints the decision."""
    payload = json.load(sys.stdin)
    tool_input = payload.get("tool_input") or {}
    project_dir = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    base = os.path.join(project_dir, ".claude")
    state_path = os.path.join(base, "pr-gate.local.json")
    ledger_path = os.path.join(base, "pr-gate-overrides.local.json")
    now = int(time.time())

    if mode == "record":
        state = load(state_path, {})
        save(state_path, record(state, tool_input, payload.get("tool_response"), now))
        return
    if mode == "bash":
        decision = bash(tool_input.get("command", ""))
    elif mode == "gate":
        state = load(state_path, {})
        head = (tool_input.get("head") or "").strip() or current_branch(project_dir)
        decision, new_state, override = gate(state, tool_input, now, head)
        if new_state is not state:
            save(state_path, new_state)
        if override is not None:
            ledger = load(ledger_path, [])
            ledger.append(override)
            save(ledger_path, ledger)
    else:
        return

    if decision["action"] == "deny":
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": decision["reason"],
        }}))


if __name__ == "__main__":
    try:
        main(sys.argv[1] if len(sys.argv) > 1 else "")
    except Exception as e:
        sys.stderr.write("pull request gate malfunctioned and allowed the call: " + repr(e) + "\n")
    sys.exit(0)
