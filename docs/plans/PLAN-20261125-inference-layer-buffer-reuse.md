---
status: proposed
created: 2026-11-25
closed:
tracker: [56266c47-e1ad-4b6e-a9f4-4d20e25874de]
---

# Reuse layer output buffers in models compiled for inference

**Category:** Memory

**Stage:** design and baseline only. Nothing described under "Design" is implemented on this
branch. Marking the pull request ready for review is the approval to implement it.

## Motivation

Every `DefaultCellularLayer` owns a `PackedCollection` for its output for the life of the model.
`LayerFeatures.layer(...)` (`domain/graph/.../layers/LayerFeatures.java`) and
`LayerRoutingFeatures.compose(...)` (`LayerRoutingFeatures.java`) both call
`init(inputShape, Layer.ioTracking, true)`, and `init` allocates the buffer immediately
(`DefaultCellularLayer.java`), when the layer is constructed rather than when a model is
compiled. In a pass, a layer's output is only needed until its last reader has run. A model
compiled for inference could therefore share memory between layers whose outputs are never live
at the same time, the way a compiler assigns registers.

For Stable Audio 3, layer output buffers account for most of the memory left over once the
weights are subtracted. The baseline below shows this, and it is the only option found that
lowers physical memory as well as the tracked count. Removing buffers that are never touched
lowers only the tracked count, because untouched Metal shared buffers are not resident.

## Baseline (current master, before any change)

### How it was measured

- **Node:** the macOS build node: Apple silicon, 128 GB RAM, Metal backend (auto-selected).
  `AR_HARDWARE_MEMORY_SCALE=7` (32 GB tracked ceiling).
- **Code:** `origin/master` as of 2026-11-25, unmodified. It was built with `mvn install -DskipTests`
  and run through the test runner.
- **Weights:** the released sets under `/Users/Shared/models/stable-audio-3-{dit,autoencoder,conditioner,t5gemma}`
  and the exported tokenizer `stable-audio-3-tokenizer/t5gemma-tokenizer.bin`.
- **Harness:** a temporary test class in `engine/ml` (`org.almostrealism.ml.audio`). It is not
  committed; its method is described here so that the after-measurement can repeat it exactly.
  - It builds `StableAudio3.small(dit, conditioner, t5gemma, autoencoder, 11.0)`: 11 s plus the
    6 s of `HEADROOM_SECONDS`, i.e. compiled for 17 s and 753,664 samples, with 184 latent frames.
  - It sets the tokenizer and generates three **4 s text clips**:
    `generateFromText(seed, "a warm analog synth pad", 4.0).evaluate()`, 8 steps (the default).
  - It then builds a **blend encoder** the way a client does. It takes a `Model` of
    `SAMEAutoEncoder.small(ae).encoder(1, getAudioLength())` and calls `compile(false)`.
  - It runs two **blends**: it encodes a `randnFill()` clip, then calls
    `generateFromText(seed, prompt, 4.0, 0.7, latent)`. The noise source stands in for the
    composer.
  - **Tracked memory** is the sum of `MetalMemoryProvider.getAllocatedMemory()` over
    `Hardware.getLocalHardware().getDataContext().getMemoryProviders()`, the same reading
    `Clap2TaggingSpikeTest.measuredDeviceBytes` takes.
  - **Physical footprint** is the `Footprint:` total of `/usr/bin/footprint -p <pid> -f bytes`.
    The `IOAccelerator (graphics)` category is the GPU-buffer part of it.
  - A daemon thread samples tracked memory every 200 ms and the footprint every 1 s, keeping the
    peaks. The peaks are reset at the start of each clip.
  - At each stage boundary the harness runs `System.gc()` and sleeps 1 s, three times, then
    records the settled values.
- **Layer-buffer census:** a second method of the same harness walks the block graphs without
  compiling them. It follows `SequentialBlock.getBlocks`, `BranchBlock.getChildren`,
  `ParallelBlock.getChildren` and `Model.getInputs` down to each `DefaultCellularLayer`, and sums
  `getOutput()` and `getInput()` at 4 bytes per element. Two kinds of buffer are not counted:
  - buffers behind `DefaultBlock` instances, which the walk cannot see inside;
  - the compose-layer `auxInput` records, which are held by a closure.

  The census is therefore a lower bound.

