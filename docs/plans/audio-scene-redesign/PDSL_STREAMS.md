# PDSL Run-Ahead Streams — concept seed

> A seed for a from-scratch planning session, not a plan. The core idea: make **"a
> computation that runs ahead of its consumer's real-time forward pass"** a first-class PDSL
> construct, instead of hand-building it once per use. The real-time AudioScene pipeline
> (a1/a2/a3) is the first consumer, but the construct is general (any pipeline where an
> expensive producer must stay ahead of a real-time sink).
>
> Why this exists: a ~14-hour effort to reach 5× by tweaking the existing batched-render
> structure stalled at 2.34×. The blocker was structural, and "render pattern audio ahead of
> the mixdown" is a *special case* of a general PDSL capability. The remedy is to build that
> capability and migrate onto it — and to use the PDSL language itself to express the
> abstraction so it stays the north star. See `../falsification-gate/FEASIBILITY.md`.

---

## The abstraction (the north star)

A PDSL program today describes **one** forward pass — a single graph evaluated per tick. The
extension: a program may declare multiple named **streams**, each a separately-defined
computation, with declared **dependencies** between them. The runtime guarantees a producer
stream runs **far enough ahead** of its consumer that the consumer's forward pass always
finds its input already produced — the consumer never blocks on the producer.

Exactly one stream is bound to the real-time clock (the **sink**). Every upstream stream runs
ahead on its own cadence, decoupled by ring buffers the **runtime** owns.

## Sketch — express it IN PDSL, don't hide it in Java

Illustrative only (syntax is for the planning session to design); the point is that the
decoupling is *declared*, not coded by hand:

```
stream patterns()                 # a1: which elements exist on the timeline
stream rendered = render(patterns)  # a2: synthesize each element's audio
stream out      = mixdown(rendered) # a3: mix ready buffers and play

realtime out                      # the ONLY clock-bound stream (the sink)
ahead rendered by 8 buffers       # lead/ring depth the runtime must maintain
ahead patterns by N measures
```

The ring, the producer thread, the back-pressure, and "render once, ahead of need" all become
the runtime's implementation of `ahead … by …` — not bespoke code in
`AudioSceneRealtimeRunner` / `PatternRenderStream`.

## How it maps to the larger goal (a1/a2/a3)

| Layer | As a PDSL stream | Lead |
|---|---|---|
| a1 pattern-element creation | `patterns` | minutes ahead; live genome swap = swap its definition without tearing down downstream rings |
| a2 pattern-element render | `rendered = render(patterns)` | seconds ahead; fills a ring of rendered buffers |
| a3 mixdown | `out = mixdown(rendered)` | the real-time sink; per-buffer forward consumes ready output |

The defining invariant — *a3 is the only clock-bound layer; a1/a2 run ahead and never block
a3* — becomes a property the construct **guarantees by construction**, instead of something
re-implemented and re-verified per attempt. (It is currently implemented by hand and verified;
this would make it structural.)

## Why this unlocks 5× where incremental tuning did not

The 5× wall was structural, and the structure was the existing batched-render kernel. Two
overheads could not be removed in place (both measured this session):
- the kernel **shares one source-length across all rows of a dispatch**, so batching
  continuing notes bloats every row (net loss, reverted);
- inputs are **marshalled host-side** per dispatch.

Designing a2 *as* a PDSL stream lets its render kernel be built **for** the contract:
- **per-row-independent source lengths** — no shared-dimension bloat, so continuing notes
  batch cleanly;
- **in-kernel / GPU-side input generation** — no host marshalling;
- **"render each element once"** expressed as the stream's caching/lifetime semantics — not a
  bolted-on `NoteAudioCache` with melodic-vs-percussion special-casing.

These are exactly the costs the incremental effort could not eliminate; they fall out of a
clean stream design rather than being fought one at a time.

## Open questions for the planning session

- Syntax + semantics of `stream` / dependency / `ahead … by …` in PDSL.
- Scheduling: how streams run concurrently given Metal serializes GPU encoding through a
  single command-runner executor (threads feeding one runner? a cooperative scheduler?).
- Back-pressure and degraded-mode when a producer cannot hold its lead.
- Live-swap semantics: replacing a stream definition while downstream rings still hold its
  prior output.
- How `for each channel` / vectorization composes with streams.
- Whether **true stereo** is simply the sink stream carrying twice the channels in one
  forward (today's path is dual-mono) — i.e. stereo as a shape of the sink, not a separate
  mechanism.
- Migration path: stand up the stream construct, port a1/a2/a3 onto it, retire the hand-built
  `PatternRenderStream` + ring once parity holds.
