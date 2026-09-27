#!/usr/bin/env python3
"""
Dump a real Stable Audio 3 DiT reference activation for a Block D (diffusion
transformer) numerical-parity test.

This mirrors ``dump_t5gemma_reference.py``'s and ``dump_same_references.py``'s role
for their respective sub-models: it builds the **real** released small-music model
(``stabilityai/stable-audio-3-small-music``) from the reference ``stable_audio_3``
source and its ``model_config.json``, runs the real conditioner on a fixed prompt,
calls the diffusion transformer exactly once on a fixed seeded latent, and writes
the resulting activations as protobuf collection data
(:func:`safetensors_extractor.dump_reference_activations`) for a Java parity test to
compare against.

The conditioning config's ``prompt`` entry names the T5Gemma encoder by
``repo_id``/``subfolder`` (a Hugging Face Hub reference); this script substitutes
a local ``model_path`` (``--t5-dir``) so the encoder loads from a snapshot already
on disk instead of triggering a network download.

The DiT forward is called through the underlying ``DiTWrapper`` (``model.model``
on the loaded ``ConditionedDiffusionModelWrapper``) rather than through the
wrapper's own ``forward``, so the exact conditioning tensors handed to the
transformer (before the DiT's internal ``to_cond_embed``/``to_global_embed``
projections) are available to dump directly instead of being reconstructed from a
hook. With the default ``cfg_scale=1.0`` this is a single, non-CFG forward pass.

Since the released model's ``local_add_cond_ids`` includes the inpainting inputs
(``inpaint_mask``, ``inpaint_masked_input``), this script supplies the same
all-zero defaults ``stable_audio_3.model.Model.generate`` uses when no inpainting
is requested, so the forward call matches real (non-inpainting) inference.

Prerequisites::

    pip install torch transformers sentencepiece safetensors numpy
    git clone https://github.com/Stability-AI/stable-audio-3   # the reference source
    # gated weights + config (needs a Hugging Face token with access):
    #   huggingface_hub.hf_hub_download(
    #       "stabilityai/stable-audio-3-small-music", "model.safetensors")
    #   huggingface_hub.hf_hub_download(
    #       "stabilityai/stable-audio-3-small-music", "model_config.json")

Usage::

    python dump_sa3_dit_reference.py \
        --sa3-src /path/to/stable-audio-3 \
        --checkpoint /path/to/stable-audio-3-small-music/model.safetensors \
        --config /path/to/stable-audio-3-small-music/model_config.json \
        --t5-dir /path/to/t5gemma-b-b-ul2

``--out`` defaults to the git-ignored build-output directory
``engine/ml/target/test-classes/sa3-dit-references``, matching
``dump_t5gemma_reference.py``'s and ``dump_same_references.py``'s ``DEFAULT_OUT``
convention. These dumps are regenerable artifacts: never write them into a tracked
source path such as ``src/test/resources/sa3-dit-references`` -- doing so leaks
them into commits.
"""

import argparse
import copy
import json
import os
import sys

import numpy as np
import torch

# Block E serializer (same directory).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import safetensors_extractor as core

# Default output directory: a NON-tracked, build-output location under the
# engine/ml module's target/ tree, matching dump_t5gemma_reference.py's and
# dump_same_references.py's DEFAULT_OUT convention.
DEFAULT_OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "target", "test-classes", "sa3-dit-references"))

PROMPT = "a warm analog synth pad with slow filter sweeps"
SECONDS_TOTAL = 10.0


def load_local_prompt_config(config_path, t5_dir):
    """Load ``model_config.json`` and point the ``prompt`` conditioner at a local
    T5Gemma snapshot instead of the Hugging Face Hub ``repo_id``/``subfolder`` it
    ships with, so the encoder loads offline.
    """
    with open(config_path) as f:
        config = json.load(f)

    config = copy.deepcopy(config)
    for entry in config["model"]["conditioning"]["configs"]:
        if entry["id"] == "prompt":
            entry["config"].pop("repo_id", None)
            entry["config"].pop("subfolder", None)
            entry["config"]["model_path"] = t5_dir

    return config


def build_model(sa3_src, config, checkpoint_path):
    """Construct the real ``ConditionedDiffusionModelWrapper`` and load the
    released checkpoint's DiT, autoencoder pretransform and conditioner weights.
    """
    sys.path.insert(0, sa3_src)
    from stable_audio_3.loading_utils import load_diffusion_cond

    model = load_diffusion_cond(config, checkpoint_path, device="cpu")
    model.eval()
    return model


def zero_inpaint_conditioning(batch_size, io_channels, latent_len):
    """The all-zero ``inpaint_mask``/``inpaint_masked_input`` pair
    ``stable_audio_3.model.Model.generate`` supplies when no inpainting is
    requested (see its ``mask = torch.zeros(...)`` / ``inpaint_input =
    torch.zeros(...)`` fallback), needed because the released small-music model's
    ``local_add_cond_ids`` names them unconditionally.
    """
    mask = torch.zeros((batch_size, 1, latent_len))
    masked_input = torch.zeros((batch_size, io_channels, latent_len))
    return mask, masked_input


