# Host-array and loop audit

Every `double[]`/`float[]` occurrence in every file changed on this branch, with the
reason it is not expressed with `PackedCollection` / `CollectionProducer`. Scoped to the
branch diff against `origin/master`; comments and javadoc are excluded.

**This is not a whole-repo audit.** A repo-wide scan turns up several thousand
occurrences in pre-existing geometry, physics and ray-tracing value types — `Vector`,
`TransformMatrix`, `Ray`, `KdTree`, `CSG`, `MercuryXenonLamp`, `DefaultPhotonField` and
their neighbours. Those predate the framework this audit is about and are not findings
here; moving them would be an architectural change of a different kind and scale.

No entry below appeals to the prohibition on calling `evaluate()`. That prohibition exists
to move work onto the device; using it to justify leaving work on the host inverts it.

## Position

**27 occurrences across 8 files, all of them defended. Nothing in scope is outstanding.**

The audit opened at 971 items across 74 files, 640 of them without a defense. Each was
either migrated or given a defense, and each defense is recorded below against the
occurrence it covers.

To recompute this position after further work on the branch:

```
git diff --name-only origin/master...HEAD    # filter to *.java
# strip /* */ blocks and //-leading lines, then count /(?:double|float)\[\]/
```

There is no generator for this document; it is maintained by hand.

## What remains, and why

### `engine/utils/.../FirFilterTestFeatures.java` — 7

The host FIR reference implementation (`in`, `taps`, `output`) and the coefficient
builder beside it. This is the oracle the device filter is checked against; expressing it
with the producers it exists to test would let a fault agree with itself. `floatToDouble`
converts at a caller's boundary.

### `studio/music/.../BatchedPatternLayerRenderer.java` — 5
### `studio/music/.../BatchedNoteInputs.java` — 5
### `engine/audio/.../PatternRenderingFloorBenchmarkAdditional.java` — 4

The per-tick scalar staging for the batched pattern dispatch, and the benchmark that
measures its floor. The renderer's bound store is column-major while a note's scalars are
contiguous, so moving these to the device costs several hundred small per-note copies and
a transpose against one contiguous host write. It becomes worth doing only alongside a
gather that gives every note somewhere shared to write. The benchmark's upload stays
inside its timed region deliberately: production cannot pre-stage a scalar set that
changes with the notes overlapping each window, and hoisting it would report a floor
production cannot reach.

### `engine/utils/.../TestFeatures.java` — 2

`compare` reads both sides to the host to average them. Its strict counterpart,
`largestDeviation`, reduces on the device and is what a test asserting that nothing
anywhere deviates should reach for.

### `engine/ml/.../HnswSearchTest.java` — 2

`HnswIndex.insert` and `search` take `double[]` by design. The signature was a deliberate
fix for native-memory exhaustion — the `PackedCollection` form leaked an allocation per
insert and cascaded into out-of-memory failures across later test classes.

### `studio/compose/.../MixdownLayerPerformanceTest.java` — 1

Forward-pass durations. These are wall-clock measurements *of* the computation, not data
*in* it; no device produces them.

### `compute/algebra/.../VectorFeatures.java` — 1

`vector(double[])`, a convenience overload for a caller-supplied array. This is the
surface where a value from outside the framework enters it.

## What the migration produced

Work that came out of the individual files and is now available to every consumer, rather
than being repeated per file:

- `TestFeatures` gained `largestDeviation` in three forms — against a single value every
  element must hold, against another collection, and against a formula evaluated position
  by position — plus `assertAllFinite` and `assertSymmetric`.
- `FirFilterTestFeatures` gained `differenceEnergy`, `sumChannels`, `channelEnergy` and
  `render` alongside the existing `energy` and `peakOf`.
- `VectorFeatures` gained `oneHot`; `LayerFeatures` gained `gruGate` and `gruStep`.
- `BatchedPatternRenderer` names its own scalar column layout, and
  `getSssNoteScalarColumns` names the part of it a note supplies.
- `PackedCollection.clone()` already covered snapshotting a model output, and
  `MatrixFeatures.identity` already covered matrix construction two tests had copied.

## Lessons, each learned by measurement

- Loop-carried collections must be allocated once and written through with `setFrom`. An
  operand's offset is part of its signature, so a fresh allocation per iteration makes
  every iteration a distinct graph and recompiles the pipeline once per iteration.
- Aggregates reported together should be concatenated into one computation. Six separate
  evaluations compile six kernels per shape.
- A comparison mask carries a per-element traversal policy, so `sum` will not reduce one
  without an intervening `reshape`.
- Two renders from one compiled model share its output buffer. The earlier must be cloned
  before the later runs, or a comparison between them collapses to zero and passes
  vacuously.
- A reference kept as a formula stays a formula. Materialising it as an array to compare
  element by element puts the expectation back on the host for no reason — and a host
  formula never justified a host array, which is a different claim.
- Removing a host array is not automatically an improvement. Turning a host scan into a
  device reduction inside production code can introduce an `evaluate()`/`toDouble()` pair
  that the policy detector rejects, and the answer still has to reach the host when it
  sizes a buffer.
