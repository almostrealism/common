#!/usr/bin/env python3
"""Debug what hidden_states actually contains."""

import torch
from transformers import AutoModelForCausalLM

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"

def print_stats(name, tensor):
    t = tensor.float().squeeze()
    print(f"{name}: mean={t.mean():.6f}, std={t.std():.6f}, range=[{t.min():.4f}, {t.max():.4f}]")

def main():
    print("Loading model...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME)
    model.eval()

    print("\nModel structure:")
    print(f"  model.model.embed_tokens: {type(model.model.embed_tokens)}")
    print(f"  model.model.layers: {len(model.model.layers)} layers")
    print(f"  model.model.norm: {type(model.model.norm)}")
    print(f"  model.lm_head: {type(model.lm_head)}")

    # Check final norm parameters
    print(f"\nFinal norm weight stats:")
    norm_weight = model.model.norm.weight
    print_stats("  model.model.norm.weight", norm_weight)

    token_id = 9707

    with torch.no_grad():
        input_ids = torch.tensor([[token_id]], dtype=torch.long)
        outputs = model(input_ids, output_hidden_states=True, return_dict=True)

    print(f"\nNumber of hidden_states tensors: {len(outputs.hidden_states)}")

    # Print stats for each hidden state
    for i, hs in enumerate(outputs.hidden_states):
        print_stats(f"  hidden_states[{i}]", hs)

    # Get layer 23's output directly
    layer23 = model.model.layers[23]
    layer22_out = outputs.hidden_states[23]

    # Hook to capture layer 23 output before any additional processing
    layer23_output = {}
    def hook(module, input, output):
        if isinstance(output, tuple):
            layer23_output['out'] = output[0].detach()
        else:
            layer23_output['out'] = output.detach()
    layer23.register_forward_hook(hook)

    # Also hook the final norm
    final_norm_output = {}
    def norm_hook(module, input, output):
        final_norm_output['in'] = input[0].detach()
        final_norm_output['out'] = output.detach()
    model.model.norm.register_forward_hook(norm_hook)

    # Run again
    with torch.no_grad():
        outputs2 = model(input_ids, output_hidden_states=True, return_dict=True)

    print("\n--- Layer 23 vs hidden_states comparison ---")
    print_stats("Layer 23 hook output", layer23_output['out'])
    print_stats("hidden_states[24] (last hs)", outputs2.hidden_states[24])

    print("\n--- Final norm analysis ---")
    print_stats("Final norm input", final_norm_output['in'])
    print_stats("Final norm output", final_norm_output['out'])

    # Check if hidden_states[24] is before or after final norm
    diff_with_norm_in = (outputs2.hidden_states[24] - final_norm_output['in']).abs().mean()
    diff_with_norm_out = (outputs2.hidden_states[24] - final_norm_output['out']).abs().mean()

    print(f"\nhidden_states[24] vs final_norm input MAE: {diff_with_norm_in:.10f}")
    print(f"hidden_states[24] vs final_norm output MAE: {diff_with_norm_out:.10f}")

    if diff_with_norm_out < diff_with_norm_in:
        print("\n*** hidden_states[24] is AFTER the final norm! ***")
    else:
        print("\n*** hidden_states[24] is BEFORE the final norm ***")

if __name__ == "__main__":
    main()
