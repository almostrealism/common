# 03 — PDSL Run-Ahead Streams: Design

> The north star. The owner's directive was explicit: do not "add a feature" to PDSL in the
> abstract — **use the PDSL language itself to express the abstraction we are building an
> implementation for**, so the concept stays the guide throughout. This document does that: it
> defines the `stream` construct in the *real* PDSL grammar (the grammar verified firsthand from
> `engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl` and `PdslInterpreter`), shows
> the a1/a2/a3 pipeline expressed in it, and specifies the runtime that makes the defining
> invariant true **by construction**.

## 1. The abstraction

A PDSL program today describes **one** forward pass: `layer`/`state`/`model`/`config`/`data`
declarations (`PdslInterpreter` keeps these in `layerDefinitions`, `stateBlockDefinitions`,
`modelDefinitions`, … `:137-149`), compiled to a `CompiledModel` whose `forward()` is evaluated
once per tick. There is no notion of *time* beyond the single pass, and no notion of one
computation *running ahead of* another.

The extension adds exactly that: a program may declare multiple named **streams**, each a
separately-defined computation, with **dependencies** between them (one stream's body references
another), and a **lead** relationship. Exactly one stream is the **sink** — bound to the
real-time clock. Every upstream stream runs **ahead** on its own cadence, decoupled by a ring
the **runtime** owns, so the consumer's forward pass always finds its input already produced. The
invariant *a3 is the only clock-bound layer; a1/a2 run ahead and never block a3* becomes a
property the construct **guarantees**, not something re-implemented and re-verified per attempt
(it is currently hand-built in `PatternRenderStream` and unverified — see 01/02).

## 2. The construct, in real PDSL grammar

PDSL's existing top-level declarations are `state NAME { … }`, `layer NAME(params) -> [shape] {
body }`, `model`, `config`, `data`. **`stream` is a new top-level declaration** parsed into
`PdslNode.Program` alongside them. Illustrative — the exact surface syntax is part of the design
review, but it must be a *declaration of decoupling*, not hand-coded scheduling:

```
// a1: which elements exist on the timeline. Cheap; produces an element schedule, not audio.
stream patterns() -> schedule { pattern_elements() }

// a2: synthesize each element's audio ONCE and assemble it into per-channel buffers.
//     `render_ahead` is the new placement primitive (§4): one batched scatter-add of all
//     active elements' (cached) audio into the output window — NOT a per-note loop.
stream rendered(channels: int, signal_size: int) -> [channels, signal_size] = render_ahead(patterns)

// a3: the existing mixdown model, now consuming a stream instead of a hand-fed buffer.
stream out(channels: int, signal_size: int) -> [1, signal_size] = mixdown_master_wet(rendered, ...)

