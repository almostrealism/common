---
status: proposed
created: 2026-11-03
closed:
tracker: [4675cf1c-28e9-43e2-851a-694483355241]
---

# Document the vector-index subsystem (HNSW + disk store + model-keyed rebuild)

## Category

Documentation.

## Motivation

A whole vector similarity-search subsystem landed in the last few days (PR #827 and the
commits around it: *Serialize HNSW index access and relax the model-declaration API*,
*Rebuild the HNSW index from stored records on a model change*, *Rebuild the HNSW index when
its model marker outlives the index*, *Clarify the same-model index-rebuild contract in
WaveDetailsStore*). It spans two layers:

- **`engine/ml` — `org.almostrealism.persist.index`:** `HnswIndex` (a Hierarchical Navigable
  Small World graph over `PackedCollection` vectors), `ProtobufDiskStore` (a length-delimited
  protobuf key-value store that integrates the index via `put(id, record, vector)` /
  `insertEmbedding(id, vector)` / `search(query, topK)`), `SimilarityMetric` (cosine by
  default), `SearchResult`, and the `DiskStore` interface.
- **`studio/compose` — `ProtobufWaveDetailsStore`:** the *model-keyed rebuild* contract —
  `setEmbeddingModel` declares which embedding model the index holds, `rebuildIndex`
  re-embeds every stored record's features for that model and re-inserts them, an `hnsw.model`
  marker records the owning model beside `hnsw.bin`, and records lacking the declared model's
  features are excluded until re-embedded.

The reference-level material exists — `engine/ml/README.md` has a solid "HnswIndex — Vector
Search" section, and every class carries careful Javadoc. But this is the project's recurring
*reference-exists / connective-tissue-missing* situation, on the newest subsystem:

1. **`llms.txt` does not mention the subsystem at all** — no entry for the vector index, the
   `persist.index` package, `HnswIndex`, or similarity search. A reader following the
   documentation index — or a model trained on the corpus — never discovers that the platform
   has a native-accelerated vector index.
2. **Two things live only in Javadoc and nowhere connective:**
   - **The index *is* a computation graph.** `SimilarityMetric` describes similarity as
     Producers (`normalize` = `divide(v, max(sqrt(sum(sq(v))), ε))`, `similarities` =
     `matmul(candidates, query)`), compiled *once* per index and reused: one dispatch
     normalizes an incoming vector directly into its row of a contiguous `[capacity, dimension]`
     store, one dispatch scores a query against the whole store, and all top-K and graph-edge
     decisions are made on the host from the resulting score array. This is a clean,
     self-contained exemplar of THE FUNDAMENTAL RULE — *Java orchestrates, native executes* —
     applied to a **non-training** workload. Most internals pages that teach this principle do
     so through ML training or DSP; a retrieval example broadens where a reader (human or model)
     sees the principle hold.
   - **The model-keyed rebuild contract**, which was the entire subject of the recent commits,
     is described only on `ProtobufWaveDetailsStore` / `ProtobufDiskStore`. It is the kind of
     subtle lifecycle invariant (one model per index; rebuild on change; exclusion until
     re-embedded) that a connective page should state once, plainly.

**Vision connection.** Vector retrieval is the substrate of retrieval-augmented
self-understanding: a system that studies itself needs to retrieve over its own corpus (and,
ultimately, its own source). The platform already embeds and searches — the `HnswIndex` is the
native-accelerated index underneath that capability. Documenting it connectively lets a model
trained on the corpus reason about the platform's own retrieval substrate, and records, for
humans, how similarity search is expressed in the computation-graph idiom rather than as host
loops.

## Scope

Concrete, actionable deliverables:

1. **New internals page `docs/internals/vector-index.md`** that walks the end-to-end pipeline:
   - **Storage and persistence** — `ProtobufDiskStore` layout (`index.bin`, `batch_*.bin`,
     `hnsw.bin`, `hnsw.model`), length-delimited records, `FrequencyCache`, the
     `put(id, record, vector)` / `insertEmbedding` / `search` surface and `SearchResult<T>`.
   - **The index as a computation graph** — `SimilarityMetric` (cosine: normalize = L2,
     `similarities` = `matmul`), compile-once/reuse of `normalizeVector` and `scoreAll`,
     the contiguous `[capacity, dimension]` store, normalize-into-row single dispatch,
     whole-store single-dispatch scoring, host-side top-K and layered-graph construction from
     exact neighbors, capacity growth (double + recompile), soft delete vs. hard removal and
     row reuse. State the current design plainly: exact whole-store scoring now, with the
     layered graph persisted from exact neighbors so a batched graph traversal can become the
     search strategy later without a format change.
   - **Save / load** — protobuf `HnswIndexData` / `HnswNodeData` / `HnswLayerNeighbors`,
     persisted edge scores so post-reload pruning needs no rescoring, and the backward-compatible
     unknown-score (`NaN`) edge behavior.
   - **Model-keyed rebuild** — `ProtobufWaveDetailsStore.setEmbeddingModel` → `rebuildIndex`,
     the `hnsw.model` marker, re-embedding stored features, and the
     exclusion-until-re-embedded property.
   - A short "Related Files" table (module-relative paths, no line numbers) and a "Known limits
     / future direction" note (exact scoring at current scale; `efSearch` retained for a future
     graph-walk path).