The first run used `-Xmx8g`, before the GC settling was added. It showed tracked memory growing
by 2.1 GB between the first and second text clip. The settled second run (`-Xmx2560m`, the heap a
desktop client ships with) shows that this was garbage not yet collected, not growth. Tracked
memory is flat across repeated clips and across repeated blends. The table is the second run.

### Stages (MB, `-Xmx2560m`, settled after GC)

| Stage | Tracked | Peak tracked during stage | Physical | Peak physical during stage | IOAccelerator (graphics) |
|---|---|---|---|---|---|
| Weights read into `StateDictionary` | 0 | 0 | 125 | 223 | 0 |
| Generator built (decoder compiled; DiT and T5Gemma compile lazily) | 3,756 | 3,756 | 762 | 1,952 | 1 |
| After text clip 1 (19.5 s, includes compiling) | 8,822 | 9,733 | 10,758 | 12,112 | 8,831 |
| After text clip 2 (10.3 s) | 8,822 | 12,161 | 10,963 | 15,224 | 8,831 |
| After text clip 3 (10.3 s) | 8,822 | 11,092 | 10,917 | 13,296 | 8,831 |
| Blend encoder compiled | 11,975 | 11,975 | 10,956 | 13,296 | 8,831 |
| After blend 1 (11.7 s) | 12,394 | 15,196 | 14,534 | 18,062 | 12,406 |
| After blend 2 (11.8 s) | 12,394 | 15,308 | 14,551 | 18,153 | 12,406 |

**Summary of the baseline:**

- **Text generation:** 8.8 GB tracked and 10.9 GB physical at rest. Peaks reach 12.2 GB tracked
  and 15.2 GB physical.
- **Text plus blend:** 12.4 GB tracked and 14.5 GB physical at rest. Peaks reach 15.3 GB tracked
  and 18.2 GB physical.
- The physical figures include the JVM: about 0.26 GB of live heap, plus its reserve.
- The gap between the resting and the peak numbers, roughly 2.5–3.3 GB, is temporary memory used
  during a pass, not layer buffers. See open question 8.

### Layer-buffer census (graphs at the 17 s compile length)

| Graph | `DefaultCellularLayer` outputs | Sum of output buffers | Largest single output | Sum of input records |
|---|---|---|---|---|
| SAME-S decoder | 239 | 2,669 MB | 55 MB | 2,277 MB |
| SAME-S encoder | 238 | 2,655 MB | 55 MB | 2,274 MB |
| DiT | 923 (plus 500+ uncounted `DefaultBlock`s) | ≥ 1,648 MB | 7 MB | 1,512 MB |
| T5Gemma encoder | not counted: the model is built inside `T5GemmaEncoder` and not reachable | — | — | — |

**How the census squares with the stages:**

- **Text, at rest (8,822 MB tracked):**
  - weights for the GPU: about 3.2 GB (DiT 1.84 GB, T5Gemma 1.13 GB, decoder 0.22 GB);
  - decoder outputs: 2.67 GB;
  - DiT outputs: at least 1.65 GB;
  - about 1.3 GB left for T5Gemma's layer buffers, the `DefaultBlock`/`auxInput` buffers and
    scratch.
- **Blend encoder:** compiling it adds 3.15 GB tracked, of which 2.65 GB is output buffers and
  about 0.22 GB is weights.
- **Input records:** these are allocated at construction and destroyed by `compile(false)`
  (`CompiledModel.java` → `DefaultCellularLayer.setInputTracking(false)`). They do not
  appear at rest, but each autoencoder graph briefly holds about 2.3 GB of them while it is being
  built.

