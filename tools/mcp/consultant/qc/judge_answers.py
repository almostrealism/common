#!/usr/bin/env python3
"""
Grade consultant answers with a strong model (LLM-as-judge).

The history analyzer (``analyze_history.py``) reports cheap proxies. This
tool measures the thing that matters: given the question and the exact
documentation the consultant retrieved, was the answer GROUNDED, CORRECT,
and free of speculation? It samples consult records from ``history.db``,
reconstructs each one's retrieved context from the ``doc_chunks`` table,
and asks a judge model to score it.

The judge is a *strong* model by default (Anthropic API) — judging with the
same weak model we are auditing gives a weak signal. A ``local`` backend is
provided for a free smoke test against the consultant's own llama.cpp.

Sampling (default) estimates corpus-wide rates cheaply; pass ``--all`` for a
full sweep. ``--dry-run`` builds and prints prompts without any network call,
so you can review exactly what the judge will see and what it will cost.

Usage:
    # Free smoke test against the local llama.cpp backend:
    python3 judge_answers.py --backend local --sample 5

    # Real judging with a strong model (needs ANTHROPIC_API_KEY):
    ANTHROPIC_API_KEY=... python3 judge_answers.py --backend anthropic \
        --model claude-sonnet-5 --sample 100 --out /tmp/judge.json

    # Inspect prompts / estimate scope without calling anything:
    python3 judge_answers.py --sample 20 --dry-run

Environment:
    ANTHROPIC_API_KEY          required for --backend anthropic
    AR_CONSULTANT_LLAMACPP_URL  local backend URL (default host.docker.internal:8084)
"""

import argparse
import json
import os
import sqlite3
import urllib.request
import urllib.error

# Deterministic sampling without Math.random-style nondeterminism concerns:
# a fixed seed makes runs reproducible so re-grading the same sample compares
# like-for-like.
import random

_DEFAULT_DB = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "data", "history.db"
)
_DEFAULT_LOCAL_URL = os.environ.get(
    "AR_CONSULTANT_LLAMACPP_URL", "http://host.docker.internal:8084"
)
_ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"

JUDGE_SYSTEM = (
    "You are a strict evaluator of a documentation assistant for a large "
    "Java/native ML codebase. You are given a QUESTION, the exact "
    "DOCUMENTATION CONTEXT the assistant retrieved, and its ANSWER. Judge "
    "only against the provided context and general software-engineering "
    "correctness. Do not reward fluent answers that are not supported by "
    "the context. Respond with a single JSON object and nothing else."
)

JUDGE_INSTRUCTIONS = (
    "Score each dimension 1-5 (5 best):\n"
    "- grounded: is every factual claim supported by the retrieved context?\n"
    "- correct: is the answer technically correct?\n"
    "- complete: does it actually answer the question asked?\n"
    "- no_speculation: 5 = no guessing; 1 = mostly hedging/speculation.\n"
    "Then set verdict to one of: good, borderline, bad.\n"
    'Return exactly: {"grounded":N,"correct":N,"complete":N,'
    '"no_speculation":N,"verdict":"...","rationale":"one sentence"}'
)


def sample_records(db_path, sample_size, take_all):
    """Load consult records that have a real (non-passthrough) answer.

    Each record is returned with its reconstructed retrieval context.
    """
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT id, input_params, llm_response, backend, doc_result_count "
        "FROM requests WHERE tool_name = 'consult' "
        "AND llm_response IS NOT NULL AND llm_response != ''"
    ).fetchall()

    usable = [
        row for row in rows
        if not (row["backend"] or "").startswith("passthrough")
        and not (row["llm_response"] or "").lstrip().startswith(
            "[Consultant model not available"
        )
    ]

    if not take_all and sample_size < len(usable):
        rng = random.Random(1337)
        usable = rng.sample(usable, sample_size)

    records = []
    for row in usable:
        chunks = conn.execute(
            "SELECT file, line, context FROM doc_chunks WHERE request_id = ?",
            (row["id"],),
        ).fetchall()
        try:
            question = json.loads(row["input_params"] or "{}").get("question", "")
        except json.JSONDecodeError:
            question = ""
        records.append({
            "id": row["id"],
            "question": question,
            "answer": row["llm_response"],
            "doc_count": row["doc_result_count"],
            "context": "\n\n".join(
                f"[{c['file']}:{c['line']}]\n{c['context']}" for c in chunks
            ),
        })
    conn.close()
    return records


def build_prompt(record):
    """Build the judge user-prompt for one record."""
    context = record["context"] or "(no documentation was retrieved)"
    return (
        f"## QUESTION\n{record['question']}\n\n"
        f"## DOCUMENTATION CONTEXT\n{context}\n\n"
        f"## ANSWER\n{record['answer']}\n\n"
        f"## TASK\n{JUDGE_INSTRUCTIONS}"
    )


