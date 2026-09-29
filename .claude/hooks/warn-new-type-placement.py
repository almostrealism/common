#!/usr/bin/env python3
"""PreToolUse (Edit/MultiEdit/Write) hook: placement reminder for every new type.

Fires when a change to a Java source file introduces a top-level type
declaration (class / interface / enum / record) that was not there before. It
warns by default; it blocks in exactly one case - a name CLAUDE.md forbids
outright (a *Util / *Utils / *Helper / *Exporter / *Converter / *Manager class).

This is the type-level counterpart of warn-method-placement.py, and it exists
because a new type is the change that arrives with the LEAST deliberation and is
the MOST expensive to relocate later. It assumes the worst about the agent on
the other end: agents optimize for closing the task in front of them, and the
cheapest closure is a brand-new class that wraps the operation at hand - a
FooExporter beside Foo, a helper dropped in whatever module today's task
surfaced in. Each such class is individually plausible and collectively
corrosive: the behaviour belonged on an existing type, or one rung up the
generality ladder, and once the class exists nobody moves it. CLAUDE.md is
deliberately stricter about types than about methods for exactly this reason
("No utility/helper/exporter/converter classes"; "Before creating a new class,
ask: Does this behaviour belong on an existing type?"; "New classes are for
genuinely new concepts, not for wrapping operations on existing concepts") - and
yet a new class is the change that comes with no prompt at all. This hook
supplies the prompt at the moment the type is born, when moving it costs nothing.

A new class should also not be an agent's FIRST act on a concept. When the
session has no discovery on record - no Grep, Glob, Explore/Agent search or
consult before this write - the warning escalates to say so: the likeliest
reason a type does not exist yet is that the behaviour already lives somewhere
under a name nobody has searched for. The discovery signal is heuristic, so it
only sharpens the wording; it never blocks. The signal is read from the same
/tmp marker convention that track-consultant-call.sh / enforce-consultant-first.sh
use for the consult call, plus a discovery marker written by
track-discovery-call.sh for Grep / Glob / Agent.

The one blocking tier is mechanical, not a judgment call. CLAUDE.md forbids
utility / helper / exporter / converter (and manager) classes flatly, so a new
type whose DECLARED name ends in Util, Utils, Helper, Exporter, Converter or
Manager is refused with the rule quoted and the fix named. Two correctness
requirements are honoured: the match is on the declared type name, not the file
name; and a pre-existing type of that name merely being edited never fires,
because the decision is made from the before/after comparison, not the after
text alone.
"""

import json
import os
import re
import sys

TYPE = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|private|protected|static|final|abstract|sealed'
    r'|non-sealed|strictfp)\s+)*'
    r'\b(class|interface|enum|record)\s+(\w+)',
    re.MULTILINE)

BLOCKED_SUFFIXES = ("Util", "Utils", "Helper", "Exporter", "Converter", "Manager")

TYPE_REMINDER = """You are introducing new type(s): {names}

A new type is the change that arrives with the least deliberation and is the
most expensive to relocate once it exists. Place it deliberately before
proceeding - CLAUDE.md is stricter about new types than about new methods.

Walk UP the generality ladder and stop at the highest rung where a NEW type is
still the right answer:

 1. Does the behaviour belong on an EXISTING type? A capability that reads or
    interprets another type's state is a method on THAT type, not a new class
    that operates on it from the outside. From CLAUDE.md: "A PatternElement that
    can produce MIDI events has a toMidiEvents() method - it does NOT have a
    PatternMidiExporter that operates on it from the outside."
 2. Is this a genuinely NEW concept, or a wrapper around operations on an
    existing one? "New classes are for genuinely new concepts, not for wrapping
    operations on existing concepts." A name built from a verb - an -er / -or
    that says what it DOES rather than what it IS - is the tell of a wrapper.
 3. Is THIS module the concept's conceptual home, or merely where today's task
    surfaced? Code belongs in the module that matches its domain, at the most
    general layer where it still makes sense - "place it where the NEXT consumer
    will look for it, not where today's diff is smallest."

If, after that, the type is genuinely a new concept placed at its conceptual
home, add it - and be prepared to defend the placement in review. An argument
about convenience or diff size is not a defense. See CLAUDE.md, CODE QUALITY."""

NO_DISCOVERY_REMINDER = """

No discovery is on record for this session - no Grep, Glob, Explore/Agent search
or consult has run before this write. A new type should never be your first act
on a concept: an equivalent may already exist and nothing has looked. "Does an
equivalent already exist? Search before writing - the second implementation of
anything is a defect." Search for the concept and for the behaviour it needs
before adding {names}; if it already exists, extend it instead of duplicating
it."""

