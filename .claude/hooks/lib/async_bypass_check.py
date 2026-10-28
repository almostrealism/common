#!/usr/bin/env python3
"""Detect caller-side workarounds for the asynchronous-submission contract.

The contract (docs/internals/asynchronous-submission.md): a caller chains
`Submittable.submit(dependsOn)`, keeps the latest completion, submits host code
through `Submittable.of`, and waits once at a host boundary. Ordering, bounds on
in-flight work and thread use are the receiving implementation's job.

The incident this guards against: a backend held a thread for every pending
submission, and Metal let GPU work wait on the host until the watchdog killed it.
Instead of either defect being found, an agent added `Semaphore.isOrderedOnDevice`
(a backend property asked of a completion), `Submittable.chainOnDevice` (a second
submit method whose meaning depended on the backend), and a "steps in flight"
cap in `SimulationRunner`, so the caller waited whenever the backend would have
misbehaved. Every shape below is a mechanical signature of that workaround.

Blocking findings (exit 2):

 1. BACKEND QUERY ON THE CONTRACT: a new method on `Semaphore` or `Submittable`
    whose name asks about the backend or the ordering mechanism
    (isOrderedOnDevice, isNative, isDeviceOrdered, ...), or a new submit variant
    (chainOnDevice, submitOrdered, ...). A completion cannot answer whether a
    receiver can honour it; only the receiver can.
 2. RESURRECTION: a call to one of the removed members.
 3. CALLER-SIDE TYPE CHECK: in calling code (outside base/), a new `instanceof`
    against a Semaphore type.
 4. IN-FLIGHT CAP IN CALLING CODE: outside base/, a new identifier or setting
    naming work "in flight" in a file that waits on a completion.
 5. WAIT, THEN SUBMIT NOTHING: a new `waitFor()` followed by `submit(null)`,
    the hand-written form of discarding the dependency.

Warning (context only): a new `waitFor()` in calling code (outside base/) in a
file that also submits, since a wait there is a boundary operation that should
be deliberate.

Tests are exempt: they wait to observe results, and recording stand-ins are
expected to implement the interfaces.
"""

import json
import re
import subprocess
import sys


#: Files that define the contract itself.
CONTRACT_FILES = ("/io/almostrealism/streams/Semaphore.java",
                  "/io/almostrealism/concurrent/Submittable.java")

#: A method declaration, capturing its name.
METHOD = re.compile(
    r'^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|private|protected|static|final|abstract|default'
    r'|synchronized)\s+)*'
    r'(?!class\b|interface\b|enum\b|record\b|new\b|return\b|throw\b)'
    r'[\w$.<>\[\],?\s&]+?\s+(\w+)\s*\([^;{]*\)\s*(?:throws[^{;]*)?[{;]',
    re.MULTILINE)

#: Method names that ask a completion about the mechanism behind it.
BACKEND_QUERY = re.compile(
    r'^(is|has|supports|can|needs|requires)\w*'
    r'(Device|Native|Gpu|GPU|Host|Ordered|Order|Batch|Queue|Backend|Metal|Cuda'
    r'|OpenCL|CL)\w*$')

#: Method names that offer a second way to submit.
SUBMIT_VARIANT = re.compile(
    r'^(chain|submit|enqueue|dispatch)\w*'
    r'(Device|Ordered|Order|Native|Host|Backend|IfPossible|Directly)\w*$')

#: Members removed with the incident; calling them again is a regression.
REMOVED_MEMBERS = re.compile(r'\b(isOrderedOnDevice|chainOnDevice)\s*\(')

#: A type check against a completion type.
SEMAPHORE_INSTANCEOF = re.compile(r'\binstanceof\s+(\w*Semaphore)\b')

#: Something named for limiting work in flight.
IN_FLIGHT = re.compile(r'(InFlight|IN_FLIGHT|inFlight|_in_flight|Outstanding'
                       r'|OUTSTANDING)')

#: A wait.
WAIT = re.compile(r'\.waitFor\s*\(\s*\)')

#: A wait followed shortly by a submission that drops the dependency.
WAIT_THEN_NULL_SUBMIT = re.compile(
    r'\.waitFor\s*\(\s*\)\s*;[^;]{0,200}?;?[^;]{0,200}?\.submit\s*\(\s*null\s*\)',
    re.DOTALL)


def governs(path):
    """Return whether the file is production Java source this policy covers."""
    return (path.endswith(".java") and "/src/main/java/" in path
            and "/src/test/" not in path)


def is_contract(path):
    """Return whether the file defines Semaphore or Submittable."""
    return any(path.endswith(f) for f in CONTRACT_FILES)


def is_caller(path):
    """Return whether the file is calling code rather than the execution layer.

    The base/ modules hold the semaphore implementations and the backends, which
    are the receivers: recognising their own completions is their job. Every
    module above them only issues work.
    """
    return "/base/" not in path.replace("\\", "/") and not path.startswith("base/")


def change_texts(tool, tool_input, existing):
    """Return the (before, after) full-file texts of an edit, or None."""
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
        for edit in tool_input.get("edits") or []:
            old = edit.get("old_string") or ""
            new = edit.get("new_string") or ""
            if old and old in after:
                count = -1 if edit.get("replace_all") else 1
                after = after.replace(old, new, count)
        return existing, after
    return None


