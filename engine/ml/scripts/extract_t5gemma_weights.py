#!/usr/bin/env python3
"""
Extract a T5Gemma text encoder from its ``.safetensors`` checkpoint(s) into the
StateDictionary layout read by ``T5GemmaEncoder``.

This is a *thin config* over ``safetensors_extractor.py`` (per the
``extract_<model>_weights.py`` convention in ``engine/ml/CLAUDE.md``): it only
declares the encoder's remapping rules and delegates loading, remapping and
serialization to the core.

The remap keeps the released ``encoder.*`` keys and reshapes them into what the
framework's attention and feed-forward primitives consume:

* ``self_attn.{q,k,v}_proj.weight`` are fused, in that order along the output
  axis, into ``self_attn.to_qkv.weight`` (the fused projection of
  ``AttentionFeatures.sequenceAttention``).
* ``mlp.up_proj.weight`` and ``mlp.gate_proj.weight`` are fused, linear half
  first, into ``mlp.proj.weight`` (the split convention of
  ``FeedForwardFeatures.gatedLinearFeedForward``, which activates the second half).
* Every RMS norm scale (``*layernorm.weight`` and ``encoder.norm.weight``) has its
  unit offset folded in: the reference layer computes ``x / rms(x) * (1 + w)``,
  the framework's ``rmsnorm`` computes ``x / rms(x) * w``.
* ``encoder.rotary.inv_freq`` is added, holding
  ``theta ** (-2 i / head_dim)`` for ``i < head_dim / 2`` (the full-head rotary
  frequencies the reference model derives from its configuration).

Decoder tensors and the language-model head are dropped. Checkpoints stored
without the ``encoder.`` prefix (an encoder-only export) are accepted and
prefixed.

Usage::

    python extract_t5gemma_weights.py model.safetensors out_dir --head-dim 64 --rope-theta 10000
    python extract_t5gemma_weights.py model-00001-of-00002.safetensors model-00002-of-00002.safetensors out_dir
"""

import argparse
import re

import numpy as np

import safetensors_extractor as core

ENCODER_PREFIX = "encoder."

LAYER_PATTERN = re.compile(r"^(encoder\.layers\.\d+)\.")


def ensure_encoder_prefix(state):
    """Keep the ``encoder.`` tensors of a full checkpoint, or prefix an encoder-only export."""
    if any(key.startswith(ENCODER_PREFIX) for key in state):
        return {k: v for k, v in state.items() if k.startswith(ENCODER_PREFIX)}
    return {ENCODER_PREFIX + k: v for k, v in state.items()}


def _layer_prefixes(state):
    prefixes = set()
    for key in state:
        match = LAYER_PATTERN.match(key)
        if match:
            prefixes.add(match.group(1))
    return sorted(prefixes, key=lambda p: int(p.rsplit(".", 1)[1]))


def _fuse(state, prefix, parts, out_suffix):
    """Concatenate ``prefix + part`` tensors along the output axis under ``prefix + out_suffix``."""
    arrays = []
    for part in parts:
        key = prefix + part
        if key not in state:
            raise ValueError("Missing tensor required for fusion: %s" % key)
        arrays.append(state.pop(key).astype(np.float32))
    state[prefix + out_suffix] = np.concatenate(arrays, axis=0)


def fuse_attention_projections(state):
    """Fuse the query, key and value projections of every layer."""
    out = dict(state)
    for prefix in _layer_prefixes(out):
        _fuse(out, prefix, [".self_attn.q_proj.weight", ".self_attn.k_proj.weight", ".self_attn.v_proj.weight"],
              ".self_attn.to_qkv.weight")
    return out


def fuse_feed_forward_projections(state):
    """Fuse the feed-forward's linear (``up``) and gate projections, linear half first."""
    out = dict(state)
    for prefix in _layer_prefixes(out):
        _fuse(out, prefix, [".mlp.up_proj.weight", ".mlp.gate_proj.weight"], ".mlp.proj.weight")
    return out


def fold_norm_offset(state):
    """Add the unit offset of the Gemma RMS norm into every norm scale."""
    out = {}
    for key, array in state.items():
        if key.endswith("layernorm.weight") or key == ENCODER_PREFIX + "norm.weight":
            out[key] = (array.astype(np.float32) + 1.0).astype(np.float32)
        else:
            out[key] = array
    return out


def add_rotary_frequencies(head_dim, theta):
    """Rule adding ``encoder.rotary.inv_freq`` for a full-head rotary embedding."""
    def apply(state):
        out = dict(state)
        exponent = np.arange(0, head_dim, 2, dtype=np.float64) / head_dim
        out[ENCODER_PREFIX + "rotary.inv_freq"] = (theta ** -exponent).astype(np.float32)
        return out
    return apply


def t5gemma_encoder_rules(head_dim, theta):
    """The ordered rule list mapping a T5Gemma checkpoint to the encoder's StateDictionary."""
    return [
        ensure_encoder_prefix,
        fuse_attention_projections,
        fuse_feed_forward_projections,
        fold_norm_offset,
        add_rotary_frequencies(head_dim, theta),
    ]


def main():
    parser = argparse.ArgumentParser(description="Extract a T5Gemma encoder into StateDictionary shards")
    parser.add_argument("paths", nargs="+",
                        help="one or more .safetensors files followed by the output directory")
    parser.add_argument("--head-dim", type=int, default=64, help="attention head width")
    parser.add_argument("--rope-theta", type=float, default=10000.0, help="rotary frequency base")
    args = parser.parse_args()

    if len(args.paths) < 2:
        parser.error("expected at least one .safetensors file and an output directory")

    inputs, out_dir = args.paths[:-1], args.paths[-1]
    state = {}
    for path in inputs:
        print("Loading safetensors: %s" % path)
        state.update(core.load_safetensors(path))
    print("  %d source tensors" % len(state))

    mapped = core.remap(state, t5gemma_encoder_rules(args.head_dim, args.rope_theta))
    print("Remapped to %d tensors" % len(mapped))
    for key in sorted(mapped):
        print("  %s: %s" % (key, list(mapped[key].shape)))

    written = core.write_state_dictionary(mapped, out_dir)
    print("Wrote %d shard(s) to %s" % (len(written), out_dir))


if __name__ == "__main__":
    main()