BLOCK_MESSAGE = """BLOCKED: NEW UTILITY/HELPER-STYLE TYPE in {path}

New type(s) forbidden by name: {names}

CLAUDE.md, CODE QUALITY: "No utility/helper/exporter/converter classes. If you
need to add behaviour that operates on an existing type, add it as a method on
that type." A class whose name ends in Util, Utils, Helper, Exporter, Converter
or Manager names an OPERATION, not a concept, and is forbidden outright - this
is mechanical, not a judgment call.

DO THIS INSTEAD: find the type the behaviour operates on and add the behaviour
there as a method - foo.doThing(), never new {first}().doThing(foo). If several
callers must agree on a value, put a named constant or accessor on the type that
owns the concept. If the behaviour is domain-agnostic, it belongs one rung up
the generality ladder, in the general layer (a core builtin, a base features
mixin) - never in a helper class.

If you believe this is a genuinely new concept whose name merely happens to end
that way, rename it to name the concept rather than the operation."""


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def top_level_types(source):
    """Return (kind, name) tuples for top-level type declarations in the source.

    Top-level means declared at brace depth zero. A nested or local type is an
    ordinary part of an existing type's body - not the birth of a new top-level
    concept - so it is deliberately excluded. Brace depth is counted from the
    raw text, which is heuristic (it does not discount braces inside strings or
    comments) but sufficient for the ordinary shapes this reminder targets.
    """
    found = []
    for m in TYPE.finditer(source or ""):
        prefix = source[:m.start()]
        depth = prefix.count("{") - prefix.count("}")
        if depth == 0:
            found.append((m.group(1), m.group(2)))
    return found


def change_texts(tool, tool_input):
    """Return (before, after) FULL-FILE source texts for the change, or None.

    Full-file reconstruction (read the file, apply the edit) is what lets an
    ordinary edit to an existing class stay quiet: the class declaration is in
    both texts, so the before/after count comparison sees nothing added.
    """
    path = tool_input.get("file_path", "")
    try:
        with open(path) as f:
            existing = f.read()
    except OSError:
        existing = ""

    if tool == "Write":
        return existing, tool_input.get("content") or ""
    if tool == "Edit":
        old = tool_input.get("old_string") or ""
        new = tool_input.get("new_string") or ""
        if old and old in existing:
            count = -1 if tool_input.get("replace_all") else 1
            return existing, existing.replace(old, new, count)
        return existing, existing
    if tool == "MultiEdit":
        after = existing
        for e in tool_input.get("edits") or []:
            old = e.get("old_string") or ""
            new = e.get("new_string") or ""
            if old and old in after:
                count = -1 if e.get("replace_all") else 1
                after = after.replace(old, new, count)
        return existing, after
    return None


def discovery_on_record():
    """True when this session has any discovery marker on record.

    Mirrors the /tmp timestamp convention of track-consultant-call.sh: the
    consult call writes .ar_consultant_last_<user>.ts and Grep / Glob / Agent
    write .ar_discovery_last_<user>.ts (track-discovery-call.sh). Either one is
    enough - the point is only whether SOMETHING looked before the new type.
    """
    user = os.environ.get("USER") or "developer"
    markers = (
        "/tmp/.ar_discovery_last_" + user + ".ts",
        "/tmp/.ar_consultant_last_" + user + ".ts",
    )
    return any(os.path.exists(m) for m in markers)


def added_types(before, after):
    """Top-level type declarations present in `after` but not in `before`."""
    before_types = top_level_types(before)
    after_types = top_level_types(after)
    return [entry for entry in after_types
            if after_types.count(entry) > before_types.count(entry)]


def blocked(added):
    """Added types whose declared name ends in a forbidden suffix."""
    return [(kind, name) for kind, name in added
            if name.endswith(BLOCKED_SUFFIXES)]


def main():
    payload = read_payload()
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path", "")

    if tool not in ("Edit", "MultiEdit", "Write") or not path.endswith(".java"):
        sys.exit(0)

    texts = change_texts(tool, tool_input)
    if texts is None or texts[0] == texts[1]:
        sys.exit(0)
    before, after = texts

    added = added_types(before, after)
    if not added:
        sys.exit(0)

    forbidden = blocked(added)
    if forbidden:
        forbidden_names = ", ".join(sorted({name for _, name in forbidden}))
        message = BLOCK_MESSAGE.format(
            path=path, names=forbidden_names,
            first=sorted({name for _, name in forbidden})[0])
        print(message, file=sys.stderr)
        sys.exit(2)

    names = ", ".join(kind + " " + name for kind, name in added)
    context = TYPE_REMINDER.format(names=names)
    if not discovery_on_record():
        context += NO_DISCOVERY_REMINDER.format(names=names)

    summary = ("New type(s) introduced (" + names + ") - place each at the most"
               " general location where it makes sense. See context.")
    if not discovery_on_record():
        summary = ("New type(s) introduced (" + names + ") with no discovery on"
                   " record - an equivalent may already exist. See context.")

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
