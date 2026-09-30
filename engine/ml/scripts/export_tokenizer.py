#!/usr/bin/env python3
"""
Export a HuggingFace BPE tokenizer to the compact binary the Java
``SentencePieceBPETokenizer`` reads.

A ``tokenizer.json`` is a large JSON document (tens of megabytes for a 256k
vocabulary) and ``engine/ml`` has no JSON parser on its classpath, so the
vocabulary and merge list are converted here, once, into a length-prefixed
binary that Java can read with ``DataInputStream`` and nothing else. This
mirrors how model weights are handled: the Python side owns the HuggingFace
formats, and the Java side reads a format designed for it.

The format is length-prefixed rather than line-delimited because BPE tokens are
not line-safe. A SentencePiece vocabulary contains tokens that are runs of
newlines and runs of ``\\u2581``, so any newline- or space-delimited encoding
corrupts part of the vocabulary.

All integers are big-endian, matching ``DataInputStream``::

    magic       4 bytes, "ARTK"
    version     int32, currently 2
    vocabSize   int32
                vocabSize entries, in token-id order:
                    int32 length, then that many UTF-8 bytes
    mergeCount  int32
                mergeCount entries, in priority order (best merge first):
                    int32 length + UTF-8 bytes for the left element,
                    int32 length + UTF-8 bytes for the right element
    bosId       int32, -1 when the tokenizer has none
    eosId       int32, -1 when the tokenizer has none
    padId       int32, -1 when the tokenizer has none
    unkId       int32, -1 when the tokenizer has none
    addedCount  int32
                addedCount entries, one per added token:
                    int32 id,
                    int8 1 when matched after normalization, else 0,
                    int8 1 when special (skipped on decode), else 0,
                    int32 length + UTF-8 bytes for the content

A token id absent from the vocabulary (ids are dense in practice, but a gap is
possible) is written as a zero-length entry. An added token's content is written
at its id in the vocabulary, since the source tokenizer resolves that id to it.

Usage::

    python export_tokenizer.py --tokenizer-dir /path/to/t5gemma-b-b-ul2 \\
        --out /path/to/t5gemma-tokenizer.bin
"""

import argparse
import json
import os
import struct

MAGIC = b"ARTK"
VERSION = 2

# The SentencePiece boundary marker (U+2581). The Java reader replaces an ASCII space with it.
BOUNDARY = "▁"

# The largest vocabulary the Java reader accepts: SentencePieceBPETokenizer.readCount rejects a
# vocabulary, merge or added-token count above MAX_ENTRIES (1 << 24) before allocating.
# write_tokenizer sizes the token table as max(id) + 1, so the largest usable id is MAX_ENTRIES - 1:
# a single id at or above MAX_ENTRIES would allocate a table that large here and then emit a binary
# the reader refuses.
MAX_ENTRIES = 1 << 24

# The largest length, in bytes, of any single serialized string the Java reader accepts:
# SentencePieceBPETokenizer.readString rejects a length above MAX_STRING_BYTES (1 << 20) before
# allocating. A vocabulary token, merge element or added-token content whose UTF-8 encoding is longer
# would be written here and then refused by the reader, so it is rejected before the binary is opened.
MAX_STRING_BYTES = 1 << 20


def _flatten(node, sequence_key):
    """Flatten one pipeline node.

    A ``Sequence`` node yields each of its children (recursively); any other node yields
    itself. An absent node yields nothing.
    """
    if not node:
        return []
    if node.get("type") == "Sequence":
        result = []
        for child in node.get(sequence_key) or []:
            result.extend(_flatten(child, sequence_key))
        return result
    return [node]


def _pattern_string(component):
    """The literal string a ``Replace``/``Split`` component matches, or ``None``.

    ``tokenizer.json`` wraps a literal pattern as ``{"String": " "}`` and a regex pattern as
    ``{"Regex": "..."}``; only the literal form is unwrapped here.
    """
    pattern = component.get("pattern")
    if isinstance(pattern, dict):
        return pattern.get("String")
    return pattern


