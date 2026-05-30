# Real-Time AudioScene Rendering — Three Diagrams

> Companion to [`REALTIME_RENDERING_STATE_OF_PLAY.md`](REALTIME_RENDERING_STATE_OF_PLAY.md).
> Three diagrams drawn on the **same three-layer grid** so they can be compared
> directly:
>
> - **Diagram A** — the simple, hypothetical "this is how a working real-time
>   renderer is shaped" (the target).
> - **Diagram B** — what our system actually does today (so the gap is visible).
> - **Diagram C** — a path from B toward A using the classes we already have.
>
> Shared legend (used in all three):
>
> | Symbol | Meaning |
> |--------|---------|
> | **a1** | pattern generation — *which* notes exist (positions, pitch, ADSR, automation) |
> | **a2** | per-note audio rendering — synthesize each note's samples (resample + envelopes + automation) |
> | **a3** | frame-buffer rendering — mix live notes into N frames and play them |
> | `⟿` ring | a rolling/double buffer that decouples one layer's clock from the next |
> | **horizon** | how far *ahead of playback* the layer is allowed to work |
> | **cadence** | how often the layer runs |
> | **grain** | the natural unit of work for the layer |

---

## Diagram A — The hypothetical working real-time renderer (TARGET)

The essential idea: **three layers, three clocks, decoupled by buffers.** Each
layer runs as far ahead as its cost allows and hands off through a ring buffer.
Nothing downstream ever waits on a synchronous upstream call.

```
        horizon: MINUTES            horizon: ~SECONDS            horizon: NOW (just-in-time)
        cadence: rare / on-edit     cadence: every few buffers   cadence: every audio buffer
        grain:   whole arrangement  grain:   a window of notes    grain:   one frame-buffer
        cost:    cheap              cost:    costly               cost:    must beat the clock

   ┌──────────────────────┐    ┌──────────────────────────┐    ┌───────────────────────────┐
   │  a1  PATTERN GEN     │    │  a2  PER-NOTE RENDER      │    │  a3  FRAME-BUFFER RENDER  │
   │                      │    │                          │    │                           │
   │ genome → note events │    │ for the next few seconds │    │ pull the next 1024/4096   │
   │ positions, pitch,    │    │ of scheduled notes:      │    │ frames from the note-audio│
   │ duration, ADSR,      │    │  resample → vol env →    │    │ ring, run mixdown/effects │
   │ automation curves    │    │  filter env → automation │    │ → write to output line    │
   │                      │    │ write into note-audio ring   │                           │
   │  ── PURE DATA ──     │    │                          │    │  ── must keep ratio-of-1 ──│
   └─────────┬────────────┘    └────────────┬─────────────┘    └─────────────┬─────────────┘
             │  note-SCHEDULE                │  note-AUDIO                    │  output
             │  ⟿ ring (minutes ahead)       │  ⟿ ring (seconds ahead)        │  line / DAC
             ▼                               ▼                                ▼
        [ schedule buffer ] ───────────▶ [ audio ring buffer ] ────────────▶ [ speaker ]
                       back-pressure ◀── pacing ◀── playback clock ◀── BufferedOutputScheduler gap

   INVARIANTS that make it "work":
     • a3 is the only layer bound to the real-time clock; a1/a2 run AHEAD and never block a3.
     • each seam is a buffer, so each layer can have a DIFFERENT grain and cadence.
     • the playback clock (a3) applies BACK-PRESSURE up the chain: if a3 is starving,
       a2 renders more aggressively; if a2 is behind, a1 has already supplied the schedule.
     • a1 is cheap enough to pre-compute the whole arrangement; a2 only ever materializes
       the slice of notes about to be heard; a3 only ever sees ready samples.
```

The whole trick is the two ring buffers and the three independent clocks. That
is the entire idea of Diagram A.

---

## Diagram B — What we actually do today (CURRENT)

We *do* stream buffers (`AudioScene.runnerRealTime`), but **a1 and a2 are fused
into one synchronous Java call per buffer, and a3 cannot start until that call
returns.** There are no decoupling ring buffers between the layers — the only
buffering is the output-side `BufferedOutputScheduler`. So the per-buffer
critical path is `a1+a2 (278 ms) → a3 (33 ms)`, serialized, every buffer.

