# ar-memory Schema Evolution Proposal

Status: **PROPOSAL — awaiting approval.** No migration has been applied.
Author context: quality-control audit of the consultant/memory corpus,
2026-07-03. All numbers below come from a live census of the store
(`tools/mcp/consultant/qc/memory_census.py`, 9,521 entries).

## 1. Why change the schema

The current `entries` table
(`id, namespace, content, tags, source, created_at, repo_url, branch`)
records what a memory *says* but nothing about whether it is *true*,
*used*, or *superseded*. The census surfaced concrete consequences:

- **Wrong/obsolete memories are indistinguishable from correct ones.**
  A memory that made a claim later proven false stays in the index at
  full ranking weight forever. There is no supersession, no verification
  state, no staleness signal beyond `recall` asking the LLM to guess.
- **Backend-down dumps were stored as knowledge.** 37 entries (1.3%)
  begin with `[Consultant model not available. Returning raw context.]`
  or leak the reformulation prompt. Nothing marks them as non-knowledge,
  so they compete for recall slots.
- **No usage signal.** We cannot tell a load-bearing memory (recalled
  weekly, drove real fixes) from one never retrieved since it was
  written. Ranking is pure embedding distance.
- **Redundancy is invisible at write time.** 232 near-duplicate entries;
  the import path does an ad-hoc semantic check but no hash is stored, so
  dedup cannot be enforced or audited.
- **Provenance is lost.** ~43% of memories bypass reformulation (stored
  verbatim by jobs); we cannot filter or weight by how a memory was made.

The design goal is **additive and backward-compatible**: every new column
is nullable or defaulted, existing rows keep working, and the FAISS index
is untouched. This mirrors the existing `_migrate_add_columns` pattern in
`store.py`, which already added `repo_url`/`branch` the same way.

## 2. Proposed columns

All columns are added to `entries`. None are required at write time.

| Column | Type | Default | Purpose |
|---|---|---|---|
| `content_hash` | TEXT | NULL | SHA-256 of `content`, set on write. Enables exact-dedup enforcement and audit without rescanning. |
| `origin` | TEXT | `'unknown'` | Provenance: `interactive-remember`, `job`, `import`, `passthrough-fallback`. Lets recall exclude non-knowledge and weight by source. |
| `embedding_source` | TEXT | `'content'` | Which text was embedded: `reformulated` (store_dual) or `original`. Enables A/B of reformulation vs raw retrieval. |
| `verification_status` | TEXT | `'unverified'` | `unverified` \| `verified` \| `refuted` \| `stale`. The truth lifecycle. |
| `verified_at` | TEXT | NULL | ISO-8601 timestamp of the last verification pass. |
| `confidence` | REAL | NULL | 0.0–1.0 score from an author or a judge model. |
| `superseded_by` | TEXT | NULL | `id` of the memory that replaces this one. Non-destructive correction. |
| `hit_count` | INTEGER | 0 | Times this entry was returned by a search that fed an answer. |
| `last_hit_at` | TEXT | NULL | ISO-8601 timestamp of the most recent hit. |
| `session_id` | TEXT | NULL | The session/job that created the memory, for downstream-outcome joins. |

### Design notes

- **`verification_status` is a lifecycle, not a boolean.** `refuted`
  memories are *kept* (they document a dead end) but down-ranked and
  excluded from default recall. `stale` is auto-assigned when a memory
  cites a `file:line` / class that no longer exists in the current tree.
- **`superseded_by` replaces deletion.** When a new memory corrects an
  old one, we link rather than delete, preserving the audit trail and
  letting `recall` follow the chain to the current truth.
- **`hit_count` / `last_hit_at` turn recall into a feedback loop.** A
  memory never recalled in 4 months is a candidate for archival; a
  frequently-recalled one is a candidate for promotion into documentation.

## 3. Migration

Additive `ALTER TABLE`, run once at startup, exactly like the existing
column migration:

```python
def _migrate_add_qc_columns(self):
    cursor = self._conn.execute("PRAGMA table_info(entries)")
    columns = {row[1] for row in cursor.fetchall()}
    additions = {
        "content_hash": "TEXT",
        "origin": "TEXT DEFAULT 'unknown'",
        "embedding_source": "TEXT DEFAULT 'content'",
        "verification_status": "TEXT DEFAULT 'unverified'",
        "verified_at": "TEXT",
        "confidence": "REAL",
        "superseded_by": "TEXT",
        "hit_count": "INTEGER DEFAULT 0",
        "last_hit_at": "TEXT",
        "session_id": "TEXT",
    }
    for name, decl in additions.items():
        if name not in columns:
            self._conn.execute(f"ALTER TABLE entries ADD COLUMN {name} {decl}")
    self._conn.commit()
```

Add indexes for the columns recall will filter/sort on:
`idx_entries_verification (verification_status)`,
`idx_entries_superseded (superseded_by)`,
`idx_entries_content_hash (content_hash)`.

No FAISS change. No row rewrite. Reversible by ignoring the columns.

## 4. Backfill (transition tooling)

A one-time backfill populates the new columns for the 9,521 existing
rows. Shipped as `tools/mcp/memory/backfill_qc_metadata.py` (companion to
this proposal), **dry-run by default**, writing a report of proposed
values before anything is committed:

1. `content_hash` — compute SHA-256 for every row.
2. `origin` — classify:
   - passthrough banner / prompt leak in content → `passthrough-fallback`
     (these 37 rows also get `verification_status = 'refuted'`).
   - `source` is a `{"original": ...}` wrapper → `interactive-remember`
     with `embedding_source = 'reformulated'`.
   - otherwise → `job` (verbatim), `embedding_source = 'original'`.
3. `verification_status` — default `unverified`; passthrough rows
   `refuted`; rows whose content cites a `file:line`/class absent from the
   current tree flagged `stale` (opt-in, needs a checkout to verify).
4. exact-duplicate groups (by `content_hash`) reported for supersession:
   keep the newest, set `superseded_by` on the rest.

## 5. Using the new features

### 5.1 Recall ranking (`store.py` `search`)

Today `search` returns pure L2 distance with a repo/branch/tag
post-filter. The proposal adds, after the vector search and before
returning:

- **Exclude by default:** `verification_status = 'refuted'`,
  `origin = 'passthrough-fallback'`, and rows with a non-null
  `superseded_by` (follow the link to the successor instead).
- **Re-rank:** blend embedding distance with a recency/usage/verification
  boost, e.g. `effective_score = L2 * penalty` where `penalty` grows for
  stale/unverified and shrinks for verified + recently-hit. Kept behind a
  flag so behavior is opt-in and measurable.
- **Record the hit:** increment `hit_count` and set `last_hit_at` for
  every returned row (best-effort, never blocks the read path).

### 5.2 New/changed endpoints

Additive HTTP routes on `ar-memory` (no breaking changes):

- `POST /api/memory/{id}/verify` — set `verification_status`,
  `verified_at`, `confidence`.
- `POST /api/memory/{id}/supersede` — set `superseded_by` on the old row.
- `GET  /api/memory/quality` — corpus QC rollup (counts by
  `verification_status`, `origin`, dupes, never-hit) so the census can run
  server-side instead of pulling the whole corpus.

### 5.3 Write-path guard (independent of the schema; do this first)

Regardless of the schema decision, `remember` and the store endpoint
should **refuse to persist a passthrough/prompt-leak dump as a memory**
(detect the banner strings) and instead return an error telling the caller
the backend was down. This stops new garbage at the source; the backfill
only cleans up the existing 37.

## 6. Rollout phases

1. **Write-path guard** (no schema change) — stop new passthrough garbage.
2. **Migration** — add columns + indexes (additive, reversible).
3. **Backfill** — dry-run, review report, then commit metadata.
4. **Recall ranking** — behind a flag; A/B against the golden eval set
   (see the QC plan's Workstream B) before making it the default.
5. **Verify/supersede tooling** — wire the detector from Workstream C to
   populate `verification_status` / `superseded_by` on an ongoing basis.

Each phase is independently valuable and independently revertible.