The largest single layer output is 55 MB. In a mostly sequential graph only a handful of outputs
are live at once (see "Lifetimes" below), so the outputs of the autoencoder graphs should fit in a
few hundred MB rather than 2.7 GB each, and the DiT's in tens of MB rather than 1.6 GB. Together
that is roughly 6 GB of tracked and resident memory for text plus blend. This is an estimate from
the census; Stage 2 replaces it with a measurement.

## Current mechanics, with evidence

- **When a layer's output producer is created.** In `init`, the output buffer is read only inside
  lambdas:
  - `Cell.of((in, next) -> output(in, p(output)))` at `DefaultCellularLayer.java`;
  - `next.push(p(output))` there.

  So the `p(output)` producer is created when the cell is pushed, during `CompiledModel.compile`
  (`CompiledModel.java`). From then on, a compiled kernel holds that exact object. A
  `Provider` of it is captured when the argument map is built (`RootDelegateProviderSupplier.java`).
  Replacing the field after compiling has no effect on compiled kernels.
- **What an inference compile changes today.** With `backprop == false`, `CompiledModel.compile`
  only calls `model.setInputTracking(false)` (`CompiledModel.java`). That destroys the
  input records, and each entry cell then passes the incoming producer straight through
  (`DefaultCellularLayer.java`). Each layer's kernel therefore reads the previous layer's
  `p(output)` directly. Output buffers are never released.
- **Which buffers the backward pass reads.** Only the recorded input, never an output:
  - `BackPropagationCell.push` propagates from `p(input)` (`BackPropagationCell.java`);
  - `DefaultGradientPropagation` re-evaluates the operator on that input
    (`DefaultGradientPropagation.java`);
  - compose layers read `p(auxInput)` and `layer.getInput()` (`LayerRoutingFeatures.java`).

  During training, an output buffer is read only by the next layer's input copy, in the same pass.
- **`getOutput()` readers.** No main-code caller of a layer's `getOutput()` exists. The test
  callers are:
  - `ConvolutionModelTests`;
  - `TrainModelTest`;
  - `AssignmentRecordingDiagTest`.

  All of these are training or `compile()` paths.
- **Output monitoring.** It is off by default (`HardwareFeatures.java`). When it is on, the
  `MonitorReceptor` is pushed `p(output)` in the same operation list that writes it
  (`DefaultCellularLayer.java`).
- **Persistent buffers that are not layer outputs:**
  - **Compose-layer `auxInput` records.** They are allocated whenever `Layer.ioTracking` is on
    (`LayerRoutingFeatures.java`), and `setInputTracking(false)` does not clear them.
  - **The DiT's conditioning copies** (`timestep`, `globalCond`, pre/post-transformer taps).
  - **KV caches**, which belong to the caller.
  - **`CompiledModel.output`**, which callers rely on (`DiffusionSampler`, `StableAudio3.generateFrom`).
- **`BranchBlock`'s gradient accumulator** is already allocated lazily, by `BranchBlock.gradient()`,
  only when a backward cell is built. `ParallelBlock.gradient` is lazy too. An inference compile
  does not allocate either, so the saving the task lists as related is already on master and is not
  part of this work.

## Design

### 1. Where reuse applies, and what keeps training unchanged

- **Only `compile(false)` reuses buffers.** Reuse is a step of `CompiledModel.compile` taken only
  when `backprop == false`. The `backprop == true` path keeps building the operation lists exactly
  as it does today, with one standalone buffer per layer. No backward cell, input record or
  gradient buffer is ever pooled.
- **Training needs no special case.** A model compiled with backprop never reaches the reuse code.
  The training tests are the check that its graphs and numbers are unchanged (see "Test strategy").
- **Kill switch.** One flag on `CompiledModel`, read from `AR_GRAPH_LAYER_BUFFER_REUSE` (default
  on once Stage 2 lands), turns reuse off. It exists for A/B equivalence runs and for bisecting.
- **A compiled model owns its layout.** The layers of a `Model` belong to one compiled model at a
  time, and the pool belongs to that `CompiledModel`. Its `destroy()` releases the pool (see open
  question 1 on recompiling).

### 2. Lifetimes come from the compiled operations, not from block types

