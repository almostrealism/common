# PDSL for Audio DSP Processing

**Status:** Exploratory design. No implementation has been started.  
**Related:** `docs/plans/AUDIO_SCENE_REDESIGN.md`

---

## The Core Idea

The AudioScene redesign plan identifies per-channel kernel splitting and copy chain
elimination as the two main performance improvements needed. Both require analyzing and
restructuring the signal processing graph. Right now, that graph is defined imperatively
— Java methods in `MixdownManager`, `EfxManager`, and `AutomationManager` call `CellList`
fluent methods to wire up cells at construction time.

If the same pipeline were defined **declaratively** in PDSL, the framework would have a
structured description of the graph it could analyze, partition, optimize, and recompile —
rather than having to reverse-engineer it from the output cell structure. The redesign goals
fall out naturally from having a declarative pipeline definition.

This document explores what that would look like, what it would cost, and whether it is
the right direction.

---

## Part 1: The Current DSP Pipeline

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
`Producer<PackedCollection>` values that are consumed as time-varying parameters
by `EfxManager` and `MixdownManager`.

### How Parameters Flow

Every parameter in the DSP pipeline is genome-driven:

- **EfxManager:** 3 chromosomes — delay times (choice gene), delay levels
  (wet/feedback/filter-decision/cutoff), delay automation (6-locus envelopes)
- **MixdownManager:** 8+ chromosomes — volume, high-pass filter, low-pass filter,
  delay times, delay dynamics, transmission matrix, wet send levels, reverb levels
- **AutomationManager:** 1 chromosome with 6-locus envelopes that produce
  `sin()`/`pow()` modulation curves

At construction time these genes are wired into Producer expressions. At runtime the
genes resolve to float values that drive the kernel.

### Key Characteristics

1. **State is per-sample:** Delay line buffers, filter state (biquad coefficients, IIR
   history) — this state must be read and written on every sample.
2. **Feedback exists within a channel:** Delay lines feed back into themselves. The
   Cell/CachedStateCell model handles this via double-buffering: each cell writes to its
   `outValue`, which is pushed to downstream cells on the next tick.
3. **Cross-channel routing exists:** `MixdownManager` has a `transmission` chromosome
   that routes delay sends between channels (send from channel A into channel B's delay
   input). This is a true data dependency between channels.
4. **Parameters are mutable without recompilation:** `assignGenome()` updates gene values
   in the existing `PackedCollection` without rebuilding the cell graph. The compiled
   kernel reads the same memory locations; only the values change.

---

## Part 2: Current PDSL Capabilities

