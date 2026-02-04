#!/usr/bin/env python3
"""
Validate consultant response quality by re-running questions.

Usage:
    python3 validate_responses.py <evaluation.json> [--sample N] [--output file.json]

This script:
1. Loads an evaluation JSON (from evaluate_dataset.py)
2. Re-runs questions through the consultant with curated keywords
3. Compares old vs new response metrics
4. Outputs a validation report

Requires the consultant backend to be running.
"""

import argparse
import json
import random
import sys
import time
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

try:
    from server import consult
    from inference import SYSTEM_PROMPT
    CONSULTANT_AVAILABLE = True
except ImportError as e:
    print(f"Warning: Could not import consultant modules: {e}")
    CONSULTANT_AVAILABLE = False


# Speculation phrases (same as evaluate_dataset.py)
SPECULATION_PHRASES = [
    "does not contain",
    "does not specifically",
    "not mentioned",
    "speculative",
    "hypothetical",
    "based on typical",
    "I can infer",
    "you may need to refer",
    "not covered in",
]


def has_speculation(response: str) -> bool:
    """Check if response contains speculation phrases."""
    response_lower = response.lower()
    return any(phrase in response_lower for phrase in SPECULATION_PHRASES)


def run_question(question: str, keywords: list[str]) -> dict:
    """Run a question through the consultant and return metrics."""
    if not CONSULTANT_AVAILABLE:
        return {
            'error': 'Consultant not available',
            'response': '',
            'length': 0,
            'speculation': False,
        }

    try:
        start = time.time()
        result = consult(question=question, keywords=keywords)
        elapsed = time.time() - start

        response = result.get('answer', '')
        return {
            'response': response,
            'length': len(response),
            'speculation': has_speculation(response),
            'latency': elapsed,
            'doc_chunks_found': len(result.get('sources', [])),
        }
    except Exception as e:
        return {
            'error': str(e),
            'response': '',
            'length': 0,
            'speculation': False,
        }


def validate_record(rec: dict) -> dict:
    """Validate a single record by re-running the question."""
    question = rec.get('question', '')
    keywords = rec.get('curated_keywords', [])

    # Old metrics from the evaluation
    old_response = rec.get('response_preview', '')
    old_length = rec.get('goal3_other_failure', {}).get('response_length', 0)
    old_speculation = rec.get('goal3_other_failure', {}).get('speculation_detected', False)

    # Run new query
    new_result = run_question(question, keywords)

    return {
        'id': rec.get('id'),
        'question': question,
        'keywords': keywords,
        'old': {
            'length': old_length,
            'speculation': old_speculation,
            'preview': old_response[:200] if old_response else '',
        },
        'new': {
            'length': new_result.get('length', 0),
            'speculation': new_result.get('speculation', False),
            'latency': new_result.get('latency', 0),
            'doc_chunks_found': new_result.get('doc_chunks_found', 0),
            'preview': new_result.get('response', '')[:200],
            'error': new_result.get('error'),
        },
        'improvement': {
            'length_change': new_result.get('length', 0) - old_length,
            'length_change_pct': (
                round(100 * (new_result.get('length', 0) - old_length) / old_length, 1)
                if old_length > 0 else 0
            ),
            'speculation_fixed': old_speculation and not new_result.get('speculation', False),
        },
    }


def main():
    parser = argparse.ArgumentParser(description='Validate consultant response quality')
    parser.add_argument('evaluation_file', help='Path to evaluation JSON from evaluate_dataset.py')
    parser.add_argument('--sample', type=int, help='Number of questions to sample (default: all)')
    parser.add_argument('--output', help='Output file path (default: <input>-validation.json)')
    parser.add_argument('--dry-run', action='store_true', help='Show what would be run without calling consultant')
    args = parser.parse_args()

    # Load evaluation
    eval_path = Path(args.evaluation_file)
    with open(eval_path) as f:
        data = json.load(f)

    records = data.get('records', [])
    print(f"Loaded {len(records)} records from evaluation")

    # Sample if requested
    if args.sample and args.sample < len(records):
        records = random.sample(records, args.sample)
        print(f"Sampled {len(records)} records for validation")

    if args.dry_run:
        print("\n[DRY RUN] Would validate these questions:")
        for i, rec in enumerate(records[:5], 1):
            print(f"  {i}. {rec.get('question', '')[:80]}...")
        if len(records) > 5:
            print(f"  ... and {len(records) - 5} more")
        return

    if not CONSULTANT_AVAILABLE:
        print("\nERROR: Consultant modules not available.")
        print("Make sure you're running from the consultant directory or the backend is configured.")
        sys.exit(1)

    # Validate each record
    print(f"\nValidating {len(records)} questions...")
    validations = []
    for i, rec in enumerate(records, 1):
        print(f"  [{i}/{len(records)}] {rec.get('question', '')[:60]}...")
        result = validate_record(rec)
        validations.append(result)

        # Brief pause to avoid overwhelming the backend
        time.sleep(0.5)

    # Compute summary
    successful = [v for v in validations if not v['new'].get('error')]

    if successful:
        avg_old_length = sum(v['old']['length'] for v in successful) / len(successful)
        avg_new_length = sum(v['new']['length'] for v in successful) / len(successful)
        old_speculation_count = sum(1 for v in successful if v['old']['speculation'])
        new_speculation_count = sum(1 for v in successful if v['new']['speculation'])
        speculation_fixed = sum(1 for v in successful if v['improvement']['speculation_fixed'])
    else:
        avg_old_length = avg_new_length = 0
        old_speculation_count = new_speculation_count = speculation_fixed = 0

    summary = {
        'total_validated': len(validations),
        'successful': len(successful),
        'failed': len(validations) - len(successful),
        'avg_old_length': round(avg_old_length, 1),
        'avg_new_length': round(avg_new_length, 1),
        'length_change_pct': (
            round(100 * (avg_new_length - avg_old_length) / avg_old_length, 1)
            if avg_old_length > 0 else 0
        ),
        'old_speculation_count': old_speculation_count,
        'new_speculation_count': new_speculation_count,
        'speculation_fixed': speculation_fixed,
    }

    # Build output
    output = {
        'metadata': {
            'source': str(eval_path),
            'sample_size': args.sample,
            'system_prompt_preview': SYSTEM_PROMPT[:200] + '...' if CONSULTANT_AVAILABLE else 'N/A',
        },
        'summary': summary,
        'validations': validations,
    }

    # Write output
    output_path = args.output or str(eval_path).replace('.json', '-validation.json')
    with open(output_path, 'w') as f:
        json.dump(output, f, indent=2)

    # Print summary
    print(f"\n{'='*60}")
    print("VALIDATION SUMMARY")
    print('='*60)
    print(f"Questions validated: {summary['successful']}/{summary['total_validated']}")

    print(f"\nResponse Length:")
    print(f"  Old average: {summary['avg_old_length']:.0f} chars")
    print(f"  New average: {summary['avg_new_length']:.0f} chars")
    print(f"  Change: {summary['length_change_pct']:+.1f}%")

    print(f"\nSpeculation:")
    print(f"  Old: {summary['old_speculation_count']}/{summary['successful']} responses with speculation")
    print(f"  New: {summary['new_speculation_count']}/{summary['successful']} responses with speculation")
    print(f"  Fixed: {summary['speculation_fixed']} responses no longer speculate")

    print(f"\nOutput written to: {output_path}")


if __name__ == '__main__':
    main()
