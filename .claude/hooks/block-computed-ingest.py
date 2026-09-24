#!/usr/bin/env python3
"""PreToolUse (Edit/MultiEdit/Write) hook: block host-to-device ingest evasion.

The host-to-device write policy (SetMemLiteralsDetector) forbids moving a value
Java has computed into device memory except through a Producer. A documented
evasion routed computed values through the INGEST surface reserved for data
entering the process from OUTSIDE it:

    ByteBuffer values = ByteBuffer.allocate(Double.BYTES * n);
    for (...) values.putDouble(<value computed in Java>);
    collection.read(values.flip());        # read(ByteBuffer) -- ingest, not compute

read(ByteBuffer) / read(InputStream) on a MemoryData/PackedCollection is the
sanctioned route for deserialization and file/network I/O. It may be called only
from an enumerated CLOSED SET of genuine deserializers -- the ingest allowlist.
This hook is the in-session mirror of the SetMemLiteralsDetector CI rules
INGEST_OUTSIDE_SANCTIONED_SURFACE and COMPUTED_VALUE_STAGED_FOR_INGEST, and it
reads the SAME allowlist file the detector does so the two cannot drift.

It BLOCKS (exit non-zero; the edit is never written) when an edit introduces,
in a computation-layer Java file that is NOT on the ingest allowlist, either:
  1. a new read(ByteBuffer) / read(InputStream) call on a collection, or
  2. the allocate/wrap -> put* -> read triple (a host-filled buffer),
whichever the file's layer. The allocate->put->read triple is blocked even when
the file IS allowlisted, because staging computed values is never ingest -- that
is what stops the evasion from simply relocating into an allowlisted file.
"""

import json
import os
import re
import sys

# Shared source of truth with SetMemLiteralsDetector -- do not duplicate the list.
ALLOWLIST_RESOURCE = os.path.join(
    "engine", "utils", "src", "main", "resources",
    "org", "almostrealism", "util", "ingest-allowlist.txt")

# Files that IMPLEMENT the ingest/write surface itself (they define read()); editing
# them is not the threat this hook guards against. Mirrors the read-implementing
# members of SetMemLiteralsDetector.SANCTIONED_WRITE_SURFACE.
SANCTIONED_SURFACE = (
    "/hardware/MemoryData.java",
    "/hardware/mem/MemoryDataAdapter.java",
    "/collect/PackedCollection.java",
    "/color/RGB.java",
)

# Computation layers the policy governs. flowtree/tools are excluded (not the
# device-memory computation layers), matching where the producer rule applies.
COMPUTATION_LAYER = re.compile(
    r"/(base|compute|domain|engine|studio)/[^ ]*/src/(main|test)/java/.*\.java$")

# A ByteBuffer local: <id> = ByteBuffer.allocate(...) / ByteBuffer.wrap(...).
BUFFER_ORIGIN = re.compile(
    r"\b([A-Za-z_$][\w$]*)\s*=\s*ByteBuffer\s*\.\s*(?:allocate|allocateDirect|wrap)\s*\(")

# <lowercase-receiver>.read( -- an instance read (a type-name receiver is static).
READ_CALL = re.compile(r"\b([a-z_$][\w$]*)\s*\.\s*read\s*\(")

TRAILING_BUFFER_METHOD = re.compile(
    r"(?:\.\s*(?:flip|rewind|duplicate|slice|asReadOnlyBuffer|clear|mark|reset)\s*\(\s*\))+$")

IDENTIFIER = re.compile(r"^[A-Za-z_$][\w$]*$")


def allowlist_fragments():
    """Path fragments from the shared ingest allowlist file (empty if unreadable)."""
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    path = os.path.join(root, ALLOWLIST_RESOURCE)
    fragments = []
    try:
        with open(path) as f:
            for line in f:
                fragment = line.split("#", 1)[0].strip()
                if fragment:
                    fragments.append(fragment)
    except OSError:
        pass
    return fragments


def masked(text):
    """Blank out // and /* */ comments and string/char literals, preserving length."""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            out[i] = " "
            i += 1
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            while i < n and not (text[i - 1] == "*" and text[i] == "/"):
                i += 1
            if i < n:
                out[i] = " "
                i += 1
        elif c in "\"'":
            quote = c
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\" and i + 1 < n:
                    out[i] = out[i + 1] = " "
                    i += 2
                    continue
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                i += 1
        else:
            i += 1
    return "".join(out)


def matching_paren(text, start):
    """Index of the ) closing the ( just before start, or -1."""
    depth = 1
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def declared_buffer_or_stream(text, ident):
    """True if ident is declared as a ByteBuffer or an *InputStream in text."""
    return re.search(
        r"(?:ByteBuffer|[A-Za-z_$][\w$]*InputStream|InputStream)\s+" + re.escape(ident) + r"\b",
        text) is not None


