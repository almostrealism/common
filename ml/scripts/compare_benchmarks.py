#!/usr/bin/env python3
"""
Compare PyTorch and Java benchmark results.

Reads benchmark output files and generates a comparison summary.

Usage:
    python compare_benchmarks.py
"""

import os
import re

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "..", "results")
PYTORCH_FILE = os.path.join(RESULTS_DIR, "pytorch_benchmark.txt")
JAVA_FILE = os.path.join(RESULTS_DIR, "java_benchmark.txt")


def parse_benchmark_file(filepath):
    """Parse a benchmark output file and extract key metrics."""
    if not os.path.exists(filepath):
        return None

    results = {}
    with open(filepath, 'r') as f:
        content = f.read()

    # Extract overall statistics
    mean_match = re.search(r'Mean time per token:\s+([\d.]+)\s*ms', content)
    min_match = re.search(r'Min time per token:\s+([\d.]+)\s*ms', content)
    max_match = re.search(r'Max time per token:\s+([\d.]+)\s*ms', content)
    tps_match = re.search(r'Tokens per second:\s+([\d.]+)', content)
    total_match = re.search(r'Total tokens:\s+(\d+)', content)

    if mean_match:
        results['mean_ms'] = float(mean_match.group(1))
    if min_match:
        results['min_ms'] = float(min_match.group(1))
    if max_match:
        results['max_ms'] = float(max_match.group(1))
    if tps_match:
        results['tokens_per_second'] = float(tps_match.group(1))
    if total_match:
        results['total_tokens'] = int(total_match.group(1))

    return results if results else None


def main():
    print("=" * 70)
    print("  PYTORCH vs JAVA BENCHMARK COMPARISON")
    print("=" * 70)
    print()

    pytorch_results = parse_benchmark_file(PYTORCH_FILE)
    java_results = parse_benchmark_file(JAVA_FILE)

    if not pytorch_results:
        print(f"PyTorch benchmark not found: {PYTORCH_FILE}")
        print("Run: python benchmark_pytorch_generation.py")
        print()
    else:
        print("PyTorch Benchmark Results:")
        print(f"  Mean time per token: {pytorch_results.get('mean_ms', 'N/A'):.2f} ms")
        print(f"  Tokens per second:   {pytorch_results.get('tokens_per_second', 'N/A'):.1f}")
        print()

    if not java_results:
        print(f"Java benchmark not found: {JAVA_FILE}")
        print("Run the Qwen3BenchmarkTest via MCP test runner")
        print()
    else:
        print("Java Benchmark Results:")
        print(f"  Mean time per token: {java_results.get('mean_ms', 'N/A'):.2f} ms")
        print(f"  Tokens per second:   {java_results.get('tokens_per_second', 'N/A'):.1f}")
        print()

    if pytorch_results and java_results:
        print("-" * 70)
        print("Comparison:")
        print("-" * 70)

        pytorch_tps = pytorch_results.get('tokens_per_second', 0)
        java_tps = java_results.get('tokens_per_second', 0)

        if pytorch_tps > 0 and java_tps > 0:
            ratio = java_tps / pytorch_tps
            slowdown = pytorch_tps / java_tps

            print(f"  PyTorch: {pytorch_tps:.1f} tokens/sec")
            print(f"  Java:    {java_tps:.1f} tokens/sec")
            print()
            print(f"  Java is {ratio:.2f}x the speed of PyTorch")
            if ratio < 1:
                print(f"  Java is {slowdown:.1f}x slower than PyTorch")
            else:
                print(f"  Java is {ratio:.1f}x faster than PyTorch!")
            print()

            # Performance gap
            pytorch_ms = pytorch_results.get('mean_ms', 0)
            java_ms = java_results.get('mean_ms', 0)
            gap_ms = java_ms - pytorch_ms

            print(f"  Time per token gap: {gap_ms:.2f} ms")
            if gap_ms > 0:
                print(f"  Need to reduce Java time by {gap_ms:.2f} ms to match PyTorch")
            print()

    print("=" * 70)


if __name__ == "__main__":
    main()
