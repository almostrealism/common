# PDSL vs CellList Signal Path — Divergence Assessment (2026-07-09)

The authoritative inventory of the differences between the legacy CellList mixdown path
(`AudioSceneRealtimeRunner.createCellList`, buffer size 1 — one frame per tick) and the
production PDSL path (`AudioSceneRealtimeRunner.createPdsl` driving `mixdown_master_wet`
in `engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl`, buffer size 4096).

**Why this rewrite exists.** The owner reports the two paths audibly diverge, that the
divergence *grows with clip duration*, is *worst when most of the signal comes from EFX*,
and reads as a **grinding** artifact. The previous version of this document recorded a
parity verdict ("validated by ear and windowed RMS") from a 40 s A/B at buffer size 8192.
That verdict is superseded: this assessment re-derives every EFX mechanism from current
source at the production buffer size of 4096, and finds **three ring-arithmetic defects**
(§2) that generate exactly the reported symptom class, on top of the known structural
differences (§3). Windowed RMS cannot see these defects (rotations and splices preserve
energy), and the 8192 validation exercised different — though also partly broken —
arithmetic.

Every claim below carries a `file:symbol` receipt. The arithmetic claims follow
mechanically from the cited expressions; runtime confirmation is step 0 of the option
plan (§6).

---

## 1. What is identical (unchanged from the parity work)

- **Pattern preparation** — both paths share the identical Java pattern-prepare phase.
- **Per-channel input clamp** — `clip(-0.99, 0.99)` before filtering, matching
  `AudioPassFilter.MAX_INPUT` (`mixdown_master_wet` MAIN and master tail).
- **Master tail** — clamp → master low-pass → `masterBusGain` (0.5) → **hard clip**
  `[-1, 1]`. The earlier `tanh_act()` soft saturator was replaced by the same hard clip
  Java uses (`mixdown_manager.pdsl` master-tail comment). Note: the javadoc block in
  `MixdownManagerPdslAdapter.buildArgsMap` still describes the tanh stage — stale, fixed
  alongside this assessment.
- **Gain structures** — efx-bus `volume ∘ wetFilter`, reverb send `gain = 0.1`, wet
  output mean `1/N`, cutoff bounds `[AudioPassFilter.MIN_FREQUENCY, 20000]` Hz.
- **Automation values** — computed by the same `AutomationManager` producers; only
  delivery granularity differs (§3.4).

---

## 2. Open defects — ring arithmetic (the likely grinding sources)

The block-parallel delay machinery reads a ring at
`position = (head + i − delay) mod bufSize` per output sample `i`
(`MultiChannelDspFeatures.routedRingRead` / `ringValueAt`;
same expression in the scalar `AudioDspPrimitives.dispatchDelay`). That expression is a
true delay **only when the ring is deep enough that the wrapped read never lands in a
slot holding the wrong lap**. The invariant it needs:

> **Ring-sizing invariant.** For a read at `(head + i − D) mod B` the sample's age is
> `i + ((D − i) mod B)`, which equals the requested `D` exactly only inside a band that
> depends on write order. Stages that read *before* writing the current frame
> (`feedbackNetworkBlock`, used by `feedback` and `delay_network`) support
> `signalSize ≤ D ≤ bufSize` — sub-frame delays would need intra-frame recurrence,
> which block-parallel evaluation cannot express. Stages that write *first*
> (`multiChannelDelayBlock`, the `delay` primitive) support
> `0 ≤ D ≤ bufSize − signalSize` — a one-frame ring holds only `D = 0`. Nothing
> enforced either band. (Now enforced by a device-side clamp in the kernels.)

Every violation produces, per buffer: a **splice discontinuity** (the read crosses from
a correct lap to a wrong lap mid-frame) and a region whose **effective delay is wrong**
(too short, non-causal, or a full ring-lap stale). At 4096/44.1 kHz that is a periodic
artifact train at 10.77 Hz — mechanically the texture one would describe as grinding —
and each defect sits **inside or feeding a feedback loop**, so the artifacts recirculate
and accumulate with clip duration and EFX share. All three current rings violate the
invariant:

