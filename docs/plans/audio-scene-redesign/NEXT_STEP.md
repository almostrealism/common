# Next Step — Close the Audible Gap to the CellList Baseline

> **Updated 2026-07-09.** The performance mission of this folder is **complete**: the
> owner measures ~5× real-time or better in realistic conditions on M4 hardware
> (≈ 0.163 s per generated second, *including* compilation and warmup), after the
> probe fix, the Semaphore copy-chaining arc, `MTLSharedEvent` bridging (PR #337), and
> the operation-list subdivision / argument-preparation chaining work (PR #340, merged
> 2026-07-08). Performance levers, measurements, and the old queue live in git history
> (this file's previous revisions); do not re-open them here without a new regression.

## The single current next step

**Make the PDSL path sound like the CellList baseline** — the two paths audibly
diverge, the divergence grows with clip duration and EFX share, and it presents as a
grinding artifact. The full assessment (verified mechanisms, ranked causes, options) is
**[PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md)**; the headline is that three ring-sizing
defects in the delay/feedback/reverb stages (§2 there, summarized in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md) §1) mechanically generate per-buffer splice
discontinuities inside the feedback loops, on top of the known structural character
differences (reverb room, merged feedback loops, missing genes).

Execution order (PDSL_DIFFERENCES §6):

1. **Step 0 — receipts. DONE 2026-07-09** (`DelayBankBehaviorTest`; exact-timing tests
   in `DelayNetworkBehaviorTest` including the sample-exact-regeneration receipt).
2. **A — fix the ring defects. DONE 2026-07-09** (device-side band clamps; rings sized
   from actual delays; the two `delay` forms unified on write-first semantics).
3. **B — re-align the reverb room. DONE 2026-07-09** (seconds-denominated ring;
   `reverb_taps` decoupled from channels, default 32; golden-ratio spread over the
   legacy 0.15–1.5 s range; radius 1/taps). Owner listening verdict pending.
4. **C — restore missing character. ← CURRENT.** C1 **done 2026-07-09**: the
   self-feedback gene drives the grid diagonal and the wet arm's feedforward delay is
   the gene-driven bus delay (4–20 s `delay` chromosome — restoring the legacy efx
   bus's slow-building arrival); wiring pinned by
   `MixdownManagerPdslVerificationTest.feedbackGridAndBusDelayFollowGenes`. C2 **done
   2026-07-10** (pending by-ear verdict): per-buffer Euler integration of the
   `delayDynamics` cursor rate into a `bus_delay_drift` slot — the legacy wash's
   wobble and accelerando — pinned by `busDelayDriftAccumulatesWithClock`. C3 **done 2026-07-11**
   (pending by-ear verdict): the `ramp_scale` primitive replaces the stepped hot-bus
   gains (volume, efx automation, reverb send) with per-sample linear ramps between
   per-buffer slot refreshes — removing the 43 Hz staircase at 1024, the leading
   mechanical grit suspect — pinned by `RampScaleBehaviorTest` and
   `automationRampSlotsTrackPreviousValues`. C4 **done 2026-07-11**: the wet-filter
   bank now renders the legacy HP-then-LP `AudioPassFilter` cascade as biquad-table
   rows — and the investigation found the old bank's cutoffs had been meaningless
   since the cutover (a composed filter `Factor` misread as a frequency, clamping
   near 20 Hz) plus a missing high-pass half, so the wet bus had been drastically
   over-filtered all along (PDSL_DIFFERENCES §3.5, revised). The efx-loop bank was
   already legacy-faithful. Owner verdict on the C4 render: "much better; the gap is
   starting to seem more subtle." C5 **done 2026-07-11** (pending by-ear verdict):
   the **loop split** — the merged feedback stage is replaced by legacy's two distinct
   regeneration structures (PDSL_DIFFERENCES §3.2 revised, §6-C5): the per-channel
   pure-tap apply echo inside the apply chain on BOTH voicing arms (including the
   newly-discovered legacy MAIN-bus echo, which the assessment had missed entirely),
   and the bus-line network (clock-automated `wetInSimple` send into line 0 only,
   3 shared lines with per-line drift, unscaled transposed genome transmission,
   `wetOut` output taps, reverb send re-tapped to the apply output).
5. **D — accept and document** what a block-parallel buffer cannot reproduce
   (PDSL_DIFFERENCES §5). **Continuous delay-time modulation is NOT in this set** —
   see the queue below (owner directive 2026-07-11, PDSL_DIFFERENCES §5a).

Acceptance: long-render (≥ 3 min) A/B on the curated scene judged by ear on wet and
reverb channels, with the Step-0 unit tests green and no grinding signature. Bit
parity with pre-fix output is **not** a gate — that output contained the defects.

## Buffer size: the default is now 1024 (2026-07-09, owner decision)

`AudioScene.DEFAULT_REALTIME_BUFFER_SIZE` switched 4096 → **1024**, accepting that it
is not yet reliably real-time on all hardware. The 2026-07-09 buffer-size sweep found
a frame-count-independent per-tick cost floor (~21.6 ms early content / ~52 ms dense on
the worker M1) made of host-completion commits (~41–47/tick) and per-note fallback
dispatches (~15–19/tick), putting break-even at 1024 on that machine. Removing the
floor — plus the argument-preparation NPE fix that unblocks the benchmark — is
**handed off to the performance effort**: the full sweep data, mechanisms, three work
items, and the acceptance bar live in
**[PERFORMANCE_HANDOFF.md](PERFORMANCE_HANDOFF.md)**. Quality work (option C above)
proceeds here at 1024 in the meantime; re-test real-time viability when the
performance items land.

## Queue after the audible gap

1. **Smooth delay-rate pitch modulation (`AdjustableDelayCell.scale`) — owner
   directive 2026-07-11, highest priority after the current arc.** The continuous
   pitch bend on regenerations is a defining feature of the application's efx chain;
   the owner would "rather abandon real-time generation before abandoning it," so it
   must never sit in the accepted-limits category. It is tractable block-parallel: a
   rate-modulated read is a fractional-stride gather + lerp (the batched resampler's
   construct), not the blocked intra-frame recurrence — a resampling variant of
   `MultiChannelDspFeatures.ringValueAt` renders a genuine per-sample bend with the
   rate held per buffer. Full sketch: PDSL_DIFFERENCES §5a. Likely a separate branch
   (this one is already large).
2. **True stereo** — per-channel pan in the PDSL mixdown; the sink renders both stereo
   sides in one forward. Feature work, not parity.
3. **Adapter absorption** — `MixdownManagerPdslAdapter` is `@Deprecated` transitional
   glue; once PDSL is the only mixdown path, `MixdownManager` builds its own argument
   map and the adapter disappears (CellList mixdown retires with it, resolving the
   non-contiguous-channel fallback in KNOWN_ISSUES §6 one way or the other).
4. **`runnerBuildMs` ≈ 16 s** — one-time PDSL mixdown model build per runner
   construction; the largest remaining one-time cost. Profile before touching.
5. **Run-ahead streams as a PDSL capability** — the generalized `stream()`/run-ahead
   idiom extracted to [../PDSL_STREAMS_IDIOM.md](../PDSL_STREAMS_IDIOM.md);
   `PatternRenderStream` is the shipped hand-built prototype. Pursue when the language
   work is prioritized, not as part of parity.
6. **8192 rendered-peak nondeterminism** (0.63 vs 0.68 across identical runs; 4096 is
   stable) — a real determinism question, deprioritized while the production size is
   far below 8192.

## How to measure

- `AudioScenePdslCutoverTest` — the CellList/PDSL A/B on the real curated scene (needs
  the local sample library, KNOWN_ISSUES §7). Render long, listen to wet channels
  (`AR_GENERATE_CHANNEL=2`) and a reverb channel; windowed RMS is blind to the §2
  defects, so it is a level check only.
- `PdslHotPathBreakdownTest` / `PdslSetupBreakdownTest` — the perf instruments; keep
  them green as regression guards (p50 ratio at the production buffer size, setup
  seconds, exact-peak parity where applicable). The former measures {1024, 4096} as of
  2026-07-11 and runs in default (async) mode again — the argument-preparation NPE
  that briefly broke it was fixed by PR #344
  (see [PERFORMANCE_HANDOFF.md](PERFORMANCE_HANDOFF.md) item 1).
- The batched sentinel battery (`studio/music`) — unchanged; guards a2 dispatch parity.
