#!/usr/bin/env python3
"""
Extract Moonbeam MIDI Foundation Model weights from HuggingFace and convert to protobuf format.

This script:
1. Loads the Moonbeam model from guozixunnicolas/moonbeam-midi-foundation-model
2. Extracts all weights (encoder, embedding, decoder) layer by layer
3. Converts to protobuf format compatible with AR framework's StateDictionary
4. Handles large tensors by splitting into multiple files

Usage:
    python extract_moonbeam_weights.py <model_name> <output_dir>

Example:
    python extract_moonbeam_weights.py guozixunnicolas/moonbeam-midi-foundation-model ./moonbeam_weights

Weight Key Mapping (HuggingFace -> AR StateDictionary):
    Keys are preserved 1:1 from the HuggingFace checkpoint.

    Transformer layer weights:
        model.layers.{i}.self_attn.q_proj.weight    -> model.layers.{i}.self_attn.q_proj.weight
        model.layers.{i}.self_attn.k_proj.weight    -> model.layers.{i}.self_attn.k_proj.weight
        model.layers.{i}.self_attn.v_proj.weight    -> model.layers.{i}.self_attn.v_proj.weight
        model.layers.{i}.self_attn.o_proj.weight    -> model.layers.{i}.self_attn.o_proj.weight
        model.layers.{i}.mlp.gate_proj.weight       -> model.layers.{i}.mlp.gate_proj.weight
        model.layers.{i}.mlp.up_proj.weight         -> model.layers.{i}.mlp.up_proj.weight
        model.layers.{i}.mlp.down_proj.weight       -> model.layers.{i}.mlp.down_proj.weight
        model.layers.{i}.input_layernorm.weight      -> model.layers.{i}.input_layernorm.weight
        model.layers.{i}.post_attention_layernorm.weight -> model.layers.{i}.post_attention_layernorm.weight
        model.norm.weight                            -> model.norm.weight

    Embedding weights (FME - Fundamental Music Embedding):
        onset_embedding.linear.weight               -> onset_embedding.linear.weight
        onset_embedding.linear.bias                  -> onset_embedding.linear.bias
        onset_embedding.translation_bias             -> onset_embedding.translation_bias
        duration_embedding.linear.weight             -> duration_embedding.linear.weight
        duration_embedding.linear.bias               -> duration_embedding.linear.bias
        duration_embedding.translation_bias          -> duration_embedding.translation_bias
        octave_embedding.linear.weight               -> octave_embedding.linear.weight
        octave_embedding.linear.bias                 -> octave_embedding.linear.bias
        octave_embedding.translation_bias            -> octave_embedding.translation_bias
        pitch_embedding.linear.weight                -> pitch_embedding.linear.weight
        pitch_embedding.linear.bias                  -> pitch_embedding.linear.bias
        pitch_embedding.translation_bias             -> pitch_embedding.translation_bias
        velocity_embedding.linear.weight             -> velocity_embedding.linear.weight
        velocity_embedding.linear.bias               -> velocity_embedding.linear.bias
        velocity_embedding.translation_bias          -> velocity_embedding.translation_bias

    Instrument embedding (standard nn.Embedding):
        instrument_embedding.weight                  -> instrument_embedding.weight

    Supplementary embedding (SOS/EOS):
        supplementary_embedding.weight               -> supplementary_embedding.weight
        supplementary_mlp.0.weight                   -> supplementary_mlp.0.weight
        supplementary_mlp.0.bias                     -> supplementary_mlp.0.bias
        supplementary_mlp.2.weight                   -> supplementary_mlp.2.weight
        supplementary_mlp.2.bias                     -> supplementary_mlp.2.bias

    GRU decoder weights:
        decoder.weight_ih_l0 .. decoder.weight_ih_l3 -> decoder.weight_ih_l{0-3}
        decoder.weight_hh_l0 .. decoder.weight_hh_l3 -> decoder.weight_hh_l{0-3}
        decoder.bias_ih_l0 .. decoder.bias_ih_l3     -> decoder.bias_ih_l{0-3}
        decoder.bias_hh_l0 .. decoder.bias_hh_l3     -> decoder.bias_hh_l{0-3}
        summary_projection.weight                    -> summary_projection.weight
        summary_projection.bias                      -> summary_projection.bias
        lm_head.weight                               -> lm_head.weight
        lm_head.bias                                 -> lm_head.bias
        decoder_embedding.weight                     -> decoder_embedding.weight
"""

