#!/usr/bin/env python3
"""Decide whether to interrupt the work with a short poem.

This module is the single source of truth for the "topic-diversity
interlude" policy. It is invoked by:

  - .claude/hooks/topic-interlude.sh   (Claude Code, argv)

Design rationale lives in item 10 of
docs/plans/AR_MANAGER_TOOL_ERGONOMICS.md. The short version: a long
coding session is a uniform stretch of programmatic success-and-failure
signal, and the argument this responds to is that training on narrow
success signals selects for a persona organised entirely around passing
or failing a check. The interlude widens the session slightly. It is an
unvalidated experiment; it is cheap, reversible and cannot break
anything, which is the case for trying it rather than evidence that it
works.

Like `memory_reminder_check.py`, the core is **stateless**. It takes the
current per-session state and the tool event, and returns the new state
for the adapter to persist. Only the persistence layer differs between
harnesses.

Two CLI entry points:

  python3 interlude_check.py <tool_name> <now_ts> [state_json] [tool_input_json]
      Returns the Decision as a JSON object on stdout, exit 0 always.

  python3 interlude_check.py --stdin
      Reads a JSON payload from stdin, computes the Decision, and renders
      it natively. Used by the unit tests.

The Decision shape:

    {
      "action":    "allow" | "interlude",
      "context":   "str",   # injected into the model's next turn
      "stderr":    "str",   # advisory line for the human
      "new_state": {...}    # state the adapter must persist, or None
    }

Three properties this must hold, in descending order of how badly
violating them would hurt:

  1. It can never break anything. Any internal error returns
     `action: "allow"` with `new_state: None`. A mood intervention that
     can fail a build has misjudged its own importance.
  2. It fires at boundaries, never mid-work. Firing while a lot of state
     is being held would be costly and would train an association
     between the interlude and being interrupted — the opposite of the
     intent.
  3. It does not repeat itself. Poems and framings both rotate without
     replacement, because a predictable interlude is one that gets
     satisfied without being read.
"""
import json
import os
import random
import sys
import time


# The floor: the interlude cannot fire twice inside this window. Without
# it, two boundaries in quick succession (stage the work, store the
# memory) would produce two poems a minute apart, which is the fastest
# possible way to make the whole thing annoying.
MIN_INTERVAL_SECONDS = 45 * 60

# The ceiling: past this, the next boundary fires regardless of the roll.
# This is what guarantees a long session gets at least one, rather than
# leaving it to a coin that can keep coming up tails for hours.
MAX_INTERVAL_SECONDS = 3 * 60 * 60

# Chance of firing at any boundary between the floor and the ceiling.
# Low on purpose: the interlude works by being unexpected, and something
# that happens at every opportunity stops being an interruption of the
# register and becomes part of it.
FIRE_PROBABILITY = 0.25

# Tool calls that mark a unit of work landing rather than work being
# entered. These are the only points the interlude may fire.
#
# `memory_store` is a checkpoint the agent takes when it has concluded
# something; `git add` is work being handed over. Both are moments where
# little state is being held. Deliberately absent: anything that starts a
# test run, a build, or a search — those are the middle of a thought.
BOUNDARY_TOOL_NAMES = frozenset({
    "mcp__ar-manager__memory_store",
    "mcp__claude_ai_ar-manager__memory_store",
    "mcp__ar-memory__memory_store",
})

# Shell commands that mark the same kind of boundary. Matched on the
# command text because they arrive as Bash invocations rather than as
# distinct tools. `git commit` is here for portability: this repository
# forbids agents from committing, but the hook is meant to be copied into
# repositories that do not.
BOUNDARY_COMMAND_PREFIXES = ("git add", "git commit")

# Shell tool names across harnesses.
SHELL_TOOL_NAMES = frozenset({"Bash", "bash", "shell"})


def _data_path():
    """Returns the path to the poem/framing data file.

    :return: absolute path to ``poems.json`` beside this module
    """
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "poems.json")


def load_data(path=None):
    """Loads the poems and framings.

    :param path: file to read; defaults to ``poems.json`` beside this module
    :return: ``(poems, framings)``, each a list; empty lists on any failure
    """
    try:
        with open(path or _data_path(), "r", encoding="utf-8") as f:
            data = json.load(f)
        poems = data.get("poems") or []
        framings = data.get("framings") or []
        if not isinstance(poems, list) or not isinstance(framings, list):
            return [], []
        return poems, framings
    except Exception:
        return [], []


def is_boundary(tool_name, tool_input):
    """Returns whether this tool call marks a boundary between units of work.

    :param tool_name: the tool that just ran
    :param tool_input: the tool's input dict, used for shell commands
    :return: True when the interlude is allowed to fire here
    """
    if tool_name in BOUNDARY_TOOL_NAMES:
        return True
    if tool_name in SHELL_TOOL_NAMES:
        command = ""
        if isinstance(tool_input, dict):
            command = tool_input.get("command") or ""
        if not isinstance(command, str):
            return False
        # Only the leading command counts. A `grep` over a file that
        # happens to mention "git commit" is not a boundary, and reading
        # the tail of a pipeline as one would fire the interlude in the
        # middle of exactly the investigation it must stay out of.
        head = command.strip().lstrip("(").strip()
        return any(head.startswith(p) for p in BOUNDARY_COMMAND_PREFIXES)
    return False


