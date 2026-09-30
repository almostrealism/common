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


def _byte_tokens(start):
    """The 256 ``<0xNN>`` atoms a byte-fallback vocabulary must contain, mapped to consecutive ids
    from ``start``. A byte-fallback tokenizer is only exportable with the complete set present."""
    return {"<0x%02X>" % value: start + value for value in range(256)}


def _supported_spec(vocab=None, merges=None):
    """A ``tokenizer.json`` document matching the pipeline the Java reader implements."""
    if vocab is None:
        vocab = {"a": 0, "b": 1, "ab": 2, BOUNDARY: 3}
        vocab.update(_byte_tokens(4))
    return {
        "model": {
            "type": "BPE",
            "byte_fallback": True,
            "vocab": vocab,
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


def test_incomplete_byte_fallback_vocabulary_is_rejected():
    # HuggingFace enables byte fallback only when all 256 <0xNN> atoms are present; with one missing
    # the source falls back to an unknown token neither the binary nor the Java reader reproduces, so
    # the export must be rejected rather than silently diverging from the source tokenizer.
    spec = _supported_spec()
    del spec["model"]["vocab"]["<0x41>"]
    with pytest.raises(ValueError, match="incomplete byte-fallback"):
        exporter.validate_pipeline(spec)


def test_complete_byte_fallback_vocabulary_is_accepted():
    # A vocabulary carrying every one of the 256 byte atoms validates; the assertion pins that the
    # accepted default fixture is in fact complete, so this is the true complement of the rejection.
    spec = _supported_spec()
    assert all(("<0x%02X>" % value) in spec["model"]["vocab"] for value in range(256))
    exporter.validate_pipeline(spec)


@pytest.mark.parametrize("option, value", [
    ("dropout", 0.1),
    ("ignore_merges", True),
    ("fuse_unk", True),
    ("continuing_subword_prefix", "##"),
    ("end_of_word_suffix", "</w>"),
])
def test_non_default_bpe_option_is_rejected(option, value):
    # The Java reader applies every merge deterministically to undecorated symbols, so a model
    # option that makes the source skip, bypass or decorate merges must not export.
    spec = _supported_spec()
    spec["model"][option] = value
    with pytest.raises(ValueError, match=option):
        exporter.validate_pipeline(spec)


@pytest.mark.parametrize("option, value", [
    ("dropout", None),
    ("dropout", 0.0),
    ("ignore_merges", False),
    ("fuse_unk", None),
    ("fuse_unk", False),
    ("continuing_subword_prefix", None),
    ("continuing_subword_prefix", ""),
    ("end_of_word_suffix", None),
    ("end_of_word_suffix", ""),
])
def test_default_bpe_option_is_accepted(option, value):
    # tokenizer.json serializes the defaults explicitly as null / 0 / false / ""; those must pass.
    spec = _supported_spec()
    spec["model"][option] = value
    exporter.validate_pipeline(spec)


def test_inverted_split_pre_tokenizer_is_rejected():
    # An inverted Split on a space is not a no-op once no space remains: the whole text becomes the
    # match and the behavior (e.g. Removed) applies to it.
    spec = _supported_spec()
    spec["pre_tokenizer"]["invert"] = True
    spec["pre_tokenizer"]["behavior"] = "Removed"
    with pytest.raises(ValueError, match="pre-tokenizer"):
        exporter.validate_pipeline(spec)


def test_non_inverted_split_pre_tokenizer_is_accepted():
    spec = _supported_spec()
    spec["pre_tokenizer"]["invert"] = False
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


def test_non_special_added_token_is_accepted():
    # A non-special added token is matched atomically before the BPE model, and the Java reader
    # reproduces that matching from the added tokens carried in the binary.
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": 2, "content": "ab", "special": False}]
    exporter.validate_pipeline(spec)


def test_special_added_tokens_are_accepted():
    # Released tokenizers register their control tokens (BOS/EOS/PAD/UNK) in added_tokens with
    # special=true; they are matched like any other added token and still export.
    spec = _supported_spec()
    spec["added_tokens"] = [
        {"id": 0, "content": "<pad>", "special": True},
        {"id": 1, "content": "<eos>", "special": True},
    ]
    exporter.validate_pipeline(spec)


@pytest.mark.parametrize("option", ["single_word", "lstrip", "rstrip"])
def test_added_token_matching_option_is_rejected(option):
    # The Java reader matches added tokens exactly, so an option that widens or narrows the match
    # would make it disagree with the source.
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": 0, "content": "<pad>", "special": True, option: True}]
    with pytest.raises(ValueError, match=option):
        exporter.validate_pipeline(spec)


@pytest.mark.parametrize("token_id", [-1, None, "2", True])
def test_added_token_with_invalid_id_is_rejected(token_id):
    # write_tokenizer indexes the token table by id, so -1 would overwrite the last slot through
    # Python's negative indexing instead of failing; any non-integer id is equally unwritable.
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": token_id, "content": "<pad>", "special": True}]
    with pytest.raises(ValueError, match="invalid id"):
        exporter.validate_pipeline(spec)


def test_vocabulary_token_with_negative_id_is_rejected():
    spec = _supported_spec()
    spec["model"]["vocab"]["ab"] = -1
    with pytest.raises(ValueError, match="invalid id"):
        exporter.validate_pipeline(spec)


def test_vocabulary_token_id_at_or_above_reader_limit_is_rejected():
    # write_tokenizer sizes the token table as max(id) + 1, so a single sparse id at the reader's
    # MAX_ENTRIES bound would allocate a MAX_ENTRIES + 1 table and emit a binary the Java reader,
    # which caps the vocabulary at MAX_ENTRIES, then refuses. Reject it before allocating.
    spec = _supported_spec()
    spec["model"]["vocab"]["ab"] = exporter.MAX_ENTRIES
    with pytest.raises(ValueError, match="limit the Java reader accepts"):
        exporter.validate_pipeline(spec)


def test_added_token_id_at_or_above_reader_limit_is_rejected():
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": exporter.MAX_ENTRIES, "content": "<pad>", "special": True}]
    with pytest.raises(ValueError, match="limit the Java reader accepts"):
        exporter.validate_pipeline(spec)


def test_vocabulary_token_id_just_below_reader_limit_is_accepted():
    # The largest usable id is MAX_ENTRIES - 1: it sizes the table at exactly MAX_ENTRIES, which the
    # reader accepts. This is the boundary the rejection above sits one past.
    spec = _supported_spec()
    spec["model"]["vocab"]["ab"] = exporter.MAX_ENTRIES - 1
    exporter.validate_pipeline(spec)


def test_empty_added_token_is_rejected():
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": 0, "content": "", "special": True}]
    with pytest.raises(ValueError, match="empty content"):
        exporter.validate_pipeline(spec)


@pytest.mark.parametrize("content", ["a b", BOUNDARY + "ab"])
def test_normalized_added_token_changed_by_normalization_is_rejected(content):
    # A normalized added token is matched against normalized text; the Java reader matches it
    # before normalization, which differs when the content holds a space or the boundary marker.
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": 2, "content": content, "special": False, "normalized": True}]
    with pytest.raises(ValueError, match="normalized added token"):
        exporter.validate_pipeline(spec)


def test_normalized_added_token_unchanged_by_normalization_is_accepted():
    spec = _supported_spec()
    spec["added_tokens"] = [{"id": 2, "content": "<ab>", "special": False, "normalized": True}]
    exporter.validate_pipeline(spec)


# ---------------------------------------------------------------------------
# read_tokenizer / write_tokenizer round trip
# ---------------------------------------------------------------------------

def _read_string(handle):
    (length,) = struct.unpack(">i", handle.read(4))
    return handle.read(length).decode("utf-8")


def test_read_and_write_round_trip(tmp_path):
    vocab = {"<pad>": 0, "<eos>": 1, "<unk>": 2, "a": 3, "b": 4, "ab": 5, BOUNDARY: 6}
    vocab.update(_byte_tokens(7))
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

    read_vocab, read_merges, specials, added = exporter.read_tokenizer(str(tokenizer_dir))
    assert read_vocab == vocab
    assert read_merges == merges
    assert specials == {"bos": -1, "eos": 1, "pad": 0, "unk": 2}
    assert added == []

    out = tmp_path / "tokenizer.bin"
    size = exporter.write_tokenizer(str(out), read_vocab, read_merges, specials, added)
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

        (added_count,) = struct.unpack(">i", handle.read(4))
        assert added_count == 0
        assert handle.read() == b""


def test_added_tokens_round_trip(tmp_path):
    # Added tokens are written after the special ids, and one whose id lies beyond the model
    # vocabulary extends the token table so the id resolves to its content. A special token that
    # lives only in the added vocabulary still resolves from tokenizer_config.json.
    vocab = {"<pad>": 0, "a": 1, "b": 2, "ab": 3, BOUNDARY: 4}
    vocab.update(_byte_tokens(5))
    spec = _supported_spec(vocab=vocab)
    spec["added_tokens"] = [
        {"id": 0, "content": "<pad>", "special": True, "normalized": False},
        {"id": 261, "content": "<start_of_turn>", "special": True, "normalized": False},
        {"id": 262, "content": "<ab>", "special": False, "normalized": True},
    ]

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(spec))
    (tokenizer_dir / "tokenizer_config.json").write_text(json.dumps({
        "pad_token": "<pad>",
        "bos_token": "<start_of_turn>",
    }))

    read_vocab, read_merges, specials, added = exporter.read_tokenizer(str(tokenizer_dir))
    assert added == [(0, "<pad>", False, True), (261, "<start_of_turn>", False, True),
                     (262, "<ab>", True, False)]
    assert specials == {"bos": 261, "eos": -1, "pad": 0, "unk": -1}

    out = tmp_path / "tokenizer.bin"
    exporter.write_tokenizer(str(out), read_vocab, read_merges, specials, added)

    with open(str(out), "rb") as handle:
        handle.read(8)
        (vocab_size,) = struct.unpack(">i", handle.read(4))
        assert vocab_size == 263
        tokens = [_read_string(handle) for _ in range(vocab_size)]
        assert tokens[0] == "<pad>"
        assert tokens[261] == "<start_of_turn>"
        assert tokens[262] == "<ab>"

        (merge_count,) = struct.unpack(">i", handle.read(4))
        for _ in range(2 * merge_count):
            _read_string(handle)
        handle.read(16)

        (added_count,) = struct.unpack(">i", handle.read(4))
        assert added_count == 3
        entries = []
        for _ in range(added_count):
            index, normalized, special = struct.unpack(">ibb", handle.read(6))
            entries.append((index, _read_string(handle), bool(normalized), bool(special)))
        assert entries == added
        assert handle.read() == b""


def test_conflicting_added_token_content_is_rejected(tmp_path):
    # A vocabulary entry and an added token that name the same id with different content cannot both
    # be written -- the binary holds one string per id -- so the conflict is rejected before writing
    # rather than resolved by which loop runs last.
    vocab = {"<pad>": 0, "a": 1}
    added = [(0, "<PAD>", False, True)]
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="conflicting content at token id 0"):
        exporter.write_tokenizer(str(out), vocab, [], {}, added)