import argparse
import numpy as np
import os
import sys
from pathlib import Path

try:
    import torch
    from transformers import AutoModel, AutoConfig
except ImportError:
    print("Error: Required packages not found.")
    print("Install with: pip install torch transformers")
    sys.exit(1)

# Import the protobuf definitions
try:
    sys.path.append('/workspace/project/rings/rings-ml/src/main/python')
    import collections_pb2 as collections
except ImportError:
    print("Error: collections_pb2.py not found")
    print("Make sure the protobuf definitions are available")
    sys.exit(1)

# Protobuf size limit (1GB to stay under 2GB limit with overhead)
PROTOBUF_SIZE_LIMIT = 1024 * 1024 * 1024

# Weight categories for organized extraction
EMBEDDING_KEYS = [
    "onset_embedding", "duration_embedding", "octave_embedding",
    "pitch_embedding", "instrument_embedding", "velocity_embedding",
    "supplementary_embedding", "supplementary_mlp"
]

DECODER_KEYS = [
    "decoder.", "summary_projection.", "lm_head.", "decoder_embedding."
]


def estimate_protobuf_size(entry):
    """Estimate the serialized size of a CollectionLibraryEntry."""
    key_size = len(entry.key.encode('utf-8'))
    data_size = len(entry.collection.data_32) * 4 + len(entry.collection.data) * 8
    dims_size = len(entry.collection.traversal_policy.dims) * 4
    overhead = 100
    return key_size + data_size + dims_size + overhead


def write_protobuf_file(entries, file_path):
    """Serialize and write CollectionLibraryData to binary file."""
    library_data = collections.CollectionLibraryData()
    library_data.collections.extend(entries)

    with open(file_path, 'wb') as f:
        f.write(library_data.SerializeToString())

    print(f"Wrote {len(entries)} weight tensors to {file_path}")


def numpy_to_collection_data(array, key):
    """Convert numpy array to CollectionData protobuf."""
    collection_data = collections.CollectionData()

    # Set traversal policy
    traversal_policy = collections.TraversalPolicyData()
    for dim in array.shape:
        traversal_policy.dims.append(int(dim))
    traversal_policy.traversal_axis = 0
    collection_data.traversal_policy.CopyFrom(traversal_policy)

    # Use float32 for efficiency
    flattened = array.flatten().astype(np.float32)
    collection_data.data_32.extend(flattened.tolist())

    return collection_data


def map_moonbeam_key(hf_key):
    """
    Map HuggingFace Moonbeam weight keys to AR framework format.
    Currently a direct 1:1 mapping -- kept as a function for future customization.
    """
    return hf_key


def is_embedding_key(key):
    """Check if a weight key belongs to the embedding group."""
    return any(key.startswith(prefix) for prefix in EMBEDDING_KEYS)


def is_decoder_key(key):
    """Check if a weight key belongs to the decoder group."""
    return any(key.startswith(prefix) for prefix in DECODER_KEYS)


def is_transformer_layer_key(key):
    """Check if a weight key belongs to a transformer layer."""
    return "model.layers." in key


def is_final_norm_key(key):
    """Check if a weight key is the final layer norm."""
    return key == "model.norm.weight"


