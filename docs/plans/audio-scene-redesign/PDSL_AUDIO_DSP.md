# PDSL for Audio DSP Processing

> Part of the audio-scene-redesign consolidation — see [STATE_OF_PLAY.md](STATE_OF_PLAY.md)
> for the big picture. This is the design reference for migrating all signal processing to PDSL.

PDSL declaratively describes the multi-channel DSP pipeline that backs `MixdownManager`,
`EfxManager`, and `AutomationManager`. A `layer` body composes FIR primitives
(`fir`, `scale`, `lowpass`, `highpass`), stateful primitives (`biquad`, `delay`, `lfo`),
and multi-channel constructs (`channels: N`, `for each channel { }`, `repeat(N)`,
`route(matrix)`, `sum_channels()`) into a `Block` that the framework compiles into a
`Model`. Existing PDSL files (`efx_channel.pdsl`, `mixdown_channel.pdsl`,
`delay_feedback_bank.pdsl`, `mixdown_manager.pdsl`) cover the structural rendition of
the audio path, and the closed-loop reverb bus (`delay_network`) and time-varying
parameters — gene-driven, clock-driven, and automation-driven, via `producer([shape])`
arguments — have since landed. Section 11 is the design reference for the producer-valued
parameter form; Section 14 explains why migrating this DSP path is the current priority,
and Section 15 covers the production cutover.

**Related:** [STATE_OF_PLAY.md](STATE_OF_PLAY.md), [KNOWN_ISSUES.md](KNOWN_ISSUES.md)

---

## 1. The Goal and Why

The AudioScene pipeline — `MixdownManager`, `EfxManager`, `AutomationManager` — is wired imperatively in Java. The framework cannot analyze the graph, partition it across channels, or optimize it automatically because the graph is hidden inside Java method calls.

If the same pipeline were defined **declaratively in PDSL**, the framework would have a structured description of the graph it could analyze, partition, and recompile. Per-channel kernel splitting, copy elimination, and silent channel skipping — the three main goals of the AudioScene redesign — all become tractable problems in a declarative graph model.

The chosen approach extends the existing `layer` keyword with multi-channel DSP constructs: `channels: N` header parameter, `for each channel { }` body construct, `repeat(N)`, `route(matrix)`, and `sum_channels()`. A `layer` compiles to a `Block` — a static computation graph — and a sequence of PDSL layers compiles to a `Model` via `SequentialBlock`. This approach reuses the full existing PDSL infrastructure without a new compilation target.

Cross-channel routing (`route`), collapse (`sum_channels`), and per-channel iteration (`for each channel`) cover the core multi-channel vocabulary used by `MixdownManager`. The `delay_feedback_bank.pdsl` layer (see Appendix A) demonstrates all four constructs working together end-to-end.

---

## 2. The Current DSP Pipeline

### Signal Flow

The per-channel audio path through `AudioScene.getCells()` is:

```
PatternAudioBuffer (source)
  └─ EfxManager.apply()
       ├─ [optional] MultiOrderFilter (FIR, selectable HP/LP)
       ├─ AdjustableDelayCell (delay time from gene × beat duration)
       ├─ feedback modulation (automation envelope × wet level)
       └─ sum(wet path, dry path)
  └─ MixdownManager.createCells()
       ├─ main path: HighPassFilter → volume scaling
       ├─ wet/efx path: FixedFilterChromosome → delay network → transmission routing
       ├─ reverb path: DelayNetwork (selected channels, no feedback)
       └─ LowPassFilter → output receptors (master, measures, stems)
```

`AutomationManager` does not insert cells into the pipeline — it produces
`Producer<PackedCollection>` values consumed as time-varying parameters
by `EfxManager` and `MixdownManager`.

### How Parameters Flow

Every parameter in the DSP pipeline is genome-driven:

- **EfxManager:** 3 chromosomes — delay times (choice gene), delay levels
  (wet/feedback/filter-decision/cutoff), delay automation (6-locus envelopes)
- **MixdownManager:** 8+ chromosomes — volume, high-pass filter, low-pass filter,
  delay times, delay dynamics, transmission matrix, wet send levels, reverb levels
- **AutomationManager:** 1 chromosome with 6-locus envelopes producing
  `sin()`/`pow()` modulation curves

At construction time these genes are wired into Producer expressions. At runtime the
genes resolve to float values that drive the kernel.

### Key Characteristics

1. **State is per-sample:** Delay line buffers, filter state (biquad coefficients, IIR
   history) — must be read and written on every sample.
2. **Feedback exists within a channel:** Delay lines feed back into themselves via
   `CachedStateCell` double-buffering: each cell writes to its `outValue`, pushed to
   downstream cells on the next tick.
3. **Cross-channel routing exists:** `MixdownManager` has a `transmission` chromosome
   routing delay sends between channels via `mself()`. True data dependency between channels.
4. **Parameters are mutable without recompilation:** `assignGenome()` updates gene values
   in the existing `PackedCollection` without rebuilding the cell graph.

---

## 3. PDSL Today: Capabilities and Gaps

PDSL (`PdslLoader` + `PdslInterpreter`) is a declarative DSL for building neural network
computation graphs. It compiles to `Block` and `Model` objects backed by the same
Producer/CollectionProducer framework that underlies the cell graph.

### What PDSL Can Express Today

**Layer composition (sequential):**
```pdsl
layer my_layer(weights: weight, biases: weight) {
    dense(weights, biases)
    relu()
}
```

**Residual connections:**
```pdsl
accum {
    rmsnorm(norm_weights, epsilon)
    dense(w1)
    silu()
}
```

**Element-wise multiply (gating):**
```pdsl
product(
    { dense(w1); silu() },
    { dense(w3) }
)
```

**Parallel branches with concatenation:**
```pdsl
concat_blocks(
    { slice(0, half); rope_rotation(shape, freq_cis, position) },
    { slice(half, half) }
)
```

**Zero-copy weight slicing:**
```pdsl
data gru_weights {
    weight_ih: weight
    hidden_size: int
    w_r = range(weight_ih, [hidden_size, input_size], 0)
    w_z = range(weight_ih, [hidden_size, input_size], hidden_size * input_size)
}
```

**Current primitive set:** `dense`, `embedding`, `rmsnorm`, `softmax`, `silu`,
`sigmoid`, `gelu`, `relu`, `tanh_act`, `slice`, `reshape`, `range`, `lerp`,
`rope_rotation`, `attention`, `transformer`, `feed_forward`, `fir`, `scale`,
`identity`, `lowpass`, `highpass`, `biquad`, `delay`, `lfo`, `route`,
`sum_channels`, `repeat`, `delay_network`.

The audio primitives (`fir`, `scale`, `lowpass`, `highpass`, `biquad`, `delay`,
`lfo`, `route`, `delay_network`) and the multi-channel constructs are registered by
`studio/compose/.../dsl/audio/AudioDspPrimitives.java`, which builds blocks via
`MultiChannelDspFeatures` (same package). The `engine/ml` `PdslInterpreter` registers
the core/ML primitives plus the domain-agnostic `repeat`, `sum_channels`, `identity`,
and `scale`. Each `AudioDspPrimitives` dispatch site binds its arguments through
`PdslPrimitiveContext.toProducer(value, shape, name)` — the single point that accepts a
Number, a `PackedCollection`, or a `Producer<PackedCollection>` at the declared shape.

### What PDSL Cannot Express Today

1. **Temporal self-scheduling:** No construct that drives its own tick from a `CellList`
   requirement. PDSL layers are called by their host (via `CompiledModel.forward()`); they
   do not self-tick. A thin Java `Temporal` adapter wrapping `forward()` covers most cases.
2. **Conditional execution:** No `gate` or `if` construct.
3. **Variable channel count at runtime:** The `channels` parameter is fixed at build time.
   Dynamic channel count (e.g., channel activation via gene) remains Java CellList code.

---

## 4. Cell vs Block/Model — The Two Execution Models

Both `Cell` and `Block`/`Model` route data through processing stages built from the same
building block: `Cell<PackedCollection>` (`domain/graph/Cell.java`). The differences
reflect genuinely different use cases.

| Dimension | Cell / CellList | Block / Model |
|-----------|-----------------|---------------|
| **Data flow** | Push-based: upstream ticks and pushes to downstream receptors | Pull-based Producer graph: downstream pulls from upstream when evaluated |
| **State** | Owns persistent state (`cachedValue`, `outValue`); updated each tick | Stateless by default; state passed explicitly as inputs/outputs |
| **Compilation** | `CellList.tick()` compiles an `OperationList` from all temporals | `Model.compile()` compiles a `CompiledModel` from the Producer graph |
| **Execution unit** | `Temporal.tick()` — one call per sample/frame | `Runnable forward(input)` — one call per forward pass |
| **Time** | Intrinsic: `TimeCell` flows through the graph as a clock signal | Extrinsic: caller controls when forward is called |
| **Feedback** | Natural: CachedStateCell double-buffers output | Artificial: caller must pass previous output as a new input |
| **Mutability** | Parameters live in `PackedCollection` slots updated by `assignGenome()` | Parameters in `StateDictionary`; can be swapped |
| **Granularity** | Per-sample within a buffer (cell graph ticks once per sample) | Per-buffer (forward pass over entire tensor at once) |

The key difference for audio DSP: **feedback** and **per-sample state**. The cell model
was designed for these; the block model was designed for feedforward ML inference.

---

## 5. State Management: `data` blocks → `state` blocks

### What `data` Blocks Already Provide

The `data` block mechanism already provides exactly the right infrastructure for DSP state:

1. Accept `PackedCollection` inputs by reference — caller passes an existing
   `PackedCollection` and the data block holds a reference.
2. Derive zero-copy sub-views via `range()`.
3. Make originals and sub-views available throughout the layer body.
4. The caller retains the `PackedCollection` reference between calls — it persists
   across buffer boundaries automatically.

A **delay line buffer** is a `PackedCollection` of `max_delay_samples` floats that persists
between calls. **Filter history** (biquad: x1, x2, y1, y2) is a 4-element `PackedCollection`.
**LFO phase** is a 1-element `PackedCollection` that gets incremented each call.

The ONLY difference between ML weights and DSP state: weights are **read-only** during
execution; state is **read-write**. The write step uses `CollectionProducer` operations —
following the same pattern as the existing non-stateful primitives in `PdslInterpreter.java`.

### What Was Actually Added

1. **`state` keyword** — syntactically identical to `data` but produces `StateDef` instead
   of `DataDef`. Signals write-intent. `PdslInterpreter` populates the environment
   identically to `data`.
2. **State-aware primitives** — `dispatchBiquad`, `dispatchDelay`, `dispatchLfo` in
   `studio/compose/.../dsl/audio/AudioDspPrimitives.java`. Follow the same
   `CollectionProducer` pattern as `dispatchFir`, `dispatchLowpass`, `dispatchHighpass`.
   No `setMem()` or `toDouble()` — all computation expressed as Producer operations.

