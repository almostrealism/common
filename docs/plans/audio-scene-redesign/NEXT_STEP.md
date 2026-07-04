# Next Step — Stop Forcing Commits from the Hot Path (adjustVolume first)

> **Rewritten 2026-07-04** after the kernel-tooling merge (`remove-variable-relative-value`,
> `PackedCollectionMap` removal, OpenCL fixes) and a fresh measurement pass on the rebuilt
> system. The two prior versions of this page are in git history: the encoder/arg-bind theory
> (superseded 2026-06-28 by the `MemoryDataCopy` root cause) and the
> `MemoryDataCopy → Assignment` migration framing (superseded by `a3b20e285`, which chained
> every copy on the Semaphore mechanism instead — `apply` no longer blocks the host, and
> `MemoryDataCopy` is deprecated). The measurements below are from the current tree.

## Where the system stands (measured 2026-07-04, runs `f5f8486a` / `1dadc516`)

Pinned dense scene (seed 58, 1126 elements), efx+reverb on, `renderAheadSlots=24`,
200 sustained ticks, default hybrid driver:

| | 4096 (budget 92.9 ms) | 8192 (budget 185.8 ms) |
|---|---|---|
| p50 / p95 / max tick | 36.7 / 58.5 / 135 ms | 50.5 / 145 / 488 ms |
| p50 ratio (5× bar ≤ 0.2) | **0.40** (2.5×) | **0.27** (3.7×) |
| commits/tick (100 % host-wait) | 63.3 | 72.1 |
| `meanDispatchesPerCommit` | 2.90 | 3.36 |
| a2 gather+eval+marshal | 18.4 ms | 40.6 ms |
| batched vs per-note dispatches | 748 vs 3190 | 1395 vs 3541 |

End-to-end honesty (`GenerateAudioFileTest`, 8192): `setupSeconds=2.23`,
`generateRealtimeX=1.79`, peak 1.00 — both numbers are real (no pre-warm, one-buffer
prefill; see `SETUP_FRONT_LOADING_HANDOFF.md` for how the setup number was made honest).

Batching has recovered from the recorded collapse (`meanDispatchesPerCommit` 1.07 →
2.9/3.4) via the Semaphore copy-chaining and the earlier zeroing batches — but **every
commit is still a host-completion wait** (`maxOpenCommits=0`). The remaining distance to
5× is concentrated in a small number of named wait sources, now directly attributed by the
commit-cause requester histogram (`MetalCommandRunner.hostCompleteRequesters`, logged by
`PdslHotPathBreakdownTest`).

## The one thing to do

**Eliminate the per-cell `adjustVolume` evaluate — the single largest commit-forcer, and it
is currently a no-op.**

- `PatternSystemManager.sum` ends every render cell's batch with
  `AudioProcessingUtils.getSum().adjustVolume(destination, volume)` —
  `AudioSumProvider.scaleVolume` (`multiply(v(shape(-1),0), v(shape(1),1))`, a
  `collectionProductComputation`) run as a **synchronous `.into(dest).evaluate(...)`** once
  per render cell (~24 cells: 6 channels × MAIN/WET × L/R) per buffer.
- Measured: **~23 host-wait commits per tick at both buffer sizes** (22.84 @4096, 23.37
  @8192 — buffer-size-independent, matching the cell count) ≈ **a third of all commits**.
- `PatternSystemManager.enableAutoVolume` is hardcoded `false`, `volume` initializes to
  `1.0`, and no production caller invokes `PatternSystemManager.setVolume` — so the render
  path multiplies every cell buffer by **1.0**, ~23 times per tick, each forcing a command
  buffer commit + host wait.

Fix shape (in order of preference):
1. **Skip when identity.** Track the volume scalar host-side (it is written only by
   `setVolume` / the disabled auto-volume assignment) and skip `adjustVolume` when it is
   `1.0`. Zero risk, removes ~23 waits/tick outright in the production config.
2. **When a real volume is needed**, don't pay a per-cell synchronous evaluate: fold the
   per-cell volume into the batched dispatch chain (it can ride the existing per-note volume
   envelope scalars or the accumulate), or at minimum submit it as a chained `Submittable`
   (no `.evaluate()` read-back — nothing reads the result on the host).

Parity gate: rendered output must be bit-identical with the skip in place (it is a ×1.0),
on `PdslSetupBreakdownTest` (exact-peak) and `PdslHotPathBreakdownTest` (peak per size).

## The queue after that (ranked, each with its receipt)

