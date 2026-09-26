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


def test_write_state_dictionary_excludes_stale_same_prefix_shard(tmp_path):
    """A reused out_dir must not let a stale same-prefix shard pollute the load.

    The StateDictionary loader reads every non-hidden file in the directory, so
    ``write_state_dictionary`` must (a) return only the files it actually wrote
    and (b) clear any stale same-prefix shard left by a prior, larger run so a
    later directory load does not pick up old weights.
    """
    out_dir = str(tmp_path / "w")
    os.makedirs(out_dir, exist_ok=True)

    # Simulate a prior run that needed two shards ("weights" + "weights_1"); the
    # current, smaller state only produces the single "weights" shard, leaving
    # "weights_1" stale on disk.
    stale = os.path.join(out_dir, "weights_1")
    core.write_protobuf_file(
        [core.make_entry("stale.weight", np.full((2,), 99.0, np.float32))], stale)
    assert os.path.isfile(stale)

    new_state = {"fresh.weight": np.arange(3, dtype=np.float32)}
    paths = core.write_state_dictionary(new_state, out_dir)

    # The returned list is exactly what was written, excluding the stale shard.
    assert stale not in paths
    assert all(os.path.basename(p) != "weights_1" for p in paths)
    assert os.path.basename(paths[0]) == "weights"

    # The stale shard was removed, so a directory load sees only the fresh state.
    assert not os.path.exists(stale)
    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded.keys()) == {"fresh.weight"}
    np.testing.assert_array_equal(reloaded["fresh.weight"], new_state["fresh.weight"])


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
    """A reference dump is protobuf collection data, read back by the same reader
    a weight export is, with each stage's shape preserved."""
    stages = {
        "test_input": np.arange(8, dtype=np.float32),
        "resampling_stage_0": np.array([[1.0, 2.0], [3.0, 4.0]], dtype=np.float32),
        "encoder_output": np.zeros((3,), np.float32),
    }
    out_dir = str(tmp_path / "reference")
    paths = core.dump_reference_activations(stages, out_dir)
    assert paths, "at least one shard is written"

    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded) == set(stages)
    for name, expected in stages.items():
        np.testing.assert_array_equal(reloaded[name], expected)
        assert reloaded[name].shape == expected.shape, name + " keeps its shape"


def test_dump_reference_activations_writes_no_bespoke_files(tmp_path):
    """The per-stage `<name>.bin` files are gone: one format crosses the boundary."""
    out_dir = str(tmp_path / "reference")
    core.dump_reference_activations({"only": np.arange(4, dtype=np.float32)}, out_dir)
    assert not [n for n in os.listdir(out_dir) if n.endswith(".bin")]


def test_dump_over_a_legacy_dump_removes_its_stage_files(tmp_path):
    """Dumping into a directory the bespoke format wrote removes the `<stage>.bin` files
    for the stages being written, so the directory reads back as protobuf; a `.bin` file
    that names no stage in this dump is not the writer's to remove and is left alone."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    for name in ("dit_output", "cond_bias", "unrelated"):
        core.save_reference_output(np.arange(3, dtype=np.float32), str(out_dir / (name + ".bin")))

    stages = {
        "dit_output": np.arange(6, dtype=np.float32).reshape(2, 3),
        "cond_bias": np.full((4,), 0.5, dtype=np.float32),
    }
    core.dump_reference_activations(stages, str(out_dir))

    assert not (out_dir / "dit_output.bin").exists()
    assert not (out_dir / "cond_bias.bin").exists()
    assert (out_dir / "unrelated.bin").exists()

    # The leftover `.bin` for a stage this dump did not write stays on disk, but
    # the directory reader skips legacy `.bin` files, so the dump reads back as
    # protobuf without removing it first.
    reloaded = core.read_state_dictionary(str(out_dir))
    assert set(reloaded) == set(stages)
    np.testing.assert_array_equal(reloaded["dit_output"], stages["dit_output"])
    np.testing.assert_array_equal(reloaded["cond_bias"], stages["cond_bias"])


def test_second_dump_with_same_prefix_replaces_the_first(tmp_path):
    """Two dumps into the same directory with the same shard prefix do not accumulate:
    the writer clears stale same-prefix shards first, so the second dump replaces the
    first. A caller with two tensor groups must therefore merge them into one dump."""
    out_dir = str(tmp_path / "reference")
    core.dump_reference_activations({"first": np.arange(3, dtype=np.float32)}, out_dir)
    core.dump_reference_activations({"second": np.arange(4, dtype=np.float32)}, out_dir)

    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded) == {"second"}, "the second dump replaced the first"


def test_merged_dump_keeps_every_group(tmp_path):
    """Merging two disjoint tensor groups into a single dump keeps every key — the
    pattern a caller with separate stage and conditioner maps must use so neither group
    deletes the other's shards."""
    stages = {"dit_output": np.arange(3, dtype=np.float32)}
    conditioner = {"cond_bias": np.arange(4, dtype=np.float32)}
    combined = dict(stages)
    combined.update(conditioner)

    out_dir = str(tmp_path / "reference")
    core.dump_reference_activations(combined, out_dir)

    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded) == {"dit_output", "cond_bias"}


