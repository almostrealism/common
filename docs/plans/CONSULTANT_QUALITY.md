# ar-consultant Data Quality — Plan & Status

Owner: quality-control effort on the ar-consultant / ar-memory data set.
Last updated: 2026-07-03.

## Purpose

After months of use, the consultant has accumulated ~9,500 memories and a
5-month request history. This effort answers a set of quality questions and
builds the tooling to keep answering them as the corpus grows:

1. What happens to memories that make claims which later prove wrong?
2. Is the weaker local model (used for reformulation) degrading stored
   memories, especially large ones with complex reasoning?
3. How often do we give good answers — and how would we even know?
4. Is the set of interaction patterns the consultant exposes the right set?
5. Do the results point to value in fine-tuning on the codebase?
6. Are we creating too many memories to sort through usefully?

## Data model (two stores)

| Store | Location | Purpose | QC before this effort |
|---|---|---|---|
| **Memories** | `ar-memory` HTTP service (`host.docker.internal:8020` from the container), SQLite `entries` + per-namespace FAISS `IndexFlatL2` | knowledge recall | none |
| **Request history** | `tools/mcp/consultant/data/history.db` (SQLite) | every consultant tool call, for quality/latency/fine-tuning analysis | partial (consult-only, string heuristics) |

Key mechanics discovered:
- `remember` reformulates each note through the **local weak model**
  (qwen2.5-coder Q4) and `store_dual` makes the **reformulated** text the
  primary embedded/searched content. Only interactive `remember` does this;
  FlowTree jobs (`ar-manager memory_store`) store **verbatim**.
- Each namespace has its **own** FAISS index, so namespaces do not pollute
  each other's semantic search. `workstream_context` retrieves by
  `search_by_branch` (pure SQL recency), **not** semantic search.

## Findings from the live audit (2026-07-03)

Corpus census (9,521 memories) and history analysis (862 calls, 418 consults):

| Finding | Measurement | Implication |
|---|---|---|
| **Retrieval whiff** | 24.4% of consults retrieved **zero** docs (94% supplied keywords) | Dominant quality problem is **retrieval/coverage, not synthesis** → fine-tuning the generator won't fix it |
| **Backend-down garbage** | 36 memories (1.3%) are passthrough dumps stored as knowledge | Concrete noise; fixed at the write path |
| **`messages` bloat** | 6,767 entries = 71% of corpus; 1,234 of 1,236 exact duplicates | Operational chatter in the semantic store; retrieved by branch, never usefully by similarity |
| **Reformulation coverage** | 57.5% reformulated / 42.5% verbatim (non-messages) | Weak-model rewrite is inconsistent and lossy (observed dropping facts live) |
| **No memory lifecycle** | schema has no verify / supersede / hit-count / origin | Wrong memories are indistinguishable from correct ones forever |
| **Uncontrolled tags** | 5,016 distinct, 68% used once | Tags unreliable as a filter facet |
| **Speculation** | 8.9% of answers | Anti-speculation prompt work is holding |
| **Latency** | consult p50 9.9s, p99 34s | Slow; backend/model dependent |

## The five workstreams

- **A — Instrument & measure.** Turn the two stores into repeatable health
  reports. *(census + history analyzer built.)*
- **B — Answer-quality judging.** Replace string heuristics with a strong
  LLM-as-judge over the retrieved context; a curated golden set for
  regression. *(judge harness built; golden set pending.)*
- **C — Memory lifecycle & correctness.** Give memories a truth lifecycle
  (verify / supersede / staleness); additive schema. *(proposal written;
  migration pending approval.)*
- **D — Interaction-pattern & scaling analysis.** Which tools/patterns are
  used, recall precision@k, corpus growth/saturation. *(partially covered by
  history analyzer; deeper analysis pending.)*
- **E — Fine-tuning feasibility.** Decide with evidence. Early signal (24%
  whiff) says the first bottleneck is retrieval, not synthesis.

## Current status

### Done and validated
- **Write-path guard** (`tools/mcp/memory/store.py`
  `MemoryStore.store` + `is_passthrough_dump`, and an early guard in
  `tools/mcp/consultant/server.py` `remember`): refuses to store a
  backend-down passthrough dump as a memory. Tests:
  `tools/mcp/memory/test_passthrough_guard.py` (6, passing).
- **Corpus census** — `tools/mcp/consultant/qc/memory_census.py`.
- **History analyzer** — `tools/mcp/consultant/qc/analyze_history.py`.
- **LLM-as-judge harness** — `tools/mcp/consultant/qc/judge_answers.py`
  (Anthropic API by default; `--backend local` for a free smoke test;
  `--dry-run`).
- **Schema evolution proposal** —
  `tools/mcp/memory/SCHEMA_EVOLUTION_PROPOSAL.md` and its dry-run transition
  tool `tools/mcp/memory/backfill_qc_metadata.py`.
- **`messages` de-indexing** — messages stored for branch retrieval only,
  excluded from the semantic FAISS index (see below).

### Pending
- Run the strong-model judge at sample scale (needs `ANTHROPIC_API_KEY`) to
  convert proxies into a good/borderline/bad rate.
- Curate a golden eval set under `tools/mcp/consultant/eval/`.
- Approve the schema proposal → apply migration + backfill.
- Downstream-outcome linkage via the transcript analyzer (did a consult help
  the session that made it?).
- Documentation-coverage work to attack the 24% retrieval whiff.
- Deferred: evaluate a **GLM / MiniMax** backend that fits the M1 192 GB, to
  replace qwen for reformulation/synthesis.

## Tooling reference

| Tool | Runs against | Answers |
|---|---|---|
| `qc/memory_census.py` | ar-memory HTTP | corpus health: volume, repos, reformulation, size, dupes, tags |
| `qc/analyze_history.py` | `history.db` | answer-quality proxies: whiff, passthrough, speculation, latency, trend |
| `qc/judge_answers.py` | `history.db` + judge model | grounded/correct/complete/no-speculation verdicts |
| `memory/backfill_qc_metadata.py` | ar-memory HTTP or SQLite | derives proposed QC columns (dry-run) |

## `messages` de-indexing (this change)

`messages` (71% of the corpus, retrieved only by `workstream_context` via
`search_by_branch`) is designated a **non-semantic namespace**: its entries
are written to SQLite (so branch/recency retrieval keeps working) but are
**not embedded** and **not added to the FAISS index**. This removes the
largest source of embedding cost and index bloat with no loss to how
messages are actually retrieved. The set is configurable via
`AR_MEMORY_NON_SEMANTIC_NAMESPACES` (default `messages`).