### 2.1 Feedforward wet-arm delay: one-frame ring, 6500-sample delay — broken at 4096

- The wet arm applies `delay(delay_samples, buffers[channel], heads[channel])` to the
  whole wet voicing (dry component + filtered wet, post `wet_filter`/`volume`, before
  the feedback grid) — `mixdown_master_wet`, `mixdown_manager.pdsl`.
- `delay_samples` = `AudioSceneRealtimeRunner.pdslDelaySamples` = **6500** (static,
  "wire-first default"). The ring is allocated **one frame per channel**
  (`MixdownManagerPdslAdapter.buildArgsMap`: `new PackedCollection(channels * signalSize)`).
  The `.pdsl` parameter comment ("must be < signal_size") is violated at 4096 and
  unenforced everywhere.
- Under the production vectorized form (`AR_PDSL_VECTOR_FOREACH` on →
  `MultiChannelDspFeatures.multiChannelDelayBlock`, which writes the frame into the ring
  *before* the read), a one-frame ring makes the read
  `(i − 6500) mod 4096 = (i + 1692) mod 4096` **over the current frame itself**: samples
  `i < 2404` read `i + 1692` — **1692 samples into the future** — and samples
  `i ≥ 2404` get a 2404-sample delay instead of 6500. Every buffer, every wet channel:
  one splice, a non-causal region, and the wrong echo time.
- At 8192 (where parity was validated) the same allocation gives 79 % future-reads and
  21 % correct-6500 — also broken, but with different (less flutter-like) character.
  **Neither buffer size ever implemented the intended 147 ms pre-delay.**
- Aggravating: the scalar (non-vectorized) `dispatchDelay` emits the read *before* the
  ring write, so the two forms of the same primitive have **different semantics** for
  any sub-ring delay. Whichever order is chosen must be one order.

### 2.2 Reverb network: 2-frame ring whose taps can never all be valid

- `reverbTapDelays` spreads taps over `0.3 … 0.85 × (REVERB_FRAMES × signalSize)` with
  `REVERB_FRAMES = 2` (`MixdownManagerPdslAdapter`), i.e. **0.6–1.7 frames**, but the
  read-first `delay_network` band is `[signalSize, bufSize]` — so any tap below one
  frame is defective. For 6 channels the two lowest tap fractions are always below
  one frame regardless of buffer size (at 4096: taps {3101, 3745} of
  {3101, 3745, 4388, 5032, 5676, 6320}); each splices at `i = delay` every frame and
  reads a full ring-lap stale (`delay + 8192` ≈ +186 ms) for the rest. (An earlier
  revision of this section claimed the above-frame taps were also invalid — wrong: the
  head slot legitimately holds the oldest lap under read-first ordering, so
  `delay ≤ bufSize` is fine.)
- These spliced reads are **inside the Householder feedback loop**
  (`reverb_feedback`, radius 1/N), so each pass re-injects the previous pass's
  discontinuities: the artifact energy grows with accumulated tail energy — i.e. with
  clip duration and with how much signal the automation sends to the reverb. This is
  the strongest single candidate for "grinding that expands as EFX dominates."
- Even where the arithmetic is right, the **room is wrong at 4096** (§3.1): the ring is
  sized in *frames*, so the owner's production buffer halved the reverb's time span
  relative to the validated 8192 renders.

### 2.3 Efx feedback grid: gene delays can enter the unsupported band

- `efx_fb_delay` = `floor(delayTimes gene × beatSamples)`; the gene choices are
  `{0.25, 0.375, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6}` beats (`EfxManager.init`). At
  BPM > 161.5 the 0.25-beat choice is **< 4096 samples** → below the read-first
  `feedback` stage's band; the echo reads a full fb-ring lap stale (~33 frames ≈ 3 s at
  120 bpm sizing) for most of each frame. (The fb ring's *size* is fine: an earlier
  revision claimed the `ceil((maxDelay + 1)/signalSize)` formula was one frame short —
  wrong, since the read-first band extends to the full ring span.)

