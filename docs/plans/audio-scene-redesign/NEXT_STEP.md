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

## The one thing to do — DONE (2026-07-04, run `ba6c43ca`)

**Eliminate the per-cell `adjustVolume` evaluate — the single largest commit-forcer, and it
is currently a no-op.** Implemented as fix shape 1 (skip when identity):
`PatternSystemManager` tracks the volume scalar host-side (valid only while
`enableAutoVolume` is off — auto-volume writes the collection device-side) and skips the
per-cell `adjustVolume` when it is `1.0`.

Measured result (pinned scene, 200 sustained ticks): commits/tick **63.3 → 42.7 @4096**
and **72.1 → 49.2 @8192**; the `f_collectionProductComputation` requester (~23 waits/tick)
is gone from the histogram at both sizes; `meanDispatchesPerCommit` 2.90 → 3.87 / 3.36 →
4.44; p50 ratio **0.39 @4096 / 0.24 @8192**. The 4096 rendered peak is unchanged (0.57).
When a real (non-unity) volume is someday needed per-cell, fold it into the batched
dispatch chain rather than reinstating the per-cell evaluate (fix shape 2, unimplemented).

## REFUTED (2026-07-04): the "per-note fallback blit waits" lever — do not re-chase

The previous version of this page ranked "chain the per-note `sumToDestination` /
accumulate sums" as the next lever, attributing the `mtlBlitCopy` waits (~17–20/tick) to
them. **This was implemented two ways and measured to be wrong** (runs `bf0fb2fa`
un-pinned, `d8787f87` GPU-pinned): deferring those sums via
`request`+`CompletionConsumer` with a per-round drain removed the targeted synchronous
waits, yet **commits/tick and the `mtlBlitCopy` wait count did not move at all** in either
variant (42.6/49.2 commits, 17.6/20.4 blit waits — identical to the skip-only run), and the
GPU-pinned variant regressed p50. The wiring was reverted.

Two durable lessons, bought with receipts:
- **`hostCompleteRequesters` is JVM-global across threads.** The blit waits belong (by
  elimination) to the **a3 consumer thread** — the forward's per-stage boundary waits whose
  semaphore-chain tails are aggregation copy-out blits — not to the producer-side pattern
  sums. A producer-side change cannot be judged from the global histogram; thread-tagged
  wait attribution is the missing instrument.
- **The 8192 rendered peak is run-to-run nondeterministic** (0.68 vs 0.63 across
  identical-code runs of `PdslHotPathBreakdownTest`; 4096 is stable at 0.57). Do not use
  the 8192 peak as a parity verdict in either direction; the cause (producer/consumer ring
  timing vs note-cache eviction is the leading candidate) is itself worth a diagnosis.

## The queue after that (ranked, each with its receipt)

1. **The consumer-side wait tail — now measured as ~35–40 of the remaining ~43–49
   commits/tick.** The ~15 distinct forward-stage requesters at ~1/tick each PLUS the
   `mtlBlitCopy` waits (~17–20/tick, re-attributed above) are the a3 forward's own
   per-stage boundary waits (one per materialized stage output, tails landing on copy-out
   blits) and the automation-refresh evaluates. This is where the remaining distance to 5×
   is concentrated. The structural fixes are (a) the owner's planned **`MTLSharedEvent`
   support** (cross-JNI/MTL coordination so stage boundaries stop host-waiting), and (b)
   the D+A arc — `REFACTOR_ORDERING_NOTES.md` items D (self-contained destination
   producer) + A (drop the `output` arg from `apply`), designed in
   `DROP_OPERATION_OUTPUT_ARG.md`. B and C from that arc landed in the 2026-07-04 merge.
2. **a2 cost at 8192 (40.6 ms/tick > the 37.2 ms 5× bar on its own).** Gather (17.8 ms,
   Java-side) + eval (17.4 ms) at 8192. Overlapped on the producer thread today, but at 5×
   the consumer budget is small enough that a2 must also shrink (or 4096 becomes the
   production size — it is the owner-preferred size anyway; at 4096 a2 is 18.4 ms and hides
   completely).
3. **`runnerBuildMs` ≈ 16 s** (one-time PDSL mixdown model build per runner construction) —
   the largest remaining one-time cost now that setup is ~2 s. Same first-evaluate scope
   machinery; profile before touching.
4. **True stereo** (G4) — feature completeness, unchanged by the above; the dual-mono
   master is still shipped.
5. **Tooling: thread-tagged commit-cause attribution.** Split `hostCompleteRequesters` by
   waiting thread (producer vs consumer) so the next lever decision does not repeat the
   misattribution above. Small `MetalCommandRunner`/`PdslHotPathBreakdownTest` change.

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
