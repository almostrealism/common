# PDSL for Audio DSP Processing

PDSL declaratively describes the multi-channel DSP pipeline that backs `MixdownManager`,
`EfxManager`, and `AutomationManager`. A `layer` body composes FIR primitives
(`fir`, `scale`, `lowpass`, `highpass`), stateful primitives (`biquad`, `delay`, `lfo`),
and multi-channel constructs (`channels: N`, `for each channel { }`, `fan_out(N)`,
`route(matrix)`, `sum_channels()`) into a `Block` that the framework compiles into a
`Model`. Existing PDSL files (`efx_channel.pdsl`, `mixdown_channel.pdsl`,
`delay_feedback_bank.pdsl`, `mixdown_manager.pdsl`) cover the structural rendition of
the audio path under the simplifying assumption that every parameter is a fixed scalar
at build time. Time-varying parameters — gene-driven, clock-driven, and
automation-driven — are addressed in Section 11 below.

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
4. **Time-varying parameters above tier 1:** All scalar parameters today are inlined as
   `double` constants at build time. Gene-driven, clock-driven, and automation-driven
   parameters cannot be expressed as PDSL arguments. See Section 11.

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
- **`mixdown_manager.pdsl`** — top-level structural rendition of `MixdownManager.createCells()`
  + `createEfx()` with three layers (`mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_master`).
  All scalar parameters fixed at build time (Tier 1 in Section 11).
- **PDSL/CellList integration** — `PdslLayerCellListIntegrationTest` (2 tests) validates
  state persistence across `Temporal.tick` and Block→Temporal adapter flow.
- **Test coverage** — `PdslAudioDspTest` (13 tests), `MixdownChannelPdslTest` (8 tests),
  `PdslAudioDemoTest` (2 tests), `DelayFeedbackBankPdslTest` (1 test),
  `MixdownManagerPdslTest` (mix of structural and `@Disabled` capability tests).
  All non-disabled tests pass at depth 2.
- **WAV output** — `results/pdsl-audio-dsp/` contains dry multitone, lowpass-filtered,
  delay-echo, wet/dry mix, and delay_feedback_bank WAV files.

### What Remains as Gaps in the Structural Rendition

- **IIR vs FIR:** `MixdownManager` uses `AudioPassFilter` (IIR biquad) via
  `CellFeatures.hp()`/`lp()`. The PDSL `highpass`/`lowpass` primitives use
  `MultiOrderFilter` (FIR). Frequency responses differ near the cutoff — validated by
  energy-level assertions, not exact sample match.
- **Reverb path:** `DelayNetwork` is multi-tap feedback assembled from Java cell primitives.
  No PDSL equivalent yet (Capability E in Section 12.5).
- **Time-varying parameters:** All scalar parameters are inlined at build time. The
  `MixdownManager` audio path is driven by gene-, clock-, and automation-derived
  Producers that PDSL cannot accept as layer arguments today. This is the single largest
  gap and is decomposed into four tiers in Section 11.

### What's Next

Listed roughly in priority order:

1. **`MixdownManager.createEfx()` end-to-end migration.** The structural shell
   (`mixdown_efx_bus`) is in place. Promoting it to a working drop-in replacement requires
   wiring genome-driven per-channel state and the time-varying parameter capabilities
   below.
2. **Tier 4 sample-rate automation primitive — `automation(producer)`** (Section 11.3).
   Allows `callHighpass` / `callLowpass` / `callScale` to accept a
   `Producer<PackedCollection>` argument instead of a build-time `double`. Largest
   single unlock for migrating live `MixdownManager` and `EfxManager` paths.
3. **Tier 2 mutable scalars** (Section 11.3). Smallest possible step — make `scalar`
   parameters refer to a 1-element `PackedCollection` slot mutable across renders, the
   same way `weight` parameters already work. Useful as an interim step or as the
   degenerate case of tier 4.
4. **Rectangular routing** — generalize `route(matrix)` to accept `[rows, cols]` matrices
   where rows ≠ cols (Section 12.3). Required for the N efx → M delays fan in
   `createEfx()`.
5. **Heterogeneous fan-out** — a construct that applies a *different* sub-block to each
   branch output (Section 12.4). Required for the wet/efx/reverb branch in
   `createCells()`.
6. **`delay_network(...)` primitive** — PDSL equivalent of
   `org.almostrealism.audio.filter.DelayNetwork` (Section 12.5).
