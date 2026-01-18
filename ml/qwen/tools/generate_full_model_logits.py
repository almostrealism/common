#!/usr/bin/env python3
"""
Generate PyTorch logits for full 24-layer Qwen2.5-0.5B model.
This will serve as ground truth to compare against AR implementation.
"""

import numpy as np
import struct
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"
OUTPUT_DIR = "/workspace/project/common/ml/qwen3_reference/full_model_logits"

def main():
    print("Loading Qwen2.5-0.5B-Instruct model...")
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_NAME,
        torch_dtype=torch.float32,
        device_map="cpu"
    )
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)

    model.eval()

    # Test with "Hello" - token 9707
    test_text = "Hello"
    tokens = tokenizer.encode(test_text, add_special_tokens=False)
    print(f"\nInput: '{test_text}'")
    print(f"Tokens: {tokens}")

    if tokens != [9707]:
        print(f"WARNING: Expected [9707], got {tokens}")
        return

    # Create input tensor
    input_ids = torch.tensor([tokens], dtype=torch.long)

    print("\n=== Running full model forward pass ===")
    with torch.no_grad():
        outputs = model(input_ids, return_dict=True)
        logits = outputs.logits  # Shape: (batch=1, seq_len=1, vocab_size)

    # Extract logits for position 0
    position_0_logits = logits[0, 0, :].numpy()  # Shape: (vocab_size,)

    print(f"\nLogits shape: {position_0_logits.shape}")
    print(f"Logits dtype: {position_0_logits.dtype}")

    # Find top predictions
    top_k = 10
    top_indices = np.argsort(-position_0_logits)[:top_k]

    print(f"\n=== Top {top_k} Predictions ===")
    for i, idx in enumerate(top_indices):
        token_str = tokenizer.decode([idx])
        logit_val = position_0_logits[idx]
        print(f"{i+1}. Token {idx}: '{token_str}' (logit={logit_val:.4f})")

    # Save logits to binary file
    import os
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    output_file = os.path.join(OUTPUT_DIR, "position_0_logits.bin")
    with open(output_file, 'wb') as f:
        # Write header: vocab_size
        f.write(struct.pack('i', len(position_0_logits)))
        # Write logits as float32
        position_0_logits.astype(np.float32).tofile(f)

    print(f"\nSaved logits to: {output_file}")

    # Also save top predictions to text file
    top_pred_file = os.path.join(OUTPUT_DIR, "top_predictions.txt")
    with open(top_pred_file, 'w') as f:
        f.write(f"Input: '{test_text}' (token {tokens[0]})\n")
        f.write(f"Logits shape: {position_0_logits.shape}\n\n")
        f.write(f"Top {top_k} Predictions:\n")
        for i, idx in enumerate(top_indices):
            token_str = tokenizer.decode([idx]).replace('\n', '\\n')
            logit_val = position_0_logits[idx]
            f.write(f"{i+1}. Token {idx}: '{token_str}' (logit={logit_val:.4f})\n")

    print(f"Saved top predictions to: {top_pred_file}")

    # Check specific tokens of interest
    print("\n=== Specific Tokens of Interest ===")
    tokens_of_interest = [198, 271, 49, 27]  # \n, \n\n, R, <
    for token_id in tokens_of_interest:
        token_str = tokenizer.decode([token_id]).replace('\n', '\\n')
        logit_val = position_0_logits[token_id]
        rank = (position_0_logits > logit_val).sum() + 1
        print(f"Token {token_id}: '{token_str}' - logit={logit_val:.4f}, rank={rank}")

if __name__ == "__main__":
    main()