def _require_token_id(description, index):
    """Raise ``ValueError`` unless ``index`` is a token id the Java reader can hold.

    A valid id is a non-negative integer below :data:`MAX_ENTRIES`. The upper bound matches the
    Java reader, which sizes its vocabulary at ``max(id) + 1`` and rejects a vocabulary above
    ``MAX_ENTRIES``; enforcing it here stops a sparse or malformed id from allocating an oversized
    table in :func:`write_tokenizer` and emitting a binary the reader would then refuse.

    :param description: names the token in the error message.
    :param index: the id to check.
    """
    if not isinstance(index, int) or isinstance(index, bool) or index < 0:
        raise ValueError("%s has invalid id %r; token ids must be non-negative integers"
                         % (description, index))
    if index >= MAX_ENTRIES:
        raise ValueError(
            "%s has id %d at or above the %d-token limit the Java reader accepts; the exported "
            "table is sized by the largest id, so a larger id would allocate an oversized table and "
            "produce a binary the reader rejects" % (description, index, MAX_ENTRIES))


def _merge_parts(merge):
    """The ``(left, right)`` elements of one merge, in either representation ``tokenizer.json`` uses.

    A merge is stored either as a two-element list or as a single space-separated string, depending on
    the version of ``tokenizers`` that wrote the file.

    :param merge: one entry of ``model.merges``.
    :return: the left and right elements as a tuple.
    """
    if isinstance(merge, str):
        left, right = merge.split(" ", 1)
        return left, right
    return merge[0], merge[1]


def _require_string_length(description, content):
    """Raise ``ValueError`` unless ``content`` fits the Java reader's per-string byte limit.

    The reader rejects any serialized string whose declared length exceeds :data:`MAX_STRING_BYTES`
    before allocating for it, so a longer token, merge element or added-token content would export
    without complaint and then fail to load. It is rejected here instead.

    :param description: names the string in the error message.
    :param content: the string whose UTF-8 encoding is measured.
    """
    length = len(content.encode("utf-8"))
    if length > MAX_STRING_BYTES:
        raise ValueError(
            "%s is %d bytes, above the %d-byte per-string byte limit the Java reader accepts; the reader "
            "rejects a longer string before allocating, so the exported binary would not load"
            % (description, length, MAX_STRING_BYTES))