PDSL (`PdslLoader` + `PdslInterpreter`) is a declarative DSL for building neural network
computation graphs. It compiles to `Block` and `Model` objects, which are backed by the
same Producer/CollectionProducer framework that underlies the cell graph.

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
data model_weights {
    weight_ih: weight
    hidden_size: int
    w_r = range(weight_ih, [hidden_size, input_size], 0)
    w_z = range(weight_ih, [hidden_size, input_size], hidden_size * input_size)
}
```

**Current primitive set:** `dense`, `embedding`, `rmsnorm`, `softmax`, `silu`,
`sigmoid`, `gelu`, `relu`, `tanh_act`, `slice`, `reshape`, `range`, `lerp`,
`rope_rotation`, `attention`, `transformer`, `feed_forward`.

### What PDSL Cannot Express Today

1. **Stateful operations:** There is no concept of "this layer has persistent state
   that must be updated each call." RNNs are supported via GRU cell `.pdsl` files, but
   the hidden state is passed as an explicit input/output — the DSL does not own the state.
2. **Feedback loops:** No construct for "the output of stage N feeds back into stage N
   at the next time step."
3. **Time-domain primitives:** No delay lines, no sample rate awareness, no temporal
   indexing.
4. **Conditional execution:** No "skip this stage if the input is zero."
5. **In-place operations:** All operations produce new tensors. There is no `inplace`
   modifier.

---

## Part 3: Cell vs Block/Model — Semantic Comparison

Both `Cell` and `Block`/`Model` route data through processing stages, but with
fundamentally different execution models.

| Dimension | Cell / CellList | Block / Model |
|-----------|-----------------|---------------|
| **Data flow** | Push-based: upstream ticks and pushes to downstream receptors | Pull-based Producer graph: downstream pulls from upstream when evaluated |
| **State** | Owns persistent state (`cachedValue`, `outValue`); state updated each tick | Stateless by default; state passed explicitly as inputs/outputs |
| **Compilation** | `CellList.tick()` compiles an `OperationList` from all temporals | `Model.compile()` compiles a `CompiledModel` from the Producer graph |
| **Execution unit** | `Temporal.tick()` — one call per sample/frame | `Runnable forward(input)` — one call per forward pass |
| **Time** | Intrinsic: `TimeCell` flows through the graph as a clock signal | Extrinsic: caller controls when forward is called |
| **Feedback** | Natural: CachedStateCell double-buffers output so downstream can read the previous value | Artificial: caller must pass previous output as a new input |
| **Mutability** | Parameters live in `PackedCollection` slots updated by `assignGenome()` | Parameters in `StateDictionary`; can be swapped but requires rebuild for shape changes |
| **Composition** | Fluent: `cellList.f(...).d(...).m(...).sum()` | Fluent: `block.andThen(nextBlock).andThen(receptor)` |
| **Granularity** | Per-sample within a buffer (cell graph ticks once per sample) | Per-buffer (forward pass over entire tensor at once) |

The key difference for audio DSP: **feedback** and **per-sample state**. The cell model
was designed for these; the block model was designed for feedforward ML inference.

---

## Part 4: What PDSL-Defined DSP Would Look Like

### The Per-Channel Signal Path as PDSL

Here is a sketch of what `EfxManager`'s per-channel processing might look like in PDSL:

```pdsl
// Parameters passed from genome
data efx_params {
    delay_time_beats: scalar      // delayTimes gene
    wet_level: scalar             // delayLevels[0]
    feedback_level: scalar        // delayLevels[1]
    filter_select: scalar         // delayLevels[2] — HP vs LP decision
    filter_cutoff: scalar         // delayLevels[3]
    automation_phase_short: scalar
    automation_phase_long: scalar
    automation_phase_main: scalar
    automation_mag_short: scalar
    automation_mag_long: scalar
    automation_mag_main: scalar
    beat_duration: scalar         // runtime: seconds per beat

    filter_coeffs = fir_select(filter_select, filter_cutoff, 40)
}

