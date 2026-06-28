# AudioScene Redesign — Plan Index

> **Goal:** a real-time `AudioScene` pipeline — batched pattern rendering with live genome
> swap, all DSP defined in PDSL, acoustic parity with the released system, at/under
> ratio-of-1 (~92.9 ms/tick at 44.1 kHz / 4096 frames).
>
> **Where it stands (updated 2026-06-28, `feature/pattern-batched-dispatch`):** the a3 DSP/mixdown
> is migrated to PDSL, parity-validated by ear, and runs under the realtime budget by
> default (faster than the legacy CellList path); the efx-feedback parity has closed its
> three biggest character gaps. a2 batched pattern dispatch now fires on real scenes
> (melodic and percussion), `enablePdslMixdown` is default-on, and the a1/a2/a3 ring
> decoupling is implemented and **measured to work** (a2 runs ahead, rarely blocks a3). The
> system is ~2–2.4× realtime steady-state; the remaining headline work is **5×**, then true
> stereo and kernel pre-warm. **The 5× bottleneck is the a3 mixdown `compiled.forward`'s fixed
> per-dispatch encode/arg-bind overhead — NOT a2.** The earlier "a2-bound / needs an a2 kernel
> redesign" framing is **superseded by measurement** — see [NEXT_STEP.md](NEXT_STEP.md) and
> `pdsl-streams-plan/04 §0b` / `05` Phase 2.
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
