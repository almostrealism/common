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

- **When a layer's output producer is created.** The output buffer is read only inside lambdas
  that dereference the `output` field when the cell is pushed:
  - the exit cell built in `DefaultCellularLayer.init`, `Cell.of((in, next) -> output(in, p(output)))`;
  - the forward cell that `DefaultCellularLayer.getForward()` builds once and caches in `fw`,
    which pushes `next.push(p(output))` downstream.

  So the `p(output)` producer is created when the cell is pushed, during `CompiledModel.compile`
  (`CompiledModel.java`). From then on, a compiled kernel holds that exact object. A
  `Provider` of it is captured when the argument map is built (`RootDelegateProviderSupplier.java`).
  Replacing the field after compiling has no effect on compiled kernels. Replacing it *before* a
  push does take effect, because neither lambda captures the buffer itself; the cached `fw` cell
  therefore does not need to be rebuilt for a second push (see §2, step 5).
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
- **Output monitoring.** It is off by default (`HardwareFeatures.outputMonitoring`, read from
  `AR_HARDWARE_OUTPUT_MONITORING`). When it is on, `LayerFeatures.layer(...)` gives the layer a
  `MonitorReceptor` through `setMonitor`, and `DefaultCellularLayer.output(...)` pushes it `p(output)`
  in the same operation list that writes it. Compose layers built by `LayerRoutingFeatures.compose`
  get no output monitor. The entry cell's input monitor reads `p(input)`, which an inference
  compile does not have.
- **Persistent buffers that are not layer outputs:**
  - **Compose-layer `auxInput` records.** They are allocated whenever `Layer.ioTracking` is on
    (`AR_GRAPH_IO_TRACKING`, default on; `LayerRoutingFeatures.compose`), and
    `setInputTracking(false)` does not clear them. The compose layer reads its branch through a
    `Cell.CaptureReceptor` (`auxReceptor`) created once, when the layer is built. Its receipt
    persists from one push to the next, and `CaptureReceptor` has no way to clear it.
  - **The DiT's conditioning copies** (`timestep`, `globalCond`, pre/post-transformer taps).
  - **KV caches**, which belong to the caller.
  - **`CompiledModel.output`**, which callers rely on (`DiffusionSampler`, `StableAudio3.generateFrom`).
- **`BranchBlock`'s gradient accumulator** is already allocated lazily, by `BranchBlock.gradient()`,
  only when a backward cell is built. `ParallelBlock`'s `gradient` field is lazy too: it is allocated
  only when a gradient is first pushed. An inference compile allocates neither, so the saving the
  task lists as related is already on master and is not part of this work.
- **Existing reuse of memory.** The only other reuse mechanism is `AllocationCache`
  (`base/hardware/.../mem/AllocationCache.java`). It keeps native allocations a provider has
  *released* and hands them out again for an allocation of the same size, and only
  `CudaMemoryProvider` uses it. It does nothing for buffers that stay allocated for the life of the
  model, which is the case here, and it is not used on Metal. No plan in `docs/plans/` covers sharing
  live layer buffers. `PLAN-20261123-audio-tagging-model-spike.md` measures against the memory cap of
  "the in-flight buffer-reuse work", and its open question 7 asks which work that is. This plan is
  the likely candidate, but it sets no cap. It only reports the baseline below and the
  after-measurement Stage 2 will add.

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