layer efx_channel(params: efx_params, delay_state: state) {
    // Optional FIR filtering
    fir(params.filter_coeffs)

    // Delay with automation-modulated mix
    product(
        { delay(params.delay_time_beats * params.beat_duration, delay_state); scale(params.wet_level) },
        { automation(params.automation_phase_short, params.automation_mag_short,
                     params.automation_phase_long, params.automation_mag_long,
                     params.automation_phase_main, params.automation_mag_main) }
    )

    // Feedback
    accum_blocks(
        { identity() },
        { scale(params.feedback_level) }
    )
}
```

And the MixdownManager main path:

```pdsl
layer mixdown_channel(hp_cutoff: scalar, lp_cutoff: scalar,
                       volume: scalar, filter_state: state) {
    highpass(hp_cutoff, filter_state.hp)
    scale(volume)
    // (wet path branching omitted for brevity)
    lowpass(lp_cutoff, filter_state.lp)
}
```

This is illustrative — several constructs used here (`state`, `delay`, `fir`,
`highpass`, `lowpass`, `automation`, `identity`) do not exist in the current PDSL.

### The Full Scene as a PDSL Model

At the highest level, the entire `AudioScene.getCells()` pipeline could be expressed as:

```pdsl
model audio_scene(channels: int, buffer_size: int) {
    // Phase 1: Per-channel source + effects
    for ch in 0..channels {
        add(pattern_source(ch))
        add(efx_channel(efx_params.ch, efx_state.ch))
    }
    // Phase 2: Mix bus (cross-channel routing)
    add(mix_bus(transmission_matrix, delay_states))
    // Phase 3: Aggregation
    add(aggregate_stereo(volume_levels))
}
```

---

## Part 5: New PDSL Primitives Needed

To express audio DSP in PDSL, the following new built-in operations are needed:

### Signal Processing

| Primitive | Description | State? |
|-----------|-------------|--------|
| `biquad(b0, b1, b2, a1, a2)` | IIR biquad filter (one stage) | Yes — 2 samples of history |
| `highpass(cutoff, q)` | Parametric high-pass (wraps biquad) | Yes |
| `lowpass(cutoff, q)` | Parametric low-pass (wraps biquad) | Yes |
| `fir(coefficients)` | FIR filter (convolution) | Yes — `order` samples of history |
| `delay(time_seconds)` | Fixed or variable delay line | Yes — circular buffer |
| `sample_interp(source, frame_pos)` | Sample interpolation at non-integer position | No |

### Modulation

| Primitive | Description | State? |
|-----------|-------------|--------|
| `lfo_sin(freq_hz)` | Sinusoidal LFO | Yes — phase accumulator |
| `lfo_poly(phase, magnitude)` | Polynomial trend envelope | No |
| `automation(phases, mags)` | Three-tier AutomationManager envelope | No |
| `envelope_follower(attack, release)` | Peak/RMS envelope detector | Yes |

### Time and Rate

| Primitive | Description | State? |
|-----------|-------------|--------|
| `sample_clock()` | Current sample position | No — injected |
| `beat_clock(bpm)` | Current beat position | No — injected |
| `rate_convert(ratio)` | Sample rate conversion | Yes — interpolation state |

### Control

| Primitive | Description |
|-----------|-------------|
| `gate(threshold)` | Zero output if input below threshold (silence detection) |
| `mix(a, b, level)` | Weighted mix of two inputs |
| `feedback(level)` | Self-feedback with gain |
| `identity()` | Pass-through (useful for branching) |

### State Management

The most significant gap: PDSL has no concept of persistent inter-call state. For audio
DSP, every stateful primitive (delay lines, filter history, LFO phase) needs a state
container that persists between buffer calls.

Two design options:

**Option A: Explicit state parameters**
```pdsl
layer biquad_filter(b0: scalar, b1: scalar, b2: scalar,
                    a1: scalar, a2: scalar,
                    x1: state, x2: state, y1: state, y2: state) { ... }
```
State is passed in and out explicitly. The caller owns the state buffers. This matches
how GRU hidden state works in the current PDSL.

**Option B: Named state blocks**
```pdsl
state filter_state {
    x1: sample
    x2: sample
    y1: sample
    y2: sample
}
layer biquad_filter(coeffs: scalar[5], s: filter_state) { ... }
```
A `state` block defines the shape of persistent state. The runtime allocates and
manages the buffers; the layer references them by name. This is cleaner for complex
state like delay lines (which have variable length).

Option B is more ergonomic for audio DSP but requires a new concept in the PDSL
runtime — a state registry that allocates and tracks persistent buffers. Option A
can be implemented incrementally without runtime changes.

---

## Part 6: Cell and Block/Model Convergence

### The Underlying Unity

Both `Cell` and `Block` ultimately compile to the same thing: a `CollectionProducer`
graph that the framework compiles to native code. The difference is in how that graph
is structured and when it is evaluated.

A `Cell` composes operations into an `OperationList` that is ticked per-sample. A
`Block` composes operations into a `CompiledModel` that is called per-buffer. Both
use the same `PackedCollection` memory, the same hardware backends, and the same
`Producer` expression system.

The convergence question is: **can we have one system** where everything — audio DSP
and ML inference — is defined as a PDSL computation graph and executed through the
same pipeline?

### What Would Convergence Look Like?

The key insight is that the Cell model's per-sample iteration is equivalent to a `for`
loop over the buffer that the Block model could also express:

```
Cell model:
  for (int i = 0; i < bufferSize; i++) {
    output[i] = biquad(input[i], state);
    state = update_state(input[i], output[i]);
  }

Block model (hypothetical):
  for i in 0..buffer_size {
    output[i] = biquad(input[i], filter_state[i])
    // state update is implicit in biquad primitive
  }
