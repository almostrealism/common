# Training Compile Performance

What dominates the cost of the first training step of a model, how to measure it, and the
defects that once made fine-tuning look infeasible. The measurements below come from a
September 2026 re-baseline of LoRA fine-tuning on small `DiffusionTransformer`s
(`AggressiveFineTuningTest`, studio/compose). They describe orders of magnitude and growth
rates, not guarantees; re-measure before relying on a specific number.

## How to measure

- Use the individually selectable methods of `AggressiveFineTuningTest`. Run each
  configuration in a **fresh JVM** (one test-runner invocation each), so no run reuses
  kernels compiled by another, and take the median of several repetitions.
- Separate the timings:
  - **cold first step**: the first `optimizer.optimize(1)`, which includes the lazy
    backward compile;
  - **warm step**: the second `optimize(1)`;
  - **forward compile**: `compileForTraining()`.
  `cold − warm` estimates the compile cost.
- To profile only the backward pass, build, compile and run a forward pass *outside* the
  profile, then profile the first `CompiledModel.backward(...)` call
  (`testProfiledBackwardEmbed64` does this). Analyse the profile with `ar-profile-analyzer`:
  `find_slowest_by_category(category="compile")` for backend compile, and `get_source` to
  see which backend compiled each kernel.
- Long tests run only with `AR_LONG_TESTS=enabled` (the flag takes `enabled`/`disabled`,
  not `true`).

## What the re-baseline found

On single-block and 2–3-block LoRA models (`latentLen=2`, no cross-attention conditioning):

- **The cold first step took 14–21 s and did not grow with embedding width** from 8 to 256.
  The log-log slope was slightly negative, plausibly because more intermediates exceed the
  argument-aggregation size limit at larger sizes, though that was not measured. The warm
  step grew from 0.35 s to 1.3 s over the same range.
- **Each additional transformer block added about 1 s** of cold first-step latency.
- An earlier measurement had recorded minutes to tens of minutes for the same
  configurations, and timeouts at embed=256. That gap was caused by the defects below, not
  by any fundamental limit of the compilation architecture.
- **Not measured:** production sizes (for example embed=1024, depth=16), cross-attention
  conditioning, and latent lengths beyond 2. At those sizes run time, not compile time, may
  dominate.

### Where the cold backward pass goes (embed=64)

The cold first backward took about 7.9 s; warm backward about 0.15 s.

- **Backend compile was about 3.2 s over 144 kernels**, and thirteen of them accounted for
  almost all of it: twelve small `sum` reductions and one `indexOfMax`, each compiled as a
  **native (JNI) C kernel** at roughly 250 ms. The other 131 kernels compiled in about
  0.07 s together. Large kernels such as the `multiply` products compiled in milliseconds
  on Metal. Per-kernel native compilation of small reductions is therefore the biggest
  remaining lever, at about 40% of the cold backward pass.
- **Expression-cache matching** (`expressionCacheMatch`) is now negligible, about 0.03 s
  in total; it was once the largest accumulated stage.
- About 2.8 s of the cold backward pass, and much of the 5–14 s forward compile, is JVM-side
  preparation (expression construction, scope preparation) that the profile does not
  attribute to any operation node.

## Defects that looked like "compilation does not scale"

1. **Per-entry constant construction in loop-replacement analysis.**
   `ExpressionMatrix.uniqueNonZeroOffset`, called from
   `AggregatedProducerComputation.prepareScope`, built a `Constant` expression for every
   entry of matrices with up to hundreds of thousands of entries, only to test it for zero.
   In a JFR sample this accounted for about 80% of the cold backward pass. Entries are now
   tested through `isNonZero`, which `SequenceMatrix` and `MaskMatrix` answer from stored
   numbers (`ExpressionMatrixNonZeroTests`). The general lesson: an analysis that
   materialises an expression per element turns a compile step into a hot loop. Ask the
   data structure directly.
2. **Instruction-cache collision in `Assignment`.** `Assignment.signature()` omits the
   destination so one kernel serves every size. Whether the destination is folded into the
   aggregate argument depends on its size relative to
   `MemoryDataArgumentMap.maxAggregateLength`, so two gradient-clearing assignments of
   different sizes collided and every embed ≥ 64 configuration failed in
   `compileForTraining()`. The signature now includes whether the destination is an
   aggregation target
   (`InstructionCacheCollisionEnforcementTest#assignmentAcrossAggregationLimitDoesNotCollide`).
   Any signature that omits an input must also include every property of that input that
   changes the generated code.
3. **Input shape drift.** After batched-input validation was added to
   `CompiledModel.InputManager`, `DiffusionTrainingDataset` still produced a bare `(1)`
   timestep instead of `(batchSize, 1)`, so every `ModelOptimizer` run over it failed
   immediately (`DiffusionTrainingDatasetTest`). Global conditioning must likewise be
   `(batchSize, globalCondDim)`.

## See also

- [training-loop-examples.md](training-loop-examples.md)
- [automatic-differentiation.md](automatic-differentiation.md)
- [profiling.md](profiling.md)
- `base/hardware/docs/INSTRUCTION_CACHING.md`