A table of lifetime rules per `Block` type (sequential, branch, accum, product, concat, split,
compose, deprecated `CaptureReceptor` accum, PDSL `DefaultBlock`s, DiT `into()` taps, model inputs)
would always be one custom `Cell` behind the code. A reader it missed would silently produce wrong
numbers. Lifetimes are therefore taken from what the forward pass actually does:

1. **Pass 1, analysis.** Push the cells as today and `flatten().optimize()` the forward list, using
   the per-layer buffers. Never `get()`/compile it. Walk the optimized tree in execution order, and
   for each step (defined below) record the set of layer-output roots it reads and the set it
   writes. `MemoryRegionList.writes(member)` and `MemoryRegionList.dependentReads(member)` already
   do this for `OperationList.subdivide()` (`OperationList.java`). Reads must also cover
   inline (non-isolated) reads, so the walk collects every `CollectionProviderProducer` leaf in a
   member's producer tree, not only the isolated ones.
2. **Interval of a buffer.** From the first step that writes it to the last step that reads it,
   inclusive at both ends. A buffer that no step reads still occupies the step that writes it. The
   last layer's output is read by the `"Model Forward Output"` assignment into `CompiledModel.output`,
   so it lives to the end of the pass. Nothing crosses a pass boundary: no layer reads its own
   previous output (verified: KV state lives in caller-owned caches, `attention.pdsl`), so
   every interval closes within one pass.
3. **What a step is.** It is the largest unit whose internal order the analysis cannot rely on: a
   **fused kernel**. An `OperationList` whose members are all computations compiles into one kernel
   (`OperationList.get`). In that kernel, member *k*'s read of `X[j]` by one thread and
   member *k+1*'s write of `Y[t]` by another are not ordered. Within a fused list, writes are ordered
   before inline reads only for the same element in the same thread (the reason
   `subdivide()` exists). So two buffers touched anywhere in the same kernel must never share memory,
   which is why intervals are measured in kernels, not in list members. Members that run through
   `OperationListRunner` are separate steps, in order.
4. **Assignment.** Sort the buffers by interval start and assign them to pooled blocks by best fit,
   on the interval-graph coloring. A block is reused only after the last step of its current
   tenant's interval.
5. **Pass 2, build.** Install the assigned memory into the layers. Clear the cells' captured state
   (e.g. `CaptureReceptor` receipts and the cached `fw` cell; these must be re-created, not
   re-pushed) and push, flatten and optimize again. The existing compile then continues with
   `get()`.
6. **Refinement check.** Pass 2's kernel grouping must be a refinement of pass 1's: the same steps,
   or steps split further. `subdivide()` may cut more when buffers share memory, and cutting more
   only shortens the intervals, so the assignment stays safe. If pass 2 groups members that pass 1
   kept apart, the compile reverts to per-layer buffers and logs why, instead of running a layout
   that was never analysed. A debug mode (`AR_GRAPH_LAYER_BUFFER_REUSE_VERIFY`) also re-walks
   pass 2 and asserts that no step touches two distinct intervals that share memory.

**How this covers the composition cases:**

- **Skip connections / `accum` / `residual`.**
  - `SequentialBlock.accum(value)` branches `value` off the current tail and appends a compose
    `"accum"` layer (`SequentialBlock.java`).
  - The compose layer's main input is the predecessor's `p(output)`. It is read in a step after
    every layer of `value` has been emitted, so that buffer's interval spans the whole branch. No
    rule is needed; the read is in the operation list.
- **`BranchBlock` (`branch`, `split`).** The block pushes the same producer to every child and then
  to the downstream block (`BranchBlock.java`). The source buffer's interval ends at the last
  child or downstream step that reads it.
- **`ParallelBlock` (`accum(List)`, `concat(List)`).** Each child's output stays live until the
  `combine` step reads it (`ParallelBlock.java`).
- **Autoencoder.** `SAMEAutoEncoder` is a chain of four stages (`SAMEAutoEncoder.java`).
  The resampling transformer layers use two `accum`s and an `accum_blocks`
  (`resampling_transformer.pdsl`), and differential attention uses branches. Every one of
  these appears as reads in the list.
