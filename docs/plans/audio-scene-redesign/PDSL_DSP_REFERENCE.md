# PDSL Audio DSP Reference

Reference for the PDSL audio DSP substrate — the primitives, multi-channel constructs,
and argument forms used when writing new PDSL audio DSL. PDSL `layer` bodies compile to
`Block`/`Model` graphs backed by the same `Producer`/`CollectionProducer` framework as the
rest of the project; the audio primitives add stateful and multi-channel block factories on
top of the interpreter core.

For broader context on the audio path this substrate backs, see
[NEXT_STEP.md](NEXT_STEP.md) and [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

## Where the code lives

| Concern | Location |
|---------|----------|
| `.pdsl` resources | `engine/ml/src/main/resources/pdsl/` (audio under `pdsl/audio/`) |
| Interpreter + core/ML primitives | `engine/ml/.../ml/dsl/PdslInterpreter.java` (core primitives delegated to `PdslBuiltins.java`) |
| Argument normalisation | `engine/ml/.../ml/dsl/PdslPrimitiveContext.java` (`toProducer`); bank form `PdslChannelBank.java` |
| Audio primitive dispatch | `studio/compose/.../studio/dsl/audio/AudioDspPrimitives.java` |
| Multi-channel block factories | `studio/compose/.../studio/dsl/audio/MultiChannelDspFeatures.java` |

The interpreter core ships only domain-agnostic primitives. Audio primitives are attached
by registering `AudioDspPrimitives` with a fresh interpreter — the canonical loader is:

```java
PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
```

`registerWith` binds the audio primitives and installs the multi-channel dispatcher
(`AudioDspPrimitives.registerWith`).

## Audio DSL files

All four files live in `engine/ml/src/main/resources/pdsl/audio/`.

| File | What it defines |
|------|-----------------|
| `efx_channel.pdsl` | EFX channel layers — `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix`, the composite `efx_channel` (dry + filtered/scaled/delayed wet via `accum_blocks`), and the `feedback_comb` closed-loop comb. States: `efx_delay_state`, `feedback_comb_state`. |
| `mixdown_channel.pdsl` | Single-channel mixdown — `mixdown_main` (HP → `scale` → LP) and `mixdown_channel` (full path with wet/delay). State: `mixdown_delay_state`. |
| `delay_feedback_bank.pdsl` | Multi-channel delay bank — `delay_feedback_bank`: `repeat` → per-channel `delay` → `route` → `sum_channels`. State: `delay_bank_state`. |
| `mixdown_manager.pdsl` | Top-level multi-channel mixdown — `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_efx_bus_rectangular`, `mixdown_master`, `mixdown_master_wet`, `mixdown_reverb_bus` (plus `rect_route_probe`/`mixdown_hetero_branch` capability layers). States: `mixdown_efx_state`, `mixdown_reverb_state`, `mixdown_efx_feedback_state`. |

## Primitive catalog

### Audio-domain primitives (`AudioDspPrimitives`)

Stateless:

| Primitive | Signature | Behavior |
|-----------|-----------|----------|
| `fir` | `fir(coefficients)` | FIR filter via `MultiOrderFilter`; coefficients shape `[fir_taps]`, or a `[channels, taps]` bank when vectorized. |
| `lowpass` | `lowpass(cutoff, sampleRate, filterOrder)` | FIR low-pass; cutoff is `producer([1])`, `sampleRate`/`filterOrder` are compile-time ints. |
| `highpass` | `highpass(cutoff, sampleRate, filterOrder)` | FIR high-pass; same argument shape as `lowpass`. |
| `clip` | `clip(lo, hi)` | Element-wise hard clamp into `[lo, hi]`; `lo < hi` required. The PDSL counterpart of the Java hard bounds. |

Stateful (state is a caller-owned `PackedCollection` declared in a `state` block; it is
read and written every `forward()` pass, so it persists across frames — one tick = one
frame):

| Primitive | Signature | State |
|-----------|-----------|-------|
| `biquad` | `biquad(b0, b1, b2, a1, a2, history)` | Biquad IIR; `history` = `[x1, x2, y1, y2]` (shape `[4]`). Coefficients are `producer([1])`. |
| `delay` | `delay(delaySamples, buffer, head)` | Integer-sample ring delay; `buffer` = ring (≥ `signal_size`), `head` = write position `[1]`. `delaySamples` accepts `producer([1])`. |
| `lfo` | `lfo(freqHz, sampleRate, phase)` | Sinusoidal LFO; `phase` = accumulator `[1]`, `freqHz` is `producer([1])`. |
| `delay_network` | `delay_network(delaySamples, feedbackMatrix, buffer, heads)` | Multi-tap feedback delay network (FDN); per-frame matrix `[channels, channels]`, multi-frame ring. |
| `feedback` | `feedback(delaySamples, transmissionMatrix, passthroughMatrix, buffer, heads)` | Block-parallel feedback delay network — the PDSL analogue of `CellList.mself`. Delayed output is routed back into the ring via `transmissionMatrix` and emitted via `passthroughMatrix`. |

### Core/domain-agnostic primitives (interpreter core)

Supplied by `PdslBuiltins` regardless of audio registration — pure tensor ops with no
audio-domain assumption:

| Primitive | Signature | Behavior |
|-----------|-----------|----------|
| `identity` | `identity()` | Pass-through (zero computation). |
| `scale` | `scale(factor)` | Element-wise multiply; `factor` accepts `producer([1])`. |
| `repeat` | `repeat(n)` | Axis-0 replication, `[1, S]` → `[n, S]` (`CollectionProducer.repeat(0, n)`). |
| `sum_channels` | `sum_channels()` | Axis-0 reduction, `[C, S]` → `[1, S]`. |

(`PdslBuiltins` also supplies the ML primitives — `dense`, `rmsnorm`, `softmax`, the
activations, `slice`, `reshape`, `range`, `lerp`, `rope_rotation`, `attention`,
`transformer`, `feed_forward` — usable in the same layer bodies.)

There is **no `choice()` primitive.** A `Choice` cannot be code-generated inside a compiled
PDSL model. Gene-driven HP/LP filter selection is performed host-side in the genome→args
adapter as a generatable arithmetic blend — `coeffs = hp * (1 - sel) + lp * sel` — and the
resulting coefficient bank is passed to `fir`. Describe filter selection this way; do not
reach for a conditional primitive.

## Multi-channel constructs

A multi-channel layer carries an `int channels` header parameter; it is in scope throughout
the body and drives every multi-channel construct.

```pdsl
layer delay_feedback_bank(channels: int, signal_size: int, delay_samples: int,
                          transmission: weight) -> [1, signal_size] {
    repeat(channels)                                    // [1, S] -> [channels, S]
    for each channel {
        delay(delay_samples, buffers[channel], heads[channel])
    }
    route(transmission)                                 // [channels, S] -> [out, S]
    sum_channels()                                      // [C, S] -> [1, S]
}
```

| Construct | Shape effect | Notes |
|-----------|--------------|-------|
| `channels: int` (header) | — | Channel multiplicity; consumed by all constructs below. |
| `repeat(N)` | `[1, S]` → `[N, S]` | Axis-0 fan-out. |
| `for each channel { }` | `[C, S]` → `[C, S]` | Applies the body per channel; `channel` is bound to the index. State and producer rows are sliced with `expr[channel]`. |
| `route(matrix)` | `[in, S]` → `[out, S]` | `out[i] = sum_j matrix[i,j] * in[j]`. Matrix must be 2D and its first axis must equal the upstream channel count. Square and rectangular both supported; a rectangular route updates the downstream channel count via the primitive context. |
| `sum_channels()` | `[C, S]` → `[1, S]` | Within-tensor channel-axis reduction. |
| `accum_blocks(a, b, …)` | branches → sum | Each brace-delimited sub-block receives the same input; the output is the element-wise sum of their outputs. This is heterogeneous branching (e.g. dry + wet). Distinct from `sum_channels()`, which reduces one tensor's channel axis. |

### Vectorized `for each channel`

`for each channel` is **vectorized by default** (`PdslInterpreter.enableVectorizedForEach`
defaults `true`; set `AR_PDSL_VECTOR_FOREACH=disabled` to turn it off). When the body is
channel-uniform and the upstream signal is channel-aligned, the body compiles **once** over
`[channels, signalSize]`: subscripted per-channel arguments resolve to whole banks
(`PdslChannelBank`), and the bank-aware primitives (`fir`, `scale`, `delay`) apply them in a
single kernel. Any body whose primitives cannot accept the bank form falls back
automatically to per-channel dispatch (one sub-block per channel) — no source change is
needed; the interpreter catches the rejection and re-interprets per channel.

## Producer-valued arguments

Every primitive argument that the live system drives with a value (rather than a build-time
constant) accepts the producer form `producer([shape])`. The shape is part of the type and
uses the same bracketed literal as layer output annotations:

```pdsl
layer mixdown_main_automated(channels: int, signal_size: int,
                             hp_cutoff: producer([1]), volume: producer([1]),
                             sample_rate: scalar, filter_order: scalar) -> [1, signal_size] {
    for each channel {
        highpass(hp_cutoff, sample_rate, filter_order)
        scale(volume)
    }
    sum_channels()
}
```

- Producers bound at dispatch through `PdslPrimitiveContext.toProducer(value, shape, name)`
  — the single point that accepts a `Number`, a `PackedCollection`, or a
  `Producer<PackedCollection>` at the declared shape. Per-primitive code never discriminates
  between the forms.
- Shape drives intent. `producer([1])` is a single value; `producer([channels])` is a
  per-channel row sliced by `expr[channel]` inside `for each channel`;
  `producer([channels, channels])` is a time-varying routing matrix; `producer([fir_taps])`
  is a coefficient vector. The argument's *meaning* (automation envelope, gene-derived slot,
  fixed constant) lives in its name, not its type.
- A literal call compiles identically. A constant-in-time producer constant-folds to the
  same kernel a literal scalar produces, so fixed-parameter PDSL files do not regress.

Confirmed shapes today: `scale`, `highpass`, `lowpass`, `biquad`, `delay`, `lfo` accept
`producer([1])`; `fir` accepts `producer([fir_taps])`; `route` accepts `producer([N, M])`
(square or rectangular). Subscript indexing (`coeffs[channel]`) accepts producers as well as
`PackedCollection`s, so per-channel slicing of a `producer([channels, taps])` bank falls out
of the same plumbing.

## State blocks

DSP state is declared with a `state` block — syntactically identical to a `data` block, but
signaling write-intent:

```pdsl
state delay_bank_state {
    buffers: weight     // channels * bufSize ring storage, zero-initialised
    heads: weight       // per-channel write positions
}
```

The caller owns and supplies the backing `PackedCollection`s; they persist across
`forward()` calls. State is written inside the primitive's `push()` via `into(...)` — a
`CollectionProducer` write, never `setMem()`/`toDouble()` — so a stateful PDSL block behaves
like a `CachedStateCell` from the cell graph's perspective without any adapter code.