### Concrete Mappings

**Biquad Filter** — state: `x[n-1]`, `x[n-2]`, `y[n-1]`, `y[n-2]` (4 floats)

```pdsl
state biquad_state {
    history: weight    // [x1, x2, y1, y2] as a 4-element PackedCollection
}

layer biquad_filter(b0: scalar, b1: scalar, b2: scalar,
                    a1: scalar, a2: scalar, s: biquad_state) {
    biquad(b0, b1, b2, a1, a2, s.history)
}
```

The `biquad` primitive processes samples serially inside its Java implementation
(loop-carried dependency cannot be parallelized naively). This is a vectorization concern,
not a state-storage concern. The PDSL layer graph remains clean.

**Delay Line** — state: circular buffer of `max_samples` floats + write pointer

```pdsl
state delay_state {
    buffer: weight    // max_delay_samples-element PackedCollection, zero-initialized
    head: weight      // 1-element PackedCollection: current write position
}

layer delay_line(delay_samples: scalar, s: delay_state) {
    delay(delay_samples, s.buffer, s.head)
}
```

The maximum delay length is fixed at block-build time. Variable delay within that maximum
is handled by the read offset calculation: `(head - delay_samples + max) % max`.

**LFO Phase Accumulator** — state: current phase in `[0, 2π)` (1 float)

```pdsl
state lfo_state {
    phase: weight    // 1-element PackedCollection, initialized to 0.0
}

layer lfo_sin(freq_hz: scalar, sample_rate: scalar, s: lfo_state) {
    lfo(freq_hz, sample_rate, s.phase)
}
```

### `data` vs `state` — Summary

| | `data` block | `state` block |
|--|-------------|---------------|
| **Storage** | `PackedCollection` reference, caller-owned | Same |
| **Derivations** | `range()` sub-views, computed at build time | Same |
| **Access during execution** | Read-only | Read-write |
| **Lifecycle** | Persists as long as caller holds the reference | Same |
| **Interpreter change** | None needed | Recognize `state` keyword; populate env identically |
| **Runtime change** | None | None — state `PackedCollection` updated via Producer operations inside state-aware primitives |

---

## 6. Multi-Channel Composition

### 6A: What CellList Multi-Channel Composition Actually Does

A CellList is a *list* of cells — at every layer there are typically N independent channels
running in parallel, and multi-channel operations define structured relationships between them.
This is the core of what `MixdownManager`, `EfxManager`, and the full audio scene graph do.

The multi-channel vocabulary (method → location → production call sites):

**`sum()` — Collapse N channels to 1**
- `CellFeatures.sum(CellList)` at `engine/audio/.../CellFeatures.java:582`
- `MixdownManager.createCells():610` — collapses N pattern channels into one mixed signal
- `MixdownManager.createEfx():667,674,678`; `EfxManager.apply():246,248`

**`branch(IntFunction<Cell>... dest)` — Fan out N to N×M**
- `CellFeatures.branch(CellList, IntFunction<Cell>...)` at line 229
- `MixdownManager.createCells():572-578` — splits N wet channels into EFX + reverb branches
- `MixdownManager.createCells():592-601,605-606`

**`and(CellList)` / `cells(CellList...)` — Merge parallel paths**
- `CellList.and(CellList)` at line 406; `CellFeatures.cells(CellList...)` at line 183
- `MixdownManager.createEfx():678`; `EfxManager.apply():248`

**`m(adapter, destinations, transmission)` — Gene-controlled cross-channel routing**
- `CellFeatures.m(CellList, IntFunction<Cell>, List<Cell>, IntFunction<Gene>)` at line 927
- `MixdownManager.createEfx():664` — routes N EFX channels to M delay lines via gene

**`mself(adapter, transmission, passthrough)` — Cross-channel feedback grid**
- `CellFeatures.mself(...)` at line 901; delegates to `m(cells, adapter, cells, transmission, passthrough)`
- `MixdownManager.createEfx():665-666` — [M×M] cross-channel delay feedback matrix
- `EfxManager.apply():245` — single-channel self-feedback

**`gr(duration, segments, choices)` / `grid(...)` — Temporal channel selection**
- `CellFeatures.gr(...)` at line 1061; `grid(...)` at lines 1074, 1090
- `CellList.poly(IntFunction<CollectionProducer>)` at line 428

**`CellList.collector()` / `all(count, cells)` — Assembly of N-cell lists**
- `CellList.collector()` at line 1032; `CellFeatures.all(int, IntFunction<CellList>)` at line 194
- `MixdownManager.createEfx():654-657` — builds the M-element delay CellList

### 6B: Why Block/Model Cannot Express These Cleanly

In the CellList model, N channels = N independent `Cell<PackedCollection>` instances. Each
has its own state, delay buffers, receptor wiring. `sum()`, `branch()`, `m()`, `mself()`
define the *topology* of the graph.

In the Block/Model model, N channels = a batch dimension in a single `[N, signal_size]`
tensor. Cross-channel dependencies require explicit tensor contractions.

| Multi-channel operation | CellList | Block/Model |
|------------------------|----------|-------------|
| `sum()` — collapse N → 1 | `SummationCell` wired to N receptors | No PDSL primitive; reduce-sum over batch dimension |
| `branch()` — fan out N → N×M | Wire each of N cells to M functions | `tile([N, signal_size], M)` — not expressible in current PDSL |
| `m(adapter, dests, gene)` — gene routing | Per-cell gene lookup at N×M granularity | Matrix multiply on channel dimension — feasible but loses per-cell gene structure |
| `mself(adapter, gene)` — feedback grid | Direct wiring back into same cell array | Self-referential tensor op — no static-graph expression |
| Per-channel state | Each cell holds its own `CachedStateCell` state | Must index into shared [N, state_size] tensor |
| Channel count flexibility | N is a Java integer; CellList grows dynamically | N must be fixed at PDSL compile time |

The `mself` feedback grid is the critical case. `MixdownManager.createEfx()` expresses it in
3 lines of Java:

```java
efx = efx
    .m(fi(), delays, tg)                               // [N] → [M]: gene-routed
    .mself(fi(), transmission, fc(wetOut.valueAt(0))) // [M] × [M]: feedback matrix
    .sum();                                             // [M] → 1
```

The Block equivalent is not expressible in static PDSL: the feedback creates a loop-carried
dependency across tick boundaries at the cell level. No `accum_blocks` or `concat_blocks`
nesting can represent this.

### 6C: Implemented PDSL Syntax for Multi-Channel Composition

Four constructs cover the core multi-channel vocabulary. All are implemented on `layer`
definitions and compile to `DefaultBlock` instances via `MultiChannelDspFeatures`.

**Construct 1: `channels: int` — Channel multiplicity parameter**

Declares that a layer operates on N independent channels. The `channels` parameter
flows into the environment and is used by all multi-channel constructs in the body.

```pdsl
layer delay_feedback_bank(channels: int, signal_size: int, ...) -> [1, signal_size] {
    // channels and signal_size are in scope throughout the body
}
```

**Construct 2: `repeat(N)` — Replicate 1 channel to N**

Takes a `[1, signal_size]` input and produces `[N, signal_size]` by concatenating N copies.
This is a domain-agnostic axis-0 replication kernel and is supplied by the PDSL interpreter
core (it is not registered by `AudioDspPrimitives`); it is the Block-level wrapper for the
producer-level `CollectionProducer.repeat(0, n)`.

```pdsl
repeat(channels)    // [1, signal_size] → [channels, signal_size]
```

**Construct 3: `for each channel { }` — Per-channel application**

Applies the body to each channel independently. Inside the body, `channel` is bound to
the current channel index (0 to N-1). State subscript `buffers[channel]` slices the
shared state collection into per-channel views using `PackedCollection.range()`.

```pdsl
for each channel {
    delay(delay_samples, buffers[channel], heads[channel])
}
```

Compiles to: one `SequentialBlock` per channel (built by interpreting the body N times with
`channel` bound to i). All channel blocks are composed into a `perChannelBlock` via
`MultiChannelDspFeatures.perChannelBlock(channelBlocks, channels, signalSize)`, which:
1. Slices `[1, signalSize]` from the `[channels, signalSize]` input per channel.
2. Pushes each slice through the corresponding channel block via `Cell.setReceptor` + `push`.
3. Concatenates all channel outputs back to `[channels, signalSize]`.

**Construct 4: `route(matrix)` — Cross-channel routing**

Applies a `[channels, channels]` routing matrix. For each output channel `i`:
`out[i] = sum_j(matrix[i,j] * in[j])`. Matrix values are extracted at build time.
Compiles to `MultiChannelDspFeatures.routeBlock(matrix, channels, signalSize)`.

```pdsl
route(transmission)    // [channels, signal_size] → [channels, signal_size]
```

**Construct 5: `sum_channels()` — Collapse N channels to 1**

Element-wise addition over all channel slices. Like `repeat`, `sum_channels()` is a
domain-agnostic axis-0 reduction supplied by the PDSL interpreter core, not by
`AudioDspPrimitives`.

```pdsl
sum_channels()    // [channels, signal_size] → [1, signal_size]
```

**`sum_channels()` vs `accum_blocks` — orthogonal reductions, not duplicates.**
Both operations produce element-wise sums, but they sum over different things and
are not substitutable:

- `accum_blocks(a, b, c, ...)` (Section 12.4, implemented as
  `LayerRoutingFeatures#accumBlocks`) is summation **across multiple sibling
  `Block` sources**. Every sub-block receives the same upstream input
  independently, and the layer output is the element-wise sum of the per-block
  outputs. The "axis" being reduced is the implicit branch axis introduced by
  enumerating the sub-blocks; no axis of any individual sub-block's output
  tensor is collapsed.
- `sum_channels()` (this construct, implemented as
  `PdslInterpreter#callSumChannels`) is the **within-tensor** channel-axis
  reduction. It operates on the output of a *single* upstream block whose
  shape is already `[C, S]` and reduces along axis 0 to yield `[1, S]`. The
  reduction axis is internal to one tensor.

Both operations are legitimate; they cover different shapes of multi-source
parallelism. A heterogeneous-branching pipeline that emitted a multi-channel
tensor per branch would need both — `accum_blocks` to combine the branches
followed by `sum_channels()` to collapse the channel axis of each branch's
output — but in practice the canonical PDSL pattern keeps the two roles
separate: `repeat(N) ... for each channel ... sum_channels()` for within-tensor
fan-out/collapse, and `accum_blocks(...)` for heterogeneous branching at the
block level.

**Complete example — `delay_feedback_bank`:**

```pdsl
layer delay_feedback_bank(channels: int, signal_size: int, delay_samples: int,
                          transmission: weight) -> [1, signal_size] {
    repeat(channels)
    for each channel {
        delay(delay_samples, buffers[channel], heads[channel])
    }
    route(transmission)
    sum_channels()
}
```

Signal flow: `[1, S]` → repeat → `[N, S]` → per-channel delay → `[N, S]` → route → `[N, S]` → sum → `[1, S]`.

