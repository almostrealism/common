# PDSL for Audio DSP Processing

**Status:** Phase A (FIR primitives: `fir`, `scale`, `lowpass`, `highpass`) complete. Phase B (`state` block syntax and AST) partially complete. State-aware primitives (`biquad`, `delay`, `lfo`) in progress.  
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
`lowpass`, `highpass`.

### What PDSL Cannot Express Today

1. **Stateful operations:** There is no concept of "this layer has persistent state
   that must be updated each call." However, see Part 5A — the `data` block mechanism
   already provides the storage infrastructure; only the write-back step is missing.
2. **Feedback loops:** No construct for "the output of stage N feeds back into stage N
   at the next time step." This is distinct from the state storage problem (see Part 6).
3. **Time-domain primitives:** No delay lines, no sample rate awareness, no temporal
   indexing — though `fir`, `lowpass`, and `highpass` are already implemented.
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
}

// Persistent DSP state — updated each buffer call
state efx_state {
    delay_buffer: weight           // circular buffer, max_delay_samples elements
    delay_head: weight             // 1-element: write pointer into delay_buffer
}

layer efx_channel(params: efx_params, s: efx_state) {
    // Optional FIR filtering
    lowpass(params.filter_cutoff, sample_rate, filter_order)

    // Delay with automation-modulated mix
    product(
        { delay(params.delay_time_beats * params.beat_duration, s.delay_buffer, s.delay_head);
          scale(params.wet_level) },
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
// Persistent filter state for main-path IIR filters
state mixdown_state {
    hp_history: weight    // 4-element: [x1, x2, y1, y2] for high-pass biquad
    lp_history: weight    // 4-element: [x1, x2, y1, y2] for low-pass biquad
}

layer mixdown_channel(hp_cutoff: scalar, lp_cutoff: scalar,
                       volume: scalar, s: mixdown_state) {
    biquad_hp(hp_cutoff, s.hp_history)
    scale(volume)
    // (wet path branching omitted for brevity)
    biquad_lp(lp_cutoff, s.lp_history)
}
```

This is illustrative — several constructs used here (`state`, `delay`, `biquad_hp`,
`biquad_lp`, `automation`, `identity`) do not exist in the current PDSL.

---

## Part 5: State Management — The `data` Block Is Already the Answer

### 5A: What the `data` Block Already Provides

The original version of this plan described state management as "the most significant
gap" and listed it as Phase D — last to be implemented. That framing was based on the
assumption that PDSL lacks any persistent storage mechanism. It does not: the `data`
block already provides exactly the right infrastructure.

**What `data` blocks do today (from `PdslInterpreter.evaluateDataDef`):**

1. Accept `PackedCollection` inputs by reference — the caller passes in an existing
   `PackedCollection` (created in Java) and the data block holds a reference to it.
2. Derive zero-copy sub-views via `range()` — `source.range(shape, offset)` returns a
   `PackedCollection` backed by the same native memory, just at a different offset and
   shape.
3. Make both the originals and sub-views available throughout the layer body without
   explicit parameter threading.
4. The caller retains the `PackedCollection` reference between calls, so it persists
   across buffer boundaries automatically.

This is precisely what DSP state needs:

- A **delay line buffer** is a `PackedCollection` of `max_delay_samples` floats that
  persists between calls. The caller creates it once, passes it to the `data`/`state`
  block on every `buildLayer` call. The delay primitive reads from it and writes back to it.
- **Filter history** (biquad: x1, x2, y1, y2) is a 4-element `PackedCollection` that
  persists between calls. Identical structure to a 4-element weight tensor.
- **LFO phase** is a 1-element `PackedCollection` that gets incremented each call.

The ONLY difference between ML weights and DSP state: weights are **read-only** during
execution; state is **read-write**. The write step needs a primitive that updates the
state `PackedCollection` using `CollectionProducer` operations — following the same
pattern as the existing non-stateful primitives in `PdslInterpreter.java`.

### 5B: What Is Actually Missing

The real gap is small and mechanical:

**1. A `state` block keyword (syntax only)**

Currently, `data` blocks declare both read-only weights and everything else uniformly.
A `state` keyword — syntactically identical to `data` but conveying write-intent —
clarifies to both the programmer and the runtime that these `PackedCollection` entries
will be mutated during execution. No change to `PackedCollection`, `PdslLoader`, or
the environment mechanism is needed. The `PdslInterpreter` only needs to recognize the
`state` keyword and populate the environment identically to how it handles `data`.

**2. State-aware primitives that update their state argument**

The existing primitives (`dense`, `fir`, `rmsnorm`, etc.) receive `PackedCollection`
arguments and read from them. State-aware primitives (`delay`, `biquad`, `lfo_sin`)
additionally write their updated state back. The implementation follows the same
`CollectionProducer` pattern as `callFir`, `callLowpass`, and `callHighpass` in
`PdslInterpreter.java` — study those methods before writing state-aware primitives.
No new memory model, no new infrastructure. Just: read state as Producer inputs,
compute, write state back as Producer outputs.

That is the complete extension required.

### 5C: Concrete Mapping — Each Stateful Primitive to `state` Blocks

#### Biquad Filter (IIR, 2 poles / 2 zeros)

The standard biquad difference equation: `y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]`

State: `x[n-1]`, `x[n-2]`, `y[n-1]`, `y[n-2]` — four floats.

```pdsl
// State block — 4-element PackedCollection, caller creates and owns it
state biquad_state {
    history: weight    // [x1, x2, y1, y2] as a 4-element PackedCollection
}

// Layer using biquad with explicit state
layer biquad_filter(b0: scalar, b1: scalar, b2: scalar,
                    a1: scalar, a2: scalar,
                    s: biquad_state) {
    biquad(b0, b1, b2, a1, a2, s.history)
}
```

Implementation of `biquad(b0, b1, b2, a1, a2, history)`:
Follow the pattern established by `callFir`, `callLowpass`, and `callHighpass` in
`PdslInterpreter.java`. Those methods return `Block` objects composed from
`CollectionProducer` operations. Study those implementations. The `biquad` primitive
must use the same approach: return a `Block` that reads the history state as a
`CollectionProducer` input, applies the biquad difference equation as Producer
operations, and writes updated state back via Producer assignments — no `setMem()`
calls and no `toDouble()` calls in the implementation.

Note on IIR parallelism: the loop-carried dependency in biquad means samples cannot
be parallelized across each other within a buffer. This is a vectorization concern,
not a state-storage concern — the state block approach handles it correctly by
processing samples serially inside the primitive, exactly as the current Cell model
does. Alternatively, the primitive can use a known parallel IIR algorithm (transposed
direct-form II, look-ahead, etc.) for GPU execution.

#### Delay Line (Circular Buffer)

A delay line with maximum length `max_samples` requires:
- A circular buffer of `max_samples` floats
- A write pointer (head index) — 1 integer/float

```pdsl
// State block — caller pre-allocates with known maximum delay length
state delay_state {
    buffer: weight    // max_delay_samples-element PackedCollection, zero-initialized
    head: weight      // 1-element PackedCollection: current write position [0, max_delay_samples)
}

// Optionally expose sub-views for inspection (using existing range() support)
// read_pos = range(head, [1], 0)   — not needed, just illustrative

layer delay_line(delay_samples: scalar, s: delay_state) {
    delay(delay_samples, s.buffer, s.head)
}
```

Implementation of `delay(delay_samples, buffer, head)`:
Follow the pattern established by `callFir`, `callLowpass`, and `callHighpass` in
`PdslInterpreter.java`. Those methods return `Block` objects composed from
`CollectionProducer` operations. Study those implementations. The `delay` primitive
must use the same approach: compose the circular buffer read (delayed sample at
`(head - delay_samples + max) % max`), the buffer write, and the head-pointer
advancement all as `CollectionProducer` operations — no `toDouble()` calls and no
`setMem()` calls in the implementation.

The maximum delay length is fixed at block-build time (the `PackedCollection` has a
fixed size, determined by the caller based on gene range bounds). Variable delay within
that maximum is handled by the read offset calculation — exactly how hardware delay
lines work. Risk 2 from the original plan (variable-length state) resolves here: fix
the maximum at compile time, use a pointer for variable read position.

#### LFO Phase Accumulator

An LFO needs only a single float: the current phase in `[0, 2π)`.

```pdsl
state lfo_state {
    phase: weight    // 1-element PackedCollection, initialized to 0.0
}

layer lfo_sin(freq_hz: scalar, sample_rate: scalar, s: lfo_state) {
    lfo(freq_hz, sample_rate, s.phase)
}
```

Implementation of `lfo(freq_hz, sample_rate, phase)`:
Follow the pattern established by `callFir`, `callLowpass`, and `callHighpass` in
`PdslInterpreter.java`. Those methods return `Block` objects composed from
`CollectionProducer` operations. Study those implementations. The `lfo` primitive
must use the same approach: compose `sin(phase)` and the phase-increment
`(phase + 2π * freq_hz / sample_rate) % 2π` as `CollectionProducer` operations,
writing the updated phase back as a Producer assignment — no `toDouble()` calls
and no `setMem()` calls in the implementation.

This is the most trivial state case — a 1-element `PackedCollection` that gets
incremented. The `data` block already supports 1-element scalars as `PackedCollection`
entries; `state` adds the write-back via Producer operations.

### 5D: The Full Picture — `data` vs `state` Semantics

| | `data` block | `state` block |
|--|-------------|---------------|
| **Storage** | `PackedCollection` reference, caller-owned | `PackedCollection` reference, caller-owned |
| **Derivations** | `range()` sub-views, computed at build time | Same |
| **Access during execution** | Read-only (passed to primitives as weights) | Read-write (passed to state-aware primitives) |
| **Lifecycle** | Persists as long as caller holds the reference | Same |
| **Interpreter change** | None needed | Recognize `state` keyword; populate env identically to `data` |
| **Runtime change** | None | None — state `PackedCollection` is updated via Producer operations inside state-aware primitives |

The only implementation cost is:
1. Add `state` as a parser keyword (one line in the parser switch)
2. Add `StateDef` AST node (structurally identical to `DataDef`, different class for type-checking)
3. Populate `StateDef` entries the same way `DataDef` entries are populated
4. Implement state-aware primitives (`biquad`, `delay`, `lfo`) that update their state
   arguments using `CollectionProducer` operations, following the pattern of `callFir`,
   `callLowpass`, and `callHighpass` in `PdslInterpreter.java`

### 5E: Comparison to the Original Two Options

The original plan proposed:

**Option A: Explicit state parameters** — pass state as individual `state` parameters on
each layer definition. Matches how GRU hidden state works today (caller-managed).

**Option B: Named state blocks** — a `state` block defines the shape; the runtime
allocates and manages buffers.

The analysis above shows that **Option B is almost free** given the existing `data` block
infrastructure. The "runtime allocates and manages buffers" step does not require a new
state registry — the caller (Java code that calls `buildLayer`) already manages the
`PackedCollection` references it passes into `data` blocks. For state, the same caller
creates the state `PackedCollection` objects (zero-initialized) and passes them in on
each call, exactly as it passes weight `PackedCollection` objects today.

Option A is still valid for trivial state (a single GRU-style hidden vector passed
explicitly), but Option B is the right design for complex state (delay buffers, biquad
history, LFO phase) because it keeps the state structure with the layer definition rather
than spreading it across the call site.

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
    output[i] = biquad(input[i], filter_state)
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

  **Crucially, IIR feedback is orthogonal to the state-storage problem.** The `state`
  block correctly handles IIR by encapsulating the serial sample loop inside the
  primitive implementation. The PDSL graph declares "apply biquad with this state";
  the Java primitive handles the serialization. This is the same separation of
  concerns that makes `fir()` work: the PDSL layer says `fir(coefficients)` and the
  Java implementation handles the convolution loop.

- **Variable-length state:** A delay line's length depends on the delay time
  parameter, which is genome-driven and can change between renders. The Block model
  allocates tensors with fixed shapes at compile time. **Resolution:** as described
  in Part 5C, fix the delay buffer at `max_delay_samples` and use a pointer for
  variable read position. The maximum is computable from gene range bounds.

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

## Part 8: New PDSL Primitives Needed

To express audio DSP in PDSL, the following new built-in operations are needed.
**Note: `fir`, `lowpass`, and `highpass` are already implemented** on the current
branch. State-aware primitives are tractable given the `state` block extension.

### Signal Processing

| Primitive | Description | State? | Notes |
|-----------|-------------|--------|-------|
| `biquad(b0,b1,b2,a1,a2,state)` | IIR biquad filter (one stage) | Yes — 4 floats | Serial loop in primitive |
| `biquad_hp(cutoff, state)` | Parametric high-pass (wraps biquad) | Yes | Computes coefficients at build time |
| `biquad_lp(cutoff, state)` | Parametric low-pass (wraps biquad) | Yes | Computes coefficients at build time |
| `fir(coefficients)` | FIR filter (convolution) | No | **Already implemented** |
| `delay(time, buffer, head)` | Circular delay line | Yes — buffer + pointer | Fixed max size |
| `sample_interp(source, frame_pos)` | Sample interpolation at non-integer position | No | |

### Modulation

| Primitive | Description | State? |
|-----------|-------------|--------|
| `lfo(freq_hz, sample_rate, phase)` | Sinusoidal LFO | Yes — 1 float |
| `lfo_poly(phase, magnitude)` | Polynomial trend envelope | No |
| `automation(phases, mags)` | Three-tier AutomationManager envelope | No |
| `envelope_follower(attack, release, state)` | Peak/RMS envelope detector | Yes |

### Scaling and Mix

| Primitive | Description | Notes |
|-----------|-------------|-------|
| `scale(factor)` | Multiply each element by a scalar factor | **Already implemented** |
| `mix` | Weighted mix of wet/dry paths | **No new primitive needed** — use `accum_blocks({ scale(dry) }, { wet_chain; scale(wet) })`. See Part 10. |
| `identity()` | Pass-through (useful for branching) | **Already implemented** — used as dry-path arm of `accum_blocks` |
| `gate(threshold)` | Zero output if input below threshold | |
| `lowpass(cutoff, sampleRate, filterOrder)` | Low-pass FIR filter | **Already implemented** |
| `highpass(cutoff, sampleRate, filterOrder)` | High-pass FIR filter | **Already implemented** |

### Time and Rate

| Primitive | Description | State? |
|-----------|-------------|--------|
| `sample_clock()` | Current sample position | No — injected |
| `beat_clock(bpm)` | Current beat position | No — injected |

---

## Part 9: Risks and Unknowns

### Risk 1: IIR Filter Feedback (Medium Impact, Well-Understood)

~~High Impact, Hard to Solve~~ — re-assessed.

IIR biquad filters have loop-carried dependencies that cannot be naively parallelized
across samples. This is a vectorization concern, not a state or architecture problem.
The `state` block approach handles it correctly: the `biquad` primitive processes
samples serially inside its Java implementation, updating the 4-float history
`PackedCollection` as it goes. The PDSL layer graph remains clean.

For GPU execution, the primitive can use a parallel IIR algorithm (transposed
direct-form II, look-ahead techniques). This is an optimization choice that does not
affect the PDSL layer API or the state block design.

The current `EfxManager` uses 40-tap FIR filters (no feedback), which are already
expressible via the existing `fir()` primitive. IIR filters appear in `MixdownManager`'s
main path — these should be audited before migration.

### Risk 2: Variable Delay Line Length (Low Impact, Resolved)

~~Medium Impact, Solvable~~ — resolved by design.

Delay line length is genome-driven. As described in Part 5C, the resolution is:
1. At block-build time, determine `max_delay_samples` from gene range bounds.
2. Allocate the `state` block's `buffer` `PackedCollection` at that fixed maximum.
3. Use a read pointer offset (stored in the `head` state entry) for variable delay.

This is identical to how hardware delay lines work and requires no change to PDSL's
fixed-shape compilation model.

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

The list of new primitives in Part 8 is substantial. Implementing all of them before
the DSP migration can begin would delay the work significantly. A phased approach:

1. **Phase A — FIR filters (done).** `fir`, `lowpass`, `highpass`, `scale` are already
   implemented. The `efx_channel.pdsl` file on this branch demonstrates them.
2. **Phase B — `state` block extension.** Add the `state` keyword to the parser and
   AST (trivial). Implement `delay` and `biquad` primitives. This covers the full
   stateful path.
3. **Phase C — Modulation.** `automation`, `lfo` — straightforward since they are
   already expressed as Producers.
4. **Phase D — IIR optimization.** Parallel IIR algorithms for GPU execution, only
   after Phase B is working.

---

## Summary: Is PDSL the Right Direction?

**Yes, directionally.** Expressing the DSP pipeline declaratively enables the framework
to analyze, partition, and optimize it in ways that the current imperative CellList
construction does not. The AudioScene redesign goals — per-channel splitting, copy
elimination, silent channel skipping — all become tractable problems in a declarative
graph model.

**The state problem is smaller than it appeared.** The original plan treated state
management as a fundamental architectural gap (Phase D — last to implement). The
correct assessment is: the `data` block already provides the storage model; a `state`
keyword and state-aware primitives are a small mechanical extension, not a new concept.
The IIR feedback concern is a vectorization optimization question, not a blocker.

**Current status and recommended path:**

- **Phase A — FIR primitives (complete).** `fir`, `lowpass`, `highpass`, `scale` are
  implemented in `PdslInterpreter.java`. The `efx_channel.pdsl` file on this branch
  demonstrates them working end-to-end.
- **Phase B — `state` block extension (partially complete).** The `state` keyword,
  `StateDef` AST node, parser support, and interpreter population are implemented.
  State-aware primitives (`callBiquad`, `callDelay`, `callLfo`) are in progress — they
  must follow the `CollectionProducer` pattern of `callFir`/`callLowpass`/`callHighpass`.
- **Phase C — End-to-end audio output.** Wire a complete PDSL-defined DSP pipeline
  through `AudioScene` and produce a `.wav` file. This is the deliverable that proves
  the system works (see Deliverables section).
- **Phase D — Modulation.** `automation`, `lfo` — straightforward since they are
  already expressed as Producers.
- **Phase E — IIR optimization.** Parallel IIR algorithms for GPU execution, only
  after Phase C is working.

1. Proceed with the Channel-Scoped CellLists redesign (Option A in the AudioScene plan)
   as the immediate performance improvement.
2. Complete state-aware primitives (`biquad`, `delay`, `lfo`) using `CollectionProducer`
   operations. Study `callFir`, `callLowpass`, `callHighpass` as the canonical examples.
3. Migrate `EfxManager` first — it already uses FIR (Phase A is done), making it the
   simplest candidate for PDSL expression. Produce audio output to verify.
4. Evaluate `MixdownManager` migration after `EfxManager` is working, resolving the
   IIR filter question in the process.
5. Use the declarative graph structure to drive the next round of kernel optimization
   (automatic per-channel splitting, copy elimination from the expression tree).

The long-term vision — everything from audio DSP to ML inference defined as PDSL
computation graphs, compiled to the same native kernels — is coherent and achievable.
The path there is shorter than the original plan suggested: the state storage problem
is already solved; only the write-back semantics are missing.

---

## Part 10: CellList ↔ SequentialBlock Convergence — Code Observations

The following analysis is grounded in the actual code at the commit when it was written.
No abstract speculation — only observations that can be verified by reading the listed
source files.

### 10A: What the Two Systems Share

Both `CellList` (`engine/audio`) and `SequentialBlock` (`domain/graph`) route data
through processing stages built from the same building block: `Cell<PackedCollection>`
(`domain/graph/Cell.java`). The `Cell` interface defines one operation:

```java
Supplier<Runnable> push(Producer<PackedCollection> protein);
void setReceptor(Receptor<PackedCollection> r);
```

Everything else — how cells are ordered, how they are composed, whether state is
preserved, how they compile to native code — differs.

**Where they diverge:**

| Dimension | CellList | SequentialBlock |
|-----------|----------|-----------------|
| **Composition** | Parent/child hierarchy; roots push to roots, parents tick before children | Sequential receptor wiring: `last.setReceptor(next)` in `SequentialBlock.add()` |
| **Activation** | Push-driven via `tick()` → `OperationList` returned from `CellList.tick()` | Pull-driven: caller calls `CompiledModel.forward(input)` |
| **State** | `CachedStateCell`: two PackedCollection buffers (`cachedValue`, `outValue`); `tick()` copies cache→out, then resets | PDSL `into()`: stateful block's `push()` runs forward pass then writes new state back via `FEATURES.into(...)` |
| **Compilation** | `CellList.tick()` returns `Supplier<Runnable>` wrapping an `OperationList` | `CompiledModel.compile(Model)` flattens all forward cells, runs `Process.optimized()`, returns pre-compiled `Runnable` |
| **Granularity** | Per-sample: each `tick()` call processes one sample | Per-buffer: each `CompiledModel.forward()` call processes `shape.getSize()` samples |

### 10B: Block → CellList Adapter

**This adapter is trivially available today.** A `Block` implements `Block`, and
`Block.getForward()` returns `Cell<PackedCollection>`. That return value can be
added directly to a `CellList`:

```java
Block pdslBlock = loader.buildLayer(program, "efx_delay", shape, args);
CellList cells = new CellList();
cells.add(pdslBlock.getForward());  // Block's forward cell IS a Cell
```

**What works:** The push mechanism is identical. When the `CellList` ticks and pushes
to the cell, `Block.getForward().push(input)` runs exactly as it would under
`CompiledModel.forward()`.

**Stateful blocks in CellList context:** The state write-back in `callBiquad`,
`callDelay`, and `callLfo` happens inside `push()` via `FEATURES.into(...)`. This
means state is updated on every tick — exactly like `CachedStateCell.tick()`. A
PDSL biquad block added to a `CellList` maintains correct per-call state without any
adapter code.

**What is lost:** `SequentialBlock` tracks input/output shapes via
`getInputShape()`/`getOutputShape()`. `CellList` is shape-agnostic. The shape contract
is invisible to `CellList`. This is a debugging concern, not a correctness concern.

### 10C: CellList → Block Adapter

**This adapter is harder and requires explicit output capture.** `CellList.tick()`
returns a `Supplier<Runnable>` — not a `Cell` that processes a single input and
forwards to a receptor. To wrap a `CellList` as a `Block`, you need:

1. A way to inject input into the `CellList`'s root cells.
2. A way to capture the output from the `CellList`'s leaf cells.

The natural pattern (not yet implemented in the codebase):

```java
// Adapter sketch — illustrative, not production code
PackedCollection outputCapture = new PackedCollection(shape);
Cell<PackedCollection> captureCell = Cell.of((in, next) -> 
    FEATURES.into("capture", in, FEATURES.cp(outputCapture), false));

// Wire: last CellList cell → captureCell
lastCellInList.setReceptor(captureCell);

Cell<PackedCollection> forward = Cell.of((in, next) -> {
    OperationList ops = new OperationList("CellListBlock");
    ops.add(rootCell.push(in));       // Inject input into CellList root
    ops.add(cellList.tick());         // Run the CellList
    ops.add(next.push(FEATURES.cp(outputCapture)));  // Forward captured output
    return ops;
});

Block adapter = new DefaultBlock(shape, shape, forward, null);
```

**The fundamental challenge:** `CellList` manages its own internal tick ordering via
parent/child hierarchy and `getAllTemporals()`. Wrapping it as a block forces this
into a flat `push()` → capture cycle, which loses the hierarchical tick ordering.
For simple linear chains this is fine; for complex graphs with feedback (which
CellList is designed for) the adapter may reorder operations incorrectly.

### 10D: Is There a Unified Composition Abstraction?

**No, and one is not needed.** The existing `Cell` interface already IS the shared
abstraction. Both systems build on it. The differences in composition (hierarchy vs.
sequential wiring) and activation (tick vs. forward) reflect genuinely different
execution models for genuinely different use cases:

- Audio DSP needs hierarchical, push-based, per-sample execution with real-time
  guarantees. CellList delivers this.
- ML inference needs sequential, pull-based, per-buffer execution with gradient
  backpropagation. SequentialBlock delivers this.

Forcing them into a single unified API would produce an API that does neither well.
The right approach is the B→CellList direction (Part 10B): PDSL-compiled Blocks
compose with CellList pipelines directly via `getForward()`, so the two systems
can coexist without a new abstraction.

### 10E: The State Block as Bridge

The PDSL `state` block (Part 5) and the `into()` mechanism make PDSL Blocks
structurally equivalent to `CachedStateCell` from the cell graph's perspective:

| Mechanism | `CachedStateCell` | PDSL stateful Block |
|-----------|-------------------|---------------------|
| **State storage** | `cachedValue` PackedCollection (heap) | `state` block PackedCollection (caller-owned) |
| **Read old state** | `getResultant()` returns `p(outValue)` | `FEATURES.cp(history)` reads current PackedCollection |
| **Write new state** | `tick()` copies cachedValue → outValue | `FEATURES.into("name", newState, cp(history), false)` |
| **Update trigger** | Explicit `tick()` call in OperationList | Happens inside `push()`, same call as the forward pass |
| **Lifecycle** | JVM GC (cell holds reference) | Caller holds PackedCollection reference |

The `state` block eliminates the need for `CachedStateCell` when processing
audio through the PDSL pipeline: the state PackedCollection serves the same role as
`cachedValue`, and `into()` serves the same role as `tick()`. The caller's Java
code that owns the state PackedCollection takes the role of the cell graph's
temporal ordering.

### 10F: Implications for AudioScene

`AudioScene.getCells()` builds a `CellList` pipeline. To incorporate PDSL-defined
DSP into AudioScene without structural changes:

1. Replace a Java-defined cell (e.g., a `BiquadFilterCell`) with the PDSL equivalent:
   `loader.buildLayer(program, "efx_delay", shape, args).getForward()`
2. Add this cell to the CellList at the same position.
3. The PDSL block's `push()` performs the DSP computation and updates state on each
   tick — indistinguishable from a hand-written cell.

No changes to `AudioScene`, `EfxManager`, or `MixdownManager` are required for the
first PDSL cells to appear in the audio pipeline. The PDSL blocks participate in
CellList composition because `Block.getForward()` returns `Cell<PackedCollection>`,
which is exactly what CellList expects.

The migration path: replace cells one at a time, verifying audio output after each
replacement. The PDSL definitions can be developed and tested independently using
`CompiledModel.forward()`, then dropped into the CellList pipeline with a single
`block.getForward()` call.

---

## Deliverables: Audio Output Is the Proof

Every phase of this workstream must end with **audio you can listen to**. Passing code
policy checks and unit test assertions are prerequisites, not deliverables. The actual
proof that PDSL-defined DSP works is a `.wav` file that demonstrates the signal path.

### What "Done" Looks Like at Each Phase

**Phase A (FIR primitives — complete):**
A `.wav` file where a test signal (e.g., white noise or a sine sweep) passes through
`efx_channel.pdsl` and the output is audibly filtered. The FIR low-pass should
attenuate high frequencies; the high-pass should attenuate low frequencies.

**Phase B (state-aware primitives):**
A `.wav` file where a test signal passes through a biquad filter defined in PDSL.
The filter state should persist across buffer boundaries — the first few samples
should not exhibit the transient artifacts of a cold-started filter.

**Phase C (full pipeline — primary deliverable):**
A `.wav` file produced by running a complete PDSL-defined `efx_channel` or
`mixdown_channel` on a real audio buffer from `AudioScene`. The pipeline must
include at least one stateful primitive (delay or biquad) and the output must be
audibly different from the dry input.

### How to Produce a .wav File

Wire the PDSL pipeline output into `WavFile.write()` (see `engine/audio`) with a
test buffer as input. This can be done in a test or a standalone main method —
what matters is that the output file exists and sounds correct. The `.wav` file
does not need to be committed; the test that generates it must pass in CI.

### Why This Matters

The PDSL audio DSP workstream exists to make it possible to define audio signal
processing declaratively. If the PDSL pipeline cannot produce audio, the workstream
has not achieved its goal regardless of what the tests assert. Every agent working
on this branch should ask: *"Can I listen to the output?"* If the answer is no,
the work is not done.

---

## Part 11: MixdownManager PDSL Migration

### What Was Achieved

`mixdown_channel.pdsl` (added in `engine/ml/src/main/resources/pdsl/audio/`) expresses
the per-channel signal path from `MixdownManager.createCells()` using existing PDSL
primitives. Two layers are defined:

**`mixdown_main`** — The three-stage main path:
```pdsl
layer mixdown_main(signal_size: int, hp_cutoff: scalar, volume: scalar,
                   lp_cutoff: scalar, sample_rate: scalar,
                   filter_order: scalar) -> [1, signal_size] {
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    lowpass(lp_cutoff, sample_rate, filter_order)
}
```

**`mixdown_channel`** — The full per-channel path with wet/dry delay mix:
```pdsl
layer mixdown_channel(signal_size: int, hp_cutoff: scalar, volume: scalar,
                      lp_cutoff: scalar, sample_rate: scalar, filter_order: scalar,
                      wet_filter_coeffs: weight, wet_level: scalar,
                      delay_samples: int) -> [1, signal_size] {
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    accum_blocks(
        { identity() },
        { fir(wet_filter_coeffs); scale(wet_level); delay(delay_samples, buffer, head) }
    )
    lowpass(lp_cutoff, sample_rate, filter_order)
}
```

The `mixdown_delay_state` state block provides the delay buffer and head pointer.
Tests in `MixdownChannelPdslTest` (studio/compose) verify:
1. Both layers parse and build without errors.
2. `mixdown_main` attenuates below HP cutoff and above LP cutoff (energy assertions).
3. `mixdown_channel` wet delay path adds echo contribution (diff-energy assertion).
4. WAV files are written to `results/pdsl-audio-dsp/` for human review.

### What Remains as Gaps

**IIR vs FIR:** `MixdownManager` uses `AudioPassFilter` (IIR biquad) via
`CellFeatures.hp()`/`lp()`. The PDSL `highpass`/`lowpass` primitives use
`MultiOrderFilter` (FIR, sinc-windowed Hamming). The frequency responses differ near
the cutoff — IIR has sharper rolloff with phase distortion; FIR has linear phase
with a wider transition band. A direct sample-level comparison between the two
pipelines is therefore not meaningful. Correctness is validated by energy-level
assertions rather than exact sample match.

**Cross-channel transmission:** `MixdownManager.createEfx()` routes delay sends
between channels via `mself(fi(), transmission, fc(wetOut.valueAt(0)))`. This is a
true multi-channel operation — channel N's delayed signal feeds into channel M's
delay input. PDSL has no multi-channel routing construct. This must remain at the
Java `CellList` level.

**Reverb path:** `DelayNetwork` is a multi-tap feedback network assembled from Java
cell graph primitives. No PDSL equivalent for multi-tap feedback exists. The reverb
path remains Java-only.

**Automation envelopes:** `AutomationManager.getAggregatedValue()` computes
time-varying Producer values based on clock state and sine/power envelopes. These
are passed as `scalar` parameters in the PDSL layers — the caller computes the current
value and supplies it. This is the correct design (PDSL parameters are `Producer`
inputs, not internal state), but it means the envelope computation itself stays in Java.

### Path Forward for Full Migration

1. **Immediate:** Use `mixdown_channel.pdsl` layers in place of the main-path cells
   in `MixdownManager.createCells()`. Call `block.getForward()` on the PDSL-compiled
   block and add it to the `CellList` at the same position as the existing
   `hp()`/`scale(v)`/`lp()` chain (see Part 10B for the adapter pattern).

2. **Medium term:** Implement a `biquad_hp` / `biquad_lp` PDSL primitive that accepts
   cutoff and resonance and internally computes biquad coefficients. This would make
   the PDSL filters frequency-equivalent to the IIR filters in MixdownManager. The
   `biquad` primitive already exists in PDSL; wrapping it with coefficient computation
   requires adding a new helper layer definition.

3. **Long term:** Multi-channel routing in PDSL (for cross-channel transmission) requires
   either a new `route(matrix, inputs...)` primitive or a higher-level `model` definition
   that iterates over channels. This is a PDSL language extension, not a filter primitive.

---

## Part 12: Tick Semantics and Source-Driven Evaluation in PDSL

### 12A: The Problem, Precisely Stated

Part 10B established that `Block.getForward()` returns a `Cell<PackedCollection>`, making a
PDSL-compiled block a drop-in node for any `CellList`. That handles the case where a PDSL
block is **one passive node inside a larger, Java-wired CellList**. It does not answer a
deeper structural question: *what does a fully PDSL-defined pipeline look like when it needs
to self-drive the way a CellList does?*

#### The tick model (CellList)

`CellList.tick()` (engine/audio/.../CellList.java:923) returns a `Supplier<Runnable>`. When
executed, it:

1. Pushes `c(0.0)` to each root in `getAllRoots()` — roots are self-initializing sources
   such as `WaveCell` (reads a file) or `PatternAudioBuffer` (holds pre-computed samples).
2. Calls `getAllTemporals().tick()` — walks the parent/child hierarchy and ticks every
   `Temporal` in order (parents before children, requirements last).

`TimeCell` (domain/graph/.../TimeCell.java) is both a `Temporal` and a `Cell<PackedCollection>`.
Its `tick()` increments an internal frame counter (with optional modulo wrap). Its `push()`
forwards the current frame value to its downstream receptor. **No external caller provides
the frame number** — `TimeCell` owns its own state. The entire tick graph is self-contained;
the audio engine just calls `tick().get().run()` once per buffer.

#### The forward model (CompiledModel)

`CompiledModel.forward(PackedCollection input, PackedCollection... auxInputs)`
(domain/graph/.../CompiledModel.java) is synchronous and pull-driven. The caller provides
`input`. The compiled kernel reads it, runs the forward pass, writes to an output buffer,
and returns. The caller decides when to invoke `forward()` and what to pass.

#### Where the gap manifests

The goal is to replace `MixdownManager` with a PDSL definition. `MixdownManager.cells()`
(studio/compose/.../MixdownManager.java:457) produces a `CellList`. `AudioScene.getCells()`
calls it and returns that CellList to the audio engine. The engine calls:

```java
Supplier<Runnable> tick = cells.tick();
// ... once per audio buffer:
tick.get().run();
```

Nobody calls `compiled.forward(buffer)` — the CellList is self-driving. If
`mixdown_channel.pdsl` compiled to a `CompiledModel`, the question is: **who calls
`compiled.forward(buffer)` and who provides `buffer`?** In the CellList world the push
graph delivers data automatically. In the Model world there is no push graph.

#### The granularity dimension

`CellList.tick()` has a `tickLoopCount` that collapses per-sample iterations into a single
native call (the hot audio loop). A PDSL block's `forward()` processes one whole buffer.
These two granularities do not naturally align. Any solution must be explicit about
whether a PDSL component fires once per sample or once per buffer, and how that maps to
the CellList's temporal ordering.

#### What Part 10B already gives us (and its limit)

Part 10B's Block→CellList adapter is the right answer when:
- A Java-written CellList wires up sources and clocks
- The PDSL block is one stage in the pipeline, driven by the upstream push

It does **not** give us a fully PDSL-defined pipeline because:
- The source of the push signal (e.g., `PatternAudioBuffer`) is still Java-wired
- The `TimeCell` clock is still Java-constructed and Java-inserted into the CellList
- A reader of the PDSL file cannot see where data enters the pipeline

---

### 12B: Approach A — Source/Sink Declarations in PDSL

The PDSL layer syntax gains new top-level declaration forms: `source` (named input binding)
and `sink` (named output receptor). These make the PDSL file self-describing. The PDSL
loader detects these declarations and produces a `PdslPipeline` — a new compilation target
implementing `Temporal` — rather than a `Block`.

**What `mixdown_channel.pdsl` looks like under this approach:**

```pdsl
// Sources and sinks declared at the top — runtime binds them at attach time
source channel_input -> [1, signal_size]
sink   master_output

state mixdown_state {
    hp_history: weight    // [x1, x2, y1, y2] for HP biquad
    lp_history: weight    // [x1, x2, y1, y2] for LP biquad
}

layer mixdown_channel(hp_cutoff: scalar, volume: scalar,
                      lp_cutoff: scalar, sample_rate: scalar,
                      filter_order: scalar, s: mixdown_state) {
    source channel_input
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    lowpass(lp_cutoff, sample_rate, filter_order)
    sink master_output
}
```

The compiled `PdslPipeline.tick()` implementation:
1. Reads from the bound `PackedCollection` (via `cp(sourceBuffer)`) as the pipeline input
2. Runs the filter chain as a `CollectionProducer` graph (same as the existing forward pass)
3. Pushes the output to the bound `Receptor<PackedCollection>`

**Integration with AudioScene/MixdownManager:**

```java
// In MixdownManager.createCells():
PdslPipeline mixdown = loader.buildPipeline("mixdown_channel.pdsl", args);
mixdown.bindSource("channel_input", patternAudioBuffer.getBuffer());
mixdown.bindSink("master_output", masterReceptor);
cells.addRequirement(mixdown);   // Temporal — ticked once per buffer by CellList
```

`MixdownManager.createCells()` no longer calls `hp()`, `m()`, `lp()`, `d()` manually for
the main path. It builds and attaches one `PdslPipeline` per channel.

**New PDSL primitives/constructs required:**
- `source <name> -> <shape>` declaration (parser + `PdslNode.SourceDecl` AST node)
- `sink <name>` declaration (parser + `PdslNode.SinkDecl` AST node)
- `PdslPipeline` class implementing `Temporal` + runtime source/sink binding
- `PdslLoader.buildPipeline(...)` entry point alongside `buildLayer(...)`

**What's appealing:**
- The PDSL file shows the complete signal flow: where data enters (`source`), what happens
  to it, and where it exits (`sink`). No Java required to understand the pipeline.
- The `layer` syntax body is identical to the existing PDSL — only the file-level
  declarations are new. Existing layer definitions are unaffected.
- Source/sink binding is explicit and grep-able (`bindSource` calls in Java).

**What's awkward:**
- Introduces a runtime source registry concept and a new compilation target.
- Source/sink names are stringly-typed; a typo in `bindSource("channel_ipnut", ...)` fails
  at runtime, not compile time.
- Multi-input topologies (wet path and dry path from different sources) require extending
  the `source` declaration syntax further.
- Mixes data-flow declarations (`source`/`sink`) with computational nodes in the layer body
  — a slightly unusual grammar for the existing PDSL convention.

**Scores against hard constraints:**
- PDSL intuitive-as-flow: **4/5** — explicit sources and sinks; flow is clear at a glance
- CellList-non-destructive: **3/5** — `addRequirement()` is new but CellList code untouched

---

### 12C: Approach B — PDSL Compiles to CellList

The PDSL syntax is unchanged. A new compilation mode (`buildCellList`) in `PdslLoader`
translates each PDSL primitive to its equivalent CellList fluent API call instead of to a
`Block`. The result is an ordinary `CellList` — fully tickable, already understood by
`AudioScene`, requiring no new interfaces.

**What `mixdown_channel.pdsl` looks like under this approach (syntax unchanged):**

```pdsl
// Same file — the caller chooses the compilation mode

layer mixdown_main(signal_size: int, hp_cutoff: scalar, volume: scalar,
                   lp_cutoff: scalar, sample_rate: scalar, filter_order: scalar) {
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    lowpass(lp_cutoff, sample_rate, filter_order)
}
```

The loader generates, conceptually:

```java
// buildCellList() output — not visible in the PDSL file
CellList cells = sources;                              // parent: already wired sources
cells = cells.f(i -> hp(hpCutoff, sampleRate, order)); // highpass
cells = cells.m(i -> c(volume));                       // scale
cells = cells.f(i -> lp(lpCutoff, sampleRate, order)); // lowpass
```

**Integration with AudioScene/MixdownManager:**

```java
// In MixdownManager.createCells():
CellList mixdownCells = loader.buildCellList("mixdown_channel.pdsl",
    sources,   // parent CellList — the source input
    args);
cells = cells.and(mixdownCells);  // Merge into existing pipeline — standard CellList API
```

**New PDSL primitives/constructs required:**
- `PdslLoader.buildCellList(String file, CellList sources, Object... args)` — new entry point
- CellList translations for each audio primitive: `highpass→f(hp(...))`, `scale→m(c(...))`,
  `lowpass→f(lp(...))`, `fir→f(fir(...))`, `accum_blocks→branch()/and()/sum()`
- Every future PDSL primitive that needs CellList support requires a second implementation

**What's appealing:**
- The output is exactly what `AudioScene` already expects: a `CellList`. No new interfaces,
  no new concepts, no new API on `AudioScene` or the audio engine.
- Tick semantics are preserved perfectly — the result participates in CellList parent/child
  hierarchy, temporal ordering, and `getAllTemporals()` collection.
- Migration is incremental: replace one `CellList` method call with one `buildCellList()`.

**What's awkward:**
- Every PDSL primitive requires **two implementations** forever: a `Block` translation
  (existing) and a `CellList` translation (new). ML-domain primitives (`dense`, `attention`,
  `rmsnorm`) have no CellList analog — they are Block-only. This creates a hidden split
  in the PDSL language where some primitives work in both modes and some only in one.
- The compilation mode is invisible in the PDSL file. A reader cannot tell whether
  `mixdown_channel.pdsl` will produce a `Block` or a `CellList`.
- `accum_blocks` (two parallel sub-blocks accumulated) maps naturally to a `Block` (two
  sub-blocks in a `BranchBlock`). Mapping it to a CellList requires `branch()`/`and()`/
  `sum()` — a structurally different topology that is non-trivial to generate automatically.
- Per-buffer (Block) vs per-sample (CellList) granularity is conflated — the PDSL file
  does not express which granularity the compiled result will use.

**Scores:**
- PDSL intuitive-as-flow: **3/5** — same syntax, but the compilation target split is opaque
- CellList-non-destructive: **5/5** — output is literally a CellList; zero new API

---

### 12D: Approach C — Block Adapter That Absorbs Sources

PDSL syntax and compilation are completely unchanged. A thin Java shim wraps the compiled
`Block`/`CompiledModel` and resolves the input from a registered source on each CellList
tick. The adapter implements `Temporal` and is added to `cells.addRequirement(...)`.

**What `mixdown_channel.pdsl` looks like under this approach:**

```pdsl
// Unchanged — identical to Part 11

layer mixdown_channel(signal_size: int, hp_cutoff: scalar, volume: scalar,
                      lp_cutoff: scalar, sample_rate: scalar, filter_order: scalar,
                      wet_filter_coeffs: weight, wet_level: scalar,
                      delay_samples: int) -> [1, signal_size] {
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    accum_blocks(
        { identity() },
        { fir(wet_filter_coeffs); scale(wet_level) }
    )
    lowpass(lp_cutoff, sample_rate, filter_order)
}
```

The adapter in Java:

```java
// PdslChannelAdapter — thin shim, ~40 lines
public class PdslChannelAdapter implements Temporal {
    private final CompiledModel model;
    private final Supplier<PackedCollection> source;  // e.g., buffer::getInputBuffer
    private final Receptor<PackedCollection> sink;

    @Override
    public Supplier<Runnable> tick() {
        return () -> {
            PackedCollection input = source.get();
            PackedCollection output = model.forward(input);
            sink.push(p(output)).get().run();
        };
    }
}

// In MixdownManager.createCells():
CompiledModel mixdown = loader.buildLayer("mixdown_channel.pdsl", shape, args).compile(false);
PdslChannelAdapter adapter = new PdslChannelAdapter(
    mixdown,
    patternAudioBuffer::getBuffer,
    masterReceptor);
cells.addRequirement(adapter);   // fires once per buffer, after per-sample cells
```

**Integration with AudioScene/MixdownManager:**

Minimal. `MixdownManager.createCells()` replaces the main-path CellList fluent chain
(`hp()`, `m()`, `lp()`) with one `PdslChannelAdapter` per channel. Everything else —
the cross-channel transmission, reverb, `DelayNetwork` — remains unchanged Java CellList code.

**New PDSL primitives/constructs required:**
- None. No PDSL changes whatsoever.
- Only new Java class: `PdslChannelAdapter` (or equivalent in `MixdownManager`).

**What's appealing:**
- Zero PDSL language changes — no parser changes, no new AST nodes, no new primitives.
- The adapter is mechanical and easy to reason about: it is precisely a tick-to-forward
  bridge.
- Per-buffer semantics are correct: the adapter fires once per buffer via `requirements`,
  after per-sample cells have run.
- Incremental migration: replace one hand-written CellList cell chain with one adapter,
  verify audio output, repeat.

**What's awkward:**
- The PDSL file shows nothing about where input comes from. A reader of
  `mixdown_channel.pdsl` sees `layer mixdown_channel(...)` with scalar parameters but
  has no idea that audio data arrives from a `PatternAudioBuffer`. The source binding lives
  entirely in the Java adapter.
- This directly violates the first hard constraint: *PDSL must remain intuitive to
  understand as a "flow."* The adapter approach makes the flow invisible in PDSL.
- At scale (16 channels, multiple PDSL-defined blocks per channel), the Java wiring code
  grows proportionally. The PDSL definitions do not describe the whole system.
- `CompiledModel.forward()` is per-buffer. If the CellList also runs with `tickLoopCount`
  (per-sample batching), the adapter must be registered as a requirement (not as a cell)
  to fire at buffer granularity — easy to get wrong during initial implementation.

**Scores:**
- PDSL intuitive-as-flow: **2/5** — source binding invisible in PDSL; violates the
  declarative-readability constraint
- CellList-non-destructive: **5/5** — no CellList code changes at all

---

### 12E: Approach D — `pipeline` Keyword (Tickable Block)

A new `pipeline` top-level declaration joins `layer` and `model` in the PDSL grammar.
A `pipeline` is like a `layer` but self-describing: it declares its own input source,
output sink, and tick state. It compiles to a `PdslTemporalBlock` — an object implementing
both `Temporal` (so it can be added to CellList requirements) and `Cell<PackedCollection>`
(so it can also participate in the Part 10B Block→CellList path if needed).

The `pipeline`/`layer` distinction mirrors the existing `CellList`/`Cell` distinction:
a `layer` is a passive transformation node; a `pipeline` is a self-driving processing unit.

**What `mixdown_channel.pdsl` looks like under this approach:**

```pdsl
// A pipeline is self-describing: source, state, signal path, and sink are all declared here.

pipeline mixdown_channel(hp_cutoff: scalar, volume: scalar,
                         lp_cutoff: scalar, sample_rate: scalar, filter_order: scalar) {

    input  channel_audio -> [1, signal_size]   // reads from this named source each tick
    output master_output                        // pushes result to this named receptor

    state mixdown_state {
        hp_history: weight    // [x1, x2, y1, y2] for HP biquad
        lp_history: weight    // [x1, x2, y1, y2] for LP biquad
    }

    // Signal path — identical grammar to a layer body
    highpass(hp_cutoff, sample_rate, filter_order)
    scale(volume)
    lowpass(lp_cutoff, sample_rate, filter_order)
}
```

Compiled `PdslTemporalBlock.tick()` implementation:
1. Reads `cp(attachedInputBuffer)` — the source bound via `attachInput()`
2. Runs the filter chain as a `CollectionProducer` graph (same Producer machinery as today)
3. Pushes the result to the attached output receptor

**Integration with AudioScene/MixdownManager:**

```java
// In MixdownManager.createCells():
PdslTemporalBlock mixdown = loader.buildPipeline("mixdown_channel.pdsl", stateArgs, params);
mixdown.attachInput("channel_audio", patternAudioBuffer.getBuffer());
mixdown.attachOutput("master_output", masterReceptor);
cells.addRequirement(mixdown);   // Temporal — ticked once per buffer
```

`MixdownManager.createCells()` replaces its manual main-path construction with one
`buildPipeline()` call per channel. Cross-channel transmission, reverb, and `DelayNetwork`
remain as Java CellList code (see Part 11 — these are not expressible in PDSL today).

**New PDSL primitives/constructs required:**
- `pipeline` keyword (parser switch alongside `layer`, `model`)
- `input <name> -> <shape>` declaration within a pipeline body
- `output <name>` declaration within a pipeline body
- `PdslNode.PipelineDef` AST node (structurally similar to `LayerDef`, adds input/output
  fields alongside the state and layer body)
- `PdslTemporalBlock` class implementing `Temporal + Cell<PackedCollection>` — new
  compilation target in `PdslLoader`
- `PdslLoader.buildPipeline(...)` entry point
- `attachInput(String name, PackedCollection buffer)` and
  `attachOutput(String name, Receptor<PackedCollection> receptor)` on `PdslTemporalBlock`

**What's appealing:**
- The PDSL file is fully self-describing. A reader sees: input source, state structure,
  signal processing chain, output receptor — the complete flow at a glance.
- The `pipeline` / `layer` distinction maps onto a mental model users already have from
  the `CellList` / `Cell` analogy. The two-word vocabulary is learnable.
- `layer` definitions (for ML, for Block→CellList adapter use) are completely unchanged.
- The tick granularity is explicit: a `pipeline` fires once per buffer via `requirements`,
  same as any other `Temporal` in the CellList hierarchy.
- `state` blocks work identically in `pipeline` and `layer` — no new state mechanism.
- `PdslTemporalBlock` implementing both `Temporal` and `Cell` means it can also be used
  in the Part 10B path (`getForward()`), giving maximum flexibility.

**What's awkward:**
- Two top-level keywords (`layer` vs `pipeline`) add conceptual overhead. New PDSL authors
  must learn when to use each.
- `input`/`output` declarations are syntactically new — they do not exist in the current
  PDSL grammar and require parser extension.
- `attachInput()` / `attachOutput()` are stringly-typed (same risk as Approach A).
- `PdslLoader.buildPipeline()` is a third entry point alongside `buildLayer()` and
  `buildModel()` — the loader API grows wider.

**Scores:**
- PDSL intuitive-as-flow: **5/5** — explicit source, processing chain, sink; maximum
  declarative clarity
- CellList-non-destructive: **4/5** — `addRequirement()` is the only new CellList
  interaction; all existing CellList/AudioScene/MixdownManager code is unchanged

---

### 12F: Side-by-Side Comparison

| Dimension | A: Source/Sink Decls | B: PDSL→CellList | C: Block Adapter | D: Pipeline Keyword |
|-----------|---------------------|------------------|-----------------|---------------------|
| **PDSL intuitive-as-flow (1–5)** | 4 | 3 | 2 | **5** |
| **CellList-non-destructive (1–5)** | 3 | **5** | **5** | 4 |
| **Implementation cost** | Medium — new `PdslPipeline` class, source/sink AST nodes, binding | High — dual compilation path for every primitive, ongoing maintenance burden | **Low** — thin adapter class, zero PDSL changes | Medium-High — new `pipeline` keyword, `PdslTemporalBlock`, new loader entry point |
| **Required PDSL extensions** | `source`/`sink` declarations, `PdslPipeline` compilation target | Second compilation target for every audio primitive | **None** | `pipeline` keyword, `input`/`output` declarations, `PdslTemporalBlock` |
| **MixdownManager rewrite** | Replace `createCells()` body with `buildPipeline()` + source/sink bindings | Replace `createCells()` body with `buildCellList()` + pass sources as parent | Keep most of `createCells()`; replace leaf cell chains with `PdslChannelAdapter` | Replace `createCells()` main-path body with `buildPipeline()` + `attachInput()`/`attachOutput()` |
| **Handles per-buffer granularity?** | Yes — `PdslPipeline` is `Temporal`, fires once per buffer via `requirements` | Partial — CellList translation conflates per-sample / per-buffer | Yes — adapter fires once per buffer as `requirement` | Yes — `PdslTemporalBlock` fires once per buffer as `requirement` |
| **Blocking risks** | Runtime source registry adds failure mode; stringly-typed names | Dual compilation doubles maintenance; `accum_blocks` topology is hard to map to CellList API | Violates PDSL-as-flow constraint; source invisible in PDSL file | Two-keyword design adds cognitive overhead; `attachInput()` is stringly-typed |
| **Best suited for** | Fully self-describing PDSL files with readable source/sink | Incremental CellList replacement with no new Java concepts | Immediate migration with zero PDSL risk | Long-term: fully PDSL-defined self-driving pipelines |

---

### 12G: Recommendation

#### Phase 1 (now): Approach C as the migration vehicle

The Block Adapter requires no PDSL changes and has the lowest risk. It allows
`MixdownManager.createCells()` to migrate one cell chain at a time: replace a Java-defined
`hp()`/`m()`/`lp()` chain with a `PdslChannelAdapter` wrapping the already-working
`mixdown_channel.pdsl` `CompiledModel`. This delivers real audio output from PDSL-defined
filters without any PDSL language work.

The readability violation is acceptable at this phase because the PDSL files are already
written (Part 11) and the signal path is already visible in the PDSL definition — the
adapter's source binding is a narrow seam, not a structural opacity. The adapter is also
the right prototype: before investing in `PdslTemporalBlock` infrastructure, verify that:

1. `addRequirement(adapter)` fires at the correct granularity relative to the CellList's
   per-buffer tick (not per-sample)
2. The `state` block's `into()` write-back mechanism works correctly when the block is
   invoked via a `Temporal` tick rather than a direct `forward()` call
3. Audio output is correct end-to-end — listen to the WAV

#### Phase 2 (after prototype validates): Approach D as the target architecture

Approach D achieves both hard constraints simultaneously: PDSL files are fully
self-describing (maximum readability), and integration is via `addRequirement()` which
leaves all existing CellList code intact.

The `pipeline` / `layer` distinction is worth the conceptual overhead because it mirrors
an existing distinction in the codebase: a `CellList` is self-driving; a `Cell` is a
passive node. The analogy teaches itself. A new PDSL author who knows the `Cell`/`CellList`
model will immediately understand that a `pipeline` drives itself and a `layer` is driven
by something else.

Approach D is preferable to Approach A because the `pipeline` keyword makes the compilation
target explicit from the first word of the definition — a reader knows immediately that
this compiles to a `Temporal`, not a `Block`. Approach A (`source`/`sink` declarations
inside a `layer`) provides the same information but requires reading deeper into the file.

#### Why Approach B is excluded

The dual compilation path maintenance burden is too high. Every future primitive that
needs audio DSP support would require two implementations in perpetuity. The
`accum_blocks` topology (two parallel branches accumulated) does not map cleanly to the
CellList `branch()`/`and()`/`sum()` API — generating it automatically would require
non-trivial graph topology reconstruction. The ongoing cost of maintaining two compilation
paths outweighs the benefit of producing a literal `CellList` output.

#### What the Approach C prototype should specifically test

- **Granularity:** Add `PdslChannelAdapter` as a requirement to a `CellList` configured
  with `setTickLoopCount(bufferSize)`. Confirm the adapter's `tick()` fires exactly once
  per buffer, not once per sample.
- **State persistence:** Run two consecutive buffer ticks. Confirm that biquad/delay state
  (from `state` blocks, written back via `into()`) survives across the tick boundary and
  produces the expected filter transient response (not a cold restart each buffer).
- **Output correctness:** Compare WAV output from the `PdslChannelAdapter`-driven pipeline
  against the existing Java-wired CellList pipeline on the same input signal. The frequency
  response should match (modulo FIR vs IIR differences noted in Part 11).

If all three conditions hold, `PdslTemporalBlock` (Approach D) is the adapter refactored
to be self-describing — the execution model is identical, only the PDSL grammar extension
is new work.

---

## Appendix A: Current PDSL Files Reference

| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |
| `pdsl/audio/efx_channel.pdsl` | EFX channel FIR filter + scale layers (Phase A complete) |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown main path and full channel with wet delay (Part 11) |

The GRU block is the closest analog to stateful audio DSP: hidden state is passed
explicitly, gates control information flow. The main difference is that GRU state is
a vector updated once per forward call, while filter state is scalars updated once
per sample within a buffer. The `state` block design bridges this gap.

---

## Appendix B: `data` Block vs `state` Block — Implementation Diff

The implementation distance between the current `data` block and the proposed `state`
block is deliberately small. Here is the delta:

**Parser:** Add `"state"` as an alternative to `"data"` for block header keywords.
Produce `StateDef` instead of `DataDef` for `state` blocks.

**AST:** Add `PdslNode.StateDef` — structurally identical to `PdslNode.DataDef`,
different class for semantic distinction.

**Interpreter:** In `populateDataDefs` (and `evaluateDataDef`), also iterate over
`stateDefs`. The population logic is identical — bind parameters from args, evaluate
derivations, set in environment.

**Primitives:** `callBiquad`, `callDelay`, `callLfo` — these must follow the pattern
established by `callFir`, `callLowpass`, and `callHighpass` in `PdslInterpreter.java`.
Study those implementations before writing state-aware primitives. All computation
must be expressed as `CollectionProducer` operations. Do not use `setMem()` or
`toDouble()` in the implementation — these are Java-side operations that bypass the
Producer computation graph and cannot be hardware-accelerated.

No changes to `PackedCollection`, `SequentialBlock`, `Model`, `CompiledModel`, or any
hardware backend are needed.

---

## Part 13: Multi-Channel Composition: PDSL for Lists, Not Just Chains

### 13A: What CellList Multi-Channel Composition Actually Does

Part 12 treated PDSL integration as if CellList were a single-channel pipeline: one input,
one output, one layer of stateful processing. This is not what CellList is. A CellList is
a *list* of cells — at every layer there are typically N independent channels running in
parallel, and the multi-channel operations create structured relationships between them.
This is not a side feature. It is the core of what `MixdownManager`, `EfxManager`, and the
full audio scene graph actually do.

The following operations are the multi-channel vocabulary of CellList. Each entry lists
the implementing method, its location, and at least one production call site.

---

#### Operation 1: `sum()` — Collapse N Channels to 1

**Method:** `CellFeatures.sum(CellList cells)` — `engine/audio/.../CellFeatures.java:582`

Creates a single `SummationCell`, wires every cell in the list as a receptor, and returns
a new single-element CellList. Push-based: the `SummationCell` accumulates contributions
from all N upstream cells as they push their values on each tick.

**Production call sites:**
- `MixdownManager.createCells():610` — `main = main.sum()` — collapses N pattern channels
  into one mixed signal before the EFX chain
- `MixdownManager.createEfx():667` — `.sum()` — collapses the N-element delay feedback
  grid after cross-channel routing
- `MixdownManager.createEfx():674` — `reverb.sum()` — collapses N reverb channels before
  the `DelayNetwork`
- `MixdownManager.createEfx():678` — `cells(efx, reverb).sum()` — merges efx and reverb
  paths and sums
- `EfxManager.apply():246` and `:248` — `wet...sum()` and `cells(wet, dry).sum()` — per-channel
  wet/dry collapse

---

#### Operation 2: `branch(IntFunction<Cell>... dest)` — Fan Out from N to N×M

**Method:** `CellFeatures.branch(CellList cells, IntFunction<Cell>... dest)` —
`engine/audio/.../CellFeatures.java:229`

Creates M new CellLists (one per destination function). Each cell i in the source is wired
to send its output to ALL M destination cells simultaneously on every tick. From a source
with N cells, `branch(f0, f1, f2)` produces three CellLists each with N cells. The source
cells now have 3 receptors — every tick sends N values to all 3 × N destinations.

**Production call sites:**
- `MixdownManager.createCells():572-578` — `wetSources.branch(filterEfxFunc, reverbFunc)`
  splits N wet channels into an EFX branch (with `wetFilter` applied) and a reverb branch
  (with reverb factor applied), running in parallel from the same N sources
- `MixdownManager.createCells():592-601` — `cells.branch(volumeFunc, efxFilterFunc, reverbFunc)`
  creates main, efx, and reverb paths all from the same N channel cells simultaneously
- `MixdownManager.createCells():605-606` — `main.branch(stemCell, passThroughCell)` taps
  a stem output alongside the main path without interrupting it

---

#### Operation 3: `and(CellList)` / `cells(CellList...)` — Merge Parallel Paths

**Method:** `CellList.and(CellList cells)` — `engine/audio/.../CellList.java:406`,
delegating to `CellFeatures.cells(CellList...)` at line 183, which calls
`CellFeatures.all(int, IntFunction<CellList>)` at line 194.

Merges two or more CellLists into a combined list in which all parent CellLists tick before
the combined list's cells. Used to unify parallel signal paths for a subsequent `sum()`.

**Production call sites:**
- `MixdownManager.createEfx():678` — `cells(efx, reverb).sum()` — combines the efx
  CellList (N delay outputs) with the reverb CellList (1 reverb output) into a shared
  pool before the final sum
- `MixdownManager.createEfx():719` — `cells(main, riser).sum()` — adds a riser element
  alongside main before summing
- `EfxManager.apply():248` — `cells(wet, dry).sum()` — merges the wet delay path with
  the dry bypass and sums

---

#### Operation 4: `m(adapter, destinations, transmission)` — Gene-Controlled Cross-Channel Routing

**Method:** `CellFeatures.m(CellList cells, IntFunction<Cell> adapter,
List<Cell> destinations, IntFunction<Gene> transmission)` —
`engine/audio/.../CellFeatures.java:927`

Routes each cell i through an adapter cell, then distributes the adapted signal to
destinations j, scaled by `transmission.apply(i).valueAt(j)`. The transmission gene
controls the routing weight matrix: how strongly channel i's signal flows into destination
j. This is a fully general N×M cross-channel routing operation — the audio-domain analog
of a linear routing layer in ML.

**Production call site:**
- `MixdownManager.createEfx():664` — `efx.m(fi(), delays, tg)` — routes each of N EFX
  channels to each of M delay line cells (in `delays`) using the `tg` gene. The gene
  controls which delay layers receive signal from each channel and at what level. The
  identity adapter `fi()` means routing happens without signal transformation.

---

#### Operation 5: `mself(adapter, transmission, passthrough)` — Cross-Channel Feedback Grid

**Method:** `CellFeatures.mself(CellList cells, IntFunction<Cell> adapter,
IntFunction<Gene> transmission, IntFunction<Cell> passthrough)` —
`engine/audio/.../CellFeatures.java:901`, delegating to `m(cells, adapter, cells,
transmission, passthrough)`

Identical to `m(...)` except the destinations ARE the source cells themselves (`cells`
is passed as both source and destination). Creates a fully-connected feedback grid: the
output of each cell i feeds into each cell j via the transmission matrix. The passthrough
cell delivers the non-feedback signal alongside. This is the `mself` in the name: routes
*into* self.

**Production call sites:**
- `MixdownManager.createEfx():665-666` — `.mself(fi(), transmission, fc(wetOut.valueAt(0)))`
  — the cross-channel delay feedback network: each of the M delay cells feeds back into
  all M delay cells according to the `transmission` chromosome (an [M×M] routing matrix),
  while `wetOut.valueAt(0)` controls the direct wet-send level alongside the feedback
- `EfxManager.apply():245` — `.mself(fi(), i -> g(delayLevels.valueAt(ch, 1)))` — single-
  channel self-feedback: the delay cell feeds back into itself at a level controlled by
  `delayLevels[ch][1]` (the feedback gene for this channel)

---

#### Operation 6: `gr(duration, segments, choices)` / `grid(...)` — Temporal Channel Selection

**Methods:** `CellFeatures.gr(CellList, double, int, IntUnaryOperator)` at line 1061;
`CellFeatures.grid(CellList, double, int, IntToDoubleFunction)` at line 1074;
`CellFeatures.grid(CellList, double, int, IntFunction<Producer>)` at line 1090

Selects one channel from the CellList for each time segment, using a `ValueSequenceCell`
to cycle through choices on a schedule. This is temporal multiplexing — only one channel
is active during each segment — rather than all-at-once multi-channel processing.

Also: `CellList.poly(IntFunction<CollectionProducer> decision)` at line 428 creates a
`PolymorphicAudioCell` for producer-driven dynamic channel selection (smooth interpolation
rather than stepped segments).

**Production use:** Used in AudioScene-level pattern routing to select which pattern
source is active in each musical segment. The CellList contains one cell per available
pattern; `gr` routes audio through the correct cell for each bar.

---

#### Operation 7: `CellList.collector()` / `all(count, cells)` — Assembly of N-Cell Lists

**Methods:** `CellList.collector()` at line 1032; `CellFeatures.all(int, IntFunction<CellList>)`
at line 194

`collector()` is a `java.util.stream.Collector` that builds a CellList from a stream of
cells. `all()` aggregates N CellLists into one flat container with all N as parents (tick-
ordering preserved). These are the assembly primitives — how N-channel CellLists are built
from per-channel streams.

**Production call sites:**
- `MixdownManager.createEfx():654-657` — `IntStream.range(0, delayLayers).mapToObj(...).collect(CellList.collector())` —
  builds the M-element delay CellList from a stream of `AdjustableDelayCell` instances,
  one per delay layer

---

### 13B: Why Block/Model Cannot Express These Cleanly Today

The seven operations above have no clean Block/Model analogs. Here is why, operation by
operation, followed by a side-by-side code comparison.

#### The Structural Mismatch

In the CellList model, N channels = N independent `Cell<PackedCollection>` instances. Each
cell has its own state (`cachedValue`, `outValue`), its own delay buffers, its own receptor
wiring. Operations like `sum()`, `branch()`, `m()`, and `mself()` define the *topology*
of the graph — how signals flow between the N cells.

In the Block/Model model, N channels would have to be represented as a batch dimension in a
single `PackedCollection` tensor of shape `[N, signal_size]`. Operations on the tensor
apply to all N channels simultaneously (vectorized), but cross-channel dependencies require
explicit tensor contractions.

The consequences:

| Multi-channel operation | CellList | Block/Model |
|------------------------|----------|-------------|
| `sum()` — collapse N → 1 | `SummationCell` wired to N receptors | Need a reduce-sum over the batch dimension; no PDSL primitive exists |
| `branch()` — fan out N → N×M | Wire each of N cells to M destination functions | Tensor replication: `tile([N, signal_size], M)` — not expressible in PDSL without new primitives |
| `m(adapter, dests, gene)` — gene-controlled routing | Per-cell gene lookup at N×M granularity | Matrix multiply (dense) on channel dimension — feasible but loses per-cell gene structure |
| `mself(adapter, gene)` — feedback grid | Direct wiring back into same cell array | Self-referential tensor op: output at step k is input at step k+1 — no static-graph expression |
| Per-channel state | Each cell holds its own `CachedStateCell` state | Must index into a shared [N, state_size] tensor per channel — requires per-element subscripting |
| `gr()/grid()` — temporal selection | `ValueSequenceCell` routes between cell instances | Conditional execution (if channel_idx == i) — not expressible without gating primitives |
| Channel count flexibility | N is a Java integer; CellList grows dynamically | N must be fixed at PDSL compile time (tensor shapes are static) |

#### Side-by-Side: 3-Channel Filter + Sum

The simplest multi-channel pattern: apply a filter to each of 3 channels, then sum.

**CellList (Java, used in production everywhere):**

```java
// Build a 3-element CellList from 3 source cells
CellList sources = cells(ch0, ch1, ch2);

// Apply HP filter to each channel independently — 3 separate FilteredCells
CellList filtered = sources.f(i -> hp(c(500), scalar(0.1)));

// Collapse 3 → 1 via SummationCell
CellList summed = filtered.sum();
// result: 1-element CellList whose single cell accumulates all 3 filtered signals
```

**The equivalent PDSL Block expression — today (with existing primitives):**

```pdsl
// Channels must be pre-interleaved as [3, signal_size] tensor
// PDSL has no "apply this to each row" construct — must enumerate explicitly:
accum_blocks(
    { slice(0, signal_size); fir(hp_coeffs) },            // channel 0
    { slice(signal_size, signal_size); fir(hp_coeffs) },  // channel 1
    { slice(signal_size * 2, signal_size); fir(hp_coeffs) } // channel 2
)
// accum_blocks sums the 3 outputs — that IS the sum.
// But: (a) channel count is hard-coded in the layer definition
//      (b) each channel names its own slice offset explicitly
//      (c) if filter is stateful (biquad), each needs its own state block declaration
//      (d) for N=8, this is 8 explicit branches
```

This is already four times more verbose and structurally rigid. For the `mself` feedback
grid (the most important multi-channel operation in `MixdownManager`), the Block form is
not just verbose — it is inexpressible in static PDSL syntax:

**CellList feedback grid (3 delay layers, `MixdownManager.createEfx():664-667`):**

```java
// N EFX channels routed to M delay lines, then feedback grid
efx = efx
    .m(fi(), delays, tg)                               // [N] → [M]: gene-routed
    .mself(fi(), transmission, fc(wetOut.valueAt(0))) // [M] × [M]: feedback matrix
    .sum();                                             // [M] → 1
```

**PDSL Block equivalent — not possible without new primitives.** The feedback (`mself`)
creates a loop-carried dependency between cells at the CellList level. In a static computation
graph (which PDSL compiles to), self-referential routing across tick boundaries cannot be
expressed without explicit state tracking. The `mself` feedback is structurally equivalent
to a recurrent connection — it is what PDSL *state* blocks handle for single-channel
biquad/delay, but extended to N×N cross-channel routing. No amount of `accum_blocks` or
`concat_blocks` nesting can express this.

The readability gap is real: **the CellList form is the same 3-line chain regardless of
channel count N. The Block form grows as O(N²) and requires O(N) separate state block
declarations.** For the 8-channel, 3-delay-layer case in `MixdownManager`, the Block form
would require 24 explicit branches and 8 separate state block declarations — 30+ lines of
PDSL — to express what the CellList form says in 3 lines.

---

### 13C: Proposed PDSL Syntax for Multi-Channel Layer Composition

The multi-channel vocabulary of CellList maps onto six PDSL constructs. Each is shown with
the CellList operation it mirrors and the underlying compilation strategy.

---

#### Construct 1: `channels: N` — Channel Multiplicity Declaration

Declares that a layer or pipeline operates on N independent channels. When present in a
`pipeline` header, the pipeline's `input` and `output` declarations become N-element arrays.
The body's operations apply per-channel unless explicitly marked as cross-channel.

```pdsl
pipeline delay_bank(channels: int, delay_time: scalar) {
    input  channel_audio[channels] -> [1, signal_size]
    output master_mix
    // ...
}
```

**Compiles to:** `PdslTemporalBlock` that internally maintains a `channels`-element
`CellList`, constructed via `IntStream.range(0, channels).mapToObj(...).collect(CellList.collector())`.
The `channels` value is passed as a Java integer parameter at `buildPipeline()` call time.

---

#### Construct 2: `for each channel { }` — Per-Channel Application

Applies the body to each channel independently. The body must contain only single-channel
primitives (no cross-channel operations). State accessed inside `for each channel` is
automatically indexed per channel: `delay_state.buffer` becomes `delay_state.buffer[channel]`.

```pdsl
// Apply a filter + delay to all 6 channels independently
for each channel {
    fir(filter_coeffs)
    delay(delay_time, delay_state.buffer[channel], delay_state.head[channel])
    scale(wet_level)
}
```

**Compiles to:** `sources.f(i -> fir(...)).d(i -> delay(i, ...)).m(i -> scale(...))` —
the body becomes the argument lambda to CellList's per-cell methods. The `[channel]`
indexing becomes `[i]` in the lambda.

---

#### Construct 3: `sum_channels()` — Collapse N Channels to 1

Collapses all channels to a single output via element-wise summation across the channel
dimension. The output has the same shape as one channel, with values equal to the sum of
all channels at each position.

```pdsl
for each channel {
    fir(filter_coeffs)
}
sum_channels()    // N-channel → 1-channel
```

**Compiles to:** `cellList.sum()` — the SummationCell pattern. Also useful: `mean_channels()`
(divides by N), `max_channels()` (element-wise max across channels).

---

#### Construct 4: `fan_out(N)` — Replicate 1 Channel to N

Replicates the single-channel input N times, producing a N-channel output. Each copy is
independent from this point forward. This is the inverse of `sum_channels()`.

```pdsl
fan_out(num_experts)   // 1 → num_experts channels
for each channel {
    dense(expert_weights[channel])
    silu()
}
sum_channels()
```

**Compiles to:** An N-element `CellList` constructed by creating N copies of the source
cell's receptor (via `branch(f0, f1, ..., fN-1)` with N identical functions). The source
feeds N downstream cells.

---

#### Construct 5: `route(matrix)` — Cross-Channel Routing via Transmission Matrix

Applies a gene-controlled or weight-controlled routing matrix across the channel dimension.
Each channel's output is distributed to destination channels with weights from `matrix`.
The matrix may be a `weight` (fixed at build time) or a `gene` (genome-driven at runtime).

```pdsl
// Route N EFX channels to M delay lines using a transmission gene
route(delay_routing_gene)         // [N channels] × [N, M gene] → [M channels]

// Cross-channel feedback: each of M delay lines feeds all others
route(transmission_gene)          // [M channels] × [M, M gene] → [M channels]
```

**Compiles to:** `.m(fi(), destinations, gene)` for routing to new destinations;
`.mself(fi(), gene, passthrough)` when routing back into the same cell list.
The distinction: if `route()` maps to a new set of channels it calls `.m()`; if it routes
back into the same set (feedback) it calls `.mself()`. The feedback vs. forward direction
is inferred from whether the input and output channel lists are the same.

---

#### Construct 6: `tap(i)` — Select a Single Channel

Selects channel `i` from a multi-channel layer for separate single-channel processing. The
remaining channels continue through the pipeline unchanged.

```pdsl
channels = 4

for each channel {
    fir(filter_coeffs)
}

tap(0) {
    // Channel 0 only: additional high-frequency boost
    fir(treble_coeffs)
}

sum_channels()
```

**Compiles to:** The `branch()` pattern: `cells.branch(stemCell, passThroughCell)[1]`
taps channel i while the rest continue. Inside the `tap(i)` block, channel i is processed
separately; the modified channel is rejoined via `and()/cells()` before `sum_channels()`.

---

#### Combined Example: MixdownManager-Style Delay Feedback Bank

This exercises 5 of the 6 constructs above. It closely mirrors `MixdownManager.createEfx()`
lines 648–667.

```pdsl
// MixdownManager delay feedback bank expressed in PDSL
// Matches: efx.m(fi(), delays, tg).mself(fi(), transmission, fc(wetOut)).sum()

state delay_bank_state(delay_layers: int) {
    buffers: weight    // [delay_layers, max_delay_samples] — one buffer per delay line
    heads: weight      // [delay_layers] — write pointers
}

pipeline delay_feedback_bank(n_efx: int, delay_layers: int,
                              delay_times: weight, delay_dynamics: weight,
                              transmission: gene, wet_out: scalar,
                              s: delay_bank_state) {

    input  efx_channels[n_efx] -> [1, signal_size]    // N independent EFX channels
    output combined_mix                                 // single summed output

    // Step 1: Route N EFX channels into M delay lines via transmission gene
    //   CellList equivalent: efx.m(fi(), delays, tg)
    route(delay_times)                                  // [n_efx] → [delay_layers]

    // Step 2: Apply delay to each of the M delay lines independently
    //   CellList equivalent: each AdjustableDelayCell in `delays`
    for each channel {
        delay(delay_times[channel], s.buffers[channel], s.heads[channel])
    }

    // Step 3: Cross-channel feedback grid via transmission chromosome
    //   CellList equivalent: .mself(fi(), transmission, fc(wetOut.valueAt(0)))
    route(transmission)                                 // [delay_layers] × [M, M] → [delay_layers]

    // Step 4: Collapse M delay lines to 1 summed output
    //   CellList equivalent: .sum()
    sum_channels()
}
```

The PDSL version says in 6 lines what `MixdownManager.createEfx()` says across 14 lines of
Java — and unlike the Java, the PDSL makes the signal flow visible at a glance: fan in
from N EFX channels → delay bank → feedback routing → collapse.

---

### 13D: Two Syntactic Styles for Multi-Channel Composition

Two distinct styles are possible. Both are evaluated using the combined delay feedback bank
example above.

---

#### Style 1: Channel-Arity as a Property of the Pipeline

The `pipeline` header declares channel count explicitly. Operations inside the body are
single-channel by default; cross-channel operations (`route`, `sum_channels`, `fan_out`)
must be explicit. The `for each channel` block makes per-channel iteration explicit.

```pdsl
// Style 1: channel count is declared on the pipeline header
pipeline delay_bank_style1(n_efx: int, delay_layers: int,
                            delay_times: weight, transmission: gene,
                            wet_out: scalar, s: delay_bank_state) {

    input  efx_input[n_efx] -> [1, signal_size]    // N-element input array
    output master_mix

    route(delay_times)                              // N EFX → M delay lines

    for each channel {                              // M independent delay operations
        delay(delay_times[channel], s.buffers[channel], s.heads[channel])
    }

    route(transmission)                             // M×M feedback matrix
    sum_channels()                                  // M → 1
}
```

**What's clear:** The structure is a chain of single-channel and cross-channel operations,
exactly mirroring the CellList fluent chain. A reader unfamiliar with the codebase sees the
signal flow: N inputs → routing → per-channel delay → feedback → sum. The `for each channel`
and `route` markers make the multi-channel steps explicit without requiring knowledge of the
Java implementation.

**What's harder:** The `n_efx` and `delay_layers` parameters must be known at `buildPipeline()`
time. Variable channel count (e.g., determined by genome) is not expressible — the CellList
fluent API handles this naturally because Java integers can be computed at runtime, but PDSL
shape inference requires fixed sizes at compile time. Also, the `[n_efx]` multiplicity
notation in `input` is new syntax.

---

#### Style 2: Channels as a Separate Sequence/Iteration Construct

The pipeline header does not declare channel count. Multi-channel sections are enclosed in
a `parallel` block that explicitly forks the single-channel flow into N copies. Outside
`parallel` blocks, the pipeline is single-channel.

```pdsl
// Style 2: multi-channel sections are explicit blocks
pipeline delay_bank_style2(n_efx: int, delay_layers: int,
                            delay_times: weight, transmission: gene,
                            s: delay_bank_state) {

    input  efx_input -> [1, signal_size]    // single-channel input conceptually
    output master_mix

    // Explicit fork: expand 1 input into N parallel EFX paths
    parallel(n_efx) {
        // Each of the n_efx copies is treated as one channel here
        // (Note: in practice, efx_input is already N-channel; this represents the topology)
    }

    route(delay_times)                      // N → M routing

    parallel(delay_layers) {                // M parallel delay lines
        delay(delay_time, buffer, head)
    }

    route(transmission)                     // M×M feedback
    sum_channels()                          // M → 1
}
```

**What's clear:** The `parallel(N) { }` block visually marks where the pipeline branches.
A reader can see exactly where parallelism enters and exits the signal flow. The rest of the
pipeline (outside `parallel` blocks) is clearly single-channel. This style is closer to how
a programmer thinks about the architecture: single stream → fork into N → process in parallel
→ merge.

**What's harder:** The nesting is heavier. A pipeline with multiple parallel sections has
multiple `parallel` blocks, and the relationship between them (does the output of `parallel(n_efx)`
feed `route(delay_times)` directly?) requires reading the whole pipeline. Also, the channel
index is implicit inside `parallel` — how does `delay_time` know which delay time to use?
Needs explicit indexing: `delay(delay_times[channel], ...)`, same as Style 1, so the advantage
of "not needing channel declarations" disappears.

---

#### Style Scores

| Axis | Style 1: Channel-Arity as Property | Style 2: Channels as Iteration Construct |
|------|------------------------------------|-----------------------------------------|
| **Intuitive-as-flow (1–5)** | **4** — signal flow visible; `for each channel` and `route` are self-explanatory | **3** — `parallel(N)` block is clear, but nested structure is heavier; channel index is still needed inside |
| **CellList-non-destructive (1–5)** | **5** — `PdslTemporalBlock` compiles to CellList operations internally; no CellList changes | **5** — same; both styles compile to identical CellList operations |
| **Expressiveness for cross-channel routing (1–5)** | **5** — `route(gene)` maps directly to `.m(fi(), dests, gene)` and `.mself(fi(), gene, ...)`; the syntax mirrors the semantic | **4** — `route` works the same, but `parallel` nesting makes it less obvious that cross-channel routing applies at the inter-block level |
| **Channel count flexibility** | Fixed at `buildPipeline()` time; N is a Java integer parameter | Same — N must be known at build time |
| **State indexing** | `delay_state.buffer[channel]` — explicit | Same — no improvement |
| **Learning curve** | New syntax: `for each channel`, `route`, `sum_channels` | New syntax: `parallel(N)`, `route`, `sum_channels` — similar learning curve, different shape |

**Verdict: Style 1 is preferred.** The signal flow is more linear and readable. The
`for each channel { }` block is semantically self-explanatory in a way that `parallel(N)
{ }` is not — the former says "do this to every channel"; the latter says "run N copies"
which is the same but less directly expressive. Both styles compile to identical CellList
operations. Style 1 is recommended.

---

### 13E: ML Use Cases That Benefit from Multi-Channel Composition

The user observed that ML models sometimes want the same multi-channel composition patterns
and currently find them awkward to express. Three concrete cases follow.

---

#### ML Case 1: Multi-Head Attention Without `concat_blocks` Gymnastics

Today, `skytnt_block.pdsl` expresses attention as:

```pdsl
attention(q_proj, k_proj, v_proj, o_proj, num_heads, head_dim)
```

This is a black-box primitive. It works, but the per-head computation is invisible. If you
wanted to write a custom attention variant — e.g., adding ALiBi position bias, sparse
attention, or per-head dropout — you currently have no way to decompose the `attention`
primitive without writing a new Java primitive in `PdslInterpreter.java`.

With multi-channel PDSL, attention can be decomposed as:

```pdsl
layer multi_head_attention_decomposed(q_proj: weight, k_proj: weight, v_proj: weight,
                                       o_proj: weight, num_heads: int, head_dim: int) {

    // Project to Q, K, V (single-channel output: [3 * num_heads * head_dim])
    concat_blocks({ dense(q_proj) }, { dense(k_proj) }, { dense(v_proj) })

    // Fan out: one channel per head, each carrying [3 * head_dim] (Q, K, V for that head)
    fan_out(num_heads)

    // Per-head: extract Q/K/V slices and compute attention
    for each channel {
        tap(channel * 3 * head_dim, head_dim)   // slice Q for this head
        // (similarly K and V — actual syntax TBD per PDSL slice semantics)
        scaled_dot_product(head_dim)             // within-head attention
    }

    // Concatenate heads, project to output dimension
    concat_channels()                            // num_heads → 1 concatenated
    dense(o_proj)
}
```

This is significantly more readable than the current alternative for 8 heads:

```pdsl
// WITHOUT multi-channel — must enumerate all 8 heads in concat_blocks:
concat_blocks(
    { slice(0, head_dim); slice(num_heads * head_dim, head_dim); slice(2*num_heads*head_dim, head_dim);
      scaled_dot_product(head_dim) },  // head 0
    { slice(head_dim, head_dim); ... ; scaled_dot_product(head_dim) },  // head 1
    { slice(2*head_dim, head_dim); ... ; scaled_dot_product(head_dim) }, // head 2
    // ... 5 more head blocks ...
)
```

For 8 heads, the `concat_blocks` version requires 8 explicit branches with manual slice
index arithmetic. The `for each channel` version is the same 4 lines regardless of head count.
Note: this also requires `concat_channels()` (not `sum_channels()`) for the head-merge step,
which shows that the aggregation operation at the end can vary (sum, concat, mean, max).

---

#### ML Case 2: Mixture-of-Experts Without Branch Combinatorics

A sparse mixture-of-experts layer routes each input to k of N expert feedforward networks
and combines their outputs with learned gate weights. In standard PDSL today, N=4 experts
requires:

```pdsl
// Today: 4 explicit experts via concat_blocks
concat_blocks(
    { dense(expert0_w1); silu(); dense(expert0_w2) },  // expert 0
    { dense(expert1_w1); silu(); dense(expert1_w2) },  // expert 1
    { dense(expert2_w1); silu(); dense(expert2_w2) },  // expert 2
    { dense(expert3_w1); silu(); dense(expert3_w2) }   // expert 3
)
// Then weight by gate scores and sum... no existing primitive for this
```

With multi-channel PDSL and a `route(gate_weights)` aggregate:

```pdsl
layer mixture_of_experts(expert_w1: weight, expert_w2: weight, gate_weights: weight,
                          num_experts: int) {

    // Step 1: Compute gate distribution over experts
    data gate { scores: weight }  // gate(gate_weights) produces per-expert weights

    // Step 2: Fan out input to all experts
    fan_out(num_experts)

    // Step 3: Per-expert computation
    for each channel {
        dense(expert_w1[channel])    // per-expert weight slice
        silu()
        dense(expert_w2[channel])
    }

    // Step 4: Gate-weighted sum (transmission matrix = gate scores)
    route(gate_scores)               // gate_scores: [1, num_experts] soft routing
    sum_channels()                   // weighted combination
}
```

The `route(gate_scores)` is the MoE routing — the same mechanism as `mself(transmission)`
in the delay bank, now expressed at the ML layer level. This is the conceptual unification:
audio cross-channel routing and ML expert gating are both instances of the same
gene/weight-controlled routing operation.

---

#### ML Case 3: Parallel Residual Streams

Some architectures (e.g., Mamba-style selective SSMs or multi-path transformers) run
multiple independent streams through the same layer stack, then merge. In current PDSL:

```pdsl
// Two parallel residual streams — awkward with concat_blocks:
accum_blocks(
    { dense(w_stream0); relu() },    // stream 0 residual
    { dense(w_stream1); relu() }     // stream 1 residual
)
// accum_blocks accumulates (sums) — correct for residual, but 2 streams hard-coded
```

With `fan_out`/`for each channel`/`sum_channels`:

```pdsl
layer dual_residual(stream_weights: weight, num_streams: int) {
    fan_out(num_streams)
    for each channel {
        dense(stream_weights[channel])
        relu()
    }
    sum_channels()   // mean_channels() for mean-pooling across streams
}
```

Adding a third stream now requires only changing the `num_streams` parameter, not rewriting
the layer definition.

---

### 13F: Re-evaluation of Approaches A through D in Light of Multi-Channel

Part 12F scored Approaches A–D on two axes. The multi-channel question adds a third axis:

- **Expressiveness for cross-channel routing (1–5):** Can the approach express
  `m(adapter, destinations, gene)` and `mself(adapter, gene)` in PDSL, or must
  these always remain Java CellList code?

The revised table:

| Dimension | A: Source/Sink Decls | B: PDSL→CellList | C: Block Adapter | D: Pipeline Keyword |
|-----------|---------------------|------------------|-----------------|---------------------|
| **PDSL intuitive-as-flow (1–5)** | 4 | 3 | 2 | **5** |
| **CellList-non-destructive (1–5)** | 3 | **5** | **5** | 4 |
| **Cross-channel routing expressiveness (1–5)** | 2 | **5** | 1 | **5** (with extensions) |
| **Implementation cost** | Medium | High | **Low** | Medium-High |
| **Multi-channel additions needed** | `channels` declaration, cross-channel syntax in `source`/`sink` | `for each channel` body, `route()` → `.m()`/`.mself()` compilation | Nothing — cross-channel cannot be expressed; must remain Java | `channels` header param, `for each channel { }`, `route()`, `sum_channels()` |

**How multi-channel changes the recommendation:**

**Approach B gains significantly on cross-channel routing expressiveness.** If PDSL compiles
to CellList (Approach B), then `for each channel { }` body maps directly to the Java lambda
arguments that CellList's `.f(i -> ...)`, `.d(i -> ...)`, `.m(i -> ...)` already expect.
The `route(transmission_gene)` keyword maps directly to `.m(fi(), destinations, gene)`.
The `sum_channels()` keyword maps directly to `.sum()`. This is the only approach where
the PDSL multi-channel constructs have a *one-to-one* structural mapping to the CellList
API — no intermediate `PdslTemporalBlock` needed, no new compilation target. The generated
CellList is literally what a human would write.

However, the maintenance burden identified in Part 12G remains: every future primitive
needs two implementations (Block and CellList). The `accum_blocks` → `branch()/and()/sum()`
topology mapping is non-trivial. These costs do not go away. Approach B remains the most
technically direct path for multi-channel, but the ongoing maintenance burden and the
topology-mapping problem make it impractical as the primary approach.

**Approach C drops to effectively 0 for cross-channel routing.** The Block Adapter approach
works by compiling PDSL to a `Block`/`CompiledModel` and wrapping it in a `Temporal` adapter.
A Block operates on a single tensor — it cannot express "route channel i to channel j via a
gene-controlled matrix" because that operation requires multiple independent Cell instances
with separate tick ordering. The `mself(transmission)` feedback grid is fundamentally a
per-Cell-level operation that has no equivalent tensor contraction. Any multi-channel audio
pipeline that uses transmission routing cannot be expressed under Approach C. For
`MixdownManager`, which is the primary migration target and which uses `mself(transmission)`
as a central feature (lines 665-666), Approach C provides zero benefit on the multi-channel
operations that matter most.

**Approach D (Pipeline keyword) extends cleanly to multi-channel.** The `pipeline` keyword
already introduces `input`/`output` declarations. Adding `channels: N` to the header and
`for each channel { }` to the body grammar is a natural extension of the same syntax. The
compiled `PdslTemporalBlock` would internally manage a CellList of N cells, with `route()`
mapping to `.m()`/`.mself()` and `sum_channels()` mapping to `.sum()`. The extension is
additive and does not change any existing `layer` or `pipeline` without `channels`.

**Approach A (Source/Sink declarations) is neutral for multi-channel.** The `source`/`sink`
declarations describe where data enters and exits the pipeline, not how channels relate
internally. Adding `channels` support would require extending the `source name[N]` syntax
— possible, but not architecturally cleaner than Approach D.

**Does the recommendation change?** Not fundamentally. Approach D remains the correct
Phase 2 target. But multi-channel considerations sharpen two conclusions:

1. **Approach D must be designed with multi-channel in mind from day one.** If
   `PdslTemporalBlock` is initially designed as single-channel only, retrofitting N-channel
   support later is harder than building it in. The `channels` header parameter and
   `for each channel` grammar should be included in the Approach D design specification
   even if not implemented in the first iteration.

2. **Approach C is more limited than Part 12 implied.** Part 12G described Approach C as
   a valid Phase 1 migration vehicle that covers "most of MixdownManager." In light of
   Part 13, it covers only the per-channel single-channel path (HP filter, volume, LP
   filter). The cross-channel transmission routing — which is one of the two distinctive
   features of `MixdownManager` — cannot be expressed in Approach C and must permanently
   remain as Java CellList code. This is still acceptable for Phase 1, but Phase 1 is
   more narrowly scoped than Part 12 suggested.

---

### 13G: Recommendation for the Multi-Channel Question

#### The recommended syntax: Style 1 (Channel-Arity as Property), within Approach D

The best PDSL syntax for multi-channel composition is Style 1 extended into the `pipeline`
keyword of Approach D:

```pdsl
pipeline my_pipeline(channels: int, ...) {
    input  source_channels[channels] -> [1, signal_size]
    output master_out

    state per_channel_state(channels: int) {
        buffers: weight    // [channels, max_samples] — automatically per-channel
        heads: weight      // [channels]
    }

    for each channel {         // per-channel operations
        fir(coeffs)
        delay(times[channel], s.buffers[channel], s.heads[channel])
    }

    route(routing_gene)        // cross-channel: .m(fi(), dests, gene)
    sum_channels()             // final collapse
}
```

This is readable, unambiguous as a signal flow, and maps to CellList operations in
the obvious way.

#### How this interacts with the Phase 1 (Approach C) prototype plan

Part 12G recommends Approach C as the Phase 1 migration vehicle. Multi-channel awareness
does not change this recommendation, but it sharpens the scope:

**Phase 1 (Approach C) covers:**
- Per-channel single-channel PDSL layers: `mixdown_main` and `mixdown_channel` (Part 11)
- These layers express: `highpass → scale → accum_blocks(identity, wet+delay) → lowpass`
- The `PdslChannelAdapter` wraps one `CompiledModel` per channel, fires once per buffer
- This replaces the Java-wired HP/volume/LP chain in `MixdownManager.createCells()`

**Phase 1 (Approach C) does NOT cover:**
- Cross-channel transmission routing (`efx.m(fi(), delays, tg)` at line 664)
- Cross-channel feedback grid (`.mself(fi(), transmission, ...)` at line 665-666)
- `sum()` of multi-channel results — still done by Java CellList code
- These must remain as Java CellList code in `MixdownManager.createEfx()`

**Phase 2 (Approach D) covers everything**, including multi-channel constructs, once
`PdslTemporalBlock` is extended with the `channels` header parameter and `for each channel`
grammar.

#### New multi-channel primitives needed (beyond Part 8's list)

| Primitive | Description | Compiles to |
|-----------|-------------|-------------|
| `fan_out(N)` | Replicate 1 input to N channels | `branch(f0, f1, ..., fN-1)` with N identical functions |
| `for each channel { }` | Per-channel body application | Lambda argument to `.f(i -> ...)`, `.d(i -> ...)`, etc. |
| `route(gene_or_weight)` | Cross-channel routing matrix | `.m(fi(), dests, gene)` or `.mself(fi(), gene, ...)` |
| `sum_channels()` | Collapse N channels to 1 via sum | `.sum()` — `SummationCell` |
| `mean_channels()` | Collapse N channels to 1 via mean | `.sum()` then `scale(1.0/N)` |
| `concat_channels()` | Concatenate N channels (for multi-head) | Tensor concat across channel dim |
| `tap(i)` | Select channel i for separate processing | `branch(stemCell, passThroughCell)[1]` |

#### Appendix to Part 12G: Revision in Light of Part 13

*Part 12G recommended Approach C as Phase 1 and Approach D as Phase 2. These recommendations
stand, with the following addenda:*

**Addendum 1:** The Phase 1 Approach C prototype covers only the single-channel per-channel
layers in `MixdownManager`. The cross-channel transmission routing (`mself(transmission)`)
cannot be expressed in Approach C and must remain Java CellList code indefinitely under
this approach. This is not a blocker for Phase 1 — the per-channel layers are valuable
independently — but it means the PDSL migration of `MixdownManager` is inherently two-phase:
per-channel layers first (Approach C), cross-channel routing second (Approach D with
multi-channel extensions).

**Addendum 2:** The `PdslTemporalBlock` design for Approach D should include the
`channels` header parameter and `for each channel` grammar from the initial design, even if
only a subset is implemented first. Designing it single-channel-only and retrofitting
multi-channel later is unnecessary technical debt. The CellList compilation target naturally
accommodates channels ≥ 1 without architectural change — N=1 is just a CellList with one cell.

**Addendum 3:** Approach B's cross-channel routing score (`5/5`) shows that for
multi-channel PDSL specifically, Approach B is the most direct path. If the dual-compilation
maintenance burden and `accum_blocks` topology problem were resolved by a single shared
compilation layer (e.g., PDSL→intermediate IR→CellList and PDSL→intermediate IR→Block),
Approach B would be the winner. This is a design direction worth tracking as the PDSL
interpreter matures, but it is not recommended for the current phase.

**Addendum 4:** The ML multi-channel use cases (Part 13E) show that `for each channel`,
`fan_out`, `route`, and `sum_channels` benefit ML definitions as well as audio DSP. These
are not audio-specific primitives — they belong in the core PDSL grammar alongside `accum_blocks`
and `concat_blocks`. The multi-channel question is therefore not an audio DSP edge case: it
is a gap in the expressiveness of the PDSL language itself, relevant to any domain that
operates on sets of parallel signals.
