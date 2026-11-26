"""
Extract CLAP2 weights from an OpenCLIP ``open_clip_model.safetensors`` checkpoint.

This is a thin config over the generalized core in ``safetensors_extractor.py``:
it declares the CLAP2 remap rules and delegates loading and protobuf
serialization to the core. The output is the StateDictionary that
``org.almostrealism.ml.clap.Clap2`` loads (see ``/pdsl/clap2.pdsl`` for the key
layout the towers read).

Besides renaming, the rules rearrange four things so that the native towers can
use the shared layers unchanged:

* rotary pairing -- CLAP2 rotates interleaved feature pairs ``(2i, 2i + 1)``;
  the framework rotates split-half pairs ``(i, i + D/2)``. The query and key
  rows of every qkv projection (weight and bias) and the text tower's per-head
  query/key norm scales are permuted so old feature ``2i`` becomes ``i`` and
  ``2i + 1`` becomes ``i + D/2``. Attention scores are invariant under one
  permutation applied to both queries and keys, so the model is unchanged.
* gate halves -- the audio feed-forward computes ``silu(x1) * x2`` (gate
  first); ``glu_feed_forward`` gates its second half, so the halves of every
  audio ``fc1`` (weight and bias) are swapped.
* register token -- the bias-free patch embedding gains one input column that
  holds the register token, so the encoder's one-hot first row embeds to it.
* shapes -- LayerScale vectors and pool latents become single rows, the
  log temperature a one-element tensor, and the audio head count (which no
  weight shape determines) is recorded as ``config.audio.heads``, a tensor with
  one element per head.

Usage::

    python extract_clap2_weights.py open_clip_model.safetensors out_dir --audio-heads 4

The audio head count is in the checkpoint's ``config.json``
(``audio_config.num_attention_heads``); ``--config`` reads it from there.
"""

import argparse
import json

import numpy as np

import safetensors_extractor as core

AUDIO_VIT = "audio.encoder.vit."


def interleaved_to_split_half(head_dim):
    """The row order that moves interleaved rotary pairs to split-half pairs within one head."""
    return np.concatenate([np.arange(0, head_dim, 2), np.arange(1, head_dim, 2)])


def permute_query_key_rows(array, width, heads):
    """Permutes the query and key thirds of a fused qkv weight ``[3W, ...]`` or bias ``[3W]``."""
    head_dim = width // heads
    order = interleaved_to_split_half(head_dim)
    rows = np.arange(3 * width)
    for section in (0, 1):
        for head in range(heads):
            start = section * width + head * head_dim
            rows[start:start + head_dim] = start + order
    return array[rows]


def swap_halves(array):
    """Swaps the two halves of a tensor along its first axis."""
    half = array.shape[0] // 2
    return np.concatenate([array[half:], array[:half]], axis=0)


def clap2_rules(audio_heads, text_heads):
    """The ordered remap rules from the OpenCLIP CLAP2 state to the native layout."""

    def audio_blocks(state):
        out = {}
        for key, array in state.items():
            if key.startswith(AUDIO_VIT + "blocks.") and key.endswith(("attn.qkv.weight", "attn.qkv.bias")):
                array = permute_query_key_rows(array, array.shape[0] // 3, audio_heads)
            elif key.startswith(AUDIO_VIT + "blocks.") and key.endswith(("mlp.fc1.weight", "mlp.fc1.bias")):
                array = swap_halves(array)
            elif key.startswith(AUDIO_VIT + "blocks.") and (key.endswith(".gamma_1") or key.endswith(".gamma_2")):
                array = array.reshape(1, -1)
            out[key] = array
        return out

    def text_blocks(state):
        out = {}
        for key, array in state.items():
            if key.startswith("text.blocks.") and key.endswith("attn.qkv.weight"):
                array = permute_query_key_rows(array, array.shape[0] // 3, text_heads)
            elif key.startswith("text.blocks.") and key.endswith(("attn.q_norm.weight", "attn.k_norm.weight")):
                array = array[interleaved_to_split_half(array.shape[0])]
            out[key] = array
        return out

    def patch_embedding(state):
        out = dict(state)
        projection = out.pop(AUDIO_VIT + "embeds.proj.weight")
        register = out.pop(AUDIO_VIT + "embeds.reg_token").reshape(-1, 1)
        out["audio.patch_embed.weight"] = np.concatenate([projection, register], axis=1)
        return out

    def shapes(state):
        out = dict(state)
        out["audio.pool.latent"] = out["audio.pool.latent"].reshape(1, -1)
        out["text.pool.latent"] = out["text.pool.latent"].reshape(1, -1)
        out["logit_scale"] = np.asarray(out["logit_scale"], dtype=np.float32).reshape(1)
        out["config.audio.heads"] = np.zeros(audio_heads, dtype=np.float32)
        return out

    return [
        core.drop("logit_bias"),
        audio_blocks,
        text_blocks,
        patch_embedding,
        core.rename_prefix(AUDIO_VIT + "attn_pool.", "audio.pool."),
        core.rename_prefix(AUDIO_VIT, "audio."),
        core.rename_prefix("audio.proj.0.", "audio.head.hidden."),
        core.rename_prefix("audio.proj.2.", "audio.head.output."),
        core.rename_prefix("text.pool.query", "text.pool.latent"),
        core.rename_prefix("text.text_projection.", "text.projection."),
        shapes,
    ]


def text_heads_of(state):
    """The text head count: the text width over the width of one per-head query norm."""
    width = state["text.token_embedding.weight"].shape[1]
    return width // state["text.blocks.0.attn.q_norm.weight"].shape[0]


def native_state(state, audio_heads):
    """Maps an OpenCLIP CLAP2 state (name -> ndarray) to the native StateDictionary layout."""
    return core.remap(state, clap2_rules(audio_heads, text_heads_of(state)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("safetensors", help="open_clip_model.safetensors of a CLAP2 checkpoint")
    parser.add_argument("out_dir", help="directory to write the StateDictionary shards to")
    parser.add_argument("--audio-heads", type=int, help="audio attention head count")
    parser.add_argument("--config", help="the checkpoint's config.json, to read the audio head count from")
    args = parser.parse_args()

    heads = args.audio_heads
    if heads is None and args.config:
        with open(args.config) as f:
            heads = json.load(f)["audio_config"]["num_attention_heads"]
    if heads is None:
        parser.error("one of --audio-heads or --config is required")

    state = core.load_safetensors(args.safetensors)
    mapped = native_state(state, heads)
    for key in sorted(mapped):
        print(f"  {key}: {list(mapped[key].shape)}")
    paths = core.write_state_dictionary(mapped, args.out_dir)
    print(f"Wrote {len(paths)} shard(s) to {args.out_dir}")


if __name__ == "__main__":
    main()