### 6D: Why This Matters for ML Too

The multi-channel constructs are not audio-specific — they are gaps in the
expressiveness of the PDSL language itself, relevant to any domain operating on
sets of parallel signals. Multi-head attention decomposition
(`repeat(num_heads)` + `for each channel`), mixture-of-experts
(`repeat(num_experts)` + `for each channel` + `route(gate_scores)` +
`sum_channels()` — MoE routing is the same mechanism as `mself(transmission)` in
the audio delay bank), and parallel residual streams all fall out of the same
constructs.

---

## 7. CellList Integration

### Block → CellList Adapter (Available Today)

`Block.getForward()` returns `Cell<PackedCollection>`. That return value can be added
directly to a `CellList`:

```java
Block pdslBlock = loader.buildLayer(program, "efx_delay", shape, args);
CellList cells = new CellList();
cells.add(pdslBlock.getForward());  // Block's forward cell IS a Cell
```

**Stateful blocks in CellList context:** The state write-back in `dispatchBiquad`,
`dispatchDelay`, and `dispatchLfo` happens inside `push()` via `FEATURES.into(...)`. State is updated on every
tick — exactly like `CachedStateCell.tick()`. A PDSL biquad block added to a `CellList`
maintains correct per-call state without any adapter code.

**What is lost:** `SequentialBlock` tracks input/output shapes via `getInputShape()`/
`getOutputShape()`. `CellList` is shape-agnostic. This is a debugging concern, not a
correctness concern.

### CellList → Block Adapter (Requires Explicit Output Capture)

`CellList.tick()` returns a `Supplier<Runnable>` — not a `Cell` that processes a single
input and forwards to a receptor. To wrap a `CellList` as a `Block` requires:

1. A way to inject input into the CellList's root cells.
2. A way to capture the output from the CellList's leaf cells.

The fundamental challenge: `CellList` manages its own internal tick ordering via parent/child
hierarchy. Wrapping as a block loses this hierarchical tick ordering for complex graphs
with feedback.

### The State Block as Bridge

The PDSL `state` block and `into()` mechanism make PDSL Blocks structurally equivalent to
`CachedStateCell` from the cell graph's perspective:

| Mechanism | `CachedStateCell` | PDSL stateful Block |
|-----------|-------------------|---------------------|
| **State storage** | `cachedValue` PackedCollection (heap) | `state` block PackedCollection (caller-owned) |
| **Read old state** | `getResultant()` returns `p(outValue)` | `FEATURES.cp(history)` reads current PackedCollection |
| **Write new state** | `tick()` copies cachedValue → outValue | `FEATURES.into("name", newState, cp(history), false)` |
| **Update trigger** | Explicit `tick()` call in OperationList | Happens inside `push()`, same call as the forward pass |
| **Lifecycle** | JVM GC (cell holds reference) | Caller holds PackedCollection reference |

### Implications for AudioScene

`AudioScene.getCells()` builds a `CellList` pipeline. To incorporate PDSL-defined DSP:

1. Replace a Java-defined cell (e.g., `BiquadFilterCell`) with the PDSL equivalent:
   `loader.buildLayer(program, "efx_delay", shape, args).getForward()`
2. Add this cell to the CellList at the same position.
3. The PDSL block's `push()` performs the DSP computation and updates state on each tick.

No changes to `AudioScene`, `EfxManager`, or `MixdownManager` are required for the
first PDSL cells to appear in the audio pipeline.

For multi-channel cases (delay feedback bank, cross-channel routing), build the layer with
the `channels` parameter and the pre-allocated per-channel state collections, then add the
compiled model's forward cell to the CellList:

```java
Block block = loader.buildLayer(program, "delay_feedback_bank", shape, args);
CompiledModel compiled = new Model(shape).add(block).compile();
// Wrap as Temporal: state (buffers, heads) persists between forward() calls
Temporal temporal = () -> () -> () -> { compiled.forward(input); };
cells.addRequirement(temporal);
```

---

## 8. Status and Next Steps

### What Is Complete

- **FIR primitives** (`fir`, `scale`, `identity`, `lowpass`, `highpass`) — implemented in
  `PdslInterpreter.java`; `efx_channel.pdsl` demonstrates them end-to-end.
- **`state` block syntax** — `STATE` token, `StateDef` AST node, parser support, interpreter
  population.
- **Stateful primitives** (`biquad`, `delay`, `lfo`) — implemented using `CollectionProducer`
  operations, no `setMem()`/`toDouble()`.
- **`mixdown_channel.pdsl`** — expresses `MixdownManager`'s main path (HP → scale → LP)
  and full per-channel path (with wet/delay) as PDSL layers.
- **Multi-channel constructs** — `repeat(N)`, `for each channel { }`, `route(matrix)`,
  `sum_channels()` implemented in `PdslInterpreter`, `PdslParser`, `PdslNode`, and
  `MultiChannelDspFeatures`. Subscript syntax `expr[index]` for per-channel state slicing.
- **`delay_feedback_bank.pdsl`** — exercises all four multi-channel constructs end-to-end:
  fan out → per-channel delay → cross-channel route → sum to mono.
- **`mixdown_manager.pdsl`** — top-level structural rendition of `MixdownManager.createCells()`
  + `createEfx()` with three layers (`mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_master`).
  Time-varying parameters are declared with their producer shape directly:
  `hp_cutoff`/`volume`/`lp_cutoff`/`delay_samples` are `producer([1])`,
  `wet_filter_coeffs` is `producer([channels, fir_taps])`, and `transmission`
  is `producer([channels, channels])`.
- **PDSL/CellList integration** — `PdslLayerCellListIntegrationTest` (2 tests) validates
  state persistence across `Temporal.tick` and Block→Temporal adapter flow.
- **Producer-valued arguments at all relevant shapes** — `producer([shape])` is
  implemented for every audio primitive whose parameters the live system drives
  with a `Producer<PackedCollection>`: `scale`, `highpass`, `lowpass`, `biquad`,
  `delay`, `lfo` (shape `[1]`); `fir` (shape `[fir_taps]`); and `route` (shape
  `[N, M]` for square or rectangular routing). The argument-binding plumbing is
  `PdslPrimitiveContext.toProducer(value, shape, name)`, called once per argument
  at each `AudioDspPrimitives` dispatch site (e.g.
  `AudioDspPrimitives.java:117,127,139,151-156`); it dispatches over
  Number / PackedCollection / Producer at the declared shape. Subscript indexing
  (`coeffs[channel]`) accepts producers as
  well as PackedCollections, so per-channel slicing of a
  `producer([channels, fir_taps])` row inside `for each channel` falls out of
  the same plumbing. Literal-bound calls continue to compile to the same
  constant-folded kernel as before.
- **Rectangular routing** — `route(matrix)` accepts non-square
  `[input_channels, output_channels]` matrices. Downstream
  `for each channel` / `sum_channels` / `tap` operate on the new output channel
  count rather than re-using the input channel count. This unlocks the N-efx →
  M-delays fan in `MixdownManager.createEfx()` (Section 10 rows 19, 20).
- **Heterogeneous branching** — expressible as `accum_blocks(a, b, c, …)`
  applied to a `[1, signal_size]` mono signal. `fan_out_with` was retired as
  a redundant spelling of `accum_blocks(...) + sum_channels()` (Section 12.4);
  no new keyword was needed.
- **Genome → args-map glue** — `MixdownManagerPdslAdapter.buildArgsMap(MixdownManager, Config)`
  in `studio/compose/src/main/java/org/almostrealism/studio/arrange/`. Each PDSL
  parameter is sourced from `MixdownManager`'s constructed chromosomes
  parameter-by-parameter (HP cutoff via `automation.getAggregatedValue(...)`,
  volume via `toAdjustmentGene(...)`, transmission and wet-filter coefficients
  sampled from their respective genes into producer-shape slots). Per-parameter
  choices are documented inline against the corresponding
  `MixdownManager.createCells()` line.
- **Block → CellList integration helper** —
  `MixdownManagerPdslAdapter.wrapBlockAsCellList(Block)` exposes a compiled PDSL
  Block's forward `Cell` as a single-element `CellList`, the minimum bridge
  AudioScene needs from `MixdownManager.cells(...)`. CellList is being
  deprecated long-term in favour of Block; the helper is intentionally narrow.
- **Real-audio verification** — `MixdownManagerPdslVerificationTest` in
  `studio/compose/src/test/java/org/almostrealism/studio/ml/test/` renders the
  same genome through `MixdownManager.cells(...)` and through the PDSL
  `mixdown_master` layer (via the adapter), writes
  `mixdown_manager_java_path.wav` and `mixdown_manager_pdsl_path.wav` to
  `results/pdsl-audio-dsp/`, and reports per-path RMS, peak, and energy ratio.
  The structural mismatches (per-channel vs shared HP cutoff, IIR vs FIR wet
  filter, master-bus gain/clip stage absent from the PDSL path) make exact
  parity impossible; the test verifies that both paths produce non-degenerate
  audio in the same dynamic-range neighbourhood and reports the divergence
  honestly rather than loosening tolerance.
- **`delay_network` reverb bus** — registered at `AudioDspPrimitives.java:98`,
  dispatched at `:311`, and built as a real `delayNetworkBlock(...)` (an
  `OperationList`) at `MultiChannelDspFeatures.java:168-228`. The
  `mixdown_reverb_bus` layer in `mixdown_manager.pdsl` exercises it
  (`delay_network(delay_samples, feedback_matrix, ...)`), so the multi-tap
  closed-loop reverb equivalent to `org.almostrealism.audio.filter.DelayNetwork`
  now has a working PDSL rendition.
- **Test coverage** — `PdslAudioDspTest`, `MixdownChannelPdslTest`,
  `PdslAudioDemoTest`, `DelayFeedbackBankPdslTest` (audio Java tests and their
  test resources live in `studio/compose`), and `MixdownManagerPdslTest`. The
  producer-args / rectangular-route / heterogeneous-branch / variable-delay-time
  capability tests are plain enabled `@Test` methods; the reverb-path test is
  `@Test @TestDepth(2) @TestProperties(knownIssue=true)`
  (`MixdownManagerPdslTest.java:859-862`) tracking the acoustic-parity gap, not a
  missing primitive. All tests pass at depth 2.
- **WAV output** — `results/pdsl-audio-dsp/` contains dry multitone, lowpass-filtered,
  delay-echo, wet/dry mix, and delay_feedback_bank WAV files.

### What Remains as Gaps in the Structural Rendition

- **Reverb acoustic parity:** the `delay_network` PDSL bus exists and renders the
  multi-tap closed-loop structure, but its sample-level output is not yet
  bit-matched to `org.almostrealism.audio.filter.DelayNetwork`. The
  `MixdownManagerPdslVerificationTest` keeps the reverb path comparison framed as
  an energy/dynamic-range check rather than sample-accurate equivalence
  (`MixdownManagerPdslTest.testMixdownManagerReverbPath` is annotated
  `knownIssue=true` for the same reason).
