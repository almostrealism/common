# The Vector Index: HNSW, the Disk Store, and the Model-Keyed Rebuild

The platform has a vector similarity-search subsystem with native acceleration. Records are
stored on disk as protobuf messages. Each record can carry an embedding vector, and a query
vector returns the most similar records. This page walks the pipeline from end to end: how
records and vectors are laid out on disk, how similarity is expressed as a computation graph,
how the index is saved and reloaded, and how the index is tied to the embedding model that
produced its vectors.

Each class has careful Javadoc, and the API reference lives in the
["HnswIndex — Vector Search"](../../engine/ml/README.md#hnswindex--vector-search) and
"Protobuf Disk Store" sections of the `engine/ml` README. This page shows how the pieces fit
together. It does not repeat the reference.

The worked examples are in `VectorIndexExamplesTest` in `engine/ml`
(`org.almostrealism.persist.index.test`). Code blocks on this page are taken from that test
unless they are marked as fragments. The test also asserts each behavioral claim the page makes
about its example: top-1 recall of a planted neighbor, unchanged results after a save and reload,
and exclusion after a model change until the records are re-embedded.

---

## 1. Where each part lives

| Part | Module | Classes |
|---|---|---|
| The index itself | `engine/ml` (`org.almostrealism.persist.index`) | `HnswIndex`, `SimilarityMetric` |
| Record storage with an integrated index | `engine/ml` (`org.almostrealism.persist.index`) | `ProtobufDiskStore`, `DiskStore`, `SearchResult` |
| On-disk index format | `engine/ml` (`src/main/proto/diskstore.proto`) | `HnswIndexData`, `HnswNodeData`, `HnswLayerNeighbors` |
| Re-embedding stored records for a model | `studio/compose` (`org.almostrealism.studio.persistence`) | `ProtobufWaveDetailsStore` |

The general machinery is in `engine/ml` and knows nothing about audio. A `ProtobufDiskStore`
holds opaque protobuf records, so it can discard and rebuild its index, but it cannot
recompute embeddings. Re-embedding needs to understand the records, and the only consumer
that does is `ProtobufWaveDetailsStore`, which stores audio feature records.

---

## 2. Storage and persistence

`ProtobufDiskStore<T extends Message>` is a key-value store. Records are written with
`writeDelimitedTo` straight into batch files. There are no wrapper messages, so nothing is
serialized twice. One store directory holds:

```
<store-root>/
├── index.bin          # DiskStoreIndex: record ID → (batch_id, byte_offset)
├── batch_0000.bin     # length-delimited records of the caller's own message type
├── batch_0001.bin
├── hnsw.bin           # HnswIndexData: the vector index (only once a vector was stored)
└── hnsw.model         # identifier of the model whose vectors hnsw.bin holds (if declared)
```

`index.bin` is loaded into memory on startup. Batches are read on demand and kept in a
`FrequencyCache`, sized from the store's memory budget. New records wait in a pending batch,
which is written when it reaches the target batch size, on `flush()`, or on `close()`. The
vector index is written on `flush()` and `close()`.

There are three vector operations on the store:

- `put(id, record, vector)` stores the record and inserts (or replaces) its vector.
  `put(id, record)` without a vector removes any stale vector for that id, so a record that is
  replaced without one stops appearing in search results.
- `insertEmbedding(id, vector)` adds a vector for a record that is already stored, without
  rewriting the record. This is the backfill and rebuild primitive.
- `search(queryVector, topK)` returns `List<SearchResult<T>>`. Each result has the id, the
  record (fetched from the store) and the similarity, with the best result first. A query whose
  width differs from the indexed vectors returns an empty list, because such a query comes from
  a different model and nothing in the index can be compared with it.

```java
// VectorIndexExamplesTest.searchResultsSurviveReload (setup and query)
try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
			 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
	for (int i = 0; i < 40; i++) {
		store.put("rec-" + i, makeRecord("rec-" + i, "content-" + i, i),
				gaussianVector(16, random));
	}
	store.put("plain", makeRecord("plain", "no-vector", -1));

	before = store.search(query, 5);
}
```

A record with no vector (`"plain"` above) is a normal record. It can be read by key and is
counted by `size()`, but it never appears in search results. After this block, the store
directory holds `index.bin`, `batch_0000.bin` and `hnsw.bin`. The test asserts all three.

---

## 3. The index as a computation graph

`HnswIndex` follows THE FUNDAMENTAL RULE: Java orchestrates, and native code executes. It is an
example of the rule outside training. No similarity is computed in a Java loop. Instead,
`SimilarityMetric` *describes* similarity as producers. For cosine (`SimilarityMetric.COSINE`):

```java
// SimilarityMetric.COSINE (fragment)
public CollectionProducer similarities(Producer<PackedCollection> candidates,
									   Producer<PackedCollection> query) {
	return matmul(candidates, query);
}

public CollectionProducer normalize(Producer<PackedCollection> vector) {
	return divide(vector, max(sqrt(sum(sq(vector))), c(MIN_NORM)));
}
```

Vectors are L2-normalized once, when they are inserted, so cosine similarity becomes a dot
product. Scoring a query against a whole batch of candidates is then one `matmul`. The
`MIN_NORM` floor keeps a zero vector at zero instead of dividing by zero. The batch form is a
single producer, evaluated once at the boundary (the example below asserts the cosine
similarities it returns):

```java
// VectorIndexExamplesTest.wholeBatchScoringMatchesCosineSimilarities
SimilarityMetric metric = SimilarityMetric.COSINE;

PackedCollection candidates = new PackedCollection(3, 2);
candidates.setFrom(0, metric.normalize(vec(3.0, 4.0)), 0, 2);
candidates.setFrom(2, metric.normalize(vec(0.0, 2.0)), 0, 2);
candidates.setFrom(4, metric.normalize(vec(-1.0, 0.0)), 0, 2);
PackedCollection query = metric.normalize(vec(1.0, 0.0));

PackedCollection scores = metric.similarities(cp(candidates), cp(query)).evaluate();
// scores = [0.6, 0.0, -1.0]
```

`HnswIndex` compiles these descriptions **once** and reuses them. All vectors are kept in one
contiguous `[capacity, dimension]` `PackedCollection`. The index holds two evaluables:

- `normalizeVector`, compiled once per index. An insert evaluates it *into the vector's own row*
  of the store, so normalization and storage take one dispatch.
- `scoreAll`, compiled once per capacity. It scores one vector against every row of the store
  in one dispatch and writes into a fixed `[capacity]` score buffer.

So inserting a new vector into a non-empty index, and searching a non-empty index, each make a
fixed number of dispatches — two, however many comparisons they imply: one normalizes the
incoming vector, one scores it against the whole store. The edge cases do less and never more:
the first vector in an empty index and the re-normalization of an id that is still live stop
after the normalize dispatch (there is nothing yet to score against, or the row is simply
rewritten in place), and a search of an empty index returns before dispatching at all. The host
reads the score array once, and every decision is made on the host from that array: the top-K
for a search, and the neighbor lists of each layer for an insert. Each node keeps only its row
index. There is no `PackedCollection` per node, so the number of native
allocations does not grow with the number of nodes.

When the store fills up, `grow()` doubles the capacity, copies the existing rows across, and
recompiles `scoreAll` for the new shape. The index has two kinds of removal:

- `remove(id)` is a **soft delete**. The node stays in the graph, so edges that point to it
  stay valid, but it is excluded from search. `size()` counts live nodes, and `totalSize()`
  also counts deleted ones.
- Re-inserting a soft-deleted id does a **hard removal** first. The old node leaves the graph,
  and its row goes on a free list to be reused before the store is extended.

The planted-neighbor example inserts more vectors than `HnswIndex.INITIAL_CAPACITY` (256), so
the store grows while it is filled:

```java
// VectorIndexExamplesTest.plantedNeighborIsRecalledAtTopOne
Random random = new Random(7);
HnswIndex index = new HnswIndex(DIMENSION);   // DIMENSION = 64

for (int i = 0; i < DISTRACTORS; i++) {       // DISTRACTORS = 600
	index.insert("distractor-" + i, gaussianVector(DIMENSION, random));
}

double[] query = gaussianValues(DIMENSION, random);
double[] planted = new double[DIMENSION];
for (int i = 0; i < DIMENSION; i++) {
	planted[i] = query[i] + 0.05 * random.nextGaussian();
}
index.insert("planted", vec(planted));

List<HnswIndex.IdScore> results = index.search(vec(query), 5);
// results.get(0).id is "planted", with a score above 0.95;
// the best distractor scores below 0.7
```

(The Java loops here only build test data on the host. They do no math on collections.)

**The current design, stated plainly.** At the sizes this index serves today, one whole-store
dispatch is cheap. Both search and construction therefore use *exact* scores, and do not walk
the graph. The layered HNSW graph is still built, from those exact neighbors (so it is at least
as good as a graph built by walking), and it is persisted. Because of this, a batched graph
traversal can become the search strategy later, when store sizes make brute force too slow,
without a change to the file format. `efSearch` (see `setEfSearch`) is kept for that future
path. The exact search does not use it.

---

## 4. Save and load

`HnswIndex.save(path)` writes one `HnswIndexData` message (see `diskstore.proto`). The message
holds:

- the index parameters: `dimension`, `m`, `ef_construction`, `max_level` and
  `entry_point_id`;
- one `HnswNodeData` per node, including soft-deleted nodes: its `id`, its normalized `vector`
  (encoded with `CollectionEncoder` at FP32), its `level`, its `deleted` flag, and one
  `HnswLayerNeighbors` per layer;
- in each `HnswLayerNeighbors`, the `neighbor_ids` and a parallel list of `neighbor_scores`.

Edge scores are saved so that pruning after a reload needs no rescoring. When an insert pushes
a node over its connection limit (`2 * m` at layer 0, `m` above it), `connect` drops the
lowest-scored edge, and the saved score is that edge's score.

**Backward compatibility.** `neighbor_scores` was added after the format first shipped. A file
written before that has ids and no scores. `HnswIndex.load` gives such edges the score
`Float.NaN`, which means "unknown", and an unknown edge is never pruned ahead of a known one.
These NaN scores are written back unchanged when the index is saved again, so an old file
never gains made-up scores. `HnswSearchTest` covers this.

`HnswIndex.load` returns `null` if the file does not exist. When the file is unreadable, it
logs a warning and also returns `null`, and the store starts with a fresh index.
`ProtobufDiskStore` loads `hnsw.bin` and `hnsw.model` in its constructor. A standalone round
trip keeps the live and deleted state and the search results:

```java
// VectorIndexExamplesTest.standaloneIndexRoundTrips
HnswIndex index = new HnswIndex(32);
for (int i = 0; i < 50; i++) {
	index.insert("v-" + i, gaussianVector(32, random));
}
index.remove("v-7");
index.save(file);

HnswIndex loaded = HnswIndex.load(file, SimilarityMetric.COSINE);
// loaded.size() == 49, loaded.totalSize() == 50, !loaded.contains("v-7"),
// and loaded.search(query, 10) returns the same ids, in the same order,
// with the same scores, as index.search(query, 10)
```

The store-level example in section 2 asserts the same identity across `close()` and reopening.
The reopened store returns the same records in the same order with the same similarities.

---

## 5. Model-keyed rebuild

An index holds vectors from **one** embedding model. Vectors from two models cannot share an
index. Their widths usually differ, and even when the widths match, a similarity between them
means nothing. Three separate mechanisms enforce this. They live at two layers.

**(a) The model marker: `ProtobufDiskStore.setIndexModel(model)`.** This declares which model's
vectors the index holds. The declared model is written to `hnsw.model` **immediately**.
`setIndexModel` returns `true` when the index must be rebuilt for that model:

- When the declared model differs from the recorded one, or no model is recorded, the index is
  discarded, in memory and on disk (`hnsw.bin` is deleted), and the call returns `true`.
- When the declared model is the recorded one and its index is loaded, the call is a no-op and
  returns `false`.
- When the declared model is the recorded one but **no index is loaded**, the call returns
  `true`. This can happen because the marker is written at once, while `hnsw.bin` is written
  only on `flush`/`close`. A run interrupted between the two reopens with a marker and no index.
  Treating that case as "current" would leave the index empty for good.

**(b) The index is created for a vector width, not for a model.** After a discard, the next
`put(id, record, vector)` or `insertEmbedding` creates a new `HnswIndex` sized to *that vector's
width*. The disk store knows the model only through the marker. The index structure records
only a dimension.

**(c) Re-embedding: `ProtobufWaveDetailsStore.setEmbeddingModel(model)`.** A store of opaque
records cannot recompute embeddings. So when `setIndexModel` reports a rebuild, the layer above
does the work. `ProtobufWaveDetailsStore.rebuildIndex` visits every stored record. Each record
that already has features for the declared model is re-embedded from those features (mean
pooled) and re-inserted with `insertEmbedding`. A record **without** the declared model's
features is left out. It enters the index when its embedding for that model is computed and
stored. `ProtobufWaveDetailsStore.put(id, details, vector)` declares the model of the record's
active features — rebuilding the index if that model differs from the one recorded — before it
inserts, so a `put` cannot silently add a vector to an index built for another model. This is a
caller-side contract rather than a validated guarantee: the store does not check that `vector`
was actually produced by that model, and when the record's active model identifier is `null`
the index model is left unchanged (`setIndexModel(null)` is a no-op), so a vector supplied with a
null active model is inserted into whatever index is current.

The examples test checks the disk-store half of this contract directly, with `insertEmbedding`
standing in for the re-embedding step:

```java
// VectorIndexExamplesTest.modelChangeExcludesUntilReembedded
try (ProtobufDiskStore<TestRecordProto.TestRecord> store =
			 new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser())) {
	store.setIndexModel("model-a");                        // true: nothing recorded yet
	store.put("x", makeRecord("x", "first", 1), vec(1.0, 0.0, 0.0));
	store.put("y", makeRecord("y", "second", 2), vec(0.0, 1.0, 0.0));

	store.setIndexModel("model-a");                        // false: no-op
	store.setIndexModel("model-b");                        // true: index discarded
	// search(...) is now empty, but store.get("x") still returns the record

	store.insertEmbedding("x", vec(0.0, 0.0, 0.0, 1.0));   // re-embed x for model-b
	// search(vec(0, 0, 0, 1), 10) returns only "x"; "y" stays excluded
}
// hnsw.model now contains "model-b"; after reopening, setIndexModel("model-b")
// returns false and the index still holds "x" but not "y"
```

Note that the new model's vectors have a different width (4, not 3). The rebuilt index takes the
width of the first vector inserted after the discard. `markerWithoutIndexReportsRebuild` covers
the case of a marker with no index.

**Where each half is tested.** The re-embedding step needs records that carry features for
several models. That is the audio feature model in `studio/compose`, which `engine/ml` cannot
depend on. So `VectorIndexExamplesTest` asserts the marker and the discard/re-insert primitives
that the contract rests on. The full re-embedding behavior is guarded in `studio/compose` by
`ProtobufWaveDetailsStoreTest`: the rebuild on a model change, records without the model's
features staying out of the index, and the rebuild after a marker outlives its index. The
end-to-end library tests `DiskStoreAudioLibraryTest` and `AudioLibraryMigrationTest` cover the
same ground.

---

## 6. Known limits and future direction

- **Exact scoring.** Search cost grows linearly with the size of the store: it is one dispatch,
  but over every row. This is the right choice at current sizes. The persisted layered graph
  and `efSearch` exist so that a batched graph walk can replace it without a format change.
- **Model identity is tracked by the marker, not by the index.** The disk store records the
  owning model only in `hnsw.model`. The index structure is created for the width of the first
  vector inserted after a discard. A caller that inserts vectors without declaring a model, or
  that inserts vectors of one width from two models, is not detected at the disk-store layer.
  `ProtobufWaveDetailsStore` avoids this by declaring the model on every vector `put`.
- **Width changes without a model change.** An index is only discarded by a model change.
  Inserting a vector of another width into an existing index fails (`HnswIndex.insert` rejects
  it), and a query of another width finds nothing. A backfill that decides whether to run from
  `indexedEmbeddingCount()` or `hasEmbedding(id)` alone will see a reopened index full of
  old-width vectors as complete, and will not rebuild it. The indexed width is not yet exposed
  above the disk store.
- **Use after close.** `close()` releases the index. `put` rejects a closed store, but
  `insertEmbedding` does not check whether the store is closed. A call after `close()` creates a
  new, empty index instead of failing. Do not use a store after closing it.
- **Concurrency.** Every access to the index (insert, remove, search, model change, save and
  destroy) is serialized on one lock inside `ProtobufDiskStore`. A model change on one thread
  therefore cannot destroy an index that another thread is still using. The record side of the
  store (the pending batch and the id index) is not synchronized in the same way.

---

## Related Files

| File | Role |
|---|---|
| `engine/ml/src/main/java/org/almostrealism/persist/index/HnswIndex.java` | The index: contiguous store, compiled normalize/score evaluables, layered graph, save/load |
| `engine/ml/src/main/java/org/almostrealism/persist/index/SimilarityMetric.java` | Similarity as producers; `COSINE` |
| `engine/ml/src/main/java/org/almostrealism/persist/index/ProtobufDiskStore.java` | Record storage, index integration, `hnsw.model` marker |
| `engine/ml/src/main/java/org/almostrealism/persist/index/DiskStore.java` | The store interface, including the `search` contract |
| `engine/ml/src/main/java/org/almostrealism/persist/index/SearchResult.java` | One search hit: id, record, similarity |
| `engine/ml/src/main/proto/diskstore.proto` | `DiskStoreIndex`, `HnswIndexData`, `HnswNodeData`, `HnswLayerNeighbors` |
| `studio/compose/src/main/java/org/almostrealism/studio/persistence/ProtobufWaveDetailsStore.java` | Re-embedding on a model change (`setEmbeddingModel`, `rebuildIndex`) |
| `engine/ml/src/test/java/org/almostrealism/persist/index/test/VectorIndexExamplesTest.java` | The worked examples on this page |
| `engine/ml/src/test/java/org/almostrealism/persist/index/test/HnswSearchTest.java` | Detailed index and store behavior, including old-format loading and edge pruning |
| `studio/compose/src/test/java/org/almostrealism/studio/persistence/test/ProtobufWaveDetailsStoreTest.java` | The rebuild contract at the re-embedding layer |
