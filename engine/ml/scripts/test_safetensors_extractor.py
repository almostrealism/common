#!/usr/bin/env python3
"""
Unit tests for the generalized safetensors -> StateDictionary extractor core
(``safetensors_extractor.py``) and the SA3 config (``extract_sa3_weights.py``).

These tests use only synthetic data -- no SA3 weights, no torch -- and are the
firm deliverable for Block E. Run with::

    cd engine/ml/scripts && python -m pytest test_safetensors_extractor.py -v

``collections_pb2.py`` must exist first (generate via
``./engine/ml/scripts/generate_protobuf_python.sh``).
"""

import json
import os
import struct
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

try:
    import collections_pb2  # noqa: F401
except ImportError:
    pytest.skip(
        "collections_pb2.py not generated; run generate_protobuf_python.sh",
        allow_module_level=True)

import safetensors_extractor as core
import extract_sa3_weights as sa3


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _write_safetensors_fp32(path, tensors):
    """Write a synthetic fp32 .safetensors file from a name -> ndarray dict."""
    from safetensors.numpy import save_file
    save_file({k: np.ascontiguousarray(v.astype(np.float32)) for k, v in tensors.items()},
              path)


def _write_safetensors_bf16(path, tensors):
    """Hand-write a synthetic bf16 .safetensors file (numpy has no bf16 dtype).

    Each value is truncated from fp32 to bf16 (drop the low 16 mantissa bits),
    so values exactly representable in bf16 round-trip exactly.
    """
    header = {}
    blob = bytearray()
    offset = 0
    for name, array in tensors.items():
        f32 = np.ascontiguousarray(array.astype(np.float32))
        u32 = f32.view(np.uint32)
        bf = (u32 >> 16).astype("<u2")
        raw = bf.tobytes()
        header[name] = {"dtype": "BF16", "shape": list(f32.shape),
                        "data_offsets": [offset, offset + len(raw)]}
        blob += raw
        offset += len(raw)
    hj = json.dumps(header).encode("utf-8")
    with open(path, "wb") as f:
        f.write(struct.pack("<Q", len(hj)))
        f.write(hj)
        f.write(blob)


# ---------------------------------------------------------------------------
# (i) Round-trip: known tensor -> remap + write -> reload identical
# ---------------------------------------------------------------------------

def test_round_trip_values_and_shapes(tmp_path):
    rng = np.random.default_rng(0)
    src = {
        "block.w": rng.standard_normal((4, 3)).astype(np.float32),
        "block.b": rng.standard_normal((4,)).astype(np.float32),
        "scalar": np.array([2.5], dtype=np.float32),
    }
    st_path = str(tmp_path / "model.safetensors")
    _write_safetensors_fp32(st_path, src)

    loaded = core.load_safetensors(st_path)
    # Identity remap (keep everything) then write StateDictionary shards.
    mapped = core.remap(loaded, [core.rule()])
    out_dir = str(tmp_path / "weights")
    paths = core.write_state_dictionary(mapped, out_dir)
    assert paths, "expected at least one shard written"

    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded.keys()) == set(src.keys())
    for key, expected in src.items():
        assert reloaded[key].shape == expected.shape, key
        np.testing.assert_array_equal(reloaded[key], expected)


def test_round_trip_single_shard_file(tmp_path):
    src = {"a.weight": np.arange(6, dtype=np.float32).reshape(2, 3)}
    out_dir = str(tmp_path / "w")
    core.write_state_dictionary(src, out_dir, shard_prefix="dit")
    # The single shard is named exactly by the prefix (no numeric suffix).
    shard = os.path.join(out_dir, "dit")
    assert os.path.isfile(shard)
    reloaded = core.read_state_dictionary(shard)
    np.testing.assert_array_equal(reloaded["a.weight"], src["a.weight"])


def test_zero_sized_dimension_round_trips(tmp_path):
    # The SA3 SoftNorm bottleneck has noise_scaling_factor with shape [1, 0, 1].
    src = {"bottleneck.noise_scaling_factor": np.zeros((1, 0, 1), dtype=np.float32)}
    out_dir = str(tmp_path / "w")
    core.write_state_dictionary(src, out_dir)
    reloaded = core.read_state_dictionary(out_dir)
    assert reloaded["bottleneck.noise_scaling_factor"].shape == (1, 0, 1)


