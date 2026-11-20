#!/usr/bin/env python3
"""PreToolUse (Edit/MultiEdit/Write) hook: new types must not duplicate old ones.

Fires when a change to a Java source file introduces a top-level type
declaration (class / interface / enum / record) that was not there before. It
blocks in two cases and warns otherwise:

  * a name CLAUDE.md forbids outright (a *Util / *Utils / *Helper / *Exporter /
    *Converter / *Manager class);
  * a main-source type that DUPLICATES a concept the repository already has -
    lib/type_equivalence.py finds the same concept from shared public method
    signatures, javadoc describing the same thing and names that say the same
    thing. Shared vocabulary or a shared package alone never blocks; a type
    that only resembles existing ones gets a warning that lists them.

The second block exists because of a real incident. A session needed a method
returning Set<Integer> to stop boxing, and wrote ArithmeticIndexSet - an
immutable set of start + k*step - beside ArithmeticIndexSequence in the same
package, importing it on the line below the existing class's import. Its
reasoning ("the method returns Set, the existing type is not a Set, so I need
a new type") was false at every step: the method's only callers were the code
being optimized, so its signature could change; an interface could have been
introduced for the existing hierarchy to implement; the existing type could
have been extended. Three review passes then checked the new class's
correctness and never asked whether it should exist.

The block lifts only when the new type genuinely builds on a candidate - it
extends or implements it and overrides or calls its members, or calls it
through a field, parameter or local - or when .claude/new-type-ledger.json
carries a justification covering EVERY blocking candidate and naming a test
that exists and refers to the new type (see lib/type_equivalence.py for its
shape). A justified type is still re-examined by the harness's type audit
(DeduplicationRule), which treats the ledger as a claim to verify, and the
harness refuses to stage it until that audit has settled it.

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

Every Java declaration this hook reads is parsed by lib/type_equivalence.py:
there is one parser for the hook and the harness. A failure anywhere in the
assessment fails open - the write proceeds - with a warning on stderr and in
the session's system message, never a traceback.
"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))

try:
    import type_equivalence  # noqa: E402
    IMPORT_FAILURE = None
except Exception as failure:  # the hook must fail open, see main()
    type_equivalence = None
    IMPORT_FAILURE = failure

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

DUPLICATE_MESSAGE = """BLOCKED: NEW TYPE WITH LIKELY EXISTING EQUIVALENTS in {path}

{details}
The rule: solve the problem with the existing abstraction, improving it if
necessary. A second implementation of a concept the codebase already has is a
defect, however correct it is. Take the FIRST remedy that works, in order:

 1. USE the candidate as it is.
 2. CHANGE THE SIGNATURE OR CALLER that made a new type seem necessary. A
    method that "returns Set<Integer>" can return the existing type instead,
    especially when its only callers are the code you are changing.
 3. EXTEND OR IMPROVE the candidate - add the method, the constructor, the
    capability it lacks - or INTRODUCE AN INTERFACE that the candidate
    implements, and code against that interface.
 4. Only then, a new type.

These are NOT reasons for a new type, because signatures and hierarchies can be
changed: "the existing type does not implement interface X"; "the method
signature requires Y"; "the existing type is in a different shape". Renaming
the new type is NOT a remedy either: the search matches javadoc and method
signatures as well as names, and the harness's type audit re-runs it.

This block lifts when the new type genuinely builds on a candidate - extends or
implements it AND overrides or calls its members, or calls its methods through
a field, parameter or local (an unused field or a marker interface does not
count) - or when {ledger} justifies it. A justification covers EVERY listed
candidate; for each it says why the candidate cannot be extended, why the
signature cannot change, and why an interface the candidate implements would
not work; and it names a test, in the repository's test sources, that refers
to the new type and demonstrates the distinction:

  {{"types": [{{"type": "{fqn}",
              "test": "SomeTest#distinguishesFromCandidate",
              "candidates": {{
                "{first}": {{
                  "cannot_extend": "...",
                  "cannot_change_signature": "...",
                  "interface_would_not_work": "..."}}}}}}]}}

A justified type is still audited independently before it can be committed: the
harness's type audit treats the justification as a claim to verify, and folds
the type into the existing abstraction when it does not hold."""


def read_payload():
    try:
        return json.load(sys.stdin)
    except Exception:
        return {}


def top_level_types(source):
    """``(kind, name)`` of each top-level type declared in ``source``, as
    lib/type_equivalence.py parses it: a nested or local type is part of an
    existing type's body, not the birth of a new concept."""
    return [(t["kind"], t["name"]) for t in type_equivalence.parse_types(source)]


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
    """``(kind, name)`` of each top-level type declared in ``after`` but not in
    ``before``."""
    return [(t["kind"], t["name"]) for t in type_equivalence.added_types(before, after)]


def blocked(added):
    """Added types whose declared name ends in a forbidden suffix."""
    return [(kind, name) for kind, name in added
            if name.endswith(BLOCKED_SUFFIXES)]


