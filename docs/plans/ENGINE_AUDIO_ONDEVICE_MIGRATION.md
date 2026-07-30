# engine/audio On-Device Migration — Handoff Plan

This document hands off the remaining work to move host-side DSP computation in
`engine/audio` onto the device, as part of the `setMem` enforcement effort. It
records the pattern already established (device biquad coefficients), then
specifies the three remaining pieces in priority order.

Read [`SETMEM_ENFORCEMENT_POSTMORTEM.md`](SETMEM_ENFORCEMENT_POSTMORTEM.md) first
if you are new to this effort: it explains why wrapping a host `setMem` in
`a(cp(x), c(v))` is *worse* than the original, not a fix.

---

## 0. Context: what is already done

The `feature/setmem-policy-phases/9` cleanup reverted phase 9's bad migrations
(host scalar setters and per-element loops wrapped in per-value kernels) back to
their baselined `setMem` forms, keeping only the genuinely-correct conversions
(`setFrom`, `into`, device `sin(integers(...))`). Those reverted setters are
grandfathered in `engine/utils/.../setmem-violation-baseline.tsv`; they are
storage-layer scalar writes awaiting the *real* migration described here —
computing the values on the device and removing the host-computing callers.

One real migration is complete and in CI as the reference pattern:

**Device biquad coefficients.** `BiquadFilterData` gained a coefficient producer
for each RBJ response (`lowPassCoefficients`, `highPassCoefficients`,
`bandPassCoefficients`, `notchCoefficients`, `allPassCoefficients`,
`peakingEQCoefficients`, `lowShelfCoefficients`, `highShelfCoefficients`). Each
takes the cutoff/center frequency and Q (or gain) as `Producer<PackedCollection>`
and returns the normalized `(5)` `[b0, b1, b2, a1, a2]` computed entirely in the
graph. `updateCoefficients(CollectionProducer)` writes that vector into the
contiguous coefficient block (`coefficients()`) in a single device assignment.
`BiquadCoefficientParityTest` checks each response against the host
`BiquadFilterCell.calculate*Coefficients` reference and proves one compiled
operation handles a runtime-varying cutoff.

The biquad *signal path* (`BiquadFilterCell.push`) was already on the device;
only the coefficients were host-computed. The coefficient producers are the
enabling foundation — but nothing calls them yet. Case 2 below is where they get
used.

---

## 1. Reusable patterns and primitives

These apply to every case; they are the lessons the biquad work encodes.

### The rule that actually matters

The test is **not** "does it use Producers?" — it is **"does a host value vary
per invocation?"**

- `a(cp(x), c(1.0))` — the constant is invariant, so the kernel compiles **once**
  and is reused forever. Correct (this is what `TimeCell` does for its counter).
- `a(cp(x), c((double) step))` — the constant varies per call, so a **new kernel
  compiles per distinct value**, exhausting the native operator pool. Wrong.

To feed a *varying* value into a computation without a kernel per value, the value
must flow as **device data** (read from a `PackedCollection` via `cp(buffer)`),
not as a baked-in `c(hostDouble)`. A single compiled operation then reads the
buffer's current contents each time it runs.

### Sanctioned ways a value crosses host → device

- **Control-boundary scalar write** (a user/config parameter set occasionally, not
  per sample): `buffer.fill(value)`. `fill` is on the detector's sanctioned
  surface for scalar writes (fewer than 16 individual values). Use it for
  parameter setters — a cutoff, an attack time — that change on user action, not
  per tick.
- **Device-to-device copy**: `dest.setFrom(offset, src, srcOffset, length)` or
  `cp(src).into(dest).evaluate()`. No host value involved.
- **Genuine literal vector**: `setMem(0, 1.0, 2.0)` is permitted as-is.

Never introduce a new non-literal `setMem` (it will not be in the baseline and CI
will fail), and never introduce an `a(cp, c(varyingHostValue))`.

### Device math available (all reachable from `CodeFeatures`)

- Trig: `sin`, `cos` (`GeometryFeatures`).
- Arithmetic on producers: `add`, `subtract`, `multiply`, `divide`, `pow`, `sqrt`,
  `min`, `max` (`ArithmeticFeatures`); fluent `.multiply(double)`, `.divide(double)`,
  `.add(double)` on `CollectionProducer`.
