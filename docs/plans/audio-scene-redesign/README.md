# AudioScene Redesign — Plan Index

> **The goal:** render an `AudioScene` at ratio-of-1 (render time per tick ≤ the audio
> duration of that tick; ~92.9 ms/tick at 44.1 kHz / 4096 frames).
>
> **Where it stands (2026-06-12, `feature/audio-scene-pdsl`):** the **a3 DSP/mixdown
> migration to PDSL is done, parity-validated by ear, and well under the realtime budget** — the full
> mixdown/efx/reverb path runs as one compiled PDSL model per buffer behind the
> `MixdownManager.enablePdslMixdown` A/B flag (default off), ticking at 0.81–1.15×
> realtime at 8192 frames by default and 1.34–2.81× with opt-in vectorized for-each
> (faster than the CellList path either way) after the dispatch-fragmentation fixes.
> What remains: the instruction-rebinding fix that lets vectorization default on,
> true stereo, and the accepted-difference review before flipping the default.
> See STATE_OF_PLAY §5 for the to-do list and PDSL_SIGNAL_PATH_DIFFERENCES for the
> swap impact.

## Start here

1. **[STATE_OF_PLAY.md](STATE_OF_PLAY.md)** — the big picture: goal, current status, the
   a1/a2/a3 layers, what landed, and what is outstanding. **Read first.**
2. **[PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md)** — the complete
   inventory of expected differences between the PDSL and legacy signal paths; the
   impact assessment for flipping `enablePdslMixdown`.
3. **[PERCEPTUAL_MAP.md](PERCEPTUAL_MAP.md)** — the listener's companion to the inventory:
   what each difference *sounds like* (symptom → cause → lever), with triage for which to
   chase toward the legacy sound vs accept. Open this during an A/B listening session.

## Reference

| Document | Purpose |
|----------|---------|
| [EFX_PDSL_PARITY_PLAN.md](EFX_PDSL_PARITY_PLAN.md) | Done-record of the DSP parity work: the parity standard, the six masking defects, durable lessons. |
| [PDSL_AUDIO_DSP.md](PDSL_AUDIO_DSP.md) | The PDSL DSP substrate reference: capabilities, the row-by-row `MixdownManager` migration map, the producer-valued-argument model. Largely historical now that the migration is complete. |
| [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md) | The production note model and how a2 batching works, as built (absorbs the former variable-note-scheduling and integration-design notes). |
| [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md) | The benchmark evidence that justified a2 batching (the 100–1500× speedup; 99.4%-is-JNI-dispatch finding). |
| [METAL_SUSTAINED_DISPATCH.md](METAL_SUSTAINED_DISPATCH.md) | The resolved Metal dispatch-ceiling fix — done-record with the durable invariants and failure lessons. |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Live platform constraints: hybrid-routing requirement, Metal 31-buffer limit, `floor()` resample ambiguity, `AR_PATTERN_CACHE_PERSIST`, compile-reuse pool. |
| [SINGLE_CHANNEL_PDSL_AND_DOC_PARITY.md](SINGLE_CHANNEL_PDSL_AND_DOC_PARITY.md) | Handoff request (from ringsdesktop's "Combine" tab): give the PDSL path single-channel support, drop the unused `getCells` mixdown CellList on the PDSL path, and correct the stale efx/reverb parity claims in the code/docs. |

## Related, elsewhere (out of scope for this folder)

- `../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md` — a separate, still-live heap-retention
  (Phenomenon X/Y) workstream on the optimizer, not the render-pipeline redesign.
- `docs/internals/celllist-realtime-streaming.md`, `docs/internals/features-pattern.md`
  — stable framework references the redesign builds on.

## History

Superseded design/investigation docs (Phase-3 design, envelope investigation, the prior
zero-note postmortem, the loop-optimization and redesign journals, and the original
phased master plan) were distilled into the documents above and removed; their full text
remains in git history.
