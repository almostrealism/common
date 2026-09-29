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
    version     int32, currently 1
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

A token id absent from the vocabulary (ids are dense in practice, but a gap is
possible) is written as a zero-length entry.

Usage::

    python export_tokenizer.py --tokenizer-dir /path/to/t5gemma-b-b-ul2 \\
        --out /path/to/t5gemma-tokenizer.bin
"""

import argparse
import json
import os
import struct

MAGIC = b"ARTK"
VERSION = 1


def read_tokenizer(tokenizer_dir):
    """Load ``tokenizer.json`` and return its vocabulary, merges and special ids.

    The special ids are read from ``tokenizer_config.json`` / ``config.json``
    when present, since ``tokenizer.json`` itself does not name them.
    """
    with open(os.path.join(tokenizer_dir, "tokenizer.json")) as handle:
        spec = json.load(handle)

    model = spec.get("model") or {}
    if model.get("type") != "BPE":
        raise ValueError(
            "only a BPE tokenizer can be exported; this one is %r" % model.get("type"))

    vocab = model.get("vocab") or {}
    merges = model.get("merges") or []

    specials = {}
    config_path = os.path.join(tokenizer_dir, "tokenizer_config.json")
    if os.path.exists(config_path):
        with open(config_path) as handle:
            config = json.load(handle)
        for key, name in (("bos", "bos_token"), ("eos", "eos_token"),
                          ("pad", "pad_token"), ("unk", "unk_token")):
            token = config.get(name)
            if isinstance(token, dict):
                token = token.get("content")
            specials[key] = vocab.get(token, -1) if token is not None else -1
    else:
        for key in ("bos", "eos", "pad", "unk"):
            specials[key] = -1

    return vocab, merges, specials


def write_tokenizer(path, vocab, merges, specials):
    """Write the binary described in the module docstring, returning its size."""
    size = max(vocab.values()) + 1 if vocab else 0
    tokens = [None] * size
    for token, index in vocab.items():
        tokens[index] = token

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

    return os.path.getsize(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tokenizer-dir", required=True,
                        help="Directory holding tokenizer.json (and tokenizer_config.json)")
    parser.add_argument("--out", required=True, help="Path of the binary to write")
    args = parser.parse_args()

    vocab, merges, specials = read_tokenizer(args.tokenizer_dir)
    size = write_tokenizer(args.out, vocab, merges, specials)

    print("wrote %s" % args.out)
    print("  vocabulary %d tokens, %d merges, %.1f MB" % (
        len(vocab), len(merges), size / (1024.0 * 1024.0)))
    print("  bos=%(bos)d eos=%(eos)d pad=%(pad)d unk=%(unk)d" % specials)


if __name__ == "__main__":
    main()