- **IIR vs FIR wet filter:** `MixdownManager` uses `AudioPassFilter` (IIR) via
  `CellFeatures.hp()`/`lp()`; the PDSL path renders the wet filter as static
  FIR coefficients. The structural mismatch is documented in the verification
  test and adapter — energy-level comparisons, not sample-accurate equivalence.
- **Per-channel automation parity:** the PDSL `mixdown_master` applies one
  shared producer to every channel inside `for each channel`; the live Java
  path drives a per-channel-distinct envelope from each channel's gene. The
  adapter samples channel 0's gene as the structural approximation. Closing
  this gap requires either a `producer([channels])` form on the relevant
  primitives or a per-channel layer body — both are language work for a
  separate task.

### What's Next

The structural rendition, producer-valued arguments, the `delay_network` reverb
bus, the genome→args adapter, the Block→CellList helper, and the real-audio
verification test have all landed. The remaining work is the production cutover
and the surfaces not yet expressed in PDSL:

1. **Production cutover.** Swap the compiled PDSL model into the live
   `MixdownManager.createCells()` path behind a feature flag, gated on acoustic
   parity and real-time performance on hybrid routing. See Section 15.
2. **`EfxManager` migration.** Same shape of work as the `MixdownManager`
   migration, against `EfxManager.apply()`. `efx_channel.pdsl` renders only the
   feedforward wet chain so far — the automation-driven wet/dry path is not yet
   expressed. See Section 16.
3. **Reverb acoustic parity.** Tighten the `delay_network` bus output toward
   sample-level equivalence with `DelayNetwork` so the reverb path can drop its
   `knownIssue` framing.
4. **Variable channel count.** Today `channels` is fixed at build time.
   Supporting gene-driven channel activation requires runtime branching.
5. **Full retirement of CellList in favour of Block.** Long-arc consolidation:
   the `wrapBlockAsCellList` bridge is the minimum stop-gap while CellList
   remains AudioScene's primary topology container.

---

## 9. Risks

### Risk 1: IIR Filter Feedback (Medium Impact, Well-Understood)

IIR biquad filters have loop-carried dependencies that cannot be naively parallelized
across samples. The `biquad` primitive handles this by processing samples serially inside
its Java implementation. For GPU execution, a parallel IIR algorithm (transposed direct-form
II, look-ahead) can be substituted — this is an optimization choice, not a PDSL API change.

The current `EfxManager` uses 40-tap FIR filters (already expressible via `fir()`). IIR
filters appear in `MixdownManager`'s main path — the PDSL approximation uses FIR
(`highpass()`/`lowpass()`); energy-level assertions validate correctness.

### Risk 2: Variable Delay Line Length (Low Impact, Resolved)

Delay line length is genome-driven. Resolution: at block-build time, determine
`max_delay_samples` from gene range bounds, allocate the state block's `buffer`
at that fixed maximum, and use a read-pointer offset (stored in `head`) for
variable delay. This is how hardware delay lines work.

### Risk 3: Cross-Channel Transmission (Medium Impact, Resolved)

`MixdownManager`'s `transmission` chromosome routes audio between channels.
Resolution: `route(transmission)` (Section 6C Construct 4) is implemented and the
genome→args glue (`MixdownManagerPdslAdapter.buildArgsMap`) wires the values in.

### Risk 4: Real-Time Audio Constraints (High Impact — the governing constraint)

Real-time audio requires deterministic latency *and* hybrid JNI+Metal routing.
Compile the PDSL model in a background thread during scene init and swap it in
double-buffered once compilation completes. Critically, the compiled loop must
run under default hybrid routing — Metal alone cannot compile the mixdown loop
(31-buffer-argument limit). See Section 13 and [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

---

## 10. MixdownManager Migration: Structural Plan

This section catalogs every statement in
`studio/compose/src/main/java/org/almostrealism/studio/arrange/MixdownManager.java`
that contributes to the signal flow and maps it to one of three statuses:

- **PDSL-ready** — expressible in the current vocabulary
  (`layer` + existing primitives + multi-channel constructs), covered by a
  PDSL file and a passing test. Every capability the original `@Disabled` tests
  clustered around — producer-valued arguments, rectangular routing,
  heterogeneous branching, the `delay_network` reverb bus — has since landed, so
  rows that this section once marked "PDSL-blocked-by-X" are now PDSL-ready and
  their tests are enabled. The "blocked-by-X" annotation is retained in the
  *Covered by* column only as the historical record of which capability each row
  waited on.
- **Not in scope** — Java orchestration (genome/automation wiring, lifecycle,
  output receptor routing, feature flags, dynamic resize). Stays in Java
  and does not need a PDSL rendition.

The PDSL rendition this plan produces is *structural*: it captures the shape of
the pipeline (multi-channel fan-in, per-channel filtering, cross-channel routing,
master bus summation, master low-pass). The time-varying parameters that once
forced most rows into "PDSL-blocked" status — `MixdownManager` wires gene-,
clock-, and automation-derived `Producer<PackedCollection>` values into the audio
path — are now accepted directly via the `producer([shape])` argument form
(Section 11), so the structural rendition and the time-varying rendition use the
same layers.

### 10.1 `createCells()` — top-level per-channel wiring

| # | Line(s) | Method / statement | Status | Covered by |
|---|---------|---------------------|--------|------------|
| 1 | 504–513 | Per-channel HP filter with **automation-driven cutoff** (`enableAutomationManager` branch) | PDSL-ready (was blocked-by-producer-args) | `testMixdownManagerAutomatedHighpass` (`@Test`) |
| 2 | 514–521 | Per-channel HP filter with **gene-driven cutoff** (no automation, but still time-varying via `TemporalFactor`) | PDSL-ready (was blocked-by-producer-args) | Same as #1 |
| 3 | 524–526 | Per-channel volume `Factor` from `toAdjustmentGene(...).valueAt(0)` | PDSL-ready (was blocked-by-producer-args) | `testMixdownManagerAutomatedVolume` (`@Test`) |
| 4 | — | Per-channel HP filter with **fixed cutoff** (structural rendition of row 1/2) | PDSL-ready | `mixdown_main_bus` layer in `mixdown_manager.pdsl` |
| 5 | — | Per-channel volume with **fixed scalar** (structural rendition of row 3) | PDSL-ready | `mixdown_main_bus` layer in `mixdown_manager.pdsl` |
| 6 | 528–536 | `enableSourcesOnly` fast-path (skip effects, deliver directly to master) | Not in scope | Java feature flag / receptor wiring |
| 7 | 538–539 | `cells.mixdown(mixdownDuration)` — offline-buffered pattern mixdown | Not in scope | CellList-level buffering pass |
| 8 | 541–544 | `reverbActive` flag computation | Not in scope | Java feature flag |
| 9 | 546–561 | `reverbFactor` gene / automation curve per reverb channel | PDSL-ready (was blocked-by-producer-args) | Part of `testMixdownManagerReverbPath` |
| 10 | 567–576 | Wet-sources path: `wetSources.branch(v·wetFilter, reverbFactor)` — heterogeneous fan-out | PDSL-ready (was blocked-by-heterogeneous-fanout) | `testMixdownManagerHeterogeneousBranch` (`@Test`) |
| 11 | 591–602 | Main-sources path: `cells.branch(v, v·wetFilter, reverbFactor)` — 3-way heterogeneous fan-out | PDSL-ready (was blocked-by-heterogeneous-fanout) | Same as #10 |
| 12 | 580–589 | `!enableEfx` fast-path: main-only (no efx, no reverb) | PDSL-ready (as the `mixdown_main_bus` layer) | `mixdown_main_bus` layer |
| 13 | 604–607 | Stems fan-out: `main.branch(StemReceptor, PassThroughCell)` | Not in scope | Output receptor routing |
| 14 | 610     | `main = main.sum()` — collapse N channels to 1 | PDSL-ready | `sum_channels()` inside `mixdown_main_bus` |
| 15 | 612–613 | `createEfx(main, efx, reverb, ...)` delegation | PDSL-ready (merge via `accum_blocks`) | `mixdown_master` layer |
| 16 | 615–624 | Master-path delivery without EFX (receptor to `output.getMaster`) | Not in scope | Output receptor routing |

### 10.2 `createEfx()` — effects bus, reverb, master LP

| # | Line(s) | Method / statement | Status | Covered by |
|---|---------|---------------------|--------|------------|
| 17 | 654–658 | Delay-layer array of `AdjustableDelayCell` with **time-varying delay samples** from `delay` chromosome | PDSL-ready (was blocked-by-producer-args) | `testMixdownManagerVariableDelayTime` (`@Test`) |
| 18 | — | Delay-layer array with **fixed delay samples** (structural rendition of row 17) | PDSL-ready | Per-channel `delay(...)` inside `mixdown_efx_bus` |
| 19 | 660–662 | `delayGene` routing: N efx cells → M delay layers via **gene-driven rectangular matrix** | PDSL-ready (was blocked-by-rectangular-route) | `testMixdownManagerRectangularRoute` (`@Test`) |
| 20 | 664     | `efx.m(fi(), delays, tg)` — per-cell gene-routed fan-out | PDSL-ready (was blocked-by-rectangular-route) | Same as #19 |
| 21 | 665–666 | `.mself(fi(), transmission, fc(wetOut.valueAt(0)))` — cross-channel feedback matrix (square N×N case) | PDSL-ready | `route(transmission)` inside `mixdown_efx_bus` |
| 22 | 667     | `.sum()` final collapse | PDSL-ready | `sum_channels()` inside `mixdown_efx_bus` |
| 23 | 669     | `!enableTransmission`: `efx.sum()` only | PDSL-ready | `mixdown_efx_bus` with identity routing matrix |
| 24 | 672–674 | Reverb path: `reverb.sum().map(DelayNetwork)` | PDSL-ready (was blocked-by-DelayNetwork; `delay_network` landed) | `mixdown_reverb_bus` layer; `testMixdownManagerReverbPath` (`@Test`, `knownIssue=true` for acoustic parity) |
| 25 | 676–683 | Reverb/efx merge: `cells(efx, reverb).sum()` | PDSL-ready (`accum_blocks` semantics) | Part of `testMixdownManagerReverbPath` |
| 26 | 685–694 | `disableClean` alternate receptor wiring | Not in scope | Output receptor routing |
| 27 | 696–705 | `efx.get(0).setReceptor(Receptor.to(main.get(0), ...))` — cell-level wiring | Not in scope | Java CellList wiring |
| 28 | 707–714 | Per-channel master LP filter with **automation-driven cutoff** | PDSL-ready (was blocked-by-producer-args) | `testMixdownManagerAutomatedLowpass` (`@Test`) |
| 29 | — | Master LP filter with **fixed cutoff** (structural rendition of row 28) | PDSL-ready | `lowpass(...)` tail in `mixdown_master` |
| 30 | 717–720 | Riser mixing: `cells(main, riser).sum()` | Not in scope | External input-channel merge; covered in future task |
| 31 | 723–729 | Master output receptor (`master` + `measures[MAIN]`) wiring | Not in scope | Output receptor routing |
| 32 | 731     | `return cells(main, efx)` — final wrapping | Not in scope | Java return wrapping |

### 10.3 Non-signal-flow methods (Not in scope)

| Method | Line(s) | Why not in scope |
|--------|---------|------------------|
| Constructor `MixdownManager(...)` | 227–280 | Chromosome allocation + scale collection init |
| `initRanges(Configuration, int)` | 336–441 | Sets gene bounds on `ProjectedGene` |
| `setup()` | 444–446 | Returns empty `OperationList` |
| `cells(sources, output, audioChannel)` | 457–461 | Delegation to the full overload |
| `cells(sources, wetSources, riser, ...)` | 476–484 | Tracks dependencies; wraps `createCells` |
| `destroy()` | 734–739 | Lifecycle |
| `factor(Factor)` | 749–751 | Temporal-detection workaround |
| `delayGene(int, Gene)` | 759–770 | Builds a Gene (consumed by `m(...)`) |
| `Configuration` (inner class) | 776–1005 | Range bounds data holder |
| `setVolumeAdjustmentScale`, `setMainFilterUpAdjustmentScale`, `setMainFilterDownAdjustmentScale`, `setReverbAdjustmentScale` | 290–319 | Scale setters for adjustment collections |
| `setReverbChannels` / `getReverbChannels` | 321–333 | Java-side list |

The `*AdjustmentScale` setters listed above mutate 1-element `PackedCollection`
fields in place — the render-time mutable slot pattern that Section 11 treats
as a degenerate constant-in-time `producer([1])`.

### 10.4 PDSL files produced by this task

| PDSL file | Layers defined | Signal flow |
|-----------|----------------|-------------|
| `pdsl/audio/mixdown_manager.pdsl` | `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_reverb_bus`, `mixdown_master` | Multi-channel mixdown: N inputs → per-channel HP+volume → sum; N inputs → per-channel wet-filter+scale+delay → route → sum; reverb channels → `delay_network` closed-loop multi-tap reverb; accumulate (main+efx+reverb) → master LP |

The existing `mixdown_channel.pdsl` covers the single-channel case
(`mixdown_main`, `mixdown_channel`); the existing `delay_feedback_bank.pdsl`
covers the pure multi-channel delay/route/sum pattern. `mixdown_manager.pdsl`
is the first PDSL file that renders the *top-level* shape of
`MixdownManager.createCells()` in one declarative graph.

### 10.5 Summary of blocking capabilities

Section 12 (now "Implementation Notes") tracks the four capabilities the original
`@Disabled` tests clustered around. **All four have landed:**

1. **Producer-valued arguments** — *implemented* (Section 12.1). Primitives
   that previously took a `double` scalar literal now accept a
   `Producer<PackedCollection>` of declared shape via the
   `producer([shape])` argument-binding form. One capability covering filters,
   gain stages, and delay-sample counts; the variable-delay-time case
   (Section 12.2) is the same plumbing applied to `delay`'s sample-count
   argument. Covered rows 1–3, 9, 17, 28. Section 11 remains the design
   reference.
2. **Rectangular routing** — *implemented* (Section 12.3). `route(matrix)`
   now accepts `[rows, cols]` matrices where rows ≠ cols. Covered rows
   19, 20.
3. **Heterogeneous branching** — *expressible without a new keyword*
   (Section 12.4). `accum_blocks(a, b, c, …)` accepts N comma-separated
   inline brace-delimited bodies and sums their per-arm outputs, which is
   exactly what the wet/efx/reverb branch needs. Covered rows 10, 11.
4. **`delay_network(...)` primitive** — *implemented* (Section 12.5).
   Multi-tap feedback reverb equivalent to
   `org.almostrealism.audio.filter.DelayNetwork`, registered in
   `AudioDspPrimitives` and rendered by the `mixdown_reverb_bus` layer.
   Covers rows 24, 25. The only residual is sample-level acoustic parity, not
   the primitive's existence.

With these capabilities, every row in the tables above is either PDSL-ready or
explicitly Not-in-scope. No row requires architectural changes to `layer`,
`state`, or the existing multi-channel constructs.

---

## 11. Producer-Valued Parameters

The PDSL files produced so far treat every numeric parameter as a build-time
constant. The Java `MixdownManager` / `EfxManager` code does not. This section
proposes a single change: a primitive may accept any of its arguments as a
`Producer<PackedCollection>` of a declared shape, instead of a literal. The
producer is evaluated per kernel evaluation. Everything else — gene-driven
parameters, automation envelopes, render-time mutable scalars — falls out as
specific cases of supplying a particular kind of producer.

### 11.1 The Concept

Today every scalar argument to a PDSL primitive is inlined as a Java `double`
literal at build time. The numeric value is folded into the compiled kernel;
changing it requires rebuilding the layer. `weight` parameters already break
this rule for collection-shaped arguments — they are caller-supplied
`PackedCollection` references that the kernel reads through `cp(slot)`, and the
caller can mutate the collection between renders without rebuilding.

The proposal generalises that mechanism to scalar (and other) arguments. A
primitive may accept any of its arguments as a `Producer<PackedCollection>` of
an explicitly declared shape. The body of the layer does not change —
`highpass(hp_cutoff, ...)` continues to look the same. Only the *binding* of
`hp_cutoff` changes: instead of a Java `double` baked in at build time, the
interpreter reads a `Producer<PackedCollection>` of shape `[1]` from the args
map, and the compiled kernel emits a producer read instead of a literal.

A constant-folded producer is the degenerate case. Supplying `cp(constant_slot)`
over a 1-element `PackedCollection` mutated by the caller between renders
recovers the render-time-mutable-scalar pattern that today has to be inlined as
a `double` because PDSL's scalar binding does not accept a collection. Constant-
in-time producers should compile down to the same kernel a literal scalar
produces today, so fixed-parameter PDSL files do not regress.

The shape of the producer is part of the type. PDSL does not need to
distinguish "this argument is shape-1" from "this argument is shape-N" with
different markers — the shape is just the shape. Today's `scalar` becomes
`producer([1])` when promoted; a per-channel envelope is `producer([N])`; a
time-varying transmission matrix is `producer([N, N])`; morphing FIR
coefficients are `producer([K])`. The argument's *intent* (automation
envelope, gene-derived render-time constant, fixed constant, etc.) belongs in
the argument's name and a comment, not in the type.

