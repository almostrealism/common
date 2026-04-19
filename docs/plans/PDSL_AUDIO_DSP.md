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

## Appendix A: Current PDSL Files Reference

| File | What it defines |
|------|-----------------|
| `pdsl/gru_cell.pdsl` | GRU cell (sigmoid gates, tanh cell state update) |
| `pdsl/gru_block.pdsl` | GRU block with data slice definitions |
| `pdsl/midi/skytnt_block.pdsl` | LLaMA-style transformer block (attention + SwiGLU FFN) |
| `pdsl/audio/efx_channel.pdsl` | EFX channel FIR filter + scale layers (Phase A complete) |

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
