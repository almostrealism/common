#!/usr/bin/env bash
# PreToolUse — Write/Edit/MultiEdit/NotebookEdit: block any attempt to introduce
# reflective method invocation into a Java source file.
#
# Reflectively calling a method (getDeclaredMethod + setAccessible + invoke, or
# Method.invoke) bypasses the access modifier the author chose and is unchecked
# at compile time — a rename becomes a runtime NoSuchMethodException instead of a
# build error. It is almost always a test prying open a private helper. The fix is
# to call the method directly: if it is not visible, make it package-private (and
# co-locate the test) or add a real API — never reach in with reflection.
#
# This is the local mirror of the CI gate ReflectiveInvocationDetector
# (REFLECTIVE_METHOD_INVOCATION), which fails the build via CodePolicyEnforcementTest.
# Blocking here saves a round-trip through CI.
#
# Exit 0  -> allow
# Exit 2  -> BLOCK (reason on stderr is shown to the model)
set -euo pipefail

INPUT=$(cat)

python3 - "$INPUT" <<'PY'
import json
import sys

try:
    data = json.loads(sys.argv[1])
except Exception:
    sys.exit(0)

tool_input = data.get("tool_input", {}) or {}
file_path = tool_input.get("file_path", "") or ""

# Java source only.
if not file_path.endswith(".java"):
    sys.exit(0)

# Gather every piece of proposed new content across the edit tool shapes.
fragments = []
if isinstance(tool_input.get("content"), str):
    fragments.append(tool_input["content"])            # Write
if isinstance(tool_input.get("new_string"), str):
    fragments.append(tool_input["new_string"])         # Edit
for edit in tool_input.get("edits", []) or []:         # MultiEdit
    if isinstance(edit, dict) and isinstance(edit.get("new_string"), str):
        fragments.append(edit["new_string"])
if isinstance(tool_input.get("new_source"), str):
    fragments.append(tool_input["new_source"])         # NotebookEdit

content = "\n".join(fragments)
if not content.strip():
    sys.exit(0)

reflect_import = ("import java.lang.reflect.Method" in content
                  or "import java.lang.reflect.*" in content)

hits = []
for raw in content.splitlines():
    line = raw.strip()
    # Ignore comment / javadoc lines so prose that merely names the API is not blocked.
    if line.startswith("//") or line.startswith("*") or line.startswith("/*"):
        continue
    if "getDeclaredMethod(" in raw or "getDeclaredMethods(" in raw:
        hits.append(line)
    elif reflect_import and ".invoke(" in raw:
        hits.append(line)

if not hits:
    sys.exit(0)

shown = "\n".join("  > " + h for h in hits[:10])
sys.stderr.write(
    "BLOCKED: REFLECTIVE METHOD INVOCATION IS NOT ALLOWED — EVER.\n\n"
    "This edit introduces a reflective method call into:\n"
    "  " + file_path + "\n\n"
    + shown + "\n\n"
    "CALLING A METHOD THROUGH REFLECTION BYPASSES THE ACCESS MODIFIER THE AUTHOR\n"
    "CHOSE AND IS UNCHECKED AT COMPILE TIME: A RENAME OR SIGNATURE CHANGE BECOMES A\n"
    "RUNTIME NoSuchMethodException INSTEAD OF A BUILD ERROR. THIS IS EXACTLY HOW A\n"
    "REMOVED METHOD KEPT COMPILING AND FAILED ONLY WHEN RUN.\n\n"
    "FIX IT PROPERLY — CALL THE METHOD DIRECTLY:\n"
    "  - If the method is not visible to the caller, make it package-private and\n"
    "    co-locate the test in the same package, OR add a real (non-reflective) API.\n"
    "  - Never use getDeclaredMethod/setAccessible/Method.invoke to reach a private\n"
    "    or hidden method.\n\n"
    "The CI gate ReflectiveInvocationDetector (REFLECTIVE_METHOD_INVOCATION) fails\n"
    "the build on this regardless; do not try to route around it.\n"
)
sys.exit(2)
PY
