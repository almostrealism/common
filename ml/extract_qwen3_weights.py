#!/usr/bin/env python3
"""
Extract Qwen3 model weights from Hugging Face checkpoint and convert to protobuf format.

This script:
1. Loads a Qwen3 model from Hugging Face
2. Extracts weights layer by layer
3. Converts to protobuf format compatible with AR framework's StateDictionary
4. Handles large models by splitting into multiple files

Usage:
    python extract_qwen3_weights.py <model_name> <output_dir>

Example:
    python extract_qwen3_weights.py Qwen/Qwen3-Instruct-2507-4B ./qwen3_weights
"""

import os
import sys
import argparse
import numpy as np
from pathlib import Path

try:
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
except ImportError:
    print("Error: Required packages not found.")
    print("Install with: pip install torch transformers")
    sys.exit(1)

# Import the protobuf definitions
# Assumes collections_pb2.py is in the same directory or on PYTHONPATH
try:
    sys.path.append('/workspace/project/rings/rings-ml/src/main/python')
    import collections_pb2 as collections
except ImportError:
    print("Error: collections_pb2.py not found")
    print("Make sure the protobuf definitions are available")
    sys.exit(1)

# Protobuf size limit (1GB to stay under 2GB limit with overhead)
PROTOBUF_SIZE_LIMIT = 1024 * 1024 * 1024

def estimate_protobuf_size(entry):
    """Estimate the serialized size of a CollectionLibraryEntry"""
    key_size = len(entry.key.encode('utf-8'))
    data_size = len(entry.collection.data_32) * 4 + len(entry.collection.data) * 8
    dims_size = len(entry.collection.traversal_policy.dims) * 4
    overhead = 100
    return key_size + data_size + dims_size + overhead

def write_protobuf_file(entries, file_path):
    """Serialize and write CollectionLibraryData to binary file"""
    library_data = collections.CollectionLibraryData()
    library_data.collections.extend(entries)

    with open(file_path, 'wb') as f:
        f.write(library_data.SerializeToString())

    print(f"Wrote {len(entries)} weight tensors to {file_path}")

def numpy_to_collection_data(array, key):
    """Convert numpy array to CollectionData protobuf"""
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

def map_qwen3_key(hf_key):
    """
    Map Hugging Face Qwen3 weight keys to AR framework format.

    Qwen3 HF format:
        model.layers.{i}.self_attn.q_proj.weight
        model.layers.{i}.self_attn.k_proj.weight
        model.layers.{i}.self_attn.v_proj.weight
        model.layers.{i}.self_attn.o_proj.weight
        model.layers.{i}.self_attn.q_norm.weight
        model.layers.{i}.self_attn.k_norm.weight
        model.layers.{i}.input_layernorm.weight
        model.layers.{i}.mlp.gate_proj.weight
        model.layers.{i}.mlp.up_proj.weight
        model.layers.{i}.mlp.down_proj.weight
        model.layers.{i}.post_attention_layernorm.weight
        model.embed_tokens.weight
        model.norm.weight
        lm_head.weight
    """
    # Direct mapping - keep HF format for now
    # We'll handle the mapping in Java StateDictionary loader
    return hf_key

def extract_weights(model, output_dir, layer_split=True):
    """
    Extract weights from Qwen3 model.

    Args:
        model: Hugging Face Qwen3 model
        output_dir: Output directory for protobuf files
        layer_split: If True, split weights by layer for better organization
    """
    os.makedirs(output_dir, exist_ok=True)

    state_dict = model.state_dict()
    print(f"Model has {len(state_dict)} weight tensors")

    if layer_split:
        # Extract embeddings and final norm separately
        extract_weight_group(state_dict, output_dir, "embeddings",
                           lambda k: k.startswith("model.embed_tokens") or
                                   k.startswith("model.norm") or
                                   k.startswith("lm_head"))

        # Extract each layer separately
        num_layers = len([k for k in state_dict.keys() if "model.layers.0." in k])
        if num_layers == 0:
            # Count layers
            layer_nums = set()
            for k in state_dict.keys():
                if "model.layers." in k:
                    layer_num = int(k.split("model.layers.")[1].split(".")[0])
                    layer_nums.add(layer_num)
            num_layers = len(layer_nums)

        print(f"Extracting {num_layers} transformer layers...")
        for i in range(num_layers):
            extract_weight_group(state_dict, output_dir, f"layer_{i:02d}",
                               lambda k, layer=i: f"model.layers.{layer}." in k)
    else:
        # Extract all weights in one batch (may create multiple files if too large)
        extract_weight_group(state_dict, output_dir, "all_weights",
                           lambda k: True)

    print(f"\nWeight extraction complete. Files written to {output_dir}")

def extract_weight_group(state_dict, output_dir, prefix, filter_fn):
    """Extract a group of weights matching the filter function"""
    current_entries = []
    current_size = 0
    file_index = 0

    for name, param in state_dict.items():
        if not filter_fn(name):
            continue

        # Map the key
        mapped_key = map_qwen3_key(name)

        # Create protobuf entry
        entry = collections.CollectionLibraryEntry()
        entry.key = mapped_key
        entry.collection.CopyFrom(numpy_to_collection_data(
            param.cpu().numpy(), mapped_key))

        # Check size and write file if needed
        entry_size = estimate_protobuf_size(entry)
        if current_size + entry_size > PROTOBUF_SIZE_LIMIT and current_entries:
            file_path = os.path.join(output_dir, f"{prefix}_{file_index}")
            write_protobuf_file(current_entries, file_path)
            current_entries = []
            current_size = 0
            file_index += 1

        current_entries.append(entry)
        current_size += entry_size

    # Write remaining entries
    if current_entries:
        if file_index > 0:
            file_path = os.path.join(output_dir, f"{prefix}_{file_index}")
        else:
            file_path = os.path.join(output_dir, prefix)
        write_protobuf_file(current_entries, file_path)