7. **Variable channel count.** Today `channels` is fixed at build time. Supporting
   gene-driven channel activation requires runtime branching.
8. **Temporal integration wrapper.** A reusable Java adapter that wraps a `CompiledModel`
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
Most of the PDSL-blocked rows share the same underlying cause: `MixdownManager`
wires gene-, clock-, and automation-derived `Producer<PackedCollection>` values
into the audio path, and PDSL does not yet have a way to accept those as
layer arguments. Section 11 below classifies what variation each parameter
carries and what would be needed to express it in PDSL.

### 10.1 `createCells()` — top-level per-channel wiring

| # | Line(s) | Method / statement | Status | Covered by |
|---|---------|---------------------|--------|------------|
| 1 | 504–513 | Per-channel HP filter with **automation-driven cutoff** (`enableAutomationManager` branch) | PDSL-blocked-by-tier-4 | `testMixdownManagerAutomatedHighpass` (`@Disabled`) |
| 2 | 514–521 | Per-channel HP filter with **gene-driven cutoff** (no automation, but still time-varying via `TemporalFactor`) | PDSL-blocked-by-tier-4 | Same as #1 |
| 3 | 524–526 | Per-channel volume `Factor` from `toAdjustmentGene(...).valueAt(0)` | PDSL-blocked-by-tier-4 | `testMixdownManagerAutomatedVolume` (`@Disabled`) |
| 4 | — | Per-channel HP filter with **fixed cutoff** (structural rendition of row 1/2) | PDSL-ready | `mixdown_main_bus` layer in `mixdown_manager.pdsl` |
| 5 | — | Per-channel volume with **fixed scalar** (structural rendition of row 3) | PDSL-ready | `mixdown_main_bus` layer in `mixdown_manager.pdsl` |
| 6 | 528–536 | `enableSourcesOnly` fast-path (skip effects, deliver directly to master) | Not in scope | Java feature flag / receptor wiring |
| 7 | 538–539 | `cells.mixdown(mixdownDuration)` — offline-buffered pattern mixdown | Not in scope | CellList-level buffering pass |
| 8 | 541–544 | `reverbActive` flag computation | Not in scope | Java feature flag |
| 9 | 546–561 | `reverbFactor` gene / automation curve per reverb channel | PDSL-blocked-by-tier-4 | Part of `testMixdownManagerReverbPath` (`@Disabled`) |
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
| 17 | 654–658 | Delay-layer array of `AdjustableDelayCell` with **time-varying delay samples** from `delay` chromosome | PDSL-blocked-by-tier-4-delay | `testMixdownManagerVariableDelayTime` (`@Disabled`) |
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
| 28 | 707–714 | Per-channel master LP filter with **automation-driven cutoff** | PDSL-blocked-by-tier-4 | `testMixdownManagerAutomatedLowpass` (`@Disabled`) |
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
fields in place — exactly the tier-2 (render-time mutable scalar) pattern
described in Section 11.

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

The `@Disabled` tests produced in this task are indexed in Section 12
("Current Limitations — Captured as Tests"). They cluster around
five missing capabilities, four of which trace back to one root cause:

1. **Tier 4 sample-rate automation** — `automation(producer)` primitive plus a
   variable-delay-time variant. PDSL needs the ability to accept a
   `Producer<PackedCollection>` as a scalar argument to filters, gain stages, and
   delay-sample counts. Covers rows 1–3, 9, 17, 28. Section 11 unifies these.
2. **Rectangular routing** — a `route(matrix)` primitive that accepts
   `[rows, cols]` matrices where rows ≠ cols. Covers rows 19, 20.
3. **Heterogeneous fan-out** — a `fan_out_with(block_a, block_b, ...)`
   or equivalent primitive that applies a *different* sub-block to each
   branch output. Covers rows 10, 11.
4. **`delay_network(...)` primitive** — multi-tap feedback reverb
   equivalent to `org.almostrealism.audio.filter.DelayNetwork`. Covers
   rows 24, 25.

Under these capabilities, every row in the tables above becomes either
PDSL-ready or is explicitly Not-in-scope. No row requires architectural
changes to `layer`, `state`, or the existing multi-channel constructs.

---

## 11. Time-Varying Parameters: Four Tiers of Variation

The PDSL files produced so far treat every numeric parameter as a build-time
constant. The Java `MixdownManager`/`EfxManager` code does not. Different
parameters change at very different rates, and conflating them under a single
"automation" label obscures both the work needed and the order to do it in.

