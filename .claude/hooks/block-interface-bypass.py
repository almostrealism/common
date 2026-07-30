#!/usr/bin/env python3
"""PreToolUse (Edit/MultiEdit/Write) hook: detect interface-contract bypasses.

A recurring, high-cost agent failure mode: a class whose contract is expressed
through an interface (it routes to, returns, or accepts Receptor, Producer,
Cell, ...) is quietly given members that only function when a PARTICULAR
concrete implementation is behind that interface - and degrade to doing
nothing, returning null, or throwing when it is not. The class stops honoring
its own contract; every other implementation of the interface is silently
excluded; the design rots invisibly until a human reads the file.

The canonical instance: a router adapting List<WaveOutput> into
ChannelInfo -> Receptor grew getStemOutput()/appendStem() members that handed
the concrete WaveOutput back out and no-opped otherwise, so consumers began
writing around the Receptor contract entirely.

Three detectors, from that incident's mechanical signature:

 1. RE-EXPOSURE (blocks): a new public/protected member returns or stores a
    concrete class that a constructor of this class accepts and adapts behind
    an interface. The constructor taking the concrete type is a convenience;
    handing it back out makes the abstraction a lie.
 2. ABSTRACTION FORK (warns): a new member whose name extends an existing
    member's name (getStemOutput vs getStem) but returns a concrete class
    where the existing member returns an interface. Two abstraction levels
    for one concept - consumers will split between them.
 3. DOWNCAST (warns): a new `instanceof` against a repo class. Behavior that
    depends on which implementation is behind an interface belongs on the
    interface.

Type identities (interface vs class) are resolved from the repo source via
`git ls-files`, cached per run. Detection is heuristic; the block message
explains how to comply, and a maintainer can always adjust the hook itself.
"""

import json
import os
import re
import subprocess
import sys

METHOD = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'((?:(?:public|private|protected|static|final|abstract|default'
    r'|synchronized|native|strictfp)\s+)+)'
    r'(?!class\b|interface\b|enum\b|record\b|new\b|return\b|throw\b)'
    r'([\w$.<>\[\],?\s&]+?)\s+(\w+)\s*\(',
    re.MULTILINE)

INSTANCEOF = re.compile(r'\binstanceof\s+([A-Z]\w*)')

COMMON_TYPES = {
    "void", "int", "long", "double", "float", "boolean", "byte", "short",
    "char", "String", "Object", "Integer", "Long", "Double", "Float",
    "Boolean", "Character", "Byte", "Short", "T", "K", "V", "O", "List",
    "Map", "Set", "Collection", "Optional", "Iterator", "Stream", "Class",
}

_kind_cache = {}


def type_kind(name):
    """Return 'interface', 'class', or None for a repo source type name."""
    if name in _kind_cache:
        return _kind_cache[name]
    kind = None
    if name not in COMMON_TYPES:
        try:
            paths = subprocess.run(
                ["git", "ls-files", "--", "*/src/main/java/*" + name + ".java"],
                capture_output=True, text=True, timeout=10,
            ).stdout.splitlines()
            paths = [p for p in paths
                     if os.path.basename(p) == name + ".java"]
            if paths:
                with open(paths[0]) as f:
                    source = f.read(20000)
                decl = re.search(
                    r'\b(interface|class|enum|record)\s+' + name + r'\b',
                    source)
                if decl:
                    kind = decl.group(1)
                    if kind in ("enum", "record"):
                        kind = "class"
        except Exception:
            kind = None
    _kind_cache[name] = kind
    return kind


def type_names(type_text):
    """Uppercase-initial type identifiers in a signature fragment."""
    return {t.split(".")[-1] for t in re.findall(r'\b([A-Z][\w.]*)', type_text)}


def methods(source):
    """Return (name, return_text, modifiers) for method declarations."""
    found = []
    for m in METHOD.finditer(source or ""):
        if "=" in m.group(0):
            continue
        found.append((m.group(3), m.group(2).strip(), m.group(1)))
    return found


def constructor_adapted_types(source, class_name):
    """Concrete classes accepted by constructors of the class."""
    adapted = set()
    ctor = re.compile(
        r'(?:public|protected)\s+' + re.escape(class_name) + r'\s*\(([^)]*)\)')
    for m in ctor.finditer(source or ""):
        for name in type_names(m.group(1)):
            if type_kind(name) == "class":
                adapted.add(name)
    return adapted