# ---------------------------------------------------------------------------
# (ii) Remap rules rename / strip prefixes as specified
# ---------------------------------------------------------------------------

def test_strip_prefix():
    state = {
        "pretransform.model.encoder.weight": np.zeros((2,), np.float32),
        "pretransform.model.decoder.bias": np.zeros((2,), np.float32),
        "other.key": np.zeros((1,), np.float32),
    }
    out = core.remap(state, [core.select_prefix("pretransform.model."),
                             core.strip_prefix("pretransform.model.")])
    assert set(out.keys()) == {"encoder.weight", "decoder.bias"}


def test_rename_prefix_and_select():
    state = {
        "model.model.transformer.layers.0.x": np.zeros((1,), np.float32),
        "pretransform.model.y": np.zeros((1,), np.float32),
    }
    out = core.remap(state, [core.select_prefix("model.model.")])
    assert set(out.keys()) == {"model.model.transformer.layers.0.x"}

    renamed = core.remap(state, [core.rename_prefix("model.model.", "dit.")])
    assert "dit.transformer.layers.0.x" in renamed
    assert "pretransform.model.y" in renamed  # non-matching passes through


def test_rename_regex():
    state = {"layers.7.weight": np.zeros((1,), np.float32)}
    out = core.remap(state, [core.rename_regex(r"layers\.(\d+)\.", r"block_\1.")])
    assert "block_7.weight" in out


def test_per_key_transform_applies_only_to_matches():
    state = {"a.weight": np.ones((2,), np.float32), "b.weight": np.ones((2,), np.float32)}
    out = core.remap(state, [core.rule(match="a.", transform=lambda x: x * 3.0)])
    np.testing.assert_array_equal(out["a.weight"], np.full((2,), 3.0, np.float32))
    np.testing.assert_array_equal(out["b.weight"], np.ones((2,), np.float32))


def test_duplicate_key_collision_raises():
    state = {"x.a": np.zeros((1,), np.float32), "y.a": np.zeros((1,), np.float32)}
    with pytest.raises(ValueError):
        # Both rename to the same key -> collision detected.
        core.remap(state, [core.rule(rename=lambda k: "same")])


def test_check_shapes_passes_and_fails():
    state = {"w": np.zeros((4, 3), np.float32)}
    # Passing check is a no-op identity.
    assert core.remap(state, [core.check_shapes({"w": (4, 3)})]) is not None
    with pytest.raises(ValueError):
        core.remap(state, [core.check_shapes({"w": (4, 4)})])
    with pytest.raises(ValueError):
        core.remap(state, [core.check_shapes({"missing": (1,)})])


# ---------------------------------------------------------------------------
# (iii) bf16 input is upcast to fp32
# ---------------------------------------------------------------------------

def test_bf16_upcast_to_fp32(tmp_path):
    # 1.5, -2.0, 0.0, 3.0, 0.5 are all exactly representable in bf16.
    values = np.array([1.5, -2.0, 0.0, 3.0, 0.5], dtype=np.float32)
    st_path = str(tmp_path / "bf16.safetensors")
    _write_safetensors_bf16(st_path, {"t": values})

    loaded = core.load_safetensors(st_path)
    assert loaded["t"].dtype == np.float32
    np.testing.assert_array_equal(loaded["t"], values)


def test_fp16_upcast_to_fp32(tmp_path):
    values = np.array([1.25, -0.5, 2.0], dtype=np.float16)
    st_path = str(tmp_path / "fp16.safetensors")
    from safetensors.numpy import save_file
    save_file({"t": values}, st_path)
    loaded = core.load_safetensors(st_path)
    assert loaded["t"].dtype == np.float32
    np.testing.assert_array_equal(loaded["t"], values.astype(np.float32))


# ---------------------------------------------------------------------------
# Weight-norm folding transform (reusable form of the inlined AE folding)
# ---------------------------------------------------------------------------