def test_matching_added_token_content_is_accepted(tmp_path):
    # The ordinary case: an added token repeats the vocabulary string already at its id. The
    # idempotent collision must be accepted and write that single string at the shared id.
    vocab = {"<pad>": 0, "a": 1}
    added = [(0, "<pad>", False, True)]
    out = tmp_path / "tokenizer.bin"
    exporter.write_tokenizer(str(out), vocab, [], {}, added)

    with open(str(out), "rb") as handle:
        handle.read(8)
        (vocab_size,) = struct.unpack(">i", handle.read(4))
        assert vocab_size == 2
        tokens = [_read_string(handle) for _ in range(vocab_size)]
        assert tokens == ["<pad>", "a"]


def test_conflicting_vocabulary_ids_are_rejected(tmp_path):
    # Two vocabulary entries mapping to the same id carry different content (dict keys are unique),
    # so they always conflict; the exported table can keep only one and the export is rejected.
    vocab = {"a": 0, "b": 0, "c": 1}
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="conflicting content at token id 0"):
        exporter.write_tokenizer(str(out), vocab, [], {}, [])


@pytest.mark.parametrize("token_id", [-1, -3, None, "0", True])
def test_write_tokenizer_invalid_vocabulary_id_is_rejected(tmp_path, token_id):
    # write_tokenizer is a public entry point its own callers may reach without read_tokenizer, so it
    # re-validates every id. A negative id would otherwise wrap through Python's negative indexing and
    # place the token at the wrong slot; a non-integer id is unwritable.
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="invalid id"):
        exporter.write_tokenizer(str(out), {"a": token_id}, [], {}, [])
    assert not out.exists()