def validate_pipeline(spec):
    """Reject a tokenizer whose pipeline the Java ``SentencePieceBPETokenizer`` does not reproduce.

    The Java reader splits out added tokens atomically, treats each remaining span of text as one
    segment, replaces an ASCII space with the SentencePiece boundary marker, falls back to one
    ``<0xNN>`` token per UTF-8 byte for characters outside the vocabulary, and reverses exactly that
    on decode. The exported binary carries only the vocabulary, merges, special ids and added
    tokens -- none of the normalizer, pre-tokenizer, decoder or post-processor configuration -- so
    a tokenizer that normalizes, pre-tokenizes, decodes or adds special tokens differently would
    export without complaint and then silently produce token ids other than the source
    tokenizer's. Rather than allow that, the unsupported pipeline is rejected here.

    :param spec: the parsed ``tokenizer.json`` document.
    :raises ValueError: if any stage is one the Java reader does not implement.
    """
    model = spec.get("model") or {}
    if model.get("type") != "BPE":
        raise ValueError(
            "only a BPE tokenizer can be exported; this one is %r" % model.get("type"))
    if not model.get("byte_fallback"):
        raise ValueError(
            "only a byte-fallback tokenizer can be exported; this one does not set "
            "model.byte_fallback, so the Java reader's <0xNN> handling of characters outside the "
            "vocabulary would not match the source tokenizer")

    # HuggingFace BPE enables byte fallback only when the vocabulary contains all 256 <0xNN> atoms.
    # With an incomplete set the source tokenizer falls back to one unknown token per unknown
    # character, which neither the exported binary (it carries no unknown-fallback rule) nor the
    # Java reader reproduces -- the reader rejects a character whose byte token is missing. So an
    # incomplete byte-fallback vocabulary cannot match the source and is rejected here, where the
    # whole vocabulary is available, rather than surfacing later as an encode-time failure.
    vocab = model.get("vocab") or {}
    missing = ["<0x%02X>" % value for value in range(256)
               if ("<0x%02X>" % value) not in vocab]
    if missing:
        raise ValueError(
            "incomplete byte-fallback vocabulary; %d of 256 <0xNN> byte tokens are missing "
            "(e.g. %s). HuggingFace enables byte fallback only with all 256 present, so an "
            "incomplete vocabulary cannot reproduce the source tokenizer"
            % (len(missing), ", ".join(missing[:8])))

    # write_tokenizer sizes and indexes the token table by id, so a negative id would silently
    # overwrite a slot from the end of the table (Python negative indexing) and write a binary the
    # Java reader then rejects. Every id is checked here, before anything is allocated.
    for token, index in vocab.items():
        _require_token_id("vocabulary token %r" % token, index)

    # The Java reader applies every merge deterministically, in priority order, to the per-character
    # symbols of the whole text, and does nothing else. BPE-dropout skips merges at random, and
    # ignore_merges looks a whole word up in the vocabulary before merging; a continuing-subword
    # prefix or end-of-word suffix decorates the symbols. Each changes the ids the source produces.
    if model.get("dropout"):
        raise ValueError(
            "unsupported BPE dropout %r; the Java reader applies every merge deterministically"
            % model.get("dropout"))
    if model.get("ignore_merges"):
        raise ValueError(
            "unsupported BPE ignore_merges; the Java reader always applies the merges rather than "
            "first matching a whole segment against the vocabulary")
    if model.get("fuse_unk"):
        raise ValueError(
            "unsupported BPE fuse_unk; it collapses a run of unknown characters into one token, "
            "while the Java reader emits one fallback token per symbol")
    for option in ("continuing_subword_prefix", "end_of_word_suffix"):
        if model.get(option):
            raise ValueError(
                "unsupported BPE %s %r; the Java reader does not decorate symbols"
                % (option, model.get(option)))

    saw_boundary_replace = False
    for component in _flatten(spec.get("normalizer"), "normalizers"):
        kind = component.get("type")
        if kind == "Replace" and _pattern_string(component) == " " \
                and component.get("content") == BOUNDARY:
            saw_boundary_replace = True
            continue
        raise ValueError(
            "unsupported normalizer step %r; the Java reader applies no normalization beyond "
            "replacing a space with the boundary marker, so a step such as Prepend, NFKC or a "
            "different Replace would change the tokenization" % kind)

    if not saw_boundary_replace:
        raise ValueError(
            "the tokenizer's normalizer does not replace spaces with the boundary marker; the "
            "Java reader relies on that substitution being the whole of normalization")

    for component in _flatten(spec.get("pre_tokenizer"), "pretokenizers"):
        kind = component.get("type")
        # After the normalizer has turned every space into the boundary marker, a Split on a
        # literal space matches nothing and is a no-op, which is the only pre-tokenization the
        # whole-text Java reader reproduces. An inverted Split is not a no-op: with no match, the
        # whole text becomes the "match" and the configured behavior applies to it (Removed drops
        # it). A ByteLevel or Metaspace pre-tokenizer is not reproduced either.
        if kind == "Split" and _pattern_string(component) == " " \
                and not component.get("invert"):
            continue
        raise ValueError(
            "unsupported pre-tokenizer step %r; the Java reader does not split the input, so a "
            "byte-level or metaspace pre-tokenizer would change the tokenization" % kind)

    # The Java decoder restores spaces from the boundary marker on vocabulary tokens and then folds
    # byte tokens, in that order, and does nothing else. A trailing Fuse only concatenates the
    # already-decoded pieces, which the decode has effectively done, so it is accepted as the
    # optional final step. A Fuse anywhere earlier runs before the boundary replacement and byte
    # folding and destroys the per-token boundaries those steps rely on, so it is rejected along
    # with every other step and ordering.
    steps = _flatten(spec.get("decoder"), "decoders")
    if steps and steps[-1].get("type") == "Fuse":
        steps = steps[:-1]
    kinds = [component.get("type") for component in steps]
    if kinds != ["Replace", "ByteFallback"] or _pattern_string(steps[0]) != BOUNDARY \
            or steps[0].get("content") != " ":
        raise ValueError(
            "unsupported decoder %r; the Java reader decodes by replacing the boundary marker with "
            "a space and then folding byte tokens, matching only a Replace(boundary -> space), "
            "ByteFallback[, optional trailing Fuse] decoder" % kinds)

    # The binary carries no post-processor and the Java reader adds no special tokens, so a
    # post-processor is accepted only when it adds none either. The single-sequence template must be
    # exactly one Sequence carrying the input ids (id "A"): a template that adds a special token, or
    # one that repeats the sequence (e.g. [$A, $A], which duplicates every input id), would change
    # the ids the Java reader never reproduces.
    for component in _flatten(spec.get("post_processor"), "processors"):
        kind = component.get("type")
        single = component.get("single") or []
        if kind == "TemplateProcessing" and len(single) == 1 \
                and "Sequence" in single[0] \
                and single[0]["Sequence"].get("id") == "A":
            continue
        raise ValueError(
            "unsupported post-processor %r; the Java reader adds no special tokens and emits each "
            "input id once, so a post-processor that adds BOS/EOS or repeats the sequence would "
            "make the exported tokenizer disagree with the source" % kind)

    # An added token is matched against the input before the BPE model runs, so its whole content
    # becomes one id wherever it appears in the text -- a special (control) token such as <pad> as
    # much as any other, since "special" only affects decoding and post-processing, not matching.
    # The Java reader reproduces that matching for the added tokens carried in the binary, with the
    # leftmost-longest rule, raw tokens before normalized ones. It does not implement the
    # whitespace-stripping or whole-word options, and it matches a normalized token against the
    # text before normalization, which is the same only when the content holds neither a space nor
    # the boundary marker (the one pair of characters the normalizer changes); anything else is
    # rejected.
    for added in spec.get("added_tokens") or []:
        content = added.get("content")
        _require_token_id("added token %r" % content, added.get("id"))
        if not content:
            raise ValueError("unsupported added token %r with empty content" % added.get("id"))
        for option in ("single_word", "lstrip", "rstrip"):
            if added.get(option):
                raise ValueError(
                    "unsupported added token %r with %s; the Java reader matches added tokens "
                    "exactly and does not reproduce that option" % (content, option))
        if added.get("normalized") and (" " in content or BOUNDARY in content):
            raise ValueError(
                "unsupported normalized added token %r; the Java reader matches it before "
                "normalization, which differs from the source when the content contains a space "
                "or the boundary marker" % content)