def test_read_skips_json_sidecars(tmp_path):
    """The dump scripts write shapes.json / meta.json / weight_shapes.json beside their
    shards; reading the directory back returns only the tensors, not a parse error."""
    stages = {"dit_output": np.arange(6, dtype=np.float32).reshape(2, 3)}
    out_dir = str(tmp_path / "reference")
    core.dump_reference_activations(stages, out_dir)
    for sidecar in ("shapes.json", "meta.json", "weight_shapes.json"):
        with open(os.path.join(out_dir, sidecar), "w") as f:
            json.dump({"dit_output": [2, 3]}, f)

    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded) == {"dit_output"}
    np.testing.assert_array_equal(reloaded["dit_output"], stages["dit_output"])


def test_read_skips_legacy_bin_files(tmp_path):
    """A directory dumped into before the protobuf migration may hold bespoke
    `<stage>.bin` files for stages not in the current dump; reading the directory
    back skips them rather than failing to parse them as protobuf."""
    stages = {"dit_output": np.arange(6, dtype=np.float32).reshape(2, 3)}
    out_dir = str(tmp_path / "reference")
    core.dump_reference_activations(stages, out_dir)
    core.save_reference_output(np.arange(3, dtype=np.float32),
                               os.path.join(out_dir, "legacy_stage.bin"))

    reloaded = core.read_state_dictionary(out_dir)
    assert set(reloaded) == {"dit_output"}
    np.testing.assert_array_equal(reloaded["dit_output"], stages["dit_output"])


def test_write_state_dictionary_rejects_reserved_shard_prefix(tmp_path):
    """A shard_prefix ending in a reserved suffix (`.json` sidecar / `.bin` legacy)
    is rejected: read_state_dictionary skips those files, so the first shard — named
    by the bare prefix — would be dropped on read while Java StateDictionary loaded
    it. The dump directory must stay empty rather than hold an unreadable shard."""
    out_dir = tmp_path / "reserved"
    for prefix in ("weights.bin", "weights.json"):
        with pytest.raises(ValueError):
            core.write_state_dictionary(
                {"only": np.arange(4, dtype=np.float32)}, str(out_dir), shard_prefix=prefix)
    assert not out_dir.exists() or not list(out_dir.iterdir())


def test_write_state_dictionary_rejects_hidden_shard_prefix(tmp_path):
    """A shard_prefix beginning with `.` names hidden shard files, which both
    read_state_dictionary and the Java StateDictionary skip. Such a prefix is
    rejected before anything is written — otherwise write_state_dictionary would
    report success while producing a dump neither reader can load."""
    out_dir = tmp_path / "hidden"
    for prefix in (".weights", ".references", ".hidden.shard"):
        with pytest.raises(ValueError):
            core.write_state_dictionary(
                {"only": np.arange(4, dtype=np.float32)}, str(out_dir), shard_prefix=prefix)
    assert not out_dir.exists() or not list(out_dir.iterdir())


def test_dump_reference_activations_keeps_legacy_bin_on_hidden_prefix(tmp_path):
    """A hidden shard_prefix is rejected before the destructive legacy cleanup, so
    dumping over a pre-migration directory with a `.`-prefixed name must raise
    ValueError WITHOUT deleting the `<stage>.bin` dump it would have replaced."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    legacy = out_dir / "dit_output.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(legacy))

    with pytest.raises(ValueError):
        core.dump_reference_activations(
            {"dit_output": np.arange(6, dtype=np.float32)}, str(out_dir),
            shard_prefix=".references")

    assert legacy.exists(), "legacy dump must survive a hidden-prefix rejection"


def test_dump_reference_activations_keeps_legacy_bin_when_bindings_missing(tmp_path, monkeypatch):
    """The protobuf binding is validated before the destructive legacy cleanup: on a
    checkout without collections_pb2, dumping over a pre-migration directory must raise
    ImportError WITHOUT first deleting the `<stage>.bin` dump it would have replaced."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    legacy = out_dir / "dit_output.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(legacy))

    def _raise():
        raise ImportError("collections_pb2 unavailable")

    monkeypatch.setattr(core, "_require_collections", _raise)

    with pytest.raises(ImportError):
        core.dump_reference_activations(
            {"dit_output": np.arange(6, dtype=np.float32)}, str(out_dir))

    assert legacy.exists(), "legacy dump must survive a prerequisite failure"