@pytest.mark.parametrize("token_id", [-1, -3, None, "0", True])
def test_write_tokenizer_invalid_added_id_is_rejected(tmp_path, token_id):
    # The same guard applies to added-token ids, which index the token table just as vocabulary ids do.
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="invalid id"):
        exporter.write_tokenizer(str(out), {"a": 0}, [], {}, [(token_id, "<pad>", False, True)])
    assert not out.exists()


def test_write_tokenizer_special_id_out_of_range_is_rejected(tmp_path):
    # A special id beyond the vocabulary is what readControlId rejects on load; catch it before writing.
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="bos token id 5 names no exported token"):
        exporter.write_tokenizer(str(out), {"a": 0}, [], {"bos": 5}, [])
    assert not out.exists()


def test_write_tokenizer_negative_special_id_is_rejected(tmp_path):
    # -1 marks an absent control token; any other negative value is an invalid id, not "none".
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="invalid id"):
        exporter.write_tokenizer(str(out), {"a": 0}, [], {"eos": -2}, [])
    assert not out.exists()


def test_write_tokenizer_special_id_naming_empty_slot_is_rejected(tmp_path):
    # A sparse vocabulary leaves a zero-length gap slot; a control id pointing at it would decode to an
    # empty token, so it is rejected even though it is within range.
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="pad token id 1 names no exported token"):
        exporter.write_tokenizer(str(out), {"a": 0, "b": 2}, [], {"pad": 1}, [])
    assert not out.exists()


