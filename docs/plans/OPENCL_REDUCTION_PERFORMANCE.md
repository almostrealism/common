# OpenCL Reduction Performance

Investigation and status notes for the OpenCL reduction slowdown
(`MatrixMathTests.sumPowers` timeout), including the accumulator promotion
work in `Repeated` and the evidence pointing at per-dispatch data movement
as the remaining cause.

Branch: `feature/cl-memory-gc`

## Open problem

`MatrixMathTests.sumPowers` times out under `AR_HARDWARE_DRIVER=native,cl`. Root
cause is understood and is NOT dispatch/batching:

- The sum runs as **1 kernel dispatch per invocation**; raw CL dispatch is on par
  with Metal (measured with `matmulPowers`: CL ~9.6s vs Metal ~10s).
- The **reduction kernel itself** is 4–12× slower on CL than Metal, scaling with
  reduction width (dim=2 → 3.8×, dim=64 → 12.5×). Metal is flat ~0.3ms/run.

The generated CL kernel (captured, verified):

```c
__kernel void f_collectionSumComputation_23(__global float *_v15, __global float *_v16, ...) {
    _v15[((long) get_global_id(0)) * _v15Size + _v15Offset] = 0.0;
    for (int i = 0; i < 64;) {
        _v15[((long) get_global_id(0)) * _v15Size + _v15Offset]        // read
            = _v16[(((long) get_global_id(0)) * 64) + i + _v16Offset]
            + _v15[((long) get_global_id(0)) * _v15Size + _v15Offset]; // + write, every iter
        i = i + 1;
    }
}
```

Two issues, both scale-with-width:
1. The accumulator is the **global output buffer** — a global read-modify-write
   every iteration instead of a register accumulator with one final write.
2. **`(long)` index arithmetic** for a 600-element output where `int` suffices.

## Important constraint (from the owner)

This is **not** an Expression-level change and **not** in `Sum::create`. The
structure above is an entire **Scope with multiple Statements** (the zero-init
assignment + the loop body). The register-vs-global accumulator and the int-vs-long
index are Scope/Statement/codegen-level decisions. Do not approach this as an
Expression rewrite/simplification. The owner will point to the correct location.

## What is already done (committed / staged on this branch)

- **CL memory GC** — `CLMemoryProvider` now reclaims device memory via GC
  (phantom refs, like Metal/native). Committed.
- **CL host read-cache** — `getMem` serves repeated element reads from a
  whole-buffer snapshot; fixed `matmulLarge`. Committed/staged.
- **Misc CL test-cl fixes** — OpenCL `isInt64`, `OperatorPoolExhausted`
  misreporting, duplication-profile test isolation, build-validator code_policy
  structured output. Committed.
- **Profiling source capture (tooling)** — `CodeFeatures.profile(name, op)` now
  assigns the profile before compiling so generated kernel source is recorded;
  `initKernelMetrics` javadoc and `docs/internals/profiling.md` document the
  capture workflow. Staged (see `commit.txt`).

## How to inspect the kernel

Capture: `profile("name", (OperationList) op.optimize()).save(new File("results/name.xml"))`
(the profile must be active before `get()` compiles — see `docs/internals/profiling.md`).
Read: `ar-profile-analyzer` `load_profile` → `search_operations` → `get_source`
(`has_source` in listings is unreliable; call `get_source` and check
`available_source_keys`). CL-only runs need `-DAR_HARDWARE_DRIVER=native,cl`.

## Not addressed / known separate items

- `NormTests.backwardsTrainableSmallLowVariance` — flaky (FP32 precision, unseeded
  random gradient).
- `BatchedRealtimeTickTest.batchedTickByTickMatchesPerNote` — 55% batched-vs-per-note
  divergence on CI Linux OpenCL; not reproducible on Apple OpenCL (25/25 local pass).

---

## Assessment (added by a later session, 2026-07-08)

A follow-up investigation of the claims above, with the chosen path forward.

### Re-examined claims

**The `(long)` index arithmetic is not the bottleneck.** Metal emits the identical
cast — `MetalLanguageOperations.kernelIndex()` returns `((long)global_id)`
(`base/hardware/.../metal/MetalLanguageOperations.java:80`), and the javadoc on the
CL version states it exists to match Metal (unsigned `size_t` poisons signed
comparisons). Both backends compile the same Scope with the same `long` arithmetic;
Metal is flat at ~0.3ms. Narrowing to `int` would require value-range analysis
(invasive) and the evidence says it would buy nothing. Drop this half of the plan.

