#!/usr/bin/env python3
"""
Generate reference logits from PyTorch Qwen2.5-0.5B-Instruct for comparison.

This script:
1. Loads the real Qwen2.5-0.5B-Instruct model from HuggingFace
2. Encodes a simple prompt
3. Runs one forward pass to get logits
4. Saves logits and top-k predictions to a file for Java comparison
"""

import argparse
import numpy as np
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', default='Qwen/Qwen2.5-0.5B-Instruct', help='Model name')
    parser.add_argument('--prompt', default='Hello', help='Prompt text')
    parser.add_argument('--output', default='/tmp/qwen_logits_reference.txt', help='Output file')
    parser.add_argument('--temperature', type=float, default=0.0, help='Temperature (0=greedy)')
    args = parser.parse_args()

    print(f"\n{'='*60}")
    print(f"Qwen Logits Reference Generator")
    print(f"{'='*60}\n")

    # Load model and tokenizer
    print(f"Loading model: {args.model}")
    model = AutoModelForCausalLM.from_pretrained(
        args.model,
        torch_dtype=torch.float32,
        device_map="cpu",
        trust_remote_code=True
    )
    model.eval()
    
    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    print(f"Model loaded successfully")
    print(f"Vocab size: {tokenizer.vocab_size}")
    
    # Encode prompt
    print(f"\nPrompt: '{args.prompt}'")
    inputs = tokenizer(args.prompt, return_tensors="pt", add_special_tokens=True)
    input_ids = inputs['input_ids']
    
    print(f"Encoded tokens: {input_ids[0].tolist()}")
    print(f"Token count: {input_ids.shape[1]}")
    
    # Decode to verify
    decoded = tokenizer.decode(input_ids[0], skip_special_tokens=False)
    print(f"Decoded: '{decoded}'")
    
    # Forward pass
    print(f"\nRunning forward pass...")
    with torch.no_grad():
        outputs = model(input_ids)
        logits = outputs.logits
    
    # Get logits for the last token position (next token prediction)
    last_logits = logits[0, -1, :].cpu().numpy()
    
    print(f"Logits shape: {logits.shape}")
    print(f"Last position logits shape: {last_logits.shape}")
    print(f"Logits range: [{last_logits.min():.4f}, {last_logits.max():.4f}]")
    print(f"Logits mean: {last_logits.mean():.4f}")
    print(f"Logits std: {last_logits.std():.4f}")
    
    # Apply temperature if needed
    if args.temperature > 0:
        last_logits = last_logits / args.temperature
    
    # Get top-k predictions
    top_k = 20
    top_indices = np.argsort(last_logits)[-top_k:][::-1]
    top_logits = last_logits[top_indices]
    
    # Apply softmax to get probabilities
    exp_logits = np.exp(top_logits - np.max(top_logits))
    probs = exp_logits / exp_logits.sum()
    
    # Save results
    with open(args.output, 'w') as f:
        f.write(f"Prompt: {args.prompt}\n")
        f.write(f"Encoded: {input_ids[0].tolist()}\n")
        f.write(f"Token count: {input_ids.shape[1]}\n")
        f.write(f"\nLogits statistics:\n")
        f.write(f"  Shape: {last_logits.shape}\n")
        f.write(f"  Range: [{last_logits.min():.6f}, {last_logits.max():.6f}]\n")
        f.write(f"  Mean: {last_logits.mean():.6f}\n")
        f.write(f"  Std: {last_logits.std():.6f}\n")
        f.write(f"\nTop {top_k} predictions:\n")
        f.write(f"{'Rank':<6} {'Token ID':<10} {'Logit':<12} {'Prob':<12} {'Token'}\n")
        f.write(f"{'-'*70}\n")
        
        for rank, (idx, logit, prob) in enumerate(zip(top_indices, top_logits, probs), 1):
            token_str = tokenizer.decode([idx])
            # Escape special characters for display
            token_display = repr(token_str)[1:-1]  # Remove quotes from repr
            f.write(f"{rank:<6} {idx:<10} {logit:<12.6f} {prob:<12.6f} '{token_display}'\n")
        
        # Save full logits for numerical comparison
        f.write(f"\nFull logits (first 50):\n")
        for i in range(min(50, len(last_logits))):
            f.write(f"{i}: {last_logits[i]:.6f}\n")
    
    print(f"\n{'='*60}")
    print(f"Top {top_k} predictions:")
    print(f"{'='*60}")
    print(f"{'Rank':<6} {'Token ID':<10} {'Logit':<12} {'Prob':<12} {'Token'}")
    print(f"{'-'*70}")
    
    for rank, (idx, logit, prob) in enumerate(zip(top_indices, top_logits, probs), 1):
        token_str = tokenizer.decode([idx])
        token_display = repr(token_str)[1:-1]
        print(f"{rank:<6} {idx:<10} {logit:<12.6f} {prob:<12.6f} '{token_display}'")
    
    print(f"\nResults saved to: {args.output}")
    print(f"\n{'='*60}\n")

if __name__ == '__main__':
    main()