- **DiT.**
  - It has residual input and output projections (`DiffusionTransformer.java`), plus
    `accum` sublayers for self-attention, cross-attention and feed-forward.
  - Cross-attention K/V branches hang off `condEmbed`, a `Model.addInput` input
    (`SequenceAttentionFeatures.java`). Model inputs are pushed first
    (`CompiledModel.java`), so the K/V records are written at the start of the pass and
    read by each block's attention. Their intervals simply span most of the pass and they are not
    shared, which is correct.
  - The adaLN `into(timestep/globalCond)` copies target non-layer buffers and are not pooled.
- **T5Gemma.** Two `accum` blocks per layer, `branch keys`/`values`, and a `product`
  (`t5gemma_encoder.pdsl`). Same treatment.

### 3. Which buffers are pooled

**Pooled:**

- `DefaultCellularLayer.output` of every layer in the compiled model.
- A compose layer's `auxInput`, **only when its branch is reached in the same pass**. When it is
  reached, an inference compile can skip that record altogether and pass the branch's producer
  through, as the entry cell already does for input records. That removes a copy as well as a
  buffer.

**Never pooled:**

- **A compose `auxInput` whose branch is *not* reached.** It is written by another model's pass
  and read by this one (`LayerRoutingFeatures.java`), so its lifetime is not
  visible to this compile.
- **Everything else:** weights, `CompiledModel` inputs and `output`, caller-supplied `into(...)` /
  `andThen(PackedCollection)` destinations, KV caches, and DiT conditioning copies.

**Allocation becomes lazy.** `init` keeps the output's *shape*. The standalone buffer is created
the first time a backprop compile, or a caller of `getOutput()` on an uncompiled layer, needs it.
That makes the per-layer allocation at construction go away, which is also what lets pass 1 avoid
holding the full per-layer set. Pass 1 still needs distinct objects to analyse. One option is
placeholder collections that are views over a small shared root; open question 9 asks whether
creating them as unallocated handles is preferable. Pass-1 objects are never compiled or run.

### 4. Pool representation, argument aggregation and kernel argument maps

There are two ways to put several layers on the same memory:

- **(A) Views at assigned offsets into one arena root per model.**
- **(B) A pool of standalone blocks.** Each layer's buffer is a view at **offset 0** of the pooled
  block it was assigned, with the layer's own length and shape.

**Recommendation: (B).** The evidence, from the hardware module:

- **Offsets are compiled into kernels.**
  - A view's offset is fixed when the kernel is compiled: `DefaultScopeInputManager.java`
    sets it, and `DefaultLanguageOperations.java` renders it as `root + offset`.
  - The provider-leaf signature includes it: `offset:memLength + shape`
    (`CollectionProviderProducer.java`).
  - Under (A), identical layers at different arena offsets get different signatures, so the
    repeated DiT and T5Gemma blocks would stop sharing compiled kernels.
  - Under (B), offset 0 and the layer's own length give the same signature as today.
- **Aggregation is decided by root size.**
  - An argument is eligible only if its *root* is at most `maxAggregateLength` elements, 1024 by
    default (`MemoryDataArgumentMap.java`).
  - Views of the same root collapse to one kernel argument (`MemoryDataArgumentMap.java`).
  - Under (A), a multi-GB arena is never aggregated, which differs from today for small outputs.
    It also merges, into one argument, every layer that touches the arena.
  - Under (B), a small output lands on a small pooled block, just as it lands on a small
    standalone buffer today. It aggregates on the same terms, with `&aggRoot=` in its signature
    as today. Large ones are direct arguments, as today.
- **Aggregation has no aliasing check.**
  - `MemoryDataArgumentMap` identifies shared roots but never compares ranges.
  - Copy-back skips only a slice that shares memory with the explicit `output`
    (`MemoryDataArgumentMap.java`).
  - If two buffers of one kernel ever shared a pooled block, an aggregate copy-in of one and
    copy-back of the other could overwrite each other.
  - The step rule in §2 makes this impossible by construction: buffers of one kernel never share a
    block. The verify mode asserts it. Stage 2 must also run on the native backend, which has no
    argument limit and so folds every eligible argument
    (`AcceleratedOperation.java`), to exercise aggregation with pooled blocks.
