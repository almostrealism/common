# AudioScene Rendering Pipeline Redesign

## Motivation

A week of incremental optimization on the `feature/audio-loop-performance` branch
established clear evidence that the current architecture has fundamental limits:

- **Expression-level optimizations don't help.** LICM factoring hoisted 216/229 sin()
  calls — zero kernel impact. Exponent strength reduction eliminated 111 powf() calls —
  zero kernel impact. The GPU compiler already performs these optimizations independently.

- **Copy elimination doesn't help.** Removing 46 array assignments from CachedStateCell —
  zero kernel impact. GPU memory bandwidth absorbs trivial copy operations.

- **The kernel is memory-bandwidth bound**, not compute bound. 2530 array assignments per
  iteration × 4096 = 10.4M memory operations per tick. The monolithic kernel processes
  all 6 channels sequentially in a single loop, creating a 2783-line inner loop body
  that thrashes the cache.

- **prepareBatch() adds 53ms (27.5%) per tick** at 4096-frame buffers. This is pure
  Java-side work (pattern resolution, sample buffer preparation) that cannot be
  GPU-accelerated.

The conclusion: **no amount of expression-level optimization will achieve real-time
rendering.** The architecture itself must change.

### Current Performance (M1 Ultra, Metal+JNI, 4096 frames)

| Component | Time | Budget | Over |
|-----------|------|--------|------|
| prepareBatch (24 channels) | 53ms | — | — |
| Loop kernel (monolithic) | 139ms | — | — |
| **Total per tick** | **192ms** | **92.9ms** | **2.07×** |

---

## Current Architecture

### How a Tick Works Today

```
AudioScene.tick() → OperationList:
  Phase 0: bufferFrameIndex = 0                          [Java, ~0ms]
  Phase 1-24: PatternAudioBuffer.prepareBatch() × 24     [Java, ~53ms]
  Phase 25: Loop(4096) { cellGraph.tick() }              [Native, ~139ms]
  Phase 26: currentFrame += bufferSize                   [Java, ~0ms]
```

### The Monolithic Kernel Problem

The Loop at Phase 25 compiles the entire cell graph into a **single C function** with
a single for-loop iterating 4096 times. Inside each iteration:

- 6 source channels are read (PatternAudioBuffer outputs)
- Each channel passes through: sample interpolation → filter chain (IIR biquad) →
  delay line → automation (sin/pow LFO modulation) → mix to output
- All channels process sequentially within the same loop body
- Data flows through CachedStateCell chains: `cachedValue → push → receptor → ...`
- Each CachedStateCell adds: 1 copy + 1 zero reset + downstream push(es)
- Result: 535 simple copies, 103 zero resets, 117 accumulations per iteration

### The Cell Graph Memory Model

```
CellList.tick() → getAllTemporals() → ordered list of Temporal.tick() calls
  → each CachedStateCell.tick():
      1. push(cachedValue) to receptor     [copy or accumulate]
      2. reset(cachedValue) to 0.0         [zero write]
  → receptor chain propagates data through more cells
  → all operations compile into one OperationList → one Loop → one kernel
```

The push-based receptor pattern means data flows through chains of intermediate
buffers. Each link in the chain owns its own `cachedValue` and `outValue` memory,
even when the transformation is trivial (pass-through, identity).

### Why Per-Channel Splitting Is Hard Today

1. **CellList builds one OperationList** — no concept of channel grouping
2. **OperationList.get() requires uniform count** — all ops must agree on parallelism
3. **Memory consolidation is global** — one contiguous buffer for all channels
4. **Tick ordering is a single flat list** — parent hierarchy doesn't support
   per-channel scheduling
5. **Loop wraps the entire tick** — can't have per-channel loops with different
   iteration counts or schedules

---

## Design Goals

### G1: Per-Channel Parallelism

Each channel should compile to its own kernel, enabling:
- Parallel GPU dispatch (6 channels × 2 stereo = 12 independent kernels)
- Independent optimization per channel (silent channels skipped entirely)
- Smaller kernels with better cache behavior (~460 lines vs 2783)
- Potential for different channels on different devices (CPU vs GPU routing)