def gained(pattern, before, after):
    """Return the matches of a pattern that the edit adds."""
    found = pattern.findall(after)
    previous = pattern.findall(before)
    return [m for m in set(found) if found.count(m) > previous.count(m)]


def findings(path, before, after):
    """Return (blocks, warnings) for an edit from `before` to `after`."""
    blocks = []
    warnings = []

    if is_contract(path):
        for name in gained(METHOD, before, after):
            if BACKEND_QUERY.match(name):
                blocks.append(
                    "New member '" + name + "' asks a completion about the mechanism"
                    " behind it. Whether a dependency can be honoured without a host"
                    " wait depends on the RECEIVER, not on the semaphore, so no"
                    " semaphore can answer it; a caller that asks has taken over the"
                    " receiver's job.")
            elif SUBMIT_VARIANT.match(name) and name != "submit":
                blocks.append(
                    "New member '" + name + "' is a second way to submit whose meaning"
                    " depends on the backend. There is one: submit(dependsOn), and"
                    " the receiver decides how to order after its dependency.")

    for name in gained(REMOVED_MEMBERS, before, after):
        blocks.append(
            "Call to '" + name + "', removed because it made callers wait whenever a"
            " backend would have misbehaved. Chain with submit(dependsOn) and fix the"
            " backend instead.")

    if is_caller(path):
        for name in gained(SEMAPHORE_INSTANCEOF, before, after):
            blocks.append(
                "New `instanceof " + name + "` in calling code. A caller never decides"
                " from the kind of completion it holds whether to wait; it chains, and"
                " the receiver orders.")

        if WAIT.search(after) and gained(IN_FLIGHT, before, after):
            blocks.append(
                "New in-flight limit in calling code that waits on completions. A cap"
                " on how far ahead a caller submits compensates for a backend that"
                " cannot keep up; if a bound is needed, the backend applies it at the"
                " resource that runs out.")

        if gained(WAIT, before, after) and ".submit(" in after:
            warnings.append(
                "New waitFor() in code that submits work. A wait is a boundary"
                " operation: it belongs only where host code needs the result (the"
                " end of a run, a host read, Submittable.of for host work). If it is"
                " there because chaining was slow or wrong, the backend is broken.")

    if len(WAIT_THEN_NULL_SUBMIT.findall(after)) > len(WAIT_THEN_NULL_SUBMIT.findall(before)):
        blocks.append(
            "New waitFor() followed by submit(null): this discards the dependency and"
            " serializes the host with the device. Pass the completion as dependsOn.")

    return blocks, warnings


BLOCK_FOOTER = (
    "\n\nWHY: the asynchronous-submission contract is that a caller chains"
    " submit(dependsOn), submits host code through Submittable.of, and waits once at"
    " a host boundary. Ordering, in-flight bounds and thread use are the receiving"
    " implementation's job. When chaining is slow, wrong, or grows threads, a receiver"
    " is broken: take a thread dump while a long chain is in flight, find the"
    " receiver that holds a thread or lets work be discarded, and fix it there.\n\n"
    "See docs/internals/asynchronous-submission.md.")


def committed_text(path):
    """Return the file as HEAD has it, or the empty text for a new file."""
    try:
        done = subprocess.run(("git", "show", "HEAD:" + path),
                              capture_output=True, text=True, timeout=10)
    except Exception:
        return ""
    return done.stdout if done.returncode == 0 else ""


def decide(tool, tool_input, existing):
    """Return (exit_code, message) for an edit.

    A payload with no tool is the replay check-source-writes.sh makes for a file
    a shell command changed: the change is then the difference from HEAD.
    """
    path = tool_input.get("file_path", "") or ""
    if not governs(path):
        return 0, None

    if tool is None:
        texts = (committed_text(path), existing)
    else:
        texts = change_texts(tool, tool_input, existing)
    if texts is None or texts[0] == texts[1]:
        return 0, None

    blocks, warnings = findings(path, texts[0], texts[1])
    if blocks:
        return 2, ("BLOCKED: ASYNCHRONOUS-SUBMISSION BYPASS in " + path + "\n\n- "
                   + "\n- ".join(blocks) + BLOCK_FOOTER)
    if warnings:
        return 0, json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "additionalContext": "\n".join(warnings)
                + "\n\nSee docs/internals/asynchronous-submission.md.",
            },
        })
    return 0, None


def main(argv=None):
    """Read a PreToolUse payload from stdin and decide on the edit."""
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] != "--stdin":
        sys.stderr.write("usage: async_bypass_check.py --stdin\n")
        return 1

    try:
        data = json.loads(sys.stdin.read())
    except Exception:
        return 0

    tool_input = data.get("tool_input", {}) or {}
    try:
        with open(tool_input.get("file_path", "") or "") as f:
            existing = f.read()
    except OSError:
        existing = ""

    code, message = decide(data.get("tool_name"), tool_input, existing)
    if message is not None:
        (sys.stderr if code == 2 else sys.stdout).write(message)
    return code


if __name__ == "__main__":
    sys.exit(main())
