#!/usr/bin/env python3
"""
Dump real T5Gemma prompt-conditioner reference activations for the Block D2
(T5Gemma text encoder) numerical-parity test.

This mirrors ``dump_same_references.py``'s role for the SAME autoencoder: it
runs the **real** ``google/t5gemma-b-b-ul2`` encoder exactly as
``stable_audio_3.models.conditioners.T5GemmaConditioner`` does (``config.
is_encoder_decoder = False``, ``T5GemmaEncoderModel``, tokenizer
``padding="max_length"``, ``truncation=True``) on a fixed prompt, then writes
per-stage activations with the Block E serializer
(:func:`safetensors_extractor.save_reference_output`) for a Java parity test to
compare against.

The released Stable Audio 3 checkpoints do not embed T5Gemma's weights; the
encoder is downloaded separately from the ``t5gemma-b-b-ul2`` subfolder of a
released SA3 repo (e.g. ``stabilityai/stable-audio-3-small-music``), while the
learned padding embedding used by ``padding_mode="learned"`` IS part of the SA3
checkpoint, under ``conditioner.conditioners.prompt.padding_embedding``.

Prerequisites::

    pip install torch transformers sentencepiece safetensors
    huggingface_hub.snapshot_download(
        "stabilityai/stable-audio-3-small-music",
        allow_patterns=["t5gemma-b-b-ul2/*"])

Usage::

    python dump_t5gemma_reference.py \
        --t5-dir /path/to/t5gemma-b-b-ul2 \
        --checkpoint /path/to/stable-audio-3-small-music/model.safetensors \
        --prompt "a warm analog synth pad with slow filter sweeps" \
        --max-length 256

``--out`` defaults to the git-ignored build-output directory
``engine/ml/target/test-classes/t5gemma-references``. These dumps are
regenerable artifacts: never write them into a tracked source path such as
``src/test/resources/t5gemma-references`` -- doing so leaks them into commits.
"""

import argparse
import json
import os
import sys

import numpy as np
import torch
from safetensors import safe_open

# Block E serializer (same directory).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import safetensors_extractor as core

# Default output directory: a NON-tracked, build-output location under the
# engine/ml module's target/ tree, matching dump_same_references.py's
# DEFAULT_OUT convention.
DEFAULT_OUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "target", "test-classes", "t5gemma-references"))


def load_padding_embedding(checkpoint_path):
    """Read ``conditioner.conditioners.prompt.padding_embedding`` from a released
    Stable Audio 3 checkpoint (the learned per-dimension vector substituted at
    padded positions when ``padding_mode="learned"``).
    """
    with safe_open(checkpoint_path, framework="pt") as f:
        return f.get_tensor("conditioner.conditioners.prompt.padding_embedding").float()


def apply_learned_padding(embeddings, attention_mask, padding_embedding):
    """Reproduce ``Conditioner.apply_padding`` for ``padding_mode="learned"``:
    positions where ``attention_mask`` is 0 are replaced by ``padding_embedding``.
    """
    mask_expanded = attention_mask.unsqueeze(-1).bool()
    padding = padding_embedding.unsqueeze(0).unsqueeze(0).expand_as(embeddings)
    return torch.where(mask_expanded, embeddings, padding)


def run_t5gemma_reference(t5_dir, prompt, max_length):
    """Run the real T5Gemma encoder on ``prompt`` exactly as
    ``T5GemmaConditioner.forward`` does, and return the tokenizer output plus
    the per-stage hidden states (embeddings, layer 0, layer 1, final).
    """
    from transformers import AutoConfig, AutoTokenizer, T5GemmaEncoderModel

    tokenizer = AutoTokenizer.from_pretrained(t5_dir)
    config = AutoConfig.from_pretrained(t5_dir)
    config.is_encoder_decoder = False
    model = T5GemmaEncoderModel.from_pretrained(t5_dir, config=config)
    model.eval()

    encoded = tokenizer(
        [prompt], truncation=True, max_length=max_length,
        padding="max_length", return_tensors="pt")
    input_ids = encoded["input_ids"]
    attention_mask = encoded["attention_mask"].to(torch.bool)

    with torch.no_grad():
        output = model(
            input_ids=input_ids, attention_mask=attention_mask,
            output_hidden_states=True)

    hidden_states = output.hidden_states
    return {
        "input_ids": input_ids,
        "attention_mask": attention_mask,
        "embeddings": hidden_states[0],
        "hidden_layer0": hidden_states[1],
        "hidden_layer1": hidden_states[2],
        "last_hidden_state": output.last_hidden_state,
    }


def main():
    parser = argparse.ArgumentParser(
        description="Dump real T5Gemma prompt-conditioner reference activations "
                     "for the Block D2 parity test")
    parser.add_argument("--t5-dir", required=True,
                        help="Local directory holding the t5gemma-b-b-ul2 snapshot "
                             "(config.json, tokenizer files, model.safetensors)")
    parser.add_argument("--checkpoint", required=True,
                        help="Path to a released SA3 model.safetensors, used only "
                             "to read conditioner.conditioners.prompt.padding_embedding")
    parser.add_argument("--prompt", default="a warm analog synth pad with slow filter sweeps",
                        help="Prompt to encode")
    parser.add_argument("--max-length", type=int, default=256,
                        help="Tokenizer max_length (matches the released conditioner config)")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="Output directory for the reference dumps. Defaults to the "
                             "git-ignored build-output path %(default)s. Do NOT point this "
                             "at a tracked source path -- these dumps are regenerable "
                             "artifacts and must never be committed.")
    args = parser.parse_args()

    reference = run_t5gemma_reference(args.t5_dir, args.prompt, args.max_length)
    padding_embedding = load_padding_embedding(args.checkpoint)
    prompt_output = apply_learned_padding(
        reference["last_hidden_state"], reference["attention_mask"], padding_embedding)

    stages = {
        "t5_input_ids": reference["input_ids"].float(),
        "t5_attention_mask": reference["attention_mask"].float(),
        "t5_embeddings": reference["embeddings"],
        "t5_hidden_layer0": reference["hidden_layer0"],
        "t5_hidden_layer1": reference["hidden_layer1"],
        "t5_last_hidden_state": reference["last_hidden_state"],
        "t5_prompt_output": prompt_output,
    }
    stages = {name: tensor.detach().cpu().float().numpy() for name, tensor in stages.items()}

    os.makedirs(args.out, exist_ok=True)
    written = core.dump_reference_activations(stages, args.out)

    shapes = {name: list(array.shape) for name, array in stages.items()}
    with open(os.path.join(args.out, "t5_shapes.json"), "w") as f:
        json.dump(shapes, f, indent=2, sort_keys=True)

    print("Wrote %d reference activations to %s" % (len(written), args.out))
    for name in sorted(stages):
        print("  %-24s %s" % (name, list(stages[name].shape)))


if __name__ == "__main__":
    main()
