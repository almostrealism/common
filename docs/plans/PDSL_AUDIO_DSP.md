# PDSL for Audio DSP Processing

**Status:** Phase A (FIR primitives) complete. Phase B (`state` block syntax + stateful primitives) complete. `mixdown_channel.pdsl` complete. Approach D foundations (`pipeline` keyword + `PdslTemporalBlock`) starting now.
**Related:** `docs/plans/AUDIO_SCENE_REDESIGN.md`

---

## 1. The Goal and Why

The AudioScene pipeline — `MixdownManager`, `EfxManager`, `AutomationManager` — is wired imperatively in Java. The framework cannot analyze the graph, partition it across channels, or optimize it automatically because the graph is hidden inside Java method calls.

If the same pipeline were defined **declaratively in PDSL**, the framework would have a structured description of the graph it could analyze, partition, and recompile. Per-channel kernel splitting, copy elimination, and silent channel skipping — the three main goals of the AudioScene redesign — all become tractable problems in a declarative graph model.

The architecture is Approach D: a `pipeline` top-level PDSL keyword that compiles to `PdslTemporalBlock`, a class implementing both `Temporal` (for CellList integration) and `Cell<PackedCollection>` (for Block→CellList adapter use). This is being built incrementally: minimal single-channel pipeline first, multi-channel constructs next iteration.

Approaches A (source/sink declarations), B (PDSL→CellList compilation), and C (PdslChannelAdapter Block-wrapper) were considered and rejected. A was structurally close to D but required reading past the keyword to understand the compilation target. B requires every primitive to have two implementations forever. C covers only the trivial single-channel path and cannot express cross-channel routing (`mself`, `route`) — the defining feature of `MixdownManager` — which would remain Java forever. Full design exploration is preserved in commit `4f436a74fa` if needed.

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

1. **Self-driving pipelines:** No construct for "read input from a named source, run, push
   output to a named receptor, fire once per buffer as a `Temporal`." This is the `pipeline`
   keyword being built in Approach D.
2. **Multi-channel iteration:** No `for each channel` or `fan_out(N)` construct.
   Cross-channel operations like `mself` must remain Java CellList code.
3. **Conditional execution:** No `gate` or `if` construct.

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

### 6C: Proposed PDSL Syntax for Multi-Channel Composition

Six constructs cover the multi-channel vocabulary. Each is shown with its CellList
compilation target. These are scheduled for the second iteration of Approach D (after
single-channel pipelines are working).

**Construct 1: `channels: N` — Channel multiplicity declaration**

Declares that a pipeline operates on N independent channels. Body operations apply
per-channel unless explicitly marked as cross-channel.

```pdsl
pipeline delay_bank(channels: int, delay_time: scalar) {
    input  channel_audio[channels] -> [1, signal_size]
    output master_mix
    // ...
}
```

Compiles to: `PdslTemporalBlock` that internally maintains a `channels`-element `CellList`.

**Construct 2: `for each channel { }` — Per-channel application**

Applies the body to each channel independently. State accessed inside is automatically
indexed per channel: `delay_state.buffer[channel]`.

```pdsl
for each channel {
    fir(filter_coeffs)
    delay(delay_time, delay_state.buffer[channel], delay_state.head[channel])
    scale(wet_level)
}
```

Compiles to: `sources.f(i -> fir(...)).d(i -> delay(i, ...)).m(i -> scale(...))`.

**Construct 3: `sum_channels()` — Collapse N channels to 1**

```pdsl
for each channel { fir(filter_coeffs) }
sum_channels()    // N-channel → 1-channel
```

Compiles to: `cellList.sum()` — the `SummationCell` pattern.

**Construct 4: `fan_out(N)` — Replicate 1 channel to N**

```pdsl
fan_out(num_experts)
for each channel { dense(expert_weights[channel]); silu() }
sum_channels()
```

Compiles to: N-element `CellList` via `branch(f0, f1, ..., fN-1)` with N identical functions.

**Construct 5: `route(matrix)` — Cross-channel routing via transmission matrix**

```pdsl
route(delay_routing_gene)    // [N channels] × [N, M gene] → [M channels]
route(transmission_gene)     // [M channels] × [M, M gene] → [M channels] (feedback)
```

Compiles to: `.m(fi(), destinations, gene)` for forward routing;
`.mself(fi(), gene, passthrough)` for feedback. Feedback vs forward is inferred from whether
input and output channel lists are the same.

**Construct 6: `tap(i)` — Select a single channel**

```pdsl
tap(0) { fir(treble_coeffs) }
sum_channels()
```

Compiles to: `branch()` pattern: `cells.branch(stemCell, passThroughCell)[1]`.

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

## 7. The Architecture: `pipeline` Keyword + `PdslTemporalBlock`

### The Core Design

A `pipeline` top-level declaration joins `layer` and `model` in the PDSL grammar.
The `pipeline`/`layer` distinction mirrors the `CellList`/`Cell` distinction:
- A `layer` is a passive transformation node, driven by its caller.
- A `pipeline` is a self-driving processing unit, declaring its own input source and
  output sink.