This section distinguishes four tiers of variation, identifies which
parameters in `MixdownManager`/`EfxManager` sit at each tier, and lays out
what PDSL needs at each tier above tier 1.

### 11.1 The Four Tiers

#### Tier 1: Build-time constant

What PDSL handles today. Resolved at `loader.buildLayer(...)`. The numeric
value is folded into the compiled kernel as a literal `double`.

- **Runtime form:** `double` constant, baked into the compiled kernel.
- **Mutability after build:** none; rebuilding the layer is required.
- **Example in MixdownManager:** none of the live parameters are tier 1; the
  fixed-scalar rendition in `mixdown_manager.pdsl` is a deliberate
  simplification of the live paths.

#### Tier 2: Render-time constant (genome-reassignment-time)

The value is constant for the duration of one render but can be replaced
between renders without rebuilding the graph. In Java, this is the granularity
at which `assignGenome()` operates: gene values are written into existing
`PackedCollection` slots, and the next render reads them.

- **Runtime form:** a 1-element `PackedCollection` (read with `cp(scale)` or
  `p(scale)` to get a `Producer<PackedCollection>`).
- **Mutability after build:** by mutating the underlying `PackedCollection`;
  no recompile.
- **PDSL today:** `weight` parameters already work this way (the caller
  supplies a `PackedCollection` reference). `scalar` parameters do **not** —
  they are inlined as Java `double`s at build time.
- **Examples in MixdownManager:**
  - `volumeAdjustmentScale`, `mainFilterUpAdjustmentScale`,
    `mainFilterDownAdjustmentScale`, `reverbAdjustmentScale` — 1-element
    `PackedCollection` fields mutated by setters
    (`MixdownManager.java:301-330`). These are passed into
    `automation.getAggregatedValue(..., p(scale), ...)` and used as multipliers
    on the gene contribution. In Java they participate in tier-4 producers, but
    the *scale value itself* changes only between renders.
  - `transmission` chromosome resolved by `assignGenome()` — its
    `[channels, channels]` `PackedCollection` is already wired into PDSL today
    via the `weight` parameter `transmission` of `mixdown_efx_bus`. This is
    tier 2 by construction: changing the genome rewrites the matrix in place.
  - `wetFilter` FIR coefficients (`FixedFilterChromosome`) — likewise tier 2,
    consumed via `wet_filter_coeffs: weight` in `mixdown_efx_bus`.

The `weight` parameter mechanism in PDSL already covers tier 2 *for collections*.
The hole is for `scalar` parameters that today inline as `double`s.

#### Tier 3: Beat-rate / structural-time variation

Values that change at musical-time boundaries — every beat, every measure,
every section — but not every audio sample. In the cell graph this maps to
gene values sampled by musical position via `Gene.valueAt(position)` lookups
and `factor()` adapters that re-evaluate at structural boundaries rather than
per sample.

- **Runtime form:** a `Producer<PackedCollection>` that depends on a
  position/section index, evaluated when the index advances.
- **Mutability after build:** continuous within a render (driven by an
  external position input).
- **PDSL today:** not directly representable. The caller can sample a
  `Gene.valueAt(position)` per render in Java and pass the result as a tier-2
  `weight` parameter, but PDSL does not know about the position dimension or
  the gene structure.
- **Examples in MixdownManager / EfxManager:**
  - `delay.valueAt(i, 0)` (`MixdownManager.java:667`) — the delay-time gene
    for delay layer `i`. The numeric delay time changes at structural
    boundaries because the active gene allele and the beat duration change.
    The Java code wraps this in `AdjustableDelayCell` and reads it as a
    `Producer` per sample, but the underlying gene value is structural.
  - `delayGene(...)` constructed from `wetInSimple.valueAt(channelIdx)`
    (`MixdownManager.java:672`) — gene values per pattern channel, sampled
    per render but selected by channel index.
  - `EfxManager.delayLevels.valueAt(channel, k)`
    (`EfxManager.java:223,226,311,313`) — wet, feedback, decision, cutoff
    gene values per channel, indexed by channel, evaluated as needed.
  - `wetFilter` HP/LP cutoffs computed from
    `delayLevels.valueAt(channel, 3).getResultant(c(1.0))` — gene-driven
    cutoff that the FIR coefficients are derived from at build time.

