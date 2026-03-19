#!/usr/bin/env python3
"""
Extract Moonbeam MIDI Foundation Model weights from a .pt checkpoint to protobuf format.

This script:
1. Loads the Moonbeam .pt checkpoint (training checkpoint with model_state_dict)
2. Strips the 'module.' prefix from DDP-wrapped keys
3. Maps checkpoint key names to AR framework expected names
4. Converts all weights to float32 protobuf format for StateDictionary
5. Creates zero-filled bias tensors where the checkpoint lacks them

Usage:
    python extract_moonbeam_weights.py <checkpoint_path> <output_dir>

Example:
    python extract_moonbeam_weights.py ../moonbeam-weights/moonbeam_309M.pt ../moonbeam-weights-protobuf

Weight Key Mapping (Checkpoint -> AR StateDictionary):

    Transformer layer weights (strip 'module.' prefix):
        module.model.layers.{i}.self_attn.q_proj.weight    -> model.layers.{i}.self_attn.q_proj.weight
        module.model.layers.{i}.self_attn.k_proj.weight    -> model.layers.{i}.self_attn.k_proj.weight
        module.model.layers.{i}.self_attn.v_proj.weight    -> model.layers.{i}.self_attn.v_proj.weight
        module.model.layers.{i}.self_attn.o_proj.weight    -> model.layers.{i}.self_attn.o_proj.weight
        module.model.layers.{i}.mlp.gate_proj.weight       -> model.layers.{i}.mlp.gate_proj.weight
        module.model.layers.{i}.mlp.up_proj.weight         -> model.layers.{i}.mlp.up_proj.weight
        module.model.layers.{i}.mlp.down_proj.weight       -> model.layers.{i}.mlp.down_proj.weight
        module.model.layers.{i}.input_layernorm.weight      -> model.layers.{i}.input_layernorm.weight
        module.model.layers.{i}.post_attention_layernorm.weight -> model.layers.{i}.post_attention_layernorm.weight
        module.model.norm.weight                            -> model.norm.weight

    Embedding weights (strip prefix + rename):
        module.model.onset_embedding.linear_fme.weight      -> onset_embedding.linear.weight
        module.model.onset_embedding.linear_fme.bias        -> onset_embedding.linear.bias
        module.model.onset_embedding.translation_bias       -> onset_embedding.translation_bias
        module.model.dur_embedding.linear_fme.weight        -> duration_embedding.linear.weight
        module.model.dur_embedding.linear_fme.bias          -> duration_embedding.linear.bias
        module.model.dur_embedding.translation_bias         -> duration_embedding.translation_bias
        module.model.octave_embedding.linear_fme.*          -> octave_embedding.linear.*
        module.model.pitch_embedding.linear_fme.*           -> pitch_embedding.linear.*
        module.model.velocity_embedding.linear_fme.*        -> velocity_embedding.linear.*
        module.model.instrument_embedding.embedding.weight  -> instrument_embedding.weight
        module.model.supplementary_embedding.weight         -> supplementary_embedding.weight
        module.model.supplementary_MLP.0.*                  -> supplementary_mlp.0.*
        module.model.supplementary_MLP.2.*                  -> supplementary_mlp.2.*

    GRU decoder weights:
        module.decoder.gru.weight_ih_l{l}                  -> decoder.weight_ih_l{l}
        module.decoder.gru.weight_hh_l{l}                  -> decoder.weight_hh_l{l}
        module.decoder.gru.bias_ih_l{l}                    -> decoder.bias_ih_l{l}
        module.decoder.gru.bias_hh_l{l}                    -> decoder.bias_hh_l{l}
        module.decoder.fc_out.weight                        -> decoder.fc_out.weight
        module.decoder.fc_out.bias                          -> decoder.fc_out.bias
        module.summary_projection.weight                    -> summary_projection.weight
        (zero-filled)                                       -> summary_projection.bias
        module.lm_head.weight                               -> lm_head.weight
        (zero-filled)                                       -> lm_head.bias
        module.decoder_embedding.weight                     -> decoder_embedding.weight
"""

import argparse
import numpy as np
import os
import sys
from pathlib import Path

try:
    import torch
except ImportError:
    print("Error: torch not found. Install with: pip install torch")
    sys.exit(1)

try:
    sys.path.append('/workspace/project/ringsdesktop/audio-ml/stability')
    import collections_pb2 as collections
except ImportError:
    print("Error: collections_pb2.py not found")
    print("Make sure the protobuf definitions are available")
    sys.exit(1)

PROTOBUF_SIZE_LIMIT = 1024 * 1024 * 1024