1. **Per-note fallback volume: `mtlBlitCopy` waits (~16.5–20/tick).** The fallback count
   (~16–18 per tick: continuing notes render per-note by design) numerically matches the
   blit-copy wait count — the per-note path's evaluate + cache copy still host-waits per
   note. Two sub-options, measure both: (a) chain the per-note copy/evaluate instead of
   waiting (same treatment the aggregate copies got); (b) revisit batching continuing notes
   via the offset-aware `buildBatchedSssChainPlacedFromScalars` (`samplingOffsets` support
   already exists) — the "measured net loss" that justified the per-note fallback predates
   the merge and the probe fix, so re-measure before trusting it.
2. **a2 cost at 8192 (40.6 ms/tick > the 37.2 ms 5× bar on its own).** Gather (17.8 ms,
   Java-side) + eval (17.4 ms) at 8192. Overlapped on the producer thread today, but at 5×
   the consumer budget is small enough that a2 must also shrink (or 4096 becomes the
   production size — it is the owner-preferred size anyway; at 4096 a2 is 18.4 ms and hides
   completely).
3. **The remaining ~1/tick wait tail (~15 distinct forward-stage requesters).** These are
   the a3 mixdown forward's own per-stage boundary waits (one per materialized stage output
   per tick). The structural fix is the D+A arc — `REFACTOR_ORDERING_NOTES.md` items D
   (self-contained destination producer) + A (drop the `output` arg from `apply`), which is
   also where `DROP_OPERATION_OUTPUT_ARG.md` already carries the design. B and C from that
   arc landed in the 2026-07-04 merge; E's correctness class (pad-under-CL) is diagnosed
   in-progress there.
4. **`runnerBuildMs` ≈ 16 s** (one-time PDSL mixdown model build per runner construction) —
   the largest remaining one-time cost now that setup is ~2 s. Same first-evaluate scope
   machinery; profile before touching.
5. **True stereo** (G4) — feature completeness, unchanged by the above; the dual-mono
   master is still shipped.

## Already resolved / ruled out (do NOT re-chase)

- **Per-scene setup cost & mid-stream ~29–33 s "compile" spikes** — RESOLVED. Root cause
  was the `uniqueNonZeroOffset` gather-collapse probe on first evaluate of each batched
  kernel shape (14–29 s each, always returning null on scatter-add chains); fixed by
  `BatchedPatternRenderer.sumNoteAxis` (`setReplaceLoop(false)`). Setup 128.6 s → ~2.3 s;
  the whole-arrangement pre-warm was removed as front-loaded rendering. Full record:
  [`SETUP_FRONT_LOADING_HANDOFF.md`](SETUP_FRONT_LOADING_HANDOFF.md); correction to the
  prior "genuine per-scene rendering" claim:
  [`pdsl-streams-plan/HANDOFF_2026-06-28.md`](pdsl-streams-plan/HANDOFF_2026-06-28.md) §9.
- **Cross-scene kernel caching** — not a problem (`instrMisses=0` on scene 1, run
  `68bfcc17`; re-confirmed post-fix: scene 1 setup 2.1 s, run `f6094966`).
- **Command-buffer batching mechanics** — the Semaphore chaining (`a3b20e285`) works;
  commits are no longer forced by copy mechanics per se, but by the named synchronous
  evaluates above. Fix the callers, not the runner.
- **A potential framework hygiene follow-up** (not a 5× lever): a matrix-size cost gate in
  `TraversableExpression.uniqueNonZeroOffset` alongside the depth/node gates, so no other
  deep chain can hit the probe explosion the audio path hit.

## How to measure

- `PdslHotPathBreakdownTest#hotPathBreakdown` — sustained 200-tick p50/p95/max + ratio at
  4096 and 8192, a2 gather/eval/marshal split, batched-vs-fallback counts, Metal
  dispatch/commit counts, commit-cause split, and the per-requester wait histogram. This is
  the instrument that produced every number above; any change must move these, not a
  one-off timing.
- `GenerateAudioFileTest` — the honest end-to-end pair (`setupSeconds` + `generateRealtimeX`
  + non-silence). Any "real-time" claim must keep setup at seconds.
- `PdslSetupBreakdownTest` — exact-peak parity + per-stage setup attribution.

## Acceptance

Unchanged: ~5× end-to-end (p50 per-tick ratio ≤ 0.2) on the pinned dense scene at 4096
(preferred) or 8192, efx + stereo on, sustained ≥ 200 ticks, consistent across ≥ 3 runs —
the mechanical gates in
[`pdsl-streams-plan/00_OBJECTIVE_AND_ACCEPTANCE.md`](pdsl-streams-plan/00_OBJECTIVE_AND_ACCEPTANCE.md) §4.