### G2: Reduced Memory Traffic

Eliminate unnecessary intermediate buffers and copy chains:
- In-place operations where the source and destination are the same logical buffer
- Fused read-process-write paths (no intermediate CachedStateCell staging)
- Direct producer-consumer connections without double-buffering when temporal
  ordering guarantees make it safe

### G3: Aggregation as a Separate Phase

After per-channel processing, a lightweight aggregation kernel mixes channel
outputs to the final stereo buffer:
- Simple multiply-accumulate across channels
- Naturally parallelizable (each output sample is independent)
- Could run on GPU with high efficiency

### G4: prepareBatch() Cost Reduction

Reduce the 53ms Java-side overhead through:
- Caching pattern resolution across ticks when patterns haven't changed
- Lazy evaluation (only prepare channels that have active notes)
- Potential pre-rendering of pattern data into GPU-resident buffers

### G5: Maintain Correctness

Any redesign must preserve:
- Exact same audio output (bit-for-bit comparison against current implementation)
- Support for feedback loops (delay lines that read their own output)
- Temporal ordering guarantees (effects that depend on previous state)
- Dynamic genome changes without recompilation

---

## Architecture Options

### Option A: Channel-Scoped CellLists

**Concept:** Build a separate CellList per channel, each compiling to its own kernel.
Add a final aggregation CellList that mixes channel outputs.

```
AudioScene.tick() → OperationList:
  Phase 0: Reset frame index
  Phase 1-N: prepareBatch() per active channel
  Phase N+1: Loop(4096) { channel_0.tick() }    [Kernel 0]
  Phase N+2: Loop(4096) { channel_1.tick() }    [Kernel 1]
  ...
  Phase N+K: Loop(4096) { channel_K.tick() }    [Kernel K]
  Phase N+K+1: Loop(4096) { aggregate() }       [Aggregation kernel]
  Phase final: Advance frame
```

**Pros:**
- Minimal framework changes — CellList already supports parent hierarchies
- Each channel kernel is independently optimizable
- Silent channels can skip their Loop entirely (zero cost)
- Memory per channel is isolated — better cache locality
- Aggregation kernel is trivially parallelizable

**Cons:**
- Multiple kernel dispatches instead of one (GPU dispatch overhead)
- Cross-channel effects (send/return buses) need special handling
- Memory consolidation needs per-channel scoping
- Channels cannot share intermediate computations (duplicated filter coefficients, etc.)

**Difficulty:** Medium — requires changes to AudioScene.getCells() to build per-channel
CellLists, and to CellList.tick() to support channel-scoped Loop wrapping.

### Option B: Flat Buffer Pipeline (No Cell Graph)

**Concept:** Replace the Cell/CachedStateCell/Receptor architecture entirely for the
inner loop. Instead, define the audio pipeline as a sequence of buffer-to-buffer
transformations operating on flat arrays.

```
Per channel, per tick:
  1. Read source samples:     src[i] → channel_buf[i]
  2. Apply filter (in-place): channel_buf[i] = biquad(channel_buf[i], state)
  3. Apply delay (in-place):  channel_buf[i] += delay_line[offset + i]
  4. Apply automation:        channel_buf[i] *= lfo_value(i)
  5. Mix to output:           output[i] += channel_buf[i] * volume
```

**Pros:**
- Zero copy overhead — every operation reads/writes the same buffer
- No CachedStateCell double-buffering, no receptor chains
- Maximum cache locality — single buffer stays in L1
- Simple, auditable generated code
- Easy to reason about memory layout

**Cons:**
- Major architectural departure — bypasses the entire Cell/Factor/Receptor framework
- Loses generality of the graph-based approach
- Effects ordering becomes explicit (hardcoded pipeline stages)
- Feedback loops require explicit delay-line management
- Harder to compose dynamically (adding/removing effects)