**The global-buffer accumulator is real, but the framing needs care.** The structure
is produced by `RepeatedProducerComputation.getScope()`
(`compute/algebra/.../RepeatedProducerComputation.java:270`): both the zero-init
statement and the loop body write through `getDestination()`, which always
references the global output `ArrayVariable`; the per-iteration re-read comes from
`AggregatedProducerComputation.getExpression()` building the current value as
`var.reference(kernelIndex * var.length())`. Metal compiles the *same* structure and
is fast — its compiler register-promotes the read-modify-write. The OpenCL compiler
does not, and arguably cannot: the buffers are unqualified `__global float*` with no
`restrict`, so every store to the output forces conservative reloads of the input.
That is consistent with the 4–12× width-scaling gap. (The `volatile __global`
qualifier was checked and ruled out: it is emitted only when
`AR_HARDWARE_MEMORY_LOCATION=heap`; the default location is `DEVICE`.)

### Chosen path: accumulator promotion in `Repeated` (single-class change)

Do NOT rework the destination plumbing in `RepeatedProducerComputation` /
`ConstantRepeatedProducerComputation` / `TraversableRepeatedProducerComputation` /
`AggregatedProducerComputation`. `getDestination` and `getExpression` bake the
output-buffer reference into the subclass contract, and changing that touches four
classes plus the delta-computation and `generate()` paths.

Instead, add an "accumulator promotion" transform to `io.almostrealism.scope.Repeated`
(`base/code`), as a sibling of the LICM pass that already lives in
`Repeated.simplify()`:

1. **Detect** non-declaration statements in the loop body of the form
   `arr[idx] = f(arr[idx], ...)` where `idx` is loop-invariant. The analysis
   machinery already exists in the class (`collectBaseVariantNames`,
   `isLoopInvariant`, `collectDestinationNames`).
2. **Rewrite** to a local scalar declaration before the loop (same
   `ExpressionAssignment(true, StaticReference, expr)` mechanism the LICM pass uses
   for its `f_licm_*` declarations), seeded from the matching pre-loop init statement
   if one exists (else from a single load of `arr[idx]`); replace the in-loop
   statement with an assignment to the local; store once after the loop.
3. **Epilogue position**: `Repeated.write()` fully controls its own rendering, so an
   epilogue statement list rendered after the closing `}` is a few lines in the same
   class. (`Scope.write` renders statements → variables → children, so there is no
   post-child slot without this.)

Correctness gate (the part that must be conservative): promotion is only legal if
the destination array is not referenced anywhere else in the loop body except via
the structurally identical index — the input could alias the output in principle.
Bail out on any other reference, on references from the loop condition, and on
nested `Repeated` scopes touching the same array. Gate the transform behind a public
static flag (mirroring `enableLoopInvariantHoisting`) for differential testing.

**Regression found by CI and fixed:** `PeriodicTest.testPeriodicInsideLoop`
failed (`0.0 != 3.0`) because a Periodic inside a Loop increments a counter
element via a statement but tests it via a `Cases` branch condition — an
expression held *outside* the statements list, invisible to a statement-only
analysis. The counter was promoted to a register while the branch condition
kept reading stale global memory, so the periodic body never fired. The fix
restricts analysis to loop bodies whose every descendant is exactly plain
`Scope` (`getClass() != Scope.class` aborts): `Cases` conditions, `HybridScope`
explicit code, and nested `Repeated` bounds all read memory outside statements,
and future subclasses are excluded by default. Reduction kernels still promote
(their bodies are plain Scopes). Regression test: `casesScopeBlocksPromotion`
in `AccumulatorPromotionTest`.

This fixes the sum kernel and every other reduction sharing the structure
(`CollectionMaxComputation`, etc.) on every backend, without touching the
computation classes, the Expression layer, or the codegen writers. With a private
accumulator the loop body contains loads only — no stores — so the CL compiler's
aliasing conservatism no longer matters.

### Implementation status and measured outcome (same session)

The transform was implemented as described (uncommitted on this branch):

- `io.almostrealism.scope.Repeated` — `enableAccumulatorPromotion` flag (default
  true), `promoteAccumulators()` running in `simplify()` after LICM, and an
  epilogue statement list rendered by `write()` after the loop closes. Matching
  is by referent name plus structurally equal position child, NOT full
  `InstanceReference` equality (the supplementary `imod` index field differs
  between destination and read references built by different code paths). The
  LICM `isLoopInvariant` could not be reused for position invariance: it treats
  the stored array's own name as variant, and the position depends on the array
  through `SizeValue` (`_v15Size`); a separate `isIterationInvariant` permits
  size/offset references while rejecting element reads of stored arrays.