def read_tokenizer(tokenizer_dir):
    """Load ``tokenizer.json`` and return its vocabulary, merges, special ids and added tokens.

    The added tokens are ``(id, content, normalized, special)`` tuples, in ``tokenizer.json`` order.

    The special ids are read from ``tokenizer_config.json`` / ``config.json``
    when present, since ``tokenizer.json`` itself does not name them.

    :raises ValueError: if a special token named by ``tokenizer_config.json``, or an id named by
        ``config.json``, is not in the exported vocabulary.
    """
    with open(os.path.join(tokenizer_dir, "tokenizer.json")) as handle:
        spec = json.load(handle)

    validate_pipeline(spec)

    model = spec.get("model") or {}
    vocab = model.get("vocab") or {}
    merges = model.get("merges") or []

    added = [(token["id"], token["content"], bool(token.get("normalized")),
              bool(token.get("special")))
             for token in spec.get("added_tokens") or []]

    # A special token named by tokenizer_config.json may live only in the added vocabulary, so its
    # id is looked up there as well as in the model vocabulary.
    ids = dict(vocab)
    ids.update((content, index) for index, content, _, _ in added)

    specials = {key: -1 for key in ("bos", "eos", "pad", "unk")}
    # Keys tokenizer_config.json declared explicitly, whether it named a token or an explicit null.
    # A null declares the token absent, so config.json must not resurrect it below; only a key that
    # tokenizer_config.json omits entirely is eligible for the config.json fallback.
    declared = set()

    config_path = os.path.join(tokenizer_dir, "tokenizer_config.json")
    if os.path.exists(config_path):
        with open(config_path) as handle:
            config = json.load(handle)
        for key, name in (("bos", "bos_token"), ("eos", "eos_token"),
                          ("pad", "pad_token"), ("unk", "unk_token")):
            if name not in config:
                continue
            declared.add(key)
            token = config.get(name)
            if isinstance(token, dict):
                token = token.get("content")
            # A configured token absent from tokenizer.json would otherwise export as -1, which
            # means "this tokenizer has no such token" and silently drops its special behavior. An
            # explicit null instead declares the token absent on purpose and correctly stays -1.
            if token is not None:
                if token not in ids:
                    raise ValueError("%s token %r is not in the vocabulary" % (key, token))
                specials[key] = ids[token]

    # config.json is the documented fallback for any special id tokenizer_config.json did not name
    # at all. A model config commonly names the ids directly as integer ``*_token_id`` fields rather
    # than as token strings, so a snapshot carrying only those would otherwise export every id as -1
    # and change encode(..., add_special=True) and special-token decoding. A key tokenizer_config.json
    # declared -- including one it set to an explicit null -- is never refilled here, so config.json
    # cannot re-enable a token the tokenizer's own config declared absent.
    if any(specials[key] < 0 and key not in declared for key in specials):
        model_config_path = os.path.join(tokenizer_dir, "config.json")
        if os.path.exists(model_config_path):
            with open(model_config_path) as handle:
                model_config = json.load(handle)
            for key in ("bos", "eos", "pad", "unk"):
                if specials[key] < 0 and key not in declared:
                    token_id = model_config.get("%s_token_id" % key)
                    if isinstance(token_id, int) and not isinstance(token_id, bool):
                        specials[key] = token_id

    # config.json ids are copied as integers, so one naming no exported token would otherwise be
    # written and make encode(..., add_special=True) emit an index no embedding row exists for.
    known = set(ids.values())
    for key, value in specials.items():
        if value != -1 and value not in known:
            raise ValueError("%s token id %d is not in the vocabulary" % (key, value))

    return vocab, merges, specials, added