1. **Pass 1, analysis.** Push the cells as today and `flatten().optimize()` the forward list, with
   every layer output on memory of its own, distinct from every other layer's (§3 and open question 9
   cover how pass 1 gets those objects without the full per-layer allocation). Never `get()`/compile
   it. Distinct memory is a requirement, not a convenience. `optimize()` runs `subdivide()`, which
   cuts a list wherever `MemoryRegionList.overlaps` finds two regions on the same `getMem()` with
   overlapping ranges. Pass-1 objects that overlapped one another would make pass 1 cut where today's
   compile does not, so the grouping the analysis records would not be today's. Walk the optimized
   tree in execution order, and for each step (defined below) record the set of layer-output roots
   it reads and the set it writes. `MemoryRegionList.writes(member)` and
   `MemoryRegionList.dependentReads(member)` already do this for `OperationList.subdivide()`
   (`OperationList.java`). Reads must also cover inline (non-isolated) reads, so the walk collects
   every `CollectionProviderProducer` leaf in a member's producer tree, not only the isolated ones.
   Writes need the same widening. `MemoryRegionList.writes` records only `Assignment`
   destinations; any other writer is not treated as one. That covers more than custom cells:
   `LayerFeatures.into(name, in, out, copy, ...)` emits `Ops.o().copy(...)` when `copy` is true and
   the shape's count is 1, and `DefaultCellularLayer.output` passes `enableMemoryDataCopy` (default
   `true`). With `AR_HARDWARE_ASSIGNMENT_COPY` unset (default off,
   `MemoryDataFeatures.enableAssignmentCopy`), `copy(...)` returns a `MemoryDataCopy`, so a layer
   whose output shape has count 1 writes its output through a member `writes` does not see. A
   `MemoryDataCopy` built from producers exposes its source and target as `getChildren()`, so the
   walk can resolve both. Any writer it still cannot resolve must leave every buffer it might touch
   unpooled, rather than be read as writing nothing (open question 13).
2. **Interval of a buffer.** From the first step that writes it to the last step that reads it,
   inclusive at both ends. A buffer that no step reads still occupies the step that writes it. The
   last layer's output is read by the `"Model Forward Output"` assignment into `CompiledModel.output`,
   so it lives to the end of the pass. Nothing crosses a pass boundary: no layer reads its own
   previous output, so every interval closes within one pass. The SA3 graphs use the non-cached
   `sequence_attention.pdsl` layers, and where state does persist across passes, as in the
   autoregressive layers of `attention.pdsl`, it is held in caller-owned KV caches, not in layer
   outputs. Stage 2's analysis should assert this rather than assume it: an interval whose first
   read comes before its first write is a cross-pass reader and must leave that buffer unpooled.
3. **What a step is.** It is the largest unit whose internal order the analysis cannot rely on: a
   **fused kernel**. `OperationList.get` compiles a list into one kernel only when every member is a
   computation (`isComputation()`) **and** the list is uniform (`isUniform()`). The second condition
   applies because `OperationList.enableNonUniformCompilation` defaults to `false`. Any other list
   runs member by member through an `OperationListRunner`. The step walk must apply the same two
   tests `OperationList.get` applies, read from the same methods; a copy of the rule would drift. In
   a fused kernel, member *k*'s read of `X[j]` by one thread and
   member *k+1*'s write of `Y[t]` by another are not ordered. Within a fused list, writes are ordered
   before inline reads only for the same element in the same thread (the reason
   `subdivide()` exists). So two buffers touched anywhere in the same kernel must never share memory,
   which is why intervals are measured in kernels, not in list members. Members that run through
   `OperationListRunner` are separate steps, in order.
4. **Assignment.** Sort the buffers by interval start and assign them to pooled blocks by best fit,
   on the interval-graph coloring. A block is reused only after the last step of its current
   tenant's interval.