**Difficulty:** High — essentially building a new audio engine that replaces the cell
graph for the inner loop, while keeping the cell graph for configuration/setup.

### Option C: Hybrid — Cell Graph for Structure, Direct Buffers for Execution

**Concept:** Keep the Cell graph for defining the signal flow and managing lifecycle,
but at compilation time, analyze the graph to produce an optimized flat-buffer execution
plan that eliminates intermediate copies.

```
Build phase (Java, once):
  CellList → analyze graph → identify copy chains → produce execution plan
  Plan: [(read, buf_A, offset_0), (filter_inplace, buf_A),
         (delay_read, buf_B, buf_A), (mix, buf_out, buf_A, volume)]

Compile phase (once):
  execution_plan → per-channel C function → compile → cache

Execute phase (per tick):
  prepareBatch() → dispatch per-channel kernels → aggregate
```

**Pros:**
- Preserves Cell graph flexibility for configuration
- Eliminates copies at compilation time through graph analysis
- Can still use CellList fluent API for building pipelines
- Incremental adoption — can coexist with current approach
- Graph analysis can detect and skip trivial cells (pass-through, identity)

**Cons:**
- Requires a new "graph optimizer" pass between cell construction and compilation
- Analysis must correctly handle all cell types (SummationCell, FilteredCell, etc.)
- More complex than either A or B alone
- Risk of analysis bugs producing incorrect execution plans

**Difficulty:** High — the graph analysis is the hard part. But it's the most
future-proof approach.

### Option D: Stream-Oriented Processing (Pull Model)

**Concept:** Instead of push-based receptor chains, use a pull-based model where the
output requests samples from upstream, and each stage processes on demand. Combined
with per-channel scoping.

```
output.requestSamples(4096)
  → mixer.requestSamples(4096)
    → channel_0.requestSamples(4096)
      → delay.requestSamples(4096)
        → filter.requestSamples(4096)
          → source.getSamples(4096)
          ← filtered samples (in-place)
        ← delayed samples (in-place)
      ← automated samples
    ← channel_0 contribution
    → channel_1.requestSamples(4096)
    ...
  ← mixed output
```

**Pros:**
- Natural per-channel scoping (each pull chain is independent)
- Lazy evaluation — silent channels never requested
- In-place processing natural in pull model (transform and return same buffer)
- Familiar pattern in audio frameworks (JUCE, VST, CoreAudio all use pull)

**Cons:**
- Fundamental change to the data flow model (push → pull)
- Feedback loops more complex in pull model (circular dependencies)
- May not compose well with the existing Producer/Computation expression system
- Significant refactoring of Cell, CellAdapter, Receptor interfaces

**Difficulty:** Very High — changes the fundamental data flow direction.

---

## Recommended Approach

### Phase 1: Option A — Channel-Scoped CellLists (Immediate)

Start with per-channel kernel splitting. This is the most tractable change that
delivers the biggest architectural improvement:

1. **Modify AudioScene.getCells()** to build one CellList per channel instead of
   one monolithic CellList
2. **Each channel CellList** wraps its own Loop(4096) independently
3. **Add an aggregation phase** that mixes per-channel outputs to stereo
4. **Skip silent channels** — if prepareBatch() detects no active notes, skip
   the channel's Loop entirely

