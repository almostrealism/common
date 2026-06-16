#!/usr/bin/env python3
"""
Extract Stable Audio 3 weights from a ``.safetensors`` checkpoint.

This is a *thin config* over the generalized core in ``safetensors_extractor.py``
(per the ``extract_<model>_weights.py`` convention in ``engine/ml/CLAUDE.md``):
it only declares the SA3 key-remapping rule sets and delegates loading,
remapping, and protobuf serialization to the core. It is not a fork of the
extraction logic.

The released SA3 small checkpoint (``stabilityai/stable-audio-3-small-music``)
is a single ``model.safetensors`` holding three top-level groups:

* ``model.model.*``           -- the diffusion transformer (DiT)
* ``pretransform.model.*``    -- the embedded SAME-S autoencoder
* ``conditioner.conditioners.*`` -- the prompt / duration conditioner exports

The standalone SAME-S / SAME-L repositories store the autoencoder with *bare*
keys (no ``pretransform.model.`` prefix).

Usage::

    # DiT weights (what DiffusionTransformer loads):
    python extract_sa3_weights.py model.safetensors out_dir --target dit

    # Embedded autoencoder (strip the pretransform.model. prefix):
    python extract_sa3_weights.py model.safetensors out_dir --target ae

    # Standalone SAME-S/SAME-L repo (bare keys):
    python extract_sa3_weights.py same.safetensors out_dir --target ae --ae-mode standalone
"""

import argparse

import safetensors_extractor as core


# ---------------------------------------------------------------------------
# DiT remap
# ---------------------------------------------------------------------------

# The released DiT keys already match what DiffusionTransformer.java reads
# verbatim (model.model.to_cond_embed/to_global_embed/to_timestep_embed,
# preprocess_conv, postprocess_conv, transformer.*). So the DiT remap is simply:
# keep the model.model.* namespace and pass it through, dropping the embedded
# autoencoder (pretransform.*) and the conditioner exports.
DIT_PREFIX = "model.model."


def sa3_dit_rules():
    """Rule list mapping a released SA3 checkpoint to the DiT StateDictionary.

    Block-B (adaLN / memory tokens / local-add cond) keys are ALREADY present in
    the released ``model.model.*`` namespace and pass through unchanged here:

    * ``model.model.transformer.layers.N.to_scale_shift_gate``  (adaLN)
    * ``model.model.transformer.memory_tokens``                 (register tokens)
    * ``model.model.transformer.layers.N.to_local_embed.*``     (local-add cond)

    When Block B lands, attach any rename / shape-check rules for these keys to
    the list returned here (e.g. if AR's consumer adopts different local key
    names). Today they require no transformation, so no rule consumes them
    explicitly -- they ride through ``select_prefix``.
    """
    return [
        core.select_prefix(DIT_PREFIX),
        # (Block B attachment point: insert rename/check rules for
        #  to_scale_shift_gate / memory_tokens / to_local_embed here.)
    ]


# Representative released input keys the DiT config consumes. The real-key
# validation test asserts every one of these is present in the released
# checkpoint header, catching drift between this config and Stability's layout.
# Per-layer keys are sampled at layer 0 (all 20 layers share the structure).
SA3_DIT_EXPECTED_KEYS = frozenset([
    "model.model.to_cond_embed.0.weight",
    "model.model.to_cond_embed.2.weight",
    "model.model.to_global_embed.0.weight",
    "model.model.to_global_embed.2.weight",
    "model.model.to_timestep_embed.0.weight",
    "model.model.to_timestep_embed.0.bias",
    "model.model.to_timestep_embed.2.weight",
    "model.model.to_timestep_embed.2.bias",
    "model.model.preprocess_conv.weight",
    "model.model.postprocess_conv.weight",
    "model.model.transformer.project_in.weight",
    "model.model.transformer.project_out.weight",
    "model.model.transformer.rotary_pos_emb.inv_freq",
    "model.model.transformer.memory_tokens",
    "model.model.transformer.global_cond_embedder.0.weight",
    "model.model.transformer.global_cond_embedder.2.weight",
    "model.model.transformer.layers.0.pre_norm.gamma",
    "model.model.transformer.layers.0.self_attn.to_qkv.weight",
    "model.model.transformer.layers.0.self_attn.to_out.weight",
    "model.model.transformer.layers.0.cross_attn.to_q.weight",
    "model.model.transformer.layers.0.cross_attn.to_kv.weight",
    "model.model.transformer.layers.0.ff.ff.0.proj.weight",
    "model.model.transformer.layers.0.ff.ff.2.weight",
    # Block-B keys (present today; consumed when B lands):
    "model.model.transformer.layers.0.to_scale_shift_gate",
    "model.model.transformer.layers.0.to_local_embed.0.weight",
])


