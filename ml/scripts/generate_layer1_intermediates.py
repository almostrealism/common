#!/usr/bin/env python3
"""
Generate layer 1 intermediate reference files for debugging.
"""

import os
import struct
import torch
from transformers import AutoModelForCausalLM

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"
OUTPUT_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs"

def save_bin(data, filename):
    """Save numpy array to binary file."""
    filepath = os.path.join(OUTPUT_DIR, filename)
    with open(filepath, 'wb') as f:
        f.write(struct.pack('i', len(data)))
        for val in data:
            f.write(struct.pack('f', float(val)))
    print(f"Saved {filename}: {len(data)} values")

def print_stats(name, tensor):
    """Print statistics for a tensor."""
    t = tensor.float().squeeze()
    print(f"  {name}: mean={t.mean():.6f}, std={t.std():.6f}, range=[{t.min():.4f}, {t.max():.4f}]")

def main():
    print("=" * 70)
    print("  GENERATING LAYER 1 INTERMEDIATE REFERENCE DATA")
    print("=" * 70)

    print(f"\nLoading model {MODEL_NAME} (float32)...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    model.eval()

    token_id = 9707

    # Register hooks on layer 1 components
    layer1_intermediates = {}

    def make_hook(name):
        def hook(module, input, output):
            if isinstance(output, tuple):
                layer1_intermediates[name] = output[0].detach()
            else:
                layer1_intermediates[name] = output.detach()
            # Also save input if it's a tensor
            if isinstance(input, tuple) and len(input) > 0 and isinstance(input[0], torch.Tensor):
                layer1_intermediates[f'{name}_input'] = input[0].detach()
        return hook

    layer1 = model.model.layers[1]
    layer1.input_layernorm.register_forward_hook(make_hook('input_norm'))
    layer1.self_attn.q_proj.register_forward_hook(make_hook('q_proj'))
    layer1.self_attn.k_proj.register_forward_hook(make_hook('k_proj'))
    layer1.self_attn.v_proj.register_forward_hook(make_hook('v_proj'))
    layer1.self_attn.o_proj.register_forward_hook(make_hook('o_proj'))
    layer1.self_attn.register_forward_hook(make_hook('attn'))
    layer1.post_attention_layernorm.register_forward_hook(make_hook('post_norm'))
    layer1.mlp.gate_proj.register_forward_hook(make_hook('gate_proj'))
    layer1.mlp.up_proj.register_forward_hook(make_hook('up_proj'))
    layer1.mlp.down_proj.register_forward_hook(make_hook('down_proj'))
    layer1.mlp.register_forward_hook(make_hook('mlp'))

    print(f"\nRunning forward pass for token {token_id}...")
    with torch.no_grad():
        input_ids = torch.tensor([[token_id]], dtype=torch.long)
        outputs = model(input_ids, output_hidden_states=True, return_dict=True)

    print("\n--- Layer 1 Intermediate Results ---")
    for name, tensor in sorted(layer1_intermediates.items()):
        data = tensor.squeeze().cpu().numpy()
        save_bin(data, f"layer1_{name}.bin")
        print_stats(f"Layer 1 {name}", tensor)

    # Also compare with after_layer_1.bin
    after_layer_1 = outputs.hidden_states[2].squeeze()  # index 2 is after layer 1
    print("\n--- Verification ---")
    print_stats("hidden_states[2] (after layer 1)", after_layer_1)

    print("\n" + "=" * 70)
    print("  Layer 1 intermediate data generated!")
    print("=" * 70)

if __name__ == "__main__":
    main()