### 2.4 Deferred tap reads under accum arms: every hosted delay ran one frame short
**Found and fixed 2026-07-11, during C5.** The stateful ring stages emitted their
delayed tap as a lazy producer (`next.push(output)`); a consumer that defers
evaluation past the stage's own ops — which is exactly what `accum_blocks` does (each
branch tail is a `CaptureReceptor`, summed only after every branch's ops have run) —
evaluated the tap AFTER the ring write and head advance. Effective delay for any
stage hosted under an accum arm: `D − signalSize`; and at the read-first band floor
(`D` pinned to one frame) the read returned the **current frame outright** — an
instantaneous leak through the feedback matrix. Standalone consumers evaluated
eagerly, so the same stage had two different semantics depending on where it was
composed, and every exact-timing receipt (which drives stages standalone) was blind
to it. Concretely, in `mixdown_master_wet` before the fix: the merged feedback stage
and the reverb network ran one frame short, and at 8192 the reverb tap floor
(0.15 s = 6615 < 8192) pinned taps to one frame → a **per-frame current-frame leak
recirculated by the Householder matrix** — another buffer-size-dependent artifact
source. Fixed in `MultiChannelDspFeatures` (`feedbackNetworkBlock`,
`multiChannelDelayBlock`): the tap is materialized into scratch at its position in
the ops order, before the ring/head mutations, making the semantics
consumer-independent. Receipt: `mainArmCarriesApplyEcho` (an accum-hosted echo now
fires sample-exactly one frame later, exactly once).

---

## 3. Structural differences (PDSL ≠ legacy by design) — ranked by audible weight

### 3.1 The reverb is a different (much smaller, much sparser) room
Legacy: `reverb.sum().map(fc(i -> new DelayNetwork(sampleRate, false)))`
(`MixdownManager.createEfx`) = **128 delay lines**, per-line length drawn uniformly from
**0.15–1.5 s** (`DelayNetwork` constructor: `0.1×max + 0.9×random×max`, max 1.5 s),
Householder feedback at radius **1/128**, per-sample recurrence. Since the recirculation
is nearly nil at that radius, the legacy reverb is effectively a **dense 128-tap random
diffusion** over 1.5 seconds — a plausible room splash, re-randomized every run.
PDSL: **6 taps** (one per channel) at **70–143 ms** (regular spacing, §2.2), radius 1/6
recirculation, block-parallel. Six regular flutter-range taps recirculating is a
metallic, comb-like texture even with the §2.2 arithmetic fixed — and because the ring
is sized in frames, the room *shrinks* when the buffer does. **Lever:** decouple tap
count and ring length from `channels`/frames — e.g. 32+ taps spread over a
seconds-denominated ring (`repeat(taps)` before `delay_network` already permits taps ≠
channels), radius `1/taps`. That is a knob, not a rewrite, and block-parallel handles
≥-frame taps sample-exactly.

### 3.2 Two legacy feedback loops were merged into one, and half the genes went missing
Legacy has two distinct regeneration structures:
1. **Per-channel echo** (`EfxManager.apply`): wet voicing → gene-chosen HP/LP FIR →
   wet level (`delayLevels[ch,0]`, ≤ 0.5) → automation → a *single*
   `AdjustableDelayCell` at the `delayTimes` gene delay → **per-sample self-feedback at
   the `delayLevels[ch,1]` gene** (≤ `maxFeedback` 0.5) → summed with the *undelayed*
   dry. The dry component of a wet channel is **never delayed** in legacy.
2. **Mixdown-bus network** (`MixdownManager.createEfx`): efx bus → routed by the
   clock-automated `wetInSimple` gene into `delayLayers` `AdjustableDelayCell`s whose
   lengths come from the **`delay` chromosome** and whose playback rate is *continuously
   modulated* by the polycyclic `delayDynamicsSimple` gene (`df` → the cell's `scale`
   cursor rate — a chorus-like detune on every regeneration) → per-sample
   `.mself(transmission, fc(wetOut))` cross-feedback with the **unscaled** genome
   transmission matrix.

