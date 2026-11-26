---
status: completed
created: 2026-11-26
closed: 2026-11-26
tracker: [1cd4083d-8c9f-468f-b378-536474ca865e]
---

# Throughput benchmark of the neuron-level connectome simulation on every backend

**Tracker task:** `1cd4083d-8c9f-468f-b378-536474ca865e` — *Throughput benchmark of the
neuron-level connectome simulation on every backend* (its description widens this to every
backend and machine).
**Release:** Common 0.78 (the release under development, whose name matches the version in the
root `pom.xml`).

This is the last Phase 1 task (item 12) of the connectome model organism design
(`docs/plans/CONNECTOME_MODEL_ORGANISM.md`): once the neuron-level MaleCNS model runs and
reproduces Shiu et al. (the Phase 1 gate, task `2a8fe992`, now closed), measure **how fast** it
runs — in simulated seconds per wall-clock second — on every available backend and machine, and
break the per-step cost into its parts so the next optimization is chosen from numbers rather than
guesswork.

## Motivation

The gate proved the platform runs a real connectome *correctly*. This task answers the other half
of "is this realizable on current hardware": *how fast*. Phase 1 of the design states its gate
includes "throughput, in simulated seconds per wall-clock second, is measured and recorded on every
available backend", and the task exists to produce that number with a cost breakdown.

The numbers decide real downstream choices named in the task:

- whether **Phase 3** (training through the simulation) is affordable at 0.1 ms spiking steps, or
  must fall back to a coarser rate model. At `DT = 0.1 ms`, one simulated second is 10,000 steps,
  so a training run that unrolls seconds of simulated time multiplies the per-step cost by tens of
  thousands;