```
        ┌──────────────────────────── PER BUFFER (one TemporalCellular tick) ─────────────────────────────┐
        │                                                                                                  │
        │   ░░░ SETUP PHASE — Java, on the host, SYNCHRONOUS, ~278 ms ░░░         ▓▓▓ TICK PHASE ▓▓▓        │
        │                                                                          ▓ compiled, ~33 ms ▓    │
        │   ┌───────────────────── a1 + a2  FUSED ─────────────────────┐          ▓                  ▓    │
        │   │ PatternAudioBuffer.prepareBatch()                        │          ▓ per-frame loop:  ▓    │
        │   │   └ PatternSystemManager.sum()                           │   sums   ▓  MixdownManager  ▓    │
        │   │       └ PatternLayerManager.sumInternal()                │  into →  ▓  EfxManager      ▓    │
        │   │           │  a1: frame→measure, which repetitions/       │ consol-  ▓  filters/delay/  ▓    │
        │   │           │      elements overlap, getNoteDestinations   │ idated   ▓  reverb/volume   ▓    │
        │   │           └ PatternFeatures.renderPerNote()              │ render   ▓  → output buffer ▓    │
        │   │               for EACH note:                             │ buffer   ▓                  ▓    │
        │   │                 note.getProducer(-1).evaluate()  ◀── ✗   │          ▓                  ▓    │
        │   │                 └ ONE JNI dispatch PER NOTE              │          ▓                  ▓    │
        │   │                 sum overlap → destination                │          ▓                  ▓    │
        │   │               (NoteAudioCache removes a hit one at a time)│          ▓                  ▓    │
        │   └───────────────────────────────────────────────────────────┘          ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓    │
        │                                                                                  │              │
        └──────────────────────────────────────────────────────────────────────────────────┼──────────────┘
                                                                                            ▼
                                                                              BufferedOutputScheduler → line

   WHERE IT DIVERGES FROM DIAGRAM A:
     ✗ No a1/a2 split: scheduling and synthesis happen together, at buffer grain, every buffer.
     ✗ No horizon: a2 is NOT run seconds ahead — it runs for THIS buffer, inline, while a3 waits.
     ✗ The ✗ line is the wall: N notes ⇒ N JNI dispatches ⇒ ~99.4% of the 278 ms is boundary overhead.
     ✗ Only one clock: a3's per-frame loop can't advance until the a1+a2 setup phase finishes.
     ✓ a3 itself is already shaped right (compiled per-frame loop + BufferedOutputScheduler).

   The incremental fixes (warmNoteCache, consolidateRenderBuffers, GC-at-genome-boundary,
   cache hit-rate) all live INSIDE the ░ setup box and inside the "one evaluate per note" model.
   They shave the box; they do not split it or change N-dispatches into 1.
```

**Side-by-side with A:** A has three boxes on three clocks joined by two rings.
B has A's three *concepts* but collapses a1+a2 into one synchronous box with **no
ring** between it and a3, and renders notes **one JNI dispatch at a time** inside
it. The structure — not the tuning — is the difference.

---

## Diagram C — A reachable target using the classes we already have

Same three-clock shape as Diagram A, built from existing parts. Two changes do
the real work: **(1)** split scheduling (a1) from synthesis (a2) so a1 emits a
plain-data note schedule; **(2)** replace per-note `evaluate()` with **one
batched `CollectionProducer` per pattern-layer per tick** (the proven Phase-3
fix), writing a few seconds ahead into a rolling pattern-audio buffer that a3
drains. a3 is already done — point it at PDSL `Block`s.

