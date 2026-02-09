#!/usr/bin/env python3
"""
Evaluate a consultant export dataset for the three improvement goals.

Usage:
    python3 evaluate_dataset.py <export.json> [--keywords keywords.json]

This script:
1. Loads a consultant export JSON file
2. For each consult record, classifies issues by goal:
   - Goal 1: Documentation gap (primary term not documented)
   - Goal 2: Search issue (irrelevant results despite docs existing)
   - Goal 3: Other failure (speculation/verbosity in response)
3. Outputs evaluation JSON and summary statistics

If --keywords is provided, uses those curated keywords. Otherwise, attempts
to extract keywords heuristically (less accurate).
"""

import argparse
import json
import re
import sys
from pathlib import Path

# Add parent directory to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))
from docs_retriever import DocsRetriever


# Speculation phrases that indicate the LLM is guessing
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

# Generic terms that are not useful as primary keywords
GENERIC_TERMS = {
    'how', 'what', 'why', 'when', 'where', 'which',
    'backward', 'forward', 'gradient', 'compile', 'compilation',
    'training', 'memory', 'hardware', 'model', 'layer',
    'attention', 'transformer', 'diffusion', 'embedding',
    'create', 'make', 'build', 'use', 'get', 'set',
}


def is_specific_term(term: str) -> bool:
    """Check if term is specific (likely a class/method name)."""
    return term.lower() not in GENERIC_TERMS and (
        term[0].isupper() or  # PascalCase
        '_' in term or        # snake_case
        term.isupper()        # CONSTANT
    )


def extract_keywords_heuristic(question: str) -> list[str]:
    """Extract keywords from a question heuristically.

    This is less accurate than curated keywords but provides a fallback.
    """
    words = re.split(r'\s+', question)
    keywords = []

    for word in words:
        # Clean punctuation
        word = word.strip('?.,!;:\'"()[]')
        if len(word) < 3:
            continue

        # Prioritize specific terms (class names, etc.)
        if is_specific_term(word):
            keywords.insert(0, word)  # Add to front
        elif word.lower() not in GENERIC_TERMS:
            keywords.append(word)

    return keywords[:5]


def check_term_documented(retriever: DocsRetriever, term: str) -> tuple[bool, list[str]]:
    """Check if a term is documented (excluding heredity noise)."""
    results = retriever.search(term, max_results=5)
    relevant = [r for r in results if 'heredity' not in r['file']]
    return len(relevant) > 0, [r['file'] for r in relevant[:3]]


def has_speculation(response: str) -> bool:
    """Check if response contains speculation phrases."""
    response_lower = response.lower()
    return any(phrase in response_lower for phrase in SPECULATION_PHRASES)


def evaluate_record(
    rec: dict,
    retriever: DocsRetriever,
    keywords: list[str] | None = None,
) -> dict:
    """Evaluate a single consult record against the three goals."""

    params = json.loads(rec.get('input_params', '{}'))
    question = params.get('question', '')
    response = rec.get('llm_response', '')

    # Get keywords (provided or heuristic)
    if not keywords:
        keywords = extract_keywords_heuristic(question)

    # Find primary keyword (most specific)
    specific = [k for k in keywords if is_specific_term(k)]
    primary_keyword = specific[0] if specific else (keywords[0] if keywords else None)

    # Goal 1: Check if primary term is documented
    if primary_keyword:
        is_documented, doc_files = check_term_documented(retriever, primary_keyword)
    else:
        is_documented, doc_files = False, []

    # Goal 2: Check for search issues (would need old search results)
    # This requires the augmented dataset; mark as unknown if not available
    goal2_unknown = True

    # Goal 3: Check for speculation/verbosity
    speculation = has_speculation(response)
    verbose = len(response) > 1500 and speculation

    return {
        'id': rec['id'],
        'timestamp': rec['timestamp'],
        'question': question,
        'keywords': keywords,
        'primary_keyword': primary_keyword,

        'goal1_doc_gap': {
            'has_gap': not is_documented,
            'primary_keyword': primary_keyword,
            'files_found': doc_files,
        },

        'goal2_search_issue': {
            'needs_improvement': False,  # Requires old search results to determine
            'unknown': goal2_unknown,
        },

        'goal3_other_failure': {
            'has_failure': speculation or verbose,
            'speculation_detected': speculation,
            'is_verbose': verbose,
            'response_length': len(response),
        },

        'response_preview': response[:300] if response else "",
    }


