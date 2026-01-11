#!/usr/bin/env python3
"""
Export PyTorch logits for multi-position autoregressive generation.

This script generates reference data for verifying the Java Qwen3 implementation
by running PyTorch token-by-token and saving logits at each position.
"""

import torch
import numpy as np
import struct
from pathlib import Path
from transformers import AutoModelForCausalLM, AutoTokenizer

def export_logits(output_path: Path, logits: np.ndarray):
    """Export logits in the format expected by Java tests."""
    with open(output_path, 'wb') as f:
        # Write size as int32
        f.write(struct.pack('<i', len(logits)))
        # Write logits as float32
        for val in logits:
            f.write(struct.pack('<f', val))

def main():
    # Load model
    model_name = "Qwen/Qwen2.5-0.5B-Instruct"
    print(f"Loading model: {model_name}")

    tokenizer = AutoTokenizer.from_pretrained(model_name)
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        dtype=torch.float32  # Use float32 for comparison
    )
    model.eval()

    # Output directory
    output_dir = Path("/workspace/project/common/ml/qwen3_reference/autoregressive")
    output_dir.mkdir(parents=True, exist_ok=True)

    # Start with "Hello"
    start_token = 9707  # "Hello"
    num_positions = 5

    print(f"\nStarting autoregressive generation from token {start_token}")
    print("=" * 60)

    # Track tokens generated
    input_tokens = [start_token]
    generated_tokens = []
    all_logits = []

    # Generate token-by-token
    for pos in range(num_positions):
        # Create input for current position
        input_ids = torch.tensor([input_tokens], dtype=torch.long)

        with torch.no_grad():
            outputs = model(input_ids)
            logits = outputs.logits[0, -1, :]  # Get logits for last token

        # Convert to numpy
        logits_np = logits.numpy().astype(np.float32)
        all_logits.append(logits_np)

        # Find top-5 predictions
        top5_indices = np.argsort(logits_np)[-5:][::-1]
        top5_values = logits_np[top5_indices]

        print(f"\nPosition {pos}: Input tokens = {input_tokens}")
        print(f"Top 5 predictions:")
        for i, (idx, val) in enumerate(zip(top5_indices, top5_values)):
            token_text = tokenizer.decode([idx])
            print(f"  {i+1}. Token {idx}: {repr(token_text)} (logit={val:.4f})")

        # Get the greedy choice
        next_token = top5_indices[0]
        generated_tokens.append(int(next_token))

        # Append to input for next iteration (autoregressive)
        input_tokens.append(int(next_token))

        # Export logits
        output_file = output_dir / f"position_{pos}_logits.bin"
        export_logits(output_file, logits_np)
        print(f"Exported logits to: {output_file}")

    # Summary
    print("\n" + "=" * 60)
    print("Summary:")
    print(f"Input tokens:     {[start_token] + generated_tokens[:-1]}")
    print(f"Generated tokens: {generated_tokens}")
    print(f"Full sequence:    {input_tokens}")

    # Decode the sequence
    decoded = tokenizer.decode(input_tokens)
    print(f"Decoded text: {repr(decoded)}")

    # Save summary
    summary_file = output_dir / "summary.txt"
    with open(summary_file, 'w') as f:
        f.write(f"Autoregressive Generation Summary\n")
        f.write(f"=" * 60 + "\n")
        f.write(f"Model: {model_name}\n")
        f.write(f"Start token: {start_token}\n")
        f.write(f"Number of positions: {num_positions}\n\n")

        f.write(f"Token sequence:\n")
        for pos in range(num_positions):
            token = input_tokens[pos]
            output = generated_tokens[pos]
            token_text = tokenizer.decode([token])
            output_text = tokenizer.decode([output])
            f.write(f"  Position {pos}: input={token} ({repr(token_text)}) -> output={output} ({repr(output_text)})\n")

        f.write(f"\nFull decoded: {repr(decoded)}\n")

    print(f"\nSummary saved to: {summary_file}")
    print("Done!")

if __name__ == "__main__":
    main()