- `clamp(x, lo, hi)` = `min(max(x, c(lo)), c(hi))`.
- Sequences: `integers(from, to)` (`CollectionCreationFeatures`), evaluated in the
  containing kernel when used as an operand.
- Assembly: `concat(shape, producers...)`.
- Conditionals at the expression level: `conditional(cond, then, else)` inside a
  `HybridScope` — see `DefaultEnvelopeComputation` for the template.

Note: `CollectionProducer` is a **raw** type in this codebase — write
`CollectionProducer`, not `CollectionProducer<PackedCollection>`.

### The "device state buffer, advanced by a compiled op" idiom

State that unfolds over time (a counter, a filter coefficient, an envelope level)
lives in a `PackedCollection` and is advanced by an operation compiled **once** and
run each tick. `TimeCell.tick()` is the canonical example:
`a(cp(time), add(cp(time), c(1.0)))`. `BiquadFilterData.updateCoefficients` is the
same shape. Case 1 extends it to a multi-field state machine.

### Testing convention

Every migration keeps the host implementation as the parity reference and asserts
the device output matches it. Tolerance should be a combined absolute + relative
band (e.g. `1e-4 + 1e-4 * |expected|`) — wide enough for single-precision device
arithmetic, far tighter than any formula or wiring error. **Always run a negative
control**: perturb one term of the device formula and confirm the test fails.
`BiquadCoefficientParityTest` is the model.

Tick operations are built **once** — callers do `x.tick().get()` and run the
returned `Runnable` repeatedly. A device op added to a tick `OperationList`
therefore compiles once. Verified for `AudioSynthesizer` and the cell types.

---

## 2. Case 1 (recommended first): ADSR envelope on the device

**Value:** highest. The envelope is the root of the filter-modulation chain and
its `tick()` is a *per-sample* host state machine — the largest live host
computation in this area. Self-contained (only `ADSREnvelope` + `ADSREnvelopeData`).

### Current host violation

`ADSREnvelope.tick()` (`engine/audio/.../filter/ADSREnvelope.java:180-267`) adds a
host lambda that, every sample, reads `phase`/`position`/`currentLevel` back to the
host via `toDouble`, branches on the phase, advances the position, computes the
level, and writes the results back with the scalar setters. Its own comment admits
"the envelope computation is done in Java here rather than GPU because the
branching logic is complex."

The state, all in `ADSREnvelopeData` one-element slots: `phase` (IDLE/ATTACK/
DECAY/SUSTAIN/RELEASE constants), `position` (0–1 within the current phase),
`currentLevel` (the output), plus parameters `attackTime`, `decayTime`,
`sustainLevel`, `releaseTime`, `releaseLevel`, `sampleRate`.

### Target design

Express the per-sample state update as **one compiled operation** over device
buffers, so `tick()` adds a device op instead of a host lambda. The update is a
pure function of the current state:

```
dt = 1 / sampleRate
// per-phase position advance and level (piecewise linear):
//   ATTACK : position += dt/attackTime ; level = min(1, position)
//   DECAY  : position += dt/decayTime  ; level = 1 - (1 - sustain) * min(1, position)
//   SUSTAIN: level = sustain
//   RELEASE: position += dt/releaseTime ; level = releaseLevel * (1 - min(1, position))
//   IDLE   : no change
// transition when position >= 1: advance phase, reset position to 0
//   ATTACK->DECAY, DECAY->SUSTAIN, RELEASE->IDLE
// edge cases: attackTime/decayTime/releaseTime == 0 => immediate transition
```

Two viable shapes:

1. **`HybridScope` computation** (mirrors `DefaultEnvelopeComputation`): a single
   `CollectionProducerComputationBase` whose `getScope` emits the branch logic with
   `conditional(...)` expressions, writing the three state slots. This is the
   design the stale `ADSREnvelopeComputation` reference anticipated. Densest, but
   requires working at the `Expression`/`Scope` level.
2. **Producer-composition update**: build `newPhase`, `newPosition`, `newLevel` as
   nested `conditional` producers over the phase and position, then assign the
   three buffers (one `OperationList` of three assignments, or one packed
   `(3)`/`(9)`-slot assignment). Higher-level; stays in producer space.