KEY_MAP = {
    # Embedding renames: strip 'module.model.' prefix, rename linear_fme -> linear,
    # dur_embedding -> duration_embedding, instrument_embedding.embedding -> instrument_embedding,
    # supplementary_MLP -> supplementary_mlp
    "module.model.dur_embedding.linear_fme.weight": "duration_embedding.linear.weight",
    "module.model.dur_embedding.linear_fme.bias": "duration_embedding.linear.bias",
    "module.model.dur_embedding.translation_bias": "duration_embedding.translation_bias",
    "module.model.onset_embedding.linear_fme.weight": "onset_embedding.linear.weight",
    "module.model.onset_embedding.linear_fme.bias": "onset_embedding.linear.bias",
    "module.model.onset_embedding.translation_bias": "onset_embedding.translation_bias",
    "module.model.octave_embedding.linear_fme.weight": "octave_embedding.linear.weight",
    "module.model.octave_embedding.linear_fme.bias": "octave_embedding.linear.bias",
    "module.model.octave_embedding.translation_bias": "octave_embedding.translation_bias",
    "module.model.pitch_embedding.linear_fme.weight": "pitch_embedding.linear.weight",
    "module.model.pitch_embedding.linear_fme.bias": "pitch_embedding.linear.bias",
    "module.model.pitch_embedding.translation_bias": "pitch_embedding.translation_bias",
    "module.model.velocity_embedding.linear_fme.weight": "velocity_embedding.linear.weight",
    "module.model.velocity_embedding.linear_fme.bias": "velocity_embedding.linear.bias",
    "module.model.velocity_embedding.translation_bias": "velocity_embedding.translation_bias",
    "module.model.instrument_embedding.embedding.weight": "instrument_embedding.weight",
    "module.model.supplementary_embedding.weight": "supplementary_embedding.weight",
    "module.model.supplementary_MLP.0.weight": "supplementary_mlp.0.weight",
    "module.model.supplementary_MLP.0.bias": "supplementary_mlp.0.bias",
    "module.model.supplementary_MLP.2.weight": "supplementary_mlp.2.weight",
    "module.model.supplementary_MLP.2.bias": "supplementary_mlp.2.bias",
}

EMBEDDING_PREFIXES = [
    "onset_embedding", "duration_embedding", "octave_embedding",
    "pitch_embedding", "instrument_embedding", "velocity_embedding",
    "supplementary_embedding", "supplementary_mlp"
]

DECODER_PREFIXES = [
    "decoder.", "summary_projection.", "lm_head.", "decoder_embedding."
]


def map_key(checkpoint_key):
    """Map a checkpoint key to the AR framework key name."""
    if checkpoint_key in KEY_MAP:
        return KEY_MAP[checkpoint_key]

    key = checkpoint_key

    # Strip 'module.' prefix from DDP wrapping
    if key.startswith("module."):
        key = key[len("module."):]

    # GRU decoder: decoder.gru.weight_ih_l0 -> decoder.weight_ih_l0
    if key.startswith("decoder.gru."):
        key = "decoder." + key[len("decoder.gru."):]

    # decoder.fc_out stays as decoder.fc_out
    # summary_projection, lm_head, decoder_embedding stay as-is after module. strip

    return key


def numpy_to_collection_data(array):
    """Convert numpy array to CollectionData protobuf."""
    collection_data = collections.CollectionData()
    traversal_policy = collections.TraversalPolicyData()
    for dim in array.shape:
        traversal_policy.dims.append(int(dim))
    traversal_policy.traversal_axis = 0
    collection_data.traversal_policy.CopyFrom(traversal_policy)
    flattened = array.flatten().astype(np.float32)
    collection_data.data_32.extend(flattened.tolist())
    return collection_data


def make_entry(key, array):
    """Create a CollectionLibraryEntry from a key and numpy array."""
    entry = collections.CollectionLibraryEntry()
    entry.key = key
    entry.collection.CopyFrom(numpy_to_collection_data(array))
    return entry


def estimate_size(entry):
    """Estimate serialized size of a CollectionLibraryEntry."""
    key_size = len(entry.key.encode('utf-8'))
    data_size = len(entry.collection.data_32) * 4
    dims_size = len(entry.collection.traversal_policy.dims) * 4
    return key_size + data_size + dims_size + 100


def write_protobuf_file(entries, file_path):
    """Write entries to a protobuf file."""
    library_data = collections.CollectionLibraryData()
    library_data.collections.extend(entries)
    with open(file_path, 'wb') as f:
        f.write(library_data.SerializeToString())
    total_params = sum(
        len(e.collection.data_32) for e in entries
    )
    print(f"  Wrote {len(entries)} tensors ({total_params:,} params) to {file_path}")


