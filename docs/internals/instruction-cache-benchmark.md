# Instruction-Cache Benchmark

A seeded benchmark of how the instruction cache behaves on realistic, generated computations:
how often a kernel is reused rather than compiled, whether reuse ever collides, how
`Process::optimize` splits computations into kernels, and how arguments are folded into
aggregates and copied around each dispatch. It exists to inform decisions about signatures,
aggregation and kernel structure, and to measure any later change to them by running the same
command, with the same seed, before and after.

The code is in `engine/utils/src/test/java/org/almostrealism/hardware/benchmark`. The entry point
is `InstructionCacheBenchmark`. A baseline report is in
[instruction-cache-benchmark/](instruction-cache-benchmark/).

## Running it

The benchmark is a measurement, not a check, so it is kept out of CI and out of ordinary runs.
Its one test method is declared `@TestProperties(longRunning = true, excludeProfiles =
TestUtils.PIPELINE)`. Pipeline-profile runs never execute it, and other runs execute it only
when long tests are enabled:

```
AR_LONG_TESTS=enabled AR_CACHE_BENCHMARK_SEED=1729 AR_CACHE_BENCHMARK_POPULATION=500 \
    mvn -pl engine/utils test -Dtest=InstructionCacheBenchmark
```

It writes `<label>.json` (machine-readable) and `<label>.md` (summary) to
`engine/utils/results/instruction-cache-benchmark/`. Every parameter is read through
`SystemUtils`, so it can be given as an environment variable or as a `-D` system property of the
test JVM:

| Parameter | Default | Meaning |
|---|---|---|
| `AR_CACHE_BENCHMARK_SEED` | 1729 | Seed of every random choice. |
| `AR_CACHE_BENCHMARK_POPULATION` | 200 | Instances in the `generated` scenario. |
| `AR_CACHE_BENCHMARK_EVALUATIONS` | 3 | Evaluations of each compiled instance. The first compiles; the rest measure steady-state copies. |
| `AR_CACHE_BENCHMARK_CONTEXTS` | `JNI,MTL` | `ComputeRequirement`s to run under, one pass each. An unavailable context is reported, not run. |
| `AR_CACHE_BENCHMARK_SCENARIOS` | all | Comma-separated scenario names. |
| `AR_CACHE_BENCHMARK_LABEL` | `report` | File name of the report. |
| `AR_CACHE_BENCHMARK_OUTPUT` | `results/instruction-cache-benchmark` | Output directory, relative to `engine/utils`. |
| `AR_CACHE_BENCHMARK_TEMPLATES` | 24 | Size of the generated template pool. |
| `AR_CACHE_BENCHMARK_TEMPLATE_SKEW` | 1.1 | Zipf exponent of template draws. |
| `AR_CACHE_BENCHMARK_SHAPE_SKEW` | 1.0 | Zipf exponent of shape draws. |
| `AR_CACHE_BENCHMARK_CONSTANT_SKEW` | 1.2 | Zipf exponent of embedded-constant draws. |
| `AR_CACHE_BENCHMARK_ALIAS_PROBABILITY` | 0.15 | Probability that an instance puts its first two inputs on one root. |
| `AR_CACHE_BENCHMARK_MAX_DEPTH` | 4 | Deepest nesting of a generated expression. |
| `AR_CACHE_BENCHMARK_MAX_INPUTS` | 4 | Most distinct tensor inputs of a generated expression. |
| `AR_CACHE_BENCHMARK_SCALAR_PROBABILITY` | 0.3 | Probability that a generated operand is a scalar. |
| `AR_CACHE_BENCHMARK_LITERAL_PROBABILITY` | 0.5 | Probability that a generated scalar is an embedded constant rather than a one-element input. |

The report records the command that reproduces it, including every parameter that was set.

## What it does

`WorkloadGenerator` builds every scenario's population from the seed. Each `WorkloadInstance`
binds a `ComputationTemplate` (an element-wise expression of `TemplateNode`s, an optional
reduction and an optional division by a scalar) to a shape, to the root memory of its inputs and
to the values of its scalars. `sum(x * (y + sin(z))) / k` is one such template. The benchmark
does the following for each instance:

1. It allocates fresh inputs and builds the computation.
2. It runs `Process.optimized(...)`, so `Process::optimize` decides the kernel boundaries.
3. It compiles and evaluates the computation `EVALUATIONS` times.
4. It checks every output against a host evaluation of the same expression. The tolerance is
   1e-3, relative to the magnitude of the terms each value sums, so single-precision rounding
   over a long reduction passes and a kernel that reads the wrong memory does not.