def test_dump_reference_activations_keeps_legacy_bin_on_reserved_prefix(tmp_path):
    """A reserved shard_prefix is rejected before the destructive legacy cleanup:
    dumping over a pre-migration directory with a `.bin`/`.json` prefix must raise
    ValueError WITHOUT first deleting the `<stage>.bin` dump it would have replaced."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    legacy = out_dir / "dit_output.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(legacy))

    for prefix in ("references.bin", "references.json"):
        with pytest.raises(ValueError):
            core.dump_reference_activations(
                {"dit_output": np.arange(6, dtype=np.float32)}, str(out_dir),
                shard_prefix=prefix)
        assert legacy.exists(), "legacy dump must survive a reserved-prefix rejection"


def test_dump_reference_activations_ignores_a_traversing_stage_key(tmp_path):
    """A stage name is caller-supplied, so the legacy `<stage>.bin` cleanup must not let
    a name containing `..` (or an absolute path) delete a file outside out_dir. Such a
    name resolves outside the dump directory and is skipped rather than removed."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    # A `.bin` file OUTSIDE the dump directory that a traversing stage key would map to:
    # os.path.join(out_dir, "../sentinel.bin") resolves to tmp_path/sentinel.bin.
    outside = tmp_path / "sentinel.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(outside))

    core.dump_reference_activations(
        {"../sentinel": np.arange(4, dtype=np.float32)}, str(out_dir))

    assert outside.exists(), "cleanup must not delete a file outside the dump directory"


