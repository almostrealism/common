# Next Step — Make the Tick's Composites Fully Submittable (one wait per tick)

> **Updated 2026-07-05** after `MTLSharedEvent` foreign-dependency bridging merged
> (PR #337, `6e3c29366` + `4b48b1f9e`) and a fresh measurement pass on the fully rebuilt
> branch. **The production buffer size is now 4096** (owner decision) — measure and design
> against the 92.9 ms budget / 18.6 ms 5× bar; 8192 numbers are secondary. The 2026-07-04
> content (adjustVolume win, refuted per-note-sums lever) is retained below as history.

## Where the system stands (measured 2026-07-05, runs `a85488c2` / `30810eaf`)

Pinned dense scene (seed 58, 1126 elements), efx+reverb on, 200 sustained ticks, default
hybrid driver, full clean rebuild including the shared-event merge:

| 4096 (budget 92.9 ms, 5× bar 18.6 ms) | value |
|---|---|
| p50 / p95 / max tick | 36.8 / 71.7 / **86.0** ms |
| p50 ratio | **0.40** (2.5×) |
| over-budget ticks | **0 / 200** |
| commits/tick | 42.7 — 100 % host-complete |
| `bridgeCommits` | **0** |
| a2 gather+eval+marshal | 25.0 ms (overlapped, producer thread) |
| rendered peak | 0.57 (stable) |

The shared-event merge did not move the steady-state medians (identical to the
pre-merge `ba6c43ca` baseline) but **tightened the tail decisively at 4096**: max tick
135–183 → 86 ms, over-budget 2–4/200 → 0/200. At 8192 p50 is 61 ms (ratio 0.33) with
24/200 over budget — one more reason 4096 is the production size.

Stage decomposition of the consumer tick (`pdslTickStageTiming`, run `30810eaf`): the
tick **is** the awaitSlot+forward stage (47.5 of 48.6 ms at 8192); automation refresh is
0.28 ms, output streaming 0.45 ms, clock advance 0.34 ms. There is no secondary consumer
cost worth chasing — the forward's composition is the whole game.

## The finding that sets the next step: the bridge never fires

`MetalCommandRunner` can now bridge a *foreign* dependency (a semaphore from another
backend, another context, or a composite latch) onto the GPU — a per-bridge
`MTLSharedEvent` wait signaled from the foreign completion's callback, no host block, no
forced commit (`enableHostSignaledBridges`, with `bridgeCommits` counting the
fresh-buffer commits bridging requires). **On this workload `bridgeCommits = 0` at both
buffer sizes: nothing ever submits a Metal dispatch with a foreign dependency.**

The mechanism (verified in `OperationListRunner.run()`): `Submittable` members chain via
`submit(pending)` with no wait, but every **non-submittable member** — a plain `Runnable`
lambda or a (deprecated) `MemoryDataCopy`, which the `copy()` helpers still construct
while `enableAssignmentCopy` is off — forces `pending.waitFor()` and **resets the chain**.
The tick's composites are peppered with such members, so:

- each one is a host wait → a forced commit (the ~15 distinct forward-stage requesters at
  ~1/tick each, plus waits whose chain tails land on copy-out blits → the `mtlBlitCopy`
  share) — this is `COMMIT_ATTRIBUTION.md`'s "composite completing its members
  synchronously" fingerprint, and
- the dependency chain is chopped before any cross-context handoff reaches
  `MetalCommandRunner.submit`, so the ordering that the shared-event bridge exists to
  provide is instead provided by the waits — **the bridge is starved by the very waits it
  was built to replace**.

## The one thing to do

**Migrate the non-submittable members of the tick/forward composites to `Submittable`
form, so the whole consumer tick becomes one chained submission with a single trailing
wait.** Concretely:

1. **Inventory the non-submittable members** of the compiled tick at 4096: instrument or
   inspect `OperationListRunner` member lists for the PDSL runner's tick (which members
   are not `Submittable`; expect the stage output-tracking / model-output-capture copies
   built by the `copy()` helpers, plus small glue lambdas). The ~15 stage-named requesters
   in run `a85488c2` are the work-list.
2. **Route those copies through the `Submittable` copy machinery** — `ComputeContext.copy`
   (blit-backed on Metal, already `Semaphore`-returning) or `Assignment` — *scoped to
   these call sites*, NOT via the global `enableAssignmentCopy` flip (still blocked by the
   gradient-descent divergence recorded in `MemoryDataFeatures`).
