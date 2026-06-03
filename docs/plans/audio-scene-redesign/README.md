# AudioScene Redesign — Plan Index

> **The goal:** render an `AudioScene` at ratio-of-1 (render time per tick ≤ the audio
> duration of that tick; ~92.9 ms/tick at 44.1 kHz / 4096 frames).
>
> **Where it stands (2026-06-03):** the two original blockers are solved — **a2 pattern
> rendering is batched** (N per-note dispatches → 1 per layer/tick) and the **Metal
> dispatch ceiling is fixed**. A full all-channel render now runs **near real-time
> (~1.1×)** on hybrid JNI+Metal routing. The remaining bottleneck is the **a3
> DSP/mixdown per-frame loop** (~99.6% of the tick, a frame-recurrent CPU loop). **The
> next phase is migrating that DSP loop from the hand-wired `MixdownManager`/`EfxManager`
> CellList to the PDSL substrate**, so the ML-model optimization tooling can attack it.

## Start here

1. **[STATE_OF_PLAY.md](STATE_OF_PLAY.md)** — the big picture: goal, current status, the
   a1/a2/a3 layers, what landed, the next phase, and the durable lessons. **Read first.**
2. **[PDSL_AUDIO_DSP.md](PDSL_AUDIO_DSP.md)** — the next-phase centerpiece: what PDSL can
   express for DSP today, the row-by-row `MixdownManager` migration map, the
   producer-valued-argument model, and the cutover plan.

## Reference

| Document | Purpose |
|----------|---------|
| [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md) | The production note model and how a2 batching works, as built (absorbs the former variable-note-scheduling and integration-design notes). |
| [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md) | The benchmark evidence that justified a2 batching (the 100–1500× speedup; 99.4%-is-JNI-dispatch finding). |
| [METAL_SUSTAINED_DISPATCH.md](METAL_SUSTAINED_DISPATCH.md) | The resolved Metal dispatch-ceiling fix — done-record with the durable invariants and failure lessons. |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Live platform constraints: hybrid-routing requirement, Metal 31-buffer limit, `floor()` resample ambiguity, `AR_PATTERN_CACHE_PERSIST`. |

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
