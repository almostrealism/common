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
   wobble and accelerando — pinned by `busDelayDriftAccumulatesWithClock`. Remaining:
   per-sample parameter ramps for hot-bus automation (C3), biquad-table coefficients
   for the in-loop filters (C4). Tune the granularity-dependent pieces against the
   **1024** production buffer (43 Hz update rate).
5. **D — accept and document** what a block-parallel buffer cannot reproduce
   (PDSL_DIFFERENCES §5).

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

1. **True stereo** — per-channel pan in the PDSL mixdown; the sink renders both stereo
   sides in one forward. Feature work, not parity.
2. **Adapter absorption** — `MixdownManagerPdslAdapter` is `@Deprecated` transitional
   glue; once PDSL is the only mixdown path, `MixdownManager` builds its own argument
   map and the adapter disappears (CellList mixdown retires with it, resolving the
   non-contiguous-channel fallback in KNOWN_ISSUES §6 one way or the other).
3. **`runnerBuildMs` ≈ 16 s** — one-time PDSL mixdown model build per runner
   construction; the largest remaining one-time cost. Profile before touching.
4. **Run-ahead streams as a PDSL capability** — the generalized `stream()`/run-ahead
   idiom extracted to [../PDSL_STREAMS_IDIOM.md](../PDSL_STREAMS_IDIOM.md);
   `PatternRenderStream` is the shipped hand-built prototype. Pursue when the language
   work is prioritized, not as part of parity.
5. **8192 rendered-peak nondeterminism** (0.63 vs 0.68 across identical runs; 4096 is
   stable) — a real determinism question, deprioritized while the production size is
   far below 8192.

## How to measure

- `AudioScenePdslCutoverTest` — the CellList/PDSL A/B on the real curated scene (needs
  the local sample library, KNOWN_ISSUES §7). Render long, listen to wet channels
  (`AR_GENERATE_CHANNEL=2`) and a reverb channel; windowed RMS is blind to the §2
  defects, so it is a level check only.
- `PdslHotPathBreakdownTest` / `PdslSetupBreakdownTest` — the perf instruments; keep
  them green as regression guards (p50 ratio at the production buffer size, setup
  seconds, exact-peak parity where applicable). The former is currently broken on
  master by the argument-preparation NPE (see
  [PERFORMANCE_HANDOFF.md](PERFORMANCE_HANDOFF.md)); run with
  `-DAR_HARDWARE_ASYNC=disabled` until that fix lands.
- The batched sentinel battery (`studio/music`) — unchanged; guards a2 dispatch parity.
