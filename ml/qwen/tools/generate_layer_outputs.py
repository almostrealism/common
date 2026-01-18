#!/usr/bin/env python3
"""
Generate intermediate layer outputs from PyTorch Qwen2.5-0.5B-Instruct model.

This script runs the model and saves hidden states after specific layers to compare
with AR implementation.
"""

import os
import struct
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def main():
    model_name = "Qwen/Qwen2.5-0.5B-Instruct"
    weights_dir = "/workspace/project/common/ml/qwen3_weights"
    output_dir = "/workspace/project/common/ml/qwen3_reference/layer_outputs"

    os.makedirs(output_dir, exist_ok=True)

    print(f"Loading model {model_name}...")
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.bfloat16,
        device_map="cpu"
    )

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    # Use the same token as in our tests: 9707 ("Hello")
    token_id = 9707

    print(f"\nRunning forward pass for token {token_id} ('Hello')...")

    # Create input tensor
    input_ids = torch.tensor([[token_id]], dtype=torch.long)

    # Run forward pass with output_hidden_states=True
    with torch.no_grad():
        outputs = model(input_ids, output_hidden_states=True, return_dict=True)

    # hidden_states is a tuple: (embeddings, layer_0_out, layer_1_out, ..., layer_23_out)
    hidden_states = outputs.hidden_states

    print(f"\nNumber of hidden state tensors: {len(hidden_states)}")
    print(f"  0: Embeddings")
    for i in range(1, len(hidden_states)):
        print(f"  {i}: After layer {i-1}")

    # Save outputs after all layers
    layers_to_save = list(range(len(hidden_states)))  # All layers: embeddings, 0-23

    for layer_idx in layers_to_save:
        if layer_idx >= len(hidden_states):
            continue

        hidden = hidden_states[layer_idx]

        # hidden shape: (batch=1, seq_len=1, hidden_size=896)
        hidden_flat = hidden.squeeze(0).squeeze(0).float().cpu().numpy()

        # Save to binary file
        if layer_idx == 0:
            filename = f"after_embeddings.bin"
            layer_name = "embeddings"
        else:
            filename = f"after_layer_{layer_idx-1}.bin"
            layer_name = f"layer {layer_idx-1}"

        filepath = os.path.join(output_dir, filename)

        with open(filepath, 'wb') as f:
            # Write shape
            f.write(struct.pack('i', len(hidden_flat)))
            # Write data
            for val in hidden_flat:
                f.write(struct.pack('f', val))

        print(f"Saved {layer_name} output to {filename}")
        print(f"  Shape: {hidden_flat.shape}")
        print(f"  First 5 values: {hidden_flat[:5]}")
        print(f"  Mean: {hidden_flat.mean():.6f}, Std: {hidden_flat.std():.6f}")

    # Also save the final logits for reference
    logits = outputs.logits.squeeze(0).squeeze(0).float().cpu().numpy()
    filepath = os.path.join(output_dir, "final_logits.bin")
    with open(filepath, 'wb') as f:
        f.write(struct.pack('i', len(logits)))
        for val in logits:
            f.write(struct.pack('f', val))

    print(f"\nSaved final logits to final_logits.bin")
    print(f"  Shape: {logits.shape}")
    print(f"  Top 5 token IDs: {logits.argsort()[-5:][::-1]}")
    print(f"  Top 5 logits: {sorted(logits, reverse=True)[:5]}")

    print(f"\nAll outputs saved to {output_dir}")

if __name__ == "__main__":
    main()
