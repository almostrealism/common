#!/usr/bin/env python3
"""
Unit tests for ``export_tokenizer.py``.

These tests use only synthetic ``tokenizer.json`` documents -- no released vocabulary and no
``tokenizers`` package -- and cover the pipeline validation that guards against silently exporting
a tokenizer the Java ``SentencePieceBPETokenizer`` does not reproduce, plus the round trip through
``read_tokenizer`` and ``write_tokenizer``. Run with::

    cd engine/ml/scripts && python -m pytest test_export_tokenizer.py -v
"""

import json
import os
import struct
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import export_tokenizer as exporter


BOUNDARY = "▁"


def _supported_spec(vocab=None, merges=None):
    """A ``tokenizer.json`` document matching the pipeline the Java reader implements."""
    return {
        "model": {
            "type": "BPE",
            "byte_fallback": True,
            "vocab": vocab if vocab is not None else {"a": 0, "b": 1, "ab": 2, BOUNDARY: 3},
            "merges": merges if merges is not None else ["a b"],
        },
        "normalizer": {"type": "Replace", "pattern": {"String": " "}, "content": BOUNDARY},
        "pre_tokenizer": {
            "type": "Split",
            "pattern": {"String": " "},
            "behavior": "MergedWithPrevious",
        },
        "decoder": {
            "type": "Sequence",
            "decoders": [
                {"type": "Replace", "pattern": {"String": BOUNDARY}, "content": " "},
                {"type": "ByteFallback"},
                {"type": "Fuse"},
            ],
        },
    }


# ---------------------------------------------------------------------------
# validate_pipeline
# ---------------------------------------------------------------------------

def test_supported_pipeline_is_accepted():
    # A SentencePiece-style pipeline like T5Gemma's must validate without raising.
    exporter.validate_pipeline(_supported_spec())


def test_sequence_normalizer_with_only_boundary_replace_is_accepted():
    spec = _supported_spec()
    spec["normalizer"] = {
        "type": "Sequence",
        "normalizers": [{"type": "Replace", "pattern": {"String": " "}, "content": BOUNDARY}],
    }
    exporter.validate_pipeline(spec)


def test_non_bpe_is_rejected():
    spec = _supported_spec()
    spec["model"]["type"] = "Unigram"
    with pytest.raises(ValueError, match="only a BPE tokenizer"):
        exporter.validate_pipeline(spec)


def test_missing_byte_fallback_is_rejected():
    spec = _supported_spec()
    spec["model"]["byte_fallback"] = False
    with pytest.raises(ValueError, match="byte-fallback"):
        exporter.validate_pipeline(spec)


def test_byte_level_pre_tokenizer_is_rejected():
    # A byte-level BPE (GPT-2/Qwen family) remaps bytes to printable characters; the Java reader
    # does not, so accepting it would silently produce different ids.
    spec = _supported_spec()
    spec["pre_tokenizer"] = {"type": "ByteLevel", "add_prefix_space": False}
    with pytest.raises(ValueError, match="pre-tokenizer"):
        exporter.validate_pipeline(spec)


def test_prepend_normalizer_is_rejected():
    # A Prepend normalizer (Llama) adds a leading boundary the whole-text Java reader never adds.
    spec = _supported_spec()
    spec["normalizer"] = {
        "type": "Sequence",
        "normalizers": [
            {"type": "Prepend", "prepend": BOUNDARY},
            {"type": "Replace", "pattern": {"String": " "}, "content": BOUNDARY},
        ],
    }
    with pytest.raises(ValueError, match="normalizer"):
        exporter.validate_pipeline(spec)


def test_missing_boundary_replace_is_rejected():
    spec = _supported_spec()
    spec["normalizer"] = None
    with pytest.raises(ValueError, match="boundary marker"):
        exporter.validate_pipeline(spec)


def test_byte_level_decoder_is_rejected():
    spec = _supported_spec()
    spec["decoder"] = {"type": "ByteLevel"}
    with pytest.raises(ValueError, match="decoder"):
        exporter.validate_pipeline(spec)


# ---------------------------------------------------------------------------
# read_tokenizer / write_tokenizer round trip
# ---------------------------------------------------------------------------

def _read_string(handle):
    (length,) = struct.unpack(">i", handle.read(4))
    return handle.read(length).decode("utf-8")


def test_read_and_write_round_trip(tmp_path):
    vocab = {"<pad>": 0, "<eos>": 1, "<unk>": 2, "a": 3, "b": 4, "ab": 5, BOUNDARY: 6}
    merges = ["a b"]

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(
        json.dumps(_supported_spec(vocab=vocab, merges=merges)))
    (tokenizer_dir / "tokenizer_config.json").write_text(json.dumps({
        "eos_token": "<eos>",
        "pad_token": "<pad>",
        "unk_token": {"content": "<unk>"},
    }))

    read_vocab, read_merges, specials = exporter.read_tokenizer(str(tokenizer_dir))
    assert read_vocab == vocab
    assert read_merges == merges
    assert specials == {"bos": -1, "eos": 1, "pad": 0, "unk": 2}

    out = tmp_path / "tokenizer.bin"
    size = exporter.write_tokenizer(str(out), read_vocab, read_merges, specials)
    assert size == os.path.getsize(str(out))

    with open(str(out), "rb") as handle:
        assert handle.read(4) == exporter.MAGIC
        (version,) = struct.unpack(">i", handle.read(4))
        assert version == exporter.VERSION

        (vocab_size,) = struct.unpack(">i", handle.read(4))
        assert vocab_size == len(vocab)
        tokens = [_read_string(handle) for _ in range(vocab_size)]
        # Tokens are written in id order, so the boundary marker lands at its own id.
        assert tokens[6] == BOUNDARY
        assert tokens[5] == "ab"

        (merge_count,) = struct.unpack(">i", handle.read(4))
        assert merge_count == 1
        assert _read_string(handle) == "a"
        assert _read_string(handle) == "b"

        bos, eos, pad, unk = struct.unpack(">iiii", handle.read(16))
        assert (bos, eos, pad, unk) == (-1, 1, 0, 2)


def test_export_rejects_incompatible_tokenizer(tmp_path):
    # read_tokenizer must refuse an incompatible pipeline rather than write a misleading binary.
    spec = _supported_spec()
    spec["model"]["byte_fallback"] = False

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(spec))

    with pytest.raises(ValueError):
        exporter.read_tokenizer(str(tokenizer_dir))
