# Argument Aggregation — `result == 0.0` Root-Cause Investigation

Investigation state as of 2026-06-23 (max-effort follow-up session). Companion to
`AGGREGATION_METAL_HANDOFF.md` (objective, guardrails, prior-agent mistakes). This
document exists so the investigation can be resumed after a restart. Read it
skeptically: each claim below is tagged **[VERIFIED]**, **[LEAD]** (strong but
obtained partly by a method to re-confirm), **[INFERENCE]**, or **[RULED OUT]**.

## Objective (unchanged)

Make argument aggregation a reliable, system-wide, on-by-default feature that is
correct on Metal as well as native. Narrowing scope to dodge the problem is
rejected (see handoff). The current failures are `result == 0.0` on the CI macOS
jobs with aggregation default-on.

## Minimal reproducer (use this — fast, deterministic, no full suite)

`ExpressionDelegationTest.assignmentFromProduct` (module `engine/utils`):
`r = pack(0.0)`; `l.add(a(1, p(r), cp(a).multiply(p(b))))`; `op.run()`;
`assertEquals(2.0, r)`. `a=1, b=2`, so `r` should become `2.0`; it stays `0.0`.

```
mcp__ar-test-runner__start_test_run
  module: engine/utils
  test_methods: [{class: ExpressionDelegationTest, method: assignmentFromProduct}]
  # AR_HARDWARE_DRIVER left UNSET -> "*" (CI config). ~4s. Fails: 0.0 != 2.0.
```

## What is established [VERIFIED]

1. **Reproduces in ISOLATION on the default config** (driver unset → `*`): the
   exact CI failure `assignmentFromProduct:74 0.0 != 2.0`, single test class. →
   the prior session's "load-dependent / passes in isolation" claim is **false**;
   it is deterministic.
2. **Aggregation is the direct cause:** `AR_HARDWARE_ARGUMENT_AGGREGATION=disabled`
   → 3/3 pass; default (on) → fails.
3. **The destination `r` is folded into the aggregate.** `AR_DUMP_ARGVARS=1` shows
   the assignment op `operations_0` has a single argument = the aggregate buffer,
   `memLength=3` — i.e. inputs `a`, `b` **and** the destination `r` are all folded
   into one aggregate. Identical structure on native and on `*`.
4. **Copy-out is NOT skipped here.** `AcceleratedOperation.run()` calls
   `apply(null, …)` (AcceleratedOperation.java:336–342), so `output == null` →
   `aggregateCopyOut = aggregating && (output == null || strict)` is **true**
   (apply ~557–559). The aggregate copy-out *runs*. (So the "explicit output →
   skip copy-out" branch is not what breaks this case.)
5. **It is `*`/Metal-memory specific.** Forced `-DAR_HARDWARE_DRIVER=native`
   **passes** (`r=2.0`); default `*` **fails** (`r=0.0`).
6. **Suspect surface:** the whole reintroduction is 3 commits on top of
   `e642e64ad` (= tip of the "basically working" baseline
   `feature/language-operations-cleanup`): `9732295ff`, `a6c5213ac`, `13ce711c5`;
   8 source files (`MemoryDataArgumentMap`, `AcceleratedOperation`,
   `AcceleratedComputationOperation`, `ProcessArgumentMap`, `Assignment`,
   `DestinationEvaluable`, `ScopeInstructionsManager`, `CollectionProviderProducer`).

## What it looks like [LEAD — re-confirm via ar-profile-analyzer]

Under `*`, the failing op compiled to a **JNI / CPU kernel** (only
`jni_instruction_set_0.c` was dumped by `AR_INSTRUCTION_SET_MONITORING=always`; no
`.metal`), and the runtime log shows `Hardware[JNI]: Enabling shared memory via
MetalMemoryProvider`. The kernel writes the result **into the aggregate slice**
correctly: `_v1[off+2] = _v1[off] * _v1[off+1]` (`_v1` = the single aggregate
buffer = `argArr[0]`).

This was read from the dumped `.c` directly, which is the lazy anti-pattern. **Re-
confirm through `ar-profile-analyzer`**: add a throwaway capture test
(`initKernelMetrics(new OperationProfileNode("assignAgg"))` … `profile.save(...)`),
then `load_profile` → `search_operations` → `get_source`.

## Root cause [PROVEN — 2026-06-23, via a gated diagnostic in apply(), since reverted]

It is an **unwind-ordering bug** between two independent copy systems:
- **Compile-time aggregation** (`MemoryDataArgumentMap`): copy-in originals→aggregate,
  copy-out aggregate→originals.
- **Runtime cross-provider replacement** (`MemoryReplacementManager`, via
  `process.getPrepare()`/`getPostprocess()`): under `*` the aggregate buffer is not on
  the kernel's target provider, so it is reserved into a kernel-provider **temp**
  (prepare: aggregate→temp; kernel writes temp; postprocess: temp→aggregate).

The reservation postprocess **does** copy the kernel's result back into the aggregate —
but in `apply()` the de-aggregation copy-out (`argumentMap.getPostprocessData()`,
aggregate→originals) ran **before** the reservation postprocess
(`process.getPostprocess()`, temp→aggregate). So the de-aggregation read the aggregate
while it was still stale → result lost. The copy-*in* side was correctly ordered
(aggregation-in, then reservation-in); the copy-*out* side must unwind in **reverse**
(reservation-out, then aggregation-out).

Proof (diagnostic dumps in `AcceleratedOperation.apply`, taken *between* the two
copy-out steps):
- after copy-in: `agg = [1.0, 2.0, 0.0]` (a, b, r) — copy-in correct.
- after kernel, before the reservation postprocess: `agg = [1.0, 2.0, 0.0]`
  **unchanged**, and `aggMem` ≠ `in[0]mem` (the kernel wrote the reservation temp, a
  *different* buffer). The reservation postprocess that refreshes the aggregate had
  not run yet at this point — and the de-aggregation ran before it.

Native passes because `processing == false` there (single provider, no reservation —
the kernel writes the aggregate directly, so there is nothing to order).

**This is NOT a general cross-provider consistency bug**, and NOT a "no Process-tree
position" problem (that is a *separate* concern the reuse path handles via
`ProcessArgumentMap.putDirect`/`directSubstitutions`). Ordinary arguments round-trip
correctly under `*`; only the aggregate's de-aggregation was sequenced before the
reservation refreshed it.

