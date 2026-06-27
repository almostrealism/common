# AudioScene Redesign — Plan Index

> **Goal:** a real-time `AudioScene` pipeline — batched pattern rendering with live genome
> swap, all DSP defined in PDSL, acoustic parity with the released system, at/under
> ratio-of-1 (~92.9 ms/tick at 44.1 kHz / 4096 frames).
>
> **Where it stands (2026-06-26, `master`, merged):** the a3 DSP/mixdown is migrated to
> PDSL, parity-validated by ear, and runs under the realtime budget by default (faster
> than the legacy CellList path); the efx-feedback parity has closed its three biggest
> character gaps. The primary open work is **a2** batched pattern dispatch on real scenes;
> then true stereo, flipping `enablePdslMixdown` on by default, and a1/a2/a3 ring
> decoupling. See STATE_OF_PLAY for the full status.

## Read in this order

1. **[STATE_OF_PLAY.md](STATE_OF_PLAY.md)** — the big picture: goal, current status, the
   a1/a2/a3 layers, what landed, what's open. **Start here.**
2. **[A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md)** — the a2 per-note batching
   subsystem (note model, why batching, wiring) and the open real-scene `peak=0.0` gap.
   The next work happens here.
3. **[PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md)** — the complete PDSL-vs-CellList
   signal-path difference inventory, the perceptual symptom→cause→lever map, and the
   swap-impact triage. Read before flipping `enablePdslMixdown`.

## Reference

| Document | Purpose |
|---|---|
| [PDSL_DSP_REFERENCE.md](PDSL_DSP_REFERENCE.md) | The PDSL audio DSP substrate: primitive catalog, multi-channel constructs, the producer-valued-argument model. |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Live platform constraints (hybrid routing / Metal 31-buffer limit, cache-persist, `floor()` resample, the a2 real-scene gap) + resolved-issue records (aggregation/compile-reuse, instruction-set reuse, Metal sustained dispatch). |

## Related, elsewhere (out of scope for this folder)

- `../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` — a separate heap-retention workstream on the
  optimizer, not the render-pipeline redesign.
- `docs/internals/celllist-realtime-streaming.md`, `features-pattern.md`,
  `backend-compilation-and-dispatch.md` — stable framework references the redesign builds on.

## History

This folder was consolidated from a larger set of phase/investigation docs. Superseded
material — the original phased plan, the EfxManager→PDSL parity done-record, the
note-graph-shapes and pattern-rendering-floor studies, the single-channel / doc-parity
handoff, the Metal-sustained-dispatch and argument-aggregation-gap records, and the
mechanism/perceptual split of the differences inventory — was distilled into the documents
above and removed; their full text remains in git history.
