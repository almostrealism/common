#!/usr/bin/env python3
"""Unit tests for the CLAP2 remap in ``extract_clap2_weights.py`` (synthetic; no torch, no weights)."""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_clap2_weights as clap2

WIDTH = 8
HEADS = 2
HEAD_DIM = WIDTH // HEADS
INNER = 3


def _ramp(*shape):
    """A tensor whose every element is distinct (its flat index), so a permutation is visible."""
    return np.arange(int(np.prod(shape)), dtype=np.float32).reshape(shape)


def _released_state():
    """An OpenCLIP CLAP2 state with one block per tower."""
    vit = clap2.AUDIO_VIT
    return {
        vit + "embeds.reg_token": _ramp(1, 1, WIDTH) + 1000,
        vit + "embeds.proj.weight": _ramp(WIDTH, 6),
        vit + "norm_pre.weight": _ramp(WIDTH),
        vit + "blocks.0.gamma_1": _ramp(WIDTH),
        vit + "blocks.0.gamma_2": _ramp(WIDTH),
        vit + "blocks.0.norm1.weight": _ramp(WIDTH),
        vit + "blocks.0.attn.qkv.weight": _ramp(3 * WIDTH, WIDTH),
        vit + "blocks.0.attn.qkv.bias": _ramp(3 * WIDTH),
        vit + "blocks.0.attn.proj.weight": _ramp(WIDTH, WIDTH),
        vit + "blocks.0.mlp.fc1.weight": _ramp(2 * INNER, WIDTH),
        vit + "blocks.0.mlp.fc1.bias": _ramp(2 * INNER),
        vit + "norm.weight": _ramp(WIDTH),
        vit + "attn_pool.latent": _ramp(1, 1, WIDTH),
        vit + "attn_pool.q.weight": _ramp(WIDTH, WIDTH),
        "audio.proj.0.weight": _ramp(WIDTH, WIDTH),
        "audio.proj.2.weight": _ramp(WIDTH, WIDTH),
        "text.token_embedding.weight": _ramp(10, WIDTH),
        "text.blocks.0.attn.qkv.weight": _ramp(3 * WIDTH, WIDTH),
        "text.blocks.0.attn.q_norm.weight": _ramp(HEAD_DIM),
        "text.blocks.0.attn.k_norm.weight": _ramp(HEAD_DIM),
        "text.pool.query": _ramp(1, 1, WIDTH),
        "text.pool.q_norm.weight": _ramp(HEAD_DIM),
        "text.text_projection.weight": _ramp(WIDTH, WIDTH),
        "logit_scale": np.asarray(4.6, dtype=np.float32),
    }


def test_interleaved_to_split_half_moves_even_features_first():
    assert clap2.interleaved_to_split_half(6).tolist() == [0, 2, 4, 1, 3, 5]


def test_query_and_key_rows_are_permuted_per_head_and_values_are_not():
    qkv = _ramp(3 * WIDTH)
    permuted = clap2.permute_query_key_rows(qkv, WIDTH, HEADS)
    order = [0, 2, 1, 3]
    expected = []
    for section in (0, 1):
        for head in range(HEADS):
            start = section * WIDTH + head * HEAD_DIM
            expected.extend(start + i for i in order)
    expected.extend(range(2 * WIDTH, 3 * WIDTH))
    assert permuted.tolist() == expected


def test_native_state_renames_and_rearranges():
    state = _released_state()
    native = clap2.native_state(state, HEADS)

    patch = native["audio.patch_embed.weight"]
    assert patch.shape == (WIDTH, 7)
    np.testing.assert_array_equal(patch[:, :6], state[clap2.AUDIO_VIT + "embeds.proj.weight"])
    np.testing.assert_array_equal(patch[:, 6], state[clap2.AUDIO_VIT + "embeds.reg_token"].reshape(-1))

    fc1 = native["audio.blocks.0.mlp.fc1.weight"]
    np.testing.assert_array_equal(fc1[:INNER], state[clap2.AUDIO_VIT + "blocks.0.mlp.fc1.weight"][INNER:])
    assert native["audio.blocks.0.mlp.fc1.bias"].tolist() == [3, 4, 5, 0, 1, 2]

    bias = native["audio.blocks.0.attn.qkv.bias"]
    assert bias[:HEAD_DIM].tolist() == [0, 2, 1, 3]
    assert native["audio.blocks.0.gamma_1"].shape == (1, WIDTH)

    assert native["text.blocks.0.attn.q_norm.weight"].tolist() == [0, 2, 1, 3]
    assert native["text.pool.q_norm.weight"].tolist() == [0, 1, 2, 3]
    assert native["text.pool.latent"].shape == (1, WIDTH)
    assert native["audio.pool.latent"].shape == (1, WIDTH)
    assert native["logit_scale"].shape == (1,)
    assert native["config.audio.heads"].shape == (HEADS,)

    for key in ["audio.norm_pre.weight", "audio.norm.weight", "audio.blocks.0.norm1.weight",
                "audio.pool.q.weight", "audio.head.hidden.weight", "audio.head.output.weight",
                "text.projection.weight"]:
        assert key in native, key
    assert not any(key.startswith(clap2.AUDIO_VIT) for key in native)
