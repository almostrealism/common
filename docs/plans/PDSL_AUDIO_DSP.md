# PDSL for Audio DSP Processing

**Status:** Phase A (FIR primitives) complete. Phase B (`state` block syntax + stateful primitives) complete. Phase C (multi-channel DSP constructs on `layer`) complete. `delay_feedback_bank.pdsl` demonstrates `fan_out` + `for each channel` + `route` + `sum_channels` with WAV output.
**Related:** `docs/plans/AUDIO_SCENE_REDESIGN.md`

---

## 1. The Goal and Why

The AudioScene pipeline — `MixdownManager`, `EfxManager`, `AutomationManager` — is wired imperatively in Java. The framework cannot analyze the graph, partition it across channels, or optimize it automatically because the graph is hidden inside Java method calls.

If the same pipeline were defined **declaratively in PDSL**, the framework would have a structured description of the graph it could analyze, partition, and recompile. Per-channel kernel splitting, copy elimination, and silent channel skipping — the three main goals of the AudioScene redesign — all become tractable problems in a declarative graph model.

The chosen approach extends the existing `layer` keyword with multi-channel DSP constructs: `channels: N` header parameter, `for each channel { }` body construct, `fan_out(N)`, `route(matrix)`, and `sum_channels()`. A `layer` compiles to a `Block` — a static computation graph — and a sequence of PDSL layers compiles to a `Model` via `SequentialBlock`. This approach reuses the full existing PDSL infrastructure without a new compilation target.

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
`identity`, `lowpass`, `highpass`, `biquad`, `delay`, `lfo`.

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
2. **State-aware primitives** — `callBiquad`, `callDelay`, `callLfo` in `PdslInterpreter.java`.
   Follow the same `CollectionProducer` pattern as `callFir`, `callLowpass`, `callHighpass`.
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

**Construct 2: `fan_out(N)` — Replicate 1 channel to N**

Takes a `[1, signal_size]` input and produces `[N, signal_size]` by concatenating N copies.
Compiles to `MultiChannelDspFeatures.fanOutBlock(n, signalSize)`.

