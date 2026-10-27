# PDSL Audio DSP Reference

Reference for the PDSL audio DSP substrate — the primitives, multi-channel constructs,
and argument forms used when writing new PDSL audio DSL. PDSL `layer` bodies compile to
`Block`/`Model` graphs backed by the same `Producer`/`CollectionProducer` framework as the
rest of the project; the audio primitives add stateful and multi-channel block factories on
top of the interpreter core. The language itself (grammar, statements, name resolution and the
core built-in catalog) is described in the [PDSL Language Reference](pdsl-language.md).

The production consumer of this substrate is the `AudioScene` real-time mixdown
(`AudioSceneRealtimeRunner.createPdsl` driving `mixdown_master_wet`); see
[The AudioScene mixdown on PDSL](#the-audioscene-mixdown-on-pdsl) below for how it behaves
relative to the legacy `CellList` mixdown and the constraints callers must respect.

Note on the stateful ring primitives (`delay`, `feedback`, `delay_network`): a
block-parallel ring only holds samples of the requested age inside a band set by its
write order. The read-before-write stages (`feedback`, `delay_network`) support
`signalSize ≤ delay ≤ ringSize` — a sub-frame delay would be an intra-frame
recurrence, which block-parallel evaluation cannot express. The write-first `delay`
primitive supports `0 ≤ delay ≤ ringSize − signalSize` — a one-frame ring holds only a
zero delay. The kernels clamp delays into these bands
(see `MultiChannelDspFeatures.routedRingRead`); size rings so the intended delays sit
inside them.

## Where the code lives

| Concern | Location |
|---------|----------|
| `.pdsl` resources | `engine/ml/src/main/resources/pdsl/` (audio under `pdsl/audio/`) |
| Interpreter + core primitives | `domain/graph/.../dsl/PdslInterpreter.java` (core primitives delegated to `PdslBuiltins.java`) |
| Attention and rotary primitives | `engine/ml/.../ml/dsl/AttentionPrimitives.java`, a `PdslExtension` every interpreter registers |
| Argument normalisation | `domain/graph/.../dsl/PdslPrimitiveContext.java` (`toProducer`); bank form `PdslChannelBank.java` |
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

These files live in `engine/ml/src/main/resources/pdsl/audio/`.

| File | What it defines |
|------|-----------------|
| `oobleck_residual_block.pdsl` | The Oobleck codec's residual unit — `oobleck_residual_block`: `accum` around Snake → `conv1d` (kernel 7) → Snake → `conv1d` (kernel 1). Loaded by `OobleckCodec.buildResidualBlock`. |
| `oobleck_codec.pdsl` | The Oobleck codec's stages, which call the residual unit: `oobleck_input_projection`, `oobleck_encoder_block` (three residual units → Snake → strided `conv1d`), `oobleck_encoder_output`, `oobleck_decoder_block` (Snake → `conv_transpose1d` → three residual units) and `oobleck_decoder_output`. `OobleckEncoder` and `OobleckDecoder` build one layer per stage through the `OobleckCodec` stage builders. Because `oobleck_codec.pdsl` imports `oobleck_residual_block.pdsl`, parsing it alone (via `PdslLoader.parseResource`) resolves the residual unit into the same program. |
| `patch_transform.pdsl` | The parameter-free patched pretransform — `patch_encode` (fold runs of `patch_size` samples into the channel axis: `reshape` → `permute` → `reshape`) and its exact inverse `patch_decode`. Uses only core primitives, so `PatchedPretransform.encode`/`decode` build these layers with a plain `PdslLoader`. |
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
| `delay_network` | `delay_network(delay_samples, feedback_matrix, buffer, heads)` | Multi-tap feedback delay network (FDN); per-frame matrix `[channels, channels]`, multi-frame ring. |
| `feedback` | `feedback(delay_samples, transmission_matrix, passthrough_matrix, buffer, heads[, delay_samples_prev])` | Block-parallel feedback delay network — the PDSL analogue of `CellList.mself`. Delayed output is routed back into the ring via `transmission_matrix` and emitted via `passthrough_matrix`. With `delay_samples_prev`, each channel's delay follows a per-sample linear trajectory from the previous frame's value to `delay_samples`, using fractional-position interpolation to preserve the pitch bend of delay-time modulation. |

### Core/domain-agnostic primitives (interpreter core)

`PdslBuiltins` supplies the domain-agnostic built-ins whether or not the audio primitives are
registered, and they are usable in the same layer bodies. The ones audio layers use most are
`identity`, `scale` (which also takes a `producer([1])` factor, and a per-channel bank under
vectorized `for each channel`), `repeat`, `sum_channels` and `capture`. The complete catalog is
in the [PDSL Language Reference](pdsl-language.md#builtin-catalog): each built-in's call forms,
input and output shapes, the method it lowers to, and its gradient behavior. That page also
covers name resolution (a program's own layers shadow registered primitives and built-ins), how a
layer called from another layer gets its input shape, and imports.

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
| `accum_blocks(a, b, …)` | branches → sum | Each brace-delimited sub-block receives the same input; the output is the element-wise sum of their outputs. This is heterogeneous branching (e.g. dry + wet). The backward pass delivers the full output gradient to every sub-block and sums their input-gradients, so each summed path trains. Distinct from `sum_channels()`, which reduces one tensor's channel axis. |

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

## Imports

A `.pdsl` file declares the assets it depends on with `import "/absolute/path.pdsl"` statements
before its first definition, and `PdslLoader.parseResource` resolves them transitively.
`oobleck_codec.pdsl`, for example, imports `oobleck_residual_block.pdsl`. The rules are in the
[Imports section of the language reference](pdsl-language.md#imports): resources are
de-duplicated across diamonds, while duplicate definitions and import cycles are errors.

## The AudioScene mixdown on PDSL

The PDSL mixdown (`mixdown_manager.pdsl`; the default since `MixdownManager.enablePdslMixdown`)
replaced a per-sample `CellList` signal path with block-parallel evaluation over one buffer at a
time. Most of the signal path is identical; the differences below are deliberate or accepted
consequences of block-parallel evaluation.

### Behaviour

- **Feedback is sample-exact above a one-buffer floor.** For a delay `D ≥ signalSize` in an
  adequately sized ring, the feedback network evaluates `y[t] = x[t] + (M·y)[t−D]` exactly, so
  repeats land on exact multiples of the delay, as in the per-sample recurrence. What the buffer
  size sets is the *floor*: no regeneration interval shorter than one buffer can exist
  block-parallel (the ring-band invariant above), and short delays are quantized up to one frame.
  The floor scales linearly with buffer size (about 93 ms at 4096 frames).
- **Continuous delay-time modulation** is a fractional gather, not a recurrence. The network
  receives the previous and current per-line delays, forms a linear delay trajectory across the
  frame and interpolates between neighbouring ring samples, so shortening a delay pitches up and
  lengthening it pitches down. A static delay stays integer-exact, and the trajectory re-anchors
  at each buffer boundary so it cannot drift.
- **Automation is refreshed once per buffer.** Hot-bus gains interpolate between the previous and
  current values across the frame (`ramp_scale`); filter coefficients and other un-ramped values
  step per buffer.
- **Filters are FIR approximations.** The main high-pass and master low-pass use short
  truncated-biquad impulse-response tables (one row selected per buffer), matched to the
  12 dB/octave shape of the legacy biquads. The efx-loop filter uses the same windowed-sinc FIR
  coefficients as the legacy path. The wet bus is a high-pass followed by a low-pass, as in the
  legacy cascade.
- **Two regeneration structures, as in the legacy path:** a per-channel echo (a diagonal
  `delay_network` with per-channel self-feedback, inside the apply chain, on both the main and
  wet buses of a wet channel), and a bus-level network that sends into its first line, modulates
  line lengths and recirculates through the genome transmission matrix.
- **The reverb is deterministic.** It uses 32 taps with golden-ratio spacing over a
  seconds-denominated ring. The legacy reverb used 128 randomly sized lines, re-randomized every
  run.
- **Known remaining differences:**
  - The master is dual-mono: one rendered master streams to both stereo writers, and there is no
    pan.
  - Floating-point reduction order can drift across recompiles, by about 1–3% RMS late in
    feedback-heavy renders.
  - Some filter coefficients are sampled when the arguments are built rather than refreshed
    live.

### Constraints for callers

- **Don't force a single backend.** The mixdown relies on hybrid routing: JNI for the recurrent
  DSP loop and Metal for the parallel pattern kernels. Forcing `AR_HARDWARE_DRIVER=mtl` fails to
  compile the per-frame loop, which exceeds Metal's 31-buffer kernel argument limit. Forcing
  `native` is many times slower. Leave the driver unset.
- **Sustained rendering needs `-DAR_PATTERN_CACHE_PERSIST=true`.** Without it, note-audio
  deallocation churn leaks native memory on every buffer. `PatternLayerManager` reads the flag
  once, at class load, so it must be a JVM `-D` argument (for tests, in the surefire `argLine`),
  not something set at runtime. Callers that switch arrangements at runtime must leave it off or
  call `invalidateCaches()`.
- **Block-outward integration.** To connect a compiled PDSL `Block` to a `Cell`/`CellList`
  consumer, make the consumer accept the `Block`; never wrap the `Block` to masquerade as a
  `CellList`. The real-time contract is `TemporalCellular` (`setup()`/`tick()`/`reset()`), which
  `AudioSceneRealtimeRunner` implements on top of the PDSL block.
- **Channel selection.** The PDSL path accepts a single channel or a zero-based contiguous prefix
  of channels (`MixdownManager.supportsPdslMixdown`). A non-contiguous subset falls back to the
  `CellList` mixdown, because the feedback grid is indexed by bank position.