- whether to build **event-driven propagation** (scatter-add over only the spiking neurons' edges)
  or **step fusion** next. That decision needs to know whether the sparse product, the neuron
  update, or dispatch overhead dominates each backend's per-step time.

## Scope

Concretely, this task will add a benchmark and record its results; it changes no production code
and no simulation behaviour.

1. **A profiled benchmark of the neuron-level `ConnectomeNetwork` step** on the MaleCNS connectome,
   added as a long-running, pipeline-excluded test in `studio/bioscience` alongside the existing
   connectome tests. It:
   - loads the converted MaleCNS CSR connectome and builds a single-replica `ConnectomeNetwork` at
     the Shiu et al. constants, with sugar-GRN Poisson drive (the configuration the gate validated);
   - runs a warm-up, then times a fixed number of steps and reports **simulated seconds per
     wall-clock second** (`steps * DT / 1000 / wallSeconds`) and `perStepMicros`, logging the Metal
     dispatch/commit counts per step as `SimulationStepProfileTest` does;
   - attaches an `OperationProfileNode` to the runner and saves it to `results/connectomeStep.xml`
     so `ar-profile-analyzer` can read the per-operation timing and generated source.
2. **A per-step cost breakdown** read from that profile with `ar-profile-analyzer`
   (`list_children`, `get_timing_breakdown`, `get_source`): the time attributable to the **sparse
   product** (`dense(weights)` over the CSR connectome — `SegmentedWeightedSumComputation`), the
   **neuron update** (the `ExponentialSynapseNeurons` assignments plus `PoissonInput`), the
   **ring-buffer** `cache_read`/`cache_write`, and the **dispatch overhead** (per-step wall-clock
   minus the summed device time of the operations). The dominant cost per backend is identified.
3. **Memory footprint** recorded for the benchmarked configuration: the CSR connectome tensors
   (`row_offsets`, `presynaptic`, `weights`, `signs`), the delay-line ring
   (`[delaySteps + 1, replicas * N]`), the neuron and Poisson state, and the device high-water where
   the backend exposes it.
4. **A results table in this plan** with one row per backend and machine, each row carrying the
   commit and the settings (`AR_HARDWARE_DRIVER`, precision, `AR_HARDWARE_MEMORY_SCALE`, replicas,
   steps, stimulus rate), the simulated-sec/wall-sec throughput, the per-step breakdown and the
   memory footprint.
5. **Follow-up optimization tasks filed** from what the profiles show — e.g. event-driven
   (scatter-add) propagation, CUDA Graph capture of the step, or step fusion — each referencing this
   plan and the dominating cost that motivates it.

The target backends and machines named by the task: **Metal** (M1 128 GB, M4 Max, the Mac minis),
**CUDA** on the DGX Spark, **OpenCL** on the AMD Halo, and **native**. Which of these a single
implementation run can reach is an open question below; the plan document is the recording surface,
so rows produced on other machines (by the owner or by follow-up runs) are pasted into the same
table.

**Out of scope** (named as follow-ups, not widened into here): building event-driven propagation,
CUDA Graph capture or step fusion themselves; any change to the simulation's numerics or the
connectome ingest; the trainable rate model and backpropagation through time (Phase 3); the
MaleCNS negative-control scientific decision still open on the gate plan
(`PLAN-20261124-malecns-shiu-gate.md`, follow-up `130005e1-...`), which is a correctness question
and does not block a throughput measurement.

## Approach

The work lives in test and documentation files only. No new Maven module, no `pom.xml` change, and
no new production class is expected — the benchmark drives already-merged classes.

- **The benchmark test** — a new class in
  `studio/bioscience/src/test/java/org/almostrealism/bioscience/connectome/test/`, extending
  `NeuronConnectomeChecks` for the dataset accessors (`DERIVED`, `requireResourceIn`, the MaleCNS
  CSR directory and identities that `ConnectomeNetworkDatasetTest` already uses). It is modelled on
  two existing patterns:
  - `CellTypeRateModelTest.timePerStep` — the `warm-up → time N steps → log perStepMicros`, backend
    label, `@TestDepth(2)`, `@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)`,
    `timeout = 2400000`, assert only finiteness (never a time threshold) shape; and
  - `engine/utils` `SimulationStepProfileTest.profileSimulationStep` — attaching an
    `OperationProfileNode` to the `SimulationRunner` and saving it to `results/*.xml`, and logging
    the per-step dispatch and commit counts (`MetalCommandRunner.totalDispatchCount` /
    `totalCommitCount`, zero off Metal).
- **Getting a profiled runner.** `ConnectomeNetwork.getRunner()` builds a `SimulationRunner` with no
  profile; `meanRates(...)` uses it for correctness runs. For the benchmark the test constructs a
  profiled runner directly — `new SimulationRunner(network, ConnectomeNetwork.DT, network.getStep(),
  profile)` — exactly as `SimulationStepProfileTest` does for its own `Temporal`. `ConnectomeNetwork`
  is a `Temporal` and exposes `getStep()`, so this needs no production change. (If a profiled run
  turns out to be wanted beyond this test, the natural home is a `getRunner(OperationProfileNode)`
  overload on `ConnectomeNetwork` that owns the runner — but that is not required here and is left
  out to keep the task test-only.)
- **The benchmarked configuration.** One replica (a single brain — this is the realizability
  question, not a batch-of-trials question), the Shiu et al. constants
  (`ConnectomeNetwork.DT`, `SYNAPSE_WEIGHT`, …), the sugar-GRN cell types driven by Poisson input at
  the gate's rate. Driving through the Poisson input (the
  `ConnectomeNetwork(connectome, replicas, poissonRate, seed, stimulated)` constructor, which
  allocates its own silent external drive) keeps the benchmark self-contained — no committed Brian2
  schedule or `StateDictionary` to keep alive. The step count for timing is chosen to stay well
  under the FP32 step-counter `2^24` bound (e.g. a few thousand steps after a warm-up).
- **The breakdown.** After the run, the saved `results/connectomeStep.xml` is loaded in
  `ar-profile-analyzer` (`load_profile`, `list_children`, `get_timing_breakdown`, `find_slowest`,
  `get_source`). The `connectome_step` layer compiles to the operations described in
  `docs/internals/neuron-level-connectome-simulation.md` §4 — `poisson`, the `dense(weights)` sparse
  product scaled by `synapse_weight`, the `ExponentialSynapseNeurons` step, `cache_read`/`cache_write`
  on the ring, and the runner's counter advance — so each measured operation maps to one of the
  three reported buckets plus dispatch. Dispatch overhead is per-step wall minus summed device time,
  the same framing `SimulationStepProfileTest` and `simulation-loop-examples.md` §"Measured Overhead"
  use.
- **Per backend/machine.** `AR_HARDWARE_DRIVER` selects the backend (`mtl`, `cl`, native default);
  `AR_HARDWARE_MEMORY_SCALE=6` gives the ~16 GB the whole connectome needs. The same test is run on
  each machine and its row added to the table.
