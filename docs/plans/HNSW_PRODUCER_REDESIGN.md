# HNSW on the device — why the producer conversion does not work yet

**Branch:** `feature/hnsw-producer-redesign`
**Status:** implemented. Whole-store scoring via precompiled evaluables; all 28
tests pass with the class completing in ~12.5 s (the 10k-insert performance test
included), against the previous ≥28.5 min extrapolation. See *Implemented
design* below.

The conversion of `SimilarityMetric` and `HnswIndex` to the Producer pattern is
present on this branch exactly as it was written, together with the two things it
needs in order to build. It does not work: the index becomes roughly four thousand
times slower per comparison and the performance test times out. This document
records what is known so the next session does not have to rediscover it.

## The starting point

Three files carry the conversion, byte for byte as originally committed
(`23bab3ca4`):

- `engine/ml/src/main/java/org/almostrealism/persist/index/HnswIndex.java`
- `engine/ml/src/main/java/org/almostrealism/persist/index/SimilarityMetric.java`
- `engine/ml/src/test/java/org/almostrealism/persist/index/test/HnswSearchTest.java`

Two supporting changes travel with them. **Both are the subject of this work, not
settled parts of it** — a design that earns its place should give them back:

- `ProducerPatternDetector` gained `HnswIndex.java` entries: `insert`, `search`
  and `score` in the `evaluate` exemptions, `score` in the `toDouble` exemptions.
- `setmem-violation-baseline.tsv` lost its `SimilarityMetric.java` row, which the
  converted file no longer triggers.

`SimilarityMetric` now describes rather than computes: `similarity` and `normalize`
return `CollectionProducer`. `HnswIndex.Node.cachedData` became a
`PackedCollection`, and every comparison in the graph walk goes through
`HnswIndex.score` (line 557):

```java
private float score(PackedCollection a, PackedCollection b) {
    return (float) metric.similarity(cp(a), cp(b)).evaluate().toDouble(0);
}
```

Four call sites reach it — `greedyClosest` (450), `searchLayer` (498),
`selectNeighbors` (531) and `similarityTo` (572) — which is every comparison the
algorithm makes.

## What actually fails

`HnswSearchTest.performanceSearch10kRecords128dim` times out at 60 s. 27 of the
class's 28 tests pass, so this is not a correctness break.

The important detail: **it times out inserting, not searching.** It never reaches
the search the test exists to measure.

```
HnswIndex.score(HnswIndex.java:558)
HnswIndex.selectNeighbors(HnswIndex.java:531)
HnswIndex.insert(HnswIndex.java:228)
ProtobufDiskStore.put(ProtobufDiskStore.java:213)
HnswSearchTest.performanceSearch10kRecords128dim(HnswSearchTest.java:203)
```

## Measurements

Taken on this hardware with a disposable harness in `engine/ml` (not retained —
see *Open questions* below):

| Quantity | Value |
|---|---|
| One 128-element similarity, through the device | **430 µs** |
| The same arithmetic on the host | ~100 ns |
| `insert` at n = 200 (`efConstruction` = 200) | **171 ms** |
| Implied `score` calls per insert | ~400 |
| 10,000 inserts, extrapolated | **≥ 1714 s (28.5 min)** |
| Budget | 60 s |

The extrapolation is a **lower bound**: it holds per-insert cost flat at its n=200
value, and the candidate lists only get fuller as the graph grows.

For scale: the host form of the same index does this work in seconds. The overhead
is not in the arithmetic — 128 multiply-adds is nothing — it is entirely in
crossing to the device and back, ~4000× the work being done.

## Two costs, not one

The dispatch is the obvious cost. There is a second one, visible in the timeout
stack from the run on this branch:

```
java.lang.Thread.start0
java.lang.Thread.start
io.almostrealism.relation.Evaluable.lambda$async$0(Evaluable.java:144)
io.almostrealism.streams.EvaluableStreamingAdapter.request(EvaluableStreamingAdapter.java:111)
org.almostrealism.hardware.ProcessDetailsFactory.construct(ProcessDetailsFactory.java:701)
```

An earlier run of the same failure parked in `LatchSemaphore.waitFor` /
`AcceleratedProcessDetails.awaitReady` instead. Between them these say that a
per-pair `evaluate()` **spawns a thread per dispatch**. At ~400 comparisons per
insert that is ~400 thread creations per inserted record.