5. **Pass 2, build.** Install the assigned memory into the layers, then push, flatten and optimize
   again. The existing compile then continues with `get()`. The state left over from pass 1 has to
   be accounted for:
   - The cached `fw` cell and the exit cell read the `output` field when they are pushed, so they
     pick up the new memory with no change (see "Current mechanics").
   - The deprecated `accum(shape, Cell)` and `product(shape, shape, Cell, Cell)` in
     `LayerRoutingFeatures` create a fresh `CaptureReceptor` on every push, so they need nothing.
   - A compose layer's `auxReceptor` is created once, at construction, and keeps its pass-1 receipt.
     Pass 2 is correct only if every branch is pushed again before its compose layer reads it, which
     holds when pass 2 pushes in the same order as pass 1. Stage 2 must either clear the receipt
     before pass 2 (`CaptureReceptor` has no method for this today) or assert that each compose read
     in pass 2 sees a receipt written in pass 2.
   - `CompiledModel.compile` chains `outputReceptor` onto the last block's existing receptor
     (`Receptor.to(existingReceptor, outputReceptor)`). That must happen once, not once per pass.

   Stage 2 also has to audit every other `Cell` that captures a producer at push time and reuses it
   on a later push. This list covers only the ones named in this plan.
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
- **T5Gemma.** Two `accum` blocks per layer (`t5gemma_encoder.pdsl`). The `branch keys`/`branch
  values` come from the attention layers it imports (`sequence_attention.pdsl`), and the gated
  `product` from its feed-forward (`feed_forward.pdsl`). Same treatment.

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
holding the full per-layer set. Pass 1 still needs distinct objects to analyse, and they must not
overlap in memory (§2, step 1). Open question 9 weighs the options; views over one small shared root
do not meet that requirement. Pass-1 objects are never compiled or run.

### 4. Pool representation, argument aggregation and kernel argument maps

There are two ways to put several layers on the same memory:

- **(A) Views at assigned offsets into one arena root per model.**
- **(B) A pool of standalone blocks.** Each layer's buffer is a view at **offset 0** of the pooled
  block it was assigned, with the layer's own length and shape.

**Recommendation: (B).** The evidence, from the code and hardware modules and from
`CollectionProviderProducer` (`compute/algebra`):

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
    default (`MemoryDataArgumentMap.maxAggregateLength`, overridable with
    `AR_HARDWARE_AGGREGATE_MAX`).
  - Views of the same root collapse to one kernel argument (`MemoryDataArgumentMap.java`).
  - Under (A), a multi-GB arena is never aggregated, which differs from today for small outputs.
    It also merges, into one argument, every layer that touches the arena.
  - Under (B), a small output lands on a small pooled block, just as it lands on a small
    standalone buffer today. It aggregates on the same terms, with `&aggRoot=` in its signature
    as today. Large ones are direct arguments, as today. **This holds only when the pooled block
    has the tenant's own length** (see "Block sizes" below).
- **Aggregation has no aliasing check.**
  - `MemoryDataArgumentMap` identifies shared roots but never compares ranges.
  - Copy-back skips only a slice that shares memory with the explicit `output`
    (`MemoryDataArgumentMap.java`).
  - If two buffers of one kernel ever shared a pooled block, an aggregate copy-in of one and
    copy-back of the other could overwrite each other.
  - The step rule in §2 makes this impossible by construction: buffers of one kernel never share a
    block. The verify mode asserts it.
  - Stage 2 must also run on a backend that reports no argument limit, to exercise aggregation
    with pooled blocks. `AcceleratedOperation.isArgumentFoldingRequired()` folds every eligible
    argument when `ComputeContext.getArgumentLimit()` is empty, and only `MetalComputeContext`
    reports a limit. On Metal, therefore, only kernels that would exceed the limit fold at all, and
    a Metal-only run would leave most of the aggregation path untested. The native backend is the
    natural choice.
  - **Read-only copy-once.** `PLAN-20261103-read-only-aggregate-copy-once.md` (in progress) skips
    the per-dispatch copy-in for aggregated roots it can prove unchanged. Its precondition is
    `MemoryData.isReadOnly()`. Pooled blocks are ordinary writable `PackedCollection`s, so they
    never qualify. If that precondition is ever relaxed to "this kernel does not write it", a
    pooled block written by another kernel between dispatches would be copied in stale.
- **Kernel reuse with substituted arguments.** Reused kernels match arguments by root `getMem()`
  identity (`ProcessArgumentMap.java`), and an aggregating reused operation replays its fold
  and throws `InstructionCacheCollisionException` if positions differ
  (`AcceleratedComputationOperation.java`). (B) changes which object is the root, not the
  offsets or lengths, so it does not change fold positions. Stage 2 must confirm this with the
  instruction cache on.
