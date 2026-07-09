# AudioScene → PDSL Migration — Plan Index

> **Goal:** finish the migration of `AudioScene` real-time rendering to the PDSL-defined
> DSP path. The original performance goal was **achieved at 4096** (≈ 5× real-time
> including compilation and warmup, owner-measured on M4, 2026-07). The production
> default is now **1024** (owner decision, 2026-07-09) for its audible-quality and
> latency benefits, accepting that 1024 is not yet reliably real-time everywhere — the
> per-tick fixed-cost floor that gates it is handed off to the performance effort
> ([PERFORMANCE_HANDOFF.md](PERFORMANCE_HANDOFF.md)). **What remains here is
> acoustic:** the PDSL path must sound like the CellList baseline it replaces — plus
> the endgame items (true stereo, adapter retirement) that let CellList mixdown be
> deleted.

## Read in this order

1. **[NEXT_STEP.md](NEXT_STEP.md)** — the single current next step (close the audible
   gap) and the short queue behind it. **Start here.**
2. **[PDSL_DIFFERENCES.md](PDSL_DIFFERENCES.md)** — the 2026-07-09 divergence
   assessment: verified mechanism differences between the CellList and PDSL paths at
   buffer size 4096, the three open ring-arithmetic defects, why the divergence grows
   with duration and EFX share, and the ranked options for closing the gap.
3. **[KNOWN_ISSUES.md](KNOWN_ISSUES.md)** — live platform constraints (open ring
   defects, hybrid routing / Metal 31-buffer limit, cache-persist, curated-library
   dependence) and the compressed resolved-issues record.
4. **[PERFORMANCE_HANDOFF.md](PERFORMANCE_HANDOFF.md)** — the brief for the separate
   performance effort: the buffer-size sweep, the frame-independent cost floor, the
   argument-preparation NPE, and the acceptance bar for making 1024 reliably real-time.

## Related, elsewhere

- [`../PDSL_STREAMS_IDIOM.md`](../PDSL_STREAMS_IDIOM.md) — the run-ahead streams PDSL
  language idea, extracted from this effort because it generalizes beyond audio;
  `PatternRenderStream` is its shipped hand-built prototype.
- [`docs/internals/pdsl-audio-dsp.md`](../../internals/pdsl-audio-dsp.md) — the PDSL
  audio DSP substrate reference (primitives, multi-channel constructs, producer-valued
  arguments); moved out of this folder because it documents shipped capability.
- `docs/internals/celllist-realtime-streaming.md`, `features-pattern.md`,
  `backend-compilation-and-dispatch.md` — stable framework references.

## Keeping these current

Planning docs drift the moment code moves, and this set has been stale before:

- **Verify against source + `git log`, not memory or the consultant index** — both lag.
- **Update the doc in the same change that changes the claim.**
- **Date and qualify empirical claims** (machine, flags, buffer size — the 2026-07
  lesson: numbers derived at 8192 silently misled the 4096 production reality).
- **Name symbols, not just line numbers.**

## History

This folder once carried the full performance investigation (setup front-loading,
batched dispatch design, copy-migration plans, the claim-ledger-driven
`pdsl-streams-plan/`). That work is complete and its documents were removed in the
2026-07-09 consolidation — full text in git history. Durable material moved out:
the a2 batched-dispatch mechanism lives in the `BatchedPatternLayerRenderer` /
`BatchedPatternRenderer` javadocs and the sentinel tests; the DSP substrate reference
moved to `docs/internals/pdsl-audio-dsp.md`; the streams idiom moved to
`docs/plans/PDSL_STREAMS_IDIOM.md`.