def write_group(entries, output_dir, prefix):
    """Write a group of entries, splitting files if needed."""
    if not entries:
        return

    current = []
    current_size = 0
    file_index = 0

    for entry in entries:
        entry_size = estimate_size(entry)
        if current_size + entry_size > PROTOBUF_SIZE_LIMIT and current:
            write_protobuf_file(current, os.path.join(output_dir, f"{prefix}_{file_index}"))
            current = []
            current_size = 0
            file_index += 1
        current.append(entry)
        current_size += entry_size

    if current:
        suffix = f"_{file_index}" if file_index > 0 else ""
        write_protobuf_file(current, os.path.join(output_dir, f"{prefix}{suffix}"))


def is_embedding_key(key):
    """Check if mapped key belongs to embedding group."""
    return any(key.startswith(p) for p in EMBEDDING_PREFIXES)


def is_decoder_key(key):
    """Check if mapped key belongs to decoder group."""
    return any(key.startswith(p) for p in DECODER_PREFIXES)


def is_layer_key(key):
    """Check if mapped key belongs to a transformer layer."""
    return key.startswith("model.layers.")


def is_final_norm_key(key):
    """Check if mapped key is the final layer norm."""
    return key == "model.norm.weight"


def extract_weights(mapped_state, output_dir):
    """Extract all weights organized by component."""
    os.makedirs(output_dir, exist_ok=True)

    print(f"\nMapped {len(mapped_state)} weight tensors")
    print("\nMapped key inventory:")
    for key in sorted(mapped_state.keys()):
        shape = mapped_state[key].shape
        print(f"  {key}: {list(shape)}")

    # Group entries
    embedding_entries = []
    decoder_entries = []
    layer_entries = {}
    norm_entries = []

    for key in sorted(mapped_state.keys()):
        array = mapped_state[key]
        entry = make_entry(key, array)

        if is_embedding_key(key):
            embedding_entries.append(entry)
        elif is_decoder_key(key):
            decoder_entries.append(entry)
        elif is_layer_key(key):
            layer_num = int(key.split("model.layers.")[1].split(".")[0])
            layer_entries.setdefault(layer_num, []).append(entry)
        elif is_final_norm_key(key):
            norm_entries.append(entry)
        else:
            print(f"  WARNING: unmapped key {key}")

    print(f"\nWriting protobuf files...")

    write_group(embedding_entries, output_dir, "embeddings")
    write_group(norm_entries, output_dir, "final_norm")
    write_group(decoder_entries, output_dir, "decoder")

    for layer_num in sorted(layer_entries.keys()):
        write_group(layer_entries[layer_num], output_dir, f"layer_{layer_num:02d}")

    print(f"\nWeight extraction complete. Files written to {output_dir}")


def main():
    parser = argparse.ArgumentParser(
        description="Extract Moonbeam MIDI model weights from .pt checkpoint to protobuf")
    parser.add_argument("checkpoint_path", type=str,
                        help="Path to .pt checkpoint file")
    parser.add_argument("output_dir", type=str,
                        help="Output directory for protobuf files")

    args = parser.parse_args()

    print(f"Loading checkpoint: {args.checkpoint_path}")
    ckpt = torch.load(args.checkpoint_path, map_location='cpu', weights_only=False)

    if 'model_state_dict' in ckpt:
        raw_state = ckpt['model_state_dict']
        print(f"Epoch: {ckpt.get('epoch')}, Step: {ckpt.get('step')}")
    elif 'state_dict' in ckpt:
        raw_state = ckpt['state_dict']
    else:
        raw_state = ckpt

    print(f"Checkpoint has {len(raw_state)} weight tensors")

    # Map keys and convert to float32 numpy
    mapped_state = {}
    for ckpt_key, tensor in raw_state.items():
        ar_key = map_key(ckpt_key)
        array = tensor.float().cpu().numpy()
        mapped_state[ar_key] = array
        if ckpt_key != ar_key:
            print(f"  {ckpt_key} -> {ar_key} {list(array.shape)}")

    # Add zero-filled biases where checkpoint lacks them
    if "summary_projection.weight" in mapped_state and "summary_projection.bias" not in mapped_state:
        decoder_hidden = mapped_state["summary_projection.weight"].shape[0]
        mapped_state["summary_projection.bias"] = np.zeros(decoder_hidden, dtype=np.float32)
        print(f"  Created zero summary_projection.bias [{decoder_hidden}]")

    if "lm_head.weight" in mapped_state and "lm_head.bias" not in mapped_state:
        vocab_size = mapped_state["lm_head.weight"].shape[0]
        mapped_state["lm_head.bias"] = np.zeros(vocab_size, dtype=np.float32)
        print(f"  Created zero lm_head.bias [{vocab_size}]")

    extract_weights(mapped_state, args.output_dir)
    print("\nDone!")


if __name__ == "__main__":
    main()
