#!/usr/bin/env python3
"""
Export PyTorch intermediate outputs for layer 23 to compare with AR.
Uses hooks to capture intermediate activations.
"""

import os
import struct
import torch
from transformers import AutoModelForCausalLM
import torch.nn.functional as F

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"
REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs"

def save_bin(data, filename):
    """Save numpy array to binary file."""
    filepath = os.path.join(REFERENCE_DIR, filename)
    with open(filepath, 'wb') as f:
        f.write(struct.pack('i', len(data)))
        for val in data:
            f.write(struct.pack('f', float(val)))
    return filepath

def print_stats(name, tensor):
    """Print statistics for a tensor."""
    t = tensor.float().squeeze()
    print(f"{name}: mean={t.mean():.6f}, std={t.std():.6f}, range=[{t.min():.4f}, {t.max():.4f}]")

# Dictionary to store intermediate activations
intermediates = {}

def main():
    print("=" * 70)
    print("  EXPORTING PYTORCH LAYER 23 INTERMEDIATES")
    print("=" * 70)

    print(f"\nLoading model {MODEL_NAME}...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME)
    model.eval()

    # Get layer 23
    layer = model.model.layers[23]

    # Register hooks to capture intermediate activations
    def make_hook(name):
        def hook(module, input, output):
            if isinstance(output, tuple):
                intermediates[name] = output[0].detach()
            else:
                intermediates[name] = output.detach()
        return hook

    # Hook into layer 23 components
    layer.input_layernorm.register_forward_hook(make_hook('input_norm'))
    layer.self_attn.q_proj.register_forward_hook(make_hook('q_proj'))
    layer.self_attn.k_proj.register_forward_hook(make_hook('k_proj'))
    layer.self_attn.v_proj.register_forward_hook(make_hook('v_proj'))
    layer.self_attn.o_proj.register_forward_hook(make_hook('o_proj'))
    layer.self_attn.register_forward_hook(make_hook('attn_out'))
    layer.post_attention_layernorm.register_forward_hook(make_hook('post_norm'))
    layer.mlp.gate_proj.register_forward_hook(make_hook('gate_proj'))
    layer.mlp.up_proj.register_forward_hook(make_hook('up_proj'))
    layer.mlp.down_proj.register_forward_hook(make_hook('down_proj'))
    layer.mlp.register_forward_hook(make_hook('mlp_out'))
    layer.register_forward_hook(make_hook('layer_out'))

    # Use token 9707 ("Hello")
    token_id = 9707

    print(f"\nRunning forward pass for token {token_id}...")

    with torch.no_grad():
        input_ids = torch.tensor([[token_id]], dtype=torch.long)
        outputs = model(input_ids, output_hidden_states=True, return_dict=True)

        # hidden_states[i] is output after layer i-1 (0 = embeddings)
        layer23_input = outputs.hidden_states[23]  # Input to layer 23 = output of layer 22
        layer23_output = outputs.hidden_states[24]  # Output of layer 23

    print("\n--- Layer 23 Intermediate Values ---\n")

    # Print and save all intermediates
    print_stats("INPUT (after layer 22)", layer23_input)
    save_bin(layer23_input.squeeze().cpu().numpy(), "layer23_input.bin")

    for name, tensor in intermediates.items():
        print_stats(f"{name}", tensor)
        save_bin(tensor.squeeze().cpu().numpy(), f"layer23_{name}.bin")

    print_stats("OUTPUT (layer 23 final)", layer23_output)
    save_bin(layer23_output.squeeze().cpu().numpy(), "layer23_output.bin")

    # Calculate residual contributions
    print("\n--- Residual Analysis ---\n")
    attn_out = intermediates['attn_out'].squeeze()
    mlp_out = intermediates['mlp_out'].squeeze()
    layer_in = layer23_input.squeeze()
    layer_out_expected = layer23_output.squeeze()

    after_attn_residual = layer_in + attn_out
    final_with_residual = after_attn_residual + mlp_out

    print_stats("Layer input", layer_in)
    print_stats("Attention output (o_proj)", attn_out)
    print_stats("After attention residual (input + attn)", after_attn_residual)
    print_stats("MLP output (down_proj)", mlp_out)
    print_stats("Final (after_attn + mlp)", final_with_residual)
    print_stats("Expected layer output", layer_out_expected)

    diff = (final_with_residual - layer_out_expected).abs().mean()
    print(f"\nMAE between computed and expected output: {diff:.10f}")

    # Compare with existing reference data
    print("\n--- Comparison with Existing Reference ---\n")

    # Load existing after_layer_22.bin
    with open(os.path.join(REFERENCE_DIR, "after_layer_22.bin"), 'rb') as f:
        size = struct.unpack('<i', f.read(4))[0]
        ref_input = torch.tensor(struct.unpack(f'<{size}f', f.read(size * 4)))

    # Load existing after_layer_23.bin
    with open(os.path.join(REFERENCE_DIR, "after_layer_23.bin"), 'rb') as f:
        size = struct.unpack('<i', f.read(4))[0]
        ref_output = torch.tensor(struct.unpack(f'<{size}f', f.read(size * 4)))

    mae_input = (layer_in.float().cpu() - ref_input).abs().mean()
    mae_output = (layer_out_expected.float().cpu() - ref_output).abs().mean()

    print(f"MAE vs existing after_layer_22.bin: {mae_input:.10f}")
    print(f"MAE vs existing after_layer_23.bin: {mae_output:.10f}")

    if mae_input > 0.001 or mae_output > 0.001:
        print("\nWARNING: Existing reference data may be inconsistent!")
    else:
        print("\nReference data is consistent.")

    print("\n" + "=" * 70)
    print("  All intermediates exported to", REFERENCE_DIR)
    print("=" * 70)

if __name__ == "__main__":
    main()