This matters for choosing a direction: a fix that keeps per-pair evaluation and
only makes the kernel cheaper will still pay this. Whether the thread is avoidable
for a small synchronous evaluation is itself worth establishing, and would be
useful well beyond HNSW.

## Why the obvious fix is not enough

The natural move is to stop scoring one pair at a time and score a whole candidate
frontier in one computation. The original commit message anticipated exactly this.
It does not close the gap on its own:

- The walk is **data-dependent**. Which node is visited next is chosen by the
  comparison just made, so the algorithm cannot be expressed as one computation.
- The only batch genuinely available is **one node's neighbour list**, bounded by
  `maxM0 = 2 * m` = **32**.
- 32× against a shortfall of several hundred does not close it. Per-insert cost
  would have to fall by roughly 170× to fit 10k inserts in the budget with room
  for the search itself.
- Collecting that batch at all needs the node vectors **contiguous** — a
  `[capacity, dim]` store with nodes holding row indices, and a gather by index
  list. Today each node owns a separate allocation, so gathering would cost more
  than the batching saves.

So neighbour-list batching is necessary but not sufficient. Something else has to
give: fewer dispatches per insert, a much cheaper dispatch, or a different
construction strategy (for instance, building the graph from a small number of
large brute-force passes rather than 10k incremental insertions).

## Two things that were true before any measurement

Both were visible in the diff and both should be trusted earlier next time.

**The retained allocation.** `Node.cachedData` is a `PackedCollection`, so the
index holds one native allocation per node — 10,000 of them in this test. An
earlier session had already moved this to `double[]` specifically to fix native
memory exhaustion that cascaded into out-of-memory failures in later test classes.
The javadoc on `Node` (lines 610–615) still promises the opposite of what the code
now does:

> Stores the vector so scoring reads it without a further transfer. **No
> `PackedCollection` is retained**, avoiding native memory leaks when the finalizer
> is disabled.

That contradiction sits directly above `PackedCollection cachedData`. Whatever
design lands, this javadoc must end up true rather than aspirational.

**The exemptions.** The conversion only compiles because `insert`, `search` and
`score` were added to the detector's `evaluate`/`toDouble` allowances. Needing a
fresh carve-out from the rule the migration exists to enforce is evidence against
the change, not paperwork for it. Treat the exemption list as the scoreboard: if
the design still needs those three entries, it has not solved the problem.

## Reproducing

The `~/.m2` staleness hook matters here. This branch's artifacts were built from a
different branch, and there is a documented incident in this repository where a
failure falsely did not reproduce for exactly that reason. Do the full build:

```
mvn clean install -DskipTests
```

Then:

```
mcp__ar-test-runner__start_test_run module:"engine/ml" test_classes:["HnswSearchTest"]
```

Expect 27 passes and `performanceSearch10kRecords128dim` timing out at 60 s in
`selectNeighbors`. The validator (all five checks) passes on this branch as it
stands, so any new violation is the new work's.

Note that `code_policy` reads `setmem-violation-baseline.tsv` from the **installed**
`ar-utils` jar. Editing the baseline without `mvn install -pl engine/utils` first
produces a phantom violation that no source change explains.

## Probe results (2026-08-25)

`SimilarityDispatchProbeTest` (engine/ml, retained on this branch) isolated the
candidate explanations. Runs `9eb193e8`, `cf0aa3ec`, `fe6bc2d4` on this hardware,
auto-selected backend, 128 dimensions:

**Kernel reuse is working.** Similarity graphs over distinct `cp(a)`/`cp(b)`
pairs produce identical signatures, and steady-state compile counts
(`HardwareOperator.cpuCompileCount`/`gpuCompileCount`,
`NativeCompiler.getTotalInstructionSets`) are zero on every measured path. The
per-comparison cost is not compilation; it is per-call orchestration — graph
construction, recursive signature hashing, fresh evaluable creation with
argument substitution, a `StreamingEvaluable` thread per provider argument
(`Evaluable.async` default), and the completion wait.