def is_ingest_arg(arg, text):
    """True if a read(...) argument resolves to a ByteBuffer or InputStream."""
    core = TRAILING_BUFFER_METHOD.sub("", arg.strip()).strip()
    if core.startswith("ByteBuffer."):
        return True
    if re.match(r"\(\s*ByteBuffer\s*\)", core):
        return True
    if re.match(r"new\s+\w*InputStream\b", core):
        return True
    return bool(IDENTIFIER.match(core)) and declared_buffer_or_stream(text, core)


# TODO(review): unlike SetMemLiteralsDetector.isDeclaredChannel, this hook does not exclude
# *Channel receivers, so a newly-introduced FileChannel.read(ByteBuffer) (host file I/O, not
# MemoryData ingest) is a false-positive BLOCK here while the CI detector passes it -- mirror
# the channel exclusion so the hook and detector cannot drift.
def ingest_reads(text):
    """List of (receiver, buffer_id_or_None) for each ingest read in text."""
    reads = []
    for m in READ_CALL.finditer(text):
        args_start = m.end()
        args_end = matching_paren(text, args_start)
        if args_end < 0:
            continue
        arg = text[args_start:args_end]
        if "," in arg:               # more than one top-level arg is not MemoryData.read
            continue
        if not arg.strip() or not is_ingest_arg(arg, text):
            continue
        core = TRAILING_BUFFER_METHOD.sub("", arg.strip()).strip()
        buffer_id = core if IDENTIFIER.match(core) else None
        reads.append((m.group(1), buffer_id))
    return reads


def staged_buffers(text):
    """Names of buffers that are allocate/wrap'd, put* into, then passed to read()."""
    read_ids = {b for _, b in ingest_reads(text) if b}
    staged = set()
    for m in BUFFER_ORIGIN.finditer(text):
        name = m.group(1)
        if name not in read_ids:
            continue
        if re.search(r"\b" + re.escape(name) + r"\s*(?:\.|::)\s*put", text):
            staged.add(name)
    return staged


def change_texts(tool, tool_input):
    """Return (before, after) full-file texts for the change, or None."""
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

    norm = path.replace("\\", "/")
    if not COMPUTATION_LAYER.search(norm):
        sys.exit(0)
    if any(fragment in norm for fragment in SANCTIONED_SURFACE):
        sys.exit(0)

    texts = change_texts(tool, tool_input)
    if texts is None or texts[0] == texts[1]:
        sys.exit(0)
    before, after = masked(texts[0]), masked(texts[1])

    allowlisted = any(fragment in norm for fragment in allowlist_fragments())

    blocks = []

    before_staged, after_staged = staged_buffers(before), staged_buffers(after)
    if after_staged - before_staged:
        blocks.append(
            "This edit stages a host-filled ByteBuffer (allocate/wrap -> put* -> read) and "
            "ships it through the ingest surface: "
            + ", ".join(sorted(after_staged - before_staged))
            + ". That is the same violation as writing the values element by element, and it "
            "is forbidden even in an allowlisted deserializer.")

    if not allowlisted:
        before_reads, after_reads = ingest_reads(before), ingest_reads(after)
        added = [r for r in after_reads if after_reads.count(r) > before_reads.count(r)]
        if added:
            receivers = sorted({recv for recv, _ in added})
            blocks.append(
                "This edit introduces a read(ByteBuffer)/read(InputStream) ingest call on "
                + ", ".join(receivers)
                + ", but this file is not on the ingest allowlist. That surface is only for "
                "data entering the process from OUTSIDE it (deserialization, file/network I/O).")

    if blocks:
        message = (
            "BLOCKED: HOST-TO-DEVICE INGEST EVASION in " + path + "\n\n"
            + "\n\n".join(blocks) + "\n\n"
            + "WHY: values Java computes must reach device memory through a Producer "
            "(producer arithmetic, an index comparison, a producer assignment), never by "
            "staging them in a ByteBuffer and shipping them through read(...). read(ByteBuffer)"
            " / read(InputStream) is ingest for external data only.\n\n"
            + "THE FIX: produce the values on the device -- a scalar with fill(value), a mask or "
            "index vector with producer arithmetic over integers()/a comparison, any length or "
            "bound supplied as data. If this really is a genuine outside-the-process "
            "deserializer, add its path to " + ALLOWLIST_RESOURCE + " with a justification "
            "naming the outside source (and it will still not be allowed to stage computed "
            "values through the buffer).")
        print(message, file=sys.stderr)
        sys.exit(2)

    sys.exit(0)


if __name__ == "__main__":
    main()