```pdsl
pipeline mixdown_main(hp_cutoff: scalar, volume: scalar,
                      lp_cutoff: scalar, sample_rate: scalar,
                      filter_order: scalar) {

    input  channel_audio -> [1, signal_size]   // reads from this named source each tick
    output master_output                        // pushes result to this receptor

    // Signal path — identical grammar to a layer body
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    lowpass(lp_cutoff, sample_rate, filter_order)
}
```

A reader sees the complete flow at a glance: input source, processing chain, output receptor.

### `PdslTemporalBlock`

The compiled output of a `pipeline` definition. Implements:
- `Temporal` — so it can be added to a `CellList` via `cells.addRequirement(pipeline)` and
  will tick once per buffer alongside other temporals.
- `Cell<PackedCollection>` — so it can also participate in the Block→CellList adapter path
  (`getForward()`), giving maximum flexibility.

`PdslTemporalBlock.tick()` implementation:
1. Reads `cp(attachedInputBuffer)` — the source bound via `attachInput()`
2. Runs the filter chain as a `CollectionProducer` graph (same Producer machinery as today)
3. Pushes the result to the attached output receptor via `attachedOutput.push(...)`

Integration in Java:

```java
// In MixdownManager.createCells() — replaces hp()/scale()/lp() chain
PdslTemporalBlock mixdown = loader.buildPipeline(program, "mixdown_main", shape, args);
mixdown.attachInput("channel_audio", patternAudioBuffer.getBuffer());
mixdown.attachOutput("master_output", masterReceptor);
cells.addRequirement(mixdown);   // fires once per buffer
```

### New Parser/AST Elements Required

- `pipeline` keyword in `PdslToken.Type`, `PdslLexer`, and `PdslParser.parseDefinition()`
- `input <name> -> <shape>` declaration inside a pipeline body
- `output <name>` declaration inside a pipeline body
- `PdslNode.PipelineDef` — structurally similar to `LayerDef`, adds `inputDecl` and
  `outputDecl` fields alongside parameters and body
- `PdslLoader.buildPipeline(PdslNode.Program, String, TraversalPolicy, Map<String, Object>)`

### Tick Granularity

`CellList.tick()` has a `tickLoopCount` that collapses per-sample iterations into a single
native call. A `PdslTemporalBlock` fires once per buffer: it is added via `addRequirement()`
which causes it to tick once at the end of each buffer cycle, after all per-sample cells
have run. This is the correct granularity for per-buffer DSP (highpass → scale → lowpass).

### First Iteration Scope

- **No `channels: N` header parameter.** Single-channel pipelines only.
- **No `for each channel { }`.** Pipelines are linear chains.
- **No `route()`, `sum_channels()`, `fan_out()`, `tap()`.** Multi-channel constructs come
  next iteration.
- **No `state` block changes.** State blocks already work in `layer` definitions;
  pipelines use them identically.

The pipeline body grammar is initially identical to a `layer` body — same primitives, same
composition. The difference is the file-level `input`/`output` declarations and the
compilation target (`PdslTemporalBlock` instead of `Block`).

---

## 8. CellList Integration

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

The migration path for **`PdslTemporalBlock`** pipelines:

```java
// Replaces the hand-wired main path in MixdownManager.createCells()
PdslTemporalBlock pipeline = loader.buildPipeline(program, "mixdown_main", shape, args);
pipeline.attachInput("channel_audio", patternAudioBuffer.getBuffer());
pipeline.attachOutput("master_output", masterReceptor);
cells.addRequirement(pipeline);
// Cross-channel transmission, reverb path, DelayNetwork remain as Java CellList code
```

---

## 9. Status and Next Steps

### What Is Complete

- **FIR primitives** (`fir`, `scale`, `identity`, `lowpass`, `highpass`) — implemented in
  `PdslInterpreter.java`; `efx_channel.pdsl` demonstrates them end-to-end.
- **`state` block syntax** — `STATE` token, `StateDef` AST node, parser support, interpreter
  population.
- **Stateful primitives** (`biquad`, `delay`, `lfo`) — implemented using `CollectionProducer`
  operations, no `setMem()`/`toDouble()`.
- **`mixdown_channel.pdsl`** — expresses `MixdownManager`'s main path (HP → scale → LP)
  and full per-channel path (with wet/delay) as PDSL layers.
- **Test coverage** — `PdslAudioDspTest` (13 tests), `MixdownChannelPdslTest` (8 tests),
  `PdslAudioDemoTest` (2 tests including WAV output). All pass at depth 2.
- **WAV output** — `results/pdsl-audio-dsp/` contains dry multitone, lowpass-filtered,
  delay-echo, and wet/dry mix WAV files from `PdslAudioDemoTest`.

### What Remains as Gaps in `mixdown_channel.pdsl`

- **IIR vs FIR:** `MixdownManager` uses `AudioPassFilter` (IIR biquad) via
  `CellFeatures.hp()`/`lp()`. The PDSL `highpass`/`lowpass` primitives use
  `MultiOrderFilter` (FIR). Frequency responses differ near the cutoff — validated by
  energy-level assertions, not exact sample match.
