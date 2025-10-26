#!/usr/bin/env python3
"""
Generate Qwen3 transformer block reference data for validation testing.

This script:
1. Loads a Qwen2.5-0.5B-Instruct model from Hugging Face
2. Extracts a single transformer layer
3. Generates test inputs matching the Java implementation
4. Runs through PyTorch transformer layer
5. Saves inputs, weights, and expected outputs to protobuf

Usage:
    python generate_qwen3_reference.py <output_dir>

Example:
    python generate_qwen3_reference.py ./qwen3_reference
"""

import sys
import os
import numpy as np
import torch
import torch.nn.functional as F

# Import protobuf definitions
sys.path.append('/workspace/project/rings/rings-ml/src/main/python')
import collections_pb2 as collections

from transformers import AutoModelForCausalLM

def tensor_to_protobuf(tensor):
    """Convert PyTorch tensor to protobuf CollectionData"""
    # Convert to numpy and flatten
    data = tensor.detach().cpu().numpy().flatten().astype(np.float32)

    # Create traversal policy
    policy = collections.TraversalPolicyData()
    for dim in tensor.shape:
        policy.dims.append(int(dim))
    policy.traversal_axis = len(tensor.shape) - 1

    # Create collection data
    collection = collections.CollectionData()
    collection.traversal_policy.CopyFrom(policy)
    collection.data_32.extend(data.tolist())

    return collection

