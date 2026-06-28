#!/usr/bin/env python3
"""
Dump real per-stage SAME autoencoder reference activations for the Block C2
``transformerResamplingBlock`` numerical-parity tests.

This is the *reference-dump entry point* described in the Phase 1 component plan
(Block C2 (d), Block E "Reference dumps"). It runs the **real** Stability AI
``stable_audio_3`` SAME PyTorch autoencoder on a fixed, seeded input and writes
per-stage activations using the Block E serializer
(:func:`safetensors_extractor.save_reference_output`). The Java
``TransformerResamplingBlock`` parity test loads these ``.bin`` files and asserts
the AR primitive reproduces each stage within tolerance.

It is deliberately torch-based (like ``extract_stable_audio_autoencoder.py``) and
imports the SAME model classes directly (encoder/decoder/bottleneck/pretransform)
so it does NOT pull in the text-conditioner stack (``transformers``/T5Gemma).

Determinism: the encoder runs with ``mask_noise = 0`` (its trained default), so it
is exactly reproducible. The decoder's trained config adds stochastic ``mask_noise``
and the SoftNorm bottleneck adds ``noise_regularize`` noise at inference; both are
disabled here so the dumped decoder reference is the deterministic computation the
AR primitive reproduces.

Prerequisites::

    pip install torch numpy safetensors einops torchaudio
    git clone https://github.com/Stability-AI/stable-audio-3   # the SAME source
    # gated weights (needs a Hugging Face token with access):
    #   huggingface_hub.hf_hub_download("stabilityai/SAME-S", "model.safetensors")
    #   huggingface_hub.hf_hub_download("stabilityai/SAME-S", "model_config.json")

Usage::

    python dump_same_references.py \
        --sa3-src /path/to/stable-audio-3 \
        --config  /path/to/SAME-S/model_config.json \
        --weights /path/to/SAME-S/model.safetensors \
        --out     ../src/test/resources/same-s-references \
        --seed 1234 --samples 24576
"""

import argparse
import json
import os
import sys

import numpy as np
import torch
from safetensors.torch import load_file
from einops import rearrange

# Block E serializer (same directory).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import safetensors_extractor as core


def build_same_autoencoder(sa3_src, config_path, weights_path):
    """Construct the real SAME autoencoder and load the gated weights.

    Imports the SAME model classes directly (not via the package factory) so the
    heavy text-conditioner dependencies are not required.
    """
    sys.path.insert(0, sa3_src)
    from stable_audio_3.models.autoencoders import (
        SAMEEncoder, SAMEDecoder, AudioAutoencoder)
    from stable_audio_3.models.bottleneck import SoftNormBottleneck
    from stable_audio_3.models.pretransforms import PatchedPretransform

    with open(config_path) as f:
        model_cfg = json.load(f)["model"]

    encoder = SAMEEncoder(**model_cfg["encoder"]["config"])
    decoder = SAMEDecoder(**model_cfg["decoder"]["config"])
    bottleneck = SoftNormBottleneck(**model_cfg["bottleneck"]["config"])
    pretransform = PatchedPretransform(**model_cfg["pretransform"]["config"])

    autoencoder = AudioAutoencoder(
        encoder, decoder,
        latent_dim=model_cfg["latent_dim"],
        downsampling_ratio=model_cfg["downsampling_ratio"],
        sample_rate=44100,
        io_channels=model_cfg["io_channels"],
        bottleneck=bottleneck,
        pretransform=pretransform,
        in_channels=model_cfg["encoder"]["config"]["in_channels"],
        out_channels=model_cfg["decoder"]["config"]["out_channels"],
    )

    state = load_file(weights_path)
    missing, unexpected = autoencoder.load_state_dict(state, strict=False)
    if missing or unexpected:
        print("WARNING: state_dict load missing=%d unexpected=%d"
              % (len(missing), len(unexpected)))
    autoencoder.eval()
    return autoencoder