def _extract_json(text):
    """Extract the first JSON object from a model response."""
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None


def call_anthropic(prompt, model, timeout=60):
    """Call the Anthropic Messages API and return the text response."""
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY not set (required for --backend anthropic)")
    body = json.dumps({
        "model": model,
        "max_tokens": 512,
        "system": JUDGE_SYSTEM,
        "messages": [{"role": "user", "content": prompt}],
    }).encode("utf-8")
    req = urllib.request.Request(
        _ANTHROPIC_URL, data=body,
        headers={
            "content-type": "application/json",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    parts = payload.get("content", [])
    return "".join(p.get("text", "") for p in parts if p.get("type") == "text")


def call_local(prompt, model, timeout=120):
    """Call the local llama.cpp OpenAI-compatible endpoint (smoke test)."""
    body = json.dumps({
        "model": model or "local",
        "max_tokens": 512,
        "temperature": 0.0,
        "messages": [
            {"role": "system", "content": JUDGE_SYSTEM},
            {"role": "user", "content": prompt},
        ],
    }).encode("utf-8")
    req = urllib.request.Request(
        f"{_DEFAULT_LOCAL_URL}/v1/chat/completions", data=body,
        headers={"content-type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    return payload["choices"][0]["message"]["content"]


def judge(record, backend, model):
    """Grade one record; return the parsed verdict plus metadata."""
    prompt = build_prompt(record)
    try:
        raw = call_local(prompt, model) if backend == "local" \
            else call_anthropic(prompt, model)
    except (urllib.error.URLError, RuntimeError, KeyError) as exc:
        return {"id": record["id"], "error": str(exc)}
    verdict = _extract_json(raw)
    if verdict is None:
        return {"id": record["id"], "error": "unparseable judge output",
                "raw": raw[:200]}
    verdict["id"] = record["id"]
    verdict["doc_count"] = record["doc_count"]
    return verdict


def aggregate(verdicts):
    """Summarize graded verdicts."""
    scored = [v for v in verdicts if "error" not in v]
    if not scored:
        return {"graded": 0, "errors": len(verdicts)}
    dims = ("grounded", "correct", "complete", "no_speculation")

    def avg(key):
        vals = [v[key] for v in scored if isinstance(v.get(key), (int, float))]
        return round(sum(vals) / len(vals), 2) if vals else None

    verdict_counts = {}
    for v in scored:
        verdict_counts[v.get("verdict", "?")] = verdict_counts.get(v.get("verdict", "?"), 0) + 1

    return {
        "graded": len(scored),
        "errors": len(verdicts) - len(scored),
        "avg": {dim: avg(dim) for dim in dims},
        "verdicts": verdict_counts,
    }


def main():
    parser = argparse.ArgumentParser(description="LLM-as-judge for consultant answers")
    parser.add_argument("--db", default=_DEFAULT_DB, help="Path to history.db")
    parser.add_argument("--backend", choices=("anthropic", "local"),
                        default="anthropic", help="Judge backend")
    parser.add_argument("--model", default="claude-sonnet-5",
                        help="Judge model (anthropic backend)")
    parser.add_argument("--sample", type=int, default=50,
                        help="Number of records to grade")
    parser.add_argument("--all", action="store_true",
                        help="Grade every usable record (ignore --sample)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Build prompts and report scope without calling any model")
    parser.add_argument("--out", help="Write full results JSON here")
    args = parser.parse_args()

    records = sample_records(args.db, args.sample, args.all)
    print(f"Selected {len(records)} consult records to judge "
          f"(backend={args.backend}, model={args.model})")

    if args.dry_run:
        no_ctx = sum(1 for r in records if not r["context"])
        print(f"[DRY RUN] {no_ctx} of {len(records)} have NO retrieved context.")
        if records:
            print("\n--- example judge prompt (first record) ---")
            print(build_prompt(records[0])[:1200])
        return

    verdicts = []
    for i, record in enumerate(records, 1):
        result = judge(record, args.backend, args.model)
        verdicts.append(result)
        flag = "ERR" if "error" in result else result.get("verdict", "?")
        print(f"  [{i}/{len(records)}] {record['id'][:8]}  -> {flag}")

    summary = aggregate(verdicts)
    print("\n" + "=" * 56)
    print("JUDGE SUMMARY")
    print("=" * 56)
    print(json.dumps(summary, indent=2))

    if args.out:
        with open(args.out, "w") as handle:
            json.dump({"summary": summary, "verdicts": verdicts}, handle, indent=2)
        print(f"\nFull results written to: {args.out}")


if __name__ == "__main__":
    main()
