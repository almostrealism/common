#!/usr/bin/env python3
"""Verify PyTorch reference data for layer 23."""

import struct
import numpy as np
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
import os

REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs"
MODEL_PATH = "/workspace/project/common/ml/qwen3_weights"

def load_reference(filename):
    """Load binary reference file."""
    path = os.path.join(REFERENCE_DIR, filename)
    with open(path, 'rb') as f:
        size = struct.unpack('<i', f.read(4))[0]
        data = struct.unpack(f'<{size}f', f.read(size * 4))
    return np.array(data)

def main():
    print("=" * 70)
    print("  VERIFYING PYTORCH REFERENCE DATA FOR LAYER 23")
    print("=" * 70)

    # Load existing reference data
    print("\n--- Loading existing reference data ---")
    layer22_out = load_reference("after_layer_22.bin")
    layer23_out = load_reference("after_layer_23.bin")

    print(f"Layer 22 output: shape={layer22_out.shape}, mean={layer22_out.mean():.6f}, std={layer22_out.std():.6f}")
    print(f"Layer 22 output: range=[{layer22_out.min():.4f}, {layer22_out.max():.4f}]")
    print(f"Layer 23 output: shape={layer23_out.shape}, mean={layer23_out.mean():.6f}, std={layer23_out.std():.6f}")
    print(f"Layer 23 output: range=[{layer23_out.min():.4f}, {layer23_out.max():.4f}]")

    # Now regenerate layer 23 output from PyTorch
    print("\n--- Loading model from HuggingFace weights ---")
    try:
        model = AutoModelForCausalLM.from_pretrained(MODEL_PATH, trust_remote_code=True)
        model.eval()

        print(f"Model loaded. Config: {model.config}")

        # Get embeddings for token 9707 ("Hello")
        token_id = 9707

        with torch.no_grad():
            # Get embedding
            embed = model.model.embed_tokens.weight[token_id].unsqueeze(0).unsqueeze(0)
            print(f"\nEmbedding shape: {embed.shape}")
            print(f"Embedding: mean={embed.mean():.6f}, std={embed.std():.6f}")

            # Forward through layers 0-22
            hidden = embed
            for i in range(23):
                layer = model.model.layers[i]
                residual = hidden

                # Pre-attention norm
                hidden_norm = layer.input_layernorm(hidden)

                # Self attention
                attn_out, _, _ = layer.self_attn(
                    hidden_norm,
                    position_ids=torch.tensor([[0]]),
                    use_cache=False
                )
                hidden = residual + attn_out

                # Post-attention norm
                residual = hidden
                hidden_norm = layer.post_attention_layernorm(hidden)

                # MLP
                mlp_out = layer.mlp(hidden_norm)
                hidden = residual + mlp_out

                if i == 21:
                    print(f"\nAfter layer 22 (new): shape={hidden.shape}")
                    print(f"  mean={hidden.mean():.6f}, std={hidden.std():.6f}")
                    print(f"  range=[{hidden.min():.4f}, {hidden.max():.4f}]")
                    new_layer22 = hidden.squeeze().cpu().numpy()

                if i == 22:
                    print(f"\nAfter layer 23 (new): shape={hidden.shape}")
                    print(f"  mean={hidden.mean():.6f}, std={hidden.std():.6f}")
                    print(f"  range=[{hidden.min():.4f}, {hidden.max():.4f}]")
                    new_layer23 = hidden.squeeze().cpu().numpy()

            # Compare with existing reference
            print("\n--- Comparison with existing reference ---")
            mae22 = np.abs(new_layer22 - layer22_out).mean()
            mae23 = np.abs(new_layer23 - layer23_out).mean()
            print(f"Layer 22 MAE (new vs existing): {mae22:.6f}")
            print(f"Layer 23 MAE (new vs existing): {mae23:.6f}")

            if mae22 > 0.001:
                print("WARNING: Layer 22 reference may be stale or regenerated with different params!")
            if mae23 > 0.001:
                print("WARNING: Layer 23 reference may be stale or regenerated with different params!")

    except Exception as e:
        print(f"Error loading model: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    main()