**RESTORED 2026-07-11 (C5) — the loops are split again.** PDSL formerly rendered
**one** feedback stage taking its delay from loop 1's gene and its matrix from loop 2's
chromosome contraction-scaled; C1 put the self-feedback gene on its diagonal, and C5
completed the split into the two legacy structures (§6-C5 for the full mechanism):
- Loop 1 is a per-channel diagonal `delay_network` at the `delayTimes` gene with the
  `delayLevels[ch,1]` self-feedback, emitting the **pure delayed tap** (the exact
  `AdjustableDelayCell + mself` form), placed **inside the apply chain** — before the
  wet-filter cascade and volume, where legacy runs it.
- Loop 2 is the bus-line network: `wetInSimple`-automated send into **line 0 only**
  (the legacy `delayGene` routing — later lines hear only recirculation), `delay`
  chromosome line lengths modulated by the per-line cursor rate (the corrected C2
  ratio, §6-C2), **unscaled** genome transmission recirculation ([into, from] = the
  chromosome transposed), `wetOut` per-line output taps.
- **Discovery during C5 (the assessment had missed this):** legacy applies the
  apply echo at the *pattern-channel* level (`AudioScene.getPatternChannel` →
  `efx.apply` for **both** voicings), so the **MAIN bus of a wet channel carries the
  echo too** — the only un-bus-delayed echo in the legacy mix, i.e. the most direct
  echo character in the legacy sound. The PDSL main arm previously had no echo at all;
  it now runs the same stage on its own ring state.
- Remaining §3.2 residue (deliberate): the legacy bus regenerates through
  `AdjustableDelayCell`s whose *rate* modulation is a pitch bend — see §5a (owner
  reclassified; next arc).

### 3.3 Regeneration floor: one buffer — but sample-exact above it
Correction to earlier revisions of this inventory (including the first draft of this
one, which repeated the old "repeats quantized to the buffer grid" claim):
block-parallel feedback is **not** quantized to the buffer grid. For a delay
`D ≥ signalSize` with an adequately sized ring, `feedbackNetworkBlock` evaluates the
ring recurrence `y[t] = x[t] + (M·y)[t−D]` exactly — every read lands in
already-written frames, so repeats fall at exact multiples of the gene delay,
matching the legacy per-sample recurrence for static delays. What the buffer size
actually sets is the **floor**: no regeneration interval below one buffer can exist
block-parallel (§2 invariant), and delay/matrix values are re-read once per pass
(relevant only when they modulate, §6-C2). At 4096 the floor is 92.9 ms — clear of
the production gene range except above ~161 bpm; the floor scales linearly with
buffer size.

### 3.4 Automation steps once per buffer
All time-varying values (volume, cutoffs, efx automation, reverb send) are slots
refreshed once per buffer (`MixdownManagerPdslAdapter.automationRefresh`;
`AudioSceneRealtimeRunner.createPdsl` tick) — a 10.77 Hz staircase at 4096 vs legacy
per-frame continuity. Slow envelopes are fine; the staircase matters exactly where the
owner hears trouble: a step applied to a **hot wet bus** is a level discontinuity that
then **recirculates through the feedback loops**. Distinct from §2's splices (which
occur with automation frozen) but the same 10.77 Hz signature, and additive with them.

### 3.5 Filters: FIR approximations, asymmetrically sourced — REVISED 2026-07-11
- Main HP / master LP: 41-tap truncated-**biquad**-IR tables (1024 log-spaced bins,
  per-buffer device-side row select — `biquadResponseTable`/`tableRow`), deliberately
  matched to `AudioPassFilter`'s 12 dB/oct shape. Sub-perceptual per the sweep A/B.
- **Efx-loop filter: already legacy-faithful** — an earlier revision of this section
  claimed it diverged, but legacy `EfxManager.applyFilter` itself uses windowed-sinc
  FIR coefficients (`lowPassCoefficients`/`highPassCoefficients` via
  `MultiOrderFilter`), which is exactly what `efx_filter_coeffs` renders. No change
  needed or made.
