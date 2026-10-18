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

# A ByteBuffer local assignment suffix: = ByteBuffer.allocate(...) / wrap(...),
# accepting the fully qualified java.nio.ByteBuffer form as well. Prefixed with a
# quoted buffer identifier at the call site.
BUFFER_ORIGIN_SUFFIX = (
    r"\s*=\s*(?:java\.nio\.)?ByteBuffer\s*\.\s*(?:allocate|allocateDirect|wrap)\s*\(")

# <lowercase-receiver>.read( -- an instance read (a type-name receiver is static).
# An optional empty argument list ((?:\(\s*\))?) lets a method-call receiver such
# as getData().read(buffer) be matched as well as a plain or qualified identifier.
READ_CALL = re.compile(r"\b([a-z_$][\w$]*)\s*(?:\(\s*\))?\s*\.\s*read\s*\(")

TRAILING_BUFFER_METHOD = re.compile(
    r"(?:\.\s*(?:flip|rewind|duplicate|slice|asReadOnlyBuffer|clear|mark|reset)\s*\(\s*\))+$")

IDENTIFIER = re.compile(r"^[A-Za-z_$][\w$]*$")


def path_matches(path, entry):
    """True if path is the allowlist entry, anchored at a path boundary. Entries
    are canonical repo-relative paths, matched as a suffix of the (absolute or
    relative) file path rather than an unanchored substring, so a file sharing
    only a basename with an allowlisted deserializer does not inherit its
    exemption. Mirrors SetMemLiteralsDetector.isIngestAllowlisted."""
    norm = entry.replace("\\", "/")
    return path == norm or path.endswith("/" + norm)


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


def declared_byte_array(text, ident):
    """True if ident is declared as a byte[] (or byte... vararg) in text."""
    return re.search(
        r"byte\s*(?:\[\s*\]|\.\.\.)\s+" + re.escape(ident) + r"\b", text) is not None


def declared_channel(text, ident):
    """True if ident is declared as a *Channel in text (mirrors the detector's
    isDeclaredChannel). A java.nio channel shares the read(ByteBuffer) signature
    but reads external bytes into a host buffer, never device memory, so it is
    not the MemoryData ingest surface this hook guards."""
    return re.search(
        r"(?:[A-Za-z_$][\w$]*)?Channel(?:\s*<[^;{}=]*>)?\s+" + re.escape(ident) + r"\b",
        text) is not None


def declared_host_reader(text, ident):
    """True if ident is declared as an InputStream or Reader in text. These share
    the read(byte[])/read(char[]) signature with MemoryData's byte-array ingest
    overload but perform host I/O, so a byte-array read on such a receiver is not
    MemoryData ingest."""
    return re.search(
        r"(?:[A-Za-z_$][\w$]*)?(?:InputStream|Reader)\s+" + re.escape(ident) + r"\b",
        text) is not None


def is_ingest_arg(arg, text):
    """True if a read(...) argument resolves to a ByteBuffer or InputStream,
    accepting the fully qualified java.nio.ByteBuffer forms as well."""
    core = TRAILING_BUFFER_METHOD.sub("", arg.strip()).strip()
    if core.startswith("ByteBuffer.") or core.startswith("java.nio.ByteBuffer."):
        return True
    if re.match(r"\(\s*(?:java\.nio\.)?ByteBuffer\s*\)", core):
        return True
    if re.match(r"new\s+\w*InputStream\b", core):
        return True
    return bool(IDENTIFIER.match(core)) and declared_buffer_or_stream(text, core)


def is_byte_array_arg(arg, text):
    """True if a read(...) argument resolves to a byte[] -- a new byte[...] or a
    bare identifier declared as a byte[]. MemoryData.read(byte[]) delegates to
    read(ByteBuffer), so a computed byte[] is another host-to-device upload."""
    core = arg.strip()
    if re.match(r"new\s+byte\s*\[", core):
        return True
    return bool(IDENTIFIER.match(core)) and declared_byte_array(text, core)


def split_top_level(arg):
    """Split on top-level commas, ignoring commas nested in (), [] or {}."""
    args = []
    depth = 0
    cur = []
    for c in arg:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
    last = "".join(cur).strip()
    if last:
        args.append(last)
    return [a for a in args if a]


def ingest_reads(text):
    """List of (receiver, buffer_id_or_None, read_start) for each ingest read in text.

    Mirrors SetMemLiteralsDetector.scanIngestReads: a *Channel receiver is a
    java.nio channel, not MemoryData, and is excluded; the argument list is split
    on top-level commas (so a nested comma in ByteBuffer.wrap(bytes, 0, len) does
    not make a single read look like a multi-argument call); and the single
    argument must resolve to a ByteBuffer/InputStream, or to a byte[] whose
    receiver is not a host InputStream/Reader (read(byte[]) ingest)."""
    reads = []
    for m in READ_CALL.finditer(text):
        receiver = m.group(1)
        if declared_channel(text, receiver):
            continue
        args_start = m.end()
        args_end = matching_paren(text, args_start)
        if args_end < 0:
            continue
        args = split_top_level(text[args_start:args_end])
        if len(args) != 1:           # MemoryData.read takes exactly one argument
            continue
        single = args[0]
        ingest = is_ingest_arg(single, text)
        byte_arr = not ingest and is_byte_array_arg(single, text)
        if byte_arr and declared_host_reader(text, receiver):
            continue
        if not ingest and not byte_arr:
            continue
        core = TRAILING_BUFFER_METHOD.sub("", single).strip()
        buffer_id = core if IDENTIFIER.match(core) else None
        reads.append((receiver, buffer_id, m.start()))
    return reads


def is_host_staged(text, buffer_id, read_start):
    """True if buffer_id was allocate/wrap'd and then put* into BEFORE the read at
    read_start (mirrors the detector's isHostStaged ordering check), so a legitimate
    allocate -> external read -> later reuse/put is not treated as staging."""
    origin = re.compile(r"\b" + re.escape(buffer_id) + BUFFER_ORIGIN_SUFFIX)
    decl_pos = -1
    for m in origin.finditer(text):
        if m.start() < read_start:
            decl_pos = m.start()
        else:
            break
    if decl_pos < 0:
        return False
    put = re.compile(r"\b" + re.escape(buffer_id) + r"\s*(?:\.|::)\s*put")
    return any(decl_pos < m.start() < read_start for m in put.finditer(text))


def staged_buffers(text):
    """Names of buffers that are allocate/wrap'd, put* into, then passed to read()."""
    staged = set()
    for _, buffer_id, read_start in ingest_reads(text):
        if buffer_id and is_host_staged(text, buffer_id, read_start):
            staged.add(buffer_id)
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

    allowlisted = any(path_matches(norm, entry) for entry in allowlist_fragments())

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
        before_keys = [(r[0], r[1]) for r in ingest_reads(before)]
        after_keys = [(r[0], r[1]) for r in ingest_reads(after)]
        added = [k for k in after_keys if after_keys.count(k) > before_keys.count(k)]
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
