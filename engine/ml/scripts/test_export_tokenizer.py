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


def test_missing_decoder_is_rejected():
    spec = _supported_spec()
    spec["decoder"] = None
    with pytest.raises(ValueError, match="decoder"):
        exporter.validate_pipeline(spec)


def test_other_replace_decoder_is_rejected():
    # The Java reader only turns the boundary marker into a space; any other Replace would diverge.
    spec = _supported_spec()
    spec["decoder"]["decoders"][0] = {"type": "Replace", "pattern": {"String": "x"}, "content": "y"}
    with pytest.raises(ValueError, match="decoder"):
        exporter.validate_pipeline(spec)


def test_strip_decoder_is_rejected():
    # A Strip (Llama) removes a leading space the Java reader keeps.
    spec = _supported_spec()
    spec["decoder"]["decoders"].append({"type": "Strip", "content": " ", "start": 1, "stop": 0})
    with pytest.raises(ValueError, match="decoder"):
        exporter.validate_pipeline(spec)


def test_reordered_decoder_is_rejected():
    spec = _supported_spec()
    decoders = spec["decoder"]["decoders"]
    decoders[0], decoders[1] = decoders[1], decoders[0]
    with pytest.raises(ValueError, match="decoder"):
        exporter.validate_pipeline(spec)


def test_leading_fuse_decoder_is_rejected():
    # Fuse ahead of the Replace and ByteFallback concatenates the tokens first, destroying the
    # per-token boundaries the Java reader replaces and folds; only a trailing Fuse is a no-op.
    spec = _supported_spec()
    spec["decoder"]["decoders"] = [
        {"type": "Fuse"},
        {"type": "Replace", "pattern": {"String": BOUNDARY}, "content": " "},
        {"type": "ByteFallback"},
    ]
    with pytest.raises(ValueError, match="decoder"):
        exporter.validate_pipeline(spec)


def test_decoder_without_trailing_fuse_is_accepted():
    # The trailing Fuse is optional; a Replace(boundary -> space), ByteFallback decoder is exactly
    # what the Java reader implements and must validate on its own.
    spec = _supported_spec()
    spec["decoder"]["decoders"] = [
        {"type": "Replace", "pattern": {"String": BOUNDARY}, "content": " "},
        {"type": "ByteFallback"},
    ]
    exporter.validate_pipeline(spec)


def test_sequence_only_post_processor_is_accepted():
    spec = _supported_spec()
    spec["post_processor"] = {
        "type": "TemplateProcessing",
        "single": [{"Sequence": {"id": "A", "type_id": 0}}],
        "pair": [{"Sequence": {"id": "A", "type_id": 0}}, {"Sequence": {"id": "B", "type_id": 1}}],
        "special_tokens": {},
    }
    exporter.validate_pipeline(spec)


def test_repeated_sequence_post_processor_is_rejected():
    # A template that repeats the sequence ([$A, $A]) duplicates every input id; the Java reader
    # emits each id once, so it must be rejected even though every piece is a Sequence.
    spec = _supported_spec()
    spec["post_processor"] = {
        "type": "TemplateProcessing",
        "single": [{"Sequence": {"id": "A", "type_id": 0}},
                   {"Sequence": {"id": "A", "type_id": 0}}],
        "special_tokens": {},
    }
    with pytest.raises(ValueError, match="post-processor"):
        exporter.validate_pipeline(spec)


def test_non_a_sequence_post_processor_is_rejected():
    # The single template must carry the input ids (id "A"); any other id is not the no-op the
    # Java reader reproduces.
    spec = _supported_spec()
    spec["post_processor"] = {
        "type": "TemplateProcessing",
        "single": [{"Sequence": {"id": "B", "type_id": 0}}],
        "special_tokens": {},
    }
    with pytest.raises(ValueError, match="post-processor"):
        exporter.validate_pipeline(spec)


def test_special_token_post_processor_is_rejected():
    # The Java reader adds no BOS/EOS, so a template that adds one would change the ids.
    spec = _supported_spec()
    spec["post_processor"] = {
        "type": "TemplateProcessing",
        "single": [{"SpecialToken": {"id": "<bos>", "type_id": 0}},
                   {"Sequence": {"id": "A", "type_id": 0}}],
        "special_tokens": {"<bos>": {"id": "<bos>", "ids": [2], "tokens": ["<bos>"]}},
    }
    with pytest.raises(ValueError, match="post-processor"):
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
