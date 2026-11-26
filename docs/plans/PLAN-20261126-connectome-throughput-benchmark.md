---
status: proposed
created: 2026-11-26
closed:
tracker: [1cd4083d-8c9f-468f-b378-536474ca865e]
---

# Throughput benchmark of the neuron-level connectome simulation on every backend

**Tracker task:** `1cd4083d-8c9f-468f-b378-536474ca865e` — *Throughput benchmark of the
neuron-level connectome simulation on every backend and machine*.
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

## Results (to be filled by the implementation run)

Settings common to all rows unless noted: commit `<sha>`, one replica, Shiu et al. constants,
`DT = 0.1 ms`, sugar-GRN Poisson drive at `<rate>` Hz, `AR_HARDWARE_MEMORY_SCALE=6`,
`<warmup>`-step warm-up, `<steps>` timed steps.

| Backend | Machine | Precision | Sim s / wall s | perStepMicros | Sparse product | Neuron update | Ring r/w | Dispatch | Memory | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| Metal | M1 128 GB | FP32 | | | | | | | | |
| Metal | M4 Max | FP32 | | | | | | | | |
| Metal | Mac mini | FP32 | | | | | | | | |
| CUDA | DGX Spark | | | | | | | | | see open question |
| OpenCL | AMD Halo | | | | | | | | | |
| native | (host) | FP64 | | | | | | | | |

Dominant cost per backend and the follow-up(s) it motivates: *to be filled.*

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
   If it cannot, the CUDA row is "pending CUDA breadth" and a dependency, not a result. The task
   cites `docs/plans/CUDA_BACKEND.md` for the CUDA state, **but that file does not exist** in the
   tree (no `docs/plans/*CUDA*`); the durable reference is
   `docs/internals/backend-compilation-and-dispatch.md`. The task description should be corrected to
   point there (or the CUDA plan restored). Flagged in the task record.
3. **Benchmarked stimulus and step count.** Proposal: one replica, sugar-GRN Poisson drive at the
   gate's 150 Hz, a 100–500-step warm-up and ~1,000–5,000 timed steps (short enough to stay under
   the FP32 `2^24` step-counter bound, long enough to average out dispatch jitter). Confirm the rate
   and step counts, or state a preferred fixed protocol so every machine's row is comparable.
4. **Does throughput depend on replica count?** The sparse product batches all replicas into one
   product, so simulated-sec/wall-sec for one brain and the per-brain cost at 30 replicas can differ.
   The task asks for the single-brain realizability number; should a replica-scaling row also be
   recorded (it bears directly on whether trials-at-once amortizes dispatch)?

## Estimated complexity

**Medium.** The engine, ingest, simulation driver and profiling hooks are all merged, so the new
code is one self-contained benchmark test modelled on two existing ones. The weight of the task is
the measurement campaign across backends and machines — it depends on an owner-provided multi-
gigabyte dataset and on hardware (macOS/Metal, and ideally the DGX Spark and AMD Halo) that one
workstream cannot all reach — and on reading the profiles to name the dominant cost and file the
right follow-ups. There is no scientific-match iteration as there was for the gate, which keeps it
below "large".