A clean tier-3 design would let PDSL read a gene-indexed value per
forward pass; an "OK enough" design lets the caller sample the gene per
render and feed the result in as a tier-2 weight.

#### Tier 4: Sample-rate automation

Values that change every audio sample. In Java this is what
`AutomationManager.getAggregatedValue(...)` and `clock`-driven
`TemporalFactor` outputs return: `Producer<PackedCollection>` expressions that
incorporate `clock.frame()` or `clock.time(sampleRate)` and so are recomputed
every sample by the compiled kernel.

- **Runtime form:** a `Producer<PackedCollection>` that depends on the audio
  clock, evaluated per sample inside the compiled kernel.
- **Mutability after build:** continuous; encoded in the producer graph.
- **PDSL today:** not representable — there is no PDSL primitive that accepts a
  `Producer` as a scalar argument.
- **Examples in MixdownManager:**
  - `automation.getAggregatedValue(mainFilterUpSimple.valueAt(...), p(mainFilterUpAdjustmentScale), -40.0)`
    (`MixdownManager.java:519-522`) — HP cutoff, sample-rate.
  - `automation.getAggregatedValue(reverbAutomation.valueAt(...), p(reverbAdjustmentScale), 0.0)`
    (`MixdownManager.java:561-565`) — reverb amplitude, sample-rate.
  - Tier-4 multiplier on master LP cutoff at
    `MixdownManager.java:720-724`.
  - `toAdjustmentGene(clock, sampleRate, p(scale), simple, channelIdx).valueAt(0).getResultant(c(1.0))`
    (`MixdownManager.java:527-530`) — when `enableAutomationManager` is false,
    the *gene* is tier 2/3 but the resulting `Factor` is sample-rate because
    `clock` is a `TimeCell` advanced every sample. So the live cell-graph
    behaviour is tier 4 even in the "no automation" branch.
  - In `EfxManager.apply()`:
    `automation.getAggregatedValue(delayAutomation.valueAt(channel), null, 0.0)`
    (`EfxManager.java:237-238`) — sample-rate feedback modulation on the
    delay wet path.

### 11.2 Per-Parameter Tier Map

The following table walks through the parameters that drive
`MixdownManager.createCells()` / `createEfx()` and `EfxManager.apply()`
and assigns each to the tier at which it actually varies in the live system.

| Parameter | Java site | Tier | Notes |
|-----------|-----------|------|-------|
| Per-channel HP cutoff (main filter up), `enableAutomationManager=true` | `MixdownManager.java:519-523` | 4 | `automation.getAggregatedValue(...)` × 20 kHz |
| Per-channel HP cutoff (main filter up), `enableAutomationManager=false` | `MixdownManager.java:527-530` | 4 (via clock-driven `TemporalFactor`); gene values are 2/3 | `toAdjustmentGene(clock, sampleRate, ...)` |
| Per-channel volume | `MixdownManager.java:535-537,580-606` | 4 | Same `toAdjustmentGene(clock, ...)` pattern |
| Reverb factor, automation on | `MixdownManager.java:561-567` | 4 | `automation.getAggregatedValue(reverbAutomation, ...)` × `reverbLevel` |
| Reverb factor, automation off | `MixdownManager.java:568-572` | 2/3 | `reverb.valueAt(channelIdx, 0)` — gene-driven, no clock; rate depends on whether `valueAt` is wrapped in a clock-driven factor at the call site |
| Wet filter coefficients (FIR) | `MixdownManager` constructor + `wetFilter` | 2 | `FixedFilterChromosome`-derived; rebuilt when genome changes, tier 2 across renders |
| Wet send level (`v · wetFilter` chain) | `MixdownManager.java:585-586,605-606` | 4 | `v.apply(i)` is the tier-4 volume factor; chained with the fixed wet filter |
| Per-delay-layer delay time | `MixdownManager.java:666-668` | 4 (driven by `clock` via `AdjustableDelayCell`); gene is 2/3 | `delay.valueAt(i, 0).getResultant(c(1.0))` |
| Per-delay-layer dynamics | `MixdownManager.java:663-668` | 4 (clock-driven via `toPolycyclicGene`); gene is 2/3 | `df.apply(i).getResultant(c(1.0))` |
| `delayGene` per-channel routing | `MixdownManager.java:670-672` | 2/3 | Re-derived from `wetInSimple.valueAt(channelIdx)`; rebuilt per render |
| `transmission` matrix | `MixdownManager.java:258-260,677` | 2 | Gene-driven, mutable across renders; PDSL `weight` parameter |
| `wetOut` (delay output gain) | `MixdownManager.java:262,677` | 2 | Same as `transmission` |
| Master LP cutoff, `enableMasterFilterDown=true` | `MixdownManager.java:720-724` | 4 (clock-driven) | `toAdjustmentGene(clock, ...)` × 20 kHz |
| `*AdjustmentScale` (volume, filter-up, filter-down, reverb) | `MixdownManager.java:301-330` | 2 | 1-element `PackedCollection`s mutated by setters |
| EFX delay time | `EfxManager.java:226-231` | 4 (clock-driven via `AdjustableDelayCell`); gene is 2/3 | `delayTimes.valueAt(channelIdx, 0).getResultant(c(1.0))` × beatDuration |
| EFX delay levels (filter cutoff for wet path) | `EfxManager.java:312-313` | 2/3 | `delayLevels.valueAt(channelIdx, 3)` — used at filter-build time, not in the audio path |
| EFX wet feedback level | `EfxManager.java:222-223,245` | 2/3 | `delayLevels.valueAt(channelIdx, 0/1)` |
| EFX delay automation modulation, `enableAutomation=true` | `EfxManager.java:236-241` | 4 | `automation.getAggregatedValue(...)` |