def test_dump_reference_activations_keeps_another_stages_legacy_bin(tmp_path):
    """A traversing stage key can resolve back inside out_dir onto a different stage's
    legacy `<stage>.bin` (`sub/../unrelated` -> `out_dir/unrelated.bin`). The parent
    directory then equals out_dir, so a bare realpath-parent guard would delete that
    unrelated dump. Cleanup only owns plain single-component stage names, so the
    traversing key must leave the other stage's legacy file untouched."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    # An intermediate directory the traversing key steps through (the traversal is
    # only real if the component it descends into exists).
    (out_dir / "sub").mkdir()
    # A legacy dump for a DIFFERENT stage, sitting directly inside out_dir.
    unrelated = out_dir / "unrelated.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(unrelated))

    core.dump_reference_activations(
        {"sub/../unrelated": np.arange(4, dtype=np.float32)}, str(out_dir))

    assert unrelated.exists(), \
        "cleanup must not delete another stage's legacy dump via a traversing key"


def test_write_state_dictionary_rejects_path_bearing_shard_prefix(tmp_path):
    """A shard_prefix names files directly inside out_dir, so a prefix that is empty,
    dot-only, absolute, or contains a path separator is rejected before anything is
    written — otherwise `../escape` would place a shard beside out_dir, not in it."""
    out_dir = tmp_path / "w"
    outside = tmp_path / "abs_target"
    prefixes = ("../escape", "sub/weights", str(outside), "", ".", "..")
    for prefix in prefixes:
        with pytest.raises(ValueError):
            core.write_state_dictionary(
                {"only": np.arange(4, dtype=np.float32)}, str(out_dir), shard_prefix=prefix)

    assert not (tmp_path / "escape").exists()
    assert not outside.exists()
    assert not out_dir.exists() or not list(out_dir.iterdir())


def test_dump_reference_activations_rejects_path_bearing_shard_prefix(tmp_path):
    """The reference dump entry point applies the same prefix rule and, because it
    fails before writing, leaves a pre-migration `<stage>.bin` dump in place."""
    out_dir = tmp_path / "reference"
    out_dir.mkdir()
    legacy = out_dir / "dit_output.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(legacy))

    with pytest.raises(ValueError):
        core.dump_reference_activations(
            {"dit_output": np.arange(6, dtype=np.float32)}, str(out_dir),
            shard_prefix="../references")

    assert not (tmp_path / "references").exists()
    assert legacy.exists()


def test_failed_write_keeps_the_previous_dump(tmp_path, monkeypatch):
    """Stale same-prefix shards and legacy `<stage>.bin` files are removed only after
    the replacement shards are written: a failure while writing must leave both the
    previous protobuf shard and the legacy file on disk and readable."""
    out_dir = tmp_path / "reference"
    core.dump_reference_activations({"first": np.arange(3, dtype=np.float32)}, str(out_dir))
    legacy = out_dir / "dit_output.bin"
    core.save_reference_output(np.arange(3, dtype=np.float32), str(legacy))

    def _fail(entries, output_dir, prefix):
        raise OSError("disk full")

    monkeypatch.setattr(core, "write_group", _fail)
    with pytest.raises(OSError):
        core.dump_reference_activations(
            {"dit_output": np.arange(6, dtype=np.float32)}, str(out_dir))

    assert legacy.exists(), "legacy dump must survive a failed replacement write"
    np.testing.assert_array_equal(core.read_reference_output(str(legacy)),
                                  np.arange(3, dtype=np.float32))
    reloaded = core.read_state_dictionary(str(out_dir))
    assert set(reloaded) == {"first"}, "previous shard must survive a failed write"
    np.testing.assert_array_equal(reloaded["first"], np.arange(3, dtype=np.float32))


def test_failure_mid_write_keeps_the_previous_shards(tmp_path, monkeypatch):
    """A failure after some shards of a group have already been serialized must not
    truncate or replace the previous dump's same-named shards: shards are staged
    under hidden names and moved into place only once the whole group is written,
    and the staged files are removed when the write fails."""
    out_dir = tmp_path / "reference"
    monkeypatch.setattr(core, "PROTOBUF_SIZE_LIMIT", 1)
    previous = {"a": np.arange(3, dtype=np.float32), "b": np.arange(5, dtype=np.float32)}
    first = core.write_state_dictionary(previous, str(out_dir), shard_prefix="references")
    assert [os.path.basename(p) for p in first] == ["references", "references_1"]
    before = {name: (out_dir / name).read_bytes() for name in ("references", "references_1")}

    original = core.write_protobuf_file
    calls = []

    def _fail_on_second(entries, file_path):
        calls.append(file_path)
        if len(calls) == 2:
            with open(file_path, "wb") as f:
                f.write(b"\x00partial")
            raise OSError("disk full")
        original(entries, file_path)

    monkeypatch.setattr(core, "write_protobuf_file", _fail_on_second)
    replacement = {"a": np.full(7, 9.0, dtype=np.float32), "b": np.full(2, 4.0, dtype=np.float32)}
    with pytest.raises(OSError):
        core.write_state_dictionary(replacement, str(out_dir), shard_prefix="references")

    assert len(calls) == 2
    assert all(os.path.basename(p).startswith(".") for p in calls), \
        "shards must be serialized under hidden staging names"
    assert sorted(os.listdir(out_dir)) == ["references", "references_1"], \
        "staged files must be removed after a failed write"
    for name, data in before.items():
        assert (out_dir / name).read_bytes() == data, f"{name} must be untouched"
    reloaded = core.read_state_dictionary(str(out_dir))
    assert set(reloaded) == {"a", "b"}
    np.testing.assert_array_equal(reloaded["a"], previous["a"])
    np.testing.assert_array_equal(reloaded["b"], previous["b"])


def test_successful_write_leaves_no_staged_files(tmp_path, monkeypatch):
    """After a successful multi-shard write every staged file has been moved onto
    its final name, replacing the previous content, and no hidden file remains."""
    out_dir = tmp_path / "reference"
    monkeypatch.setattr(core, "PROTOBUF_SIZE_LIMIT", 1)
    core.write_state_dictionary({"a": np.arange(3, dtype=np.float32),
                                 "b": np.arange(5, dtype=np.float32)},
                                str(out_dir), shard_prefix="references")
    written = core.write_state_dictionary({"a": np.full(2, 6.0, dtype=np.float32),
                                           "b": np.full(4, 8.0, dtype=np.float32)},
                                          str(out_dir), shard_prefix="references")

    assert [os.path.basename(p) for p in written] == ["references", "references_1"]
    assert sorted(os.listdir(out_dir)) == ["references", "references_1"]
    reloaded = core.read_state_dictionary(str(out_dir))
    np.testing.assert_array_equal(reloaded["a"], np.full(2, 6.0, dtype=np.float32))
    np.testing.assert_array_equal(reloaded["b"], np.full(4, 8.0, dtype=np.float32))


def test_replace_failure_during_promotion_restores_the_previous_dump(tmp_path, monkeypatch):
    """A failure during the promotion phase — an ``os.replace`` after an earlier shard
    of the group has already been moved onto its final name — must roll back: the
    previous dump's shards are restored and no staged or backup file is left behind,
    so the directory never holds a mix of new and previous shards."""
    out_dir = tmp_path / "reference"
    monkeypatch.setattr(core, "PROTOBUF_SIZE_LIMIT", 1)
    previous = {"a": np.arange(3, dtype=np.float32), "b": np.arange(5, dtype=np.float32)}
    core.write_state_dictionary(previous, str(out_dir), shard_prefix="references")
    before = {name: (out_dir / name).read_bytes() for name in ("references", "references_1")}

    real_replace = os.replace

    def _fail_second_promotion(src, dst):
        # Fail only when promoting the second shard's staged file onto its final name,
        # after the first shard has already been promoted in place.
        if str(src).endswith(".references_1.partial"):
            raise OSError("rename failed")
        real_replace(src, dst)

    monkeypatch.setattr(core.os, "replace", _fail_second_promotion)
    replacement = {"a": np.full(7, 9.0, dtype=np.float32), "b": np.full(2, 4.0, dtype=np.float32)}
    with pytest.raises(OSError):
        core.write_state_dictionary(replacement, str(out_dir), shard_prefix="references")

    assert sorted(os.listdir(out_dir)) == ["references", "references_1"], \
        "rollback must leave only the previous shards, with no staged or backup files"
    for name, data in before.items():
        assert (out_dir / name).read_bytes() == data, \
            f"{name} must be restored to its previous content after a failed promotion"
    reloaded = core.read_state_dictionary(str(out_dir))
    assert set(reloaded) == {"a", "b"}
    np.testing.assert_array_equal(reloaded["a"], previous["a"])
    np.testing.assert_array_equal(reloaded["b"], previous["b"])


def test_read_still_rejects_a_corrupt_shard(tmp_path):
    """Only the .json sidecars and legacy .bin files are skipped: any other non-hidden
    file is read as a shard, so a corrupt one is an error rather than being silently
    dropped."""
    out_dir = str(tmp_path / "reference")
    core.dump_reference_activations({"only": np.arange(4, dtype=np.float32)}, out_dir)
    with open(os.path.join(out_dir, "references_9"), "wb") as f:
        f.write(b"\xff\xff\xff\xff not a protobuf")

    with pytest.raises(Exception):
        core.read_state_dictionary(out_dir)


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
    reloaded = core.read_state_dictionary(out_dir)["decoder_output"]
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
        # conditioner (dropped by the dit and ae targets)
        "conditioner.conditioners.prompt.padding_embedding": z(8),
        "conditioner.conditioners.seconds_total.embedder.embedding.1.weight": z(8, 4),
        "conditioner.conditioners.seconds_total.embedder.embedding.1.bias": z(8),
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


def test_sa3_conditioner_rules_select_and_passthrough():
    state = _synthetic_sa3_state()
    out = core.remap(state, sa3.rules_for("conditioner"))
    # Exactly the conditioner namespace survives, under the released names.
    assert set(out) == sa3.SA3_CONDITIONER_EXPECTED_KEYS
    assert out["conditioner.conditioners.seconds_total.embedder.embedding.1.weight"].shape == (8, 4)


def test_sa3_expected_key_sets_are_consistent_with_rules():
    """The declared expected-key sets must be captured by the rules themselves."""
    # Every DiT expected key is in the model.model.* namespace the rule selects.
    assert all(k.startswith(sa3.DIT_PREFIX) for k in sa3.SA3_DIT_EXPECTED_KEYS)
    # Every AE expected key is in the pretransform.model.* namespace.
    assert all(k.startswith(sa3.AE_EMBEDDED_PREFIX) for k in sa3.SA3_AE_EXPECTED_KEYS)
    # Every conditioner expected key is in the conditioner.conditioners.* namespace.
    assert all(k.startswith(sa3.CONDITIONER_PREFIX) for k in sa3.SA3_CONDITIONER_EXPECTED_KEYS)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
