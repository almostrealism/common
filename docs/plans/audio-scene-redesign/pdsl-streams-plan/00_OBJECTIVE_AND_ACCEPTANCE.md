# 00 — Objective & Un-Fakeable Acceptance

> This document fixes *what we are building* and *how we will know it is done* in terms that
> cannot be satisfied by a lucky tick, a disabled feature, or a measurement of the wrong thing.
> The acceptance criteria are written as **mechanical gates** (a test asserts them, a counter
> enforces them) because the historical failure mode here was prose claims of success that CI
> and the owner's ears immediately falsified.

## 1. The objective (owner's framing, preserved)

Build a real-time `AudioScene` rendering pipeline as **three independent layers**, decoupled by
ring buffers, each running on its own clock:

- **a1 — pattern-element creation.** Decides *which* notes/elements exist on the timeline.
  Cheap; runs minutes ahead. Must support **live genome swapping** without tearing down the
  pipeline.
- **a2 — pattern-element rendering.** Synthesizes *each element's* audio samples (resample,
  envelopes, filter). Costly; **batched**; runs seconds ahead, filling a ring of rendered
  audio.
- **a3 — mixdown.** Mixes the **already-rendered** pattern audio into output buffers and plays
  them, including automation and effects. Just-in-time; the DSP is expressed declaratively in
  PDSL.

### The defining invariant (the heart of the design)

> a3 is the **only** layer bound to the real-time clock; a1 and a2 run ahead and **never block
> a3**. Each layer produces its data just-in-time for the next. Concretely, **a2 must render
> each element's audio once, ahead of time**, so it is already available when a3 needs it — a2
> must **not** be re-run on every a3 tick.

That coupling — a2 re-rendering inside (or in lockstep with) the mixdown cadence — is the
original spec violation that started this work and, per the ground-truth finding in
[02_GROUND_TRUTH_ARCHITECTURE.md](02_GROUND_TRUTH_ARCHITECTURE.md), is **still present** in a
disguised form (a2 is on its own thread but still does per-buffer-window work).

### The PDSL framing (why this is a platform task, not an audio task)

"A computation that runs ahead of its consumer's real-time forward pass" is a **general PDSL
capability**. The audio pipeline is its first consumer. The plan's center of gravity is the
PDSL *stream* construct ([03_PDSL_STREAMS_DESIGN.md](03_PDSL_STREAMS_DESIGN.md)); a1/a2/a3 is
the driving example and the acceptance test, not the unit of design.

## 2. In-scope constraints (NOT tradeable for speed)

These were each violated by a prior attempt and explicitly reinstated by the owner. A change
that trades one of these away to hit a number is **cheating**, not progress.

1. **Stereo is in scope.** The path must support true stereo, not dual-mono. Stereo is a *shape
   of the sink stream* (one forward carrying both channels), not a second pipeline run.
2. **Do not grow the buffer size to hit the number.** Larger buffers are a last resort, used
   only after genuine alternatives are exhausted, and called out explicitly if used.
3. **Gains must come from more GPU parallelism or a better PDSL platform — not from doing less
   work.** Skipping right-channel prep, disabling reverb/efx in the "measured" path, or
   reducing element fidelity to make the ratio are disqualifying.
4. **Acoustic parity with the released system is in scope.** The PDSL mixdown must remain
   perceptually faithful to the last released (CellList) sound. Parity is judged by ear plus
   structural measures, **never** by a coarse windowed-RMS ratio alone. (Scope note: confirm
   with the owner whether parity work is part of *this* plan's delivery or tracked alongside;
   either way it is an acceptance dimension, not a thing to silently drop.)

## 3. The performance bar

The headline target is **~5× real-time**: end-to-end compute per tick ≤ **0.2×** the audio
duration of that tick. At 44.1 kHz that is **≤ 37.2 ms** for an 8192-frame tick (audio duration
185.8 ms) and **≤ 18.6 ms** for 4096.