def generate_qwen3_transformer_block_reference(output_dir):
    """Generate reference data for Qwen3 transformer block testing"""
    reference_dir = f'{output_dir}/qwen3_transformer_block'

    # Test dimensions matching Qwen2.5-0.5B-Instruct
    batch_size = 1
    seq_len = 8  # Small for focused testing
    dim = 896
    hidden_dim = 4864
    heads = 14
    kv_heads = 2
    head_dim = dim // heads  # 64

    # Set random seed for reproducibility
    torch.manual_seed(42)
    np.random.seed(42)

    print(f"Generating Qwen3 transformer block reference with dimensions:")
    print(f"  batch={batch_size}, seq={seq_len}, dim={dim}")
    print(f"  heads={heads}, kv_heads={kv_heads}, head_dim={head_dim}")
    print(f"  hidden_dim={hidden_dim}")

    # Load actual Qwen2.5-0.5B-Instruct model
    print("\nLoading Qwen2.5-0.5B-Instruct model from HuggingFace...")
    model = AutoModelForCausalLM.from_pretrained(
        "Qwen/Qwen2.5-0.5B-Instruct",
        torch_dtype=torch.float32,
        device_map="cpu",
        trust_remote_code=True
    )
    print("Model loaded successfully")

    # Extract first transformer layer
    transformer_layer = model.model.layers[0]

    # Generate test input: (batch, seq, dim)
    input_tensor = torch.randn(batch_size, seq_len, dim) * 0.02

    # Generate position IDs for RoPE
    position_ids = torch.arange(seq_len, dtype=torch.long).unsqueeze(0)

    # Compute position embeddings using the model's rotary_emb
    rotary_emb = model.model.rotary_emb
    cos, sin = rotary_emb(input_tensor, position_ids)
    position_embeddings = (cos, sin)

    # Run through the actual transformer layer
    print("\nRunning transformer layer forward pass...")
    with torch.no_grad():
        # Pass position_embeddings to the layer
        output_tensor = transformer_layer(
            input_tensor,
            position_embeddings=position_embeddings,
            use_cache=False
        )
        # Qwen2 layers return a tuple (hidden_states,) when use_cache=False
        if isinstance(output_tensor, tuple):
            output_tensor = output_tensor[0]

    print(f"Input shape: {input_tensor.shape}")
    print(f"Output shape: {output_tensor.shape}")

    # ALSO compute intermediate outputs step-by-step for debugging
    print("\nComputing intermediate outputs step-by-step...")
    with torch.no_grad():
        # Step 1: Input normalization
        residual = input_tensor
        hidden_states = transformer_layer.input_layernorm(input_tensor)
        print(f"  After input norm: sum={hidden_states.sum():.6f}, max={hidden_states.abs().max():.6f}")

        # Step 1a: Q/K/V projections (for debugging)
        q_proj = transformer_layer.self_attn.q_proj(hidden_states)
        k_proj = transformer_layer.self_attn.k_proj(hidden_states)
        v_proj = transformer_layer.self_attn.v_proj(hidden_states)
        print(f"  After Q projection: sum={q_proj.sum():.6f}, max={q_proj.abs().max():.6f}")
        print(f"  After K projection: sum={k_proj.sum():.6f}, max={k_proj.abs().max():.6f}")
        print(f"  After V projection: sum={v_proj.sum():.6f}, max={v_proj.abs().max():.6f}")

        # Step 2: Attention
        attn_output = transformer_layer.self_attn(
            hidden_states,
            attention_mask=None,
            position_embeddings=position_embeddings,
        )[0]  # Get just the output, not the cache
        print(f"  After attention: sum={attn_output.sum():.6f}, max={attn_output.abs().max():.6f}")

        # Step 3: Attention residual
        hidden_states = residual + attn_output
        print(f"  After attn residual: sum={hidden_states.sum():.6f}, max={hidden_states.abs().max():.6f}")

        # Step 4: FFN normalization
        residual = hidden_states
        hidden_states = transformer_layer.post_attention_layernorm(hidden_states)
        print(f"  After FFN norm: sum={hidden_states.sum():.6f}, max={hidden_states.abs().max():.6f}")

        # Step 5: FFN
        ffn_output = transformer_layer.mlp(hidden_states)
        print(f"  After FFN: sum={ffn_output.sum():.6f}, max={ffn_output.abs().max():.6f}")

        # Step 6: FFN residual
        final_output = residual + ffn_output
        print(f"  After FFN residual: sum={final_output.sum():.6f}, max={final_output.abs().max():.6f}")

        # Verify this matches the full layer output
        diff = (final_output - output_tensor).abs().max()
        print(f"  Difference from layer output: {diff:.10f}")

        # Save intermediate outputs
        intermediate_outputs = {
            'after_input_norm': transformer_layer.input_layernorm(input_tensor),
            'after_q_proj': q_proj,
            'after_k_proj': k_proj,
            'after_v_proj': v_proj,
            'after_attention': attn_output,
            'after_attention_residual': residual + attn_output,
            'after_ffn_norm': transformer_layer.post_attention_layernorm(residual + attn_output),
            'after_ffn': ffn_output,
            'after_ffn_residual': final_output,
        }

    # Extract weights from the transformer layer
    state_dict = transformer_layer.state_dict()

    print("\nTransformer layer state_dict keys:")
    for key in state_dict.keys():
        print(f"  {key}: {state_dict[key].shape}")

    # Create CollectionLibraryData
    library = collections.CollectionLibraryData()

    tensors = {
        # Input and output
        'input': input_tensor,
        'expected_output': output_tensor,

        # Intermediate outputs for debugging
        'intermediate.after_input_norm': intermediate_outputs['after_input_norm'],
        'intermediate.after_q_proj': intermediate_outputs['after_q_proj'],
        'intermediate.after_k_proj': intermediate_outputs['after_k_proj'],
        'intermediate.after_v_proj': intermediate_outputs['after_v_proj'],
        'intermediate.after_attention': intermediate_outputs['after_attention'],
        'intermediate.after_attention_residual': intermediate_outputs['after_attention_residual'],
        'intermediate.after_ffn_norm': intermediate_outputs['after_ffn_norm'],
        'intermediate.after_ffn': intermediate_outputs['after_ffn'],
        'intermediate.after_ffn_residual': intermediate_outputs['after_ffn_residual'],

        # Position IDs and embeddings
        'position_ids': position_ids.float(),
        'position_cos': cos,
        'position_sin': sin,

        # Attention weights
        'self_attn.q_proj.weight': state_dict['self_attn.q_proj.weight'],
        'self_attn.k_proj.weight': state_dict['self_attn.k_proj.weight'],
        'self_attn.v_proj.weight': state_dict['self_attn.v_proj.weight'],
        'self_attn.o_proj.weight': state_dict['self_attn.o_proj.weight'],

        # Attention biases
        'self_attn.q_proj.bias': state_dict['self_attn.q_proj.bias'],
        'self_attn.k_proj.bias': state_dict['self_attn.k_proj.bias'],
        'self_attn.v_proj.bias': state_dict['self_attn.v_proj.bias'],

        # Optional: QK-Norm weights (only in certain versions)
        # Note: Qwen2.5-0.5B-Instruct doesn't have QK-Norm, only biases

        # Attention layer norm
        'input_layernorm.weight': state_dict['input_layernorm.weight'],

        # FFN weights
        'mlp.gate_proj.weight': state_dict['mlp.gate_proj.weight'],
        'mlp.up_proj.weight': state_dict['mlp.up_proj.weight'],
        'mlp.down_proj.weight': state_dict['mlp.down_proj.weight'],

        # FFN layer norm
        'post_attention_layernorm.weight': state_dict['post_attention_layernorm.weight'],

        # Test configuration
        'test_config': torch.tensor([batch_size, seq_len, dim, hidden_dim, heads, kv_heads, head_dim], dtype=torch.float32)
    }

    # Add tensors to library
    for name, tensor in tensors.items():
        entry = library.collections.add()
        entry.key = name
        entry.collection.CopyFrom(tensor_to_protobuf(tensor))

    # Save to protobuf file
    os.makedirs(reference_dir, exist_ok=True)
    with open(f'{reference_dir}/weights_0', 'wb') as f:
        f.write(library.SerializeToString())

    print(f"\nQwen3 transformer block reference saved to {reference_dir}/weights_0")
    print(f"Generated {len(tensors)} tensors for transformer block validation")
    print(f"Input total: {input_tensor.sum().item()}")
    print(f"Output total: {output_tensor.sum().item()}")

    # Print some weight shapes for verification
    print(f"\nWeight shapes:")
    print(f"  Q proj: {state_dict['self_attn.q_proj.weight'].shape}")
    print(f"  K proj: {state_dict['self_attn.k_proj.weight'].shape}")
    print(f"  V proj: {state_dict['self_attn.v_proj.weight'].shape}")
    print(f"  O proj: {state_dict['self_attn.o_proj.weight'].shape}")
    print(f"  Gate proj: {state_dict['mlp.gate_proj.weight'].shape}")
    print(f"  Up proj: {state_dict['mlp.up_proj.weight'].shape}")
    print(f"  Down proj: {state_dict['mlp.down_proj.weight'].shape}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python generate_qwen3_reference.py <output_dir>")
        sys.exit(1)

    output_dir = sys.argv[1]
    generate_qwen3_transformer_block_reference(output_dir)
    print("\nDone!")
