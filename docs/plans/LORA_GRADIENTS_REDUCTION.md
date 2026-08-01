# lora-gradients branch reduction and convDelta investigation

The `feature/lora-gradients` branch had accumulated ~78 commits and 8000+
lines of diff against `master`, mixing unrelated work with the
gradient-computation changes that broke `RepeatedDeltaComputationTests.convDeltaMedium`.
This plan tracks the staged reduction of the branch to just the
genuinely-risky work that still needs a fix, while extracting safe
material into independent PRs that can land on `master` immediately.

## Baseline observation

- `master`: `convDeltaMedium` fails approximately 1 run in 10
  (240 s boundary timeout).
- `feature/lora-gradients`: `convDeltaMedium` has failed every run
  observed across dozens of attempts.

The gap to explain is therefore the **always-fails-on-lora-gradients
vs. fails-1-in-10-on-master** delta, not the master flakiness itself
(which is a separate pre-existing issue).

## Already extracted and merged

Three independent branches were peeled off lora-gradients and have
landed on `master` via separate PRs (each verified in isolation):

| branch | scope |
|---|---|
| `feature/leaf-interning` | Expression DAG interning prototype (`LeafInternTable`, `ExpressionDuplicationScanner`, `ScopeExpressionCollector`, tests). Behaviourally inert when off; flag default off. |
| `feature/kernel-traversal-cache-fix` | `KernelTraversalOperationGenerator` cache fix (`IdentityHashMap<String>` → `HashMap<Expression>`). The cache literally never hit before the fix. |
| `feature/trig-computations` | `CollectionSineComputation` / `CollectionCosineComputation` / `UnaryCollectionComputation`, `CollectionMinusComputation.delta` override, `GeometryFeatures.sin`/`cos` wiring, `TrigonometricDeltaComputationTests`. |

After each merge, `feature/lora-gradients` was synced with `origin/master`
via a merge commit so the residual diff would narrow.

## convDelta localisation experiment (2026-05-30)

A short experiment on a throwaway branch
(`experiment/revert-subset-delta`, off `feature/lora-gradients`) reverted
only `compute/algebra/.../PackedCollectionSubset.java` to its `master`
state — 29 lines: two imports plus the `delta(Producer<?>)` direct-case
override that emits a `SubsetProjectionComputation`. Five repetitions of
`convDeltaMedium` ran under that single change:

| run | duration | result |
|---|---|---|
| 1 | 173.8 s | pass |
| 2 | 243.9 s | timeout |
| 3 | 234.2 s | pass |
| 4 | 239.4 s | pass (at the wire) |
| 5 | 234.1 s | pass |

**4 of 5 pass** — essentially the master rate. The single failure was the
same boundary-timeout failure mode observed on master.

This localises the trigger to `PackedCollectionSubset.delta`'s
direct-case projection. The rest of the lora-gradients delta-related
work (the `SubsetProjectionComputation` / `ConcatProjectionComputation`
classes, the `Sum.simplify` budget guard, the
`CollectionProductComputation` 2-line delta tweak, the
`Collection{Subset,Concatenate}Computation` classes) becomes effectively
dead code under that revert and is not — by itself — sufficient to keep
`convDeltaMedium` from passing.

A second-order observation, worth filing separately: the first run was
~60 s faster than runs 2–5 in the same JVM. Something pollutes between
iterations and the JVM never recovers. That probably explains part of
the master flakiness on CI (where many tests share a JVM) and is
independent of any lora-gradients change.

## Residual lora-gradients surface (≈ 8400 lines, ~31 files)

### Risky — must be studied or fixed before landing on master

