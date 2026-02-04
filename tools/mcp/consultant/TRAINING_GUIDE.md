# AR-Consultant Training Guide

A methodology for systematically improving the ar-consultant MCP server using accumulated question-answer data.

## Overview

The ar-consultant accumulates request history from usage across Claude Code sessions. This guide describes how to analyze that data to identify and fix three types of issues:

1. **Documentation Gaps** - Questions about topics with no documentation
2. **Search Failures** - Documentation exists but isn't surfaced by search
3. **Response Quality** - Verbose or speculative responses

## Prerequisites

- Access to consultant export data (`export_request_history` tool)
- Python 3.10+ with the consultant module available
- Consultant backend running (for response validation)

## Methodology

### Step 1: Export Data

From a sandbox with consultant history:

```python
mcp__ar-consultant__export_request_history(
    include_prompts=True,
    include_chunks=True
)
```

This writes to a file and returns the path. Copy the export JSON to your working directory.

### Step 2: Initial Analysis

Run basic statistics:
- Total records by tool type (consult, recall, remember)
- Response length distribution
- Latency distribution

```bash
# Quick stats with jq
cat export.json | jq '.requests | length'
cat export.json | jq '[.requests[] | select(.tool_name=="consult")] | length'
```

### Step 3: Create Augmented Dataset

For each `consult` record, add curated keywords that a capable agent SHOULD have provided:

1. Identify the PRIMARY keyword (most specific term, usually a class/method name)
2. Add 3-5 supporting keywords ordered by specificity
3. Save as `<dataset>-augmented.json`

**Example:**
```json
{
  "question": "How do I create an attention layer for testing?",
  "curated_keywords": ["AttentionFeatures", "LayerFeatures", "attention", "Block"]
}
```

### Step 4: Run Evaluation

```bash
python3 scripts/evaluate_dataset.py export.json --augmented augmented.json
```

This classifies each record by the three goals:

**Goal 1 - Documentation Gap:**
- Searches for the PRIMARY keyword in docs
- If not found (excluding heredity noise): `has_gap = true`

**Goal 2 - Search Issue:**
- Checks if old search returned irrelevant results
- If relevant docs exist but weren't found: `needs_improvement = true`

**Goal 3 - Response Quality:**
- Checks for speculation phrases ("might be", "could be", etc.)
- Checks response length (>1500 chars with speculation = verbose)

Output: `<export>-evaluation.json`

### Step 5: Triage and Prioritize

From evaluation, extract:

1. **Doc gaps**: List unique undocumented terms, prioritize by frequency
2. **Search issues**: Count affected records
3. **Quality issues**: Count speculation/verbosity

### Step 6: Systematic Fix

#### Phase A: Documentation Gaps

For each undocumented term:
1. Add JavaDoc to the source file
2. Update module README.md
3. Update docs/modules/*.html

Locations to update:
- `<module>/src/main/java/.../*.java` - JavaDoc
- `<module>/README.md` - Module documentation
- `docs/modules/<module>.html` - HTML documentation

#### Phase B: Search Issues

The `keywords` parameter on `consult()` allows agents to guide search:

```python
consult(
    question="How do attention layers work?",
    keywords=["AttentionFeatures", "attention", "LayerFeatures"]
)
```

Ensure CLAUDE.md instructs agents to always provide keywords.

#### Phase C: Response Quality

Update `inference.py` SYSTEM_PROMPT to:
- Enforce conciseness (1-3 sentences when possible)
- Prohibit speculation ("might be", "could be")
- Require "Not documented" for missing info
- Use code references (`ClassName.method()`)

### Step 7: Re-Evaluate Documentation

After Phase A fixes:

```bash
python3 scripts/evaluate_dataset.py export.json \
    --augmented augmented.json \
    --output export-evaluation-after-fixes.json
```

Verify Goal 1 (doc gaps) dropped to 0%.

### Step 8: Validate Response Quality

The evaluation analyzes original responses. To validate Goal 3 improvements:

```bash
python3 scripts/validate_responses.py evaluation.json --sample 10
```

This re-runs questions through the consultant with the updated prompt and compares:
- Response length (should decrease significantly)
- Speculation (should be eliminated)

### Step 9: Production Verification

After deploying fixes:
1. Collect new consultant usage data
2. Run the same evaluation process
3. Compare metrics to baseline
4. Iterate if issues remain

## Scripts Reference

### evaluate_dataset.py

Evaluates an export against the three goals.

```bash
python3 scripts/evaluate_dataset.py <export.json> [--augmented <aug.json>] [--output <out.json>]
```

**Inputs:**
- `export.json` - Consultant export from `export_request_history`
- `augmented.json` (optional) - Dataset with curated keywords

**Outputs:**
- Evaluation JSON with per-record classification
- Summary: Goal 1/2/3 counts, undocumented terms

### validate_responses.py

Validates response quality by re-running questions.

```bash
python3 scripts/validate_responses.py <evaluation.json> [--sample N] [--output <out.json>]
```

**Inputs:**
- `evaluation.json` - Output from evaluate_dataset.py

**Outputs:**
- Validation JSON with old vs new comparison
- Summary: length change %, speculation fixed count

**Options:**
- `--sample N` - Validate only N random questions
- `--dry-run` - Show what would run without calling consultant

## Common Patterns

### Speculation Phrases to Detect

```python
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
```

### Documentation Locations

| Content Type | Location |
|--------------|----------|
| Class/method docs | JavaDoc in source files |
| Module overview | `<module>/README.md` |
| HTML docs | `docs/modules/<module>.html` |
| Quick reference | `docs/QUICK_REFERENCE.md` |

### Effective Keywords

Good keywords are:
- Specific class names: `AttentionFeatures`, `PackedCollection`
- Method names: `backward`, `compile`
- Domain terms: `LoRA`, `RoPE`, `diffusion`

Avoid generic words: `how`, `what`, `create`, `simple`, `use`

## Expected Results

After completing all phases:

| Goal | Expected Outcome |
|------|------------------|
| Goal 1 (Doc gaps) | 0% - all terms documented |
| Goal 2 (Search) | Fixed by keywords parameter |
| Goal 3 (Quality) | 50-90% length reduction, 0% speculation |

## Troubleshooting

**Evaluation shows high Goal 2 after fixes:**
This is expected - more documentation means more records where relevant docs exist but old search missed them. The keywords parameter addresses this.

**Validation script can't connect:**
Ensure consultant backend is running (llama.cpp, ollama, etc.). Check `AR_CONSULTANT_BACKEND` and related env vars.

**Some terms still show as undocumented:**
Verify the docs are in searchable locations (docs/ directory). Check that the search index includes the new files.