# ---------------------------------------------------------------------------
# Autoencoder (SAME) remap
# ---------------------------------------------------------------------------

AE_EMBEDDED_PREFIX = "pretransform.model."


def sa3_ae_rules(mode="embedded", fold_weight_norm=True):  # TODO(review): param `fold_weight_norm` shadows core.fold_weight_norm — rename to `with_weight_norm` to avoid hazard
    """Rule list mapping a SAME autoencoder to bare StateDictionary keys.

    ``mode="embedded"`` strips the ``pretransform.model.`` prefix used when the
    autoencoder is bundled inside a DiT checkpoint. ``mode="standalone"`` is for
    the dedicated SAME-S / SAME-L repos whose keys are already bare.

    The SAME resampling blocks carry a handful of weight-norm ``mapping`` convs
    (``...mapping.weight_g`` / ``...mapping.weight_v``); ``fold_weight_norm``
    folds them into a single ``...mapping.weight`` so the consumer loads a plain
    convolution weight (the pre-folding the C2 plan calls for).
    """
    rules = []
    if mode == "embedded":
        rules.append(core.select_prefix(AE_EMBEDDED_PREFIX))
        rules.append(core.strip_prefix(AE_EMBEDDED_PREFIX))
    elif mode == "standalone":
        # Bare keys; nothing to strip. (No select: the standalone repo is
        # autoencoder-only, so every key belongs to the AE.)
        pass
    else:
        raise ValueError("Unknown ae mode: %r (expected 'embedded' or 'standalone')" % mode)

    if fold_weight_norm:
        rules.append(core.fold_weight_norm())
    return rules


# Representative released input keys (as they appear in the embedded checkpoint,
# i.e. before the pretransform.model. prefix is stripped) the AE config consumes.
SA3_AE_EXPECTED_KEYS = frozenset([
    "pretransform.model.bottleneck.scaling_factor",
    "pretransform.model.bottleneck.bias",
    "pretransform.model.bottleneck.running_std",
    # Encoder resampling stage 0: learned new_tokens + weight-norm mapping conv
    # + the differential-attention transformer sub-blocks.
    "pretransform.model.encoder.layers.0.new_tokens",
    "pretransform.model.encoder.layers.0.mapping.weight_g",
    "pretransform.model.encoder.layers.0.mapping.weight_v",
    "pretransform.model.encoder.layers.0.transformers.0.self_attn.to_qkv.weight",
    # Decoder: the leading Linear (layers.1) and a resampling stage (layers.3).
    "pretransform.model.decoder.layers.1.weight",
    "pretransform.model.decoder.layers.3.new_tokens",
    "pretransform.model.decoder.layers.3.mapping.weight_g",
])


def rules_for(target, ae_mode="embedded"):
    """Return the remap rule list for a CLI target ('dit' or 'ae')."""
    if target == "dit":
        return sa3_dit_rules()
    if target == "ae":
        return sa3_ae_rules(mode=ae_mode)
    raise ValueError("Unknown target: %r (expected 'dit' or 'ae')" % target)


def main():
    parser = argparse.ArgumentParser(
        description="Extract Stable Audio 3 weights from .safetensors to StateDictionary protobuf")
    parser.add_argument("safetensors_path", help="Path to the .safetensors checkpoint")
    parser.add_argument("output_dir", help="Output directory for StateDictionary shards")
    parser.add_argument("--target", choices=["dit", "ae"], default="dit",
                        help="Which sub-model to extract (default: dit)")
    parser.add_argument("--ae-mode", choices=["embedded", "standalone"], default="embedded",
                        help="Autoencoder key layout (default: embedded)")
    parser.add_argument("--shard-prefix", default=None,
                        help="Override the protobuf shard file prefix")
    args = parser.parse_args()

    shard_prefix = args.shard_prefix or args.target
    rules = rules_for(args.target, ae_mode=args.ae_mode)
    core.extract(args.safetensors_path, args.output_dir, rules, shard_prefix=shard_prefix)
    print("\nDone!")


if __name__ == "__main__":
    main()