- `Expression.containsInstanceReferenceToAny(Set)` — new, mirrors
  `containsStaticReferenceToAny`.
- Tests: `engine/utils` `AccumulatorPromotionTest` (7 tests: structural
  promotion, variant-position / mismatched-reference / condition-reference
  bailouts, C rendering with the epilogue after the loop, and a differential
  sum). All 16 existing `LoopInvariantHoistingTest` tests still pass.

**Verified via profile capture that the promoted kernel is what compiles on both
backends.** The CL kernel for the `sumPowers` shape is now:

```c
__kernel void f_collectionSumComputation_3(__global float *_v5, __global float *_v6, ...) {
    _v5[((long) get_global_id(0)) * _v5Size + _v5Offset] = 0.0;
    float _3_i_acc0 = _v5[((long) get_global_id(0)) * _v5Size + _v5Offset];
    for (int _3_i = 0; _3_i < 64;) {
        _3_i_acc0 = _v6[...] + _3_i_acc0;   // loads only, no stores
        _3_i = _3_i + 1;
    }
    _v5[((long) get_global_id(0)) * _v5Size + _v5Offset] = _3_i_acc0;
}
```

**However, this does NOT change OpenCL timing.** A same-JVM toggle benchmark
(600 rows, 5000 timed runs after 500 warm-up, `AR_HARDWARE_DRIVER=native,cl`,
Apple OpenCL):

| dim | promotion off | promotion on |
|-----|--------------|--------------|
| 2   | 1.56 ms/run  | 1.62 ms/run  |
| 16  | 2.17 ms/run  | 2.17 ms/run  |
| 64  | 4.56 ms/run  | 4.62 ms/run  |

Metal on the same machine runs the full `sumPowers` at ~0.25 ms/run, flat.

So the "global read-modify-write accumulator" diagnosis is disproven as the
cause of the CL gap: with the RMW eliminated at source level, per-run time is
unchanged and still scales with reduction width. The `(long)` index claim was
already disproven separately (Metal emits the identical cast and is fast).

### Where the CL time actually goes (investigated — root cause found)

The marginal cost is linear in **input buffer size**, not loop trip count:
dims 2/16/64 → 4.8/38.4/153.6 KB input → 1.56/2.17/4.56 ms. The marginal rate
is ~20 MB/s, plus ~1.5 ms fixed per dispatch.

**Confirmed by a profiled run plus JMX thread dumps** (per-row sum, 600×64,
`AR_HARDWARE_DRIVER=native,cl`, Apple OpenCL):

- The kernels are fast. Recorded device work is ~93 µs/run — the sum kernel
  itself is 25.5 µs, the output copy kernel 67 µs — out of ~4.6 ms wall per
  run. 98% of per-dispatch time is host-side.
- Thread dumps show the `ComputeContext-0..3` executor threads RUNNABLE with
  ~6.5 s of CPU each (~62% of wall combined) inside
  `AbstractComputeContext.copy` (line 183): `NativeRead.apply` (JNI) →
  `NativeMemoryProvider.getMem` → `Memory.toArray` (a Java `double[]`
  round-trip) → `CLMemoryProvider.setMem` (line 374). The host thread parks in
  `LatchSemaphore.waitFor` under `ProcessDetailsFactory.construct` (line 581) /
  `DestinationEvaluable.request` (line 373) waiting for these copies and the
  dispatch handoffs.