Prefer shape 2 unless it proves awkward — it keeps the logic in the same producer
vocabulary as the biquad work and is easier to review. Watch:

- Phase is an enum encoded as a `double`; comparisons use producer/greater-than
  conditionals. Keep the exact constants in `ADSREnvelopeData`.
- The transition resets `position` to 0 *and* changes `phase` in the same tick;
  compute both from the pre-transition values.
- `noteOn`/`noteOff` (`data.noteOn()`/`noteOff()`) set phase and `releaseLevel`;
  those remain event-driven host calls at the note boundary (a control boundary,
  not per-sample) and can keep their current scalar writes (`fill` if a write is
  needed). Only the per-sample `tick()` needs migrating.
- `getResultant()` already returns a device producer (`data.getCurrentLevel()`),
  so consumers of the envelope value need no change.

### Guard

Add `ADSREnvelopeParityTest` (or a characterization test) in
`engine/audio/.../filter/test`:

- Construct `new ADSREnvelope(attack, decay, sustain, release, sampleRate)` with a
  small `sampleRate` (e.g. 100) so each phase is a handful of samples.
- `env.tick().get()`; `noteOn()`; run through attack → decay → sustain; `noteOff()`;
  run through release.
- After each tick assert `env.getCurrentLevel()` equals the independently-computed
  expected ADSR curve value (attack ramps 0→1, decay ramps 1→sustain, sustain is
  flat, release ramps from the level at note-off down to 0). Include the
  `time == 0` immediate-transition edge cases.
- This test passes on the **current host** implementation; write and run it first
  to lock the behavior, then migrate and confirm it still passes. Negative-control
  it.

### Done when

`ADSREnvelope.tick()` adds a compiled device operation (no host `toDouble`/scalar
writes per sample), the parity test passes on both the pre- and post-migration
code, and the module's existing envelope tests (`BatchedEnvelopeTest`, and the
`AudioSynthesizer`/`ADSRSynthesisModel` paths) still pass.

---

## 3. Case 2: wire the device biquad coefficients into `AudioSynthesizer`

**Value:** completes the biquad story — removes the live per-tick host callback
that recomputes filter coefficients. Best done after Case 1, because until the
envelope is on-device the modulation input it reads is still host-computed (this
is a partial win on its own).

### Current host violation

`AudioSynthesizer.tick()` (`engine/audio/.../synth/AudioSynthesizer.java:590-597`)
adds, when a filter envelope is active, a host lambda that runs every sample:

```java
double envValue = filterEnvelope.getCurrentLevel();               // device -> host read
double modulatedCutoff = filterBaseCutoff + envValue * filterEnvelopeAmount;
double clampedCutoff = Math.max(20.0, Math.min(20000.0, modulatedCutoff));
filter.configureLowPass(clampedCutoff, 0.707);                    // host cos/sin + 5 device writes
```

### Target design

Replace the lambda with a device operation built from the Case-0 foundation:

```
cutoff = clamp(filterBaseCutoff + filterEnvelope.getResultant(null) * filterEnvelopeAmount, 20, 20000)
tick.add(data.updateCoefficients(data.lowPassCoefficients(cutoff, c(0.707), sampleRate)))
```

where `data = filter.getData()`. `getResultant()` supplies the envelope level as a
device producer. The op compiles once and recomputes coefficients each tick as the
cutoff sweeps — no kernel per value.

The two host scalars that vary must become device buffers so the op can read them:

- Add one-element buffers backing `filterBaseCutoff` and `filterEnvelopeAmount`.
- The setters (`setLowPassFilter`/`setHighPassFilter`/`setBandPassFilter`/
  `setFilterCutoff` for the cutoff, `setFilterEnvelopeAmount` for the amount) write
  the buffers with `fill(value)` (control-boundary write). Keep the host `double`
  fields too for the getters and the `amount != 0` build-time branch.

### Gotchas

