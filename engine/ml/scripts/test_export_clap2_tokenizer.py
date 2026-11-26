#!/usr/bin/env python3
"""Unit tests for ``export_clap2_tokenizer.py`` (synthetic rank files; no tiktoken, no checkpoint)."""

import base64
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_clap2_tokenizer as export


def _byte_ranks():
    """Every single byte at its own rank, as the first 256 ranks of r50k_base are."""
    return [(bytes([b]), b) for b in range(256)]


def _write(path, ranks):
    """Writes a rank file."""
    export.write_ranks(ranks, str(path))
    return str(path)


def test_round_trip_preserves_tokens_and_ranks(tmp_path):
    ranks = _byte_ranks() + [(b" drum", 256), (b"\xf0\x9f", 257)]
    path = _write(tmp_path / "r.tiktoken", ranks)
    assert sorted(export.read_ranks(path), key=lambda p: p[1]) == ranks
    assert export.validate_ranks(ranks) == 258


def test_validation_rejects_gaps_duplicates_missing_bytes_and_control_overlap():
    with pytest.raises(ValueError, match="not exactly"):
        export.validate_ranks(_byte_ranks() + [(b"ab", 300)])
    with pytest.raises(ValueError, match="has ranks"):
        export.validate_ranks(_byte_ranks() + [(b"a", 256)])
    with pytest.raises(ValueError, match="are not tokens"):
        export.validate_ranks([(b"ab", 0)] + _byte_ranks()[1:])
    big = _byte_ranks() + [(b"t%d" % i, 256 + i) for i in range(export.EOS_TOKEN - 255)]
    with pytest.raises(ValueError, match="reaches the control token"):
        export.validate_ranks(big)


def test_subset_keeps_bytes_and_tokens_inside_a_piece():
    ranks = _byte_ranks() + [(b" drum", 256), (b" dr", 257), (b"um", 258), (b" piano", 259), (b"m k", 260)]
    kept = dict(export.subset_for(ranks, ["A", " drum", " kick"]))
    assert all(bytes([b]) in kept for b in range(256))
    assert kept[b" drum"] == 256 and kept[b" dr"] == 257 and kept[b"um"] == 258
    assert b" piano" not in kept
    assert b"m k" not in kept, "a token spanning two pieces can never be looked up"


def test_checkpoint_files_requires_the_r50k_tiktoken_tokenizer(tmp_path):
    cfg = {"model_cfg": {"text_cfg": {"tokenizer_type": "tiktoken", "tiktoken_name": "r50k_base",
                                      "tiktoken_bpe_path": "t/r50k_base.tiktoken",
                                      "tiktoken_config_path": "t/r50k_base.json"}}}
    (tmp_path / "open_clip_config.json").write_text(json.dumps(cfg))
    ranks, config = export.checkpoint_files(str(tmp_path))
    assert ranks == os.path.join(str(tmp_path), "t/r50k_base.tiktoken")
    assert config == os.path.join(str(tmp_path), "t/r50k_base.json")

    cfg["model_cfg"]["text_cfg"]["tiktoken_name"] = "cl100k_base"
    (tmp_path / "open_clip_config.json").write_text(json.dumps(cfg))
    with pytest.raises(ValueError, match="r50k_base"):
        export.checkpoint_files(str(tmp_path))


def test_export_copies_a_valid_rank_file(tmp_path):
    os.makedirs(tmp_path / "t")
    _write(tmp_path / "t" / "r50k_base.tiktoken", _byte_ranks())
    cfg = {"model_cfg": {"text_cfg": {"tokenizer_type": "tiktoken", "tiktoken_name": "r50k_base",
                                      "tiktoken_bpe_path": "t/r50k_base.tiktoken",
                                      "tiktoken_config_path": "t/r50k_base.json"}}}
    (tmp_path / "open_clip_config.json").write_text(json.dumps(cfg))
    out = tmp_path / "out" / "r50k_base.tiktoken"
    export.export(str(tmp_path), str(out))
    assert out.read_bytes() == (tmp_path / "t" / "r50k_base.tiktoken").read_bytes()
    first = out.read_bytes().splitlines()[0].split()
    assert base64.b64decode(first[0]) == b"\x00" and first[1] == b"0"
