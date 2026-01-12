#!/usr/bin/env python3
"""
Benchmark PyTorch Qwen3 generation performance.

Measures time per token for autoregressive text generation to establish
a baseline for comparison with the Java AR implementation.

Usage:
    python benchmark_pytorch_generation.py [--tokens N] [--warmup M] [--runs R]

Output:
    Writes benchmark results to ../results/pytorch_benchmark.txt
"""

import argparse
import os
import sys
import time
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL_NAME = "Qwen/Qwen2.5-0.5B-Instruct"
RESULTS_DIR = os.path.join(os.path.dirname(__file__), "..", "results")


def benchmark_generation(model, tokenizer, num_tokens=20, warmup_tokens=5, num_runs=3):
    """
    Benchmark autoregressive generation.

    Args:
        model: The loaded PyTorch model
        tokenizer: The tokenizer
        num_tokens: Number of tokens to generate per run
        warmup_tokens: Number of warmup tokens (not timed)
        num_runs: Number of benchmark runs

    Returns:
        dict with timing statistics
    """
    results = {
        'warmup_tokens': warmup_tokens,
        'tokens_per_run': num_tokens,
        'num_runs': num_runs,
        'runs': []
    }

    # Input: BOS + "Hello"
    bos_token = 151643
    hello_token = 9707

    for run in range(num_runs):
        run_data = {
            'run_number': run + 1,
            'token_times_ms': [],
            'tokens_generated': []
        }

        input_ids = torch.tensor([[bos_token, hello_token]], dtype=torch.long)

        # Warmup phase (not timed)
        for _ in range(warmup_tokens):
            with torch.no_grad():
                outputs = model(input_ids, return_dict=True)
                logits = outputs.logits[:, -1, :]
                next_token = logits.argmax(dim=-1).item()
            input_ids = torch.cat([input_ids, torch.tensor([[next_token]])], dim=1)

        # Timed generation phase
        for _ in range(num_tokens):
            start = time.perf_counter()
            with torch.no_grad():
                outputs = model(input_ids, return_dict=True)
                logits = outputs.logits[:, -1, :]
                next_token = logits.argmax(dim=-1).item()
            elapsed_ms = (time.perf_counter() - start) * 1000

            run_data['token_times_ms'].append(elapsed_ms)
            run_data['tokens_generated'].append(next_token)
            input_ids = torch.cat([input_ids, torch.tensor([[next_token]])], dim=1)

        # Compute run statistics
        times = run_data['token_times_ms']
        run_data['mean_ms'] = sum(times) / len(times)
        run_data['min_ms'] = min(times)
        run_data['max_ms'] = max(times)
        run_data['total_ms'] = sum(times)
        run_data['tokens_per_second'] = 1000.0 / run_data['mean_ms']

        results['runs'].append(run_data)

    # Compute overall statistics
    all_times = []
    for run in results['runs']:
        all_times.extend(run['token_times_ms'])

    results['overall'] = {
        'mean_ms': sum(all_times) / len(all_times),
        'min_ms': min(all_times),
        'max_ms': max(all_times),
        'tokens_per_second': 1000.0 / (sum(all_times) / len(all_times)),
        'total_tokens': len(all_times)
    }

    return results


def print_results(results, output_file=None):
    """Print benchmark results to console and optionally to file."""
    lines = []

    lines.append("=" * 70)
    lines.append("  PYTORCH QWEN3 GENERATION BENCHMARK")
    lines.append("=" * 70)
    lines.append("")
    lines.append(f"Model: {MODEL_NAME}")
    lines.append(f"Warmup tokens: {results['warmup_tokens']}")
    lines.append(f"Tokens per run: {results['tokens_per_run']}")
    lines.append(f"Number of runs: {results['num_runs']}")
    lines.append("")

    for run in results['runs']:
        lines.append(f"--- Run {run['run_number']} ---")
        lines.append(f"  Mean time per token: {run['mean_ms']:.2f} ms")
        lines.append(f"  Min time per token:  {run['min_ms']:.2f} ms")
        lines.append(f"  Max time per token:  {run['max_ms']:.2f} ms")
        lines.append(f"  Tokens per second:   {run['tokens_per_second']:.1f}")
        lines.append(f"  Total time:          {run['total_ms']:.1f} ms")
        lines.append("")

    lines.append("=" * 70)
    lines.append("  OVERALL STATISTICS")
    lines.append("=" * 70)
    overall = results['overall']
    lines.append(f"  Mean time per token: {overall['mean_ms']:.2f} ms")
    lines.append(f"  Min time per token:  {overall['min_ms']:.2f} ms")
    lines.append(f"  Max time per token:  {overall['max_ms']:.2f} ms")
    lines.append(f"  Tokens per second:   {overall['tokens_per_second']:.1f}")
    lines.append(f"  Total tokens:        {overall['total_tokens']}")
    lines.append("=" * 70)

    # Print to console
    for line in lines:
        print(line)

    # Write to file if specified
    if output_file:
        os.makedirs(os.path.dirname(output_file), exist_ok=True)
        with open(output_file, 'w') as f:
            f.write('\n'.join(lines))
            f.write('\n')
        print(f"\nResults written to: {output_file}")


def main():
    parser = argparse.ArgumentParser(description='Benchmark PyTorch Qwen3 generation')
    parser.add_argument('--tokens', type=int, default=20, help='Tokens to generate per run')
    parser.add_argument('--warmup', type=int, default=5, help='Warmup tokens (not timed)')
    parser.add_argument('--runs', type=int, default=3, help='Number of benchmark runs')
    args = parser.parse_args()

    print(f"Loading model {MODEL_NAME}...")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, torch_dtype=torch.float32)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model.eval()
    print("Model loaded.\n")

    print(f"Running benchmark: {args.tokens} tokens × {args.runs} runs "
          f"(with {args.warmup} warmup tokens)...")
    print("")

    results = benchmark_generation(
        model, tokenizer,
        num_tokens=args.tokens,
        warmup_tokens=args.warmup,
        num_runs=args.runs
    )

    output_file = os.path.join(RESULTS_DIR, "pytorch_benchmark.txt")
    print_results(results, output_file)


if __name__ == "__main__":
    main()