2. **A drift-guard examples test** in `engine/ml` (e.g. `VectorIndexExamplesTest`), modeled on
   the existing `HnswSearchTest`, extending `TestSuiteBase`, every `@Test` carrying a `timeout`
   and appropriate `@TestDepth`. It extracts and runs every *complete* code example the page
   shows and asserts **robust, backend-independent** properties — the planted nearest neighbor
   is recalled at top-1, search results survive a save/reload round trip unchanged, and a model
   change excludes then (after re-embedding) re-includes a record. No dimension reduction, no
   tolerance weakening, no environment-gated skips.
   - The model-keyed rebuild example may need a lightweight fixture at the `persist.index`
     layer rather than the full `ProtobufWaveDetailsStore` (which lives in `studio/compose` and
     pulls in audio-feature embedding). If the rebuild contract cannot be exercised cleanly from
     `engine/ml`, the page documents it from `ProtobufWaveDetailsStore` and the examples test
     asserts the underlying `ProtobufDiskStore` index-model marker / rebuild primitive it rests
     on; the `studio/compose` behavior stays guarded by its existing tests
     (`DiskStoreAudioLibraryTest`, `AudioLibraryMigrationTest`). Decide this during
     implementation and record it in the page.
3. **Index and cross-link:** add `docs/internals/vector-index.md` and the
   `org.almostrealism.persist.index` package to `llms.txt`; cross-link the page from
   `engine/ml/README.md`'s "HnswIndex — Vector Search" section.

## Approach

- This is a **documentation cycle**: no production behavior changes. The page describes what
  ships today, grounded in the code (`HnswIndex`, `ProtobufDiskStore`, `SimilarityMetric`,
  `SearchResult`, `ProtobufWaveDetailsStore`) and in the existing `HnswSearchTest`.
- Reference code only by **stable identifiers** (class/method/field/package names) — never
  source line numbers — per `docs/CLAUDE.md`. Name files (without `:NNN`) in the Related Files
  table.
- Model the drift-guard on `HnswSearchTest` (standalone index ops, store integration,
  persistence across restart, deletion). Keep examples small enough to run on any backend
  quickly, but never trivially small — assert meaningful recall/persistence/rebuild properties.
- Any rough edge the writing surfaces in the API (e.g. a confusing name, a gap in the rebuild
  contract) is recorded as a **follow-up tracker task**, not fixed on this branch — this is a
  documentation cycle, not an API change.
- Validate with the build validator (checkstyle/code-policy/test-timeouts) and run the new
  examples test (and `HnswSearchTest`) narrowly before declaring done.

## Success Criteria

- `docs/internals/vector-index.md` exists, walks the full pipeline above, and cites code by
  stable identifiers only.
- Every complete code example on the page is exercised by the drift-guard examples test, which
  passes on a CPU/any backend and asserts robust properties (top-1 recall of a planted
  neighbor, save/reload identity, model-change exclusion-then-inclusion) without reduced
  dimensions, loosened tolerances, or skip-gating.
- `llms.txt` indexes the new page and the `persist.index` package; `engine/ml/README.md`
  cross-links the page.
- `mvn clean install -DskipTests` succeeds; the build validator reports no new violations.

## Dependencies

None. The subsystem is merged to `master` (PR #827 and related). The drift-guard needs no
curated download and no macOS/Metal node — it runs on any backend, like `HnswSearchTest`.

## Open questions

These are for the person approving the plan; none is a change for this cycle. The subsystem
carries three already-tracked code-level fragilities. This is a documentation cycle, so the page
documents behavior as it ships and does **not** fix them (consistent with the Approach note that
rough edges become follow-up tasks, not branch changes) — but the "Known limits" section should be
grounded in them rather than omit them, and the implementer should read the tasks before writing it:

- **`insertEmbedding` after `close()` silently rebuilds an empty index.** `ProtobufDiskStore.close()`
  nulls the index, and `insertEmbedding` does not re-check the closed flag, so a call after close
  creates a fresh, empty index via `ensureHnswIndex` instead of failing (there is a `TODO(review)`
  on the method). Tracked as `9829c7af-1ba2-4ab1-83e0-75ce52a9b8d2`.
- **The index structure is created keyed on vector dimension, not model identity.**
  `ProtobufDiskStore` does record the owning model — `setIndexModel` persists the `hnsw.model`
  marker and discards the index on a model change — but the index itself is built by
  `ensureHnswIndex`, which keys only on the first vector's dimension. The re-embedding half of the
  rebuild contract lives a layer above, in `ProtobufWaveDetailsStore.setEmbeddingModel` →
  `rebuildIndex`. The "Model-keyed rebuild" section should keep the marker, the dimension-keyed
  structure, and the re-embedding step distinct. Task `0ca7f34b-ade1-45ca-9699-65eb370a9851`
  proposes tracking model identity more robustly at the disk-store layer.
- **A reopened index is not rebuilt after a feature-width change during backfill.** Tracked as
  `d46ca647-1844-41cc-982b-bef06c7c1c41`.

These task ids are context for the implementer and the approver; they should inform how the page
describes known limits, but the durable page itself describes behavior in prose rather than citing
opaque task ids.

## Estimated Complexity

Medium. One new internals page, one new drift-guard test, and index/README cross-links; no
production code change. Runs on any node, so no workstream settings file accompanies this plan.