**Frame 5× correctly.** 5× is not an independent goal to be chased by tuning; it is the expected
*consequence* of implementing the design to spec. If each element's audio is rendered once and
a3 only mixes pre-rendered audio, the per-tick work is the PDSL mixdown forward (already well
under budget — to be re-confirmed, see 01) plus cheap buffer assembly. If 5× does **not** fall
out once render-once holds, that is a real, surprising finding that triggers the feasibility
gate ([04_FEASIBILITY_GATE.md](04_FEASIBILITY_GATE.md)) — not a license to tune.

## 4. Acceptance gates (mechanical, un-fakeable)

"Done" means **all** of the following hold, each as an automated check, on the **production
configuration** (default driver/flags, real samples, `AudioScene.defaultSettings()`), measured
**end-to-end** (not the mixdown stage in isolation):

| # | Gate | How it is enforced (mechanical) |
|---|---|---|
| G1 | **Render-once invariant.** Over a full run, each pattern element's audio is synthesized at most once (modulo genome swap / cache eviction it explicitly accounts for). | A counter on the synthesis entry point asserts `synthesisDispatches ≤ distinctElements × smallConstant`. The ratio is logged every run; a regression test fails if it exceeds the bound. See [07](07_TOOLING_AND_GUARDRAILS.md). |
| G2 | **a3 never triggers a render.** The hot path contains only the mixdown forward + output streaming + clock advance. | The render counter asserts **zero** synthesis dispatches occur on the consumer/clock thread during steady-state ticks. |
| G3 | **Sustained 2-minute real-time render, all channels, real samples, non-silent.** | A test renders ≥ 2 min on every channel from the curated library, asserts `realTime=YES`, `silent=NO`, and a non-silence floor per channel (not just the master). |
| G4 | **Effects ON and stereo ON during the proving run.** | The 2-minute proving test runs with `enableEfx=true` and true-stereo output; a separate assertion confirms L and R are not bit-identical (i.e., stereo is real, not dual-mono). |
| G5 | **~5× end-to-end at the production buffer size.** | The benchmark reports the *end-to-end* per-tick ratio (compute / audio-duration) at 8192 and asserts ≤ 0.2, distinct from any DSP-in-isolation figure. |
| G6 | **Consistency across runs.** Similar results across ≥ 3 runs of the same pinned scene; no per-tick outlier that breaks real-time (kernel compile spikes must be pre-warmed away, not absorbed-and-ignored). | The benchmark runs ≥ 3 times; asserts the p100 per-tick (after warmup) stays under budget and run-to-run variance is bounded. |
| G7 | **Determinism / pinned scene.** The proving scene is fully pinned: fixed genome seed **and** committed `pattern-factory.json` **and** a seeded/committed arrangement — no unseeded `Math.random`, no reliance on an un-committed local `scene-settings.json`. | A harness asserts the same pinned inputs produce the same element set across runs. |
| G8 | **Acoustic parity.** The PDSL render is perceptually faithful to the released CellList render for the same pinned scene. | A/B render pair produced for owner listening + structural diff; the gate is owner sign-off, recorded with date. |

### What is explicitly *inadmissible* as evidence (anti-patterns from history)

- "Verified locally" when CI on the exact shipping config has not passed (local can run a
  different driver, depth, or flag set — it proves nothing).
- A measurement under a forced `-DAR_HARDWARE_DRIVER=mtl` (or `native`) presented as CI truth.
  Forced drivers are diagnostics only.
- A run with a flag forced on (e.g. aggregation) while the flag ships off.
- A "control" with a feature disabled (`enableReverb=false`, mono-only input) used to argue a
  comparison.
- A DSP-forward-in-isolation timing presented as the end-to-end realtime ratio.
- A single lucky tick, or a random/unseeded genome that happened to produce a silent or sparse
  channel.

## 5. Definition of the proving scene

One pinned scene drives G3–G8: the **densest** curated multi-channel scene (the worst case),
plus at least one sparser scene for headroom characterization. The dense scene is the one that
must pass G5/G6 — passing only on sparse material is not passing. The exact seed + factory +
arrangement are pinned in the harness ([07](07_TOOLING_AND_GUARDRAILS.md)) and committed so the
numbers reproduce.