- **Block sizes.** Best fit by length, with a block never smaller than its tenant. Blocks are
  `PackedCollection`s from the normal provider, so precision and memory accounting are unchanged.
  - **Review note: a block larger than its tenant changes small outputs.** The aggregation
    signature and eligibility read the *root*, not the view.
    `CollectionProviderProducer.signature()` appends `&aggRoot=` plus
    `getRootDelegate().getMemLength()`. `MemoryDataArgumentMap.isAggregationTarget(MemoryData)`
    tests the root's `getMemLength()` against `maxAggregateLength`.
  - A 600-element output given a 900-element block therefore gets a different signature from
    today's, so identical layers on blocks of different sizes stop sharing a compiled kernel. A
    600-element output given a 2,000-element block stops aggregating at all.
  - Neither change is wrong, but both break the "same signature as today" premise of (B). Two
    simple ways to keep it:
    - pool aggregation-eligible outputs (root at most `maxAggregateLength`) only on blocks of
      exactly their own length;
    - leave them out of the pool. They are at most 4 KB each, so this costs little memory.
  - Best fit applies without change to direct (non-aggregated) arguments: their signature is
    `offset:memLength|shape` of the view, which is the same on any block. See open question 14.
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
  - a graph with outputs of at most 1024 elements, so that they aggregate. The test must run with
    `AR_HARDWARE_AGGREGATE_MAX` unset (or assert `maxAggregateLength` is at least its output size),
    since a lower value would silently leave those outputs as direct arguments.

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
  master and against the branch. Record both tables on the tracker task. The Stage 1 harness was not
  committed, so Stage 2 has to rebuild it from the description under "How it was measured". To make
  the before and after tables come from the same code, Stage 2 should commit the rebuilt harness as a
  test that is skipped unless the SA3 weights are present (`skipLongTests`, plus a high
  `@TestDepth`), and run that same class against master and against the branch.

Every run is a bounded selection of a few classes, not a module suite.

## Success criteria

These were added in review, to make "done" checkable. Criteria 1–3 restate the test strategy.
The thresholds in 4 and 5 are proposals for the owner to confirm or change (open question 10).
Criterion 6 asks only for a measurement.
Stage 2 is done when all of the following hold:

1. Every training test listed under "Training unchanged" passes unmodified, and the new
   `compile(true)` test shows no pooled layer.
2. Every equivalence test passes with reuse on and in the verify mode of §2, on Metal and on the
   native backend, and compares exactly (or within the tolerance the owner chose under open
   question 6).
3. The refinement check of §2 did not fall back to per-layer buffers for the SA3 decoder, the
   blend encoder, the DiT or T5Gemma. The compile log shows `pooledBytes=` for each graph.
4. In the after-measurement, the tracked memory at rest falls by at least 4 GB for text generation
   followed by a blend, against the baseline of 12,394 MB. That is two-thirds of the roughly 6 GB the
   census predicts. Physical memory at rest falls by a comparable amount, and the peaks do not rise.
   A smaller saving is a finding to report before merging, not a reason to tune the criterion.
5. Generation time per clip does not regress by more than 5% against master on the same node.
   The plan expects no change, because the kernels are the same and splitting adds dispatches only
   where `subdivide()` now cuts. This criterion is proposed; the owner may set a different bound.
