#!/usr/bin/env python3
"""
Generate multi-token generation reference from PyTorch Qwen2.5-0.5B-Instruct model.

This script autoregressively generates tokens and saves:
- The sequence of generated token IDs
- The logits at each step for comparison
"""

import os
import struct
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def main():
    model_name = "Qwen/Qwen2.5-0.5B-Instruct"
    output_dir = "/workspace/project/common/ml/qwen3_reference/generation"

    os.makedirs(output_dir, exist_ok=True)

    print(f"Loading model {model_name}...")
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.bfloat16,
        device_map="cpu"
    )
    model.eval()

    tokenizer = AutoTokenizer.from_pretrained(model_name)

    # Test prompts
    prompts = [
        "The capital of France is",
        "Once upon a time",
        "The quick brown fox",
        "Hello, my name is",
        "In machine learning,",
    ]

    max_new_tokens = 20

    for prompt_idx, prompt in enumerate(prompts):
        print(f"\n{'='*60}")
        print(f"Prompt {prompt_idx + 1}: '{prompt}'")
        print(f"{'='*60}")

        # Tokenize prompt
        input_ids = tokenizer.encode(prompt, return_tensors="pt")
        prompt_tokens = input_ids[0].tolist()
        print(f"Prompt tokens: {prompt_tokens}")
        print(f"Prompt length: {len(prompt_tokens)}")

        # Generate autoregressively with greedy decoding
        generated_tokens = []
        all_logits = []

        current_ids = input_ids.clone()

        with torch.no_grad():
            for step in range(max_new_tokens):
                outputs = model(current_ids, return_dict=True)

                # Get logits for last position
                logits = outputs.logits[0, -1, :].float().cpu()
                all_logits.append(logits.numpy())

                # Greedy: pick argmax
                next_token = logits.argmax().item()
                generated_tokens.append(next_token)

                # Append to sequence
                current_ids = torch.cat([
                    current_ids,
                    torch.tensor([[next_token]], dtype=torch.long)
                ], dim=1)

                # Stop at EOS
                if next_token == tokenizer.eos_token_id:
                    break

        # Decode result
        full_text = tokenizer.decode(current_ids[0].tolist())
        generated_text = tokenizer.decode(generated_tokens)

        print(f"\nGenerated tokens: {generated_tokens}")
        print(f"Generated text: '{generated_text}'")
        print(f"Full output: '{full_text}'")

        # Save reference data
        filename = f"prompt_{prompt_idx}.bin"
        filepath = os.path.join(output_dir, filename)

        with open(filepath, 'wb') as f:
            # Write prompt length and tokens
            f.write(struct.pack('i', len(prompt_tokens)))
            for tok in prompt_tokens:
                f.write(struct.pack('i', tok))

            # Write generated length and tokens
            f.write(struct.pack('i', len(generated_tokens)))
            for tok in generated_tokens:
                f.write(struct.pack('i', tok))

            # Write top-5 logits for each step (for debugging)
            f.write(struct.pack('i', len(all_logits)))
            for step_logits in all_logits:
                top5_indices = step_logits.argsort()[-5:][::-1]
                top5_values = step_logits[top5_indices]
                for idx, val in zip(top5_indices, top5_values):
                    f.write(struct.pack('i', int(idx)))
                    f.write(struct.pack('f', float(val)))

        print(f"Saved reference to {filename}")

        # Also save human-readable version
        txt_filename = f"prompt_{prompt_idx}.txt"
        txt_filepath = os.path.join(output_dir, txt_filename)
        with open(txt_filepath, 'w') as f:
            f.write(f"Prompt: {prompt}\n")
            f.write(f"Prompt tokens: {prompt_tokens}\n")
            f.write(f"Generated tokens: {generated_tokens}\n")
            f.write(f"Generated text: {generated_text}\n")
            f.write(f"Full output: {full_text}\n\n")
            f.write("Step-by-step generation:\n")
            for step, (tok, logits) in enumerate(zip(generated_tokens, all_logits)):
                top5_idx = logits.argsort()[-5:][::-1]
                top5_val = logits[top5_idx]
                tok_text = tokenizer.decode([tok])
                f.write(f"  Step {step}: token={tok} ('{tok_text}'), logit={logits[tok]:.4f}\n")
                f.write(f"    Top 5: {list(zip(top5_idx, [f'{v:.2f}' for v in top5_val]))}\n")

    print(f"\n{'='*60}")
    print(f"All outputs saved to {output_dir}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
