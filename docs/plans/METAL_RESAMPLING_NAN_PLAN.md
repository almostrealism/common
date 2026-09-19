# Metal NaN in the learned-resampling block

`TransformerResamplingFeatures.transformerResamplingBlock` (`engine/ml`) produces `NaN` on
the Metal backend and correct, finite values on CPU/Native and on OpenCL. It blocks Stable
Audio 3 generation on Apple hardware. This document is a study-and-plan only — the study
was done off a Linux/CI-style sandbox with no Metal device, so nothing here was run; every
claim under "Verified from current source" was confirmed by reading the file at the cited
line, and everything under "To be confirmed on the Mac" is exactly that. Implementation is a
separate job on a macOS runner with a `MetalDataContext`.

## Symptom and blast radius

- `TransformerResamplingShapeTest.encoderBlockDownsamplesShape` and
  `.decoderBlockUpsamplesShape` (`engine/ml/src/test/java/org/almostrealism/ml/TransformerResamplingShapeTest.java:112-147`)
  call `skipWhenMetalPresent()` (same file, `:212-220`) behind a `// TODO(0.76): Metal
  uninitialised-memory NaN in resampling block` marker (`:114`, `:135`). Both tests use
  synthetic weights at `smallConfig()` (`:175-181`) — no gated real weights are needed to
  reproduce.
- `SAMEResamplingParityTest` and `SAMEAutoEncoderShapeTest.encodeDecodeShapes`
  (`engine/ml/src/test/java/org/almostrealism/ml/`) fail the same way with the real SAME-S
  weights, per the brief's job `31fc693a` (2026-09-13) run: convolution, mapping and segment
  stages match the PyTorch reference to `7.6e-6`, decoder layer 0 to `2.7e-4`; encoder layer 0
  and the shifted decoder layers are `NaN`.
- Values read as `0.0` on CPU (a fresh allocation is zero-filled there) and as garbage on
  Metal — the mechanism is a **read of memory that was never written for that kernel**, not
  an arithmetic error. `assertFinite` in the shape tests and the `!(maxAbs <= tolerance)`
  NaN-safe assertions in the parity test must not be weakened; a passing run must be real.

## What is already known (from the operator brief — do not re-derive, verify instead)

- A single resampling layer, in isolation, is finite. Two layers of the *same* chunk count in
  one compiled `Model` are finite. The defect appears once the midpoint-shift path runs
  layer 0 at one chunk count and layer 1 at a *different* chunk count inside the same
  compiled `Model` — every forward NaNs. The encoder block only shows it intermittently
  because `resamplingExtract` keeps a subset of positions, so the corrupted region is not
  always in the kept subset.
- Real-weight numbers on Metal (job `31fc693a`, 2026-09-13, recorded above) place the first
  failing stage at encoder layer 0 and the shifted decoder layers, consistent with the
  midpoint-shift trigger.
- Prior investigation on `feature/ml-same-autoencoder` (PR #312, memories tagged `SAME`,
  `PR312`, `metal`, `NaN`, `resampling`, June 2026) localized the first non-finite cell
  cell-by-cell (non-perturbing buffer reads after a NaN forward, since
  `AR_HARDWARE_OUTPUT_MONITORING` was found to *hide* the bug by forcing a host-read barrier
  after every cell): the shifted layer's (`numChunks=3`) `pre_norm` `dynamicTanh` output has
  roughly one full chunk (48 of 144 elements) silently under-processed, while the earlier
  `numChunks=2` layer and the shift itself are fully finite. Toggling
  `AR_HARDWARE_ARGUMENT_AGGREGATION` did not eliminate the NaN in either direction; toggling
  `AR_INSTRUCTION_SET_REUSE` changed *which* block (encoder vs. decoder) NaNs but did not
  eliminate it for both — evidence that reuse and aggregation *modulate* which uninitialised
  region gets exposed to the kept output positions, not that either one is the sole cause.
  These runs predate the source changes below and must be re-run against current source
  before being trusted further; they are retained here for the shape of the evidence, not as
  a settled conclusion.

## Verified from current source (this session, no Metal device — reading only)

This corrects the brief's literal "suspected mechanism" against the code as it exists on
this branch today, which has moved since the June 2026 investigation that produced the notes
above.