def assess_added(path, after, added):
    """Assesses each added type against the repository's existing types.

    Returns ``(blocking, lifted, warned)``: the assessments that block; those
    whose blocking candidates are answered by structure or by the ledger; and
    those that only resemble existing types. A test source is never assessed.
    """
    if type_equivalence.is_test_source(path):
        return [], [], []
    root = type_equivalence.find_root(path)
    index = type_equivalence.TypeIndex.load(root)
    ledger = type_equivalence.read_ledger(root)
    rel = os.path.relpath(os.path.abspath(path), root)
    blocking, lifted, warned = [], [], []
    for _, name in added:
        result = type_equivalence.assess(index, after, rel, name, ledger)
        if result is None or not result["candidates"]:
            continue
        if result["blocks"]:
            blocking.append(result)
        elif any(c["blocks"] for c in result["candidates"]):
            lifted.append(result)
        else:
            warned.append(result)
    return blocking, lifted, warned


def describe(result, blocking_only=True):
    """The message section for one assessed type: its blocking candidates, or
    every candidate when ``blocking_only`` is false."""
    lines = ["New type " + result["fqn"] + (" duplicates" if blocking_only else " resembles")
             + " existing type(s):", ""]
    for c in result["candidates"]:
        if blocking_only and not c["blocks"]:
            continue
        ev = c["evidence"]
        why = []
        if ev.get("near_copy"):
            why.append("nearly the same signatures and javadoc")
        if ev.get("same_concept_name"):
            why.append("a name for the same concept")
        if ev.get("shared_methods"):
            why.append("methods " + ", ".join(ev["shared_methods"]))
        if ev.get("shared_doc_words"):
            why.append("javadoc " + ", ".join(ev["shared_doc_words"]))
        if ev.get("shared_name_words"):
            why.append("name words " + ", ".join(ev["shared_name_words"]))
        if ev.get("relation"):
            why.append("but a " + ev["relation"] + ", so not a duplicate")
        lines.append("  - " + c["fqn"] + "  (score " + str(c["score"]) + ")")
        lines.append("      " + c["path"])
        if c.get("sentence"):
            lines.append("      \"" + c["sentence"][:160] + "\"")
        if why:
            lines.append("      shared: " + "; ".join(why))
    if result["ledger"] is not None and result["gaps"]:
        lines.append("")
        lines.append("The ledger entry for this type does not lift the block:")
        lines.extend("  - " + g for g in result["gaps"])
    return "\n".join(lines) + "\n"


def verdict(payload):
    """The hook's decision for one PreToolUse payload: ``(exit code, stdout,
    stderr)``."""
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path", "")

    if tool not in ("Edit", "MultiEdit", "Write") or not path.endswith(".java"):
        return 0, "", ""

    texts = change_texts(tool, tool_input)
    if texts is None or texts[0] == texts[1]:
        return 0, "", ""
    before, after = texts

    added = added_types(before, after)
    if not added:
        return 0, "", ""

    forbidden = sorted({name for _, name in blocked(added)})
    if forbidden:
        return 2, "", BLOCK_MESSAGE.format(path=path, names=", ".join(forbidden),
                                           first=forbidden[0])

    blocking, lifted, warned = assess_added(path, after, added)
    if blocking:
        details = "\n".join(describe(r) for r in blocking)
        first = blocking[0]
        top = [c for c in first["candidates"] if c["blocks"]][0]
        return 2, "", DUPLICATE_MESSAGE.format(
            path=path, details=details, ledger=type_equivalence.LEDGER_PATH,
            fqn=first["fqn"], first=top["fqn"])

    names = ", ".join(kind + " " + name for kind, name in added)
    context = TYPE_REMINDER.format(names=names)
    for r in lifted:
        how = ("it " + r["lift"]) if r["lift"] else (
            "its justification in " + type_equivalence.LEDGER_PATH + " covers every candidate")
        context += ("\n\n" + r["fqn"] + " duplicates "
                    + ", ".join(c["fqn"] for c in r["candidates"] if c["blocks"])
                    + "; allowed because " + how + ". The harness's type audit"
                    " will still verify that it should exist.")
    for r in warned:
        context += ("\n\n" + describe(r, blocking_only=False)
                    + "None of these is clearly the same concept, so nothing is blocked;"
                    " read them before deciding the new type is needed.")
    if not discovery_on_record():
        context += NO_DISCOVERY_REMINDER.format(names=names)

    summary = ("New type(s) introduced (" + names + ") - place each at the most"
               " general location where it makes sense. See context.")
    if not discovery_on_record():
        summary = ("New type(s) introduced (" + names + ") with no discovery on"
                   " record - an equivalent may already exist. See context.")

    return 0, json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": context,
        },
        "systemMessage": summary,
    }), ""


def main():
    """Runs the hook. Any failure to reach a verdict fails open: the write
    proceeds, and the failure is reported on stderr and as the session's system
    message so that a check that did not run is noticed."""
    payload = read_payload()
    try:
        if IMPORT_FAILURE is not None:
            raise IMPORT_FAILURE
        code, out, err = verdict(payload)
    except Exception as failure:
        warning = ("warn-new-type-placement: the new-type check did not run ("
                   + type(failure).__name__ + ": " + str(failure) + "); the write"
                   " was allowed without it.")
        print(warning, file=sys.stderr)
        print(json.dumps({"systemMessage": warning}))
        sys.exit(0)
    if out:
        print(out)
    if err:
        print(err, file=sys.stderr)
    sys.exit(code)


if __name__ == "__main__":
    main()
