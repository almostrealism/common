# AudioSceneOptimizer Performance Investigation

## Status

Performance regression diagnosed and the user-visible slowdown fixed. The
underlying heap-retention leak is documented but **not yet fixed**.

- **Phenomenon Y — per-tick render slowdown** during back-to-back genome
  evaluations in a single JVM. Triggered by a major GC firing *during* a
  render (5×–10× per-tick inflation). **Fixed** by inserting `System.gc()`
  at the per-genome boundary in
  `AudioScenePopulation.enableGenome(int)`, gated by the static
  `gcBeforeGenome` toggle (default `true`). Verified at +2.4× average
  render time across post-warmup repeats in `AR_BENCHMARK_REUSE_SCENE` mode.
- **Phenomenon X — inter-section heap growth** is real and remains. Each
  scene render retains roughly +3 300 `OperationMetadata`,
  +1 060 `TraversalPolicy`, +148 `NoteAudioKey`-cluster entries,
  +100 `NoteAudioProvider`/`FileWaveDataProvider` instances, and
  +17 Metal kernel artefacts. `System.gc()` only relieves the *symptom*
  (Y); the strong-reachable retained graph metadata is not collected.
  Over a long optimizer run this still exhausts available heap.

The outstanding work below targets X. Once X is addressed the
`gcBeforeGenome` workaround can be removed.

## Outstanding Work — fix Phenomenon X

### 1. Drain `NoteAudioProvider.audioCache` between scenes

`NoteAudioProvider.java` declares a static
`CacheManager<PackedCollection> audioCache`. Eviction via
`maxCachedEntries` calls `CachedValue.clear()` on the oldest entries but
**does not remove them from the internal HashMap** — the map keys are the
`CachedValue`s themselves, so the captured source `Evaluable` (and
through it the owning `NoteAudioProvider`) remains strongly reachable.
The 200-entry "bound" applies only to *available* entries, not HashMap
entries.

What needs to happen:

- `CacheManager` already has a `dropAll()` that drops strong references
  without invoking the destroy consumer (added during Run 5E-1; the
  per-entry `clear()` variant cascaded into still-live delegate-shared
  `PackedCollection`s and broke the next render). Use that, not
  per-entry `clear()`.
- Wire a public `clearCache()` on `NoteAudioProvider` and call it from
  the scene-destruction path so the static cache does not outlive scene
  evaluations.
- Validate via the heap-diff procedure documented under "Validation"
  below: the `NoteAudioKey` / `NoteAudioProvider` /
  `FileWaveDataProvider` clusters must drop to ≤5 instances/rep delta.

### 2. Investigate `OperationMetadata` / `TraversalPolicy` retention

These are the largest growth sources by instance count
(+3 300 / +1 060 per repeat). They belong to `Computation` graph nodes
and should die with the scene. Profile capture was *disabled* during
Run 5C and the growth still occurred, so the profile tree is not the
retention root.

Likely transitive root: the static `audioCache` above — each cached
`Evaluable` holds the operation graph that produced its source audio,
which transitively holds metadata for every `Computation` in that
graph. Fixing item 1 may collapse this cluster automatically; if not,
walk the dominator path from a representative retained
`OperationMetadata` to a GC root.

### 3. Investigate Metal kernel cluster retention

`MetalProgram` / `MetalOperatorMap` / `MTLFunction` /
`MTLComputePipelineState` grow ~17 / rep, created via
`MetalComputeContext.java:185` (`new MetalOperatorMap(...)`). Smaller
contributor than items 1–2 but still unbounded. Likely a hardware
kernel cache holding compiled artefacts across `pop.destroy()`.

### 4. Remove the `gcBeforeGenome` workaround

Once items 1–3 collapse the per-rep deltas, run the controlled pair in
`AR_BENCHMARK_REUSE_SCENE` mode with `gcBeforeGenome=false` and
verify performance matches the `gcBeforeGenome=true` baseline. Then
remove the static toggle and the `System.gc()` call from
`AudioScenePopulation.enableGenome(int)`.

## Validation Procedure

The benchmark harness `studio/compose/scripts/run-optimizer-benchmark.sh`
plus `AudioSceneBenchmark` already supports everything required:

- `AR_BENCHMARK_REUSE_SCENE=true` mirrors the production
  one-scene-many-genomes lifecycle (do not validate against the default
  recreate-per-rep mode — the fix is targeted at the reuse pattern).
- `AR_BENCHMARK_INTER_REPEAT_PAUSE_MS` plus the sentinel marker file
  lets external JMX tooling capture a class histogram during a stable
  idle window. Use `mcp__ar-jmx__get_class_histogram` between repeats.
- `AR_BENCHMARK_DISABLE_PROFILE=true` is required for clean heap-diff
  measurements (the profile tree itself adds millions of metadata
  references).
- Per-tick timeline CSVs under `results/timelines/` show the
  within-rep ramp shape of phenomenon Y.

For each candidate fix:

1. Capture a baseline class histogram in fresh-JVM end-of-rep-1 and
   end-of-rep-2 conditions with the fix disabled.
2. Apply the fix and re-capture. The targeted cluster's per-rep delta
   should drop to ≤5 instances. Other clusters should not regress.
3. Re-run multi 32m × 3 with `gcBeforeGenome=false` and confirm
   per-rep ratio stays in the 1.7–1.9 band (not 5×–10×).

## Reference: Production Fix (already landed)

`AudioScenePopulation.enableGenome(int)` calls `System.gc()` at the
start, gated by `public static boolean gcBeforeGenome = true`. Both
production paths converge on this method:

- `AudioSceneOptimizer` via `HealthCallable.enableGenome` in
  `PopulationOptimizer.orderByHealth()`.
- `AudioScenePopulation.generate(channel, frames, ...)` (used by
  ringsdesktop preview).

`disableGenome()` is **not** the right hook because
`init(Genome, MultiChannelAudioOutput, List<Integer>, int)` and
`validateGenome` also call it during setup, where firing GC interrupts
JIT compilation and prematurely promotes setup state to old gen.

## Reference: Empirical Baseline (for regression detection)

Original sweep showed multi-channel render time scaling super-linearly
with audio duration; single-channel scaling near-linearly. The
super-linear shape was traced to the Y-via-X mechanism above, *not* to
the compiled per-frame loop kernel (`f_loop_X` per-call cost is flat
across reps) or to per-channel quadratic cost (channel-count sweep
showed costs roughly linear in channel count, ruling out the original
H3). For comparison runs, baseline ratios with the fix at multi 32m in
`AR_BENCHMARK_REUSE_SCENE` mode are 1.7–1.9 per repeat; without the
fix, individual repeats incur 5×–10× per-tick inflation.

Heap context: every benchmark run uses `-Xmx8g` (the
`AR_BENCHMARK_HEAP` default). The `rings-appbundle-arm` bundle ships
with `-Xmx2g`, where the major-GC threshold is crossed sooner —
phenomenon Y manifests more frequently per genome on arm than the
benchmark captures. Once item 1 lands, also re-run with
`AR_BENCHMARK_HEAP=2g` to confirm the production fix is sufficient on
the tighter target.