1. **Argument aggregation was rewritten, not merely present.** Commit `5f0648eab` ("Removed
   MemoryData argument aggregation", 2026-06-20) gutted the original
   `MemoryDataArgumentMap` aggregation implementation; commit `a6c5213ac` ("Adjusted argument
   aggregation process to allow for program reuse via instruction cache", 2026-06-23) rebuilt
   it with an explicit **collision guard** that did not exist when the June investigation ran:
   `AcceleratedComputationOperation.rebindAggregateForReuse`
   (`base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedComputationOperation.java:554-593`).
   For a reused operation it replays the aggregate fold against *this* operation's own
   computation and compares the replayed position sequence
   (`MemoryDataArgumentMap.describeAggregatePositions()`,
   `base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryDataArgumentMap.java:319-342`)
   against `ScopeInstructionsManager.getAggregatePositions()`, the layout recorded when the
   shared kernel was compiled. **A mismatch throws `HardwareException` ("Instruction cache
   collision...")** — it does not silently bind the wrong offset.
2. **Consequence:** the brief's literal suspected mechanism — "the second layer's kernel may
   be served a cached program whose aggregate layout was baked for the first layer's shapes,
   so an aggregated buffer is addressed at the wrong offset" — is a scenario this guard now
   catches and converts into a loud exception, not a silent NaN. Since the observed failure is
   silent, either (a) the guard's premise about what is "compatible" leaves a real gap, or
   (b) the defect is not in the aggregate-position mechanism at all. The guard's own javadoc
   states the boundary explicitly (`AcceleratedComputationOperation.java:533-547`):
   *"per-argument element counts are not part of [the compiled kernel's] identity — they are
   marshaled with every dispatch, which is what lets a deliberately size-generic kernel (such
   as a single-statement assignment dispatched over its count) serve computations of different
   sizes through one signature."* This is the claim to interrogate first: does the per-dispatch
   count/size marshaling this relies on actually reach a reused size-generic kernel correctly,
   for the specific pairing this bug produces?
3. **Why the shared kernel is more likely an `Assignment`/copy than the `dynamicTanh` itself.**
   `CollectionProducerComputationBase` appends output-shape detail to a computation's
   signature (`base/hardware/docs/INSTRUCTION_CACHING.md`, "Class-specific extensions"), so
   most shape-dependent ops — including `dynamicTanh` — get *different* signatures for
   `numChunks=2` vs. `numChunks=3` and should not share a compiled kernel at all. The kernel
   classes documented as deliberately size-generic (constant signature regardless of element
   count, dispatched over a runtime count) are copy/assignment kernels — exactly the kind that
   materializes each layer's output. `TransformerResamplingFeatures.resamplingLayerBlock`
   (`engine/ml/src/main/java/org/almostrealism/ml/TransformerResamplingFeatures.java:441-531`)
   is built from `DefaultCellularLayer`s whose output is a fresh, unzeroed
   `new PackedCollection(outputShape)` written by a device `Assignment` via `into(name, in,
   out, copy)` (`domain/graph/.../DefaultCellularLayer.java:253,306`). This is consistent
   with the June localization: the first non-finite cell was layer 1's `pre_norm`
   `dynamicTanh`, whose *input* is the previous stage's materialized output — i.e. the
   corruption is upstream of `dynamicTanh`'s own arithmetic, in the buffer that fed it.
4. **The obvious "globalWorkSize is stale" reading does not hold up on inspection and should
   not be re-asserted without evidence.** `HardwareOperator.globalWorkSize`
   (`base/hardware/src/main/java/org/almostrealism/hardware/HardwareOperator.java:233,255,270`)
   is a mutable instance field on the (possibly shared, reused) operator, but
   `AcceleratedOperation.setupOperator` calls `((KernelWork) operator).setGlobalWorkSize(process.getKernelSize())`
   on **every** `apply()` (`base/hardware/src/main/java/org/almostrealism/hardware/AcceleratedOperation.java:732-751`),
   and `process.getKernelSize()` traces to `ProcessDetailsFactory` computing
   `output.getCountLong()` from the *calling* operation's own destination
   (`base/hardware/src/main/java/org/almostrealism/hardware/ProcessDetailsFactory.java:397-483`).
   This looks correctly per-dispatch on paper. Whether `output`/`process` is actually rebuilt
   correctly for a reused, differently-sized operation in this specific graph shape is exactly
   what needs the profiler, not a source read — `ProcessDetailsFactory` and
   `AcceleratedComputationOperation.load()`'s reuse path were the site of a structurally
   similar bug fixed in `docs/plans/STALE_EVALUABLE_INVESTIGATION.md` (stale argument bindings
   surviving instruction-set eviction+recompile); that fix does not obviously cover the
   same-JVM, no-eviction, different-destination-size reuse this defect needs, and should not be
   assumed to.

None of the above proves the mechanism. It narrows where to look and rules out re-litigating
the aggregate-position collision as the primary hypothesis, since that path now throws loudly
when it fires. The next step is mandatory before forming a real hypothesis: read the actual
generated kernel.

## Method

### 1. Build the profiled two-layer shifted reproduction

Reproduce the *exact* failing shape inside a standalone, profiled test — not the full block,
so the operation tree stays small enough to read in `ar-profile-analyzer`. Compose, in order,
the same stages `addResamplingTransformerStack` uses for the shifted half
(`TransformerResamplingFeatures.java:354-399`):

```
reshape([numChunks=2, effChunk, dim]) -> resamplingLayerBlock(numChunks=2, ...)
  -> reshape(back to segment space) -> shift (halo concat)
  -> reshape([numChunks=3, effChunk, dim]) -> resamplingLayerBlock(numChunks=3, ...)
  -> reshape -> crop
```

Use `TransformerResamplingShapeTest.smallConfig(true)` (`:175-181`) for the dimensions —
`inChannels=4, outChannels=8, ..., mappingKernel=1` — the same configuration
`encoderBlockDownsamplesShape` already exercises, with synthetic weights from
`syntheticWeights`/`blockWeightShapes` (no gated data needed). Build a `Model`, `compile()`
it, attach an `OperationProfile`, and `forward()` once under `AR_HARDWARE_DRIVER=mtl`. Write
the profile to `engine/ml/results/` (a new small test class, e.g.
`ResamplingMidpointShiftProfileTest`, is the natural home — do not add it to
`TransformerResamplingShapeTest`, which must stay a fast CI shape test).

### 2. Read the generated source before hypothesizing further

Per Rule 3b (`CLAUDE.md`), this comes before any hand-instrumentation:

- `mcp__ar-profile-analyzer__load_profile` the written profile.
- `list_children` to enumerate the compiled operations for the two
  `resamplingLayerBlock` calls (`numChunks=2` and `numChunks=3`) — in particular each layer's
  materializing `Assignment` (the `DefaultCellularLayer` output copy) and the `pre_norm`
  `dynamicTanh`.
- `get_source` on each. Look specifically for:
  - **Two operations sharing one compiled program** (same generated Metal source, same
    function name) whose destinations differ in size — confirms kernel sharing across the
    `numChunks=2`/`numChunks=3` layers, and identifies which operation class it actually is
    (expected candidate: an `Assignment`/copy kernel per point 3 above, not `dynamicTanh`
    itself).
  - **The per-argument `offset`/`size` bindings** for that shared kernel, and whether the
    dispatch geometry (thread/grid dimensions, or a `count`/`kernelSize` value baked or passed
    into the source) actually reflects the `numChunks=3` layer's larger destination when that
    layer is the *second* (reused) invocation — or whether it still reflects whatever was
    baked when the kernel first compiled for `numChunks=2`.
  - Whether `AcceleratedComputationOperation.rebindAggregateForReuse` fires at all for this
    pairing (i.e., whether either layer's fold is non-empty) — if it does not, the aggregate
    mechanism is definitively not implicated and step 3 below can skip the aggregation runs.

This single observation is decisive: an argument binding that covers fewer elements/threads
than the kernel source actually needs to write is the uninitialised-read mechanism, directly
visible, with the exact kernel name and argument index named by the tool output.

### 3. Confirm the mechanism with controlled runs

Only after step 2 gives a concrete hypothesis, not before:

- `AR_HARDWARE_ARGUMENT_AGGREGATION=disabled` — expected to have **no effect**, per the
  June evidence and per point 3/4 above (the shared kernel is hypothesized to be size-generic
  for reasons other than aggregation). If this run *does* change the outcome, that falsifies
  the current framing and the investigation returns to `get_source` on whatever kernel is now
  implicated.
- Force the second layer's `resamplingLayerBlock` onto a distinct instruction-set signature
  (e.g. via a structural difference that defeats reuse, or by disabling
  `AR_INSTRUCTION_SET_REUSE`) — expected to make the NaN disappear for whichever block reuse
  was serving stale geometry to, per the June `enc.reuseON`/`dec.reuseOFF` asymmetry. Record
  both outcomes in memory regardless of which way they point.

### 4. Fix at the cause, in `base/hardware`

Once the exact kernel/argument/offset is named by step 2 and confirmed by step 3, fix the
dispatch/size derivation or the reuse-verification gap at its source — most likely in
`ProcessDetailsFactory`'s per-operation kernel-size resolution, in
`AcceleratedComputationOperation.load()`'s reuse substitution path, or in extending
`rebindAggregateForReuse`'s verification to cover whatever non-aggregate per-dispatch
quantity turns out to be shared incorrectly. Do **not**:
- Pad buffers or zero-fill before every kernel (masks the defect, matches the June finding
  that clearing `DefaultCellularLayer` output buffers made things *worse* by turning
  finite-garbage into NaN-garbage — see `bugs` namespace memory `432e26ad`).
- Disable aggregation or instruction-set reuse globally.
- Special-case the fix by backend (`isCPU()`/Metal checks in ML code) — the defect is a
  framework correctness gap under reuse, not a Metal quirk to route around.

The fix must keep CPU/Native results bit-identical (they already pass; do not touch the
non-Metal path unless the same defect is shown to reach it under different scheduling).

### 5. Regression coverage and verification runs

On a Mac with a `MetalDataContext`:

- Remove both `skipWhenMetalPresent()` calls and the two `TODO(0.76)` markers in
  `TransformerResamplingShapeTest.java:114,135`. `skipWhenMetalPresent` itself
  (`:212-220`) and its `Assume` become dead code once both markers are gone — check whether
  anything else still calls it before deleting the method.
- `mcp__ar-test-runner__start_test_run` with `test_classes: ["TransformerResamplingShapeTest"]`.
- `SAMEAutoEncoderShapeTest` (`encodeDecodeShapes`) and `SAMEResamplingParityTest`, the latter
  via `jvm_args: ["-DAR_SAME_WEIGHTS=/tmp/same-weights", "-DAR_SAME_REFERENCES=..."]` (the Mac
  Studio holds the weights at `/tmp/same-weights`; references at `/tmp/same-s-references` or
  `engine/ml/target/test-classes/same-s-references` — regenerate with
  `engine/ml/scripts/dump_same_references.py` if missing).
- The hardware module's own tests for anything touched (`ProcessDetailsFactory`,
  `AcceleratedComputationOperation`, `AcceleratedOperation`, `MemoryDataArgumentMap`, or
  wherever the fix lands) — run the specific test classes for those files, not the whole
  module.
- **New hardware-level regression test** (per the brief's definition of done): compile two
  kernels of different aggregate/dispatch layouts sharing one signature-reused compiled
  instruction set within one model/graph, and assert the second reads and writes exactly what
  it wrote — i.e. a minimal, deterministic version of the reproduction in step 1, at the
  `base/hardware` level rather than the ML level, so it runs fast and backend-independently
  (both CPU and Metal) in normal CI. This is the test that would have caught this defect
  directly, rather than through three layers of ML machinery.
- `mcp__ar-build-validator__start_validation` (default checks) before declaring done.

Report per-stage `maxAbs` numbers from `SAMEResamplingParityTest` in the final message of the
implementation job, per the brief.

## Constraints (carried from the brief)

- Do not weaken the NaN-safe assertions or tolerances in the parity tests — a passing run
  must be real.
- Follow `CLAUDE.md`: consult first, store memories as findings land, never commit, never
  modify `pom.xml`, use the MCP test runner and build validator before declaring done.
- Runner: a macOS node with Metal (the Mac Studio holds the SAME-S weights).

## Definition of done

`TransformerResamplingShapeTest`, `SAMEAutoEncoderShapeTest`, and `SAMEResamplingParityTest`
pass on Metal with no skip; the new `base/hardware` regression test passes on both CPU/Native
and Metal; the build validator is green; `commit.txt` for the implementation job names the
exact kernel/argument/offset and the fix, not just "fixed the NaN."

## Open questions for the implementation session

- Does `rebindAggregateForReuse` actually fire for the pairing this bug produces (i.e., does
  either layer fold anything into an aggregate at these small `smallConfig` dimensions,
  given `maxAggregateLength=1024` default)? If it never fires, the whole aggregate mechanism
  is a red herring for this specific defect and the fix search should go straight to
  dispatch-size/count derivation under reuse.
- Is the shared kernel (once named by `get_source`) actually the `DefaultCellularLayer`
  output-materializing `Assignment`, or something else entirely (e.g. a reshape/copy the
  `into()` idiom emits, or the shift/concat operation)? The June localization pointed at
  `dynamicTanh`'s *input* being corrupted, which is consistent with several different
  upstream kernels — `get_source` on the actual compiled tree resolves this directly instead
  of guessing from the ML-level symptom.