```pdsl
fan_out(channels)    // [1, signal_size] → [channels, signal_size]
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

Element-wise addition over all channel slices. Compiles to
`MultiChannelDspFeatures.sumChannelsBlock(channels, signalSize)`.

```pdsl
sum_channels()    // [channels, signal_size] → [1, signal_size]
```

**Complete example — `delay_feedback_bank`:**

```pdsl
layer delay_feedback_bank(channels: int, signal_size: int, delay_samples: int,
                          transmission: weight) -> [1, signal_size] {
    fan_out(channels)
    for each channel {
        delay(delay_samples, buffers[channel], heads[channel])
    }
    route(transmission)
    sum_channels()
}
```

Signal flow: `[1, S]` → fan_out → `[N, S]` → per-channel delay → `[N, S]` → route → `[N, S]` → sum → `[1, S]`.

### 6D: Why This Matters for ML Too

The multi-channel constructs are not audio-specific. Three ML use cases benefit directly:

**Multi-head attention decomposition** — today `attention(q_proj, k_proj, ...)` is a black box.
With `fan_out(num_heads)` + `for each channel` the per-head computation becomes visible and
customizable without writing a new Java primitive.

**Mixture-of-experts** — `fan_out(num_experts)` + `for each channel { dense(expert_w1[ch])... }`
+ `route(gate_scores)` + `sum_channels()` replaces O(N) explicit `concat_blocks` branches.
The `route(gate_scores)` is the MoE routing — the same mechanism as `mself(transmission)` in
the audio delay bank.

**Parallel residual streams** — `fan_out(num_streams)` + `for each channel` + `sum_channels()`
makes stream count a parameter rather than hardcoding via `accum_blocks`.

These are gaps in the expressiveness of the PDSL language itself, relevant to any domain
operating on sets of parallel signals.

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

**Stateful blocks in CellList context:** The state write-back in `callBiquad`, `callDelay`,
and `callLfo` happens inside `push()` via `FEATURES.into(...)`. State is updated on every
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
- **Multi-channel constructs** — `fan_out(N)`, `for each channel { }`, `route(matrix)`,
  `sum_channels()` implemented in `PdslInterpreter`, `PdslParser`, `PdslNode`, and
  `MultiChannelDspFeatures`. Subscript syntax `expr[index]` for per-channel state slicing.
- **`delay_feedback_bank.pdsl`** — exercises all four multi-channel constructs end-to-end:
  fan out → per-channel delay → cross-channel route → sum to mono.
- **PDSL/CellList integration** — `PdslLayerCellListIntegrationTest` (2 tests) validates
  state persistence across `Temporal.tick` and Block→Temporal adapter flow.
- **Test coverage** — `PdslAudioDspTest` (13 tests), `MixdownChannelPdslTest` (8 tests),
  `PdslAudioDemoTest` (2 tests), `DelayFeedbackBankPdslTest` (1 test). All pass at depth 2.
- **WAV output** — `results/pdsl-audio-dsp/` contains dry multitone, lowpass-filtered,
  delay-echo, wet/dry mix, and delay_feedback_bank WAV files.

### What Remains as Gaps in `mixdown_channel.pdsl`

- **IIR vs FIR:** `MixdownManager` uses `AudioPassFilter` (IIR biquad) via
  `CellFeatures.hp()`/`lp()`. The PDSL `highpass`/`lowpass` primitives use
  `MultiOrderFilter` (FIR). Frequency responses differ near the cutoff — validated by
  energy-level assertions, not exact sample match.
- **Cross-channel transmission:** `MixdownManager.createEfx()` uses `mself(fi(), transmission, ...)`.
  Now expressible via `route(transmission)` in PDSL (Section 6C, Construct 4); migration
  requires wiring the per-channel state collections from the genome.
- **Reverb path:** `DelayNetwork` is multi-tap feedback assembled from Java cell primitives.
  No PDSL equivalent yet.
- **Automation envelopes:** `AutomationManager.getAggregatedValue()` produces time-varying
  Producer values computed in Java. These are passed as `scalar` parameters — the caller
  computes the current value and supplies it. This is the correct design.

### What's Next

- **`MixdownManager.createEfx()` migration** — now that `route(transmission)` and
  `for each channel` are in PDSL, the cross-channel delay feedback loop in `createEfx()`
  is the highest-value migration target. It requires wiring genome-driven per-channel state.
- **Variable channel count** — today `channels` is fixed at build time. Supporting
  gene-driven channel activation requires runtime branching, which is not yet in PDSL.
- **Temporal integration wrapper** — a reusable Java adapter that wraps a `CompiledModel`
  as a `Temporal` with automatic state collection management, reducing boilerplate at
  `CellList` integration sites.

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
`max_delay_samples` from gene range bounds. Allocate the state block's `buffer`
`PackedCollection` at that fixed maximum. Use a read pointer offset (stored in `head`)
for variable delay. This is how hardware delay lines work.

### Risk 3: Cross-Channel Transmission (Medium Impact, Addressable)

`MixdownManager`'s `transmission` chromosome routes audio between channels, preventing
per-channel splitting from being fully independent. Resolution: `route(transmission)`
(Section 6C Construct 4) is now implemented. The remaining work is wiring genome-driven
transmission values into the args map at scene initialization time.

### Risk 4: Real-Time Audio Constraints (Medium Impact, Design Question)

Real-time audio requires deterministic latency. PDSL compilation produces `CompiledModel`
objects that, once compiled, run without Java allocation. Resolution: compile the PDSL
model in a background thread during scene initialization; swap the compiled model into the
audio path only when compilation completes (double-buffered model replacement).

---

## 10. MixdownManager Migration: Structural Plan

This section catalogs every statement in
`studio/compose/src/main/java/org/almostrealism/studio/arrange/MixdownManager.java`
that contributes to the signal flow and maps it to one of three statuses:

- **PDSL-ready** — expressible today in the current vocabulary
  (`layer` + existing primitives + multi-channel constructs). Covered by a
  PDSL file and a passing test in this task.
- **PDSL-blocked-by-X** — structurally expressible, but requires a
  capability `X` that PDSL does not provide today. Covered by a `@Disabled`
  test that describes the intended final state.
- **Not in scope** — Java orchestration (genome/automation wiring, lifecycle,
  output receptor routing, feature flags, dynamic resize). Stays in Java
  and does not need a PDSL rendition.

The PDSL rendition this plan produces is intentionally *structural*: it
captures the shape of the pipeline (multi-channel fan-in, per-channel
filtering, cross-channel routing, master bus summation, master low-pass)
under the simplifying assumption that every parameter (volume, filter
cutoffs, delay times, wet levels) is a *fixed scalar at build time*.
All of the PDSL-blocked rows share the same underlying cause:
`MixdownManager` wires genome-driven `Producer<PackedCollection>` values
into the audio path, and PDSL does not yet have a `automation(scalar)`
primitive that accepts a time-varying scalar producer as a layer argument.

### 10.1 `createCells()` — top-level per-channel wiring

| # | Line(s) | Method / statement | Status | Covered by |
|---|---------|---------------------|--------|------------|
| 1 | 504–513 | Per-channel HP filter with **automation-driven cutoff** (`enableAutomationManager` branch) | PDSL-blocked-by-automation | `testMixdownManagerAutomatedHighpass` (`@Disabled`) |
| 2 | 514–521 | Per-channel HP filter with **gene-driven cutoff** (no automation, but still time-varying via `TemporalFactor`) | PDSL-blocked-by-automation | Same as #1 |
| 3 | 524–526 | Per-channel volume `Factor` from `toAdjustmentGene(...).valueAt(0)` | PDSL-blocked-by-automation | `testMixdownManagerAutomatedVolume` (`@Disabled`) |
| 4 | — | Per-channel HP filter with **fixed cutoff** (structural rendition of row 1/2) | PDSL-ready | `mixdown_main_bus` layer in `mixdown_manager.pdsl` |
| 5 | — | Per-channel volume with **fixed scalar** (structural rendition of row 3) | PDSL-ready | `mixdown_main_bus` layer in `mixdown_manager.pdsl` |
| 6 | 528–536 | `enableSourcesOnly` fast-path (skip effects, deliver directly to master) | Not in scope | Java feature flag / receptor wiring |
| 7 | 538–539 | `cells.mixdown(mixdownDuration)` — offline-buffered pattern mixdown | Not in scope | CellList-level buffering pass |
| 8 | 541–544 | `reverbActive` flag computation | Not in scope | Java feature flag |
| 9 | 546–561 | `reverbFactor` gene / automation curve per reverb channel | PDSL-blocked-by-automation | Part of `testMixdownManagerReverbPath` (`@Disabled`) |
| 10 | 567–576 | Wet-sources path: `wetSources.branch(v·wetFilter, reverbFactor)` — heterogeneous fan-out | PDSL-blocked-by-heterogeneous-fanout | `testMixdownManagerHeterogeneousBranch` (`@Disabled`) |
| 11 | 591–602 | Main-sources path: `cells.branch(v, v·wetFilter, reverbFactor)` — 3-way heterogeneous fan-out | PDSL-blocked-by-heterogeneous-fanout | Same as #10 |
| 12 | 580–589 | `!enableEfx` fast-path: main-only (no efx, no reverb) | PDSL-ready (as the `mixdown_main_bus` layer) | `mixdown_main_bus` layer |
| 13 | 604–607 | Stems fan-out: `main.branch(StemReceptor, PassThroughCell)` | Not in scope | Output receptor routing |
| 14 | 610     | `main = main.sum()` — collapse N channels to 1 | PDSL-ready | `sum_channels()` inside `mixdown_main_bus` |
| 15 | 612–613 | `createEfx(main, efx, reverb, ...)` delegation | PDSL-ready (merge via `accum_blocks`) | `mixdown_master` layer |
| 16 | 615–624 | Master-path delivery without EFX (receptor to `output.getMaster`) | Not in scope | Output receptor routing |

### 10.2 `createEfx()` — effects bus, reverb, master LP

| # | Line(s) | Method / statement | Status | Covered by |
|---|---------|---------------------|--------|------------|
| 17 | 654–658 | Delay-layer array of `AdjustableDelayCell` with **time-varying delay samples** from `delay` chromosome | PDSL-blocked-by-variable-delay | `testMixdownManagerVariableDelayTime` (`@Disabled`) |
| 18 | — | Delay-layer array with **fixed delay samples** (structural rendition of row 17) | PDSL-ready | Per-channel `delay(...)` inside `mixdown_efx_bus` |
| 19 | 660–662 | `delayGene` routing: N efx cells → M delay layers via **gene-driven rectangular matrix** | PDSL-blocked-by-rectangular-route | `testMixdownManagerRectangularRoute` (`@Disabled`) |
| 20 | 664     | `efx.m(fi(), delays, tg)` — per-cell gene-routed fan-out | PDSL-blocked-by-rectangular-route | Same as #19 |
| 21 | 665–666 | `.mself(fi(), transmission, fc(wetOut.valueAt(0)))` — cross-channel feedback matrix (square N×N case) | PDSL-ready | `route(transmission)` inside `mixdown_efx_bus` |
| 22 | 667     | `.sum()` final collapse | PDSL-ready | `sum_channels()` inside `mixdown_efx_bus` |
| 23 | 669     | `!enableTransmission`: `efx.sum()` only | PDSL-ready | `mixdown_efx_bus` with identity routing matrix |
| 24 | 672–674 | Reverb path: `reverb.sum().map(DelayNetwork)` | PDSL-blocked-by-DelayNetwork | `testMixdownManagerReverbPath` (`@Disabled`) |
| 25 | 676–683 | Reverb/efx merge: `cells(efx, reverb).sum()` | PDSL-ready (when reverb is available; `accum_blocks` semantics) | Part of `testMixdownManagerReverbPath` (`@Disabled`) |
| 26 | 685–694 | `disableClean` alternate receptor wiring | Not in scope | Output receptor routing |
| 27 | 696–705 | `efx.get(0).setReceptor(Receptor.to(main.get(0), ...))` — cell-level wiring | Not in scope | Java CellList wiring |
| 28 | 707–714 | Per-channel master LP filter with **automation-driven cutoff** | PDSL-blocked-by-automation | `testMixdownManagerAutomatedLowpass` (`@Disabled`) |
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

### 10.4 PDSL files produced by this task

| PDSL file | Layers defined | Signal flow |
|-----------|----------------|-------------|
| `pdsl/audio/mixdown_manager.pdsl` | `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_master` | Multi-channel mixdown: N inputs → per-channel HP+volume → sum; N inputs → per-channel wet-filter+scale+delay → route → sum; accumulate (main+efx) → master LP |

The existing `mixdown_channel.pdsl` covers the single-channel case
(`mixdown_main`, `mixdown_channel`); the existing `delay_feedback_bank.pdsl`
covers the pure multi-channel delay/route/sum pattern. `mixdown_manager.pdsl`
is the first PDSL file that renders the *top-level* shape of
`MixdownManager.createCells()` in one declarative graph.

### 10.5 Summary of blocking capabilities

The `@Disabled` tests produced in this task are indexed in Section 11
(“Current Limitations — Captured as Tests”) below. They cluster around
four missing capabilities:

1. **`automation(scalar)` primitive** — accepts a time-varying scalar
   `Producer<PackedCollection>` as a layer argument. Covers rows 1–3, 9, 28.
2. **Variable delay time** — a `delay(...)` primitive that accepts a
   time-varying sample count rather than a compile-time int. Covers row 17.
3. **Rectangular routing** — a `route(matrix)` primitive that accepts
   `[rows, cols]` matrices where rows ≠ cols. Covers rows 19, 20.
4. **Heterogeneous fan-out** — a `fan_out_with(block_a, block_b, ...)`
   or equivalent primitive that applies a *different* sub-block to each
   branch output. Covers rows 10, 11.
5. **`delay_network(...)` primitive** — multi-tap feedback reverb
   equivalent to `org.almostrealism.audio.filter.DelayNetwork`. Covers
   rows 24, 25.

Under these five capabilities, every row in the tables above becomes either
PDSL-ready or is explicitly Not-in-scope. No row requires architectural
changes to `layer`, `state`, or the existing multi-channel constructs.

---

## 11. Current Limitations — Captured as Tests

This section is the index of the `@Disabled` tests produced alongside the
`mixdown_manager.pdsl` rendition. Each entry names the test, the
`MixdownManager` row it targets (see Section 10), the capability that is
currently missing, and a rough size estimate for implementing it.

### 11.1 Capability A — `automation(scalar)` primitive

**What’s needed:** a PDSL primitive that accepts a
`Producer<PackedCollection>` for a *time-varying* scalar parameter. Today,
scalar parameters in PDSL are resolved to a fixed `double` at build time.
`MixdownManager` uses `AutomationManager.getAggregatedValue(...)` and
`toAdjustmentGene(...)` to produce scalar values that change on every
sample; those are the only source of time variation inside its filters
and scaling.

**Sketch of the needed primitive:**

```pdsl
layer mixdown_main_automated(...) {
    for each channel {
        highpass(automation(hp_cutoff_producer), sample_rate, filter_order)
        scale(automation(volume_producer))
    }
    sum_channels()
}
```

The interpreter would treat `automation(id)` as a special argument form
that looks up a `Producer<PackedCollection>` in the args map instead of a
constant. `callHighpass` / `callScale` would need to accept either a
literal or a `Producer`. Follows the same pattern as `fir(wet_filter_coeffs)`
accepting a caller-supplied `PackedCollection`.

**Size estimate:** small — days of work. Requires plumbing in
`PdslInterpreter.callHighpass` / `callLowpass` / `callScale` to accept
producers instead of constants.

| Test class.method | Targets (Section 10 row) | Notes |
|-------------------|--------------------------|-------|
| `MixdownManagerPdslTest.testMixdownManagerAutomatedHighpass` | 1, 2 | Per-channel HP with time-varying cutoff |
| `MixdownManagerPdslTest.testMixdownManagerAutomatedVolume` | 3, 5 | Per-channel volume with time-varying gain |
| `MixdownManagerPdslTest.testMixdownManagerAutomatedLowpass` | 28 | Master LP with time-varying cutoff |

### 11.2 Capability B — Variable delay time

**What’s needed:** a `delay(...)` primitive that accepts a time-varying
sample count (a `Producer<PackedCollection>`) rather than a compile-time
integer. Equivalent to `AdjustableDelayCell`, which takes
`Producer<PackedCollection>` for both delay time and dynamics.

**Size estimate:** medium — probably days of work but touches the delay
kernel. The current `delay` primitive uses a fixed `delay_samples` int
in the read-pointer arithmetic; making it a Producer requires the read
offset to be a `CollectionProducer` op rather than a build-time constant.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerVariableDelayTime` | 17 | Per-delay-layer delay time driven by a time-varying producer |