def test_write_tokenizer_valid_special_ids_are_written(tmp_path):
    # The boundary complement: -1 (absent) and an in-range id naming a populated slot both write, and
    # the ids land verbatim in the special-id block.
    out = tmp_path / "tokenizer.bin"
    exporter.write_tokenizer(str(out), {"<pad>": 0, "a": 1}, [], {"pad": 0}, [])

    with open(str(out), "rb") as handle:
        handle.read(8)
        (vocab_size,) = struct.unpack(">i", handle.read(4))
        for _ in range(vocab_size):
            _read_string(handle)
        (merge_count,) = struct.unpack(">i", handle.read(4))
        for _ in range(2 * merge_count):
            _read_string(handle)
        bos, eos, pad, unk = struct.unpack(">iiii", handle.read(16))
        assert (bos, eos, pad, unk) == (-1, -1, 0, -1)


def test_oversized_token_content_is_rejected(tmp_path):
    # The Java reader rejects any serialized string above MAX_STRING_BYTES before allocating, so a
    # vocabulary token whose UTF-8 encoding is longer would export and then fail to load; reject it.
    vocab = {"a" * (exporter.MAX_STRING_BYTES + 1): 0}
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="per-string byte limit"):
        exporter.write_tokenizer(str(out), vocab, [], {}, [])
    assert not out.exists()


def test_token_content_at_string_limit_is_accepted(tmp_path):
    # The boundary complement: a token of exactly MAX_STRING_BYTES bytes is the largest the reader
    # accepts, so it must export. This pins the rejection above at one byte past the limit.
    vocab = {"a" * exporter.MAX_STRING_BYTES: 0}
    out = tmp_path / "tokenizer.bin"
    exporter.write_tokenizer(str(out), vocab, [], {}, [])
    assert out.exists()


def test_oversized_merge_element_is_rejected(tmp_path):
    # A merge element is a serialized string too, so one longer than MAX_STRING_BYTES is rejected
    # before writing, matching the reader that would refuse the resulting binary.
    vocab = {"a": 0, "b": 1}
    merges = ["a " + "b" * (exporter.MAX_STRING_BYTES + 1)]
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="per-string byte limit"):
        exporter.write_tokenizer(str(out), vocab, merges, {}, [])
    assert not out.exists()


def test_merge_result_missing_from_vocabulary_is_rejected(tmp_path):
    # A merge whose concatenated result is not a vocabulary token would make the Java reader emit the
    # unknown token (-1 when there is none) for that symbol instead of a valid id, so the reader
    # rejects such a binary; catch it here before writing rather than exporting an unloadable file.
    vocab = {"a": 0, "b": 1}
    merges = ["a b"]
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="produces 'ab', which is not in the vocabulary"):
        exporter.write_tokenizer(str(out), vocab, merges, {}, [])
    assert not out.exists()