def test_fold_weight_norm():
    # Reference: weight = g * v / ||v|| over all dims except output channel 0.
    weight_v = np.array([[[3.0, 4.0]], [[0.0, 5.0]]], dtype=np.float32)  # (2,1,2)
    weight_g = np.array([[[2.0]], [[10.0]]], dtype=np.float32)           # (2,1,1)
    state = {
        "conv.mapping.weight_g": weight_g,
        "conv.mapping.weight_v": weight_v,
        "conv.mapping.bias": np.array([1.0, 2.0], dtype=np.float32),
    }
    out = core.remap(state, [core.fold_weight_norm()])
    assert "conv.mapping.weight" in out
    assert "conv.mapping.weight_g" not in out
    assert "conv.mapping.weight_v" not in out
    assert "conv.mapping.bias" in out  # untouched

    norm0 = np.sqrt(3.0 ** 2 + 4.0 ** 2)  # 5
    norm1 = np.sqrt(0.0 ** 2 + 5.0 ** 2)  # 5
    expected = np.array([
        [[2.0 * 3.0 / norm0, 2.0 * 4.0 / norm0]],
        [[10.0 * 0.0 / norm1, 10.0 * 5.0 / norm1]],
    ], dtype=np.float32)
    np.testing.assert_allclose(out["conv.mapping.weight"], expected, rtol=1e-6, atol=1e-6)


def test_fold_weight_norm_leaves_unpaired_keys():
    state = {"a.weight_g": np.ones((1, 1, 1), np.float32)}  # no matching _v
    out = core.remap(state, [core.fold_weight_norm()])
    # Unpaired g key is left as-is (no fold possible).
    assert "a.weight_g" in out


# ---------------------------------------------------------------------------
# Reference-dump capability (synthetic / stub model)
# ---------------------------------------------------------------------------

def test_dump_reference_activations(tmp_path):
    stages = {
        "test_input": np.arange(8, dtype=np.float32),
        "resampling_stage_0": np.array([1.0, 2.0], dtype=np.float32),
        "encoder_output": np.zeros((3,), np.float32),
    }
    out_dir = str(tmp_path / "reference")
    paths = core.dump_reference_activations(stages, out_dir)
    assert len(paths) == 3
    for name, expected in stages.items():
        reloaded = core.read_reference_output(os.path.join(out_dir, f"{name}.bin"))
        np.testing.assert_array_equal(reloaded, expected.flatten())


def test_run_reference_stages_with_stub_model(tmp_path):
    """A synthetic staged 'model' exercises the hook the real SAME forward fills."""
    def stub_model(x):
        x = np.asarray(x, dtype=np.float32)
        # Pretend two resampling stages then encoder/decoder outputs.
        stage0 = x[::2]                 # downsample by 2
        stage1 = stage0 * 2.0
        encoder_output = stage1 + 1.0
        decoder_output = np.concatenate([encoder_output, encoder_output])
        return {
            "resampling_stage_0": stage0,
            "resampling_stage_1": stage1,
            "encoder_output": encoder_output,
            "decoder_output": decoder_output,
        }

    test_input = np.arange(8, dtype=np.float32)
    stages = core.run_reference_stages(stub_model, test_input)
    assert "test_input" in stages
    np.testing.assert_array_equal(stages["test_input"], test_input)
    np.testing.assert_array_equal(stages["resampling_stage_0"], test_input[::2])

    # End-to-end: run stub -> dump -> reload one stage at a time.
    out_dir = str(tmp_path / "ref")
    core.dump_reference_activations(stages, out_dir)
    reloaded = core.read_reference_output(os.path.join(out_dir, "decoder_output.bin"))
    assert reloaded.shape == (8,)


def test_run_reference_stages_missing_stage_raises():
    def bad_model(x):
        return {"encoder_output": np.zeros((2,), np.float32)}
    with pytest.raises(KeyError):
        core.run_reference_stages(bad_model, np.zeros((4,), np.float32),
                                  stage_names=["resampling_stage_0"])


# ---------------------------------------------------------------------------
# SA3 config rules over a synthetic key set
# ---------------------------------------------------------------------------