- **Kernel reuse with substituted arguments.** Reused kernels match arguments by root `getMem()`
  identity (`ProcessArgumentMap.java`), and an aggregating reused operation replays its fold
  and throws `InstructionCacheCollisionException` if positions differ
  (`AcceleratedComputationOperation.java`). (B) changes which object is the root, not the
  offsets or lengths, so it does not change fold positions. Stage 2 must confirm this with the
  instruction cache on.
- **Block sizes.** Best fit by length, with a block never smaller than its tenant. Blocks are
  `PackedCollection`s from the normal provider, so precision and memory accounting are unchanged.
  The compile logs `layerOutputBytes=` (per-layer total) and `pooledBytes=` (pool total), which the
  after-measurement uses directly.

### 5. `getOutput()`, monitoring and other readers

- **`getOutput()` on a layer of an inference-compiled model.** It returns the layer's view of its
  pooled block. That view is valid only between the layer's write and the next tenant's write. To
  make that impossible to misuse quietly, the layer records that it is pooled. `getOutput()` then
  throws `IllegalStateException`, saying the output is shared under inference reuse and how to keep
  it.
- **Keeping an output.** A caller that wants a layer's output after an inference pass marks the
  layer, or the `Model`, as retained before compiling (a `retainOutput()` on `DefaultCellularLayer`
  and a model-level switch). Retained layers keep a standalone buffer and are left out of the pool.
  No current main-code caller needs this. Every existing `getOutput()` caller is a test on a
  backprop compile, which is unaffected.
- **Output monitoring (`HardwareFeatures.outputMonitoring`).** The monitor's read is in the same
  operation list as the write, so the analysis sees it inside the buffer's interval. No special
  case is needed. While monitoring is on, though, the monitor operation sits between kernels and
  can change the grouping, so the refinement check in §2 decides. A test covers monitoring on.
- **Intermediate taps** (`andThen(into(dest))`, the DiT's pre/post-transformer taps,
  `CaptureReceptor` captures in tests). These copy a layer output into a caller-owned buffer inside
  the pass. They are reads within the interval, and the destination is not pooled.

## Test strategy

**Equivalence: per-layer buffers versus pooled, on the same inputs.** Each test compiles the same
`Model` twice, with reuse off and on (two fresh `Model` instances built from the same weights). It
then compares the outputs **exactly**: the kernels are the same, so the results should be bitwise
identical. A tolerance is a fallback only if Stage 2 shows that splitting kernels changes the order
of floating-point operations (open question 6). Each test also asserts `pooledBytes < layerOutputBytes`.

- **Small synthetic graphs** in `engine/utils`, on both Metal and native:
  - a dense chain;
  - `accum`/`residual`;
  - `BranchBlock` with three children;
  - `split` with a main index;
  - `accum(List)` and `concat(List)`;
  - `product`;
  - a compose layer fed from `Model.addInput`;
  - an `andThen(into(...))` tap;
  - the deprecated `CaptureReceptor` accum (with `allowNonComposites`);
  - output monitoring on;
  - a graph with outputs of at most 1024 elements, so that they aggregate.

  Each runs in the verify mode of §2. One test asserts that `getOutput()` on a pooled layer throws,
  and that a retained layer's output equals the unpooled one.
- **SA3 autoencoder.**
  - `SAMEAutoEncoderParityTest` against its references, with reuse on.
  - A direct off/on comparison of encoder and decoder outputs on one seeded clip.
- **DiT.** `StableAudio3TransformerParityTest` with reuse on, plus an off/on comparison of one
  denoising step with fixed conditioning.
- **T5Gemma.** `T5GemmaEncoderStageParityTest` with reuse on, plus an off/on comparison of the
  encoder output for one prompt.