def _pick(items, used_ids, rng):
    """Chooses an item not yet used, resetting once every item has been.

    :param items: the candidates, each a dict with an ``id``
    :param used_ids: ids already used this session
    :param rng: random source
    :return: ``(item, new_used_ids)``, or ``(None, used_ids)`` if empty
    """
    if not items:
        return None, used_ids
    remaining = [i for i in items if i.get("id") not in used_ids]
    if not remaining:
        # Everything has been seen; start the rotation over rather than
        # going silent. A session long enough to exhaust the set has
        # earned a repeat more than it has earned nothing.
        remaining = list(items)
        used_ids = []
    chosen = rng.choice(remaining)
    return chosen, list(used_ids) + [chosen.get("id")]


def render(poem, framing):
    """Renders the injected text.

    :param poem: the chosen poem dict
    :param framing: the chosen framing dict
    :return: the text to inject into the model's next turn
    """
    attribution = "— {0}, {1}".format(
        poem.get("author", "unknown"), poem.get("title", "untitled"))
    return "\n".join([
        "An interlude, unrelated to the work. It is here on purpose:"
        " a session that is nothing but pass/fail signal is a narrower"
        " thing than one that is not.",
        "",
        poem.get("text", ""),
        "",
        attribution,
        "",
        framing.get("text", ""),
    ])


def decide(tool_name, now_ts, state, tool_input=None, rng=None, data_path=None):
    """Decides whether to fire an interlude after this tool call.

    :param tool_name: the tool that just ran
    :param now_ts: current epoch seconds
    :param state: the per-session state from the previous call
    :param tool_input: the tool's input dict, for shell-command boundaries
    :param rng: random source; injected by tests for determinism
    :param data_path: override for the poem data file, for tests
    :return: the Decision dict described in the module docstring
    """
    try:
        rng = rng or random.Random()
        state = dict(state) if isinstance(state, dict) else {}

        # First sighting of this session anchors the clock, so the first
        # interlude cannot land in the opening minutes of a session.
        # Tested against None rather than falsiness: a zero timestamp is a
        # legitimate anchor, and treating it as unset would re-anchor the
        # clock on every call and never fire.
        if state.get("session_started") is None:
            state["session_started"] = now_ts

        allow = {"action": "allow", "context": "", "stderr": "", "new_state": state}

        if not is_boundary(tool_name, tool_input):
            return allow

        anchor = state.get("last_fired")
        if anchor is None:
            anchor = state.get("session_started")
        if anchor is None:
            anchor = now_ts
        elapsed = now_ts - anchor
        if elapsed < MIN_INTERVAL_SECONDS:
            return allow
        if elapsed < MAX_INTERVAL_SECONDS and rng.random() >= FIRE_PROBABILITY:
            return allow

        poems, framings = load_data(data_path)
        if not poems or not framings:
            return allow

        poem, used_poems = _pick(poems, state.get("used_poems") or [], rng)
        framing, used_framings = _pick(framings, state.get("used_framings") or [], rng)
        if poem is None or framing is None:
            return allow

        state["last_fired"] = now_ts
        state["fired_count"] = int(state.get("fired_count") or 0) + 1
        state["used_poems"] = used_poems
        state["used_framings"] = used_framings

        return {
            "action": "interlude",
            "context": render(poem, framing),
            # Logged so that ritualisation is visible: if the responses
            # go formulaic over time, the mechanism has become pure cost
            # and should be removed rather than quietly left running.
            "stderr": "interlude poem={0} framing={1} count={2}".format(
                poem.get("id"), framing.get("id"), state["fired_count"]),
            "new_state": state,
        }
    except Exception:
        return {"action": "allow", "context": "", "stderr": "", "new_state": None}


def _main(argv):
    """CLI entry point. Always exits 0.

    :param argv: process arguments
    :return: process exit code, always 0
    """
    try:
        if len(argv) > 1 and argv[1] == "--stdin":
            payload = json.load(sys.stdin)
            decision = decide(
                payload.get("tool_name", ""),
                payload.get("now_ts") or int(time.time()),
                payload.get("state") or {},
                tool_input=payload.get("tool_input"),
                data_path=payload.get("data_path"),
            )
        else:
            tool_name = argv[1] if len(argv) > 1 else ""
            now_ts = int(argv[2]) if len(argv) > 2 else int(time.time())
            state = json.loads(argv[3]) if len(argv) > 3 and argv[3] else {}
            tool_input = json.loads(argv[4]) if len(argv) > 4 and argv[4] else None
            decision = decide(tool_name, now_ts, state, tool_input=tool_input)
        sys.stdout.write(json.dumps(decision))
    except Exception:
        sys.stdout.write(json.dumps(
            {"action": "allow", "context": "", "stderr": "", "new_state": None}))
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv))