- The current lambda reads `filter`/`filterBaseCutoff`/`filterEnvelopeAmount`/
  `filterEnvelope` as **fields, fresh each tick**, so it reflects mid-render
  reconfiguration. The device op captures `filter.getData()` and the buffers at
  tick-build time. Decide whether mid-render filter *replacement* is a supported
  scenario (it likely is not — replacing the filter mid-note also discards its
  delay-line state); document the assumption. Cutoff/amount changes are still
  reflected because they flow through the buffers.
- The current lambda hard-codes `configureLowPass(..., 0.707)` regardless of the
  configured filter type (low/high/band-pass) — a pre-existing quirk. Preserve it
  (use `lowPassCoefficients`, `q = 0.707`) unless you deliberately fix it, to keep
  the change behavior-preserving.

### Guard

`AudioSynthesizer` construction needs a model + voices + oscillators
(`AudioSynthesizer.java:172`), so the test setup is heavier than the biquad unit
test. Write a focused engine/audio test that builds a synth with a low-pass filter,
an ADSR filter envelope and a non-zero amount, runs N ticks, and after each tick
asserts `filter.getData().coefficients()` equals
`BiquadFilterCell.calculateLowPassCoefficients(clampedCutoff, 0.707, sampleRate)`
for the envelope level read that tick. This equivalence holds for both the host
lambda and the device op, so it passes before and after the migration. The
`studio/compose` `AudioSynthesizerTests` (audio-output smoke tests) are a secondary
guard but do not clearly exercise the modulation path.

### Done when

The per-tick host lambda is gone, the equivalence test passes on both the pre- and
post-migration code, and the synth tests in `engine/audio` and `studio/compose`
still pass.

---

## 4. Case 3: the smaller host-DSP sites

Lower individual value; each is its own host state machine or host-control
boundary. Migrate opportunistically or when touching the surrounding code.

- **`LFO`** (`engine/audio/.../synth/LFO.java`) — `setFrequency`/`setDepth`/
  `setSampleRate` scalar setters (reverted to `setMem`, baselined). Check whether
  the LFO's per-sample output is computed on the host; if so, express the LFO phase
  as a device counter (à la `TimeCell`) and the output as `sin`/shape producers,
  driven by frequency/depth buffers written at the control boundary.

- **`MidiCCSource`** (`engine/audio/.../midi/MidiCCSource.java:214-221`) — a host
  IIR smoother: `smoothedValue = smoothedValue * smoothing + ranged * (1 - smoothing)`
  then `output.setMem(0, smoothedValue)`. The MIDI CC input is a genuine host event
  (control-rate, not audio-rate). If migrated, the smoother state and update become
  device ops fed by the CC value written at the event boundary; the value being
  host-originated is legitimate (it is an external control input). Low urgency —
  it is control-rate, not per-sample.

- **`FrequencyToAudioConverter`** (`engine/audio/.../data/FrequencyToAudioConverter.java`)
  — host-side DSP. The `normalize` step (whole-buffer scale by a host scalar) is a
  clean single-kernel candidate (`cp(audio).multiply(scale).into(audio.traverseEach()).evaluate()`).
  `applyIfft` ingests a host complex spectrum, and the overlap-add is a host scatter
  loop reading `toDouble` per element — these want the FFT/overlap-add expressed as
  device operations, a larger rework. Treat the whole class as one deliberate DSP
  migration rather than piecemeal.

---

## 5. Enforcement and baseline notes

- The `SetMemLiteralsDetector` baseline (`engine/utils/.../setmem-violation-baseline.tsv`)
  matches on exact trimmed source text. Reverting a migrated line to its baselined
  form is clean; **removing** a baselined `setMem` (by genuinely migrating the site)
  leaves an orphaned baseline entry, which is harmless (the baseline tolerates *at
  most* its count) but should be cleaned up in a baseline refresh at a stopping
  point. Regenerate with
  `java org.almostrealism.util.SetMemLiteralsDetector <rootDir> --generate <file>`.
- Run the build validator (`checkstyle`, `code_policy`, `test_timeouts`,
  `duplicate_code`) before declaring any case done. The `code_policy` /
  Producer-pattern detector should stay green because the migrated computation is
  all producers.
- Verify with the full CI test-group shape (`test_group=N test_groups=7` in a single
  JVM) for any change that could affect operator-pool pressure; per-class runs
  cannot reproduce cross-test accumulation.