```

A unified system would:
1. **Define both DSP and ML as PDSL graphs.** DSP graphs have `state` parameters;
   ML graphs are stateless (or pass state explicitly).
2. **Compile both through the same `Block`→`CompiledModel` path.** The buffer loop
   is either explicit (ML) or implicit (DSP — "apply this operation to every sample").
3. **Dispatch through the same `OperationList`/`Loop` mechanism.** The DSP pipeline
   becomes a series of `Block.forward()` calls, same as the ML inference path.

### What Makes This Hard

- **Feedback loops in DSP:** A biquad filter at sample `i` reads the output at
  samples `i-1` and `i-2`. This is a loop-carried dependency — you cannot vectorize
  naively, and you cannot express it as a simple tensor operation. The Block model
  has no concept of this. The Cell model handles it via double-buffering, but that
  is what we're trying to eliminate.

  The correct approach for IIR filters in a GPU context is either: (a) process
  serially on CPU (no vectorization), (b) use a parallel IIR algorithm (more
  operations but parallelizable), or (c) approximate with FIR (no feedback, but
  finite impulse response). Option (c) is already used in `EfxManager` (40-tap FIR).

- **Variable-length state:** A delay line's length depends on the delay time
  parameter, which is genome-driven and can change between renders. The Block model
  allocates tensors with fixed shapes at compile time.

- **Real-time constraints:** The Cell model is designed for real-time audio — it
  processes exactly one buffer per tick, with a hard deadline. The Block model is
  designed for offline ML inference — no real-time constraints. Convergence needs
  the DSP path to retain real-time guarantees.

---

## Part 7: Performance Implications

If the DSP pipeline is expressed in PDSL, the framework gains the ability to analyze it
as a graph — the same analyses that are currently being designed for the AudioScene
redesign become easier because the graph is declared, not inferred.

### Per-Channel Splitting as Graph Partitioning

In the PDSL model, per-channel splitting is straightforward:

```pdsl
model audio_scene(channels: int) {
    for ch in 0..channels {
        add(efx_channel(params[ch], states[ch]))  // independent per channel
    }
    add(mix_bus(transmission))  // depends on all channels
    add(aggregate())
}
```

The `for` loop body is clearly independent per iteration (except where the
`transmission` matrix creates cross-channel dependencies). The compiler can
partition this into `channels` independent kernels plus one aggregation kernel
automatically — no human graph analysis required.

### Copy Elimination as Dead-Code Analysis

In the Cell model, copy elimination requires detecting that a `CachedStateCell`
has only one reader and eliminating the intermediate buffer. This is an instance
of dead-copy elimination — hard to detect in an imperative cell graph.

In a PDSL graph, the intermediate tensors are declared as part of the expression
tree. Standard producer graph optimization (common subexpression elimination,
dead-value elimination) can eliminate intermediate tensors directly at the
expression level before kernel compilation.

### Silent Channel Skipping as Conditional Compilation

In the current Cell model, detecting a silent channel requires inspecting the
`PatternAudioBuffer` state at runtime, then conditionally skipping the Loop. This
is a runtime branch around a compiled kernel.

In a PDSL model:
```pdsl
layer conditional_channel(source: weight, active: bool) {
    gate(active)  // zero output if inactive — no processing needed
    efx_channel(...)
}
```
The `gate` primitive allows the kernel to short-circuit before any DSP work. Or,
with enough compiler support, inactive channels could be detected at compile time
and their kernels omitted entirely.

---

## Part 8: Risks and Unknowns

### Risk 1: IIR Filter Feedback (High Impact, Hard to Solve)

IIR biquad filters have loop-carried dependencies that fundamentally cannot be
parallelized across samples. The current EfxManager uses FIR filters (40 taps)
precisely because FIR has no feedback — it can be expressed as a convolution and
executed in parallel.

MixdownManager's main-path filters (`highPassCoefficients`, `lowPassCoefficients`)
appear to be IIR. If they are, they cannot be expressed as PDSL tensor operations
without either (a) keeping them as CellList cells outside the PDSL graph, or
(b) converting them to FIR approximations.

**Resolution path:** Audit which filters in the pipeline are IIR vs FIR. Anything
IIR is a boundary condition for PDSL DSP.

### Risk 2: Variable Delay Line Length (Medium Impact, Solvable)

Delay line length is genome-driven (`delay.valueAt(i, 0)` returns a beat-duration
multiple). The length can change between renders when the genome changes. PDSL
shapes must be fixed at compile time.

**Resolution path:** Fix delay line length at the maximum possible value (based on
gene range bounds); use a read offset pointer to implement variable delay within
that fixed buffer. This is how hardware delay lines work. The `data` block in PDSL
could declare the delay buffer with a fixed maximum size.

### Risk 3: Cross-Channel Transmission (Medium Impact, Solvable)

`MixdownManager`'s `transmission` chromosome routes audio between channels. This
creates a data dependency that prevents per-channel splitting from being truly
independent.

**Resolution path:** Keep the transmission routing as a separate `mix_bus` phase
that runs after all per-channel kernels complete. The transmission matrix becomes
an explicit aggregation step between the per-channel and final-mix phases.

### Risk 4: Real-Time Audio Constraints (Medium Impact, Design Question)

Real-time audio requires deterministic latency — no garbage collection pauses, no
dynamic allocation during the hot path, no blocking operations. The Block/Model
compilation path allocates intermediate tensors and may trigger GC.

PDSL compilation produces `CompiledModel` objects that, once compiled, run without
Java allocation. But the compilation step itself is expensive and cannot happen in
the real-time audio thread.

**Resolution path:** Compile the PDSL model in a background thread during scene
initialization. Swap the compiled model into the audio path only when compilation
completes (double-buffered model replacement).

### Risk 5: Automation Envelopes in PDSL (Low Impact, Straightforward)

`AutomationManager` produces `Producer<PackedCollection>` values that are consumed
as parameters. Since Producers are already first-class in PDSL (they are the `scalar`
parameter type), automation can be expressed directly:

```pdsl
layer automated_volume(base_volume: scalar, automation: scalar) {
    scale(base_volume * automation)
}
```

The `automation` parameter is a `Producer` computed by `AutomationManager.getAggregatedValue()`.
No new PDSL primitives are needed for this.

### Risk 6: PDSL Extension Scope (Low Impact, Manageable)

The list of new primitives in Part 5 is substantial. Implementing all of them before
the DSP migration can begin would delay the work significantly. A phased approach:

1. **Phase A — FIR filters and delays only.** `fir`, `delay`, `mix` — the operations
   already used by `EfxManager`. This covers the hot path.
2. **Phase B — Modulation.** `automation`, `lfo_sin` — straightforward since they
   are already expressed as Producers.
3. **Phase C — IIR filters.** Only after Phase 1 is working and the IIR feedback
   question is resolved.
4. **Phase D — State management.** The named `state` block design, enabling clean
   expression of all stateful primitives.

---

## Summary: Is PDSL the Right Direction?

**Yes, directionally.** Expressing the DSP pipeline declaratively enables the framework
to analyze, partition, and optimize it in ways that the current imperative CellList
construction does not. The AudioScene redesign goals — per-channel splitting, copy
elimination, silent channel skipping — all become tractable problems in a declarative
graph model.

**But not immediately.** The Cell model has concrete capabilities that PDSL lacks: IIR
feedback, variable-length state, per-sample granularity. Rushing to migrate the entire
pipeline to PDSL before those gaps are filled would produce a worse system, not a
better one.

**The recommended path:**
1. Proceed with the Channel-Scoped CellLists redesign (Option A in the AudioScene plan)
   as the immediate performance improvement.
2. In parallel, design and implement the PDSL state management extension (`state` blocks,
   stateful primitives) as a prerequisite for DSP expression.
3. Migrate `EfxManager` first — it already uses FIR (no feedback), making it the
   simplest candidate for PDSL expression.
4. Evaluate `MixdownManager` migration after `EfxManager` is working, resolving the
   IIR filter question in the process.
5. Use the declarative graph structure to drive the next round of kernel optimization
   (automatic per-channel splitting, copy elimination from the expression tree).

The long-term vision — everything from audio DSP to ML inference defined as PDSL
computation graphs, compiled to the same native kernels — is coherent and achievable.
The path there runs through the AudioScene redesign, not around it.

---

## Appendix: Current PDSL Files Reference

| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |

These serve as concrete examples of the current PDSL syntax and composition patterns.
The GRU cell is the closest analog to stateful audio DSP: hidden state is passed
explicitly, gates control information flow. The main difference is that GRU state is
a vector updated once per forward call, while filter state is scalars updated once
per sample.