- **Wet-bus filter: was broken outright, fixed 2026-07-11 (C4).** The legacy wet
  filter (`FixedFilterChromosome.FixedFilterGene`) is a *cascade* of two
  `AudioPassFilter` biquads — high-pass (gene slot 0) then low-pass (gene slot 1).
  The PDSL bank had two defects beyond slope shape: (a) it read
  `wetFilter.valueAt(ch).valueAt(1).getResultant(c(1.0))` as its "cutoff" — but
  `FixedFilterGene.valueAt` ignores its position and returns the whole composed
  stateful filter `Factor`, so the value was the filter chain applied to a constant:
  meaningless, clamping near the 20 Hz floor and leaving the wet bus drastically
  over-filtered since the cutover; and (b) it rendered a low-pass only, dropping the
  cascade's high-pass half entirely. C4 exposes the real cutoff genes
  (`FixedFilterChromosome.highPassFrequency`/`lowPassFrequency`), renders both halves
  as truncated-biquad table rows (`wet_hp_coeffs` + `wet_filter_coeffs`, a new HP
  `fir` stage ahead of the LP in the wet arm), and bounds the cutoffs exactly as the
  `AudioPassFilter` constructor does. Expect the wet bus to open up audibly.
- Legacy `AudioPassFilter` recomputes coefficients **every frame** during sweeps;
  PDSL selects a table row per buffer (§3.4 granularity).

### 3.6 Remaining known differences (unchanged)
- **Dual-mono master** (one rendered master streamed to both writers; pan/true stereo
  outstanding).
- **Determinism**: PDSL reverb is deterministic; legacy re-randomizes line lengths per
  run (`Math.random()` in `DelayNetwork`) — any single legacy render is one draw.
- **FP reduction drift** across recompiles, ~1–3 % RMS late in feedback-heavy renders —
  both paths, more DSP inside kernels on PDSL.
- **Live-swap staleness**: `wet_filter_coeffs`/`efx_filter_coeffs` are sampled at args
  build; slots refresh live. Unchanged from the previous inventory.

---

## 4. Why divergence grows with duration and EFX share

1. **Feedback accumulation**: §2's per-buffer splices and §3.4's staircase steps are
   injected into loops (efx grid, reverb Householder). Early in a clip the buses carry
   mostly direct signal; as the arrangement's automation opens sends and tails
   accumulate, an increasing fraction of the signal is *recirculated* material carrying
   one more copy of the artifacts per pass.
2. **Wrong-lap reads scale with content density**: a lap-stale read (§2.2, §2.3)
   substitutes audio from ~186 ms (reverb) to ~3 s (fb ring) earlier — early in a clip
   those regions are silence (inaudible); later they are full mix, so the spliced-in
   material becomes loud, wrong, and periodic.
3. **Level-dependent**: everything above scales with wet-arm level, which is what "more
   of the signal coming from EFX" means.

This also explains the earlier validation verdict: a 40 s render at 8192 with one
genome exercised low accumulated tail energy, different splice arithmetic, and a
2×-larger reverb ring — and RMS windows are blind to rotations and splices.

---

## 5. What buffer size 1 could do that 4096 cannot (accepted limits)

- **Sub-buffer regeneration intervals** (per-sample or short-delay feedback): impossible
  block-parallel (§2 invariant). Short gene delays must be quantized up to one frame
  (§6-A3) — a one-frame echo instead of 3-second-stale garbage.
- **Per-sample automation and coefficient recompute**: per-buffer stepping is the
  design; in-kernel per-sample parameter interpolation (§6-C3) can smooth the staircase
  but not restore per-sample modulation semantics.

### 5a. NOT an accepted limit: continuous delay-time modulation (owner directive, 2026-07-11)

