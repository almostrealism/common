# PDSL Run-Ahead Streams — a Language Idiom for Independent Computations

> **Status: idea, not scheduled.** Extracted 2026-07-09 from the audio-scene-redesign
> planning set (the `pdsl-streams-plan/` subfolder, since removed — full text in git
> history) because the capability generalizes beyond audio. The audio effort shipped
> the *hand-built* form of this idea (`PatternRenderStream` in `studio/compose`); this
> document preserves the language-level design for whenever PDSL platform work is
> prioritized.

## The abstraction

A PDSL program today describes **one** forward pass compiled to a `CompiledModel`
whose `forward()` is evaluated once per tick. There is no notion of one computation
*running ahead of* another.

The idiom adds exactly that: a computation edge may be marked **buffered/run-ahead**.
The wrapped upstream computation runs ahead of its consumer on its own cadence, its
output deposited in a **runtime-owned ring**; the consumer takes an already-produced
slot and never blocks on it. Exactly one computation is the **sink**, bound to the
real-time clock. The invariant *the sink is the only clock-bound layer; upstreams run
ahead and never block it* becomes a property the construct **guarantees by
construction**, rather than something hand-built and re-verified per application.

## Surface (owner steer: smallest thing that works)

The owner is skeptical of an elaborate new top-level `stream`/`realtime`/`ahead..by`
sub-language — a layer already "runs ahead" of the layers below it, so the dependency
structure PDSL needs is already present in composition. Preferred shape: **one marker
on an existing composition edge**:

```
// (A) PREFERRED — wrap one sub-computation as buffered & run-ahead, ring depth 8:
out = mixdown_master_wet( stream(rendered, ahead: 8), ... )   // or use_stream(rendered, 8)
```

```
// (B) FALLBACK — explicit declarations, only if (A) cannot express the leads cleanly:
stream rendered = render_ahead(patterns)
realtime out
ahead rendered by 8 buffers
```

Semantics are identical either way; bias toward (A) — one wrapper that any edge can
carry keeps the language clearer rather than heavier.

## What the runtime owns

Everything the audio effort hand-wrote in `AudioSceneRealtimeRunner.createPdsl` +
`PatternRenderStream` — the producer thread, the bounded ring, back-pressure, the
prefill policy — becomes the runtime's implementation of the marker. The shipped
prototype is the receipt that the runtime model works; the construct changes *how a
computation is driven*, not what it computes.

## Cross-cutting design decisions (carried from the audio effort)

- **Scheduling under Metal serialization.** Streams run *ahead in time*, not in
  parallel on the GPU: the Metal command runner serializes encoding, so concurrency is
  one stream's host orchestration overlapping another's GPU dispatch. Do not design as
  if N streams encode N kernels concurrently.
- **Cache identity keyed on content, not JVM state.** Any render-once/cache semantics
  attached to a stream must key on content (sources, durations, shape), never on
  memory location or a mutable counter.
- **Ring memory lifetime.** Ring slots and cached stream output are owned by the
  runtime for their declared lifetime and freed on eviction — never views over
  transient copies. `PackedCollection` is `Destroyable`; ownership is explicit.
- **Live redefinition.** Swapping an upstream definition bumps an epoch; unchanged
  cached content survives, downstream rings drain prior-epoch buffers. No pipeline
  teardown.
- **Time-varying parameters** flow through per-buffer-refreshed argument slots (the
  `automationRefresh` pattern), so live changes engage without a rebuild.

## Open questions (deferred with the idea)

- Exact surface syntax, and how a stream body distinguishes "consume the upstream's
  produced buffer" from "call a layer".
- Ring sizing for long-lived upstream outputs; back-pressure / degraded mode when a
  producer cannot hold its lead.
- Whether cheap schedule-like upstreams (the audio a1) are streams at all, or stay
  host-side schedulers feeding the runtime.