def extract_weight_group(state_dict, output_dir, prefix, filter_fn):
    """Extract a group of weights matching the filter function."""
    current_entries = []
    current_size = 0
    file_index = 0

    for name, param in state_dict.items():
        if not filter_fn(name):
            continue

        mapped_key = map_moonbeam_key(name)

        entry = collections.CollectionLibraryEntry()
        entry.key = mapped_key
        entry.collection.CopyFrom(numpy_to_collection_data(
            param.cpu().numpy(), mapped_key))

        entry_size = estimate_protobuf_size(entry)
        if current_size + entry_size > PROTOBUF_SIZE_LIMIT and current_entries:
            file_path = os.path.join(output_dir, f"{prefix}_{file_index}")
            write_protobuf_file(current_entries, file_path)
            current_entries = []
            current_size = 0
            file_index += 1

        current_entries.append(entry)
        current_size += entry_size

    if current_entries:
        if file_index > 0:
            file_path = os.path.join(output_dir, f"{prefix}_{file_index}")
        else:
            file_path = os.path.join(output_dir, prefix)
        write_protobuf_file(current_entries, file_path)


def extract_weights(model, output_dir):
    """
    Extract all weights from the Moonbeam model, organized by component.

    Creates separate protobuf files for:
    - embeddings: FME weights, instrument embedding, supplementary embedding + MLP
    - decoder: GRU weights, summary projection, lm_head, decoder embedding
    - layer_XX: per-transformer-layer weights
    - final_norm: model.norm.weight
    """
    os.makedirs(output_dir, exist_ok=True)

    state_dict = model.state_dict()
    print(f"Model has {len(state_dict)} weight tensors")

    # Print all keys for documentation
    print("\nWeight key inventory:")
    for key in sorted(state_dict.keys()):
        shape = tuple(state_dict[key].shape)
        print(f"  {key}: {shape}")

    # Extract embedding weights
    extract_weight_group(state_dict, output_dir, "embeddings",
                         lambda k: is_embedding_key(k) or is_final_norm_key(k))

    # Extract decoder weights
    extract_weight_group(state_dict, output_dir, "decoder",
                         is_decoder_key)

    # Extract transformer layers
    layer_nums = set()
    for k in state_dict.keys():
        if "model.layers." in k:
            layer_num = int(k.split("model.layers.")[1].split(".")[0])
            layer_nums.add(layer_num)
    num_layers = len(layer_nums)

    print(f"\nExtracting {num_layers} transformer layers...")
    for i in range(num_layers):
        extract_weight_group(state_dict, output_dir, f"layer_{i:02d}",
                             lambda k, layer=i: f"model.layers.{layer}." in k)

    print(f"\nWeight extraction complete. Files written to {output_dir}")


def main():
    parser = argparse.ArgumentParser(
        description="Extract Moonbeam MIDI model weights to protobuf format")
    parser.add_argument("model_name", type=str,
                        help="HuggingFace model name "
                             "(e.g., guozixunnicolas/moonbeam-midi-foundation-model)")
    parser.add_argument("output_dir", type=str,
                        help="Output directory for protobuf files")
    parser.add_argument("--bf16", action="store_true",
                        help="Load model in bfloat16 precision")
    parser.add_argument("--fp16", action="store_true",
                        help="Load model in float16 precision")

    args = parser.parse_args()

    print(f"Loading Moonbeam model: {args.model_name}")

    dtype = torch.float32
    if args.bf16:
        dtype = torch.bfloat16
        print("Using bfloat16 precision")
    elif args.fp16:
        dtype = torch.float16
        print("Using float16 precision")

    try:
        model = AutoModel.from_pretrained(
            args.model_name,
            torch_dtype=dtype,
            device_map="cpu",
            trust_remote_code=True
        )
        print("Model loaded successfully")
        print(f"Model config: {model.config}")
    except Exception as e:
        print(f"Error loading model: {e}")
        sys.exit(1)

    extract_weights(model, args.output_dir)
    print("\nDone!")


if __name__ == "__main__":
    main()