### 11.2 Syntax

`producer([shape])` marks an argument bound to a `Producer<PackedCollection>`
of the given shape. The shape is a comma-separated list of positive integer
literals or previously-declared `int` parameters (the same expressions the
existing `-> [1, signal_size]` output-shape annotation already accepts).

Among alternatives — `producer<shape>`, `dyn(shape)`, `producer scalar`,
`dynamic(shape)` — `producer([shape])` is preferred because the bracketed
shape literal matches the existing shape annotations on layer outputs and
`data` block derivations, the keyword `producer` directly names the runtime
type the interpreter looks up in the args map, and the form scales unchanged
from `[1]` to `[N, M]` without needing a separate spelling for the shape-1
case.

**Signature 1 — single-channel mixdown with producer-valued cutoff and volume:**

```pdsl
layer mixdown_main_automated(channels: int, signal_size: int,
                             hp_cutoff: producer([1]),
                             volume:    producer([1]),
                             sample_rate: scalar, filter_order: scalar)
                          -> [1, signal_size] {
    for each channel {
        highpass(hp_cutoff, sample_rate, filter_order)
        scale(volume)
    }
    sum_channels()
}
```

`hp_cutoff` and `volume` are read by the kernel as `Producer<PackedCollection>`
values of shape `[1]`. The caller may supply a clock-driven automation
producer, a `cp(slot)` over a render-time mutable slot, or a constant-folded
literal — PDSL is indifferent.

**Signature 2 — per-channel envelope** (same primitive, different shape):

```pdsl
layer mixdown_main_per_channel(channels: int, signal_size: int,
                               hp_cutoffs: producer([channels]),
                               volumes:    producer([channels]),
                               sample_rate: scalar, filter_order: scalar)
                            -> [1, signal_size] {
    for each channel {
        highpass(hp_cutoffs[channel], sample_rate, filter_order)
        scale(volumes[channel])
    }
    sum_channels()
}
```

The implicit `channel` index inside `for each channel { }` slices the producer
per channel, the same way it slices state collections today. The producer's
shape determines whether the kernel reads a single value shared across all
channels or a per-channel value; the rest of the layer body is unchanged.

**Signature 3 — time-varying transmission matrix:**

```pdsl
layer mixdown_efx_dynamic_route(channels: int, signal_size: int,
                                transmission: producer([channels, channels]),
                                delay_samples: scalar)
                             -> [channels, signal_size] {
    for each channel {
        delay(delay_samples, buffers[channel], heads[channel])
    }
    route(transmission)
}
```

The current `mixdown_efx_bus` accepts `transmission: weight`, so the matrix is
mutable across renders but constant within one. Promoting to
`producer([channels, channels])` lets the matrix change *within* a render —
driven by `clock`, by an LFO, or by any other producer — while keeping the
body identical.

The same pattern applies across the existing primitives: `highpass`,
`lowpass`, `scale`, `delay`, `fir`, `route`, `biquad`, `lfo` accept producers
of whatever shape they expect in place of literal arguments. Whether the
producer reads `clock`, reads a gene-derived slot, or returns a constant is a
property of the producer the caller supplies, not something PDSL needs to
know.

### 11.3 Where Producers Come From in the Live System

The `Producer<PackedCollection>` values supplied to PDSL primitives come from
several distinct places in the live `MixdownManager` / `EfxManager` code.
PDSL receives a producer in each case and does not distinguish them; this list
is descriptive, not a structural axis.

- **Build-time constants.** `c(20000)` for the HP cutoff ceiling; `c(1.0)` for
  resultant baselines. Folded into the producer graph at build time.
- **Render-time mutable slots.** `cp(slot)` over a `PackedCollection` whose
  contents are updated by `assignGenome()` or by a setter
  (e.g., `setVolumeAdjustmentScale`) between renders. The producer reads the
  slot every kernel evaluation, but the value only changes between renders.
- **Gene lookups.** `Gene.valueAt(position)` or `Chromosome.valueAt(channel, k)`
  calls (often wrapped in `factor()` adapters). Resolved per render against the
  current genome.
- **Clock-driven producers.** `AutomationManager.getAggregatedValue(...)`,
  `toAdjustmentGene(clock, sampleRate, ...)`, `AdjustableDelayCell`-driven
  delay times. These read `clock.frame()` or `clock.time(sampleRate)` and so
  are recomputed every audio sample inside the compiled kernel.