Two patterns recur:

1. **Tier 2/3 gene + tier-4 clock factor → tier 4 in practice.** Many "gene"
   values reach the audio path through `toAdjustmentGene(clock, ...)` or
   `AdjustableDelayCell`, which evaluate per sample. The gene contribution
   itself is structural (tier 2/3), but the final factor seen by the audio
   kernel is tier 4. Faithfully migrating these to PDSL therefore requires
   tier-4 support, not tier-3.
2. **Pure tier-2 mutables.** `transmission`, `wetOut`, the FIR coefficient
   `weight` parameters, and the four `*AdjustmentScale` 1-element collections
   are tier-2 only. PDSL's `weight` mechanism already handles the
   collection-shaped ones. The `*AdjustmentScale` collections are the only
   fields that today have to be inlined as `double` literals in PDSL because
   they appear as scalar arguments (not `weight`s).

### 11.3 What PDSL Needs at Each Tier Above Tier 1

#### Tier 2: mutable scalars

**Idea.** Treat a `scalar` parameter as a 1-element `PackedCollection` slot, the
same way `weight` already works. Inside the compiled kernel, the value is read
via `cp(slot)` rather than emitted as a `double` literal; mutating the slot in
Java between renders is enough to change the value the kernel sees on the
next render.

**API surface.** Two plausible shapes:

1. *Implicit*: leave the syntax (`name: scalar`) unchanged and change the
   binding from "double literal" to "1-element `PackedCollection`-backed
   producer." Caller's args map starts accepting either a `Number` (auto-wrap)
   or a `PackedCollection`. Smallest possible change.
2. *Explicit*: introduce `name: mutable_scalar` (or reuse `weight` for
   scalars by relaxing the shape check). Preserves backwards compatibility for
   anyone relying on a scalar literal being inlined as a constant by the
   compiler.

The framework's expression compiler already constant-folds 1-element
`PackedCollection` reads when the slot is known to be constant during a single
forward pass, so option 1 should not regress fixed-parameter kernels.

**Edge cases.**
- `int` parameters used in compile-time shape arithmetic (e.g.,
  `signal_size`, `channels`, `filter_order`, `delay_samples`) genuinely need
  to be build-time only; they index into shapes and loop bounds. Promoting
  these to mutable would break the entire shape system. Tier 2 only applies to
  `scalar` (continuous-valued) parameters.
- Integer-shaped `delay_samples` is the awkward case: it is logically scalar
  but used as an array offset. Live `MixdownManager` makes it a `Producer`
  via `AdjustableDelayCell`, so this is more naturally tier 4.

**Effort estimate.** Days, with one caveat: the change has to preserve
constant-folding for the existing fixed-parameter PDSL files. The risk is in
the compiler, not in the parser or interpreter.

#### Tier 3: structural-time scalars

**Idea.** Allow a scalar argument to be a `gene(chromosome, position)` lookup
where `position` is supplied per forward pass. The lookup resolves the gene
allele at the given musical position; the resulting 1-element
`PackedCollection` is read by the kernel.

**Two designs.**

