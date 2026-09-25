#!/usr/bin/env python3
"""Unit tests for the T5Gemma encoder key remap in ``extract_t5gemma_weights.py``."""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_t5gemma_weights as t5


def _layer(prefix, hidden=4, inner=6):
    z = lambda *s: np.zeros(s, dtype=np.float32)
    return {
        prefix + "self_attn.q_proj.weight": z(hidden, hidden),
        prefix + "self_attn.k_proj.weight": z(hidden, hidden),
        prefix + "self_attn.v_proj.weight": z(hidden, hidden),
        prefix + "self_attn.o_proj.weight": z(hidden, hidden),
        prefix + "mlp.up_proj.weight": z(inner, hidden),
        prefix + "mlp.gate_proj.weight": z(inner, hidden),
        prefix + "mlp.down_proj.weight": z(hidden, inner),
        prefix + "pre_self_attn_layernorm.weight": z(hidden),
        prefix + "post_self_attn_layernorm.weight": z(hidden),
        prefix + "pre_feedforward_layernorm.weight": z(hidden),
        prefix + "post_feedforward_layernorm.weight": z(hidden),
    }


def _released_state():
    """A released-layout checkpoint: ``model.encoder.*`` plus a decoder to drop."""
    z = lambda *s: np.zeros(s, dtype=np.float32)
    state = {
        "model.encoder.embed_tokens.weight": z(10, 4),
        "model.encoder.norm.weight": z(4),
        "model.decoder.embed_tokens.weight": z(10, 4),
        "model.decoder.norm.weight": z(4),
    }
    state.update(_layer("model.encoder.layers.0."))
    state.update(_layer("model.decoder.layers.0."))
    return state


def test_released_layout_keeps_only_encoder_under_bare_prefix():
    out = t5.ensure_encoder_prefix(_released_state())
    assert all(k.startswith("encoder.") for k in out)
    assert "encoder.embed_tokens.weight" in out
    assert "encoder.layers.0.self_attn.q_proj.weight" in out
    assert not any("decoder" in k for k in out)


def test_bare_encoder_layout_drops_decoder():
    state = {"encoder.norm.weight": np.zeros(4, np.float32),
             "decoder.norm.weight": np.zeros(4, np.float32)}
    assert set(t5.ensure_encoder_prefix(state)) == {"encoder.norm.weight"}


def test_unprefixed_export_is_prefixed():
    state = {"norm.weight": np.zeros(4, np.float32)}
    assert set(t5.ensure_encoder_prefix(state)) == {"encoder.norm.weight"}


def test_released_layout_fuses_every_layer():
    """The fusion rules see ``encoder.layers.N`` keys once the released prefix is stripped."""
    out = t5.ensure_encoder_prefix(_released_state())
    for rule in t5.t5gemma_encoder_rules(head_dim=2, theta=10000.0)[1:]:
        out = rule(out)
    assert out["encoder.layers.0.self_attn.to_qkv.weight"].shape == (12, 4)
    assert out["encoder.layers.0.mlp.proj.weight"].shape == (12, 4)
    assert "encoder.layers.0.self_attn.q_proj.weight" not in out
    assert "encoder.rotary.inv_freq" in out
    # The Gemma norm offset is folded into every norm scale.
    assert float(out["encoder.norm.weight"][0]) == 1.0


if __name__ == "__main__":
    import pytest
    sys.exit(pytest.main([__file__, "-v"]))