5. It records the instance's kernel binding events.

An `InstructionCacheCollisionException` anywhere in the failure's cause chain is counted as a
collision. Any other exception is counted as a failure. Neither one stops the run.

Each scenario runs once per compute context, under `pushRequirements`, against a cache that holds
nothing the scenario compiled earlier. Every signature the scenario compiled is evicted when it
finishes, so scenarios and contexts share no kernels and their order does not matter.

### Scenarios

| Scenario | What it probes |
|---|---|
| `generated` | The main population. Templates, shapes and constants are drawn with Zipf-skewed reuse from finite pools. |
| `size-generic` | The four most frequent templates run over every shape of their rank, ascending and then descending, with the constants fixed. A size-generic kernel compiles once per template. |
| `aliasing` | Each instance's first two inputs are separate roots, disjoint views of one root, overlapping views, or one view bound twice. Shapes are 64 elements (folded) and 2048 (not folded). |
| `aggregate-threshold` | 64-element views over roots of 64, `maxAggregateLength` and `maxAggregateLength + 1` elements, in all 27 combinations over three inputs. The shape stays the same while the folded inputs change. Shapes from `maxAggregateLength - 2` to `+ 2` are also included. |
| `argument-limit` | 8 to 40 distinct inputs, as a chain of binary additions, as one n-ary `add(List)`, and as an alternating multiply/add chain. Inputs are too large to fold (2 × `maxAggregateLength`) or small enough (16). |

### Why the distribution sits in the middle

Each pool draw is Zipf-distributed: entry `k` is drawn with probability proportional to
`1 / k^s`. A few templates and shapes recur often and the rest occasionally, the way a program
reuses a few operations heavily. The pool sizes and skews are the tuning knobs. A pool of one
entry, or a very large skew, makes every instance repeat an earlier one, and the hit rate
approaches 100%. A large pool with no skew makes almost every instance new, and the hit rate
approaches 0%. With 24 templates (skew 1.1), 9 vector or 5 matrix shapes (skew 1.0) and 8
constants (skew 1.2), a population of 500 has a structural repeat rate of about 0.53. The
measured hit rate is about 0.66 (see the baseline).

The *structural repeat rate* is the fraction of instances whose template, shape, memory layout
and embedded constants exactly repeat an earlier instance. It is reported next to every hit rate.
It is the hit rate of a cache keyed on everything a kernel could depend on, counting one kernel
per computation. A measured hit rate above it shows kernels shared across shapes or layouts.
A rate below it shows reuse the signatures miss.

### Metrics

Each metric is reported per scenario and per compute context:

- **Cache.** `requests` (kernels obtained) splits into `hits` (an operation bound a kernel
  another operation compiled under the same signature and context) and compilations, either
  shared (under a signature) or unshared (no signature). `recompiledSignatures` counts
  compilations of a signature the scenario had already compiled, as happens after an eviction.
  `collisions` and `collisionRate` (collisions over hits) are counted by kind:
  `STRUCTURE` (a missing process-tree substitution) or `AGGREGATE_LAYOUT` (the replayed fold
  positions differ). `compiledOverMetalLimit` counts kernels compiled with more than 29
  arguments.
- **Kernels per computation.** The kernels the first evaluation obtained, after `optimize`.
- **Arguments per kernel**, **folded arguments per compiled kernel**, and **aggregate length**.
- **Copies.** Aggregate slices copied in and copied back, for the first evaluation and the later
  ones, and per aggregating dispatch.
- **Timing.** Native compilation count and time from the compilation timing listener, and the
  wall-clock time to the end of each first evaluation.
- **Problems.** Mismatches, and failures grouped by exception type, with the first detail seen.
- **Instance records.** One line per instance: its outcome and every kernel it obtained. In the
  kernel notation, `c3/1` is compiled with 3 arguments and 1 folded, `u` is compiled without a
  signature, and `h3` is a hit with 3 arguments. The record also gives the copies of its first
  and later evaluations.

Kernel structure, copies and arguments are measured over verified instances only, so a broken
kernel is never what is measured. Cache counts, including failures and collisions, cover every
instance.

### Determinism

Everything outside the `timing` sections and `problemDetails` is a deterministic function of the
seed and the code under test. Every random choice comes from `Random` streams seeded from the
seed, and each instance's input values come from a seed of their own. Failures are grouped by
exception type rather than message, because a message can name generated functions or be dropped
by the JVM. Each scenario carries a population `fingerprint`, which is equal for two reports
exactly when they generated the same instances.

