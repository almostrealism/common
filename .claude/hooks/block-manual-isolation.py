#!/usr/bin/env python3
"""PreToolUse (Edit/MultiEdit/Write) hook: block manual process isolation.

Graph-building code must not decide kernel boundaries. Deciding them by hand is
a workaround for the optimizer, not a fix for it: the spectral-analysis work
added a `CollectionFeatures.isolated(producer)` helper and wrapped thirteen
reductions in it, which hid a real code-generation defect and only appeared to
make slow graphs fast. Kernel boundaries are `Process.optimize`'s decision; a
computation that needs its own kernel says so by reporting
`isIsolationTarget(ProcessContext)`, and the graph is evaluated at the top of
the call stack through `evaluateOptimized()` or `Process.optimized(p).get()`.

This hook scans ONLY the lines an edit ADDS to a file under `src/main/java`
(existing code is never flagged, and test sources are never scanned). It blocks
an added line that contains any of:

  1. `Process.isolated(` or `Process::isolated` - the static isolation helper
     (and the shape of the removed `isolated(producer)` wrapper).
  2. `.isolate()` on a receiver other than `this`/`super` - a hand-isolation of
     some other process. `this.isolate()` and `super.isolate()` re-invoke the
     type's own isolation and are allowed everywhere.
  3. `new IsolatedProcess` or the constructor of a subclass of it.
  4. A `return` whose expression is any of the above - the helper-method shape.

`Process.optimize` and the process types' own delegation are exempt by
location: the `base/relation/.../compute/` package (`Process`, `ParallelProcess`
and the optimization strategies), and the `isolate()`/`optimize(ProcessContext)`
overrides that delegate to a wrapped process - `OperationWithInfo`,
`ReshapeProducer`, `PackedCollectionRepeat`, `IndexProjectionProducerComputation`
and `CollectionProducerComputationAdapter`. In those classes the delegation
shapes (`wrapped.isolate()`, `Process.isolated(wrapped)`, `new IsolatedProcess`)
are indistinguishable from a hand-isolation by shape alone, so the exemption is
by file - everywhere else the shape-based `this`/`super` rule is enough.

Detection is heuristic; the block message explains how to comply, and a
maintainer can always adjust the hook itself.
"""

import difflib
import json
import os
import re
import subprocess
import sys

# The static isolation helper, as a call or a method reference.
PROCESS_ISOLATED = re.compile(r'\bProcess\s*(?:\.\s*isolated\s*\(|::\s*isolated)')

# A no-argument `.isolate()` call, and the this/super forms that are allowed
# everywhere. Stripping the allowed forms first leaves only hand-isolations of
# some other process behind.
ISOLATE_CALL = re.compile(r'\.\s*isolate\s*\(\s*\)')
ISOLATE_THIS_SUPER = re.compile(r'\b(?:this|super)\s*\.\s*isolate\s*\(\s*\)')

# `new IsolatedProcess`, including a qualified or generic form such as
# `new CollectionProducerComputation.IsolatedProcess` or `new IsolatedProcess<>`.
NEW_ISOLATED_PROCESS = re.compile(r'\bnew\s+(?:[\w$]+\s*\.\s*)*IsolatedProcess\b')

# Any `new Type(` whose type name we then resolve against the repo source to see
# whether it is a subclass of an IsolatedProcess.
NEW_TYPE = re.compile(r'\bnew\s+([A-Z][\w$]*)\s*(?:<[^>]*>)?\s*\(')

# The base/relation process framework and optimization strategies.
ALLOWED_PATH = "base/relation/src/main/java/io/almostrealism/compute/"

# The process types whose isolate()/optimize(ProcessContext) overrides delegate
# to a wrapped process; their delegation shapes look like hand-isolation.
ALLOWED_BASENAMES = {
    "OperationWithInfo.java",
    "ReshapeProducer.java",
    "PackedCollectionRepeat.java",
    "IndexProjectionProducerComputation.java",
    "CollectionProducerComputationAdapter.java",
}

_subclass_cache = {}