### 11.3 Capability C — Rectangular routing

**What’s needed:** a `route(matrix)` primitive that accepts
`[rows, cols]` matrices where rows ≠ cols (fan in N channels to M ≠ N
outputs). Today `MultiChannelDspFeatures.routeBlock` is square: its
output shape is `[channels, signalSize]` with the same `channels` as
the input.

**Size estimate:** small — probably a day of work. The implementation is
a straightforward generalization of the existing `routeBlock` loop
nest.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerRectangularRoute` | 19, 20 | N efx channels fan-routed to M delay layers (M ≠ N) |

### 11.4 Capability D — Heterogeneous fan-out

**What’s needed:** a PDSL construct that takes N sub-blocks, applies them
all to the same input, and produces N channels on output — one per
sub-block. Equivalent to `CellList.branch(IntFunction<Cell>...)`.
`concat_blocks` does this for tensor concatenation, but the output shape
is `[sum_of_channels, signalSize]` rather than `[N, per-block-output]`.

**Size estimate:** small — could reuse the `fanOutBlock` + `perChannelBlock`
plumbing with a different set of sub-blocks per channel. Probably a day
of work, possibly less.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerHeterogeneousBranch` | 10, 11 | 3-way branch `{main, main·wet_filter, reverb_factor}` |

### 11.5 Capability E — `delay_network(...)` primitive

