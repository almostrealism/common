#!/usr/bin/env python3
"""
Regenerate ALL reference data with consistent float32 precision.
Also fixes the naming: hidden_states[24] is AFTER final norm, not after layer 23.
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
    print("  REGENERATING ALL REFERENCE DATA (float32)")
    print("=" * 70)

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print(f"\nLoading model {MODEL_NAME} (float32)...")
    # Load in float32 for consistent precision
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    model.eval()

    # Use token 9707 ("Hello")
    token_id = 9707

    print(f"\nRunning forward pass for token {token_id}...")

    # Capture layer outputs with hooks
    layer_outputs = {}

    def make_hook(name):
        def hook(module, input, output):
            if isinstance(output, tuple):
                layer_outputs[name] = output[0].detach()
            else:
                layer_outputs[name] = output.detach()
        return hook

    # Hook all 24 layers
    for i in range(24):
        model.model.layers[i].register_forward_hook(make_hook(f'layer_{i}'))

    # Hook final norm
    model.model.norm.register_forward_hook(make_hook('final_norm'))

    with torch.no_grad():
        input_ids = torch.tensor([[token_id]], dtype=torch.long)
        outputs = model(input_ids, output_hidden_states=True, return_dict=True)

    print("\n--- Saving Reference Data ---")

    # Save embeddings (hidden_states[0])
    embeddings = outputs.hidden_states[0].squeeze().cpu().numpy()
    save_bin(embeddings, "after_embeddings.bin")
    print_stats("Embeddings", outputs.hidden_states[0])

    # Save each layer output (BEFORE any subsequent norm)
    for i in range(24):
        layer_out = layer_outputs[f'layer_{i}'].squeeze().cpu().numpy()
        save_bin(layer_out, f"after_layer_{i}.bin")
        print_stats(f"Layer {i}", layer_outputs[f'layer_{i}'])

    # Save final norm output (this is what hidden_states[24] contains)
    final_norm_out = layer_outputs['final_norm'].squeeze().cpu().numpy()
    save_bin(final_norm_out, "after_final_norm.bin")
    print_stats("Final Norm", layer_outputs['final_norm'])

    # Save logits
    logits = outputs.logits.squeeze().cpu().numpy()
    save_bin(logits, "final_logits.bin")
    print_stats("Logits", outputs.logits)

    # Verify hidden_states vs hook outputs
    print("\n--- Verification ---")
    for i in range(24):
        # hidden_states[i+1] should match layer_outputs[f'layer_{i}']
        # But hidden_states includes residual, while hook captures layer output
        # Actually hidden_states[i+1] is the output AFTER layer i
        hs = outputs.hidden_states[i + 1].squeeze().float()
        hook = layer_outputs[f'layer_{i}'].squeeze().float()
        mae = (hs - hook).abs().mean().item()
        if mae > 0.0001:
            print(f"  Layer {i}: hidden_states[{i+1}] vs hook MAE = {mae:.6f}")

    # Check hidden_states[24] vs final_norm
    hs24 = outputs.hidden_states[24].squeeze().float()
    fn = layer_outputs['final_norm'].squeeze().float()
    mae = (hs24 - fn).abs().mean().item()
    print(f"  hidden_states[24] vs final_norm hook MAE = {mae:.10f}")

    # Also save layer 23 intermediates with proper naming
    print("\n--- Layer 23 Detailed Intermediates ---")

    # Re-run with hooks on layer 23 components
    layer23_intermediates = {}

    def make_component_hook(name):
        def hook(module, input, output):
            if isinstance(output, tuple):
                layer23_intermediates[name] = output[0].detach()
            else:
                layer23_intermediates[name] = output.detach()
        return hook

    layer23 = model.model.layers[23]
    layer23.input_layernorm.register_forward_hook(make_component_hook('input_norm'))
    layer23.self_attn.q_proj.register_forward_hook(make_component_hook('q_proj'))
    layer23.self_attn.k_proj.register_forward_hook(make_component_hook('k_proj'))
    layer23.self_attn.v_proj.register_forward_hook(make_component_hook('v_proj'))
    layer23.self_attn.o_proj.register_forward_hook(make_component_hook('o_proj'))
    layer23.self_attn.register_forward_hook(make_component_hook('attn'))
    layer23.post_attention_layernorm.register_forward_hook(make_component_hook('post_norm'))
    layer23.mlp.gate_proj.register_forward_hook(make_component_hook('gate_proj'))
    layer23.mlp.up_proj.register_forward_hook(make_component_hook('up_proj'))
    layer23.mlp.down_proj.register_forward_hook(make_component_hook('down_proj'))
    layer23.mlp.register_forward_hook(make_component_hook('mlp'))

    with torch.no_grad():
        outputs2 = model(input_ids, output_hidden_states=True, return_dict=True)

    for name, tensor in layer23_intermediates.items():
        data = tensor.squeeze().cpu().numpy()
        save_bin(data, f"layer23_{name}.bin")
        print_stats(f"Layer 23 {name}", tensor)

    print("\n" + "=" * 70)
    print("  Reference data regenerated successfully!")
    print("=" * 70)

if __name__ == "__main__":
    main()