- **Documentation.** The results, breakdown and footprint go in this plan's Results section. Any
  durable fact worth keeping (e.g. a measured per-step cost that belongs beside the existing dense
  figures) is added to `docs/internals/simulation-loop-examples.md` and
  `docs/internals/neuron-level-connectome-simulation.md` §7 (which currently names "the performance
  benchmark" as open tracker work) rather than pointing a durable page at this plan.

## Results

Measured by `ConnectomeThroughputBenchmarkTest` (`studio/bioscience`, package
`org.almostrealism.bioscience.connectome.test`): `maleCnsStepThroughput` times the profiled whole
step, `maleCnsSparseProductTime` steps the `dense(weights)` sparse product alone through a
`SimulationRunner`. Run with
`-DAR_HARDWARE_DRIVER=<mtl|native|cl> -DAR_HARDWARE_MEMORY_SCALE=6 -DAR_LONG_TESTS=enabled`.

Settings common to all rows: the working tree of this branch on top of commit `0a6f9f754`; the
converted MaleCNS CSR connectome (164,506 neurons, 25,135,616 stored edges); one replica; Shiu et
al. constants (`DT = 0.1 ms`, `SYNAPSE_WEIGHT = 0.275`); Poisson drive at 150 Hz to the 77 neurons
of the MaleCNS sugar-GRN types `LB3a`–`LB3d`; `AR_HARDWARE_MEMORY_SCALE=6`; a 200-step warm-up and
2,000 timed steps (200 ms simulated). About 91 neurons spiked per step (199,775–200,319 spikes over
the 2,200 steps, depending on precision); the neuron state stayed finite on every backend.

Machine: Mac Studio, Apple M1 Ultra, 128 GB, macOS, aarch64.

| Backend | Machine | Precision | Sim s / wall s | perStepMicros | Sparse product | Neuron update | Ring r/w | Dispatch / outside kernels | Memory | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| Metal | M1 Ultra 128 GB (Mac Studio) | FP32 | 0.0121 | 8,244 | 7,812 µs (95%) | ← 432 µs for all of these together → | | | 321 MB | 22 dispatches, 0.087 commits per step |
| native | M1 Ultra 128 GB (Mac Studio) | FP64 | 0.0049 | 20,373 | 11,684 µs (57%) | 3,412 µs (17%) | 4,251 µs (21%) | ~1,010 µs (5%) | 641 MB | 18 kernels per step |
| OpenCL (Apple) | M1 Ultra 128 GB (Mac Studio) | FP32 | 0.0059 | 17,016 | 9,239 µs (54%) | ← 7,777 µs for all of these together → | | | 321 MB | not a task target; extra row |
| Metal | M4 Max | FP32 | not run | | | | | | | follow-up `b419fa7a` |
| Metal | Mac mini | FP32 | not run | | | | | | | follow-up `b419fa7a` |
| CUDA | DGX Spark | | not run | | | | | | | follow-up `b419fa7a` |
| OpenCL | AMD Halo | | not run | | | | | | | follow-up `b419fa7a` |

**How the breakdown was read.** On native, kernels run synchronously, so the operation profile
(`results/connectomeStep.xml`, with the profile assigned to the hardware) records each kernel's
real run time, read with `ar-profile-analyzer` (`find_slowest_by_category run`, `get_source`):

| Bucket | Kernels (per step, native FP64) | µs |
|---|---|---|
| Sparse product | `SegmentedWeightedSumComputation` (`matmul (164506, 1)`) 11,424; `scale layer` 260 | 11,684 |
| Neuron update | `ExponentialSynapseNeurons`: membrane-integration, synapse-integration, threshold, membrane-reset, synapse-reset, refractory-countdown, refractory-reload, spike-count and their two isolated `multiply` inputs; the input sum `add` 335 | 3,123 |
| Poisson input | the `CounterRandom` draw plus drive `add` | 289 |
| Ring | `cacheWrite-19x164506` 3,950; `cache_read` `rowAtIndex` 301 | 4,251 |
| Counter | `Advance Simulation Step` | 17 |
| Outside kernels | per-step wall minus the 18 kernels' sum | ~1,010 |

(The table above folds Poisson into "Neuron update": 3,123 + 289 = 3,412.) `maleCnsSparseProductTime`
on native gives 11,739 µs for the product alone, agreeing with the in-step 11,424 µs.

On Metal and OpenCL, submission is asynchronous: the profile records the 3–15 µs it takes to
issue each kernel, not its device time (follow-up `90ee1ce4`), so the per-kernel split cannot be
read off the profile there. Instead the sparse product was timed on its own through the same
runner, and the remainder of the step (neuron update, Poisson, ring, and dispatch together) is the
difference. On Metal the 22 kernels per step cost 432 µs in total beyond the product, which bounds
dispatch overhead at under 5% of the step.

**Memory footprint** (from the network's tensors, logged by the benchmark): the CSR connectome
75,900,368 elements (row offsets, presynaptic columns, weights, signs); the delay-line ring
`[19, 164506]` = 3,125,614; the neuron state (membrane, synapse, refractory, spikes, spike
counts) 822,530; the Poisson targets and external drive 329,012 — 80,177,524 elements in all,
320.7 MB in FP32 and 641.4 MB in FP64. The connectome is 95% of it. Device high-water was not
recorded: the memory providers' allocated-bytes counters are not exposed through a
backend-neutral interface, so reading them would mean branching on the Metal/OpenCL classes.

**Dominant cost per backend and the follow-ups it motivates.**

- **Metal: the sparse product, 95% of the step.** It streams all 25 M edges at about 39 GB/s
  (~12 bytes per edge in 7.8 ms), far below the device's bandwidth — the same looped-reduction
  shape `simulation-loop-examples.md` records for the dense cell-type product. Dispatch is not
  the problem (< 5%), so **CUDA Graph capture / step fusion are not justified by these numbers**
  for Metal; CUDA Graph capture stays where it is already tracked (`dabc96f9`) pending a CUDA
  row. Follow-ups: **event-driven propagation** (`eccd1bc2`) — with ~91 spikes per step and a
  mean out-degree of ~153, a scatter-add over the spiking neurons' edges touches ~14,000 edges
  instead of 25 M — and a faster **segmented weighted-sum kernel** (`8d193a19`).
- **native: the sparse product (57%), then the ring write (21%).** `cacheWrite` writes one row of
  the delay line through a kernel that sweeps all 19 rows and copies 18 of them back onto
  themselves, costing 13× the matching one-row `cache_read` (follow-up `9f205a80`).
- **OpenCL (Apple): the sparse product (54%).** The 7.8 ms remainder is much larger than Metal's,
  but Apple's OpenCL is not a target platform; the AMD Halo row (`b419fa7a`) is the one that
  matters.

**What this means for Phase 3.** At 0.0121 simulated s per wall s on Metal, one simulated second
of one brain takes about 82 s; training that unrolls seconds of simulated time through 0.1 ms
spiking steps is not affordable with the current step. Event-driven propagation would remove most
of the 7.8 ms product, leaving the 0.43 ms remainder; that projects (it is not a measurement) to
roughly 0.2 simulated s per wall s on Metal — still slow for training through the full spiking
model, so the coarser rate model remains the realistic Phase 3 path unless the remainder also
shrinks.

Also found while writing the benchmark: `NeuronConnectome.mask(...)` over the 77 sugar neurons
overflows a `long` in `ParallelProcessContext.of` when evaluated through `evaluate()` (follow-up
`ee955a45`); the product-only benchmark uses an all-ones presynaptic vector instead, which costs
the same because the CSR product visits every edge whatever the vector holds.

Follow-up tasks filed: `eccd1bc2-0ee1-462b-bc14-c3ddf3cc1ba9` (event-driven propagation),
`8d193a19-a26c-4d59-b407-8114f7f4cf06` (segmented weighted-sum bandwidth),
`9f205a80-feba-4339-8431-d80ab8784810` (cacheWrite sweeps the whole cache),
`90ee1ce4-c6c6-4fce-8816-2cfc1699c6e2` (profile records issue time on Metal/OpenCL),
`ee955a45-fe97-4fc5-b24e-4a87a3d1da52` (mask overflow),
`b419fa7a-36f5-4d03-b276-3dadfa01471f` (rows for CUDA, AMD Halo, M4 Max and the Mac minis).

## Success criteria

A reviewer confirms the task is done when:

1. The benchmark test is present in `studio/bioscience`, compiles, and runs narrowly on at least
   one host (the macOS/Metal node), producing `perStepMicros`, the simulated-sec/wall-sec figure,
   the Metal dispatch/commit counts, and `results/connectomeStep.xml`. It asserts only finiteness,
   never a time threshold, and is `@TestProperties(longRunning = true, excludeProfiles = PIPELINE)`
   so it never runs in CI and weakens no existing test.
2. This plan's Results table is filled for every backend and machine that could be reached, each row
   carrying the commit and settings; any backend/machine that could not be reached is stated
   explicitly as not run (not silently omitted).
3. The per-step cost is broken into sparse product, neuron update, ring and dispatch from the
   `ar-profile-analyzer` reading of `results/connectomeStep.xml`, and the dominant cost per backend
   is named.
4. The memory footprint of the benchmarked configuration is recorded.
5. Follow-up optimization tasks are filed (`tracker_create_task`) for what the profiles justify
   (event-driven propagation, CUDA Graph capture, step fusion), their ids listed in this plan and
   in the final summary.
6. The always-on CI guard `ConnectomeNetworkTest` still passes in the pipeline profile (unchanged by
   this task, since no production code is touched).

## Dependencies and open questions

**Dependencies:**

- The **converted MaleCNS CSR connectome** on the benchmarking host (outside the repository, by
  default under `/Users/Shared/models-derived/male-cns/csr`, selected by `AR_MALE_CNS_CSR` /
  `NeuronConnectomeChecks.DERIVED`), produced for the gate task. A missing dataset fails the
  benchmark on every host; it is never gated into a skip.
- A **macOS/Metal host** with that data mounted and `AR_HARDWARE_MEMORY_SCALE=6`, because the
  dataset lives on the shared Mac model directory and the whole-connectome spiking step needs Metal
  to finish in reasonable wall-clock time. Hence the `requiredLabels: platform: macos` settings file
  beside this plan.

**Open questions for the approver:**

1. **One workstream reaches one machine.** The task names Metal on several Macs, CUDA on the DGX
   Spark, OpenCL on the AMD Halo and native — but a single implementation branch runs on one node.
   Proposal: the benchmark code plus the Metal and native numbers from the macOS node land on this
   branch, and the other machines' rows are collected separately (owner-run, or follow-up runs on
   those nodes) and pasted into this plan's table before it closes. Is collecting the full matrix on
   one branch expected, or is the staged collection acceptable?
2. **CUDA readiness.** `CONNECTOME_MODEL_ORGANISM.md` records that CUDA on the DGX Spark has its
   native bridge, code generation and asynchronous runner, but that "breadth, performance and CI are
   still open". It is not established that the CUDA backend can yet compile and run this exact step
   (the segmented-reduction sparse product, `CounterRandom`, the `cache_read`/`cache_write` ring).
   If it cannot, the CUDA row is "pending CUDA breadth" and a dependency, not a result. The durable
   reference for the CUDA state is `docs/internals/backend-compilation-and-dispatch.md`; there is no
   `docs/plans/*CUDA*` file.
3. **Benchmarked stimulus and step count.** Proposal: one replica, sugar-GRN Poisson drive at the
   gate's 150 Hz, a 100–500-step warm-up and ~1,000–5,000 timed steps (short enough to stay under
   the FP32 `2^24` step-counter bound, long enough to average out dispatch jitter). Confirm the rate
   and step counts, or state a preferred fixed protocol so every machine's row is comparable.