1. *PDSL-aware gene*: introduce `gene(chromosome, position_input)` as a
   first-class scalar argument. PDSL knows about chromosomes and positions; the
   caller supplies a `Chromosome<PackedCollection>` and a position
   `Producer<PackedCollection>`.
2. *Caller samples*: do nothing in PDSL beyond tier 2. The caller (Java)
   samples the gene at each forward pass (or at each structural boundary) and
   updates the tier-2 mutable scalar accordingly. PDSL is unaware that the
   value has structural-time semantics.

Design 2 is strictly less expressive but does not require PDSL to learn
chromosome semantics. Design 1 is more invasive and probably overengineered
unless several other domains end up needing the same indexed lookup.

**Edge cases.** The live cell-graph behaviour is mostly tier 4 (because the
genes feed into clock-driven factors), so a tier-3 design that ignores the
sample-rate dimension would not fully reproduce the live audio. Tier 3 is
useful only for parameters that genuinely *should* be evaluated structurally
rather than per sample.

**Effort estimate.** Design 1 is weeks: AST nodes, interpreter wiring, a
position input convention. Design 2 is a few days *given that tier 2 is in
place*. Recommendation: skip native tier 3 entirely (see Section 11.5).

#### Tier 4: sample-rate automation

**Idea.** Allow primitives to accept a `Producer<PackedCollection>` argument
in place of a `scalar` literal. The producer is free to depend on `clock` or
any other input and is recomputed per sample by the compiled kernel.

**API surface.** The cleanest spelling is to mark the argument with an
`automation(...)` tag at the layer header (so the interpreter knows to
extract a `Producer` from the args map rather than a `Number`):

```pdsl
layer mixdown_main_automated(channels: int, signal_size: int,
                             hp_cutoff: automation(scalar),
                             volume:    automation(scalar),
                             sample_rate: scalar, filter_order: scalar)
                          -> [1, signal_size] {
    for each channel {
        highpass(hp_cutoff, sample_rate, filter_order)
        scale(volume)
    }
    sum_channels()
}
```

Internally the interpreter binds `hp_cutoff` and `volume` to
`Producer<PackedCollection>` values from the args map; `callHighpass` /
`callScale` accept a producer or a literal at the same call site. The same
treatment applies to `delay`, where the `delay_samples` argument becomes a
producer (subsuming Section 12.2).

**Edge cases.**
- Every primitive that takes a scalar today needs to learn to accept a
  producer. That includes `highpass`, `lowpass`, `scale`, `biquad` (five
  scalars), `delay`, `lfo` (two scalars), `fir` indirectly. The plumbing
  cost is per-primitive, not one-time.
- The compiler must be willing to embed a producer that reads from `clock`
  inside the per-sample kernel. The expression compiler already does this
  for `weight` reads inside ML layers, but the audio primitives' current
  implementations may inline scalar arguments as `double`s at build time.
- Backwards compatibility: existing fixed-parameter PDSL files call
  primitives with literal arguments. The new acceptance must not regress
  them.

**Effort estimate.** Multi-week, not days. The Section 12.1 estimate of
"small — days of work" is wrong: it underestimates the producer-vs-literal
plumbing required across every primitive that takes a scalar. A realistic
budget is 1–3 weeks, with most of it spent in `PdslInterpreter` and the
audio primitive implementations rather than in the parser or AST.

### 11.4 Interaction Effects

Several real `MixdownManager`/`EfxManager` situations combine tiers. The
proposals above need to compose cleanly across them.

1. **Tier 2 scale × tier 4 envelope.** `automation.getAggregatedValue(gene, p(scale), offset)`
   passes a tier-2 `*AdjustmentScale` `PackedCollection` *into* a tier-4
   automation producer. In PDSL this means the automation producer that the
   caller supplies for a tier-4 argument may itself reference a tier-2
   `weight`. Tier 4 cleanly composes with tier 2 because both reduce to
   `Producer<PackedCollection>` from the kernel's point of view.
2. **Tier 2/3 gene × tier 4 clock factor.** Already discussed: in
   `toAdjustmentGene(clock, sampleRate, scale, gene, channel)`, the gene
   contribution is structural and the clock contribution is per-sample. The
   composed value reaching the audio kernel is tier 4, so PDSL needs tier 4
   even to express the "no automation" branch faithfully.
3. **Tier 2 transmission × tier 4 wet send.** `mself(fi(), transmission, fc(wetOut.valueAt(0)))`
   uses a tier-2 routing matrix and a tier-2 wet-out gain. The current
   `mixdown_efx_bus` already accepts both as `weight` parameters, so this
   composition works today.