def extract_tokenizer_binary(tokenizer, output_dir):
    """
    Extract tokenizer to binary format compatible with Qwen3Tokenizer.java.

    Binary format:
    - int32: vocab_size
    - For each token (vocab_size entries):
      - float32: score (use token ID as score for simplicity)
      - int32: token_length
      - bytes: token_bytes (UTF-8)
    - int32: num_merges
    - For each merge:
      - int32: token1_id
      - int32: token2_id
      - int32: merged_id
    """
    import struct

    tokenizer_file = os.path.join(output_dir, "tokenizer.bin")

    # Get vocabulary
    vocab = tokenizer.get_vocab()
    vocab_size = len(vocab)

    # Sort by ID
    sorted_vocab = sorted(vocab.items(), key=lambda x: x[1])

    # Get merges from tokenizer
    merges = []
    if hasattr(tokenizer, 'get_vocab') and hasattr(tokenizer, 'backend_tokenizer'):
        try:
            # Try to access BPE merges
            model = tokenizer.backend_tokenizer.model
            if hasattr(model, 'get_vocab'):
                # Build merge rules from the tokenizer
                # For Qwen3, we can extract this from the tokenizer.json
                pass
        except:
            pass

    with open(tokenizer_file, 'wb') as f:
        # Write vocab size
        f.write(struct.pack('i', vocab_size))

        # Write vocabulary
        for token, idx in sorted_vocab:
            # Use token ID as score (higher ID = later in vocab = likely merged token)
            score = float(idx)
            f.write(struct.pack('f', score))

            # Write token bytes
            token_bytes = token.encode('utf-8')
            f.write(struct.pack('i', len(token_bytes)))
            f.write(token_bytes)

        # Write merges (empty for now - can be enhanced later)
        f.write(struct.pack('i', len(merges)))
        for token1, token2, merged in merges:
            f.write(struct.pack('iii', token1, token2, merged))

    print(f"Wrote binary tokenizer ({vocab_size} tokens) to {tokenizer_file}")

def extract_tokenizer(tokenizer, output_dir):
    """
    Extract tokenizer vocabulary and save in multiple formats.
    """
    # Save binary format for Java
    extract_tokenizer_binary(tokenizer, output_dir)

    # Also save text format for inspection
    vocab_file = os.path.join(output_dir, "vocab.txt")
    vocab = tokenizer.get_vocab()
    sorted_vocab = sorted(vocab.items(), key=lambda x: x[1])

    with open(vocab_file, 'w', encoding='utf-8') as f:
        for token, idx in sorted_vocab:
            token_repr = repr(token)[1:-1]
            f.write(f"{idx}\t{token_repr}\n")

    print(f"Wrote vocabulary ({len(vocab)} tokens) to {vocab_file}")

    # Save tokenizer config
    tokenizer.save_pretrained(output_dir)
    print(f"Saved tokenizer config to {output_dir}")

def main():
    parser = argparse.ArgumentParser(
        description="Extract Qwen3 weights to protobuf format")
    parser.add_argument("model_name", type=str,
                       help="Hugging Face model name (e.g., Qwen/Qwen3-Instruct-2507-4B)")
    parser.add_argument("output_dir", type=str,
                       help="Output directory for protobuf files")
    parser.add_argument("--no-layer-split", action="store_true",
                       help="Don't split weights by layer")
    parser.add_argument("--bf16", action="store_true",
                       help="Load model in bfloat16 precision")
    parser.add_argument("--fp16", action="store_true",
                       help="Load model in float16 precision")

    args = parser.parse_args()

    print(f"Loading Qwen3 model: {args.model_name}")

    # Determine dtype
    dtype = torch.float32
    if args.bf16:
        dtype = torch.bfloat16
        print("Using bfloat16 precision")
    elif args.fp16:
        dtype = torch.float16
        print("Using float16 precision")

    # Load model
    try:
        model = AutoModelForCausalLM.from_pretrained(
            args.model_name,
            torch_dtype=dtype,
            device_map="cpu",
            trust_remote_code=True
        )
        print(f"Model loaded successfully")
        print(f"Model config: {model.config}")
    except Exception as e:
        print(f"Error loading model: {e}")
        sys.exit(1)

    # Load tokenizer
    try:
        tokenizer = AutoTokenizer.from_pretrained(
            args.model_name,
            trust_remote_code=True
        )
        print(f"Tokenizer loaded successfully")
    except Exception as e:
        print(f"Warning: Could not load tokenizer: {e}")
        tokenizer = None

    # Extract weights
    extract_weights(model, args.output_dir, layer_split=not args.no_layer_split)

    # Extract tokenizer if available
    if tokenizer:
        extract_tokenizer(tokenizer, args.output_dir)

    print("\nDone!")

if __name__ == "__main__":
    main()