4. **Does throughput depend on replica count?** The sparse product batches all replicas into one
   product, so simulated-sec/wall-sec for one brain and the per-brain cost at 30 replicas can differ.
   The task asks for the single-brain realizability number; should a replica-scaling row also be
   recorded (it bears directly on whether trials-at-once amortizes dispatch)?

**How the implementation resolved them:**

1. Staged collection. This branch carries the benchmark and the rows the macOS node could reach
   (Metal, native and, additionally, Apple OpenCL on the Mac Studio). The other machines are
   recorded as "not run" in the table and tracked by `b419fa7a`, which also carries the table to
   `docs/internals/simulation-loop-examples.md` once this plan is swept.
2. Not established here: no CUDA node was reachable. The CUDA row is part of `b419fa7a`.
3. One replica, 150 Hz to the 77 `LB3a`–`LB3d` neurons, a 200-step warm-up and 2,000 timed steps
   (`ConnectomeThroughputBenchmarkTest.WARMUP`/`STEPS`), the fixed protocol for every row.
4. Not recorded. Metal's dispatch is under 5% of the step, so trials-at-once would amortize little
   dispatch. The per-brain cost at many replicas is dominated by the same sparse product, whose
   edge reads are shared across replicas only if the kernel reuses them. That is a question for
   the kernel follow-up (`8d193a19`), not for this benchmark.

## Estimated complexity

**Medium.** The engine, ingest, simulation driver and profiling hooks are all merged, so the new
code is one self-contained benchmark test modelled on two existing ones. The weight of the task is
the measurement campaign across backends and machines — it depends on an owner-provided multi-
gigabyte dataset and on hardware (macOS/Metal, and ideally the DGX Spark and AMD Halo) that one
workstream cannot all reach — and on reading the profiles to name the dominant cost and file the
right follow-ups. There is no scientific-match iteration as there was for the gate, which keeps it
below "large".