An earlier revision listed `AdjustableDelayCell.scale`'s continuous cursor-rate
modulation — the pitch bend / tape-speed detune on every regeneration, the shimmer of
the legacy tails — among the accepted limits. **The owner has explicitly reclassified
it**: the smooth pitch modulation is a defining feature of the application's efx chain
("I would probably rather abandon real-time generation before abandoning it"). The C2
per-buffer ratio (`gene / s(t)`, per the corrected §6-C2) restores the delay-time
*trajectory* in whole-sample steps but not the pitch-bend *character* — the resample
ratio `s(read)/s(write)` on regenerating content — and is therefore an interim state,
not an acceptance.

It is also not actually blocked by the §2 invariant. The blocked construct is the
intra-frame *recurrence*; a rate-modulated **read** is not one. With the rate held
per-buffer (as C2 already computes it), the read position at frame sample `i` is
`r0 + i·s` — a fractional-stride **gather + linear interpolation** over the ring, the
same parallel construct the batched pattern resampler already ships
(`BatchedPatternRenderer.buildResampleProducer`: `integers(0,N)·ratio → floor → gather
→ lerp`). A resampling variant of the ring read in
`MultiChannelDspFeatures.ringValueAt` (fractional delay, per-buffer-linear rate) would
render a genuine per-sample pitch bend block-parallel, recirculation included (drifting
reads still land in prior frames for `D ≥ signalSize`). This is the next arc after C5 —
likely a separate branch; see [NEXT_STEP.md](NEXT_STEP.md).

---

## 6. Options — ordered by (payoff ÷ effort)

**Step 0 (receipts before fixes).** A ring-semantics unit test that feeds a known ramp
through `multiChannelDelayBlock` / `feedbackNetworkBlock` at small shapes and asserts
effective delay per output index — confirming §2.1–2.3 at runtime and becoming the
regression guard for A1–A3. Plus a by-ear bisection on the real scene using the
existing diagnostic arm gains (`mainArmGain` / `efxArmGain` / `reverbArmGain`,
`hpCutoffOverrideHz`) to rank the arms' contribution to the grinding before and after
each fix.

**A. Fix the ring defects (small, surgical, no character judgment involved):**
1. Enforce the ring-sizing invariant at block construction: throw (or log-and-clamp)
   when `maxDelay + signalSize > bufSize`, and for read-first stages when
   `delay < signalSize`. The invariant belongs in `MultiChannelDspFeatures`, stated
   once, checked for `delay`, `feedback`, and `delay_network`.
2. Size the feedforward delay rings from the actual delay:
   `frames = ceil((delaySamples + signalSize)/signalSize)` in
   `buildArgsMap` (3 frames for 6500 @ 4096). The fb ring formula needs no change (the
   read-first band extends to the full ring span). Unify the write/read order of the
   vectorized and scalar `delay` forms.
3. Clamp `efx_fb_delay` to `max(geneDelay, signalSize)` (quantize-up, per §5) and
   likewise floor the reverb taps at `signalSize`.
4. Re-validate: bit-parity is not expected (the current output includes the defects),
   so the gate is the Step-0 unit test + A/B listening + windowed RMS at **4096**.

**B. Re-align the reverb room (knobs, medium payoff):**
1. Size the reverb ring in **seconds** (e.g. 1.5 s → `ceil(1.5·sr/signalSize)` frames ≈
   17 frames at 4096), so the room is buffer-size-independent and matches the legacy
   span.
2. Decouple tap count from channel count (`repeat(taps)` with e.g. 32 taps), spread
   delays across 0.15–1.5 s (irregular spacing — primes or a fixed shuffled table to
   keep determinism), radius `1/taps`. This reproduces the legacy "dense random splash"
   character deterministically. Memory: 32 taps × 17 frames × 4096 × 8 B ≈ 18 MB —
   fine.

