#!/usr/bin/env python3
"""PreToolUse (Edit/MultiEdit/Write) hook: placement reminder for every new method.

Fires when a change to a Java source file introduces a method declaration that
was not there before. It never blocks; it injects a reminder that each new
method must be placed deliberately - on the type whose state it reads, at the
most general level where it makes sense - before the edit proceeds.

The reminder exists because of a demonstrated, recurring failure mode, and it
assumes the worst about the agent on the other end: agents optimize for
closing the task in front of them, and the cheapest closure is a method
defined on whatever class is already open, scoped to today's caller. Each such
method is individually defensible and collectively corrosive - the next
consumer cannot find it, reimplements it, and the codebase accumulates
narrowly-placed near-duplicates. The hook makes the placement question loud at
the exact moment the method is born, when moving it costs nothing.

A second, sharper tier fires when the new method is static, since a static
helper is the most reliable signature of procedural first-instinct placement.
"""

import json
import re
import sys

METHOD = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'((?:(?:public|private|protected|static|final|abstract|default'
    r'|synchronized|native|strictfp)\s+)+)'
    r'(?!class\b|interface\b|enum\b|record\b|new\b|return\b|throw\b)'
    r'([\w$.<>\[\],?\s&]+?)\s+(\w+)\s*\(',
    re.MULTILINE)

PLACEMENT_REMINDER = """You are introducing new method(s): {names}

Place each one deliberately before proceeding. The default instinct is to
define a method on whatever class is already being edited, scoped to the task
at hand. Over time that instinct fills the codebase with narrowly-placed,
single-consumer methods that the next caller cannot find and will reimplement.

For every new method, walk UP the generality ladder and stop at the highest
rung where the method still makes sense:

 1. Does an equivalent already exist? Search before writing - the second
    implementation of anything is a defect.
 2. Does it read or interpret another type's state? Then it belongs on THAT
    type, as an instance method, regardless of where it is called from.
 3. Is the behavior domain-agnostic? Then it belongs in the general layer
    (a core builtin, a base features mixin, the framework type it extends),
    not in the domain-specific module where today's task happened to surface.
 4. Would a second consumer in another module plausibly want this? Place it
    where that consumer will LOOK for it, not where it is convenient today.

A method is correctly placed when the next person needing the behavior finds
it by looking where it obviously belongs. Maximum reuse over the lifetime of
the project outweighs minimum diff today - see CLAUDE.md, CODE QUALITY,
"Method placement"."""

STATIC_REMINDER = """

Additionally, static method(s) were introduced: {static_names}. Two facts:

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


def methods(source):
    """Return (name, is_static) tuples for method declarations in the source."""
    found = []
    for m in METHOD.finditer(source or ""):
        if "=" in m.group(0):
            continue  # a field initialized by a call, not a method declaration
        name = m.group(3)
        if name == "main":
            continue
        found.append((name, "static" in m.group(1).split()))
    return found


def change_texts(tool, tool_input):
    """Return (before, after) source texts for the change, or None to skip."""
    if tool == "Edit":
        return tool_input.get("old_string") or "", tool_input.get("new_string") or ""
    if tool == "MultiEdit":
        edits = tool_input.get("edits") or []
        before = "\n".join(e.get("old_string") or "" for e in edits)
        after = "\n".join(e.get("new_string") or "" for e in edits)
        return before, after
    if tool == "Write":
        try:
            with open(tool_input.get("file_path", "")) as f:
                existing = f.read()
        except OSError:
            existing = ""
        return existing, tool_input.get("content") or ""
    return None


def main():
    payload = read_payload()
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path", "")

    if tool not in ("Edit", "MultiEdit", "Write") or not path.endswith(".java"):
        sys.exit(0)

    texts = change_texts(tool, tool_input)
    if texts is None:
        sys.exit(0)

    before = methods(texts[0])
    after = methods(texts[1])
    added = [entry for entry in after if after.count(entry) > before.count(entry)]
    if not added:
        sys.exit(0)

    names = ", ".join(sorted({name for name, _ in added}))
    statics = sorted({name for name, is_static in added if is_static})

    context = PLACEMENT_REMINDER.format(names=names)
    if statics:
        context += STATIC_REMINDER.format(static_names=", ".join(statics))

    summary = ("New method(s) introduced (" + names + ") - place each at the most"
               " general location where it makes sense. See context.")
    if statics:
        summary = ("New method(s) introduced (" + names + "), including static ("
                   + ", ".join(statics) + ") - reconsider placement. See context.")

    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": context,
        },
        "systemMessage": summary,
    }
    print(json.dumps(out))
    sys.exit(0)


if __name__ == "__main__":
    main()
