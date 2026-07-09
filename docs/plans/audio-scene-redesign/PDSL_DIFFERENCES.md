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

> **Ring-sizing invariant.** A block-parallel ring must span at least
> `maxDelay + signalSize` samples. Stages that read *before* writing the current frame
> (`feedbackNetworkBlock`, used by `feedback` and `delay_network`) additionally require
> `delay ≥ signalSize` — a sub-frame delay would need intra-frame recurrence, which the
> block-parallel construct cannot express (`feedbackNetworkBlock` javadoc says so, but
> nothing enforces it).

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
  `REVERB_FRAMES = 2` (`MixdownManagerPdslAdapter`), i.e. **0.6–1.7 frames**. The
  read-first `delay_network` supports only `delay/signalSize ∈ [1, REVERB_FRAMES − 1]`
  — with a 2-frame ring that band is the single value `delay = signalSize`. So **every
  tap is partially wrong at any buffer size**: for 6 channels at 4096 the taps are
  {3101, 3745, 4388, 5032, 5676, 6320} samples — the two sub-frame taps splice to a
  lap-stale echo (`delay + 8192` ≈ +186 ms) for most of each frame, and the four
  above-frame taps splice to lap-stale for the head of each frame.
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
  BPM > 161.5 the 0.25-beat choice is **< 4096 samples** → the read-first `feedback`
  stage's unsupported sub-frame band; the echo reads a full fb-ring lap stale
  (~33 frames ≈ 3 s at 120 bpm sizing) for most of each frame.
- Independently, the fb ring size `ceil((maxDelay + 1)/signalSize)` frames
  (`buildArgsMap`) is **one frame short of the invariant** at the top of the gene range
  (6 beats): the first `~(maxDelay mod signalSize)` samples of each frame read the head
  slot (lap-stale) whenever the gene picks the maximum delay at a tempo where
  `6 × beatSamples` approaches the ring size.

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

PDSL renders **one** feedback stage (`feedback(efx_fb_delay, efx_fb_transmission,
efx_fb_passthrough, …)`) that takes its delay from loop 1's gene, its matrix from
loop 2's chromosome scaled by `feedbackGain/channels = 0.1`
(`MixdownManagerPdslAdapter`), and its output tap from `wetOut`. Consequences:
- **`delayLevels[ch,1]` (the per-channel self-feedback gene) is read nowhere** in the
  adapter — the strongest legacy regeneration control simply does not exist in PDSL.
- The `delay` chromosome, `delayDynamicsSimple` (delay-rate modulation), and
  `wetInSimple` (send automation) are likewise unread.
- Loop gain 0.1 × gene vs legacy per-sample self-feedback up to 0.5 per delay period +
  unscaled cross-matrix: PDSL regeneration is both **far tamer** (echoes die after
  ~1–2 repeats) and **rigid** (static integer delays — no modulation, so what does
  regenerate is a stationary comb; legacy tails shimmer and detune).
- The wet channel's dry component goes through the (broken, §2.1) feedforward delay in
  PDSL; legacy keeps it clean.

### 3.3 Block-rate re-entry (one regeneration step per buffer)
`feedback(...)` re-enters once per forward pass, so echo regeneration is quantized to
the 92.9 ms buffer grid regardless of the gene delay, versus the legacy per-sample
recurrence. Fundamental to buffer > 1 — a sub-frame regeneration interval cannot exist
block-parallel (§2 invariant). Repeats land on `ceil(delay/buffer)` multiples; fine for
delays ≥ 1 buffer (production gene range at ≤ 161 bpm), foundational limit below that.

### 3.4 Automation steps once per buffer
All time-varying values (volume, cutoffs, efx automation, reverb send) are slots
refreshed once per buffer (`MixdownManagerPdslAdapter.automationRefresh`;
`AudioSceneRealtimeRunner.createPdsl` tick) — a 10.77 Hz staircase at 4096 vs legacy
per-frame continuity. Slow envelopes are fine; the staircase matters exactly where the
owner hears trouble: a step applied to a **hot wet bus** is a level discontinuity that
then **recirculates through the feedback loops**. Distinct from §2's splices (which
occur with automation frozen) but the same 10.77 Hz signature, and additive with them.

### 3.5 Filters: FIR approximations, asymmetrically sourced
- Main HP / master LP: 41-tap truncated-**biquad**-IR tables (1024 log-spaced bins,
  per-buffer device-side row select — `biquadResponseTable`/`tableRow`), deliberately
  matched to `AudioPassFilter`'s 12 dB/oct shape. Sub-perceptual per the sweep A/B.
- Efx-loop and wet-bus filters: **windowed-sinc** FIR (`lowPassCoefficients` /
  `highPassCoefficients` via `efxFilterCoefficients`/`wetFilterCoefficients`) — much
  steeper than the legacy biquad *and sitting in the regeneration path*, where the
  spectral difference compounds per pass. The biquad-table javadoc itself records that
  windowed-sinc "audibly diverged" on the main-bus sweep; the efx bank was never given
  the same treatment. **Lever:** source `efx_filter_coeffs`/`wet_filter_coeffs` from
  the same biquad tables.
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
- **Continuous delay-time modulation** (`AdjustableDelayCell.scale`): true per-sample
  cursor-rate modulation is an intra-frame recurrence. A per-buffer delay drift is the
  available approximation (§6-C2).

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
   `buildArgsMap` (3 frames for 6500 @ 4096); same +1-frame correction for the fb ring
   formula. Unify the write/read order of the vectorized and scalar `delay` forms.
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
1. Put the per-channel self-feedback gene (`delayLevels[ch,1]`) on the
   `efx_fb_transmission` **diagonal** (replacing the flat 0.1 scaling there), keeping
   the off-diagonal contraction scaling for stability; drive the feedforward delay from
   the `delayTimes` gene per channel instead of the static 6500 (A2 makes the rings big
   enough). This restores per-channel echo identity — the biggest single character gap
   (§3.2).
2. Approximate delay modulation: drift `efx_fb_delay` by the `delayDynamicsSimple`
   gene once per buffer in `automationRefresh` (integer sample steps; small amplitude).
   Cheap; breaks the static-comb rigidity. Evaluate by ear whether per-buffer drift
   reads as motion or as artifact before keeping.
3. Per-sample parameter ramps: interpolate each automation slot from its previous to
   current value across the buffer inside the kernel (slots become `[2]` or the ramp is
   computed from a `prev` bank) — removes the 10.77 Hz staircase where it multiplies
   hot buses (volume, efx automation, reverb send first; filter coefficient banks can
   stay stepped).
4. Source the efx/wet filter banks from the biquad response tables (§3.5) so the
   in-loop filters match legacy slope.

**D. Accept (documented):** block-rate re-entry granularity (§3.3), dual-mono until the
pan feature, determinism-by-design, FP drift, per-buffer coefficient stepping after C3.

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