- **Compositions of the above.** The
  `automation.getAggregatedValue(gene, p(scale), offset)` pattern combines a
  gene lookup, a render-time scale slot, and a clock-driven envelope into a
  single producer; the result is what the audio kernel sees.

The work to migrate any `MixdownManager` parameter to PDSL is the same
regardless of which kind it is on the Java side — the caller supplies a
producer of the declared shape; the kernel evaluates it.

### 11.4 Per-Parameter Map

The parameters that drive `MixdownManager.createCells()` / `createEfx()` and
`EfxManager.apply()`, the shape PDSL needs the producer to be, and where the
value comes from in the live system. Line citations identify the Java source.

| Parameter | Java site | Shape | Source |
|-----------|-----------|-------|--------|
| Per-channel HP cutoff (main filter up), automation on | `MixdownManager.java:519-523` | `[1]` (per channel via `for each channel`) | clock-driven envelope (`automation.getAggregatedValue(...)` × 20 kHz) |
| Per-channel HP cutoff (main filter up), automation off | `MixdownManager.java:527-530` | `[1]` | clock-driven `toAdjustmentGene(clock, sampleRate, ...)` over a gene-derived contribution |
| Per-channel volume | `MixdownManager.java:535-537,580-606` | `[1]` | clock-driven `toAdjustmentGene(...)` |
| Reverb factor, automation on | `MixdownManager.java:561-567` | `[1]` | clock-driven envelope × `reverbLevel` (gene-derived slot) |
| Reverb factor, automation off | `MixdownManager.java:568-572` | `[1]` | gene lookup (`reverb.valueAt(channelIdx, 0)`); structural |
| Wet filter coefficients (FIR) | `MixdownManager` constructor + `wetFilter` | `[filter_order]` | gene-derived slot from `FixedFilterChromosome`, mutated per render (already covered by PDSL `weight`) |
| Wet send level (`v · wetFilter` chain) | `MixdownManager.java:585-586,605-606` | `[1]` | composition of clock-driven volume × fixed wet filter |
| Per-delay-layer delay time | `MixdownManager.java:666-668` | `[1]` | clock-driven via `AdjustableDelayCell` (gene base × beat duration) |
| Per-delay-layer dynamics | `MixdownManager.java:663-668` | `[1]` | clock-driven via `toPolycyclicGene` |
| `delayGene` per-channel routing | `MixdownManager.java:670-672` | `[N, M]` | gene-derived (`wetInSimple.valueAt(channelIdx)`), per render |
| `transmission` matrix | `MixdownManager.java:258-260,677` | `[channels, channels]` | gene-derived render-time slot (already covered by PDSL `weight`) |
| `wetOut` (delay output gain) | `MixdownManager.java:262,677` | `[1]` | gene-derived render-time slot |
| Master LP cutoff (per channel) | `MixdownManager.java:720-724` | `[1]` | clock-driven `toAdjustmentGene(...)` × 20 kHz |
| `*AdjustmentScale` (volume, filter-up, filter-down, reverb) | `MixdownManager.java:301-330` | `[1]` each | render-time mutable slot mutated by setters; consumed inside a clock-driven envelope |
| EFX delay time | `EfxManager.java:226-231` | `[1]` | clock-driven via `AdjustableDelayCell` × beatDuration |
| EFX delay levels (FIR cutoff) | `EfxManager.java:312-313` | `[1]` | gene lookup (`delayLevels.valueAt(channelIdx, 3)`); consumed at FIR build time, not in the audio path |
| EFX wet feedback level | `EfxManager.java:222-223,245` | `[1]` | gene-derived render-time slot |
| EFX delay automation modulation, automation on | `EfxManager.java:236-241` | `[1]` | clock-driven envelope (`automation.getAggregatedValue(...)`) |

Most rows are shape `[1]`: scalar arguments to filters, gain stages, and
delay-sample counts. A row's shape becomes `[N]` only when a single producer
expression is used to feed all `N` channels at once; today the live code
constructs a per-channel producer inside the channel loop, which is closer to
"shape `[1]` evaluated `N` times" than to "shape `[N]` evaluated once." Both
forms are expressible.

The collection-shaped slots already handled by PDSL `weight`
(`transmission`, `wetOut`, FIR coefficients) are listed for completeness — they
do not need new plumbing, but would also be expressible as
`producer([shape])` if their callers ever wanted to drive them with a clock.

### 11.5 Migration Order

*This subsection records the original migration reasoning; producer-valued
arguments have since landed (Section 12.1), so the plan below is the historical
design rationale rather than outstanding work.*

The migration collapses to a single capability: implement producer-valued
arguments for the existing scalar-taking primitives. Once that landed, every
formerly `@Disabled` test in Section 12 that depends on time-varying scalars
unlocked at the same time, and a constant 1-element `PackedCollection` over a
render-time mutable slot became the degenerate constant-in-time case.

The plumbing is per-primitive, not one-time: every primitive that today takes
a `double` (`highpass`, `lowpass`, `scale`, `biquad`'s five coefficients,
`delay`'s sample count, `lfo`'s freq and rate) needs to learn to accept a
producer at the same call site. The compiler must also be willing to embed a
producer that depends on `clock` (or any other input) inside the per-sample
kernel; it already does this for `weight` reads in ML layers, but the audio
primitives' current implementations may inline their scalar arguments at
build time.

If anything is worth saying about ordering across the primitives, it is: do
`scale` and `highpass` / `lowpass` first. They are the most heavily used
parameters in the live `MixdownManager` audio path (volume, HP cutoff, master
LP cutoff). The constant-folding optimisation that keeps fixed-parameter PDSL
files from regressing is the single most important thing to get right;
without it, every existing PDSL test slows down.

A realistic budget across the affected primitives is 1–3 weeks. The framing
is simple, but the per-primitive plumbing is not: parser, interpreter, and
primitive implementations all need to accept a producer at the call site
where they currently accept a literal.

`int` parameters used in compile-time shape arithmetic (`signal_size`,
`channels`, `filter_order`, build-time `delay_samples` if any) are out of
scope for this change. They index into shapes and loop bounds and have to
remain build-time constants. The producer-valued change applies to the
continuous-valued (today `scalar`) parameters and to collection-shaped
arguments where a caller wants per-sample variation rather than render-time
mutability.

### 11.6 Future Work: Audio-Specific Aliases

Once `producer([shape])` is in place, audio-specific aliases may be useful for
documentation and tooling. Two candidates:

- `automation(scalar)` as a shorthand for `producer([1])` annotated with the
  intent "this argument expects a clock-driven envelope," letting tooling
  visually distinguish automation arguments from generic producer arguments.
- `envelope` or `clock_signal` as named conveniences attached to
  `producer([1])` for similar reasons.

These aliases are deferred. The core design is the generic `producer([shape])`;
aliases are surface conveniences that can be added later without changing how
the interpreter or compiler treats the underlying argument.

---

## 12. Implementation Notes

Subsections 12.1–12.5 are retrospective notes — capabilities that have landed
and whose tests are enabled. They are retained here for the section numbering
the rest of the document references.

### 12.1 Producer-valued arguments — `producer([shape])` (landed)

Producer-valued arguments are accepted by every audio primitive whose
parameters the live system drives with a `Producer<PackedCollection>`:
`scale`, `highpass`, `lowpass`, `biquad`, `delay`, `lfo` (shape `[1]`);
`fir` (shape `[fir_taps]`); and `route` (shape `[N, M]`, square or
rectangular). `PdslPrimitiveContext.toProducer(value, shape, name)` is the
single Number/PackedCollection/Producer dispatch point, called once per
argument at each `AudioDspPrimitives` dispatch site. Constant-folded literal
calls compile to the same kernel as before — no regression for
fixed-parameter PDSL files.

**Tests:**
`MixdownManagerPdslTest.testMixdownManagerAutomatedHighpass` (rows 1, 2),
`testMixdownManagerAutomatedVolume` (rows 3, 5),
`testMixdownManagerAutomatedLowpass` (row 28),
`PdslAudioDspTest.testRouteProducerTransmission` (`producer([N, N])`),
`PdslAudioDspTest.testFirProducerCoefficients` (`producer([fir_taps])`),
`PdslAudioDspTest.testProducerShapeMismatchRejected` (validation).

### 12.2 Variable delay time — `producer([1])` applied to `delay` (landed)

`delay(...)`'s `delay_samples` argument accepts `producer([1])`, equivalent
to `AdjustableDelayCell`'s `Producer<PackedCollection>` delay-time input.
The kernel's read-pointer arithmetic uses a `CollectionProducer` op so the
read offset can vary per sample; literal-bound calls compile to the same
constant arithmetic as before.

**Tests:** `MixdownManagerPdslTest.testMixdownManagerVariableDelayTime` (row 17).

### 12.3 Rectangular routing (landed)

`route(matrix)` accepts non-square `[input_channels, output_channels]`
matrices. Output shape becomes `[output_channels, signal_size]`; downstream
`for each channel`, `sum_channels`, and `tap` use the new output channel
count. No separate keyword — the matrix's shape drives the consumer.

**Tests:**
`MixdownManagerPdslTest.testMixdownManagerRectangularRoute` (rows 19, 20).

### 12.4 Heterogeneous branching — `accum_blocks` (landed; `fan_out_with` retired)

`accum_blocks(a, b, c, …)` accepts N comma-separated inline brace-delimited
bodies, applies each to the same input, and sums the per-arm outputs. The
wet/efx/reverb branch from `MixdownManager.createCells()` (rows 10–11) is
exactly this operation. An earlier iteration introduced a `fan_out_with(...)`
keyword that was strictly equivalent to `accum_blocks(...) + sum_channels()`;
it was retired together with its AST/parser/interpreter plumbing.

**Tests:**
`MixdownManagerPdslTest.testMixdownManagerHeterogeneousBranch` (rows 10, 11).

### 12.5 `delay_network(...)` primitive (landed)

A PDSL primitive equivalent to `org.almostrealism.audio.filter.DelayNetwork`:
multi-tap feedback reverb with irregular tap spacing and an internal
feedback-to-input path that does not reduce to the existing
`delay_feedback_bank` pattern. It is registered at `AudioDspPrimitives.java:98`,
dispatched at `:311`, and built as a real `delayNetworkBlock(...)` —
an `OperationList` — at `MultiChannelDspFeatures.java:168-228`. The
`mixdown_reverb_bus` layer in `mixdown_manager.pdsl` drives it
(`delay_network(delay_samples, feedback_matrix, buffer, heads)`). The only
residual gap is sample-level acoustic parity with the Java `DelayNetwork`, not
the primitive's existence.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerReverbPath` | 24, 25, 9 | `@Test @TestDepth(2) @TestProperties(knownIssue=true)` — tracks the reverb acoustic-parity gap, not a missing primitive |

---

## 13. Why Now — The a3 Bottleneck

