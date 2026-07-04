# AudioScene Redesign — Plan Index

> **STATUS UPDATE (2026-07-04) — read before anything else.** Three things changed since the
> 2026-06-28 banners:
>
> 1. **The setup front-load and the mid-stream "compile spike" are RESOLVED.** Both were the
>    `uniqueNonZeroOffset` gather-collapse probe running on the first evaluate of every batched
>    kernel shape (14–29 s each, always failing on scatter-add chains) — *not* rendering and
>    *not* native compilation. Fixed by `BatchedPatternRenderer.sumNoteAxis`
>    (`setReplaceLoop(false)`); setup is now ~2.3 s (was 128.6 s), the whole-arrangement
>    pre-warm is removed, and the honest end-to-end is `generateRealtimeX=1.79` @8192 with
>    `setupSeconds=2.23`. Record: [`SETUP_FRONT_LOADING_HANDOFF.md`](SETUP_FRONT_LOADING_HANDOFF.md);
>    correction to the prior handoff's conclusion:
>    [`pdsl-streams-plan/HANDOFF_2026-06-28.md`](pdsl-streams-plan/HANDOFF_2026-06-28.md) §9.
> 2. **The `MemoryDataCopy` batching collapse was addressed by a different design than the one
>    planned here.** `a3b20e285` chains every copy on the Semaphore mechanism
>    (`AcceleratedOperation.apply` no longer blocks the host; `MemoryDataCopy` deprecated).
>    Measured after: `meanDispatchesPerCommit` 1.07 → 2.9 @4096 / 3.4 @8192 — but commits are
>    still 100 % host-completion-driven (~63–72/tick), now attributed per-requester. The
>    `Assignment` migration ([`../ASSIGNMENT_COPY_MIGRATION.md`](../ASSIGNMENT_COPY_MIGRATION.md))
>    remains a separate, paused effort (its flag is default-off for ML-training reasons).
> 3. **The kernel-tooling simplification arc landed** (`getValueRelative` removed,
>    `PackedCollectionMap` removed, OpenCL index/abs fixes — items B/C/E of
>    [`../REFACTOR_ORDERING_NOTES.md`](../REFACTOR_ORDERING_NOTES.md)); the remaining arc is
>    D+A ([`../DROP_OPERATION_OUTPUT_ARG.md`](../DROP_OPERATION_OUTPUT_ARG.md), still a study).
>
> The current bottleneck attribution and the ranked path to 5× live in
> **[NEXT_STEP.md](NEXT_STEP.md)** (rewritten 2026-07-04 with fresh receipts).

> **Goal:** a real-time `AudioScene` pipeline — batched pattern rendering with live genome
> swap, all DSP defined in PDSL, acoustic parity with the released system, at/under
> ratio-of-1 (~92.9 ms/tick at 44.1 kHz / 4096 frames).
>
> **Where it stands (updated 2026-07-04, `feature/pattern-batched-dispatch`):** the a3
> DSP/mixdown is migrated to PDSL, parity-validated by ear, `enablePdslMixdown` is default-on,
> a2 batched dispatch fires on real scenes, and the a1/a2/a3 ring decoupling works (a2 runs
> ahead; a3 rarely waits). Setup is honest (~2.3 s) and steady-state is **p50 ratio 0.40 @4096
> (2.5×) / 0.27 @8192 (3.7×)**, sustained 200 ticks, efx+reverb on (run `1dadc516`). The
> remaining headline work is **5× (ratio ≤ 0.2)**, then true stereo. The measured levers, in
> order: the no-op per-cell `adjustVolume` synchronous evaluate (~23 host-wait commits/tick —
> a third of all commits), the per-note fallback's blit-copy waits (~17–20/tick), a2's own
> cost at 8192 (40.6 ms/tick), and the per-stage forward wait tail (the D+A framework arc).
>
> **Authoritative current plan: [`pdsl-streams-plan/`](pdsl-streams-plan/)** (claim-ledger-gated,
> measurement-first); the single current next step is **[NEXT_STEP.md](NEXT_STEP.md)**. The other
> docs here are reference (a2 mechanism, PDSL differences, DSP substrate, known issues) and the
> evidence the ledger adjudicates.

## Read in this order

1. **[NEXT_STEP.md](NEXT_STEP.md)** — **the single, current next step**: reduce the a3 mixdown
   forward's per-dispatch Metal overhead, with the evidence, what's already ruled out, and the
   acceptance bar. **Start here.**
2. **[pdsl-streams-plan/](pdsl-streams-plan/)** — the authoritative plan: objective + acceptance,
   the claim-verification ledger, ground-truth architecture, the run-ahead-stream design, the
   feasibility gate, the phased migration, risks, tooling, and the dated handoff.
3. **[A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md)** — the a2 per-note batching subsystem (note
   model, why batching, wiring) — a settled, working subsystem kept as mechanism reference.
4. **[PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md)** — the PDSL-vs-CellList signal-path difference
   inventory and parity triage (parity is a separate effort). Read before flipping `enablePdslMixdown`.

## Reference

| Document | Purpose |
|---|---|
| [PDSL_DSP_REFERENCE.md](PDSL_DSP_REFERENCE.md) | The PDSL audio DSP substrate: primitive catalog, multi-channel constructs, the producer-valued-argument model. |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Live platform constraints (hybrid routing / Metal 31-buffer limit, cache-persist, `floor()` resample) + resolved-issue records (a2 real-scene dispatch, aggregation/compile-reuse, instruction-set reuse, Metal sustained dispatch). |

## Related, elsewhere (out of scope for this folder)

- `../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` — a separate heap-retention workstream on the
  optimizer, not the render-pipeline redesign.
- `docs/internals/celllist-realtime-streaming.md`, `features-pattern.md`,
  `backend-compilation-and-dispatch.md` — stable framework references the redesign builds on.

## Keeping these current

These are planning docs, not API reference — they drift the moment the code moves, and this
set has been stale before. When you touch this area, keep them honest:

- **Verify against source + `git log`, not memory or the consultant index.** Both lag
  reality: a recent recall still described the a2 real-scene gap and the
  argument-aggregation pool blocker as open *after both were resolved*, and asserted
  nothing was outdated. Treat any claim here — and any recalled memory — as something to
  re-check against current code before relying on it.
- **Update the doc in the same change that changes the claim.** The recurring failure was
  fixing code (or learning a fact) and leaving the doc behind.
- **Date and qualify empirical claims.** Benchmark/validation numbers carry the date,
  machine, and flags (e.g. `AR_PDSL_MIXDOWN`), because they do not reproduce identically
  across hardware or genomes.
- **Name symbols, not just line numbers.** Prefer `Class.method` / a stable constant name
  over a bare `:NNN`; line numbers rot fastest.

## History

This folder was consolidated from a larger set of phase/investigation docs. Superseded
material — the original phased plan, the EfxManager→PDSL parity done-record, the
note-graph-shapes and pattern-rendering-floor studies, the single-channel / doc-parity
handoff, the Metal-sustained-dispatch and argument-aggregation-gap records, and the
mechanism/perceptual split of the differences inventory — was distilled into the documents
above and removed; their full text remains in git history.