// the temporal contract — declared, not coded:
realtime out                 // the ONLY clock-bound stream (the sink)
ahead rendered by 8 buffers  // ring depth the runtime maintains between a2 and a3
ahead patterns by 4 measures // a1 runs minutes ahead; swap-able live
```

Everything the prior attempt hand-wrote in `AudioSceneRealtimeRunner.createPdsl` +
`PatternRenderStream` — the producer thread, the bounded ring, the back-pressure, "render once,
ahead of need" — becomes the **runtime's** implementation of `ahead … by …`. The `out` stream's
body is *exactly today's* `mixdown_master_wet` layer (which already exists and is parity-tuned);
the construct changes *how it is driven*, not the DSP.

## 3. Where it attaches in the codebase (receipts)

| Concern | Where | Receipt |
|---|---|---|
| Grammar / AST | new declaration node in `PdslNode.Program` | `PdslInterpreter` reads `program`'s typed declaration lists (`:137-149`) |
| Resolution | `PdslInterpreter` builds a **stream graph** (named computations + lead edges) instead of one `CompiledModel` | `callUserLayer` (`:990`), `evaluateFunctionCall` (`:796`) are the layer-resolution seam |
| Each stream's body | compiles to a `CompiledModel` / `Block` exactly as today | `createPdsl` already does `loader.buildLayer(...) -> CompiledModel.forward` (`AudioSceneRealtimeRunner.java:362-378`) |
| Runtime scheduler | generalize `PatternRenderStream` into a reusable **stream runtime**: N producer threads (or a cooperative scheduler) feeding rings, one sink on the playback clock | `PatternRenderStream.java` (the working prototype) + `BufferedOutputScheduler` (the existing sink pump) |
| Driving the sink | the existing `TemporalCellular` tick consumes the sink ring | `AudioSceneRealtimeRunner.createPdsl` tick (`:405-439`) |

The construct is therefore **not greenfield**: it is the promotion of a proven hand-built
pattern (`PatternRenderStream`) to a declared, reusable PDSL capability, plus a parser/interpreter
extension. That keeps the risk bounded and the migration incremental (05 / OQ2).

## 4. The `render_ahead` primitive — the actual performance fix

This is where the design earns the 5× the prior approach could not. Per
[02](02_GROUND_TRUTH_ARCHITECTURE.md), the a2 wall is **per-note placement**: a Java
`notes.forEach` dispatching one `AudioProcessingUtils.getSum().sum(...)` op **per active note,
per window**. `render_ahead` replaces that with two batched ops per produced buffer:

1. **Synthesize-new (render-once):** the elements whose audio is not yet cached (newly-starting,
   plus a continuing element's first encounter) are synthesized in **one batched dispatch** and
   cached by element identity. This is the existing batched kernel + cache, made structural —
   "render each element once" is the stream's caching/lifetime semantics, not a bolted-on
   `NoteAudioCache` with melodic-vs-percussion special-casing (R3/R6).
2. **Scatter-place-all (batched placement):** all elements active in the buffer window are placed
   into the output in **one** scatter-add kernel over `[elements, overlap]` — GPU parallelism
   over elements — instead of N per-note Java-dispatched sums. This is the operation the current
   path does serially; collapsing it to one batched op is the "more GPU parallelism, not less
   work" gain the objective requires (constraint #3).

The per-buffer a2 GPU cost becomes **O(1) dispatches** (one synth-batch + one place-batch),
parallel over elements, regardless of how many notes are active — which is what should make a2
stop being the bottleneck. **This is a hypothesis with a structural argument, not a measured
fact; Phase 1 (04/05) proves or refutes it before the construct is built out.**

## 5. Cross-cutting design decisions

- **Scheduling under Metal serialization (R1).** Streams run *ahead in time*, not in parallel on
  the GPU. The Metal command runner serializes GPU encoding through one executor
  (`MetalDataContext`), so concurrency is between one stream's Java orchestration and another's
  GPU dispatch. The runtime drives all GPU work through the single runner; "ahead" buys latency
  hiding, and `render_ahead`'s batching buys throughput. The design must **not** assume N streams
  encode N kernels concurrently.
- **Render-once semantics keyed on content, not JVM state (R6).** An element's cache identity is
  derived from its content (sources, durations, shape) — never from runtime memory location or a
  mutable count. This is the lesson that wrecked the aggregation effort; it is a hard constraint
  on the stream cache key.
- **Ring memory lifetime (R3).** Cached element audio and ring slots are **owned by the runtime
  / stream** for their declared lifetime and freed on eviction — never views over transient
  `fit()` copies (the prior percussion dangling-pointer crash). `PackedCollection` is
  `Destroyable`; ownership is explicit.
- **Live genome swap (R2).** Swapping the `patterns` stream definition bumps an epoch; unchanged
  elements keep their cached audio, changed/removed elements are evicted; downstream rings drain
  prior-epoch buffers (already-valid output for their time window). No pipeline teardown.
- **Stereo as a sink shape (R9).** True stereo is the `out` sink carrying two channels in one
  forward (per-channel pan in the mixdown), not the pipeline run twice. The `rendered` clips stay
  source-native; panning happens in `out`.
- **Automation (R5).** Time-varying gene values flow through per-buffer-refreshed argument slots
  (as `createPdsl`'s `automationRefresh` already does), so live-swap and sweeps engage without a
  rebuild.

## 6. What this design explicitly does NOT do

- It does not revive the hand-built CellList DSP alternatives (channel-scoped / flat-buffer /
  hybrid-graph). PDSL `for each channel` + the Block model already subsume them (this is a
  design judgment, flagged `unverified` in 01 — but it is the agreed direction, not a perf
  claim).
- It does not adopt the prior "5× needs per-row-independent source lengths / in-kernel input
  generation" framing as a requirement. That framing is `unverified` (01) and, per 02, likely
  mis-attributes a placement cost to a synthesis-kernel shape. If Phase 1 shows synthesis-kernel
  shape genuinely matters after placement is batched, it re-enters scope *then*, with a receipt.
- It does not grow the buffer size to hit the number (constraint #2).

## 7. Open design questions (carried into the design review, not assumed)

- Exact surface syntax + semantics of `stream` / `ahead … by …` / `realtime`, and how a stream
  body distinguishes "consume the upstream's produced buffer" from "call a layer."
- The `render_ahead` scatter-add kernel: shape, how the per-element overlap offsets are supplied
  to the GPU without per-note host marshalling, and how it composes with `for each channel`.
- Ring sizing for long elements (a multi-second Rise/loop): does the assembled ring hold the
  element's full audio, or is the element re-sliced per slot (one batched op either way)?
- Back-pressure / degraded mode when a producer cannot hold its lead (under-run policy).
- Whether `patterns` (a1) is best modeled as a stream at all, or stays a cheap Java scheduler
  feeding the runtime (its output is a schedule, not a tensor).