def test_merge_result_present_in_vocabulary_is_accepted(tmp_path):
    # The boundary complement: the same merge exports once its result is a vocabulary token, and the
    # merge elements land verbatim in the merge block.
    vocab = {"a": 0, "b": 1, "ab": 2}
    merges = ["a b"]
    out = tmp_path / "tokenizer.bin"
    exporter.write_tokenizer(str(out), vocab, merges, {}, [])

    with open(str(out), "rb") as handle:
        handle.read(8)
        (vocab_size,) = struct.unpack(">i", handle.read(4))
        for _ in range(vocab_size):
            _read_string(handle)
        (merge_count,) = struct.unpack(">i", handle.read(4))
        assert merge_count == 1
        assert _read_string(handle) == "a"
        assert _read_string(handle) == "b"


def test_merge_result_from_added_token_is_accepted(tmp_path):
    # A merge result placed in the token table only by an added token is still present, so the export
    # is accepted -- the check honors the whole id-indexed table the reader's vocabulary mirrors.
    vocab = {"a": 0, "b": 1}
    added = [(2, "ab", False, False)]
    merges = ["a b"]
    out = tmp_path / "tokenizer.bin"
    exporter.write_tokenizer(str(out), vocab, merges, {}, added)
    assert out.exists()


def test_vocabulary_size_above_reader_limit_is_rejected(tmp_path, monkeypatch):
    # write_tokenizer sizes the token table as max(id) + 1, so a vocabulary larger than the reader's
    # entry limit necessarily carries an id at or above it; the per-id bound rejects that id before the
    # oversized table is allocated. The limit is lowered here to avoid allocating a real one.
    monkeypatch.setattr(exporter, "MAX_ENTRIES", 2)
    vocab = {"a": 0, "b": 1, "c": 2}
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="limit the Java reader accepts"):
        exporter.write_tokenizer(str(out), vocab, [], {}, [])
    assert not out.exists()


def test_merge_count_above_reader_limit_is_rejected(tmp_path, monkeypatch):
    # readCount caps the merge count at MAX_ENTRIES; a longer list would export and then fail to load.
    monkeypatch.setattr(exporter, "MAX_ENTRIES", 2)
    vocab = {"a": 0, "b": 1}
    merges = ["a b", "b a", "a a"]
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="merge count"):
        exporter.write_tokenizer(str(out), vocab, merges, {}, [])
    assert not out.exists()


def test_added_token_count_above_reader_limit_is_rejected(tmp_path, monkeypatch):
    # readCount caps the added-token count at MAX_ENTRIES; a longer list would export and then fail
    # to load. All ids stay within the lowered table so the count is what trips the limit.
    monkeypatch.setattr(exporter, "MAX_ENTRIES", 2)
    vocab = {"<a>": 0, "<b>": 1}
    added = [(0, "<a>", False, True), (1, "<b>", False, True), (0, "<a>", False, True)]
    out = tmp_path / "tokenizer.bin"
    with pytest.raises(ValueError, match="added-token count"):
        exporter.write_tokenizer(str(out), vocab, [], {}, added)
    assert not out.exists()


def test_special_ids_fall_back_to_config_json(tmp_path):
    # A snapshot may carry the ids only as integer *_token_id fields in config.json, with no
    # tokenizer_config.json. Those must be read; otherwise every id exports as -1 and changes
    # encode(..., add_special=True) and special-token decoding.
    vocab = {"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3, "a": 4}
    vocab.update(_byte_tokens(5))

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(_supported_spec(vocab=vocab)))
    (tokenizer_dir / "config.json").write_text(json.dumps({
        "bos_token_id": 2,
        "eos_token_id": 1,
        "pad_token_id": 0,
        "unk_token_id": 3,
    }))

    _, _, specials, _ = exporter.read_tokenizer(str(tokenizer_dir))
    assert specials == {"bos": 2, "eos": 1, "pad": 0, "unk": 3}


def test_config_json_does_not_override_resolved_ids(tmp_path):
    # tokenizer_config.json is the primary source; config.json fills only the ids it left
    # unresolved, and never overrides one already resolved from the token strings.
    vocab = {"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3, "a": 4}
    vocab.update(_byte_tokens(5))

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(_supported_spec(vocab=vocab)))
    (tokenizer_dir / "tokenizer_config.json").write_text(json.dumps({
        "eos_token": "<eos>",
        "pad_token": "<pad>",
    }))
    (tokenizer_dir / "config.json").write_text(json.dumps({
        "bos_token_id": 2,
        "eos_token_id": 999,   # ignored: tokenizer_config.json already resolved eos
        "unk_token_id": 3,
    }))

    _, _, specials, _ = exporter.read_tokenizer(str(tokenizer_dir))
    assert specials == {"bos": 2, "eos": 1, "pad": 0, "unk": 3}


