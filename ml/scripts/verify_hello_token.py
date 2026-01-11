#!/usr/bin/env python3
"""Verify logits for Hello token at position 1."""

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"

def main():
    print("=" * 70)
    print("  VERIFYING HELLO TOKEN LOGITS")
    print("=" * 70)

    print(f"\nLoading model {MODEL_NAME}...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model.eval()

    ar_bos = 151643
    hello_token = 9707

    # Test 1: Just Hello token at position 0 (no BOS)
    print("\n--- Test 1: Hello token only at position 0 ---")
    with torch.no_grad():
        input_ids = torch.tensor([[hello_token]], dtype=torch.long)
        outputs = model(input_ids, return_dict=True)
        logits = outputs.logits.squeeze().float()

    print(f"Logits: min={logits.min():.4f}, max={logits.max():.4f}, mean={logits.mean():.4f}")
    top5_values, top5_indices = torch.topk(logits, 5)
    for i, (idx, val) in enumerate(zip(top5_indices, top5_values)):
        print(f"  #{i+1}: token={idx.item()} ({val:.4f}) -> '{tokenizer.decode([idx.item()])}'")

    # Test 2: BOS + Hello, get logits at position 1 (Hello's output)
    print("\n--- Test 2: BOS + Hello, get logits at position 1 ---")
    with torch.no_grad():
        input_ids = torch.tensor([[ar_bos, hello_token]], dtype=torch.long)
        outputs = model(input_ids, return_dict=True)
        # Get logits at position 1 (after Hello token)
        logits = outputs.logits[0, 1, :].float()

    print(f"Logits: min={logits.min():.4f}, max={logits.max():.4f}, mean={logits.mean():.4f}")
    top5_values, top5_indices = torch.topk(logits, 5)
    for i, (idx, val) in enumerate(zip(top5_indices, top5_values)):
        print(f"  #{i+1}: token={idx.item()} ({val:.4f}) -> '{tokenizer.decode([idx.item()])}'")

    # Test 3: Step-by-step like AR does
    print("\n--- Test 3: Step-by-step (like AR) ---")

    # Step 1: Process BOS at position 0
    print("  Processing BOS at position 0...")
    with torch.no_grad():
        input_ids = torch.tensor([[ar_bos]], dtype=torch.long)
        outputs = model(input_ids, use_cache=True, return_dict=True)
        kv_cache = outputs.past_key_values

    # Step 2: Process Hello at position 1, using cache
    print("  Processing Hello at position 1 (with cache)...")
    with torch.no_grad():
        input_ids = torch.tensor([[hello_token]], dtype=torch.long)
        outputs = model(
            input_ids,
            past_key_values=kv_cache,
            use_cache=True,
            return_dict=True
        )
        logits = outputs.logits[0, 0, :].float()  # [0,0] because we only input 1 token

    print(f"Logits: min={logits.min():.4f}, max={logits.max():.4f}, mean={logits.mean():.4f}")
    top5_values, top5_indices = torch.topk(logits, 5)
    for i, (idx, val) in enumerate(zip(top5_indices, top5_values)):
        print(f"  #{i+1}: token={idx.item()} ({val:.4f}) -> '{tokenizer.decode([idx.item()])}'")

    # Compare AR results
    print("\n--- AR Result for Hello token (Step 1 in generation) ---")
    print("  After processing Hello, AR predicts:")
    print("    #1: token=271 -> newlines")
    print("  (This is what AR generated as the first new token)")

    print("\n" + "=" * 70)

if __name__ == "__main__":
    main()
