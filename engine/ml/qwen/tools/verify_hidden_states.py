#!/usr/bin/env python3
"""
Verify what hidden_states actually contains in HuggingFace Qwen2.5.

Key question: Is hidden_states[24] BEFORE or AFTER the final RMSNorm?

This script:
1. Prints model architecture
2. Shows what each hidden_states index represents
3. Manually applies final norm to hidden_states[24] and compares
4. Verifies if our reference data matches pre-norm or post-norm
"""

import os
import struct
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
import numpy as np


def load_reference_output(filename):
    """Load reference .bin file."""
    filepath = f"/workspace/project/common/ml/qwen3_reference/layer_outputs/{filename}"
    if not os.path.exists(filepath):
        return None

    with open(filepath, 'rb') as f:
        size = struct.unpack('i', f.read(4))[0]
        data = np.array([struct.unpack('f', f.read(4))[0] for _ in range(size)], dtype=np.float32)
    return data


def stats(arr):
    """Return statistics string."""
    return f"mean={arr.mean():.4f}, std={arr.std():.4f}, min={arr.min():.2f}, max={arr.max():.2f}"


def main():
    print("=" * 70)
    print("  Hidden States Verification for Qwen2.5-0.5B-Instruct")
    print("=" * 70)

    model_name = "Qwen/Qwen2.5-0.5B-Instruct"

    print(f"\n1. Loading model {model_name}...")
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.bfloat16,
        device_map="cpu"
    )

    # Print model structure
    print("\n2. Model Architecture:")
    print("-" * 50)
    print(f"   Model type: {type(model).__name__}")
    print(f"   Number of layers: {model.config.num_hidden_layers}")
    print(f"   Hidden size: {model.config.hidden_size}")

    # Show the model structure hierarchy
    print("\n   Structure hierarchy:")
    print(f"   - model.model.embed_tokens: {type(model.model.embed_tokens).__name__}")
    print(f"   - model.model.layers: {len(model.model.layers)} layers")
    print(f"   - model.model.norm: {type(model.model.norm).__name__}")
    print(f"   - model.lm_head: {type(model.lm_head).__name__}")

    # Check if final norm exists
    if hasattr(model.model, 'norm'):
        print(f"\n   Final norm weight shape: {model.model.norm.weight.shape}")
        final_norm_weight = model.model.norm.weight.float().cpu().numpy()
        print(f"   Final norm weight stats: {stats(final_norm_weight)}")

    print("-" * 50)

    # Run forward pass
    token_id = 9707
    print(f"\n3. Running forward pass for token {token_id} ('Hello')...")

    input_ids = torch.tensor([[token_id]], dtype=torch.long)

    with torch.no_grad():
        outputs = model(input_ids, output_hidden_states=True, return_dict=True)

    hidden_states = outputs.hidden_states

    print(f"\n   Number of hidden_states tensors: {len(hidden_states)}")

    # Show what each index represents
    print("\n4. Hidden States Index Mapping:")
    print("-" * 50)
    print("   Index | Description                  | Stats")
    print("   ------|------------------------------|------")

    for i, hs in enumerate(hidden_states):
        arr = hs.squeeze().float().cpu().numpy()
        if i == 0:
            desc = "After embeddings"
        elif i == len(hidden_states) - 1:
            desc = f"After layer {i-1} (LAST)"
        else:
            desc = f"After layer {i-1}"
        print(f"   {i:5d} | {desc:28s} | {stats(arr)}")

    # Key test: Is hidden_states[24] pre-norm or post-norm?
    print("\n5. Critical Test: Is hidden_states[24] pre-norm or post-norm?")
    print("-" * 50)

    last_hidden = hidden_states[-1].squeeze().float()  # hidden_states[24]
    print(f"   hidden_states[24] stats: {stats(last_hidden.numpy())}")

    # Manually apply final norm
    print("\n   Manually applying final RMSNorm to hidden_states[24]...")
    eps = model.config.rms_norm_eps  # Usually 1e-6

    # RMSNorm: x * weight / sqrt(mean(x^2) + eps)
    variance = last_hidden.pow(2).mean(-1, keepdim=True)
    normalized = last_hidden * torch.rsqrt(variance + eps)
    post_norm = normalized * model.model.norm.weight.float().cpu()

    print(f"   After manual RMSNorm stats: {stats(post_norm.numpy())}")

    # Compare with lm_head input (if we can trace it)
    print("\n   Comparing magnitudes:")
    print(f"   - hidden_states[24] std: {last_hidden.std():.4f}")
    print(f"   - post_norm std: {post_norm.std():.4f}")
    print(f"   - Ratio (post_norm/pre_norm): {post_norm.std() / last_hidden.std():.4f}")

    # Load our reference file and compare
    print("\n6. Comparing with saved reference files:")
    print("-" * 50)

    ref_layer_22 = load_reference_output("after_layer_22.bin")
    ref_layer_23 = load_reference_output("after_layer_23.bin")

    if ref_layer_22 is not None:
        print(f"   after_layer_22.bin: {stats(ref_layer_22)}")
        hs_22 = hidden_states[23].squeeze().float().cpu().numpy()
        diff = np.abs(ref_layer_22 - hs_22).mean()
        print(f"   vs hidden_states[23]: diff={diff:.6f}")

    if ref_layer_23 is not None:
        print(f"\n   after_layer_23.bin: {stats(ref_layer_23)}")

        # Compare with pre-norm (hidden_states[24])
        hs_24 = hidden_states[24].squeeze().float().cpu().numpy()
        diff_pre = np.abs(ref_layer_23 - hs_24).mean()
        print(f"   vs hidden_states[24] (pre-norm): diff={diff_pre:.6f}")

        # Compare with post-norm
        diff_post = np.abs(ref_layer_23 - post_norm.numpy()).mean()
        print(f"   vs post-norm output: diff={diff_post:.6f}")

        if diff_pre < diff_post:
            print(f"\n   CONCLUSION: after_layer_23.bin matches PRE-NORM (hidden_states[24])")
        else:
            print(f"\n   CONCLUSION: after_layer_23.bin matches POST-NORM!")

    # Key diagnostic: What values can layer 23 actually produce?
    print("\n7. Layer 23 Output Range Analysis:")
    print("-" * 50)

    layer_23_output = hidden_states[24].squeeze().float().cpu().numpy()
    print(f"   Layer 23 output range: [{layer_23_output.min():.2f}, {layer_23_output.max():.2f}]")
    print(f"   Layer 23 extreme values: min_idx={layer_23_output.argmin()}, max_idx={layer_23_output.argmax()}")

    if ref_layer_23 is not None:
        print(f"\n   Reference file range: [{ref_layer_23.min():.2f}, {ref_layer_23.max():.2f}]")
        print(f"   Reference extreme values: min_idx={ref_layer_23.argmin()}, max_idx={ref_layer_23.argmax()}")

        # Check if extreme values match
        if abs(layer_23_output.min() - ref_layer_23.min()) > 1.0:
            print(f"\n   WARNING: Min values differ significantly!")
            print(f"   Layer 23 min: {layer_23_output.min():.4f} at idx {layer_23_output.argmin()}")
            print(f"   Reference min: {ref_layer_23.min():.4f} at idx {ref_layer_23.argmin()}")

    # Layer delta analysis
    print("\n8. Layer Delta Analysis (output - input):")
    print("-" * 50)

    for layer_idx in [21, 22, 23]:
        layer_input = hidden_states[layer_idx].squeeze().float().cpu().numpy()
        layer_output = hidden_states[layer_idx + 1].squeeze().float().cpu().numpy()
        delta = layer_output - layer_input
        print(f"   Layer {layer_idx} delta: {stats(delta)}")

    print("\n" + "=" * 70)
    print("  Verification Complete")
    print("=" * 70)


if __name__ == "__main__":
    main()