**C. Restore the missing legacy genes/behaviors (character, medium effort):**
1. **DONE 2026-07-09.** The per-channel self-feedback gene (`delayLevels[ch,1]`) is on
   the `efx_fb_transmission` **diagonal** (`feedbackGridMatrix`), off-diagonal
   contraction scaling kept; and the wet arm's feedforward delay is gene-driven per
   position from the **`delay` chromosome** — the legacy mixdown-bus delay layer's
   4–20 s genes (`busDelaySamples`, positions cycling over the `delayLayers` genes,
   ring sized from the evaluated genome). An earlier revision of this item named the
   `delayTimes` gene for the feedforward stage — wrong: `delayTimes` is the apply-echo
   period and already drives `efx_fb_delay`; the feedforward stage renders the *bus*
   delay layer. This also restores the efx bus's slow-building arrival (legacy: the
   whole wet bus passes through the 4–20 s delay layer; the wire-first 6500 ≈ 147 ms
   constant made it near-immediate — a major duration-dependent character gap). Wiring
   pinned by `MixdownManagerPdslVerificationTest.feedbackGridAndBusDelayFollowGenes`.
   §3.2 residues deliberately out of scope here — the off-diagonal regeneration
   period, `wetInSimple`, and the echo's placement — were subsequently restored by the
   C5 loop split below.
2. **DONE 2026-07-10; MECHANISM CORRECTED 2026-07-11 after the C5 buzz regression.**
   The first rendition modeled the legacy cursor-rate modulation as an *integral* —
   a `bus_delay_drift` slot accumulating `(1 − s(clock)) × signalSize` per buffer,
   never rewound. **That model was wrong, and it detonated under C5**: reading
   `AdjustableDelayCell.tick` shows `cursors.increment(scale)` advances BOTH cursors
   together, so the write-read separation is a *fixed timeline distance* and the
   effective delay is the bounded, memoryless **ratio `gene / s(t)`** — it follows
   the clock (including the arrangement-break resets, snapping the wash back wide at
   every section) and never migrates. The integral instead dragged every line
   monotonically to the one-frame floor within ~a minute of audio (accelerando keeps
   `s > 1`; ≈ −4 s of delay per 40 s), where the C5-faithful unscaled recirculation
   (spectral radius ≈ 1.35) blew up a 23 ms three-line loop into a permanent
   full-scale buzz — the owner-reported "nothing but a buzz after ~40 seconds," whose
   spectrogram reads music → tightening chirp → solid full-band saturation. Current
   rendition: `bus_delay_samples` is a per-buffer-refreshed `[layers]` slot,
   `floor(max(gene×sr / s_j(clock), signalSize))`, one polycyclic rate gene per line
   (`df.apply(i)`). What the correct legacy mechanism actually contributes: the
   in-section tightening is a *bounded ratio* (never below `gene/s_max`), and the
   pitch character is the resample ratio `s(read)/s(write)` on regenerating content —
   shimmer while `s` changes — which remains the §5a resampling-read arc. Pinned by
   `MixdownManagerPdslVerificationTest.busDelayFollowsCursorRate` (rate-1 identity at
   frame 0, tightening away from it, refresh idempotence at a fixed clock — the
   anti-regression for the integral — and exact snap-back when the clock returns
   to 0).
3. **DONE 2026-07-11 (pending the by-ear verdict).** Per-sample parameter ramps: a new
   `ramp_scale(previous, current)` primitive interpolates each hot-bus gain linearly
   across the frame, ending exactly at `current`, so per-buffer slot refreshes trace a
   continuous piecewise-linear envelope instead of the buffer-rate staircase (43 Hz at
   1024 — the leading mechanical grit suspect: a level step on a loud bus is a
   discontinuity per buffer boundary, recirculated by the feedback loops).
   `automationRefresh` copies each gain's current value into a `_prev` slot before
   re-evaluating it; the ramped stages are volume (both arms), efx automation, and the
   reverb send. Filter coefficient banks stay stepped per the original plan, and the
   genome-static gains (`efx_wet_level`, `wet_level`) stay `scale()`. Pinned by
   `RampScaleBehaviorTest` (per-sample exactness and cross-frame continuity) and
   `automationRampSlotsTrackPreviousValues`.