```
     a1  SCHEDULE-AHEAD              a2  BATCHED SYNTHESIS            a3  COMPILED PLAYBACK
     (existing, just decoupled)      (BatchedPatternRenderer)         (already works today)
     horizon: minutes                horizon: ~seconds                horizon: now
     ┌───────────────────────┐       ┌────────────────────────────┐  ┌──────────────────────────┐
     │ PatternSystemManager / │      │ For the window of notes due │  │ BufferedOutputScheduler   │
     │ PatternLayerManager    │      │ in the next few buffers,     │  │ pumps per-frame loop:     │
     │  .sumInternal() FIRST  │      │ gather into [N] tensors:     │  │                           │
     │  HALF only:            │      │  offsets, pitch-ratios,      │  │  MixdownManager / EfxMgr  │
     │   frame→measure,       │ note │  ADSR(vol), ADSR(filter),    │  │  as PDSL Blocks           │
     │   overlapping reps/    │ sched│  automation levels           │aud│  (per-channel split via   │
     │   elements,            │─────▶│ then ONE evaluate():         │io │   `for each channel`)     │
     │   getNoteDestinations  │ ⟿    │  resample → vol env →        │⟿ │  consume ready samples,   │
     │                        │ ring │  filter env → reduce-sum     │rin│  write to output line     │
     │  EMITS: note schedule  │      │ → rolling pattern-audio buf  │g  │                           │
     │  (RenderedNoteAudio    │      │                              │  │  pacing ◀ gap / degraded  │
     │   descriptors, NO eval)│      │ 1 DISPATCH per layer/tick ✓  │  │  back-pressure to a2      │
     └───────────┬────────────┘      └─────────────┬──────────────┘  └────────────┬─────────────┘
                 │ runs minutes ahead              │ runs seconds ahead            │ real-time
                 ▼                                 ▼                               ▼
         [ schedule ring ] ───────────────▶ [ pattern-audio ring ] ─────────────▶ [ output line ]
              (cheap, on a worker)          (PatternAudioBuffer made rolling;       (existing pump)
                                             written by a worker K buffers ahead)

   MAPPING TO EXISTING PARTS (no new modules; reuse, don't reinvent):
     a1 → PatternLayerManager.sumInternal scheduling half, emitting RenderedNoteAudio descriptors
          (offsetArg + producerFactory already separate "where/what" from "evaluate").
     a2 → BatchedPatternRenderer (the enableBatched path) + Parameterized{Volume,Filter}Envelope
          refactored to pure Producers so the chain FUSES into one kernel; padded-row FIR
          (zero MultiOrderFilter changes); reduce to [NOTE_SIZE] per layer/tick.
     a3 → MixdownManager / EfxManager via the merged PDSL DSP substrate, pumped by
          BufferedOutputScheduler (which already exposes the gap/back-pressure signal).
     seams → make PatternAudioBuffer a ROLLING (ring/double) buffer; run a2 on a worker that
             stays K buffers ahead of a3; let a1 pre-fill the schedule ring far ahead.

   WHY THIS REACHES DIAGRAM A:
     • a1/a2/a3 now have independent clocks and grains, joined by two rings — A's exact shape.
     • the 278 ms collapses: N-per-note dispatches → 1-per-layer dispatch (benchmarked 100–1500×).
     • a2's horizon hides its remaining cost behind playback; a3 only ever sees ready samples.

   THE ONE GENUINELY-OPEN DESIGN QUESTION (carried from PATTERN_SYSTEM_PHASE3_DESIGN):
     variable note scheduling as a STATIC graph — notes differ in start/length/pitch, but a
     batched kernel wants fixed shapes. Candidates: pad-to-max + mask; tile by pitch-class for
     resample caching; fixed-shape note-buckets per layer. This is the crux of a2, not a3.
```

---

## How to read the three together

| | Layers | Clocks | Seams (decoupling buffers) | a2 dispatch | Limiting factor |
|--|--------|--------|----------------------------|-------------|-----------------|
| **A (ideal)** | a1 \| a2 \| a3 distinct | 3 independent | 2 rings | 1 / layer / tick | a3 vs wall clock only |
| **B (today)** | a1+a2 **fused**, a3 separate | effectively 1 (serialized) | 0 (output only) | **N / tick** | the fused setup box (278 ms) |
| **C (reachable)** | a1 \| a2 \| a3 distinct | 3 independent | 2 rings | 1 / layer / tick | a2 variable-note batching design |

The leap from **B → C** is not a tuning pass; it is two structural moves —
*split a1 from a2*, and *batch a2 into one dispatch* — plus wiring the seams as
rolling buffers and pointing a3 at the PDSL `Block`s. Every named part already
exists on master; the redesign is about re-shaping how they connect, which is
exactly what the `CollectionProducer` single-`evaluate()` model has been
resisting.
