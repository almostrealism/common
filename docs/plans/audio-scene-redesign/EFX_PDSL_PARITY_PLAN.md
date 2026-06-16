# EfxManager → PDSL: DSP Parity — Done-Record

> **COMPLETE (2026-06-11): level/sweep parity reached** between the PDSL Block-forward
> runner and the Java CellList path on `feature/audio-scene-pdsl`, validated by ear by
> the owner on the full 40s real-scene A/B. This document is the *record* of that work —
> the standard it was held to, the defects found, and where the durable knowledge now
> lives. The complete inventory of remaining (accepted) differences is
> [PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md); outstanding work
> is tracked in [STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5. The full phased plan, stage
> logs, and superseded investigation threads are in git history of this file.

## The parity standard (keep applying it)

This was a project to **replace one mixdown process with another that produces the same
result** — indistinguishable to a normal listener; only a sound engineer doing careful
A/B listening should notice anything. The rules that got us there, which apply to ALL
future parity work:

- The only acceptable differences are sub-perceptual approximations whose mechanism is
  understood and bounded (FIR vs IIR shape, block-quantized feedback, FP ordering).
- A level/energy difference of more than a few percent is a DEFECT, not a "hotter mix."
- **Never compensate for a divergence by trimming a gain.** A divergence is localized
  and fixed at its structural source so the level comes out right on its own.
- Verification is per-stage A/B against the Java equivalent (RMS/peak AND by ear),
  never "is it non-silent."

## Final result

Windowed RMS ratios (PDSL/CellList, 5s windows, reverb-ON control): 0.88–1.03 across
the render, overall 0.94–0.99 across runs; the reverb-apex spread is the *control's own*
run-to-run variance (Java's `DelayNetwork` randomizes delay lengths). The audible
`mainFilterUp` spectral sweep tracks in both renders. Review artifacts:
`studio/compose/results/pdsl-cutover/review_celllist_6ch.wav` / `review_pdsl_6ch.wav` +
`manifest.txt`, regenerated deterministically by
`AudioScenePdslCutoverTest.realSceneAbReview` (persisted `scene-settings.json` + fixed
genome seed). Performance was NOT part of this result: the full-tick benchmark
(`AudioScenePdslBenchmarkTest` →
`studio/compose/results/pdsl-cutover/benchmark.txt`) later measured the PDSL tick
~10× over the realtime budget (constant per-tick overhead; see
[STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5) — an earlier "~20 ms steady state" note
covered the DSP portion alone.

## The six defects (each masked the others — the durable lesson list)

1. **`sum_channels` summed `channels × row0`.** `PdslInterpreter.callSumChannels` passed
   flat sample offsets where `subset()` takes per-dimension coordinates, so the entire
   mix was channel 0. Found by waveform correlation against consolidated rows.
   Regression: `MixdownManagerPdslVerificationTest.forEachChannelSumsDistinctRows` /
   `.mixdownMasterWetSumsDistinctChannels`, `DelayNetworkBehaviorTest.test00/b/c`.
   *Lesson: tests feeding identical content to all channels are blind to channel
   conflation — distinct-rows tests are mandatory for multi-channel constructs.*
2. **NaN poisoning of stateful rings via the build-time warm-up forward.**
   `AutomationManager`'s scale collection was uninitialized until `setup()`; the warm-up
   forward ran earlier, divided by zero, and wrote NaN into the delay/feedback rings,
   which recirculated forever (master clip's min/max turned NaN into ±0.99 "saturation";
   the old tanh master turned it into silence). Fixed: scale initialized to 1.0 at
   construction; consolidated buffer zero-filled at allocation (`PatternRenderBuffers`).
3. **Missing `AudioPassFilter` semantics.** Java clamps the filter input to ±0.99 every
   sample and bounds cutoffs to [10, 20000] Hz; both replicated (`clip` primitive,
   adapter bounds). The clamp — not filter shape — explained the "PDSL ~2× hotter on hot
   sources" signature.
4. **Filter slope.** Windowed-sinc FIR (brickwall) replaced by the truncated impulse
   response of Java's exact biquad (`MixdownManagerPdslAdapter.biquadImpulseResponse`),
   computed host-side (the recurrence cannot be a producer graph without an exponential
   expression tree).
5. **Swept coefficients delivered as per-buffer VALUES in collection slots**
   (`hp_coeffs`/`lp_coeffs` + value slots), refreshed by
   `MixdownManagerPdslAdapter.automationRefresh` each tick. Producer-valued model args
   are frozen at build; slots are the delivery mechanism for anything time-varying.
6. **Reverb arm gain structure** mirrored to Java's `DelayNetwork`: send × 0.1 into the
   lines, Householder feedback at spectral radius 1/N, output = mean over lines. The
   prior unit-gain/0.7-radius/summed version ran ~15× hotter at the automation apex.

Two earlier theories were *disproven* and should not be revived: "per-sample IIR FP
drift" and "`hp_cutoff` frozen at 0" (a cutoff of 0 for the first ~7 measures is correct
gene behavior; the probes were observing a working ramp).

## What remains open from this phase

Tracked in [STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5 (do not duplicate detail here):

- **True stereo** — one model carrying 2× channels in a single forward; never two
  compiles/two forwards. Dual-mono is the accepted stopgap.
- **Per-channel `EfxManager.apply` echo on the dry bus** — absent (~6% quieter dry bus);
  needs a multi-frame comb ring if ever closed.
- **Gene HP/LP `choice()` in compiled models** — efx gene filter is LP-only until PDSL
  supports choice in-graph.
- **Per-frame automation** — per-buffer steps accepted; only revisit if stepping is
  audible on faster envelopes.
- **Lean pattern prep** — `createPdsl` still builds the unused Java mixdown CellList
  via `getCells`; skipping it is the dominant build-time win. Replace only with care to
  keep the A/B sharing identical prep.
- **Live-swap staleness** — `wet_filter_coeffs`/`efx_filter_coeffs` etc. sample the
  genome at build; see PDSL_SIGNAL_PATH_DIFFERENCES.md §5.

## Repo-health note

The merge-prep refactors (2026-06-11): consolidated render-buffer management extracted
from `AudioScene` into `PatternRenderBuffers`; `PdslInterpreter` split into interpreter +
`PdslBuiltins` + `PdslFeatures`. Add new WET-routing/lean-prep behavior to
`AudioSceneRealtimeRunner`, not back into `AudioScene`.