def _synthetic_sa3_state():
    """A tiny synthetic checkpoint mirroring the released SA3 top-level layout."""
    z = lambda *s: np.zeros(s, dtype=np.float32)
    return {
        # DiT
        "model.model.to_cond_embed.0.weight": z(8, 6),
        "model.model.transformer.memory_tokens": z(4, 8),
        "model.model.transformer.layers.0.self_attn.to_qkv.weight": z(24, 8),
        "model.model.transformer.layers.0.to_scale_shift_gate": z(48),
        "model.model.transformer.layers.0.to_local_embed.0.weight": z(8, 5),
        # embedded AE
        "pretransform.model.bottleneck.scaling_factor": z(1, 8, 1),
        "pretransform.model.encoder.layers.0.weight": z(8, 4),
        "pretransform.model.decoder.layers.3.mapping.weight_g": z(8, 1, 1),
        "pretransform.model.decoder.layers.3.mapping.weight_v": z(8, 8, 3),
        # conditioner (dropped by both targets)
        "conditioner.conditioners.prompt.padding_embedding": z(8),
    }


def test_sa3_dit_rules_select_and_passthrough():
    state = _synthetic_sa3_state()
    out = core.remap(state, sa3.sa3_dit_rules())
    # Only model.model.* survive; pretransform + conditioner dropped.
    assert all(k.startswith("model.model.") for k in out)
    # Block-B keys pass through unchanged (consumed when B lands).
    assert "model.model.transformer.memory_tokens" in out
    assert "model.model.transformer.layers.0.to_scale_shift_gate" in out
    assert "model.model.transformer.layers.0.to_local_embed.0.weight" in out
    assert "conditioner.conditioners.prompt.padding_embedding" not in out


def test_sa3_ae_embedded_rules_strip_and_fold():
    state = _synthetic_sa3_state()
    out = core.remap(state, sa3.sa3_ae_rules(mode="embedded"))
    # pretransform.model. prefix stripped.
    assert "bottleneck.scaling_factor" in out
    assert "encoder.layers.0.weight" in out
    # weight-norm mapping conv folded into a single weight.
    assert "decoder.layers.3.mapping.weight" in out
    assert "decoder.layers.3.mapping.weight_g" not in out
    assert "decoder.layers.3.mapping.weight_v" not in out
    # DiT / conditioner keys excluded.
    assert not any(k.startswith("model.model.") for k in out)
    assert not any(k.startswith("conditioner.") for k in out)


def test_sa3_ae_standalone_rules_bare_keys():
    # Standalone repo keys are already bare; verify fold still applies and no
    # prefix stripping is required.
    z = lambda *s: np.zeros(s, dtype=np.float32)
    state = {
        "encoder.layers.0.weight": z(8, 4),
        "decoder.layers.3.mapping.weight_g": z(8, 1, 1),
        "decoder.layers.3.mapping.weight_v": z(8, 8, 3),
    }
    out = core.remap(state, sa3.sa3_ae_rules(mode="standalone"))
    assert "encoder.layers.0.weight" in out
    assert "decoder.layers.3.mapping.weight" in out


def test_sa3_end_to_end_extract_dit(tmp_path):
    """load_safetensors -> sa3 dit rules -> write -> reload, end to end."""
    state = _synthetic_sa3_state()
    st_path = str(tmp_path / "sa3.safetensors")
    _write_safetensors_fp32(st_path, state)

    out_dir = str(tmp_path / "dit_weights")
    mapped = core.extract(st_path, out_dir, sa3.sa3_dit_rules(), shard_prefix="dit")
    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded.keys()) == set(mapped.keys())
    assert all(k.startswith("model.model.") for k in reloaded)


def test_sa3_expected_key_sets_are_consistent_with_rules():
    """The declared expected-key sets must be captured by the rules themselves."""
    # Every DiT expected key is in the model.model.* namespace the rule selects.
    assert all(k.startswith(sa3.DIT_PREFIX) for k in sa3.SA3_DIT_EXPECTED_KEYS)
    # Every AE expected key is in the pretransform.model.* namespace.
    assert all(k.startswith(sa3.AE_EMBEDDED_PREFIX) for k in sa3.SA3_AE_EXPECTED_KEYS)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