- **End to end.** `StableAudio3GenerationTest#generatesAudioFromATextPrompt`, and an off/on
  comparison of one seeded generation.
- **Training unchanged.** These run unmodified and must pass:
  - `BackPropagationTests`, whose `compile(true, true)` exact-value gradient asserts show gradients
    are unaffected;
  - `TrainModelTest`;
  - `SyntheticDenseTrainingTest`;
  - `ConvolutionModelTests`;
  - `LayerTrackingTest`;
  - `AuxiliaryInputParameterUpdateTest`;
  - `AssignmentRecordingDiagTest`.

  A new test asserts that a `compile(true)` model has no pooled layers and that every layer's
  `getOutput()` is a distinct standalone buffer.
- **After-measurement.** Repeat the harness above exactly, on the same node and settings, against
  master and against the branch. Record both tables on the tracker task.

Every run is a bounded selection of a few classes, not a module suite.

## Implementation outline (Stage 2, after approval)

1. **Lazy output allocation in `DefaultCellularLayer`.** Keep the shape and create the standalone
   buffer on first need. Training behaviour stays identical.
2. **Read/write-set walk over an optimized `OperationList`.** This generalises the
   `MemoryRegionList.writes` and `dependentReads` methods `subdivide()` already uses, as a method on
   `OperationList`, plus the kernel-step grouping.
3. **The layer-output pool** (interval assignment, best fit), owned by `CompiledModel`, and the
   two-pass inference compile with the refinement check, the kill switch and the verify mode.
4. **The compose `auxInput` pass-through** when the branch is reached in the same pass.
5. **`getOutput()` contract and `retainOutput()`.**
6. **Tests and the after-measurement** as above.

## Open questions for the owner

1. **Recompiling a `Model` that was compiled for inference.** Today `compile(false)` already
   mutates the shared `Model`: it destroys the input records, and the `TODO(review)` at
   `CompiledModel.java` notes that a later `compile(true)` could wire backward cells to freed
   memory. Should an inference compile mark the `Model` inference-only and make a later
   `compile(true)` throw? Or should a backprop compile restore standalone outputs and input records?
   Throwing is simpler and matches how models are used today.
2. **`getOutput()` on a pooled layer.** Throwing plus an explicit `retainOutput()` (proposed), or
   returning the shared view with a documented validity window?
3. **Sharing across compiled models.** The DiT, decoder and blend encoder of one generator run one
   after another, so their pools could be one pool: 2.7 GB of decoder outputs and 2.7 GB of encoder
   outputs would no longer need to coexist. That needs an owner for cross-model ordering, a pool
   object passed to several compiles, and is not in this plan. Should it be a follow-up?
4. **Input records allocated at construction.** About 2.3 GB per autoencoder graph is allocated
   while the graph is built and freed by `compile(false)`. That raises the peak during building, and
   building is where an earlier run of the generator hit the 16 GB cap (`DefaultCellularLayer.init`
   under `TransformerResamplingFeatures.resamplingLayerBlock`). Allocating input records only for a
   backprop compile needs `BackPropagationCell.setForwardInput` to resolve its input lazily (it is
   captured at construction, `LayerFeatures.java`). Include it in Stage 2, or file it separately?
5. **Compose `auxInput` pass-through.** Include it in Stage 2 (proposed), or keep Stage 2 to layer
   outputs only?
6. **Equivalence criterion.** Bitwise equality (proposed), or a tolerance if kernel splitting
   reorders reductions?
7. **Kill switch default.** Reuse on by default for every `compile(false)`, or opt-in per model at
   first?
8. **Temporary memory during a pass.** Peaks are 2.5–3.3 GB above the resting tracked memory and
   about 4 GB above the resting physical memory, from temporaries created per evaluation, not from
   layer buffers. This plan does not address it. Should it be a separate task?
9. **Pass-1 placeholders.** Views over a small shared root (no allocation, and never run), or
   reusing the lazily created standalone buffers and releasing them after the analysis (simpler,
   but it allocates them once during the compile)?
