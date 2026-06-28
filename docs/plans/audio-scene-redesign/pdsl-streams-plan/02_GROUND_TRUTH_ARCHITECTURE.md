# 02 — Ground-Truth Architecture (what the code does *today*)

> Every claim here was re-derived from source in the current session, with `file:symbol`
> receipts. Where a sub-question still needs a code-read or a measurement, it is marked
> **OPEN** and routed to 01 (ledger) or 05 (migration Phase 1). Nothing here is taken from the
> older planning docs.

## 1. The two render paths

`AudioSceneRealtimeRunner` (`studio/compose/.../AudioSceneRealtimeRunner.java`) builds a
`TemporalCellular` and chooses one of two strategies (`:136`):

- **PDSL path** — `createPdsl(...)` (`:292`). The cutover target. a3 mixdown is a compiled PDSL
  model; a2 runs on a producer thread (`PatternRenderStream`). This is the path the objective
  targets.
- **CellList path** — the legacy hand-wired mixdown (still the parity reference / source of
  truth for the released sound).

This plan is about the PDSL path.

## 2. a3 (mixdown) — the hot path IS honestly mixdown-only ✔

`createPdsl`'s `tick()` (`AudioSceneRealtimeRunner.java:405-439`) contains, per buffer:

1. reset `bufferFrameIndex` (`:411`);
2. `automationRefresh` — re-evaluate clock-driven gene values (cutoffs, volume, sends) into the
   model's argument slots (`:415`);
3. **the only per-buffer compute:** `slot = renderStream.awaitSlot(); compiled.forward(slot);
   renderStream.release();` (`:420-424`);
4. stream `masterOutput` to both writers frame-by-frame (`:427`);
5. advance the clock by one buffer and the playback frame (`:435`, `:438`).

There is **no pattern preparation in the tick.** This half of the decoupling is real: the a3
hot path does not synthesize notes. Good — keep it.

> Caveat for the acceptance gate (G2): "no render in the tick" is currently a property of how
> the code is *written*, not something *asserted*. The plan adds a counter that fails if any
> synthesis dispatch occurs on the consumer thread during steady state.

## 3. a2 (pattern render) — decoupled onto a thread, but **per-buffer-window**, not per-element

`PatternRenderStream` (`studio/compose/.../PatternRenderStream.java`) is the a2 producer:

- `produceLoop()` (`:158-182`): for each ring slot, sets `renderFrame[0] = writeIndex *
  bufferSize`, calls **`renderOp.run()`**, then copies `workingInput` into the slot. A
  two-semaphore bounded ring gives back-pressure / under-run handling. The producer thread is
  safe because Metal serializes GPU encoding through one command runner (per
  `MetalDataContext`, cited in the class javadoc).
- `renderOp` is built in `createPdsl` (`AudioSceneRealtimeRunner.java:354-358`) as: for each
  `PatternAudioBuffer renderCell` in `scene.getRenderCells()`, add `renderCell.prepareBatch()`.

`PatternAudioBuffer.prepareBatch()`
(`studio/music/.../pattern/PatternAudioBuffer.java:172-188`) returns an op that does:

```java
outputBuffer.clear();
sumOp.run();   // sumOp = patterns.sum(batchContext, channel, currentFrame, bufferSize)
```

i.e. **for each buffer window it clears a one-buffer output and re-sums every note overlapping
`[currentFrame, currentFrame + bufferSize)`** (`prepareBatch` javadoc + body, `:155-188`).

### The decisive finding (firsthand, refined)

The "ring decoupling" is **thread-decoupling.** The *unit of a2 work is a buffer window*: each
window is assembled once (the producer thread runs `renderOp` once per buffer index — it does
not re-assemble a given buffer), and assembling a window means iterating **every active note**
and contributing it to that window's output. Two distinct costs live inside that assembly, and
they behave very differently — distinguishing them is the whole game:

1. **Per-note synthesis** (resample / envelope / filter) — **render-once, NOT per-window.**
   Confirmed firsthand by tracing `PatternFeatures.renderNotes`
   (`studio/music/.../pattern/PatternFeatures.java:262-343`): a note's audio is synthesized at
   most once (a continuing note misses the cache on its first continuation window, is
   synthesized via `note.getProducer(-1).evaluate()` at `:298-299`, copied into a standalone
   `PackedCollection` and `cache.put(noteStart, …)` at `:312`); every later window is a cache
   **hit** (`:276-284`). `NoteAudioCache.evictBefore` removes only *ended* notes
   (`NoteAudioCache.java:102-116`), so a continuing note survives. The cache is active in the
   streaming path (`effectiveCache = frameCount < ctx.getFrames() ? cache : null`,
   `PatternLayerManager.sumInternal`).
2. **Per-note placement / sum** — **per-active-note, per-window, dispatched from a Java loop.**
   Even on a cache hit, `renderNotes` calls `sumToDestination(...)` inside `notes.forEach(...)`
   (`:262-343`), and `sumToDestination` (`:413-441`) issues a GPU/native
   `AudioProcessingUtils.getSum().sum(destination.range(…), audio.range(…))` op **per note**.
   So each buffer window dispatches **O(active notes)** per-note sum operations from a Java
   loop. On the densest curated scene (~1154 elements) this is the **"Java orchestrating
   per-element kernel dispatches in a loop"** anti-pattern the project's Fundamental Rule
   forbids — and it is the prime suspect for the a2 wall.