def _place_token(tokens, index, content, description):
    """Store ``content`` at ``index`` in ``tokens``, rejecting a conflicting collision.

    Two vocabulary entries, or a vocabulary entry and an added token, can name the same id. When
    they carry the same content the assignment is idempotent -- the ordinary case, since an added
    token repeats the vocabulary string already at its id. When they differ, the binary holds only
    one string per id, so the last write silently wins and the exported tokenizer would encode or
    decode that id differently from the source; that conflict is rejected here rather than resolved
    by writing order.

    :param tokens: the token table being filled, indexed by id.
    :param index: the id to store at.
    :param content: the token string to store.
    :param description: names the token being placed, for the error message.
    :raises ValueError: if a different string already occupies ``index``.
    """
    existing = tokens[index]
    if existing is not None and existing != content:
        raise ValueError(
            "conflicting content at token id %d: %r and %r; the exported vocabulary holds one "
            "string per id, so a collision with different content cannot reproduce the source "
            "tokenizer (%s)" % (index, existing, content, description))
    tokens[index] = content


def _require_within_reader_limits(tokens, merges, added):
    """Reject an export the Java reader would refuse to load, before any bytes are written.

    :func:`write_tokenizer` bounds each token id below :data:`MAX_ENTRIES`, so the vocabulary size --
    ``max(id) + 1`` -- is already within the reader's vocabulary cap and is not re-checked here. The
    reader also caps the merge and added-token counts at :data:`MAX_ENTRIES` and every serialized
    string at :data:`MAX_STRING_BYTES`; those bounds, which the id check does not imply, are mirrored
    here so that every export :func:`write_tokenizer` completes is loadable.

    :param tokens: the token table, indexed by id, holding vocabulary and added-token strings.
    :param merges: the merge list, each entry a list or a space-separated string.
    :param added: the added tokens, as ``(id, content, normalized, special)`` tuples.
    :raises ValueError: if a count or string length exceeds what the reader accepts.
    """
    for name, count in (("merge", len(merges)), ("added-token", len(added))):
        if count > MAX_ENTRIES:
            raise ValueError(
                "%s count %d is above the %d-entry limit the Java reader accepts; the reader rejects "
                "a larger count before allocating, so the exported binary would not load"
                % (name, count, MAX_ENTRIES))

    for index, token in enumerate(tokens):
        if token is not None:
            _require_string_length("token id %d content %r" % (index, token), token)
    for merge in merges:
        for part in _merge_parts(merge):
            _require_string_length("merge element %r" % part, part)