## Ruled out / corrected [RULED OUT]

- **"Load-dependent, passes in isolation"** (prior session) — false; deterministic.
- **"Backend-agnostic; native should also fail"** (this session's earlier wrong
  prediction) — false; native passes. Always test, don't assert.
- **"Just exclude the output from aggregation"** (this session's earlier proposed
  fix) — rejected. It explains neither mechanism, and its premise ("the output
  *variable* is special") is unjustified: copy-out is keyed on the runtime output
  *argument*, and here it isn't skipped at all (output == null → it runs).
- **Catastrophic systemic Metal Semaphore disrespect (Direction A)** — ruled out:
  the kernel ran fine and the semaphore was awaited (`semNull=false`); the result
  was simply never copied back. (If future evidence ever shows the host reading a
  buffer before a *GPU* kernel's write completes, that supersedes everything — drop
  aggregation and fix ordering first, per handoff.)
- **"The aggregate's device-memory (`deviceMemory`/`mainRam`) allocation is the
  cause"** — disproven: changing `createAggregatedInput` to allocate on the default
  (host-coherent) provider still failed under `*`. The problem is the reservation
  copy-back, not the aggregate's storage location.

## Fix applied + verified [2026-06-23]

**Fix:** in `AcceleratedOperation.apply()`, run the reservation postprocess
(`process.getPostprocess()`, temp→aggregate) **before** the aggregation de-aggregation
(`argumentMap.getPostprocessData()`, aggregate→originals) — i.e. unwind the copy-out
in reverse order of the copy-in. One-place reorder + comment; **zero added copies**.
Native is unaffected (`processing == false` there). Diff: `AcceleratedOperation.java`,
+10/-4.

**Verified (all under `*` unless noted):**
- `ExpressionDelegationTest.assignmentFromProduct` — pass (was `0.0 != 2.0`).
- engine/utils `SwitchTest`+`ExpressionDelegationTest`+`PackedCollectionMapTests` — 24/0/0.
- engine/utils `SoftmaxTests`+`NormTests`+`BackPropagationTests` — 46/0/0 (6 skipped).
- engine/audio target tests `BatchedChainSeamTest`+`BatchedSssFromScalarsTest` — 2/0/0.
- native regression (forced `native`): the engine/utils trio — 24/0/0.
- `checkstyle` + `code_policy` — clean.

**Still open:**
- **Comprehensive CI run** (owner commits/pushes) — local `*` mirrors the macOS job but
  CI covers both jobs and the full grouped suite.
- **Perf follow-up (optional, the owner's "option 3"):** allocate the aggregate on the
  kernel target provider (`getKernelMemoryProvider`) so `MemoryReplacementManager` does
  not reserve/copy it at all (no temp round-trip). `createAggregatedInput` currently uses
  `getComputeContext().getDataContext().deviceMemory()`, which under `*` does not match
  the kernel target provider, so the reservation happens. This is a performance
  optimization, not a correctness requirement; the ordering fix is correct regardless.

## Methodology / guardrails (for a restart)

- **Reproduce on CI config: `AR_HARDWARE_DRIVER` UNSET (→ `*`). Never force `mtl`.**
  Forced `native`/`mtl` are diagnostics only, never evidence about CI.
- **Read generated code via `ar-profile-analyzer`** (`load_profile` →
  `search_operations` → `get_source`), not `cat`/`grep`/`Read`. Capture profiles
  with `initKernelMetrics(...)` + `OperationProfileNode.save(...)`.
- **GPU contention:** local `*`/Metal runs starve the `ar-consultant` model on the
  shared GPU — run them one at a time, keep them small, and don't call the
  consultant concurrently. Native runs are CPU (lighter).
- **Diagnostics:** `AR_DUMP_ARGVARS=1` (per-arg `ArrayVariable` structure; reaches
  stdout), `AR_INSTRUCTION_SET_MONITORING=always` (dumps kernels to
  `AR_INSTRUCTION_SET_OUTPUT_DIR`), `AR_HARDWARE_ARGUMENT_AGGREGATION=disabled`
  (toggle). `AR_TRACE_ASSIGN` / `AR_TRACE_DESTEVAL` print to a console file sink
  (`results/logs/test.out`) that the test runner does NOT capture — unreliable.
- **.m2 note:** `mvn test -pl engine/utils` (no `-am`) runs against installed jars.
  The current `~/.m2` `ar-hardware` jar was verified to contain HEAD's aggregation
  code with the default flag on. If results look anomalous, rebuild the dependency
  closure (`mvn install -DskipTests -pl engine/utils -am`) before trusting them.