3. **Let the chain reach the bridge.** With the copies submittable, a Metal member that
   follows a CPU member (the recurrent DSP loop stage is CPU-resident under the 31-buffer
   limit) receives a foreign `dependsOn` and exercises the `MTLSharedEvent` bridge —
   cross-context ordering moves onto the device. Expect `bridgeCommits` to become nonzero
   and `hostCompletePerTick` to collapse toward ~1–2.

Verification: `PdslHotPathBreakdownTest` (now reporting `bridgeCommits`/`destroyCommits`
in its commitCause line) — success is host-complete commits/tick **42.7 → ≲ 5** at 4096,
p50 moving toward the 18.6 ms bar, peak 0.57 stable, and the sentinel/parity batteries
green. Watch for the 4b48b1f9e caveat: each bridge with a non-empty open buffer forces a
fresh-buffer commit, so bridge placement affects batching — if `bridgeCommits` grows to
~the member count, buffers are fragmenting and member *ordering* (group same-context
members) becomes part of the work.

## Shared-event system improvements (pursue only as the migration surfaces them)

The bridge is currently unexercised, so improving it now would be speculative. Once the
migration makes bridges fire ~10–15×/tick, these are the known candidates:

- **Per-bridge event allocation churn**: every bridge allocates a fresh
  `MTLSharedEvent` (`queue.getDevice().newSharedEvent()`) and releases it with the
  buffer; at tick rate that is hundreds of native allocations/sec — a pooled or
  per-(runner, foreign-source) event with a monotonic value would amortize it (the
  per-bridge-event design exists to prevent out-of-order releases, so pooling must keep
  values monotone per event).
- **Bridge-induced buffer fragmentation** (the `4b48b1f9e` fresh-buffer rule): if
  measured `bridgeCommits` per tick approaches the bridged-member count, consider
  encoding bridge waits only at buffer start (reorder members) or a second event
  strategy that tolerates in-buffer bridges.
- **CPU→Metal is bridged; Metal→CPU still waits**: a CPU member depending on a Metal
  semaphore must complete-wait it (the host genuinely reads the memory). That direction
  is bounded by the number of CPU-resident stages — reducing those (moving the recurrent
  loop's parallelizable parts onto Metal, per `KNOWN_ISSUES.md` §1) is the complementary
  lever.

---

# History — 2026-07-04 state and completed/refuted items

## Where the system stood (measured 2026-07-04, runs `f5f8486a` / `1dadc516`)

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

## The 2026-07-04 queue — superseded by the 2026-07-05 sections above

The consumer-side wait tail (item 1 of that queue) is now the top-of-page next step: the
`MTLSharedEvent` support it anticipated has landed (PR #337) and the measured finding is
that the tick's non-submittable members both cause the waits and starve the bridge. The
remaining items carry forward into the current queue below.

# Current queue after the submittable-composite migration (2026-07-05)

1. **Shared-event system improvements** — see the dedicated section near the top; pursue
   as the migration makes bridges fire.
2. **a2 cost** — at 4096 (the production size) a2 is ~25 ms/round on the producer thread
   and fully hidden behind the 36.8 ms consumer tick; it becomes a gate only when the
   consumer tick approaches the 18.6 ms bar. Revisit after the migration lands.
3. **`runnerBuildMs` ≈ 16 s** (one-time PDSL mixdown model build per runner
   construction) — the largest remaining one-time cost now that setup is ~2 s. Same
   first-evaluate scope machinery; profile before touching.
4. **True stereo** (G4) — feature completeness, unchanged by the above; the dual-mono
   master is still shipped.
5. **Tooling: thread-tagged commit-cause attribution.** Split `hostCompleteRequesters` by
   waiting thread (producer vs consumer) so lever decisions stop relying on elimination
   arguments (the 2026-07-04 misattribution below is the cautionary record).
6. **Diagnosis: the 8192 peak nondeterminism** (0.68 vs 0.63 across identical-code runs;
   4096 stable at 0.57) — secondary now that 4096 is the production size, but a real
   determinism question (G7) someone should eventually own.

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
  4096 (primary; 8192 secondary), a2 gather/eval/marshal split, batched-vs-fallback counts,
  Metal dispatch/commit counts, the commit-cause split (now including `bridgeCommits` and
  `destroyCommits`), and the per-requester wait histogram. This is
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