The earlier blockers in front of the audio scene are gone. Pattern batching
(**a2**) is solved and amortized — graph batching collapses N per-note
`evaluate()` calls into one dispatch per pattern-layer per tick, so a2 is now
cheap (~6–11 ms/buffer, one-time). The **Metal dispatch ceiling** that used to
wedge the host past ~2300–2560 cumulative dispatches is fixed and merged
(3000+ sustained dispatches verified). See [STATE_OF_PLAY.md](STATE_OF_PLAY.md).

With those gone, the **per-frame DSP/mixdown loop (a3) is now the bottleneck.**
A profile on a real curated library with full fx/mixdown attributes **~99.6% of
the tick to a single operation** — a frame-recurrent `f_loop_*` reported as
`Loop ×4096 [JNI]`, the per-frame mixdown/effects loop running on the CPU. This
is the only thing between the current state and a real-time ratio of 1.

Migrating that loop to PDSL is what unlocks the optimization tooling. Once the
mixdown/effects path is a declarative graph rather than a hand-wired Java cell
loop, the same model-optimization machinery used for ML models can attack it:
parallelize across channels, and split recurrent from non-recurrent ops so the
non-recurrent work can move onto Metal while the recurrence stays where it must.
The structural rendition in this document (Sections 6–12) is precisely that
declarative graph.

