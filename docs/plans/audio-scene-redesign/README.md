# AudioScene Redesign — Plan Index

> The goal: render an `AudioScene` at **ratio-of-1** (render time per tick ≤ the
> audio duration of that tick; ~92.9 ms/tick at 44.1 kHz / 4096 frames). Today a
> 32-measure render costs ~311 ms/tick (3.3× over budget), and **~89% of that is
> a2 per-note rendering, ~99.4% of which is JNI dispatch overhead** — not compute.
> The fix is **Phase 3 graph batching** (collapse N per-note `evaluate()`s into one
> `CollectionProducer`), proven to win 100–1500× by the floor benchmark.
>
> This folder accumulated many documents across several investigation rounds. This
> index is the map: what to read first, what each file is for, and what is current
> vs. historical-but-cited vs. removed.

## Start here (reading order)

1. **[REALTIME_RENDERING_STATE_OF_PLAY.md](REALTIME_RENDERING_STATE_OF_PLAY.md)** —
   the honest one-page summary of where the effort stands and why the increments
   stalled. The single best entry point.
2. **[../AUDIO_SCENE_REDESIGN.md](../AUDIO_SCENE_REDESIGN.md)** — the phased master
   plan (Phase 1 DSP cutover, Phase 2 incremental, Phase 3 batched compilation,
   Phase 4 convergence). Lives in the parent `docs/plans/` directory.
3. **[REALTIME_RENDERING_DIAGRAMS.md](REALTIME_RENDERING_DIAGRAMS.md)** — the
   three-layer (a1/a2/a3) picture: ideal vs. today vs. a path between them.
4. **[METAL_DISPATCH_CEILING_LIFTED.md](METAL_DISPATCH_CEILING_LIFTED.md)** — what
   the `feature/metal-sustained-dispatch` branch unblocks (Metal is now a viable
   sustained backend) and, just as important, what it does **not** do (it does not
   replace Phase 3 graph batching). Read before resuming implementation.

## The required path (Phase 3 design detail)

| Document | Purpose | Status |
|----------|---------|--------|
| [PATTERN_RENDERING_FLOOR.md](PATTERN_RENDERING_FLOOR.md) | The benchmark that proves batching is necessary and sufficient (100–1500× speedup; 4-kernel padded-FIR batched = 1.89 ms at 64 notes/m). | **Foundational evidence** — cited throughout; keep. |
| [NOTE_GRAPH_SHAPES.md](NOTE_GRAPH_SHAPES.md) | The scope reduction: production notes are a small *closed set* of shapes, so a2 batching is "classify-and-dispatch," not "compile an arbitrary graph." | **Current** — load-bearing. |
| [VARIABLE_NOTE_SCHEDULING.md](VARIABLE_NOTE_SCHEDULING.md) | The "pad-to-max vs pitch-class tiling" question, resolved by separating the three axes of per-note variability. | **Current**. |
| [INTEGRATION_DESIGN.md](INTEGRATION_DESIGN.md) | How the verified `BatchedPatternRenderer` kernel wires into production `PatternFeatures.render` (the integration seam). | **Current**. |
| [PATTERN_SYSTEM_PHASE3_DESIGN.md](PATTERN_SYSTEM_PHASE3_DESIGN.md) | The detailed Phase 3 implementation design (predates the May-29 reframing; cross-check against STATE_OF_PLAY where they differ). | **Current, older framing**. |
| [PRIOR_ATTEMPT_POSTMORTEM.md](PRIOR_ATTEMPT_POSTMORTEM.md) | Why the earlier batched-wiring attempt fired for **zero** notes (wrong note shape), what to keep (the RMS=0 kernel, the sentinel-counter discipline) and what to discard. | **Current** — read before re-attempting the a1↔a2 seam. |

## Reference / deep-dives (cited, not the entry point)

| Document | Purpose | Status |
|----------|---------|--------|
| [ENVELOPE_INVESTIGATION.md](ENVELOPE_INVESTIGATION.md) | Volume/filter envelope locations, shapes, semantics, and the E1/E2/E3 batched-envelope benchmarks. Its conclusions were absorbed into PATTERN_SYSTEM_PHASE3_DESIGN. | **Reference** — keep for the detail. |

## Related, in the parent `docs/plans/` directory

- **[../PDSL_AUDIO_DSP.md](../PDSL_AUDIO_DSP.md)** — the declarative DSP substrate
  (now on master) that the a3/DSP half of the redesign builds on.
- **[../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md](../AUDIO_SCENE_BENCHMARK_INVESTIGATION.md)**
  — heap/GC regression work (Phenomena X/Y).

## Removed

- **`METAL_AMBIGUITY_BATCHED_RENDERER.md`** — an "investigation only" memo about a
  Metal `floor((long)global_id)` overload-ambiguity compile failure in
  `BatchedPatternRendererTest`. **Resolved and merged** (commits `385bb1c0a`,
  `85cc1f12d`, "Resolve Metal math intrinsic compile ambiguity…", both in branch
  history). The memo documented a now-fixed problem and was removed in this pass.
  Recover from git history if the codegen detail is ever needed again.