4. **Tier 4 delay time × tier 2 buffer slot.** `AdjustableDelayCell` reads
   a tier-4 delay-time producer and writes into a tier-2 (per-render) delay
   buffer. The delay buffer is already a PDSL `state` (a `weight`-shaped
   collection); the delay time becomes tier 4 once tier 4 is supported.
5. **Tier 4 envelope multiplier on tier 2 baseline.** `c(20000).multiply(v)`
   in the HP/LP cutoff computation uses a tier-2 baseline (the
   `c(20000)` constant; would generalise to a tier-2 mutable max cutoff)
   multiplied by a tier-4 envelope `v`. Trivially supported once both
   tiers exist.

The main load-bearing observation is that **tier 4 subsumes tier 2 from the
kernel's point of view**: a tier-2 mutable scalar is a degenerate tier-4
producer that does not depend on `clock`. Any compiler change that
makes tier-4 producers work for scalar arguments also makes tier-2 mutable
scalars work for the same arguments, for free.

### 11.5 Migration Order

Naive reading: do tier 2, then tier 3, then tier 4. That is the wrong
order.

Recommended order:

1. **Tier 4 first** (`automation(producer)` plus the variable-delay
   primitive). It is the largest single unlock — it covers every
   `@Disabled` test in Section 12 that depends on time-varying scalars
   (Capabilities A and B). It is also the tier that the live
   `MixdownManager` actually uses end-to-end.
2. **Tier 2 falls out of tier 4 for free** for `scalar` arguments: a
   constant 1-element `PackedCollection` is the degenerate tier-4 case. The
   only additional work after tier 4 lands is making the `scalar` parameter
   binding accept a `PackedCollection` (or `Number`) on the args-map side and
   wrap it in a `cp(slot)` producer when the kernel reads it.
3. **Skip native tier 3.** Do not introduce `gene(chromosome, position)` as
   a first-class PDSL construct. Two reasons: (a) the live audio path is
   tier 4 anyway because of `clock`-driven factors, so a tier-3 PDSL
   construct would not faithfully reproduce the live behaviour; (b) the
   caller can sample the gene per render in Java and feed the result in
   as a tier-2 weight, which is already supported. PDSL learning chromosome
   semantics is a feature with one consumer and a strong workaround.
4. **Tier 1 stays as-is** for `int` parameters used in shape and loop-bound
   arithmetic (`signal_size`, `channels`, `filter_order`,
   build-time `delay_samples` if any). These cannot be promoted because the
   shape system needs them as Java `int`s.

This order ties directly to Section 12. The `@Disabled` tests labelled
A (sample-rate automation) and B (variable delay time) both unlock at the
same time when tier 4 lands; the existing "small — days" estimate should be
revised upward (Section 11.3 / Section 12.1). C (rectangular routing), D
(heterogeneous fan-out), and E (`delay_network`) are independent of the
tier work and can be scheduled separately.

A new gap to acknowledge: there is no `@Disabled` test today that covers
the **tier 2 mutable-scalar** case in isolation (e.g., a `mixdown_main_bus`
where `volume` and `hp_cutoff` are 1-element `PackedCollection`s mutable
across forward passes but constant within one). Tier 2 falls out of tier 4
naturally, so the gap is acceptable, but a future test should be added once
tier 4 is implemented to assert the constant-producer case.

---

## 12. Current Limitations — Captured as Tests

This section is the index of the `@Disabled` tests produced alongside the
`mixdown_manager.pdsl` rendition. Each entry names the test, the
`MixdownManager` row it targets (see Section 10), the capability that is
currently missing, and a rough size estimate for implementing it.

### 12.1 Tier 4 (sample-rate automation) — `automation(producer)` primitive

**What's needed:** a PDSL primitive that accepts a
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

**Size estimate:** 1–3 weeks (see Section 11.3, Tier 4). The earlier
"small — days of work" reading underestimated the producer-vs-literal
plumbing required across every primitive that takes a scalar (`highpass`,
`lowpass`, `scale`, `biquad`, `delay`, `lfo`, etc.). The parser-level work is
small; the interpreter and primitive-implementation work is not.