**HARD CONSTRAINT — hybrid JNI+Metal routing is mandatory.** Real-time rendering
requires the framework's *default* configuration, where JNI (CPU) and Metal run
*together*. **Never force `AR_HARDWARE_DRIVER`.** Forcing `mtl` fails to compile
the mixdown loop because a Metal kernel is limited to 31 buffer arguments
(indices 0–30) and the full fx/mixdown per-frame loop exceeds it; forcing
`native` puts the parallel pattern kernels on the CPU and is far over budget.
The optimization goal is to move *parts* of the loop (the non-recurrent ops)
onto Metal under hybrid routing — not to force the whole loop onto one backend.
See [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

---

## 14. Production Cutover Plan

The structural and time-varying renditions exist and pass tests, but
`MixdownManager.createCells()`
(`studio/compose/.../arrange/MixdownManager.java`) is still the production
path. The missing piece is the swap: how the compiled PDSL model replaces the
live cell graph. `MixdownManagerPdslAdapter.buildArgsMap(MixdownManager, Config)`
already sources every PDSL parameter from the manager's constructed chromosomes.

> **Direction principle — Block-outward (do NOT wrap Block in Cell/CellList).** A
> `Block` is essentially a `Cell` (forward) plus a second cell that runs the other
> direction (backprop); audio DSP needs no backprop, so a `Block` and a `Cell` are
> approximately the same thing. The existing
> `MixdownManagerPdslAdapter.wrapBlockAsCellList(Block)` goes the **wrong way** and
> must NOT be the cutover mechanism. The universal solution is to make the
> **consumer accept a `Block`** (or `List<Block>` if the list aspect is essential) —
> a consumer that accepts `Block` can hold any `Cell` implementation. If a
> compatibility adapter is unavoidable, **`Block` stays on the outside** (adapt
> `Cell` → `Block`, never the reverse). See [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

**Parity first, then a Block-outward A/B cutover.** Do not wire anything into the
render path until the PDSL DSP is confidently acoustically correct (the §16 gates).
Only then add the cutover, behind a flag:

1. The default path stays `createCells()`.
2. With the flag enabled, `cells(...)` builds and compiles the PDSL model via the
   adapter (in a background thread during scene init, double-buffered so the audio
   path only swaps once compilation completes) and hands the resulting `Block` to a
   consumer that accepts `Block` directly — never re-wrapped as a `CellList`.

**Two gates before the flag can flip to default-on:**

- **Acoustic-parity gate.** `MixdownManagerPdslVerificationTest` renders the
  same genome through both paths and compares RMS / peak / energy. Known
  structural mismatches (IIR-vs-FIR wet filter, master gain/clip stage, reverb
  acoustic parity) keep this an energy/dynamic-range check today; tightening it
  toward sample-level equivalence is the gating work.
- **Real-time / perf gate.** The PDSL path must hit the real-time budget *under
  hybrid routing* (Section 13) — the whole point of the migration is letting the
  optimizer split the loop across JNI and Metal. A perf regression on hybrid
  routing blocks the cutover regardless of acoustic parity.

**Known gap — per-channel automation.** The adapter samples shared / channel-0
genes (`buildArgsMap` supplies one producer per parameter), and the PDSL
`mixdown_master` applies that shared producer to every channel inside
`for each channel`. The live Java path drives a per-channel-distinct envelope
from each channel's gene. Closing this requires a `producer([channels])` form on
the relevant primitives (or a per-channel layer body) so each channel reads its
own envelope row — see the per-channel-automation gap in Section 8.

---

## 15. EfxManager PDSL Rendition

The **feedforward** EFX chain is now rendered by the composite `efx_channel`
layer in `efx_channel.pdsl` (closed 2026-06-03):

```
efx_channel = accum_blocks( { identity() },                              // dry
                            { fir(filter_coeffs); scale(wet_level);      // wet
                              scale(automation); delay(...) } )
```

This reproduces `EfxManager.apply()` (`studio/compose/.../arrange/EfxManager.java:215-250`)
**minus the recursive feedback loop**: the gene-chosen HP/LP filter (the adapter
precomputes the `decision`/`cutoff`-selected bank), the wet level (`delayLevels[0]`),
the `0.5·(1+automation_curve)` modulation (`EfxManager.java:236-240`, supplied as a
`producer([1])`), the single feedforward delay tap, and the wet/dry sum. Validated by
`PdslAudioDspTest#testEfxChannelFeedforward` (builds, renders finite non-silent audio,
wet path demonstrably contributes).

**What remains: the `.mself` delay-feedback loop** (`EfxManager.java:245`,
`g(delayLevels[1])`) — the recursive echo regeneration. This is the same
self-feedback-grid construct that PDSL cannot yet express (inventory item 5); it is
the genuinely hard piece and is the subject of the feedback-grid phase. A
genome→args adapter (analogous to `MixdownManagerPdslAdapter`, sourcing `delayTimes`
/ `delayLevels` / `delayAutomation`) is the remaining feedforward wiring.

---

## 16. Confirmation Plan — can all signal processing move to PDSL?

The objective of this phase: **confirm that the entire signal path can be defined
in PDSL with acoustic parity and at/under the real-time budget on hybrid routing.**
Four steps (the optimization payoff — actually splitting the loop across JNI/Metal —
is a separate, later effort and is explicitly out of scope here).

### Step 1 — Coverage inventory (the gate)

Every DSP element in the live `MixdownManager`/`EfxManager` path, mapped to its PDSL
construct and current fidelity. **Covered** = expressible and acoustically equivalent;
**Approx** = expressible but a documented approximation; **Gap** = not yet expressible.

| # | DSP element | Java location | PDSL construct | Status |
|---|---|---|---|---|
| 1 | Per-channel main HP filter (automation-driven cutoff) | `createCells` ~519 | `highpass` + `producer([channels])` cutoff, subscripted `hp_cutoff[channel]` | **Covered** — adapter supplies one gene-driven cutoff per channel (closed 2026-06-03) |
| 2 | Per-channel volume (automation) | `createCells` ~535 | `scale(volume[channel])`, `producer([channels])` | **Covered** — per-channel volume (closed 2026-06-03) |
| 3 | Master LP filter (automation) | `createEfx` ~720 | `lowpass` + `producer([1])` cutoff | **Approx** — channel-0 cutoff |
| 4 | Wet-bus filter | `FixedFilterChromosome` (IIR HP+LP) | `fir` static coeffs | **Approx — accepted** (owner decision 2026-06-03): the FIR rendition stands; the parity band stays loose on the wet bus rather than matching the IIR with biquads |
| 5 | Cross-channel routing / transmission | `createEfx` ~677 (`mself` feedback grid) | `route(matrix)` + transmission slot | **Approx** — static routing covered; the `mself` self-feedback grid is the one genuinely hard construct (§6B) |
| 6 | Wet/dry mix + static wet level | `createEfx` | `scale` + sum | **Covered** |
| 7 | Delay (`AdjustableDelayCell`) | `createEfx` ~665 | `delay` (static `delay_samples`) | **Approx** — static vs gene-modulated delay time |
| 8 | Reverb (`DelayNetwork`) | `createCells` ~583–613 | `delay_network` / `mixdown_reverb_bus` | **Approx** — primitive implemented; acoustic parity unverified (knownIssue test); reverb OFF in the current gate |
| 9 | Master gain + saturation | `createEfx` ~770–782 (gain × hard-clip) | `scale(master_gain)` + `tanh_act()` | **Covered** — soft-sat replaces hard clip |
| 10 | EFX feedforward wet chain | `EfxManager` (FIR/biquad) | `efx_channel.pdsl` `efx_channel` layer | **Covered** — composite feedforward chain (closed 2026-06-03) |
| 11 | EFX automation-driven wet/dry + delay-time modulation | `EfxManager.apply()` | `efx_channel` layer: dry + delay(automation·wet·fir(input)) | **Covered (feedforward)** — filter + wet + automation modulation + delay + dry sum rendered (closed 2026-06-03). The recursive `.mself` delay-feedback loop (`delayLevels[1]`) is the one deferred piece → item 5 / the feedback-grid phase |
| 12 | Dynamic channel count (gene activation) | `MixdownManager` | fixed `channels` | **Not needed** (owner decision 2026-06-03) — compile for a fixed channel count up front; gene-driven channel activation is out of scope |

**Conclusion of step 1:** the *structure* of the full signal path is expressible in
PDSL, but parity is currently gated on six items — per-channel automation (1–3), the
IIR-vs-FIR wet filter (4), reverb-bus parity (8), gene-modulated delay time (7), the
EfxManager automation path (11), and variable channel count (12). None are
"can't-be-done"; each is bounded implementation work. The `mself` feedback grid (5) is
the only construct whose PDSL expression is genuinely novel design.

### Step 2 — Acoustic-parity gate

Extend `MixdownManagerPdslVerificationTest` from its current single loose
energy-ratio check (reverb OFF, 1/6×–6× band) toward the full surface: reverb ON,
per-channel automation, EfxManager included; tighten the tolerance as each step-1
Approx/Gap is closed. The test reports the honest residual divergence at each stage —
it must never be loosened to manufacture a pass. Detail and current caveats: §14.

### Step 3 — Real-time / perf gate (hybrid routing)

The PDSL path must hold the real-time budget *under default hybrid routing* (§13) —
measured warm steady-state via the continuous-render harness with
`AR_PATTERN_CACHE_PERSIST=true`, the same method that established the current ~1.1×
baseline. A regression vs. the Cell path on hybrid routing blocks the cutover. Never
force `AR_HARDWARE_DRIVER`.

### Step 4 — A/B cutover mechanism (Block-outward, after parity)

Once the DSP is confidently correct, an `enablePdsl`-style flag (default **off**,
matching the existing `enableEfx` / `enableReverb` static-flag convention) routes
`MixdownManager.cells(...)` through the adapter-built, compiled PDSL `Block` instead
of `createCells()`. The consumer must accept a `Block` (or `List<Block>`) — **never
wrap the Block in a Cell/CellList** (§14 direction principle). Mechanism and the
per-channel-automation gap: §14.

### Sequencing

Step 1 is complete (this section). **Close the parity gaps first** — be confident the
PDSL DSP path reproduces the Java path before touching the render path. The order:
(a) extend the parity gate (step 2) to the full surface to quantify the current
divergence; (b) close the step-1 Approx/Gap items, tightening the parity tolerance as
each lands; (c) stand up the perf gate (step 3) — which needs the cutover to measure
the PDSL path in the real render; (d) add the Block-outward A/B cutover (step 4),
shipping default-off until both gates pass.

---

## Appendix A: Current PDSL Files

| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |
| `pdsl/audio/efx_channel.pdsl` | EFX channel layers: `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix` |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown layers: `mixdown_main` (HP→scale→LP), `mixdown_channel` (full with wet/delay) |
| `pdsl/audio/delay_feedback_bank.pdsl` | Multi-channel delay bank: `delay_feedback_bank` (repeat → per-channel delay → route → sum_channels) |
| `pdsl/audio/mixdown_manager.pdsl` | Top-level mixdown: `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_reverb_bus`, `mixdown_master` |

The `.pdsl` resource files live in `engine/ml/src/main/resources/pdsl/` and its
subdirectories. The audio primitive *code* that interprets the audio constructs
(`AudioDspPrimitives`, `MultiChannelDspFeatures`) lives in
`studio/compose/.../dsl/audio/`, and the audio Java tests and their test
resources live in `studio/compose` — not `engine/ml`. The `engine/ml`
`PdslInterpreter` registers only the core/ML primitives plus the
domain-agnostic `repeat`, `sum_channels`, `identity`, and `scale`.

---

## Appendix B: `data` Block vs `state` Block — Implementation Diff

**Parser (`PdslParser.java`):** `parseStateDef()` added alongside `parseDataDef()`. Both call
`parseDataDefBody()` — the shared body-parse logic that populates `parsedParams` and
`parsedDerivations`. The only difference is which keyword is consumed and which AST node
is produced.

**AST (`PdslNode.java`):** `StateDef` extends `DataDef` — structurally identical, different
class for semantic distinction. No new fields needed.

**Interpreter (`PdslInterpreter.java`):** `evaluateStateDef()` added alongside
`evaluateDataDef()`. Both call `evaluateDefEntries()` — the shared population logic. The
interpreter populates state def environments identically to data def environments.

**Primitives (`studio/compose/.../dsl/audio/AudioDspPrimitives.java`):**
`dispatchBiquad`, `dispatchDelay`, `dispatchLfo` follow the exact same pattern as
`dispatchFir`, `dispatchLowpass`, `dispatchHighpass`. All computation expressed
as `CollectionProducer` operations. Do not use `setMem()` or `toDouble()` — these bypass
the Producer computation graph and cannot be hardware-accelerated.

No changes to `PackedCollection`, `SequentialBlock`, `Model`, `CompiledModel`, or any
hardware backend are needed.

---

## Appendix C: Audio Output Is the Proof

Every phase of this workstream must end with **audio you can listen to**. Passing code
policy checks and unit test assertions are prerequisites, not deliverables.

### What "Done" Looks Like at Each Phase

**Phase A (FIR primitives — complete):**
WAV files in `studio/compose/results/pdsl-audio-dsp/`: dry multitone,
lowpass-filtered (5kHz), delay echo. Generated by
`PdslAudioDemoTest.testPdslDspProducesAudio()` (the audio tests live in
`studio/compose`).

**Phase B (state-aware primitives — complete):**
Tests in `PdslAudioDspTest` verify biquad state persists across buffer boundaries
(filter transient, not cold-start each call). Wet/dry mix demo in `PdslAudioDemoTest.testPdslMixDemo()`.

**Phase C (multi-channel DSP constructs — complete):**
WAV file `results/pdsl-audio-dsp/delay_feedback_bank.wav` produced by `DelayFeedbackBankPdslTest`.
The `delay_feedback_bank` PDSL layer fans a mono 440 Hz sine out to 3 parallel delay lines,
mixes them via a routing matrix, and sums to mono. Output is audibly different from the dry input.

### How to Produce a .wav File

Wire the PDSL pipeline output into `WavFile.write()` (see `engine/audio`) with a test buffer
as input. Test class must extend `TestSuiteBase`. The `.wav` file does not need to be committed;
the test that generates it must pass in CI.

### Why This Matters

If the PDSL pipeline cannot produce audio, the workstream has not achieved its goal regardless
of what the tests assert. Every agent working on this branch should ask: *"Can I listen to the
output?"* If the answer is no, the work is not done.

---

## 17. Feedback / `mself` in PDSL (the recursive grid)

The last DSP construct PDSL could not express is `mself` — the recursive self/cross-channel
feedback that produces echo trains (EfxManager) and the reverb transmission grid (MixdownManager).
This section records the design we agreed on and the plan to land it.

### 17.1 What `mself` is, mechanically

`CellFeatures.mself(cells, adapter, transmission, passthrough)` → `m(cells, adapter, cells,
transmission, passthrough)`: the routing **destinations are the source cells themselves**
(`CellFeatures.java:901`, `:944`). Per cell, `MultiCell.split` (`MultiCell.java:134`) wires:

- source output → `passthrough` cell → **next layer** (one linear map of the output),
- source output → `adapter` → `MultiCell[transmission]` → **back into the same cells**, which
  *accumulate* it (another linear map of the same output).

The loop terminates not because of the routing but because `CachedStateCell` accumulates on
receipt and only pushes on `tick()` — that tick boundary is a **unit delay** that turns an
algebraic loop into a recurrence. Your `feedback(transmission, block, passthrough)` mirrors this
exactly: `transmission` = routing back into the block, `passthrough` = routing out to the next
layer, both applied to the same block output.

### 17.2 Granularity: PDSL DSP is per-frame, not per-sample

Every stateful PDSL primitive (`delay`, `biquad`, `lfo`, `delay_network`) processes a whole frame
of `n` samples in **one parallel op per tick** and carries state across frames in a ring buffer
(`AudioDspPrimitives.java:196`, `MultiChannelDspFeatures.java:168`). A "tick" = one frame. The
recurrence we implement is therefore **frame-to-frame**, exactly the DAW block-processing model.

### 17.3 The block-parallel-feedback trade-off (accepted)

For `y[n] = x[n] + g·y[n−D]` with frame size `F`:

- **D ≥ F:** every `y[n−D]` lives in an already-computed prior frame → the whole frame computes in
  **one parallel kernel** (gather, apply matrix, add input, write back). Sample-accurate,
  GPU-friendly. This is what `delay_network` already does.
- **D < F:** `y[n−D]` refers to same-frame samples → intra-frame recurrence → must scan
  sample-by-sample. This is CellList `mself`'s per-sample loop, i.e. the a3 `Loop x4096 [JNI]`
  bottleneck (99.6% of tick).

**Decision:** block-parallel feedback has a **minimum loop delay = block size** (~93 ms at
4096 frames). We **accept** this floor. It is below the shortest real EFX echo: EfxManager
`delayTimes` choices `{2^(i−2), 1.5·2^(i−2)}` (`EfxManager.java:118`) have a minimum ≈ 0.25
(~11,025 samples ≈ 2.7 frames), so EFX echoes are firmly multi-frame and need **no** sequential
scan. Sub-frame feedback (some reverb modes) stays frame-quantized for now; if parity later
demands sample-accurate sub-frame feedback we add a separately-clocked smaller-frame stage — not
in scope now.

### 17.4 What already exists vs. what we build

`delay_network(delay_samples, feedback_matrix, buffer, heads)` is already the block-parallel
feedback FDN: it reads delayed outputs, applies an **arbitrary** matrix per frame, and writes
`in + fb` back to the ring. It currently locks `bufSize == signalSize` (ring = exactly one frame),
so the feedback delay snaps to ~1 frame. The work:

1. **Multi-frame ring.** Relax `bufSize == signalSize` to `bufSize == k·signalSize` (k ≥ 1) so
   real echo lengths (1–3+ frames) are sample-accurate rather than snapped to one frame.
2. **`feedback(transmission, block, passthrough)` surface construct.** Lower it to the FDN
   write-back-to-ring structure. For the common case where the wrapped block *is* a delay, it
   collapses onto the (now multi-frame) `delay_network` machinery. `mself(input_level,
   transmission, passthrough) = scale(input_level)` then `feedback(...)`.
3. **Single-channel first.** Validate the 1×1 feedback comb against the EfxManager echo
   (acoustic + numeric), then lift to the M×M grid by feeding the transmission gene matrix for the
   MixdownManager `createEfx` path.
4. **Arbitrary-block generality later.** `feedback` over a general causal block (delay+filter,
   etc.) reintroduces the "must be per-sample-evaluable" constraint; defer until the delay case
   works.

### 17.5 Well-posedness

`feedback` requires the loop path to contain ≥ 1 frame of delay (the multi-frame ring guarantees
this). A zero-delay loop is an algebraic cycle and is rejected — this is the precise form of "some
blocks for which feedback would cause infinite regress."

### Sequencing (tasks)

1. **Landed** — multi-frame ring + `feedback()` construct, single-channel comb.
   `MultiChannelDspFeatures.delayNetworkBlock` was relaxed from `bufSize == signalSize`
   to `bufSize == k·signalSize` via a new per-frame-slot windowed write (`ringWrite`);
   the head is frame-aligned so the target slot is selected with `equals`, keeping the
   whole update one parallel `into`. The FDN was generalized to `feedbackNetworkBlock`
   (transmission + optional passthrough matrix, shared `channelMatrixMultiply`), exposed
   as the `feedback(delay_samples, transmission, passthrough, buffers, heads)` PDSL
   primitive, with a channel-parametric `feedback_comb` layer in `efx_channel.pdsl`.
   Validated: `DelayNetworkBehaviorTest` 10/10 (the previously-ignored multi-frame
   wrap-around `test09` re-enabled and passing).
2. **Landed** — acoustic validation. `MixdownManagerPdslVerificationTest
   .pdslFeedbackCombLoopedSampleDemo` loops a real library sample through the
   `feedback()` surface with a multi-frame echo (~300 ms) and writes
   `results/pdsl-audio-dsp/pdsl_feedback_comb_looped_sample.wav`. Output is all-finite,
   non-silent, and stable (peak ≈ 0.90 — the feedback decays rather than blowing up).
3. **Next** — lift `feedback_comb` to the M×M mixdown grid by feeding it the
   MixdownManager `createEfx` transmission gene (an `EfxManager`/`MixdownManager`
   adapter producing the `[channels, channels]` matrix); `mself(input_level, T, P) =
   scale(input_level)` then `feedback(T, …, P)`.