4. **DONE 2026-07-11.** The wet-filter bank is sourced from the biquad response
   tables — and the investigation found the bank's cutoffs had been meaningless since
   the cutover, plus a missing high-pass cascade half (see the revised §3.5). The efx
   bank turned out to be already legacy-faithful (legacy `applyFilter` is itself
   windowed-sinc) and is unchanged.
5. **DONE 2026-07-11 (pending the by-ear verdict) — the loop split.** The merged
   feedback stage is gone; the two legacy regeneration structures are rendered
   distinctly (revised §3.2):
   - **Apply echo**: a diagonal-matrix `delay_network` (pure-tap semantics
     `w[t]=u[t]+fb·w[t−D]`, `out=w[t−D]` — exactly the legacy cell) at the
     `delayTimes` gene inside the apply chain, on BOTH voicing arms — including the
     newly-discovered legacy MAIN-bus echo — each arm with its own ring state
     (`fb_*`/`main_fb_*`). Cross-channel coupling removed from this stage entirely.
   - **Bus-line network**: `route(bus_send)` sums every channel (scaled by the
     clock-automated, C3-ramped `wet_in` gene — previously unread; its range starts
     the send closed and opens it over the arrangement) into line 0 of a
     `delay_layers`-line `feedback` network (production: 3 lines, decoupled from the
     6 channels); recirculation is the **unscaled** genome transmission (transposed —
     the feedback matrix is [into, from]; row sums can exceed 1, and legacy runaway
     is real, bounded by the master clip as in Java); output taps are
     `wetOut[j] × line j's pure delayed tap` via the passthrough matrix; the C2
     modulation becomes per-line (one rate gene per line — the legacy `df.apply(i)`)
     and, after the buzz regression, the corrected ratio semantics (revised C2 entry
     above).
   - The reverb send now taps the **apply output** (dry + echo), matching
     `wetSources.branch(..., reverbFactor)` — it previously tapped the raw WET slice.
   - The wet-filter cascade gains the legacy `AudioPassFilter` input clamp
     (`clip(-0.99, 0.99)`), and non-wet channels (`EfxManager.getWetChannels`) now
     bypass the apply chain via a zero wet level, as legacy's gate does.
   - The C5 receipt test exposed a fourth ring defect — deferred tap reads under
     accum arms (one frame short; current-frame leak at the band floor) — fixed in
     the ring kernels themselves; see §2.4.
   - Wiring pinned by the rewritten `feedbackGridAndBusDelayFollowGenes` (diagonal-only
     echo grid, transposed unscaled bus matrix, wetOut taps, line-0-only send,
     rectangular layers≠channels) and `busDelayDriftAccumulatesWithClock` (per-line);
     placement and pure-tap semantics pinned end-to-end by `mainArmCarriesApplyEcho`
     (impulse echoes exactly once, one frame later, on the MAIN arm). The
     `feedbackGain` contraction knob is gone; `regenerationGain` (default 1 =
     faithful) replaces it as the bisection diagnostic across both loops.

**D. Accept (documented):** the one-buffer regeneration floor (§3.3), dual-mono until
the pan feature, determinism-by-design, FP drift, per-buffer coefficient stepping after
C3.

A alone plausibly removes the "grinding" (it removes every mechanical discontinuity
generator); B+C close the character gap (room size/density, echo identity, tail
liveliness). C1/C2 are where "closer than we currently are" lives after the bugs are
gone.

---

## 7. Verification instruments

- Step-0 ring-semantics unit test (new; the only trustworthy receipt for §2).
- Arm-gain bisection on the real scene (`mainArmGain`/`efxArmGain`/`reverbArmGain`).
- `AudioScenePdslCutoverTest` A/B — **at 4096**, with a long render (≥ 3 min) so
  accumulation effects show; listen to wet channels (`AR_GENERATE_CHANNEL=2`) and a
  reverb channel in isolation, per the previous inventory's listening guide.
- Windowed RMS remains useful for level regressions but is **blind to §2** — do not
  use it as the parity verdict again; add a short-window spectral-flux or
  click-density comparison if an objective gate is wanted.
