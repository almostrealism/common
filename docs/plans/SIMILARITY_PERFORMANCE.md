# Similarity Performance Optimization Plan

## Phase 1: Completed Work

The following optimizations were implemented on `feature/similarity-performance`:

- **Cached Evaluable** (Strategy 1): `WaveDetailsFactory.cosineSimilarityEvaluable()` uses `cv()` placeholders to compile cosine similarity once and reuse it. Achieved 2.65x speedup.
- **Norm Caching** (Strategy 2): Rejected — splitting `length()` from the full expression tree caused numerical discrepancies.
- **Batched Pairwise** (Strategy 3): `WaveDetailsFactory.batchSimilarity()` compares a query against up to 100 targets in a single kernel call. 1.49x additional speedup.
- **Incremental/Lazy** (Strategy 5): `ApproximateSimilarityIndex` and `IncrementalSimilarityComputation` use mean-pooled embeddings with optional random projection to filter candidate pairs, reducing exact comparisons by ~90%.
- **Matrix Cosine Similarity** (Strategy 4): **Superseded.** The matrix multiply approach is no longer planned. The new `ProtobufDiskStore` + `HnswIndex` system provides a more principled solution to the same problem (avoiding O(N^2) exact comparisons) with persistence, caching, and approximate nearest neighbor search built in.

## Phase 2: Replace AudioLibraryPersistence with ProtobufDiskStore

### Goal

Migrate `AudioLibrary` from the current `HashMap`/`FrequencyCache`/`AudioLibraryPersistence` stack to use `ProtobufDiskStore<WaveDetailData>` as its backing store. This unifies persistence, caching, and vector indexing into a single component.

### Background: What Exists Today

- **`AudioLibrary`** holds a `FrequencyCache<String, WaveDetails>` (capacity 1000, 40% frequency weight) as its in-memory cache, plus a `Set<String> completeIdentifiers` tracking all known complete entries regardless of eviction state.
- **`AudioLibraryPersistence`** serializes/deserializes the library to batched protobuf files (`PREFIX_0.bin`, `PREFIX_1.bin`, ...) using the `AudioLibraryData` wrapper message. It also provides `createDetailsLoader()` which sets up on-demand reloading of evicted cache entries.
- **`WaveDetailData`** (`audio.proto`, package `org.almostrealism.audio.api`) is the existing protobuf message for a single audio sample's metadata and analysis results. It contains: identifier, sample rate, channel count, frame count, raw audio data, FFT frequency data, feature data, and a similarities map.

### What Changes

**`ProtobufDiskStore<WaveDetailData>`** replaces both `FrequencyCache` and `AudioLibraryPersistence`:

| Current Component | Replacement | Notes |
|---|---|---|
| `FrequencyCache<String, WaveDetails>` in `AudioLibrary` | `ProtobufDiskStore`'s internal `FrequencyCache` for parsed batches | Store manages its own cache with configurable memory cap (default 500 MB) |
| `AudioLibraryPersistence.saveLibrary()` / `loadLibrary()` | `ProtobufDiskStore.put()` / `get()` | Records persisted immediately on `put()`; no separate save step |
| `AudioLibraryPersistence.createDetailsLoader()` | `ProtobufDiskStore.get()` | Automatic on-demand loading from disk; no manual loader wiring |
| `completeIdentifiers` set | `ProtobufDiskStore` record index | The store's index tracks all record IDs |
| `AudioLibraryData` wrapper message | Not needed | `ProtobufDiskStore` uses length-delimited records in batch files with its own `DiskStoreIndex` |

### Protobuf Message Type

**`WaveDetailData`** from `audio.proto` is used directly. It already exists and contains all required fields. Usage:

```java
ProtobufDiskStore<WaveDetailData> store =
    new ProtobufDiskStore<>(rootDir, WaveDetailData.parser());
```

No new protobuf message definitions are needed.

### FrequencyCache: Do We Need Both?

**No.** `ProtobufDiskStore` replaces `AudioLibrary`'s `FrequencyCache`. The two caches operate at different granularities — `AudioLibrary` caches individual `WaveDetails` objects while `ProtobufDiskStore` caches parsed batches — but the store's batch-level caching is sufficient. `AudioLibrary` becomes a thin wrapper that:

1. Delegates storage to `ProtobufDiskStore`
2. Converts between `WaveDetails` (in-memory domain object) and `WaveDetailData` (protobuf)
3. Manages the `identifiers` map (file path → content hash)
4. Orchestrates similarity computation and prototype discovery

The `detailsCache` field and `detailsLoader` function in `AudioLibrary` are removed. All `get()`/`put()` operations go through the store.

### What Happens to AudioLibraryPersistence?

**Deprecated but retained for migration.** `AudioLibraryPersistence` stays in the codebase with `@Deprecated` annotation. It is needed to:

1. Read libraries saved in the old `AudioLibraryData` batch format
2. Convert them to the new `ProtobufDiskStore` format (one-time migration)

