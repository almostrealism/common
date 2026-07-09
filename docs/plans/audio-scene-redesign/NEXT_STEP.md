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

1. **Step 0 — receipts.** Ring-semantics unit test (effective-delay assertions on
   `multiChannelDelayBlock` / `feedbackNetworkBlock`) + arm-gain bisection by ear on the
   real scene at 4096.
2. **A — fix the ring defects.** Enforce the ring-sizing invariant, size rings from the
   actual delays, unify the two `delay` forms' write/read order, quantize sub-frame
   gene delays up to one frame.
3. **B — re-align the reverb room.** Seconds-denominated ring, tap count decoupled from
   channel count, legacy-range tap spread. Knobs, not a rewrite.
4. **C — restore missing character.** Self-feedback gene on the grid diagonal,
   gene-driven feedforward delay, per-buffer delay drift (modulation approximation),
   per-sample parameter ramps for hot-bus automation, biquad-table coefficients for the
   in-loop filters.
5. **D — accept and document** what buffer size 4096 cannot reproduce
   (PDSL_DIFFERENCES §5).

Acceptance: long-render (≥ 3 min) A/B at 4096 on the curated scene judged by ear on wet
and reverb channels, with the Step-0 unit test green and no grinding signature. Bit
parity with the current output is **not** a gate — the current output contains the
defects.

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
   stable) — a real determinism question, deprioritized while 4096 is the production
   size.

## How to measure

- `AudioScenePdslCutoverTest` — the CellList/PDSL A/B on the real curated scene (needs
  the local sample library, KNOWN_ISSUES §7). Render long, listen to wet channels
  (`AR_GENERATE_CHANNEL=2`) and a reverb channel; windowed RMS is blind to the §2
  defects, so it is a level check only.
- `PdslHotPathBreakdownTest` / `PdslSetupBreakdownTest` — the perf instruments; keep
  them green as regression guards (p50 ratio at 4096, setup seconds, exact-peak
  parity where applicable).
- The batched sentinel battery (`studio/music`) — unchanged; guards a2 dispatch parity.
