# Instruction-cache benchmark

Produced by:

```
AR_LONG_TESTS=enabled AR_CACHE_BENCHMARK_SEED=1729 AR_CACHE_BENCHMARK_POPULATION=500 mvn -pl engine/utils test -Dtest=InstructionCacheBenchmark
```

## Parameters

| Parameter | Value |
|---|---|
| population | 500 |
| evaluations | 3 |
| seed | 1729 |
| templatePoolSize | 24 |
| templateSkew | 1.1 |
| shapeSkew | 1.0 |
| constantSkew | 1.2 |
| aliasProbability | 0.15 |
| maxDepth | 4 |
| maxInputs | 4 |
| scalarProbability | 0.3 |
| literalProbability | 0.5 |
| aggregateLimit | 1024 |
| argumentAggregation | true |

## Scenarios

| Scenario | Instances | Templates | Structural repeat | Purpose |
|---|---|---|---|---|
| generated | 500 | 24 | 0.5320 | instances drawn with Zipf-skewed reuse from pools of generated templates, shapes and constants |
| size-generic | 56 | 4 | 0.5000 | the most frequent templates over every shape, ascending then descending |
| aliasing | 42 | 3 | 0.4286 | two inputs on separate roots, on one root, overlapping, and bound to the same view |
| aggregate-threshold | 64 | 2 | 0.0000 | roots at, below and above the aggregation limit under one shape, and shapes crossing it |
| argument-limit | 120 | 30 | 0.5000 | n distinct inputs in one computation around the Metal argument limit, unfolded and folded |

## Contexts

- JNI: NativeComputeContext
- MTL: MetalComputeContext

## Results

Outcomes are verified/mismatch/collision/failure. Hit rate is reused kernels over kernel requests; collision rate is collisions over hits. Arguments and folded inputs are means with the maximum in parentheses; copies are per aggregating dispatch (in / back); compile is the mean native compilation in milliseconds and is the only machine-dependent column.

| Scenario | Context | Instances | Outcomes | Hit rate | Structural repeat | Collision rate | Kernels/computation | Arguments/kernel | Folded/kernel | Aggregate length | Copies | Compile ms |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| generated | JNI | 500 | 500/0/0/0 | 0.6628 | 0.5320 | 0.0000 | 1.7380 | 2.2152 (6) | 1.7577 (5) | 1039.3118 | 3.0754 / 0.0000 | 203.3759 |
| size-generic | JNI | 56 | 56/0/0/0 | 0.5700 | 0.5000 | 0.0000 | 1.7857 | 2.2400 (6) | 1.9767 (5) | 1480.2400 | 3.1600 / 0.0000 | 205.5468 |
| aliasing | JNI | 42 | 42/0/0/0 | 0.5714 | 0.4286 | 0.0000 | 1.6667 | 2.2857 (4) | 0.7667 (3) | 146.6667 | 1.9524 / 0.0000 | 203.4205 |
| aggregate-threshold | JNI | 64 | 64/0/0/0 | 0.4453 | 0.0000 | 0.0000 | 2.0000 | 2.4688 (4) | 1.7746 (3) | 1330.4483 | 2.1724 / 0.0000 | 206.1408 |
| argument-limit | JNI | 120 | 120/0/0/0 | 0.7625 | 0.5000 | 0.0000 | 2.6667 | 7.0000 (41) | 5.6316 (40) | 180.2105 | 9.7500 / 0.0000 | 205.5307 |
| generated | MTL | 500 | 500/0/0/0 | 0.6628 | 0.5320 | 0.0000 | 1.7380 | 2.2152 (6) | 1.7577 (5) | 1039.3118 | 3.0754 / 0.0000 | 0.4390 |
| size-generic | MTL | 56 | 56/0/0/0 | 0.5700 | 0.5000 | 0.0000 | 1.7857 | 2.2400 (6) | 1.9767 (5) | 1480.2400 | 3.1600 / 0.0000 | 0.4236 |
| aliasing | MTL | 42 | 42/0/0/0 | 0.5714 | 0.4286 | 0.0000 | 1.6667 | 2.2857 (4) | 0.7667 (3) | 146.6667 | 1.9524 / 0.0000 | 0.4229 |
| aggregate-threshold | MTL | 64 | 64/0/0/0 | 0.4453 | 0.0000 | 0.0000 | 2.0000 | 2.4688 (4) | 1.7746 (3) | 1330.4483 | 2.1724 / 0.0000 | 0.3943 |
| argument-limit | MTL | 120 | 112/0/0/8 | 0.7595 | 0.5000 | 0.0000 | 2.7857 | 6.3141 (29) | 5.9444 (40) | 180.2105 | 9.7500 / 0.0000 | 3.4175 |

## Problems

- argument-limit / MTL: 4 x FAILURE: HardwareException, first seen as `HardwareException (in MetalProgram.compile): Failed to compile f_collectionAddComputation_N`
- argument-limit / MTL: 4 x FAILURE: NullPointerException, first seen as `NullPointerException (in ProcessArgumentMap.match): Cannot invoke "io.almostrealism.code.Memory.equals(Object)" because the return value of "org.almostrealism.hardware.MemoryData.getMem()" is null`

## Limitations

- A hit is an operation binding a kernel another operation compiled under the same signature and compute context. Reuse of a compiled operation by its own owner (evaluating one Evaluable repeatedly) is not a cache request and is not counted.
- Folded arguments and aggregate length are known only for kernels an instance compiled; a reused kernel reports its argument count, and its aggregate is verified to have the compiled positions but is not measured again.
- A kernel the backend fails to compile (for example one over the Metal argument limit) has already been counted as a compiled kernel when its native compilation fails; the failure is reported among the problems.
- Native compilation time is the only compile time the hooks report; building and optimizing the computation and generating its scope are included only in firstEvaluationMillis.
- Every value in the timing sections depends on the machine and its load. Every other value is a deterministic function of the seed and the code under test.
