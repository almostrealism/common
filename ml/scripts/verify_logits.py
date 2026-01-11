#!/usr/bin/env python3
"""Verify logits match between AR and PyTorch for first token."""

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
import struct
import os

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"
REFERENCE_DIR = "/workspace/project/common/ml/qwen3_reference/layer_outputs"

def main():
    print("=" * 70)
    print("  VERIFYING LOGITS FOR FIRST TOKEN")
    print("=" * 70)

    print(f"\nLoading model {MODEL_NAME}...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model.eval()

    # Check tokenizer special tokens
    print(f"\nTokenizer special tokens:")
    print(f"  bos_token_id: {tokenizer.bos_token_id}")
    print(f"  eos_token_id: {tokenizer.eos_token_id}")
    print(f"  pad_token_id: {tokenizer.pad_token_id}")

    # Use token 9707 ("Hello") as in our tests
    test_token = 9707
    print(f"\nTest token: {test_token}")
    print(f"  Decoded: \"{tokenizer.decode([test_token])}\"")

    # Forward pass with test token
    print("\nRunning forward pass...")
    with torch.no_grad():
        input_ids = torch.tensor([[test_token]], dtype=torch.long)
        outputs = model(input_ids, return_dict=True)
        logits = outputs.logits.squeeze().float()

    print(f"\nLogits: min={logits.min():.4f}, max={logits.max():.4f}, mean={logits.mean():.4f}")

    # Top 5 predictions
    top5_values, top5_indices = torch.topk(logits, 5)
    print("\nTop 5 predictions (PyTorch for token 9707 'Hello'):")
    for i, (idx, val) in enumerate(zip(top5_indices, top5_values)):
        token = tokenizer.decode([idx.item()])
        print(f"  #{i+1}: token={idx.item()} ({val:.4f}) -> \"{token}\"")

    # Now test with what AR uses as "BOS" (which is 151643)
    ar_bos = 151643
    print(f"\n\n--- Testing with AR's BOS token {ar_bos} ---")
    try:
        decoded = tokenizer.decode([ar_bos])
        print(f"Decoded: \"{decoded}\"")
    except:
        print("Cannot decode this token")

    with torch.no_grad():
        input_ids = torch.tensor([[ar_bos]], dtype=torch.long)
        outputs = model(input_ids, return_dict=True)
        logits = outputs.logits.squeeze().float()

    print(f"\nLogits: min={logits.min():.4f}, max={logits.max():.4f}, mean={logits.mean():.4f}")

    top5_values, top5_indices = torch.topk(logits, 5)
    print("\nTop 5 predictions (PyTorch for AR BOS 151643):")
    for i, (idx, val) in enumerate(zip(top5_indices, top5_values)):
        token = tokenizer.decode([idx.item()])
        print(f"  #{i+1}: token={idx.item()} ({val:.4f}) -> \"{token}\"")

    # Compare with AR output (from logits_debug.txt which used BOS)
    print("\n--- AR Results for BOS (from test) ---")
    print("  #1: token=89012 (13.4369) -> \"?\"")
    print("  #2: token=220 (12.3659) -> \" \"")
    print("  #3: token=3225 (11.9920) -> \"?\"")
    print("  #4: token=8701 (11.9242) -> \"#!/\"")
    print("  #5: token=27 (11.9199) -> \"<\"")

    ar_top1 = 89012
    pytorch_top1 = top5_indices[0].item()

    print(f"\n--- Comparison ---")
    print(f"AR top-1: {ar_top1}")
    print(f"PyTorch top-1: {pytorch_top1}")

    if ar_top1 == pytorch_top1:
        print("TOP-1 MATCHES!")
    else:
        print(f"TOP-1 MISMATCH!")
        # Check AR's top-1 value in PyTorch
        ar_top1_logit = logits[ar_top1].item()
        print(f"  PyTorch logit for AR top-1 ({ar_top1}): {ar_top1_logit:.4f}")
        # Check rank
        sorted_indices = torch.argsort(logits, descending=True)
        ar_rank = (sorted_indices == ar_top1).nonzero(as_tuple=True)[0].item() + 1
        print(f"  AR top-1's rank in PyTorch: #{ar_rank}")

    print("\n" + "=" * 70)

if __name__ == "__main__":
    main()