- **Cross-channel transmission:** `MixdownManager.createEfx()` uses `mself(fi(), transmission, ...)`.
  This requires multi-channel PDSL constructs (Construct 5 in Section 6C) and remains Java.
- **Reverb path:** `DelayNetwork` is multi-tap feedback assembled from Java cell primitives.
  No PDSL equivalent yet.
- **Automation envelopes:** `AutomationManager.getAggregatedValue()` produces time-varying
  Producer values computed in Java. These are passed as `scalar` parameters — the caller
  computes the current value and supplies it. This is the correct design.

### What's Next: Approach D Foundations

**Deliverable 1 (now):** This plan revision.

**Deliverable 2 (validation tests):** Before writing production `pipeline` keyword code,
validate the assumptions Approach D depends on:

1. *Per-buffer granularity* — A `CellList` with `setTickLoopCount(N)` has a requirement
   added via `addRequirement(temporal)`. Verify the requirement's `tick()` fires exactly
   once per outer tick, not N times.
2. *State persistence across `Temporal.tick`* — A stateful PDSL block (e.g., biquad) wrapped
   as a `Temporal`. Tick twice with the same input; verify the second tick's output reflects
   state from the first (not a cold-started filter).
3. *Block→Temporal minimal adapter* — A minimal `Temporal` wrapper around a
   `CompiledModel.forward()` call. Verify input flows in and output comes out, with state
   surviving between ticks.

**Deliverable 3 (minimal `pipeline` keyword):** Implement:
- `pipeline` keyword in the parser alongside `layer` and `model`
- `input <name> -> <shape>` and `output <name>` declarations inside a pipeline body
- `PdslNode.PipelineDef` AST node
- `PdslTemporalBlock` implementing `Temporal` and `Cell<PackedCollection>`
- `PdslLoader.buildPipeline(...)` entry point
- `attachInput(String name, PackedCollection buffer)` and
  `attachOutput(String name, Receptor<PackedCollection> receptor)` on `PdslTemporalBlock`

Validation: a `mixdown_main_pipeline.pdsl` file defining the HP/scale/LP path as a `pipeline`.
Built via `loader.buildPipeline(...)`, ticked through a `CellList`, output matches the `layer`
version's `forward()`. WAV file produced by a CI test.

**After Approach D foundations:**
- Add `channels: N` header + `for each channel { }` to enable multi-channel pipelines
- Add `route()` and `sum_channels()` to cover the `mself`/`m` patterns
- Migrate `MixdownManager.createEfx()` — the highest-value target once multi-channel lands

---

## 10. Risks

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

### Risk 3: Cross-Channel Transmission (Medium Impact, Solvable in Phase 2)

`MixdownManager`'s `transmission` chromosome routes audio between channels, preventing
per-channel splitting from being fully independent. Resolution: keep transmission routing
as a separate `mix_bus` phase running after all per-channel kernels complete. Expressible
via `route(transmission_gene)` in Section 6C Construct 5 once multi-channel PDSL lands.

### Risk 4: Real-Time Audio Constraints (Medium Impact, Design Question)

Real-time audio requires deterministic latency. PDSL compilation produces `CompiledModel`
objects that, once compiled, run without Java allocation. Resolution: compile the PDSL
model in a background thread during scene initialization; swap the compiled model into the
audio path only when compilation completes (double-buffered model replacement).

### Risk 5: `pipeline` Keyword Scope Creep (Low Impact, Manageable)

The full multi-channel vocabulary (`channels`, `for each channel`, `route`, `sum_channels`,
`fan_out`, `tap`) doubles the parser surface area. Resolution: this iteration implements
only single-channel `pipeline` with `input`/`output` declarations. Multi-channel constructs
are additive and do not change existing `layer` or `pipeline` definitions without `channels`.

---

## Appendix A: Current PDSL Files

| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |
| `pdsl/audio/efx_channel.pdsl` | EFX channel layers: `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix` |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown layers: `mixdown_main` (HP→scale→LP), `mixdown_channel` (full with wet/delay) |

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

**Phase C (`pipeline` keyword — Approach D Deliverable 3):**
A WAV file produced by running `mixdown_main_pipeline` (a `pipeline` definition) through a
`CellList.tick()` cycle. The file must be produced by a test that runs in CI. The pipeline
must include at least the HP→scale→LP chain from `mixdown_main` and the output must be
audibly different from the dry input.

### How to Produce a .wav File

Wire the PDSL pipeline output into `WavFile.write()` (see `engine/audio`) with a test buffer
as input. Test class must extend `TestSuiteBase`. The `.wav` file does not need to be committed;
the test that generates it must pass in CI.

### Why This Matters

If the PDSL pipeline cannot produce audio, the workstream has not achieved its goal regardless
of what the tests assert. Every agent working on this branch should ask: *"Can I listen to the
output?"* If the answer is no, the work is not done.