**The mechanism is `MemoryReplacementManager`.** On every dispatch
(`AcceleratedOperation.apply`, prepare at line 625, postprocess at line 640),
each `MemoryData` argument whose provider differs from the kernel's target
provider and is ≤ 1M elements is replaced with a temporary buffer on the
target provider, copied in before the kernel, and copied **back** after it —
both directions, every run, including read-only inputs. Under `native,cl`
collections allocate native-resident, so the sum's whole input and output
round-trip through a `double[]` on every single dispatch. This explains both
the ~20 MB/s linear-in-bytes cost (per-element JNI reads plus the `double[]`
conversion) and the ~1.5 ms fixed cost (temp allocation, semaphore chains, and
host↔executor thread handoffs). Metal never pays any of this: collections are
Metal/NIO-resident under `mtl`, so no replacement occurs. Arguments above the
1M-element threshold (`matmulLarge`'s 8.4 MB matrix) skip replacement and are
instead permanently migrated once by `HardwareOperator.reassignMemory`.

### Contained fixes, ranked

1. **Bulk copy path.** `AbstractComputeContext.copy` between native and CL
   memory goes element-by-element through JNI into a `double[]`. Both sides
   are host-accessible memory; a direct bulk transfer
   (`clEnqueueWriteBuffer`/`ReadBuffer` against the native host pointer, or at
   minimum one bulk JNI read) is contained to
   `CLMemoryProvider.setMem`/`getMem` for `NativeMemory` sources and should
   move the ~20 MB/s to something in the GB/s range.
2. **Replacement caching across dispatches.** Reuse the temp buffers when the
   same compiled operation runs with the same argument identities; skip the
   copy-in when the source has not been written since the last dispatch (the
   `CLMemory.dispatchGeneration` machinery added on this branch is exactly the
   right primitive); skip the copy-back for arguments the kernel does not
   write. This eliminates steady-state replacement traffic entirely for the
   repeated-dispatch pattern that `sumPowers`, `matmulLarge`, and realtime
   audio ticks all share.
3. **Allocation-side residency.** Prefer allocating collections on the kernel
   provider under multi-context configurations. Larger blast radius; only
   worth considering if 1 and 2 prove insufficient.

With replacement traffic gone, the per-dispatch floor is the ~0.2 ms that
`matmulPowers` already demonstrates (CL ≈ Metal there), which would put
`sumPowers` around 60 s against its 240 s budget. Approximate parity with
Metal is therefore a realistic target for these tests, not a lost cause.

### Bulk copy implemented (fix 1) — outcome

Per the owner's direction, fix 1 was implemented and fixes 2/3 will NOT be
pursued. If bulk copy proves insufficient on CI, the plan is instead to use
`CLMemory` everywhere and automatically switch from the JNI backend to the
CLJNI backend for the native portion when only `native` and `cl` are present:
CLJNI compiles C the way the native backend does, but reads and writes
`CLMemory` directly through its pointer, making the cross-provider problem
disappear rather than be optimized (requires the CL library to be available
for native compilation at runtime, which takes setup, especially on macOS).

What was changed:

- `MemoryProvider` gained two capability methods (default no-op):
  `getHostBuffer(mem, offset, length)` returning a direct `ByteBuffer` view,
  and `getMem(mem, sOffset, ByteBuffer, length)` for bulk reads. Raw bytes in
  the provider's element format; callers must check `getNumberSize()` matches.
- `NativeBufferView` (new JNI op, `org.almostrealism.c`) wraps a native
  allocation range via `NewDirectByteBuffer` — no copy.
- `NativeMemoryProvider` implements `getHostBuffer`, and its
  `setMem(…, Memory source, …)` reads bulk from the source provider directly
  into that view when element sizes match (CL→native copy-back direction).
- `CLMemoryProvider.setMem(…, Memory, …)` writes with a single
  `clEnqueueWriteBuffer` from the source's host-buffer view (native→CL
  prepare direction), resolving the long-standing TODO there; it also
  implements the bulk `getMem(ByteBuffer)` read.
- `NativeRead`'s generated C previously called `SetDoubleArrayRegion` once
  **per element** (the measured ~20 MB/s); it now performs one bulk region
  copy (FP64) or one conversion pass plus one region copy (FP32). This
  benefits every native host read, not just CL copies.
- Regression test: `CrossProviderMemoryCopyTest` (engine/utils) covers both
  directions with offsets and sentinels, and skips cleanly when CL or JNI is
  unavailable.

Measured effect (per-row sum, 600 rows, Apple OpenCL, `native,cl`):

| dim | before      | after       |
|-----|-------------|-------------|
| 2   | 1.56 ms/run | 1.28 ms/run |
| 16  | 2.17 ms/run | 1.32 ms/run |
| 64  | 4.56 ms/run | 1.30 ms/run |

The linear-in-bytes component is gone — per-dispatch time is now flat across
reduction widths — and `sumPowers` dims now complete in ~63 s each (previously
~80 s and growing with width). **However, the fixed ~1.3 ms per dispatch
remains** (temporary-buffer allocation per dispatch, semaphore chains, and
host↔executor thread handoffs in the replacement machinery), so `sumPowers`
(300k dispatches ≈ 390 s) still exceeds its 240 s budget locally, and
`matmulLarge` now surfaces the same underlying issue as `Memory Max Reached`:
with copies no longer throttling the dispatch rate, per-dispatch CL temp
allocation outpaces GC-driven reclamation. Both are the per-dispatch
replacement cost itself, which is exactly what the CLMemory-everywhere/CLJNI
direction eliminates.

The accumulator promotion is still a correct, verified codegen improvement — the
loop body is loads-only on every backend, which removes the dependence on each
backend compiler's aliasing analysis — but it is not sufficient to fix the
`sumPowers` timeout on OpenCL.