**What’s needed:** a PDSL primitive equivalent to
`org.almostrealism.audio.filter.DelayNetwork`: a multi-tap feedback reverb
network with selectable tap counts, tap delays, and feedback gains.

**Size estimate:** large — probably weeks of work. `DelayNetwork` is a
composition of primitives, but it has its own per-tap state and
feedback topology that doesn’t reduce cleanly to the existing
`delay_feedback_bank` pattern (the tap-spacing pattern is irregular and
the feedback-to-input path is internal, not exposed as a `route`
matrix). Likely requires either (a) porting `DelayNetwork` to a
compile-time expansion of `delay_feedback_bank` with irregular tap
spacing, or (b) a dedicated `delay_network(num_taps, base_delay, feedback)`
primitive.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerReverbPath` | 24, 25, 9 | Reverb bus + wet factor; depends on both Capability A (automation) and Capability E (delay_network) |

---



| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |
| `pdsl/audio/efx_channel.pdsl` | EFX channel layers: `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix` |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown layers: `mixdown_main` (HP→scale→LP), `mixdown_channel` (full with wet/delay) |
| `pdsl/audio/delay_feedback_bank.pdsl` | Multi-channel delay bank: `delay_feedback_bank` (fan_out → per-channel delay → route → sum_channels) |

All PDSL files live in `engine/ml/src/main/resources/pdsl/` and its subdirectories.
Test PDSL files live in `engine/ml/src/test/resources/pdsl/audio/`.

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

**Primitives (`PdslInterpreter.java`):** `callBiquad`, `callDelay`, `callLfo` follow the
exact same pattern as `callFir`, `callLowpass`, `callHighpass`. All computation expressed
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
WAV files in `engine/ml/results/pdsl-audio-dsp/`: dry multitone, lowpass-filtered (5kHz),
delay echo. Generated by `PdslAudioDemoTest.testPdslDspProducesAudio()`.

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
