#!/usr/bin/env python3
"""Verify generation matches between AR and PyTorch."""

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"

def main():
    print("=" * 70)
    print("  VERIFYING GENERATION MATCHES")
    print("=" * 70)

    print(f"\nLoading model {MODEL_NAME}...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model.eval()

    # Same prompt as AR test: BOS + "Hello"
    # AR uses 151643 as BOS, then 9707 for "Hello"
    ar_bos = 151643
    hello_token = 9707

    print(f"\nInput tokens: [{ar_bos}, {hello_token}]")
    print(f"Decoded: BOS + '{tokenizer.decode([hello_token])}'")

    # Generate 10 tokens step by step
    print("\n--- Step-by-Step Generation (greedy) ---")
    input_ids = torch.tensor([[ar_bos, hello_token]], dtype=torch.long)

    generated_tokens = []
    current_ids = input_ids

    for step in range(10):
        with torch.no_grad():
            outputs = model(current_ids, return_dict=True)
            logits = outputs.logits[:, -1, :]  # Last position
            next_token = logits.argmax(dim=-1).item()

        generated_tokens.append(next_token)
        decoded = tokenizer.decode([next_token])
        print(f"Step {step}: token={next_token:6d} -> \"{decoded}\"")

        current_ids = torch.cat([current_ids, torch.tensor([[next_token]])], dim=1)

    print(f"\nGenerated tokens: {generated_tokens}")
    print(f"Full output: \"{tokenizer.decode([ar_bos, hello_token] + generated_tokens)}\"")

    # Compare with AR results
    print("\n--- AR Results (from test) ---")
    ar_tokens = [271, 40, 220, 16, 13, 3555, 374, 279, 2701, 2038]
    print(f"AR tokens: {ar_tokens}")
    ar_decoded = [tokenizer.decode([t]) for t in ar_tokens]
    print(f"AR decoded: {ar_decoded}")

    # Check if they match
    print("\n--- Comparison ---")
    matches = sum(1 for a, p in zip(ar_tokens, generated_tokens) if a == p)
    print(f"Matching tokens: {matches}/{len(ar_tokens)}")

    for i, (ar, pt) in enumerate(zip(ar_tokens, generated_tokens)):
        status = "MATCH" if ar == pt else "DIFFER"
        ar_decoded = tokenizer.decode([ar])
        pt_decoded = tokenizer.decode([pt])
        print(f"  Step {i}: AR={ar} '{ar_decoded}' | PT={pt} '{pt_decoded}' [{status}]")

    print("\n" + "=" * 70)

if __name__ == "__main__":
    main()
