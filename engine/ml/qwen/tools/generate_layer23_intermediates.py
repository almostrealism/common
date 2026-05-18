#!/usr/bin/env python3
"""
Generate intermediate outputs within layer 23 to debug AR implementation.

This captures:
1. Input to layer 23 (output from layer 22)
2. After input_layernorm (before attention)
3. After attention (before residual add)
4. After attention residual (input to FFN path)
5. After post_attention_layernorm (before FFN)
6. After FFN (before residual add)
7. Final output of layer 23

This helps isolate whether the issue is in attention or FFN.
"""

import os
import struct
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def save_tensor(filepath, tensor):
    """Save tensor to binary file with size prefix."""
    flat = tensor.squeeze().float().cpu().numpy()
    with open(filepath, 'wb') as f:
        f.write(struct.pack('i', len(flat)))
        for val in flat:
            f.write(struct.pack('f', val))
    print(f"  Saved {filepath} ({len(flat)} values)")
    print(f"    First 5: {flat[:5]}")
    print(f"    Mean: {flat.mean():.6f}, Std: {flat.std():.6f}")


def main():
    model_name = "Qwen/Qwen2.5-0.5B-Instruct"
    output_dir = "/workspace/project/common/ml/qwen3_reference/layer23_intermediates"
    os.makedirs(output_dir, exist_ok=True)

    print(f"Loading model {model_name}...")
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.float32,  # Use float32 for better debugging
        device_map="cpu"
    )

    # Use same token as other tests: 9707 ("Hello")
    token_id = 9707
    input_ids = torch.tensor([[token_id]], dtype=torch.long)

    # Get layer 23 module (0-indexed, so layers.23 is the 24th layer)
    layer_idx = 23
    layer = model.model.layers[layer_idx]

    # Storage for intermediate values
    intermediates = {}

    # Get input to layer 23 by running model up to layer 22
    print(f"\nRunning model to get layer 23 input...")
    with torch.no_grad():
        # Get embeddings
        hidden_states = model.model.embed_tokens(input_ids)

        # Apply layers 0-22
        for i in range(layer_idx):
            layer_output = model.model.layers[i](
                hidden_states,
                attention_mask=None,
                position_ids=torch.tensor([[0]]),
                use_cache=False,
            )
            hidden_states = layer_output[0]

    intermediates['input'] = hidden_states.clone()
    print(f"\n1. Input to layer 23:")
    save_tensor(f"{output_dir}/layer23_input.bin", intermediates['input'])

    # Now manually run layer 23 with intermediate captures
    print(f"\n=== Running layer 23 with intermediate captures ===")

    with torch.no_grad():
        residual = hidden_states

        # Step 1: Input LayerNorm
        normed = layer.input_layernorm(hidden_states)
        intermediates['after_input_norm'] = normed.clone()
        print(f"\n2. After input_layernorm:")
        save_tensor(f"{output_dir}/after_input_norm.bin", normed)

        # Step 2: Self-attention
        # We need to call the attention module properly
        attn_output, attn_weights, _ = layer.self_attn(
            normed,
            attention_mask=None,
            position_ids=torch.tensor([[0]]),
            past_key_value=None,
            output_attentions=True,
            use_cache=False,
        )
        intermediates['attention_output'] = attn_output.clone()
        print(f"\n3. Attention output (before residual):")
        save_tensor(f"{output_dir}/attention_output.bin", attn_output)

        # Step 3: Residual add after attention
        hidden_states = residual + attn_output
        intermediates['after_attn_residual'] = hidden_states.clone()
        print(f"\n4. After attention residual:")
        save_tensor(f"{output_dir}/after_attn_residual.bin", hidden_states)

        # Save residual for FFN
        residual = hidden_states

        # Step 4: Post-attention LayerNorm
        normed_ffn = layer.post_attention_layernorm(hidden_states)
        intermediates['after_post_attn_norm'] = normed_ffn.clone()
        print(f"\n5. After post_attention_layernorm:")
        save_tensor(f"{output_dir}/after_post_attn_norm.bin", normed_ffn)

        # Step 5: MLP/FFN
        mlp_output = layer.mlp(normed_ffn)
        intermediates['mlp_output'] = mlp_output.clone()
        print(f"\n6. MLP output (before residual):")
        save_tensor(f"{output_dir}/mlp_output.bin", mlp_output)

        # Step 6: Residual add after FFN
        hidden_states = residual + mlp_output
        intermediates['final_output'] = hidden_states.clone()
        print(f"\n7. Final layer 23 output:")
        save_tensor(f"{output_dir}/layer23_output.bin", hidden_states)

    # Verify against standard forward pass
    print(f"\n=== Verification ===")
    with torch.no_grad():
        # Reset and run layer 23 through standard forward
        standard_output = model.model.layers[layer_idx](
            intermediates['input'],
            attention_mask=None,
            position_ids=torch.tensor([[0]]),
            use_cache=False,
        )[0]

        diff = (standard_output - intermediates['final_output']).abs().max().item()
        print(f"Max diff between manual and standard forward: {diff:.10f}")
        if diff < 1e-6:
            print("VERIFIED: Manual computation matches standard forward")
        else:
            print("WARNING: Mismatch detected!")

    # Also save attention breakdown
    print(f"\n=== Attention breakdown for debugging ===")
    with torch.no_grad():
        attn = layer.self_attn

        # Get Q, K, V projections
        q = attn.q_proj(intermediates['after_input_norm'])
        k = attn.k_proj(intermediates['after_input_norm'])
        v = attn.v_proj(intermediates['after_input_norm'])

        print(f"\nQ projection output:")
        save_tensor(f"{output_dir}/q_proj_output.bin", q)

        print(f"\nK projection output:")
        save_tensor(f"{output_dir}/k_proj_output.bin", k)

        print(f"\nV projection output:")
        save_tensor(f"{output_dir}/v_proj_output.bin", v)

        # Check for Q/K norms if they exist
        if hasattr(attn, 'q_norm') and attn.q_norm is not None:
            print("\nModel has Q norm")
        else:
            print("\nModel does NOT have Q norm")

        if hasattr(attn, 'k_norm') and attn.k_norm is not None:
            print("Model has K norm")
        else:
            print("Model does NOT have K norm")

    print(f"\n=== All intermediates saved to {output_dir} ===")


if __name__ == "__main__":
    main()