6. The time to compile each SA3 graph is reported against master. The two-pass compile of §2
   pushes and runs `flatten().optimize()` twice, and the baseline does not separate compile time
   from run time: text clip 1 takes 19.5 s against 10.3 s for later clips, and that gap includes
   the lazy DiT and T5Gemma compiles. No bound is proposed (open question 12).

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
   mutates the shared `Model`: it destroys the input records. The `TODO(review)` notes in
   `CompiledModel.compile` and `DefaultCellularLayer.setInputTracking` both point out that a later
   `compile(true)` could wire backward cells to freed memory, because `BackPropagationCell` keeps
   the input buffer it was given at construction. Pooling adds a second case: a backprop compile
   after an inference compile would find layers whose outputs are views of another
   `CompiledModel`'s pool. Should an inference compile mark the `Model` inference-only and make a later
   `compile(true)` throw? Or should a backprop compile restore standalone outputs and input records?
   Throwing is simpler and matches how models are used today.
   - Part of this exists already. `CompiledModel.compile` starts with `Model.recordCompilation()`,
     which counts compilations and warns from the second one on. Its javadoc and that of
     `CompiledModel.destroy()` both say a `Model` must not be compiled twice. Throwing would turn
     that warning into an error, for models compiled with reuse only. The two passes of §2 run
     inside one `compile` call, so they count as one compilation.
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
9. **Pass-1 placeholders.** The options are views over a small shared root (no allocation, and
   never run), unallocated handles, or the lazily created standalone buffers, released after the
   analysis (simpler, but they are allocated once during the compile).
   - **Review note:** the shared-root option does not work as described. Views over one small root
     share `getMem()` and must overlap, since their offsets have to fall inside the root.
     `MemoryRegionList.overlaps` would then report hazards between unrelated layers, and
     `subdivide()` would cut pass 1 where today's compile does not (see §2, step 1). Pass 1's
     grouping would then be finer than the real one, pass 2 would group members pass 1 kept apart,
     and the refinement check would reject the layout on every model.
   - Whatever placeholder is chosen must give each layer a distinct `getMem()`, or ranges that do
     not overlap. Releasing real standalone buffers after the analysis meets that requirement, but
     it puts the full per-layer set (2.7 GB per autoencoder graph) back into the compile-time peak.
     Unallocated handles would meet it only if `MemoryRegionList` and the argument signature code
     accept memory that has not been allocated. That has not been checked. One known case:
     `MemoryDataArgumentMap.isAggregationTarget(MemoryData)` returns `false` when `getMem()` is
     `null`, so a signature computed on an unallocated handle would lack the `&aggRoot=` part
     it gets once the memory exists.
10. **Success thresholds.** The 4 GB saving at rest and the 5% time bound under "Success
    criteria" were added in review, not measured. Are these the right bars, or should the saving be
    stated per graph instead (for example, decoder outputs under 500 MB pooled)?
11. **Pass-2 dependence on push order.** Correctness of pass 2 depends on compose layers seeing
    receipts from pass 2, not pass 1 (§2, step 5). Should Stage 2 add a way to clear a
    `CaptureReceptor`, or assert the push order instead? Adding a reset changes a type in
    `domain/graph` that many cells use.
12. **Cost of the second pass.** Pass 2 repeats the push, `flatten()` and `optimize()` of every
    inference compile, so compile time grows by up to one more optimization of the forward list.
    The baseline does not measure compile time on its own. Should Stage 2 measure it first and
    set a bound, or is any increase acceptable for the memory saved? If it is too slow, the
    pass-1 tree could be reused with its leaves swapped instead of rebuilt. That would need its
    own soundness argument, because the `subdivide()` cuts were made on pass-1 memory.
13. **Writers the analysis cannot see.** §2, step 1 notes that `MemoryRegionList.writes` ignores
    every writer except an `Assignment`, including the `MemoryDataCopy` that `LayerFeatures.into`
    produces for count-1 shapes. Nobody has yet checked how many SA3 layer outputs take that path.
    Should Stage 2 first count them (a layer census by writer type), and should an unresolved
    writer disable pooling for the whole compile or only for the buffers it might touch?
14. **Small outputs on larger blocks.** Under best fit, an aggregation-eligible output can land on
    a block longer than itself. That changes its `&aggRoot=` signature and can make it stop
    aggregating (§4, "Block sizes"). Should small outputs be pooled only by exact length, or left
    out of the pool? Exact length keeps the most memory, and leaving them out is the simplest.
    Either keeps the kernel-sharing and aggregation behaviour of today.