@pytest.mark.parametrize("token_id", [999, -2])
def test_config_json_id_outside_vocabulary_is_rejected(tmp_path, token_id):
    # An integer id copied from config.json that names no exported token must not be written.
    vocab = {"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3, "a": 4}
    vocab.update(_byte_tokens(5))

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(_supported_spec(vocab=vocab)))
    (tokenizer_dir / "config.json").write_text(json.dumps({"bos_token_id": token_id}))

    with pytest.raises(ValueError, match="bos token id %d" % token_id):
        exporter.read_tokenizer(str(tokenizer_dir))


@pytest.mark.parametrize("configured", ["<bos>", {"content": "<bos>"}])
def test_configured_special_token_missing_from_vocabulary_is_rejected(tmp_path, configured):
    # A token named by tokenizer_config.json that tokenizer.json does not contain must not export
    # as -1, which would mean "no such token" and silently drop the configured behavior -- even
    # when config.json names a valid integer id for the same key.
    vocab = {"<pad>": 0, "<eos>": 1, "a": 2}
    vocab.update(_byte_tokens(3))

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(_supported_spec(vocab=vocab)))
    (tokenizer_dir / "tokenizer_config.json").write_text(json.dumps({
        "bos_token": configured,
        "eos_token": "<eos>",
    }))
    (tokenizer_dir / "config.json").write_text(json.dumps({"bos_token_id": 0}))

    with pytest.raises(ValueError, match="bos token '<bos>' is not in the vocabulary"):
        exporter.read_tokenizer(str(tokenizer_dir))


def test_null_configured_special_token_exports_as_absent(tmp_path):
    # An explicit null in tokenizer_config.json declares the token absent, which is still -1.
    vocab = {"<pad>": 0, "<eos>": 1, "a": 2}
    vocab.update(_byte_tokens(3))

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(_supported_spec(vocab=vocab)))
    (tokenizer_dir / "tokenizer_config.json").write_text(json.dumps({
        "bos_token": None,
        "eos_token": "<eos>",
        "pad_token": "<pad>",
        "unk_token": None,
    }))

    _, _, specials, _ = exporter.read_tokenizer(str(tokenizer_dir))
    assert specials == {"bos": -1, "eos": 1, "pad": 0, "unk": -1}


def test_null_configured_special_token_is_not_restored_from_config_json(tmp_path):
    # An explicit null in tokenizer_config.json declares the token absent, so config.json must not
    # re-enable it even when it names an integer *_token_id for the same key. Only a key that
    # tokenizer_config.json omits entirely falls back to config.json.
    vocab = {"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3, "a": 4}
    vocab.update(_byte_tokens(5))

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(_supported_spec(vocab=vocab)))
    (tokenizer_dir / "tokenizer_config.json").write_text(json.dumps({
        "bos_token": None,     # declared absent: config.json must not restore it
        "eos_token": "<eos>",
        "pad_token": "<pad>",
        # unk_token omitted entirely: eligible for the config.json fallback
    }))
    (tokenizer_dir / "config.json").write_text(json.dumps({
        "bos_token_id": 2,     # ignored: tokenizer_config.json declared bos absent
        "unk_token_id": 3,     # applied: tokenizer_config.json omitted unk
    }))

    _, _, specials, _ = exporter.read_tokenizer(str(tokenizer_dir))
    assert specials == {"bos": -1, "eos": 1, "pad": 0, "unk": 3}


def test_export_rejects_incompatible_tokenizer(tmp_path):
    # read_tokenizer must refuse an incompatible pipeline rather than write a misleading binary.
    spec = _supported_spec()
    spec["model"]["byte_fallback"] = False

    tokenizer_dir = tmp_path / "tok"
    tokenizer_dir.mkdir()
    (tokenizer_dir / "tokenizer.json").write_text(json.dumps(spec))

    with pytest.raises(ValueError):
        exporter.read_tokenizer(str(tokenizer_dir))