So the owner's hypothesis ("we are rendering notes more often than they are used in some
hard-to-detect way") is **right in spirit but precise in mechanism**: it is not the *synthesis*
that repeats per window — it is the *placement* (a per-note GPU dispatch for every active note,
every window). And the older docs' "5× needs a kernel redesign for per-row source lengths /
in-kernel gather" is the **wrong diagnosis**: the cost is not the synthesis kernel's shape, it
is the count of per-note placement dispatches the Java assembly loop issues.

> This reframes the fix: assemble each window's mix as **one** batched operation (a single
> scatter-add of all active notes' cached audio into the buffer), instead of a Java `forEach` of
> per-note sums — i.e. *more GPU parallelism by eliminating per-note Java dispatch*, which is
> exactly the kind of gain the objective asks for. The durable home for this is the PDSL stream
> construct (03): a2 becomes a stream that produces the assembled per-channel buffer, each
> element scatter-placed once.

### MEASURED (2026-06-27) — a2 is real per-window work, but it is NOT the tick bottleneck

The structural prediction above ("placement is the suspected a2 wall") was *half right and
half wrong*, and the measurement ([04 §0](04_FEASIBILITY_GATE.md), `PdslHotPathBreakdownTest`)
settles it:

- **Right:** a2's per-buffer placement is per-active-note (perNote 7–10 ms on the dense scene);
  synthesis is render-once (cacheMisses 5–12 vs hits 221–278). The structure is as described.
- **Wrong as a *tick* bottleneck:** a2 runs on its own thread and **the a3 tick never waits on
  it** — measured `hotAwait ≈ 0.01 ms`. a2 (16–35 ms) stays hidden behind the a3 forward. So the
  per-window placement cost, while real, does **not** gate the real-time tick.
- **The tick is the a3 mixdown forward** (`compiled.forward`): 36.9 ms @4096, 57.5 ms @8192 —
  the whole budget. It scales sub-linearly with frames (fixed dispatch overhead).

So the a2 placement batching (`buildScatterAdd`, 03 §4) is a *secondary* optimization (a2
headroom), and the **primary 5× lever is the a3 mixdown-forward dispatch overhead**. This is the
measured pivot; build on it, not on the older "a2-bound" framing. The Prime Directive held: the
fix target was set by measurement, not by the prior docs' (false) causal story.

Call chain (firsthand):

```
PatternAudioBuffer.prepareBatch (clear + sum)
  -> PatternSystemManager.sum
    -> PatternLayerManager.sum (evict/clear cache) / sumInternal (effectiveCache gate)
      -> PatternFeatures.render -> renderNotes (cache hit=sumToDestination; miss=evaluate+put+sum)
        -> BatchedPatternLayerRenderer.render (batchNow [noteStart>=startFrame] vs perNote)
          -> BatchedPatternRenderer (synthesis kernels, start-window batched path)
        -> NoteAudioCache (key=note start offset; evictBefore removes ended notes only)
        -> sumToDestination -> AudioProcessingUtils.getSum().sum(...)  [per-note placement op]
```

## 4. a1 (pattern-element creation) — where the element set comes from

`PatternLayerManager` (`studio/music/.../pattern/PatternLayerManager.java`) owns layer
generation (`sumInternal`, `enableBatched`, `cachePersist`, the `NoteAudioCache`). Live genome
swap goes through `assignGenome` / `refresh` (per prior notes — **OPEN**, to be re-confirmed in
Phase 1; the relevant fact for the design is that a1 must be able to swap the element set
without tearing down a2/a3 rings).

## 5. The PDSL mixdown model (a3 DSP) — what already exists

- The model is parsed/compiled from `engine/ml/src/main/resources/pdsl/audio/*.pdsl`
  (`mixdown_master`, `mixdown_master_wet`) via `PdslLoader` and run with `CompiledModel.forward`
  (`createPdsl:362-378`).
- Producer-valued args are frozen at build; time-varying automation is pushed per buffer via
  `MixdownManagerPdslAdapter.automationRefresh` (`:373`). Per-buffer (not per-frame) automation
  granularity is a known approximation.
- Stereo today is **dual-mono**: one master is rendered and streamed to both writers
  (`createPdsl:324-329, 383-387`). True stereo is an explicit gap (objective constraint #1).

> The detailed PDSL substrate map (interpreter, `for each channel` vectorization, where a
> `stream` construct attaches) is produced by the grounding investigation and written up in
> [03_PDSL_STREAMS_DESIGN.md](03_PDSL_STREAMS_DESIGN.md).

## 6. Summary table — decoupling status by layer

| Layer | Decoupled from a3 clock? | Synthesis render-once? | Placement once? | Receipt |
|---|---|---|---|---|
| a3 mixdown | n/a (it *is* the clock) | n/a | n/a | `AudioSceneRealtimeRunner.java:405-439` |
| a2 render | **Yes** (own thread + ring) | **Yes** (cache) | **No** — per-active-note GPU sum per window | `PatternFeatures.java:262-343,413-441`, `NoteAudioCache.java:102-116` |
| a1 creation | partially (setup / refresh) | n/a (cheap) | n/a | `PatternLayerManager` — Phase 1 confirm |

The gap between the objective and reality is concentrated in one cell: **a2 placement is not
once** — every active note is re-placed into every window via a per-note Java-dispatched GPU
sum. That per-window O(active-notes) dispatch fan-out is what the PDSL stream construct must
collapse into one batched assembly, *by construction* (pending the Phase 1 profile that confirms
placement is the dominant term).
