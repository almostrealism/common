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


def validate_pipeline(spec):
    """Reject a tokenizer whose pipeline the Java ``SentencePieceBPETokenizer`` does not reproduce.

    The Java reader treats the whole text as one segment, replaces an ASCII space with the
    SentencePiece boundary marker, falls back to one ``<0xNN>`` token per UTF-8 byte for characters
    outside the vocabulary, and reverses exactly that on decode. The exported binary carries only
    the vocabulary, merges, special ids and added tokens -- none of the normalizer, pre-tokenizer, decoder or
    post-processor configuration -- so a tokenizer that normalizes, pre-tokenizes, decodes or adds
    special tokens differently would
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

    The added tokens are ``(id, content, normalized)`` triples, in ``tokenizer.json`` order.

    The special ids are read from ``tokenizer_config.json`` / ``config.json``
    when present, since ``tokenizer.json`` itself does not name them.
    """
    with open(os.path.join(tokenizer_dir, "tokenizer.json")) as handle:
        spec = json.load(handle)

    validate_pipeline(spec)

    model = spec.get("model") or {}
    vocab = model.get("vocab") or {}
    merges = model.get("merges") or []

    added = [(token["id"], token["content"], bool(token.get("normalized")))
             for token in spec.get("added_tokens") or []]

    # A special token named by tokenizer_config.json may live only in the added vocabulary, so its
    # id is looked up there as well as in the model vocabulary.
    ids = dict(vocab)
    ids.update((content, index) for index, content, _ in added)

    specials = {key: -1 for key in ("bos", "eos", "pad", "unk")}

    config_path = os.path.join(tokenizer_dir, "tokenizer_config.json")
    if os.path.exists(config_path):
        with open(config_path) as handle:
            config = json.load(handle)
        for key, name in (("bos", "bos_token"), ("eos", "eos_token"),
                          ("pad", "pad_token"), ("unk", "unk_token")):
            token = config.get(name)
            if isinstance(token, dict):
                token = token.get("content")
            if token is not None:
                specials[key] = ids.get(token, -1)

    # config.json is the documented fallback for any special id tokenizer_config.json did not
    # resolve. A model config commonly names the ids directly as integer ``*_token_id`` fields
    # rather than as token strings, so a snapshot carrying only those would otherwise export every
    # id as -1 and change encode(..., add_special=True) and special-token decoding.
    if any(value < 0 for value in specials.values()):
        model_config_path = os.path.join(tokenizer_dir, "config.json")
        if os.path.exists(model_config_path):
            with open(model_config_path) as handle:
                model_config = json.load(handle)
            for key in ("bos", "eos", "pad", "unk"):
                if specials[key] < 0:
                    token_id = model_config.get("%s_token_id" % key)
                    if isinstance(token_id, int) and not isinstance(token_id, bool):
                        specials[key] = token_id

    return vocab, merges, specials, added


def write_tokenizer(path, vocab, merges, specials, added=()):
    """Write the binary described in the module docstring, returning its size."""
    ids = list(vocab.values()) + [index for index, _, _ in added]
    size = max(ids) + 1 if ids else 0
    tokens = [None] * size
    for token, index in vocab.items():
        tokens[index] = token
    for index, content, _ in added:
        tokens[index] = content

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
            # tokenizer.json holds a merge either as a two-element list or as a
            # single space-separated string, depending on the version that wrote it.
            if isinstance(merge, str):
                left, right = merge.split(" ", 1)
            else:
                left, right = merge[0], merge[1]
            for part in (left, right):
                encoded = part.encode("utf-8")
                out.write(struct.pack(">i", len(encoded)))
                out.write(encoded)

        for key in ("bos", "eos", "pad", "unk"):
            out.write(struct.pack(">i", specials.get(key, -1)))

        out.write(struct.pack(">i", len(added)))
        for index, content, normalized in added:
            encoded = content.encode("utf-8")
            out.write(struct.pack(">ib", index, 1 if normalized else 0))
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