**Expected impact:**
- 6 independent kernels (~460 lines each) instead of 1 monolithic (2783 lines)
- Better cache behavior (each kernel's working set fits in L1)
- Silent channel skipping could save 30ms+ per tick
- Foundation for GPU parallel dispatch

### Phase 2: Option C — Copy Chain Elimination (Follow-up)

Once channels are independent, apply graph analysis within each channel to
eliminate unnecessary CachedStateCell copies:

1. **Analyze the per-channel cell graph** for trivial copy chains
2. **Fuse producer-consumer pairs** where no other reader exists
3. **Eliminate double-buffering** where temporal ordering makes it safe
4. **Generate in-place operations** where source and destination can alias

**Expected impact:**
- Eliminate ~80% of the 535 per-iteration copies (per channel: ~89 copies → ~18)
- Reduce per-channel kernel from ~460 lines to ~200 lines
- Further improve cache locality

### Phase 3: prepareBatch() Optimization (Parallel Track)

Independently optimize the Java-side overhead:

1. **Cache pattern resolution** — don't re-resolve patterns that haven't changed
2. **Skip inactive channels** — no prepareBatch() for channels with no notes
3. **Pre-render to GPU buffers** — move sample data to GPU once, not per tick

---

## Investigation Questions

Before implementing, we need to answer:

### Q1: Channel Independence

Are all 6 channels truly independent within the inner loop? Or do any cross-channel
effects exist (send/return buses, sidechain compression, stereo linking)?

**How to verify:** Trace the cell graph connections in AudioScene.getCells(). Check
whether any cell's receptor points to a cell in a different channel.

### Q2: Kernel Dispatch Overhead

What is the GPU dispatch overhead for 6-12 small kernels vs 1 large kernel?
The earlier profiling showed collectionProduct/collectionZeros dispatch overhead
was 600-700% worse for trivial operations. But per-channel kernels are not trivial
(~460 lines each).

**How to verify:** Benchmark: compile the current kernel split into 6 independent
functions called sequentially, measure total time vs monolithic.

### Q3: Memory Consolidation Scope

Can we consolidate memory per-channel instead of globally? What happens to the
argument count when we have 6 separate kernels each with their own consolidation?

**How to verify:** Count the PackedCollection arguments per channel in the current
graph. Check if per-channel consolidation reduces total argument count.

### Q4: CellList Fluent API Compatibility

Can AudioScene.getCells() return multiple CellLists (one per channel) without
breaking the fluent API chain? Or does the chain assume a single CellList?

**How to verify:** Read AudioScene.getCells() and trace how its return value
is consumed by the caller.

### Q5: Feedback Loops Across Channels

Do delay lines or other feedback effects read from buffers written by other
channels? If so, per-channel splitting requires explicit synchronization points.

**How to verify:** Trace delay line buffer references in the cell graph.

### Q6: prepareBatch() Redundancy

How much work does prepareBatch() repeat across ticks? If patterns don't change
between ticks, is the pattern resolution cached or recomputed?

**How to verify:** Read PatternSystemManager.sum() and trace whether it caches
resolved pattern elements.

---

## Success Criteria

| Metric | Current | Target | Stretch |
|--------|---------|--------|---------|
| M1 Ultra avg buffer time | 192ms | <93ms (real-time) | <70ms |
| M4 avg buffer time | 148ms | <93ms (real-time) | <70ms |
| Kernel time (Loop phase) | 139ms | <60ms | <40ms |
| prepareBatch time | 53ms | <25ms | <10ms |
| Silent channel cost | Same as active | <1ms | 0ms |
| Kernel count | 1 monolithic | 6+ per-channel | — |
| Inner loop body (lines) | 2783 | <500 per channel | <300 |

---

## Key Files

| File | Role | Expected Changes |
|------|------|-----------------|
| `compose/.../AudioScene.java` | Pipeline orchestrator | Major — per-channel CellList construction |
| `audio/.../CellList.java` | Cell container + tick ordering | Medium — channel grouping support |
| `graph/.../CachedStateCell.java` | Double-buffering state | Medium — copy elimination analysis |
| `graph/.../SummationCell.java` | Multi-source accumulation | Minor — aggregation kernel source |
| `hardware/.../OperationList.java` | Compilation orchestration | Minor — per-channel compilation |
| `hardware/.../Loop.java` | Inner loop generation | Minor — per-channel loop instances |
| `compose/.../EfxManager.java` | Effects/filter management | Medium — per-channel filter consolidation |
| `compose/.../AutomationManager.java` | LFO/envelope generation | Minor — per-channel automation |
| `music/.../PatternAudioBuffer.java` | Sample buffer management | Medium — inactive channel detection |
| `compose/.../MixdownManager.java` | Channel mixing | Medium — aggregation kernel |