def interface_returning(source):
    """Map of member name -> interface return type for existing members."""
    result = {}
    for name, ret, modifiers in methods(source):
        mods = modifiers.split()
        if "public" not in mods and "protected" not in mods:
            continue
        for t in type_names(ret):
            if type_kind(t) == "interface":
                result[name] = t
                break
    return result


def normalized(name):
    for prefix in ("get", "set", "is", "add", "create"):
        if name.startswith(prefix) and len(name) > len(prefix):
            return name[len(prefix):].lower()
    return name.lower()


def change_texts(tool, tool_input):
    """Return (before, after) FULL-FILE texts for the change, or None."""
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


def main():
    payload = json.load(sys.stdin) if not sys.stdin.isatty() else {}
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path", "")

    if tool not in ("Edit", "MultiEdit", "Write") or not path.endswith(".java"):
        sys.exit(0)

    texts = change_texts(tool, tool_input)
    if texts is None or texts[0] == texts[1]:
        sys.exit(0)
    before, after = texts

    class_name = os.path.splitext(os.path.basename(path))[0]

    before_methods = methods(before)
    after_methods = methods(after)
    added = [entry for entry in after_methods
             if after_methods.count(entry) > before_methods.count(entry)]

    blocks = []
    warnings = []

    adapted = constructor_adapted_types(after, class_name)
    iface_members = interface_returning(before)
    has_interface_surface = bool(iface_members) or any(
        type_kind(t) == "interface"
        for _, ret, _ in before_methods for t in type_names(ret))

    for name, ret, modifiers in added:
        mods = modifiers.split()
        exposed = "public" in mods or "protected" in mods
        if not exposed:
            continue

        exposure = type_names(ret) & adapted
        if exposure and has_interface_surface:
            blocks.append(
                "Member '" + name + "' exposes " + ", ".join(sorted(exposure))
                + ", a concrete class this constructor accepts only to adapt"
                + " behind an interface. Consumers of this member will bypass"
                + " the interface contract, and every other implementation of"
                + " the interface is silently excluded.")

        for existing_name, iface in iface_members.items():
            n_new, n_old = normalized(name), normalized(existing_name)
            if n_new == n_old or not n_new.startswith(n_old):
                continue
            concrete = {t for t in type_names(ret)
                        if type_kind(t) == "class"}
            if concrete:
                warnings.append(
                    "Member '" + name + "' returns concrete "
                    + ", ".join(sorted(concrete)) + " beside existing '"
                    + existing_name + "' returning interface " + iface
                    + ". Two abstraction levels for one concept split the"
                    + " consumers; express the capability on " + iface + ".")

    new_casts = [t for t in INSTANCEOF.findall(after)
                 if INSTANCEOF.findall(after).count(t)
                 > INSTANCEOF.findall(before).count(t)
                 and type_kind(t) == "class"]
    for t in sorted(set(new_casts)):
        warnings.append(
            "New `instanceof " + t + "` - behavior that depends on which"
            + " implementation sits behind an interface belongs on the"
            + " interface itself, not in a caller-side type check.")

    if blocks:
        message = (
            "BLOCKED: INTERFACE CONTRACT BYPASS in " + path + "\n\n"
            + "\n\n".join(blocks) + "\n\n"
            + "WHY: A class whose API traffics in an interface must remain"
            + " correct for EVERY implementation of that interface. Members"
            + " that hand out a particular implementation, or that only"
            + " function when one is present, break the contract for all"
            + " other implementations - silently.\n\n"
            + "THE FIX is never to expose the concrete type: express the"
            + " needed capability on the interface the API already uses (or"
            + " push richer data through the existing interface methods)."
            + " If the interface cannot express the operation, raise that as"
            + " a design question instead of routing around it.")
        print(message, file=sys.stderr)
        sys.exit(2)

    if warnings:
        context = ("Possible interface-contract bypass in this edit:\n\n- "
                   + "\n- ".join(warnings) + "\n\nAn API that traffics in an"
                   + " interface must stay correct for every implementation."
                   + " Express new capabilities on the interface; do not"
                   + " reach around it to a concrete class.")
        out = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "additionalContext": context,
            },
            "systemMessage": ("Possible interface-contract bypass ("
                              + str(len(warnings)) + " finding(s)) - see"
                              + " context."),
        }
        print(json.dumps(out))
    sys.exit(0)


if __name__ == "__main__":
    main()