New code should not use `AudioLibraryPersistence` for saving. The migration utility (see Phase 5) reads old files via `AudioLibraryPersistence.loadLibrary()` and writes records via `ProtobufDiskStore.put()`.

### Files to Modify

- `AudioLibrary.java` — Replace `detailsCache`/`detailsLoader` with `DiskStore<WaveDetailData>` field. Delegate `get()`/`include()` to store. Add encode/decode between `WaveDetails` and `WaveDetailData`.
- `AudioLibraryPersistence.java` — Add `@Deprecated`. Move `encode()`/`decode()` methods to a shared utility (they're needed by both old and new paths).
- `audio/pom.xml` — May need dependency on `ar-ml` for `DiskStore` interface (verify with build).

## Phase 3: PrototypeDiscovery Powered by HnswIndex

### Goal

Replace the O(N^2) all-pairs similarity computation in `PrototypeDiscovery` with HNSW-based approximate nearest neighbor search, building a sparse similarity graph from top-K neighbors instead of exhaustive pairwise comparison.

### Current Flow

1. `AudioLibrary.computeAllSimilaritiesIncremental()` computes similarity for all pairs (O(N^2))
2. `AudioLibrary.toSimilarityGraph()` builds a graph from the `similarities` map on each `WaveDetails`
3. `PrototypeDiscovery.findPrototypesFromGraph()` runs Louvain community detection + PageRank
4. Result: prototype identifiers with communities

### New Flow

1. When features are computed for a `WaveDetails` entry, compute its embedding vector via mean-pooling (reuse `ApproximateSimilarityIndex.computeEmbedding()` logic) and insert into the store with:
   ```java
   PackedCollection embedding = computeEmbeddingVector(details);
   store.put(details.getIdentifier(), encode(details), embedding);
   ```
2. The `ProtobufDiskStore` automatically inserts the embedding into its `HnswIndex`.
3. To build the similarity graph for prototype discovery, use HNSW search instead of all-pairs:
   ```java
   for each sample s:
       List<SearchResult<WaveDetailData>> neighbors = store.search(embedding(s), K);
       for each neighbor n in neighbors:
           graph.addEdge(s.id, n.id, n.similarity);
   ```
   This produces a sparse K-nearest-neighbor graph in O(N * K * log(N)) time instead of O(N^2).
4. Louvain community detection and PageRank run on this sparse graph as before.

### Embedding Strategy

The embedding vector for HNSW is the **mean-pooled feature vector**: average each feature bin across all frames to produce a single vector of dimension `bins` (typically 32). This is the same strategy already implemented in `ApproximateSimilarityIndex.computeEmbedding()`.

This mean-pooled embedding captures the average spectral character of the sample. `HnswIndex` uses `SimilarityMetric.COSINE` which L2-normalizes vectors at insert time, so cosine similarity between embeddings is computed as a simple dot product during search.

### Relationship to ApproximateSimilarityIndex

`ApproximateSimilarityIndex` becomes **redundant** once `HnswIndex` is integrated. Both serve the same purpose (fast approximate similarity filtering) but `HnswIndex` is superior:

- O(log N) per query vs O(N) brute-force scan
- Persistent (survives restart via `hnsw.bin`)
- Integrated with `ProtobufDiskStore` lifecycle

`ApproximateSimilarityIndex` and `IncrementalSimilarityComputation` can be deprecated after the migration.

### Similarity Storage

Currently `WaveDetailData.similarities` stores all pairwise scores (O(N^2) entries). With HNSW, only the top-K neighbors per sample need to be stored (O(N*K) entries). This dramatically reduces:
- Protobuf file size on disk
- Memory consumption when loading records
- Time to serialize/deserialize

The `similarities` field on `WaveDetailData` can either:
- Store only top-K neighbors (breaking change for old consumers that expect all pairs)
- Be left empty and computed on-demand from `HnswIndex.search()`

**Decision: leave `similarities` empty in the new system.** Similarity data lives in the HNSW index, not in individual records. The `similarities` field is retained in the proto for backward compatibility with old files but is not populated by new code.

### K Value

The default K for nearest-neighbor search should be tuned but a reasonable starting point is **K = 20**. This means each sample connects to its 20 most similar neighbors in the graph. For a library of 1000 samples, this produces ~20,000 directed edges (10,000 undirected) vs 499,500 edges from all-pairs — a 50x reduction in graph density.

### Files to Modify

- `AudioLibrary.java` — Modify `computeDetails()` to also insert embedding vector into store. Replace `computeAllSimilaritiesIncremental()` and `computeSimilarities()` with HNSW-based neighbor lookup.
- `PrototypeDiscovery.java` — Update `run()` and `discoverPrototypes()` to build sparse graph from HNSW search results instead of full similarity map.
- `AudioSimilarityGraph.java` — May need to accept sparse graph construction (from neighbor lists rather than full similarity maps).

## Phase 4: Testing Strategy

### Integration Test: DiskStoreAudioLibraryTest

A comprehensive integration test that validates the full pipeline end-to-end.

**Setup:**
- Generate 1000+ audio samples as sine waves with varied frequencies (20 Hz to 20 kHz, logarithmically spaced)
- Each sample: 1 second duration, 44100 Hz sample rate, written as real `.wav` files to a temp directory

**Test 1: Store, compute features, persist, reload**
1. Create `AudioLibrary` backed by `ProtobufDiskStore`
2. Add all 1000 samples, compute features for each (FFT + feature extraction)
3. Verify all 1000 entries are in the store with feature data
4. Close and reopen the store from the same directory
5. Verify all 1000 entries reload with complete feature data (freq_data and feature_data present)
6. **Critical assertion:** No feature recomputation occurs on reload. Instrument `WaveDetailsFactory.forProvider()` call count — it must be 0 after reload.

**Test 2: HNSW search produces sensible results**
1. Using the loaded library from Test 1, search for neighbors of a 440 Hz sample
2. Assert that the top-5 results include samples with nearby frequencies (e.g., 415 Hz, 466 Hz)
3. Assert that distant frequencies (e.g., 100 Hz, 10 kHz) are NOT in the top-5

**Test 3: PrototypeDiscovery on sparse graph**
1. Run `PrototypeDiscovery` on the loaded library
2. Assert prototype count is reasonable (between 5 and 50 for 1000 samples)
3. Assert each community has at least 2 members
4. Assert prototype discovery completes in under 60 seconds

**Test 4: Memory stays bounded**
1. Configure store with `maxMemoryBytes = 50 * 1024 * 1024` (50 MB)
2. Load all 1000 entries, iterate through all, compute similarities
3. Assert JVM heap usage does not exceed 200 MB at any point (measured via `Runtime.getRuntime()`)
4. This catches the failure mode where loading a large library loads everything into memory

**Test 5: No recomputation on reload (the critical regression test)**
1. Save library with all features computed
2. Close store
3. Reopen store, create new `AudioLibrary` from it
4. Instrument the `WaveDataProvider` for each entry to detect any audio loading
5. Call `allDetails()` to stream all entries
6. Assert: zero audio loads occurred. All data comes from the store.

### Test Location

`studio/compose/src/test/java/org/almostrealism/audio/persistence/test/DiskStoreAudioLibraryTest.java`

All tests extend `TestSuiteBase`. Tests 3 and 4 use `@TestDepth(2)` since they are resource-intensive.

## Phase 5: Migration Path

### Old Format

`AudioLibraryPersistence` saves libraries as batched protobuf files:
- `PREFIX_0.bin`, `PREFIX_1.bin`, ... — each containing a `AudioLibraryData` message with a `map<string, WaveDetailData> info` field
- The last batch may include a `PrototypeIndex`

### New Format

`ProtobufDiskStore` uses:
- `batch_0000.bin`, `batch_0001.bin`, ... — length-delimited `WaveDetailData` records
- `index.bin` — `DiskStoreIndex` mapping record IDs to (batchId, byteOffset)
- `hnsw.bin` — `HnswIndexData` with HNSW graph and vectors

### Migration Utility

A static method `AudioLibraryPersistence.migrateToStore(String oldPrefix, File newStoreDir)`:

1. Load old library via `AudioLibraryPersistence.loadSingleDetail()` for each entry
2. For each `WaveDetails` with complete feature data:
   - Encode to `WaveDetailData` via existing `encode()` method
   - Compute embedding vector via mean-pooling
   - Call `store.put(identifier, encoded, embeddingVector)`
3. Flush and close the store
4. Migrate `PrototypeIndex` data separately (store it in a metadata file alongside the store)

### Backward Compatibility

- Old protobuf files continue to be readable via `AudioLibraryPersistence`
- `AudioLibrary` constructor accepts either a `DiskStore<WaveDetailData>` (new path) or a file prefix string (old path, triggers migration on first use)
- Migration is idempotent: if the new store directory already exists and is populated, skip migration
- The `similarities` map in old `WaveDetailData` records is preserved during migration but not used by new code

### Rollback

If the new system has issues, users can revert to the old path by deleting the store directory. The old batch files are not modified or deleted during migration.

## Implementation Order

1. **Phase 2 first**: Migrate `AudioLibrary` to use `ProtobufDiskStore`. This is the foundation — everything else depends on it.
2. **Phase 3 next**: Wire `HnswIndex` into the similarity computation path. This requires Phase 2 because embeddings are stored via `put(id, record, vector)`.
3. **Phase 4 in parallel**: Tests can be written alongside Phase 2 and Phase 3 implementation.
4. **Phase 5 last**: Migration utility only matters once Phases 2-3 are working.

## Validation

All changes validated against:
- `DiskStoreAudioLibraryTest` (new, Phase 4)
- Existing `AudioLibraryCacheTest`, `AudioSimilarityGraphTest`, `PrototypeDiscoveryBuildIndexTest`
- `SimilarityOverheadTest` in utils module (existing)
- Full `mvn clean install -DskipTests` build verification
