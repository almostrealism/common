#!/usr/bin/env python3
"""Debug PyTorch attention at position 1 to understand expected behavior."""

import torch
import numpy as np
from transformers import AutoModelForCausalLM, AutoTokenizer

def main():
    model_name = "Qwen/Qwen2.5-0.5B-Instruct"
    print(f"Loading model: {model_name}")

    tokenizer = AutoTokenizer.from_pretrained(model_name)
    model = AutoModelForCausalLM.from_pretrained(model_name, dtype=torch.float32)
    model.eval()

    # Test input: "Hello" at position 0, then 271 ("\n\n") at position 1
    input_ids = torch.tensor([[9707, 271]])  # Hello, \n\n

    print(f"\nInput tokens: {input_ids[0].tolist()}")
    print(f"Decoded: {repr(tokenizer.decode(input_ids[0]))}")

    # Get outputs with context
    with torch.no_grad():
        outputs = model(input_ids)

    # Get logits
    logits = outputs.logits[0, -1, :]  # Last position logits
    top5 = torch.topk(logits, 5)
    print("\n" + "="*60)
    print("Logits at position 1:")
    print(f"  Top 5 predictions:")
    for i, (idx, val) in enumerate(zip(top5.indices, top5.values)):
        token_text = tokenizer.decode([idx])
        print(f"    {i+1}. Token {idx.item()}: {repr(token_text)} (logit={val.item():.4f})")

    # Now compare with single-token input at position 1 (no context)
    print("\n" + "="*60)
    print("Comparison: Single token 271 with NO context:")
    single_input = torch.tensor([[271]])
    with torch.no_grad():
        single_outputs = model(single_input)

    single_logits = single_outputs.logits[0, 0, :]
    single_top5 = torch.topk(single_logits, 5)
    print(f"  Top 5 predictions (no context):")
    for i, (idx, val) in enumerate(zip(single_top5.indices, single_top5.values)):
        token_text = tokenizer.decode([idx])
        print(f"    {i+1}. Token {idx.item()}: {repr(token_text)} (logit={val.item():.4f})")

    # Check if Java's output (14374) matches the no-context prediction
    print(f"\n  Token 14374 logit with context: {logits[14374].item():.4f}")
    print(f"  Token 14374 logit without context: {single_logits[14374].item():.4f}")

    print("\nDone!")

if __name__ == "__main__":
    main()