def _capture(store):
    """Build a forward hook that records flattened input/output activations."""
    def make(name):
        def hook(_module, inputs, output):
            first_in = inputs[0] if isinstance(inputs, tuple) else inputs
            first_out = output[0] if isinstance(output, tuple) else output
            store[name + "_in"] = first_in.detach().cpu().float()
            store[name + "_out"] = first_out.detach().cpu().float()
        return hook
    return make


def same_reference_stages(autoencoder, audio):
    """Run the SAME encode+decode and collect per-stage reference activations.

    Returns ``dict[str, numpy.ndarray]`` covering: the raw input, the encoder
    resampling stage (block input/output, post-mapping, the pre-transformer
    "segment" tensor, and transformer layer 0 in/out), the encoder output and
    latent, and the corresponding decoder resampling stage.
    """
    encoder = autoencoder.encoder
    decoder = autoencoder.decoder
    enc_block = encoder.layers[0]
    dec_block = decoder.layers[-1]

    # Disable the stochastic augmentations so the reference is deterministic.
    enc_block.mask_noise = 0.0
    dec_block.mask_noise = 0.0
    autoencoder.bottleneck.noise_regularize = False

    store = {}
    make = _capture(store)
    # A hook on the encoder module itself captures encoder_out from the single
    # encode() pass below: encode() runs pretransform.encode() -> encoder() ->
    # bottleneck.encode(), so the encoder hook fires exactly once. This avoids a
    # second explicit encoder() forward (which would double the encoder compute and,
    # if stochastic noise were ever re-enabled, silently diverge from the encode pass).
    handles = [
        encoder.register_forward_hook(make("encoder")),
        enc_block.register_forward_hook(make("enc_resamp")),
        enc_block.mapping.register_forward_hook(make("enc_mapping")),
        enc_block.transformers[0].register_forward_hook(make("enc_layer0")),
        enc_block.transformers[len(enc_block.transformers) // 2 - 1]
            .register_forward_hook(make("enc_midstack")),
        enc_block.transformers[-1].register_forward_hook(make("enc_laststack")),
        dec_block.register_forward_hook(make("dec_resamp")),
        dec_block.transformers[0].register_forward_hook(make("dec_layer0")),
        dec_block.transformers[len(dec_block.transformers) // 2 - 1]
            .register_forward_hook(make("dec_midstack")),
        dec_block.transformers[len(dec_block.transformers) // 2]
            .register_forward_hook(make("dec_layer3")),
        dec_block.transformers[len(dec_block.transformers) // 2 + 1]
            .register_forward_hook(make("dec_layer4")),
        dec_block.transformers[-1].register_forward_hook(make("dec_laststack")),
        dec_block.mapping.register_forward_hook(make("dec_mapping")),
    ]

    with torch.no_grad():
        latent = autoencoder.encode(audio)
        autoencoder.decode(latent)

    encoder_out = store["encoder_out"]

    for h in handles:
        h.remove()

    # The encoder transformer runs on contiguous chunks; un-chunk layer-0 input
    # back to the (batch, seq, dim) "segment" tensor that enters the transformer
    # stack (this is the post-new_tokens, pre-attention activation).
    enc_layer0_in = store["enc_layer0_in"]
    enc_seg = rearrange(enc_layer0_in, "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    # Encoder post-first-half and post-stack (same un-chunk + midpoint-shift-undo as the decoder),
    # so the encoder transformer stack can be validated at ALL positions (the encoder full-block
    # parity only reads outputSeg=1 and never checks the stack at non-terminal positions).
    enc_mid = store["enc_midstack_out"]
    enc_midstack = rearrange(enc_mid, "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    enc_last = store["enc_laststack_out"]
    enc_poststack = rearrange(enc_last, "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    enc_shift = (enc_poststack.shape[1] - enc_seg.shape[1]) // 2
    enc_poststack = enc_poststack[:, enc_shift:enc_poststack.shape[1] - enc_shift, :]
    # Same un-chunking for the decoder, giving the post-segment (pre-transformer) tensor.
    dec_layer0_in = store["dec_layer0_in"]
    dec_seg = rearrange(dec_layer0_in, "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    # Post-first-half (pre-midpoint-shift) tensor: un-chunk the last first-half layer's output. No
    # crop is needed here because the midpoint shift has not been applied yet at this point.
    dec_mid = store["dec_midstack_out"]
    dec_midstack = rearrange(dec_mid, "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    # Post-stack (pre-extract) tensor: un-chunk the last transformer layer's output and undo the
    # midpoint-shift half-chunk padding (shift = (unchunked_len - seg_len) / 2; 0 when no shift).
    dec_last = store["dec_laststack_out"]
    dec_poststack = rearrange(dec_last, "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    dec_shift = (dec_poststack.shape[1] - dec_seg.shape[1]) // 2
    dec_poststack = dec_poststack[:, dec_shift:dec_poststack.shape[1] - dec_shift, :]

    stages = {
        "test_input": audio.detach().cpu().float(),
        "enc_resamp_input": store["enc_resamp_in"],
        "enc_after_mapping": store["enc_mapping_out"],
        "enc_seg_input": enc_seg,
        "enc_layer0_input": store["enc_layer0_in"],
        "enc_layer0_output": store["enc_layer0_out"],
        "enc_midstack": enc_midstack,
        "enc_poststack": enc_poststack,
        "enc_resamp_output": store["enc_resamp_out"],
        "encoder_output": encoder_out,
        "latent": latent.detach().cpu().float(),
        "dec_resamp_input": store["dec_resamp_in"],
        "dec_seg_input": dec_seg,
        "dec_layer0_input": store["dec_layer0_in"],
        "dec_layer0_output": store["dec_layer0_out"],
        "dec_midstack": dec_midstack,
        "dec_layer3_input": store["dec_layer3_in"],
        "dec_layer3_output": store["dec_layer3_out"],
        "dec_layer4_input": store["dec_layer4_in"],
        "dec_layer4_output": store["dec_layer4_out"],
        "dec_layer5_input": store["dec_laststack_in"],
        "dec_layer5_output": store["dec_laststack_out"],
        "dec_poststack": dec_poststack,
        "dec_premap": store["dec_mapping_in"],
        "dec_resamp_output": store["dec_resamp_out"],
    }
    return {k: v.numpy() if torch.is_tensor(v) else np.asarray(v)
            for k, v in stages.items()}


def check_decoder_sensitivity(autoencoder, audio, scales=(7.8e-5, 3.27e-3, 1e-2)):
    """Measure how strongly the SAME decoder's second-half layers amplify a midstack perturbation.

    The decoder resampling stack runs its first half (layers ``0..split-1``) on standard chunks, then
    its second half (layers ``split..depth-1``) on midpoint-shifted chunks. This probe perturbs the
    post-first-half "midstack" (the output of layer ``split-1``) by a small, seeded amount and measures
    the resulting change in the post-stack (the output of the last layer, un-chunked and shift-cropped).

    It documents that the second half is *inherently ill-conditioned* in the released weights: a
    perturbation far smaller than an honest cross-implementation FP32 rounding difference is amplified
    into a gross post-stack change. This is why the Java ``SAMEResamplingParityTest`` asserts per-stage
    and per-layer faithfulness (each layer fed the real upstream activation) but only *reports* the
    chained full-stack/full-block deltas — bit-wise parity of the amplifying composition is not
    achievable, and is not a defect of the AR implementation.
    """
    decoder = autoencoder.decoder
    enc_block = autoencoder.encoder.layers[0]
    dec_block = decoder.layers[-1]
    enc_block.mask_noise = 0.0
    dec_block.mask_noise = 0.0
    autoencoder.bottleneck.noise_regularize = False

    split = dec_block.transformer_depth // 2
    mid_idx = split - 1
    last_idx = len(dec_block.transformers) - 1

    store = {}
    pstate = {"perturb": None}

    def cap_last(_m, _i, o):
        store["last"] = (o[0] if isinstance(o, tuple) else o).detach().clone()

    def perturb_mid(_m, _i, o):
        out = o[0] if isinstance(o, tuple) else o
        if pstate["perturb"] is None:
            return None
        # TODO(review): returns a bare tensor even when o is a tuple; wrap back as
        # (out + noise,) + o[1:] if tuple outputs are ever used here.
        return out + pstate["perturb"].to(out.dtype)

    handles = [
        dec_block.transformers[last_idx].register_forward_hook(cap_last),
        dec_block.transformers[mid_idx].register_forward_hook(perturb_mid),
    ]

    with torch.no_grad():
        latent = autoencoder.encode(audio)
        autoencoder.decode(latent)
    # The last-layer output is chunked [(b nc), cc, d]; un-chunk it and crop off the half-chunk
    # (cc // 2) that the midpoint shift symmetrically padded onto each end of the sequence.
    mid_shape = store["last"].shape
    crop = mid_shape[1] // 2
    post_clean = rearrange(store["last"], "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
    post_clean_c = post_clean[:, crop:post_clean.shape[1] - crop, :]

    print("decoder sensitivity: midstack chunked shape %s, post-stack(crop) %s, crop=%d"
          % (tuple(mid_shape), tuple(post_clean_c.shape), crop))
    print("  clean post-stack absmax=%.4e" % post_clean_c.abs().max().item())
    for scale in scales:
        g = torch.Generator().manual_seed(777)
        noise = (torch.rand(mid_shape, generator=g) * 2 - 1) * scale
        pstate["perturb"] = noise
        with torch.no_grad():
            autoencoder.decode(latent)
        pstate["perturb"] = None
        post_pert = rearrange(store["last"], "(b nc) cc d -> b (nc cc) d", b=audio.shape[0])
        post_pert_c = post_pert[:, crop:post_pert.shape[1] - crop, :]
        diff = (post_pert_c - post_clean_c).abs()
        in_max = noise.abs().max().item()
        print("  perturb maxAbs=%.4e -> post-stack maxAbs=%.4e meanAbs=%.4e  amplification=%.1fx"
              % (in_max, diff.max().item(), diff.mean().item(), diff.max().item() / in_max))

    for h in handles:
        h.remove()


def main():
    parser = argparse.ArgumentParser(
        description="Dump real SAME per-stage reference activations for Block C2 parity tests")
    parser.add_argument("--sa3-src", required=True, help="Path to the stable-audio-3 source clone")
    parser.add_argument("--config", required=True, help="Path to SAME-S model_config.json")
    parser.add_argument("--weights", required=True, help="Path to SAME-S model.safetensors")
    parser.add_argument("--out", required=True, help="Output directory for reference .bin files")
    parser.add_argument("--seed", type=int, default=1234, help="Torch seed for the fixed input")
    parser.add_argument("--samples", type=int, default=24576,
                        help="Input length in samples (multiple of downsampling_ratio 4096)")
    parser.add_argument("--check-sensitivity", action="store_true",
                        help="Also print the decoder second-half midstack->post-stack amplification "
                             "(documents the inherent ill-conditioning the parity test relies on)")
    args = parser.parse_args()

    autoencoder = build_same_autoencoder(args.sa3_src, args.config, args.weights)

    torch.manual_seed(args.seed)
    audio = torch.randn(1, 2, args.samples)

    if args.check_sensitivity:
        check_decoder_sensitivity(autoencoder, audio)

    stages = same_reference_stages(autoencoder, audio)

    os.makedirs(args.out, exist_ok=True)
    written = core.dump_reference_activations(stages, args.out)

    shapes = {name: list(array.shape) for name, array in stages.items()}
    with open(os.path.join(args.out, "shapes.json"), "w") as f:
        json.dump(shapes, f, indent=2, sort_keys=True)

    meta = {
        "model": "stabilityai/SAME-S",
        "config": os.path.basename(args.config),
        "seed": args.seed,
        "samples": args.samples,
        "input_shape": list(audio.shape),
        "note": "Encoder is deterministic (mask_noise=0). Decoder mask_noise and "
                "SoftNorm noise_regularize were disabled for a deterministic reference.",
    }
    with open(os.path.join(args.out, "meta.json"), "w") as f:
        json.dump(meta, f, indent=2, sort_keys=True)

    print("Wrote %d reference activations to %s" % (len(written), args.out))
    for name in sorted(stages):
        print("  %-20s %s" % (name, list(stages[name].shape)))


if __name__ == "__main__":
    main()