## Comparing two branches

Run the same command on both branches, then:

```
python3 scripts/compare-cache-benchmark.py before.json after.json
```

The script refuses a pair whose population fingerprints differ. For each scenario and context, it
prints every deterministic metric that changed and the ratio of each timing metric. It then lists
the instances whose kernels or outcome changed. Running the same command twice on one branch
reports no deterministic change.

## Instrumentation

The hooks that already existed provide the native compilation time (the
`AbstractComputeContext.compilationTimingListener`). They do not provide the rest: whether a
kernel was compiled or reused, how many inputs were folded, and which aggregate copies were
issued. Two read-only additions in `base/hardware` provide them:

- **`KernelBindingListener`**, installed through the static `AcceleratedOperation.bindingListener`
  in the same way as the profiling listeners. It is notified when an operation compiles its scope
  (`AcceleratedComputationOperation.postCompile`) or reuses a cached kernel
  (`AcceleratedComputationOperation.load`). It is also notified at each dispatch
  (`AcceleratedOperation.apply`) and for each aggregate slice copy actually issued
  (`MemoryDataArgumentMap`). Each notification is made after the decision it reports. With no
  listener installed nothing is computed. `MemoryDataArgumentMap.getFoldedArgumentCount()`
  exposes the number of folded roots.
- **`InstructionCacheCollisionException`**, a `HardwareException` subclass thrown by the two
  collision checks in place of a plain `HardwareException`. Its message is unchanged. It lets the
  benchmark tell a collision from any other failure without reading the message, so a real failure
  is never counted as a collision.

Neither addition changes which kernel is used, how arguments are bound, or which copies are made.

## Baseline

[instruction-cache-benchmark/baseline.json](instruction-cache-benchmark/baseline.json) and its
summary [baseline.md](instruction-cache-benchmark/baseline.md) were produced on an Apple Silicon
host, on both the native (`JNI`) and Metal (`MTL`) contexts, by:

```
AR_LONG_TESTS=enabled AR_CACHE_BENCHMARK_SEED=1729 AR_CACHE_BENCHMARK_POPULATION=500 \
    mvn -pl engine/utils test -Dtest=InstructionCacheBenchmark
```

The baseline tree is master plus the two read-only additions described under Instrumentation.
The benchmark cannot run on a tree without them. Caching, signatures and aggregation are
master's.

What it shows:

- **The hit rate sits in the middle range.** In `generated` it is 0.66 against a structural repeat
  rate of 0.53. The difference is kernels shared across shapes: a kernel compiled for one length
  serves others. `size-generic` shows the same thing directly, at 0.57 against 0.50.
- **No collisions in any scenario, on either context.** Signatures distinguish every fold decision
  the benchmark provokes. In `aggregate-threshold`, each combination of root lengths around
  `maxAggregateLength` compiles its own kernel, and only the sub-kernels that do not depend on the
  fold are reused. Inputs sharing a root, overlapping, or bound twice also never collide.
  So a more selective fold is not measured against collisions today. It is measured against
  kernel counts, arguments and copies.
- **`Process::optimize` keeps chained computations far below the Metal argument limit.** A chain
  of binary additions, or of alternating multiplications and additions, is split into kernels of
  at most 10 arguments, whatever the number of inputs. A single n-ary `add(List)` is not split.
  With unfolded inputs it reaches `n + 1` arguments and fails Metal compilation from 29 inputs
  (30 arguments) on. Folded, the same computation is one kernel with 2 arguments.
- **A failed compilation poisons the next computation with its signature.** After the Metal
  compilation failure, the next structurally identical computation fails with a
  `NullPointerException` in `ProcessArgumentMap.match` instead of reporting the compile error.
  This is listed under the report's problems and filed as a follow-up. The benchmark does not
  work around it.
- **Aggregate copies.** Every folded input that is not read-only is copied in at every dispatch.
  There are about 3 copies per aggregating dispatch in `generated`, and up to 40 in
  `argument-limit`. No slice is copied back, because `evaluate()` supplies an explicit output and
  the default side-effect policy then skips the copy-back.

## Limitations

These follow from the current cache design and are stated in every report rather than worked
around:

- A hit is a cross-operation reuse through the instruction cache. Evaluating one compiled
  `Evaluable` again is not a cache request.
- Folded arguments and aggregate length are measured for compiled kernels only. A reusing
  operation's fold is verified against the compiled positions but not recorded.
- A kernel that fails native compilation has already been reported as compiled.
- Scope generation and optimization time are not reported separately from the first-evaluation
  wall-clock time.
