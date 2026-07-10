# Handoff — NPE in ProcessDetailsFactory after the argument-preparation chaining arc

> **Status: open defect on `master`, 2026-07-09.** Found while verifying the PDSL ring
> fixes; **provenance verified against a pristine tree** (stash round-trip: the identical
> failure occurs with and without the ring-fix changes), so it belongs to the
> operation-list subdivision / argument-preparation work merged 2026-07-08 (PR #340),
> not to the audio branch. It blocks the planned **buffer-size sweep**, which depends on
> the failing benchmark.

## Symptom and repro

`PdslHotPathBreakdownTest#hotPathBreakdown` (studio/compose; requires the curated
sample library, so **CI never runs it** — it last ran locally 2026-07-05, before the
merge) dies ~35 s in, mid-measure:

```
java.lang.NullPointerException: Cannot invoke "io.almostrealism.relation.Evaluable.async()"
        because "this.kernelArgEvaluables[i]" is null
  at org.almostrealism.hardware.ProcessDetailsFactory.construct(ProcessDetailsFactory.java:526)
  at org.almostrealism.hardware.AcceleratedOperation.getProcessDetails(AcceleratedOperation.java:499)
  at org.almostrealism.hardware.AcceleratedOperation.apply(AcceleratedOperation.java:587)
  at org.almostrealism.hardware.AcceleratedComputationEvaluable.evaluate(...)
  at org.almostrealism.collect.computations.ReshapeProducer.lambda$get$1(ReshapeProducer.java:621)
  at org.almostrealism.music.pattern.PatternFeatures.accumulateBatchedOutput(PatternFeatures.java:197)
  at org.almostrealism.music.pattern.BatchedPatternLayerRenderer.dispatchWindow(...)   <- a2 producer thread
```

`GenerateAudioFileTest` — the same a2 + PDSL mixdown pipeline, different orchestration —
**passes** on the same tree, so the failure is timing/orchestration-sensitive rather
than a deterministic argument-shape break.

## The violated invariant

`ProcessDetailsFactory.init(...)` establishes, per argument slot `i`: either
`kernelArgs[i] != null` (pre-resolved: the output slot, a producer-argument reference,
or a cached constant) or `kernelArgEvaluables[i] != null` (populated in init's second
pass, which throws `UnsupportedOperationException` rather than leave a null).
`construct(Semaphore)` then assumes exactly that: any slot without a `kernelArgs` entry
calls `kernelArgEvaluables[i].async(...)` (the NPE site, `:526`). The crash means a slot
reached `construct` with **neither** populated — a state `init` cannot produce
single-threadedly.

## Prime suspect: concurrent `init`/`construct` on a shared factory

- `init` unconditionally recreates all three arrays every call
  (`ProcessDetailsFactory.java:380-382`: `kernelArgs = new MemoryData[...]`,
  `kernelArgEvaluables = new Evaluable[...]`, …) and populates them in two passes.
  `construct` reads them, and also resets `asyncEvaluables`. **None of this is
  synchronized**, and the factory is per-`AcceleratedOperation` mutable state
  (`currentDetails` likewise).
- A second thread calling `init` between the array recreation and the second pass
  leaves the first thread's `construct` looking at a fresh, half-empty
  `kernelArgEvaluables` — exactly the observed state.
- What changed in PR #340 to create concurrent callers: argument preparation became
  **asynchronous and chained** —
  `03e24c5b3` ("Chain argument preparation on the Semaphore mechanism",
  `DependentStreamingEvaluable`, `request(args, dependsOn)`),
  `c674b9162` ("Honor dependencies in short-circuit argument evaluation" — this is the
  commit that routes **`ReshapeProducer` short-circuits**, which is the frame in the
  stack, through the chained path), and `b188c8512` ("asynchronous argument
  delivery"). Argument evaluations that used to run inline (serialized at the call
  site) can now run on other threads concurrently with other uses of the **same
  compiled operation**. The real-time runner has two dispatching threads by design
  (the a2 producer — where the NPE fires — and the a3 consumer), and compiled
  operations are deliberately shared JVM-wide in several places (instruction-set
  cache; the batched pattern renderers are shared across scenes as of `79b297c5b`).

A secondary hypothesis, if the race doesn't confirm: a `construct(dependsOn)` path
introduced by the chaining that runs against a factory whose `init` was skipped or
`reset()` (`:438` nulls `kernelArgEvaluables` wholesale) — though `reset()` nulling the
whole array would NPE on the array, not the element, so the element-level null points
back at the interleaving.

## Suggested attack

1. Reproduce: run `PdslHotPathBreakdownTest` (MCP test runner, module
   `studio/compose`; needs `/Users/Shared/Music/Samples`). Fails reliably within ~40 s.
2. Confirm the race: log thread + identity hash of the factory in `init`/`construct`
   entry/exit around the NPE, or simply synchronize `init`+`construct` on the factory
   and observe the NPE disappear. (A coarse lock may cost hot-path throughput — the
   likely real fix is making `AcceleratedProcessDetails` construction thread-confined:
   build into locals and publish once, rather than mutating shared field arrays.)
3. Bisect if needed: `b188c8512 → 03e24c5b3 → c674b9162 → dd3facb92` is the in-scope
   range; the `ReshapeProducer` frame suggests `c674b9162` first.
4. Regression guard: whatever the fix, this benchmark is curated-gated — add a small
   ungated concurrency test (two threads applying one compiled operation with
   argument evaluables) so CI catches the next one.

## Receipts

- Failing runs: `fdc487d9` (ring-fix tree), `dea1aa82` (pristine master) — identical
  stack both times.
- Passing on the same trees: `GenerateAudioFileTest` (2 tests, real render).
- Source read: `ProcessDetailsFactory.java` `:380-382` (unconditional array
  recreation), `:417-428` (second-pass population with UOE guard), `:451-` /
  `:526` (construct's assumption and the NPE site), `reset()` `:438`.