def is_isolated_subclass(name):
    """Whether a repo source type extends a class named ...IsolatedProcess."""
    if name in _subclass_cache:
        return _subclass_cache[name]
    result = False
    try:
        paths = subprocess.run(
            ["git", "ls-files", "--", "*/src/main/java/*" + name + ".java"],
            capture_output=True, text=True, timeout=10,
        ).stdout.splitlines()
        paths = [p for p in paths if os.path.basename(p) == name + ".java"]
        if paths:
            with open(paths[0]) as f:
                source = f.read(20000)
            if re.search(
                    r'\bclass\s+' + re.escape(name)
                    + r'\b[^{]*\bextends\s+(?:[\w$]+\s*\.\s*)*IsolatedProcess\b',
                    source):
                result = True
    except Exception:
        result = False
    _subclass_cache[name] = result
    return result


def is_comment(line):
    """Whether a line is a pure comment, so isolation named in prose is ignored."""
    stripped = line.strip()
    return (stripped.startswith("//") or stripped.startswith("*")
            or stripped.startswith("/*") or stripped.startswith("*/"))


def added_lines(before, after):
    """The lines an edit inserts or changes, b-side of insert/replace opcodes."""
    b = before.splitlines()
    a = after.splitlines()
    matcher = difflib.SequenceMatcher(None, b, a, autojunk=False)
    out = []
    for tag, _i1, _i2, j1, j2 in matcher.get_opcodes():
        if tag in ("insert", "replace"):
            out.extend(a[j1:j2])
    return out


def violations(line):
    """The isolation forms a single added line introduces, as messages."""
    found = []

    if PROCESS_ISOLATED.search(line):
        found.append(
            "calls Process.isolated(...), the static isolation helper")

    if ISOLATE_CALL.search(ISOLATE_THIS_SUPER.sub("", line)):
        found.append(
            "calls .isolate() on a process other than this/super, isolating it"
            " by hand")

    if NEW_ISOLATED_PROCESS.search(line):
        found.append("constructs an IsolatedProcess directly")
    else:
        for name in NEW_TYPE.findall(line):
            if is_isolated_subclass(name):
                found.append("constructs " + name
                             + ", a subclass of IsolatedProcess, directly")
                break

    return found


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


def is_allowed_location(path):
    """Whether this file is the optimizer or a process type's own delegation."""
    normalized = path.replace("\\", "/")
    return (ALLOWED_PATH in normalized
            or os.path.basename(normalized) in ALLOWED_BASENAMES)


def main():
    payload = json.load(sys.stdin) if not sys.stdin.isatty() else {}
    tool = payload.get("tool_name")
    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path", "")
    normalized = path.replace("\\", "/")

    if tool not in ("Edit", "MultiEdit", "Write") or not path.endswith(".java"):
        sys.exit(0)

    # Only production sources under src/main/java; never test sources.
    if "/src/main/java/" not in normalized:
        sys.exit(0)

    if is_allowed_location(normalized):
        sys.exit(0)

    texts = change_texts(tool, tool_input)
    if texts is None or texts[0] == texts[1]:
        sys.exit(0)
    before, after = texts

    blocks = []
    for line in added_lines(before, after):
        if is_comment(line):
            continue
        for message in violations(line):
            blocks.append("  " + line.strip() + "\n    -> " + message)

    if blocks:
        message = (
            "BLOCKED: MANUAL PROCESS ISOLATION in " + path + "\n\n"
            + "These added lines decide a kernel boundary by hand:\n\n"
            + "\n\n".join(blocks) + "\n\n"
            + "WHY: Graph-building code must not decide kernel boundaries."
            + " Kernel boundaries are Process.optimize's decision. Isolating a"
            + " reduction by hand is a workaround for the optimizer, not a fix"
            + " for it: it hides optimizer and code-generation defects and only"
            + " appears to make a slow graph fast.\n\n"
            + "THE FIX: make the computation report"
            + " isIsolationTarget(ProcessContext) correctly so Process.optimize"
            + " isolates it, and evaluate the graph at the top of the call"
            + " stack through evaluateOptimized() or Process.optimized(p).get()"
            + " - never Producer.evaluate()/get(), which do not optimize. If a"
            + " graph is slow or deep to build, ask 'is this being optimized?'"
            + " before restructuring it.")
        print(message, file=sys.stderr)
        sys.exit(2)

    sys.exit(0)


if __name__ == "__main__":
    main()