def _require_special_ids(specials, tokens):
    """Reject a special id the Java reader would refuse or that names no exported token.

    Each of ``bos``/``eos``/``pad``/``unk`` is either ``-1`` (the tokenizer has none) or an index
    into the vocabulary. The reader's :code:`readControlId` accepts only ``-1`` or ``0..size-1`` and
    :func:`read_tokenizer` further requires the id to name an actual token, so a value outside that
    range, or one pointing at an unfilled (zero-length) slot, would either be rejected on load or
    decode to an empty string. Both are rejected here, before the binary is opened.

    :param specials: the ``{"bos": id, "eos": id, "pad": id, "unk": id}`` mapping to be written.
    :param tokens: the filled token table, indexed by id.
    :raises ValueError: if a non-``-1`` special id is invalid, out of range, or names an empty slot.
    """
    for key in ("bos", "eos", "pad", "unk"):
        value = specials.get(key, -1)
        if value == -1:
            continue
        _require_token_id("%s token" % key, value)
        if value >= len(tokens) or tokens[value] is None:
            raise ValueError(
                "%s token id %d names no exported token; a special id must be -1 or index a "
                "populated vocabulary slot, or the Java reader rejects the binary or decodes it to "
                "an empty token" % (key, value))


def write_tokenizer(path, vocab, merges, specials, added=()):
    """Write the binary described in the module docstring, returning its size.

    ``read_tokenizer`` has already validated every id, but this is a public entry point its own tests
    call directly, so it re-checks each id it indexes the token table by rather than trusting the
    caller. A negative id would otherwise wrap through Python's negative indexing and silently place a
    token at the wrong slot, and an out-of-range or dangling special id would write a binary the Java
    reader refuses to load; both are rejected here before any bytes are written.
    """
    for token, index in vocab.items():
        _require_token_id("vocabulary token %r" % token, index)
    for index, content, _, _ in added:
        _require_token_id("added token %r" % content, index)

    ids = list(vocab.values()) + [index for index, _, _, _ in added]
    size = max(ids) + 1 if ids else 0
    tokens = [None] * size
    for token, index in vocab.items():
        _place_token(tokens, index, token, "vocabulary token %r" % token)
    for index, content, _, _ in added:
        _place_token(tokens, index, content, "added token %r" % content)

    _require_special_ids(specials, tokens)
    _require_within_reader_limits(tokens, merges, added)

    with open(path, "wb") as out:
        out.write(MAGIC)
        out.write(struct.pack(">i", VERSION))

        out.write(struct.pack(">i", size))
        for token in tokens:
            encoded = b"" if token is None else token.encode("utf-8")
            out.write(struct.pack(">i", len(encoded)))
            out.write(encoded)

        out.write(struct.pack(">i", len(merges)))
        for merge in merges:
            for part in _merge_parts(merge):
                encoded = part.encode("utf-8")
                out.write(struct.pack(">i", len(encoded)))
                out.write(encoded)

        for key in ("bos", "eos", "pad", "unk"):
            out.write(struct.pack(">i", specials.get(key, -1)))

        out.write(struct.pack(">i", len(added)))
        for index, content, normalized, special in added:
            encoded = content.encode("utf-8")
            out.write(struct.pack(">ibb", index, 1 if normalized else 0, 1 if special else 0))
            out.write(struct.pack(">i", len(encoded)))
            out.write(encoded)

    return os.path.getsize(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tokenizer-dir", required=True,
                        help="Directory holding tokenizer.json (and tokenizer_config.json)")
    parser.add_argument("--out", required=True, help="Path of the binary to write")
    args = parser.parse_args()

    vocab, merges, specials, added = read_tokenizer(args.tokenizer_dir)
    size = write_tokenizer(args.out, vocab, merges, specials, added)

    print("wrote %s" % args.out)
    print("  vocabulary %d tokens, %d merges, %.1f MB" % (
        len(vocab), len(merges), size / (1024.0 * 1024.0)))
    print("  %d added tokens" % len(added))
    print("  bos=%(bos)d eos=%(eos)d pad=%(pad)d unk=%(unk)d" % specials)


if __name__ == "__main__":
    main()