**Tier 2 (mutable scalars) is a sub-capability of this:** once a primitive
accepts a `Producer<PackedCollection>` argument, a 1-element constant
`PackedCollection` can be supplied as the degenerate (constant-in-time)
case. A separate `@Disabled` test for tier 2 in isolation is not yet in
the suite; once tier 4 lands, one should be added covering a mutable-scalar
`mixdown_main_bus`.

| Test class.method | Targets (Section 10 row) | Notes |
|-------------------|--------------------------|-------|
| `MixdownManagerPdslTest.testMixdownManagerAutomatedHighpass` | 1, 2 | Per-channel HP with time-varying cutoff |
| `MixdownManagerPdslTest.testMixdownManagerAutomatedVolume` | 3, 5 | Per-channel volume with time-varying gain |
| `MixdownManagerPdslTest.testMixdownManagerAutomatedLowpass` | 28 | Master LP with time-varying cutoff |

### 12.2 Variable delay time — tier 4 specialization

**What's needed:** a `delay(...)` primitive that accepts a time-varying
sample count (a `Producer<PackedCollection>`) rather than a compile-time
integer. Equivalent to `AdjustableDelayCell`, which takes
`Producer<PackedCollection>` for both delay time and dynamics.

This is the same plumbing as Section 12.1 applied to the `delay`
primitive's `delay_samples` argument. It lands together with tier 4,
not separately.

**Size estimate:** included in the tier-4 estimate. Touches the delay
kernel: the read-pointer arithmetic that today uses an `int` becomes a
`CollectionProducer` op.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerVariableDelayTime` | 17 | Per-delay-layer delay time driven by a time-varying producer |

### 12.3 Rectangular routing

**What's needed:** a `route(matrix)` primitive that accepts
`[rows, cols]` matrices where rows ≠ cols (fan in N channels to M ≠ N
outputs). Today `MultiChannelDspFeatures.routeBlock` is square: its
output shape is `[channels, signalSize]` with the same `channels` as
the input.

**Size estimate:** small — probably a day of work. The implementation is
a straightforward generalization of the existing `routeBlock` loop
nest. Independent of the tier work in Section 11.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerRectangularRoute` | 19, 20 | N efx channels fan-routed to M delay layers (M ≠ N) |

### 12.4 Heterogeneous fan-out

**What's needed:** a PDSL construct that takes N sub-blocks, applies them
all to the same input, and produces N channels on output — one per
sub-block. Equivalent to `CellList.branch(IntFunction<Cell>...)`.
`concat_blocks` does this for tensor concatenation, but the output shape
is `[sum_of_channels, signalSize]` rather than `[N, per-block-output]`.

**Size estimate:** small — could reuse the `fanOutBlock` + `perChannelBlock`
plumbing with a different set of sub-blocks per channel. Probably a day
of work, possibly less. Independent of the tier work in Section 11.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerHeterogeneousBranch` | 10, 11 | 3-way branch `{main, main·wet_filter, reverb_factor}` |

### 12.5 `delay_network(...)` primitive

**What's needed:** a PDSL primitive equivalent to
`org.almostrealism.audio.filter.DelayNetwork`: a multi-tap feedback reverb
network with selectable tap counts, tap delays, and feedback gains.

**Size estimate:** large — probably weeks of work. `DelayNetwork` is a
composition of primitives, but it has its own per-tap state and
feedback topology that doesn't reduce cleanly to the existing
`delay_feedback_bank` pattern (the tap-spacing pattern is irregular and
the feedback-to-input path is internal, not exposed as a `route`
matrix). Likely requires either (a) porting `DelayNetwork` to a
compile-time expansion of `delay_feedback_bank` with irregular tap
spacing, or (b) a dedicated `delay_network(num_taps, base_delay, feedback)`
primitive.

| Test class.method | Targets | Notes |
|-------------------|---------|-------|
| `MixdownManagerPdslTest.testMixdownManagerReverbPath` | 24, 25, 9 | Reverb bus + wet factor; depends on both tier 4 (Section 12.1) and the `delay_network` primitive |

---

## Appendix A: Current PDSL Files

| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |
| `pdsl/audio/efx_channel.pdsl` | EFX channel layers: `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix` |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown layers: `mixdown_main` (HP→scale→LP), `mixdown_channel` (full with wet/delay) |
| `pdsl/audio/delay_feedback_bank.pdsl` | Multi-channel delay bank: `delay_feedback_bank` (fan_out → per-channel delay → route → sum_channels) |
| `pdsl/audio/mixdown_manager.pdsl` | Top-level mixdown: `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_master` |

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
