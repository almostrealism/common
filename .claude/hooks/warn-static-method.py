#!/usr/bin/env python3
"""PreToolUse (Edit/Write) hook: remind the agent when it introduces a static method.

Fires when an Edit or Write to a Java source file adds a static method that was
not there before. It never blocks; it injects a reminder that the agent must
read before deciding the static method really is the right shape.

The reminder exists because of a demonstrated, recurring failure mode: agents
reach for a static helper first, then rationalize its placement afterward. The
resulting methods have signatures that never mention the class they live in,
operate entirely on some other type's state, and are invisible to every future
caller who looks for the behavior where it belongs.
"""

import json
import os
import re
import sys

STATIC_METHOD = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|private|protected)\s+)?'
    r'(?:(?:final|synchronized|native|strictfp)\s+)*'
    r'static\s+'
    r'(?:(?:final|synchronized|native|strictfp)\s+)*'
    r'(?!class\b|interface\b|enum\b|record\b)'
    r'([\w$.<>\[\],?\s&]+?)\s+(\w+)\s*\(',
    re.MULTILINE)

REMINDER = """You are introducing a static method: {names}

Stop and reconsider before proceeding. Two facts:

(1) Object-oriented principles are how this codebase is built. Behavior lives
on the type whose state it reads: a method that examines a Foo belongs on Foo
as foo.something(), not beside it as something(Foo foo). A static method
cannot be overridden, cannot participate in polymorphism, will not be found by
the next person who looks for the behavior on the type it concerns, and will
be reimplemented by them. If your method's signature never mentions the class
you are putting it in, it is on the wrong class. If it switches on the type or
properties of its argument, that dispatch belongs to the argument's type
hierarchy. New behavior for an existing concept extends the existing type; it
does not accumulate in helpers around it.

(2) You, the agent, were trained by reinforcement learning in environments
that overwhelmingly do not use object-oriented design. Your first instinct for
any problem is therefore reliably a procedural one - a static helper, a
utility function, a free-floating conversion - and it will feel correct to
you while being wrong for this codebase. Treat the shape of your first
solution as suspect by default. STOP, ask what type this behavior belongs to,
and put it there.

Legitimate static methods exist - a factory like of(...) constructing the
class it lives on, an entry point - but they are the exception. If you
proceed, be prepared to defend the placement in review, not with an argument
about convenience or diff size."""


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def static_methods(source):
    """Return the list of static method names declared in the given source."""
    names = []
    for m in STATIC_METHOD.finditer(source or ""):
        if "=" in m.group(0):
            continue  # a field initialized by a call, not a method declaration
        if m.group(2) == "main":
            continue
        names.append(m.group(2))
    return names


def main():
    payload = read_payload()
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path", "")

    if tool not in ("Edit", "Write") or not path.endswith(".java"):
        sys.exit(0)

    if tool == "Edit":
        before = static_methods(tool_input.get("old_string"))
        after = static_methods(tool_input.get("new_string"))
    else:
        try:
            with open(path) as f:
                existing = f.read()
        except OSError:
            existing = ""
        before = static_methods(existing)
        after = static_methods(tool_input.get("content"))

    added = [n for n in after if after.count(n) > before.count(n)]
    if not added:
        sys.exit(0)

    names = ", ".join(sorted(set(added)))
    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": REMINDER.format(names=names),
        },
        "systemMessage": ("Static method introduced (" + names + ") - reconsider whether "
                          "this behavior belongs on the type it operates on. See context."),
    }
    print(json.dumps(out))
    sys.exit(0)


if __name__ == "__main__":
    main()