def dit_reference_stages(model, io_channels, latent_len, seed):
    """Run the real conditioner and a single DiT forward pass, returning the
    requested activations plus the two standalone conditioner tensors.

    Registers a forward-pre-hook on the first ``ContinuousTransformer`` layer to
    capture the transformer stack's input (after ``project_in`` and the
    memory-token/global-conditioning prepending that happens inside
    ``ContinuousTransformer.forward``) and a forward hook on the
    ``ContinuousTransformer`` itself to capture its output (after ``project_out``,
    before the DiT strips the prepended tokens and applies ``postprocess_conv``).
    Both match the states ``DiffusionTransformer`` captures: the input after its
    input projection and prepending, and the output after its output projection.
    """
    device = torch.device("cpu")

    conditioning_tensors = model.conditioner(
        [{"prompt": PROMPT, "seconds_total": SECONDS_TOTAL}], device)

    inpaint_mask, inpaint_masked_input = zero_inpaint_conditioning(1, io_channels, latent_len)
    conditioning_tensors["inpaint_mask"] = [inpaint_mask]
    conditioning_tensors["inpaint_masked_input"] = [inpaint_masked_input]

    cond_inputs = model.get_conditioning_inputs(conditioning_tensors)

    torch.manual_seed(seed)
    x = torch.randn(1, io_channels, latent_len)
    t = torch.full((1,), 0.5, dtype=torch.float32)
    padding_mask = torch.ones(1, latent_len, dtype=torch.bool)

    captured = {}

    def pre_hook(_module, args):
        captured["pre_transformer"] = args[0].detach().cpu().float()

    def post_hook(_module, _args, output):
        out = output[0] if isinstance(output, tuple) else output
        captured["post_transformer"] = out.detach().cpu().float()

    transformer = model.model.model.transformer
    handles = [
        transformer.layers[0].register_forward_pre_hook(pre_hook),
        transformer.register_forward_hook(post_hook),
    ]

    with torch.no_grad():
        output = model.model(
            x, t,
            cross_attn_cond=cond_inputs["cross_attn_cond"],
            cross_attn_mask=cond_inputs["cross_attn_mask"],
            global_cond=cond_inputs["global_cond"],
            local_add_cond=cond_inputs["local_add_cond"],
            padding_mask=padding_mask,
        )

    for h in handles:
        h.remove()

    prompt_conditioner = model.conditioner.conditioners["prompt"]
    seconds_conditioner = model.conditioner.conditioners["seconds_total"]
    seconds_linear = seconds_conditioner.embedder.embedding[1]

    stages = {
        "dit_x": x,
        "dit_t": t,
        "dit_cross_attn_cond": cond_inputs["cross_attn_cond"],
        "dit_global_cond": cond_inputs["global_cond"],
        "dit_padding_mask": padding_mask.float(),
        "dit_output": output,
        "dit_pre_transformer": captured["pre_transformer"],
        "dit_post_transformer": captured["post_transformer"],
    }
    stages = {name: tensor.detach().cpu().float().numpy() for name, tensor in stages.items()}

    conditioner_tensors = {
        "cond_padding_embedding": prompt_conditioner.padding_embedding,
        "cond_seconds_total_weight": seconds_linear.weight,
        "cond_seconds_total_bias": seconds_linear.bias,
    }
    conditioner_tensors = {
        name: tensor.detach().cpu().float().numpy() for name, tensor in conditioner_tensors.items()
    }

    return stages, conditioner_tensors


def main():
    parser = argparse.ArgumentParser(
        description="Dump a real Stable Audio 3 DiT reference activation for a Block D parity test")
    parser.add_argument("--sa3-src", required=True, help="Path to the stable-audio-3 source clone")
    parser.add_argument("--checkpoint", required=True,
                        help="Path to the released small-music model.safetensors")
    parser.add_argument("--config", required=True,
                        help="Path to the released small-music model_config.json")
    parser.add_argument("--t5-dir", required=True,
                        help="Local directory holding the t5gemma-b-b-ul2 snapshot "
                             "(config.json, tokenizer files, model.safetensors)")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="Output directory for the reference dumps. Defaults to the "
                             "git-ignored build-output path %(default)s. Do NOT point this "
                             "at a tracked source path -- these dumps are regenerable "
                             "artifacts and must never be committed.")
    parser.add_argument("--seed", type=int, default=1234, help="Torch seed for the fixed latent")
    parser.add_argument("--latent-len", type=int, default=64,
                        help="Latent sequence length (the DiT's temporal axis)")
    args = parser.parse_args()

    config = load_local_prompt_config(args.config, args.t5_dir)
    io_channels = config["model"]["diffusion"]["config"]["io_channels"]

    model = build_model(args.sa3_src, config, args.checkpoint)
    stages, conditioner_tensors = dit_reference_stages(model, io_channels, args.latent_len, args.seed)

    os.makedirs(args.out, exist_ok=True)
    # Write the stages and the conditioner tensors as ONE dump: they share the default shard
    # prefix, and dump_reference_activations clears stale same-prefix shards before writing, so a
    # second call would delete the first call's shards. Their keys are disjoint (dit_* vs cond_*).
    combined = dict(stages)
    combined.update(conditioner_tensors)
    written = core.dump_reference_activations(combined, args.out)

    shapes = {name: list(array.shape) for name, array in stages.items()}
    with open(os.path.join(args.out, "dit_shapes.json"), "w") as f:
        json.dump(shapes, f, indent=2, sort_keys=True)

    print("Wrote %d reference activations (%d shard(s)) to %s"
          % (len(combined), len(written), args.out))
    for name in sorted(stages):
        print("  %-20s %s" % (name, list(stages[name].shape)))
    for name in sorted(conditioner_tensors):
        print("  %-20s %s" % (name, list(conditioner_tensors[name].shape)))

    output_rms = float(np.sqrt(np.mean(np.square(stages["dit_output"]))))
    print("dit_output RMS=%.6e" % output_rms)


if __name__ == "__main__":
    main()