| file | size | reason |
|---|---|---|
| `compute/algebra/.../PackedCollectionSubset.java` | +29 | **Confirmed trigger** for convDeltaMedium blow-up |
| `compute/algebra/.../SubsetProjectionComputation.java` | +205 new | Emitted by the trigger |
| `compute/algebra/.../ConcatProjectionComputation.java` | +227 new | Same family |
| `compute/algebra/.../CollectionSubsetComputation.java` | +288 new | Same family (Producer-level delta) |
| `compute/algebra/.../CollectionConcatenateComputation.java` | +333 new | Same family |
| `compute/algebra/.../CollectionProductComputation.java` | +2 | Delta path tweak that was bundled with the gradient work |
| `base/code/.../expression/Sum.java` | +56 | `Sum.simplify` budget guard for `generateReordering` |
| `base/code/.../scope/ScopeSettings.java` | +18 | `maxReorderingBudget` field tied to the Sum guard |
| `engine/utils/.../hardware/kernel/MaskedSumReorderingTests.java` | +707 | Sweep tests for the Sum budget guard; depend on the risky Sum / ScopeSettings work |
| `engine/utils/.../ml/test/AttentionGradientScalingTest.java` | +1081 | Gradient tests written against the new projection computations |
| `engine/utils/.../ml/test/ProductDeltaIsolationTest.java` | +1001 | Same family |
| `engine/utils/.../hardware/test/GradientIsolationExperimentTests.java` | +796 | Same family |
| `engine/utils/.../hardware/test/OptimizationStrategyPerformanceTests.java` | +569 | Same family |
| `engine/utils/.../ml/test/RotaryEmbeddingGradientTests.java` | +395 | Same family |
| `engine/utils/.../hardware/test/TimingResult.java` | +57 | Helper used only by the above |

### Safe — proposed for immediate extraction

| group | files | branch target |
|---|---|---|
| Documentation | `docs/index.html` (+5), `docs/internals/isolation-patterns.md` (+381), `docs/internals/optimization-strategies.md` (+339), `docs/internals/process-optimization.md` (+298), `docs/plans/FINE_TUNE_FAIL.md` (+516), `docs/plans/SPARSE_GRADIENTS.md` (+57), `docs/tutorials/08-process-optimization.html` (+682), this file | `feature/lora-gradients-docs` |
| Provenance / profile diagnostics | `OperationMetadata.applyProvenance` + `withProvenance` (+27), `OperationList.flatten` propagation (+30), `OperationProvenanceTests` (+181), `ProfileAnalyzerCLI` metadata-detail wiring (+23) | `feature/operation-provenance` |
| Defensive runtime guards | `Model.recordCompilation` warning (+24), `CompiledModel.destroy` Javadoc warning (+11), `ParallelProcess.optimize` null-strategy fallback (+10), `NativeCompiler` 50K-char `-O1` pragma threshold (+17), `flowtree/.../Agent.java` (+46 new, unrelated standalone main) | `feature/runtime-defensive-tweaks` |

Each safe extraction is small, isolated, and verifiable on its own — the
same template used for the three already-merged branches. Build
validator and a targeted test sweep on each before opening a PR.

## Plan for the residual risky work

After the safe extractions land on `master` and lora-gradients is synced
once more, what remains is a tight cluster:

- The masked-sum reordering machinery (`Sum` budget guard,
  `ScopeSettings.maxReorderingBudget`, `MaskedSumReorderingTests`),
- The sparse-Jacobian projection family (`PackedCollectionSubset.delta`
  direct case, the four `*Projection*Computation` /
  `Collection{Subset,Concatenate}Computation` classes,
  `CollectionProductComputation` 2-line tweak),
- And the gradient-isolation / attention-scaling test classes that
  exercise the above.

That is the surface area for the convDeltaMedium fix proper. Two
non-mutually-exclusive directions are open:

1. **Gate the trigger.** Add a size or shape threshold to
   `PackedCollectionSubset.delta`'s direct case so it does not fire for
   convolution-scale subsets, or have `SubsetProjectionComputation` emit
   a form that does not hit the masked-sum predicate in `Sum.simplify`.
   This keeps the optimisation working for the cases it was designed for
   without triggering the blow-up.
2. **Profile the blow-up.** With the confirmed trigger isolated, run a
   failing `convDeltaMedium` on the full lora-gradients branch under
   JFR to see what the time is actually spent on (per-index simplify,
   kernel compilation, kernel execution). That tells us which mitigation
   is the right one.

Either direction can proceed once the safe extractions clear CI and
lora-gradients has narrowed to just the relevant code.

## Status (2026-05-30)

- Plan drafted on `feature/lora-gradients` after the
  `PackedCollectionSubset.delta` localisation experiment.
- Two of three safe-extraction branches still to create:
  `feature/lora-gradients-docs`, `feature/operation-provenance`,
  `feature/runtime-defensive-tweaks`. Each will get a build-validator
  pass and targeted test sweep before being handed back for push.
- convDelta profiling work parked until the lora-gradients surface area
  has narrowed enough that the remaining risky code is clearly the
  whole story.