| Path | Per dispatch | Per score |
|---|---|---|
| Current: fresh graph + `evaluate()` per pair | 657–902 µs | 657–902 µs |
| Precompiled over `Input.value(dim, 0/1)` | 79–143 µs | same |
| Precompiled + `into()` fixed destination | 46 µs | 46 µs |
| Batched `matmul([32,128],[128])` | 108–280 µs | 3.4–8.8 µs |
| Batched, K=256, fixed staging + output | 402 µs | 1.57 µs |
| Batched, K=2048, fixed staging + output | 446 µs | 0.22 µs |

Pass-through arguments (`Input.value` → `PassThroughProducer`, which implements
`ProducerArgumentReference`) bind as direct pointers in
`ProcessDetailsFactory.prepare` — no per-argument thread, answering open
question 1 for this path.

**Dispatch cost is nearly flat in batch width.** 32→2048 candidates costs
280→446 µs per dispatch. This confirms the doc's prediction that neighbour-list
batching (K ≤ 32) alone cannot close the gap — realistic partial-width lists
give ~12–24 ms per insert, still minutes for 10k — and it answers open
question 5: at this scale, construction should score each new vector against
**all** existing rows in one wide dispatch (~0.5–1 ms at n = 10k), selecting
top-`efConstruction` on the host from a bulk readback. That is ~10–20 s for
10k inserts, inside the budget, with exact-kNN neighbour quality as a side
effect. The same single wide dispatch also serves search at this scale. The
prerequisite for either is the contiguous `[capacity, dim]` vector store, which
also retires the per-node allocation problem.

## Implemented design (2026-08-25)

The owner's constraint: assume fewer than ~100k samples for now; the brute-force
crossover is an accepted consequence until a future version revisits it.

- **Contiguous store.** All vectors live in one `[capacity, dimension]`
  `PackedCollection`; `Node` holds a row index and retains no allocation. The
  store doubles on demand (`setFrom` bulk copy); the per-node javadoc promise is
  now true.
- **Two precompiled evaluables per index**, built over `Input.value`
  pass-through arguments (pointer-bound, no per-argument threads): one
  normalizes an incoming vector directly into its store row / the query staging
  buffer, and one scores a vector against every row in a single dispatch
  (`SimilarityMetric.similarities`, `matmul` for cosine) into a fixed
  destination. The score evaluable is rebuilt only when capacity doubles.
- **Insert** = one normalize dispatch + one whole-store score dispatch + host
  selection: per layer, the top-`maxConnections` active nodes present at that
  layer become the new node's neighbors (exact kNN, at least the quality the
  walked construction approximated). Backlink overflow prunes by the stored
  edge score — adjacency lists hold `IdScore` edges, so pruning never rescores.
- **Search** = the same two dispatches + host top-K over active rows; exact at
  this scale. The layered graph is still built and persisted so a batched
  traversal can become the scoring strategy past the brute-force crossover.
- **Persistence.** `HnswLayerNeighbors` gained `repeated float neighbor_scores`;
  legacy files load with unknown (NaN) edge scores that are never pruned ahead
  of known ones.
- **Scoreboard settled.** The `score`/`toDouble` detector exemptions are gone;
  `insert` and `search` remain as genuine step boundaries (two dispatches each).
  The `SimilarityMetric` baseline row stays retired.

`SimilarityDispatchProbeTest` is retained as the regression record of the
measurements above.

## Open questions for the next session

1. Can a small synchronous `evaluate()` avoid spawning a thread? This is the
   cheapest possible win and is not HNSW-specific.
2. What does a dispatch actually cost once the thread is out of the picture — is
   the remaining 430 µs mostly graph construction, compilation, or transfer? The
   generated source and argument bindings are readable via `ar-profile-analyzer`,
   but **no profile is currently wired for this test**; that is step one for any
   answer here.
3. Is per-pair compilation being cached at all? `cp(a)` over a fresh
   `PackedCollection` each call may be producing a distinct graph every time,
   in which case a fixed pair of staging buffers plus one compiled evaluable is a
   separate and much larger win than batching.
4. Does contiguous vector storage pay for itself, independent of batching? It also
   fixes the per-node allocation problem.
5. Is incremental insertion the right construction path at all on this hardware?

## What is *not* in question

The host `double[]` form is not a failure of the migration to be corrected later.
For this algorithm it is currently the only workable form, and the reasoning is
about dispatch cost against arithmetic size and the absence of a batch — **not**
about any prohibition on calling `evaluate()`. If a design here succeeds, it
succeeds by removing the dispatches, not by arguing the rule differently.