def evaluate_with_augmented(
    rec: dict,
    aug: dict,
    retriever: DocsRetriever,
) -> dict:
    """Evaluate a record using augmented data (with curated keywords and old search)."""

    params = json.loads(rec.get('input_params', '{}'))
    question = params.get('question', '')
    response = rec.get('llm_response', '')

    keywords = aug.get('curated_keywords', [])

    # Find primary keyword
    specific = [k for k in keywords if is_specific_term(k)]
    primary_keyword = specific[0] if specific else (keywords[0] if keywords else None)

    # Goal 1: Check if primary term is documented
    if primary_keyword:
        is_documented, doc_files = check_term_documented(retriever, primary_keyword)
    else:
        is_documented, doc_files = False, []

    # Goal 2: Check for search issues
    old_files = aug.get('old_search', {}).get('files', [])
    heredity_returned = any('heredity' in f for f in old_files)
    heredity_question = any(
        k.lower() in ['gene', 'chromosome', 'genome', 'heredity', 'genetic']
        for k in keywords
    )
    search_issue = heredity_returned and not heredity_question and is_documented

    # Goal 3: Check for speculation/verbosity
    speculation = has_speculation(response)
    verbose = len(response) > 1500 and speculation

    return {
        'id': rec['id'],
        'timestamp': rec['timestamp'],
        'question': question,
        'curated_keywords': keywords,
        'primary_keyword': primary_keyword,

        'goal1_doc_gap': {
            'has_gap': not is_documented,
            'primary_keyword': primary_keyword,
            'files_found': doc_files,
        },

        'goal2_search_issue': {
            'needs_improvement': search_issue,
            'old_files': old_files,
            'new_files': aug.get('new_search', {}).get('files', []),
        },

        'goal3_other_failure': {
            'has_failure': speculation or verbose,
            'speculation_detected': speculation,
            'is_verbose': verbose,
            'response_length': len(response),
        },

        'response_preview': response[:300] if response else "",
    }


def main():
    parser = argparse.ArgumentParser(description='Evaluate consultant export dataset')
    parser.add_argument('export_file', help='Path to consultant export JSON')
    parser.add_argument('--augmented', help='Path to augmented dataset with curated keywords')
    parser.add_argument('--output', help='Output file path (default: <input>-evaluation.json)')
    args = parser.parse_args()

    # Load export
    export_path = Path(args.export_file)
    with open(export_path) as f:
        data = json.load(f)

    records = data.get('requests', [])
    consult_records = [r for r in records if r.get('tool_name') == 'consult']

    print(f"Loaded {len(records)} total records, {len(consult_records)} consult records")

    # Load augmented if provided
    aug_map = {}
    if args.augmented:
        with open(args.augmented) as f:
            aug_data = json.load(f)
        aug_map = {r['id']: r for r in aug_data.get('records', [])}
        print(f"Loaded {len(aug_map)} augmented records")

    # Initialize retriever
    retriever = DocsRetriever()

    # Evaluate each record
    evaluation = []
    for rec in consult_records:
        if rec['id'] in aug_map:
            eval_rec = evaluate_with_augmented(rec, aug_map[rec['id']], retriever)
        else:
            eval_rec = evaluate_record(rec, retriever)
        evaluation.append(eval_rec)

    # Compute summary
    summary = {
        'total_records': len(evaluation),
        'goal1_doc_gaps': sum(1 for r in evaluation if r['goal1_doc_gap']['has_gap']),
        'goal2_search_issues': sum(1 for r in evaluation if r['goal2_search_issue'].get('needs_improvement', False)),
        'goal3_other_failures': sum(1 for r in evaluation if r['goal3_other_failure']['has_failure']),
    }

    # Find unique undocumented terms
    undoc_terms = {}
    for r in evaluation:
        if r['goal1_doc_gap']['has_gap']:
            term = r['primary_keyword']
            undoc_terms[term] = undoc_terms.get(term, 0) + 1

    # Build output
    output = {
        'metadata': {
            'source': str(export_path),
            'augmented': args.augmented,
            'total_records': len(evaluation),
        },
        'summary': summary,
        'undocumented_terms': [
            {'term': t, 'count': c}
            for t, c in sorted(undoc_terms.items(), key=lambda x: -x[1])
        ],
        'records': evaluation,
    }

    # Write output
    output_path = args.output or str(export_path).replace('.json', '-evaluation.json')
    with open(output_path, 'w') as f:
        json.dump(output, f, indent=2)

    # Print summary
    print(f"\n{'='*60}")
    print("EVALUATION SUMMARY")
    print('='*60)
    print(f"Total consult records: {summary['total_records']}")
    print(f"\nGoal 1 - Documentation gaps: {summary['goal1_doc_gaps']}/{summary['total_records']} ({100*summary['goal1_doc_gaps']//summary['total_records']}%)")
    print(f"Goal 2 - Search issues:      {summary['goal2_search_issues']}/{summary['total_records']} ({100*summary['goal2_search_issues']//summary['total_records']}%)")
    print(f"Goal 3 - Other failures:     {summary['goal3_other_failures']}/{summary['total_records']} ({100*summary['goal3_other_failures']//summary['total_records']}%)")

    if undoc_terms:
        print(f"\nUndocumented terms ({len(undoc_terms)} unique):")
        for item in output['undocumented_terms'][:10]:
            print(f"  - {item['term']}: {item['count']} records")

    print(f"\nOutput written to: {output_path}")


if __name__ == '__main__':
    main()
