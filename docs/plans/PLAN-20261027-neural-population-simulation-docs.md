---
status: proposed
created: 2026-10-27
closed:
tracker: []
---

# Document simulating a spiking neural population, from state to recorded spikes

**Category:** Documentation
**Estimated complexity:** Medium
**Runs on:** any node (no Metal requirement; no workstream settings file)

## Motivation

In one week the platform gained an entire scientific-simulation substrate, built task by task for
Phase 1 of the connectome model organism (`CONNECTOME_MODEL_ORGANISM.md`):

| Capability | Where it lives on `master` | Durable documentation today |
|---|---|---|
| Ordered (compact, masked, permuted) collections as kernel inputs, with absent elements reading as zero and lookup tables delivered as kernel arguments through `TraversalOrdering.bind` | `base/code` `io.almostrealism.collect`, `compute/algebra` `org.almostrealism.collect` | `shape-and-traversal.md` "IndexSet and TraversalOrdering" shows a two-method interface that predates `bind`, `signature`, `getLength` and the absent-element contract. Nothing says an ordered collection may now be the input to any kernel. |
| Integer-exact positions above 2^24 (`SplitIndexTraversalOrdering`) | `compute/algebra` | `dynamic-indexing.md`: good |
| Counter-based in-kernel random numbers (`CounterRandom`, selected by `AR_RANDOM=counter`) | `compute/algebra` `org.almostrealism.collect.computations` | **None.** `AR_RANDOM` and `CounterRandom` are named in no Markdown file. `collection-producer-operations.md` shows `rand`/`randn` without saying that two implementations exist, how they differ (host draw vs in-kernel hash), what `refresh()`/`advance()` mean, or the 2^24 seed/counter limit. |
| The simulation loop owner (`SimulationRunner`) | `compute/time` | `simulation-loop-examples.md`, `compute/time/README.md`: good, but its only worked system is an oscillator bank |
| A leaky integrate-and-fire population (`LeakyIntegrateAndFirePopulation`): exact-exponential membrane and conductance, refractory countdown, spike ring buffer for synaptic delay, Poisson drive | `domain/graph` `org.almostrealism.graph` | **None** outside its Javadoc. `domain/graph/README.md` does not mention it. |
| The biology modules (`domain/biology`, `studio/bioscience`) and the PDSL core's move to `domain/graph` (`org.almostrealism.dsl`, `PdslExtension`, `WeightGroup`) | `domain/biology`, `studio/bioscience`, `domain/graph` | The module READMEs exist. `llms.txt` lists neither module and still describes `ar-graph` without PDSL. `domain/graph/README.md` never mentions PDSL, `WeightGroup` or the population classes. |

Each piece has thorough Javadoc, and each plan that produced it has been or will be swept away.
What is missing is the page that **puts them together**: how a reader (human or model) goes from
"I want to simulate a population of spiking neurons" to a compiled step dispatched by
`SimulationRunner`, with reproducible device-side Poisson drive, synaptic delay, connectivity, and a
device-resident spike recording read back after the run. Today that knowledge is spread across
five Javadoc headers and the test classes, and the one example of driving a LIF population
(`LeakyIntegrateAndFirePopulationTest`) steps it with a hand-written loop, because the population
is not a `Temporal`. An agent asked to write the next step of the connectome track (the MaleCNS
ingest, the Shiu et al. reproduction) will learn the wrong pattern from the only example it can
find.

This is the same move the 2026-10-26 cycle made for PDSL: the *reference* material is good, the
*connective* material that teaches how the pieces compose is absent. It also serves the
self-understanding aim directly. Biology is the newest domain the platform reaches into, and the
page that explains it is the page a model trained on this corpus needs in order to reason about,
and later extend, the platform's simulations of living systems.

## Scope

### 1. New internals page: `docs/internals/neural-population-simulation.md`

A single page that walks one example from start to finish, in the register of
`training-a-language-model.md` and `automatic-differentiation.md`. Sections, in order:

1. **What the platform simulates and where each part lives.** The module map for a simulation:
   population dynamics in `domain/graph`, the loop owner in `compute/time`, random numbers and
   sparse structure in `compute/algebra`, biological models in `domain/biology`, models that need
   the ML engine or third-party data in `studio/bioscience`. State the placement rule from the
   module READMEs (format-dependent code goes in a higher module).
2. **State is device-resident collections.** The population's state collections, their shapes,
   and why the step is a list of assignments over them (one compiled operation, re-dispatched;
   no host reads inside the run). Link `host-device-write-policy.md`.
3. **One step of a LIF population.** The ordered assignments of
   `LeakyIntegrateAndFirePopulation.step`, the exact-exponential integration and why it gives the
   analytic LIF rate, threshold and reset, the refractory countdown. Show the equations; cite the
   class and method, never line numbers.
4. **Synaptic delay through the ring buffer.** `delayedSpikes()`, why the ring has
   `delaySteps + 1` rows, and why the read and write order within a step therefore cannot change
   the delay.
5. **Connectivity.** The synaptic input is a `Producer` argument to `step`, so the population is
   indifferent to how it is formed: a dense `matmul` of a weight matrix and the delayed spikes
   (show it), and, if `SparseMatrix` has landed on `master` when this work is done, a
   `SparseMatrix.multiply` of the same (show it, and link `dynamic-indexing.md`'s sparse section).
   If it has not landed, state that the sparse product is the connectome-scale form and that a
   dense 166k x 166k matrix is about 110 GB in FP32. Do not document an unmerged API.
6. **Reproducible random drive.** A full section on random generation, which no page covers:
   `RandomProducer` as one concept with two implementations chosen once by `AR_RANDOM`
   (`java`, the default: host draw, delivered as data; `counter`: `CounterRandom`, a PCG hash of
   seed, counter and element index computed in the kernel); `refresh()` vs the device-side
   `advance()`; that the population owns its own `CounterRandom` and advances it inside the step
   regardless of `AR_RANDOM`; uniform output bit-identical across backends, normal output only to
   backend precision; the 2^24 bound on seed and counter on FP32 backends and what it means in
   simulated steps. Poisson drive as `uniform < rate * dt`, masked to an input set.
7. **Driving it with `SimulationRunner`.** The population exposes `step(Producer)` rather than
   implementing `Temporal`, so show the adapter (`Temporal network = () -> pop.step(input);`) and
   a `SimulationRunner` that runs it, with `record(...)` capturing the spike vector into a
   device-resident buffer read after `run` returns. Show the wrong pattern next to it (a host
   `for` loop calling `step.run()` and reading `toDouble` per step) and say why it is wrong, with
   a link to `simulation-loop-examples.md`. Note the lifecycle order: destroy the runner (which
   releases the compiled step) before the population.
8. **Firing-rate populations** — only if `RatePopulation` has landed on `master`: a short section
   contrasting it (a `Temporal` with a next-state buffer and copy-back) with the spiking
   population, and when to use each. If it has not landed, omit the section entirely.
9. **Known limits.** Evidenced facts only, each with the class that establishes it: no surrogate
   gradients or backpropagation through time (so spiking dynamics are forward-only); the
   per-step global synchronization and its measured dispatch cost (link the measured-overhead
   table, do not copy it); the 2^24 counter bound; spike-for-spike parity with Brian2 not yet
   established.
10. **Related files** table (paths, no line numbers) and **See also** links.

### 2. Drift guard: `NeuralPopulationSimulationExamplesTest`

In `engine/utils/src/test/java/org/almostrealism/graph/test/`, extending `TestSuiteBase`, every
`@Test` with a `timeout`, modeled on `AutomaticDifferentiationExamplesTest` (which pins the
autodiff page's contract example). It runs **the page's own worked example** and asserts its
claims, so the page cannot silently drift from the code:

- a small network (for example an input population of a few neurons under masked Poisson drive,
  connected by a dense weight matrix to an output population) stepped by `SimulationRunner`
  through the adapter the page shows, with the spike vector recorded;
- the driven neurons' measured rate matches `rate` within a binomial tolerance derived from the
  number of steps, stated in the test (not tuned);
- undriven input neurons never fire;
- the output population's first spike comes no earlier than `delaySteps` after the first input
  spike;
- two runs with the same seed record identical spike rasters, and a different seed records a
  different one.

The test must exercise exactly the code the page shows. If a claim on the page cannot be pinned
(for example a measured number), the page states where the number comes from instead.

### 3. Corrections and cross-links to existing documentation

- `docs/internals/shape-and-traversal.md`, "IndexSet and TraversalOrdering": bring the interface
  sketch up to date (`bind`, `signature`, `getLength`, `compose`), state the absent-element
  contract (a negative position reads as zero on the host and in generated code), list the
  orderings that ship (`RepeatTraversalOrdering`, `ExplicitIndexTraversalOrdering`,
  `IndexMaskTraversalOrdering` and its per-lookup cost, `SplitIndexTraversalOrdering`), and state
  that an ordered collection may be the input to any kernel (the subject of
  `OrderedCollectionInputTests`) while a computation's own output never carries an ordering
  (`CollectionProducerComputationBase` strips it). Link `dynamic-indexing.md` for split indices.
- `docs/internals/collection-producer-operations.md`: one sentence under `rand()`/`randn()`
  pointing to the new page's random-generation section and naming `AR_RANDOM`.
- `domain/graph/README.md`: add short sections for the PDSL language core
  (`org.almostrealism.dsl`, `PdslExtension`, `WeightGroup`, linking `pdsl-language.md` rather
  than repeating it) and for population dynamics (`LeakyIntegrateAndFirePopulation`, and
  `RatePopulation` if landed), linking the new page.
- `domain/biology/README.md`: link the new page as the account of how biological dynamics are
  expressed and stepped today.
- `llms.txt`: add the new page and the existing `simulation-loop-examples.md` (currently absent)
  under the internals index; add `domain/biology` and `studio/bioscience` to the module lists;
  update the `ar-graph` line to mention the PDSL core and population dynamics.
- `simulation-loop-examples.md`: one "See also" link to the new page. Do not edit its measured
  overhead section (an open branch is extending it).

## Approach

1. **Re-read `master` first.** Two related branches were open when this plan was written (the
   sparse matrix–vector product and the cell-type rate model). Check which have merged; sections
   5 and 8 depend on it. Document only what is on `master`.
2. **Derive every statement from source.** Read `LeakyIntegrateAndFirePopulation`,
   `CounterRandom`, `RandomProducer`, `Random`, `SimulationRunner`, `TraversalOrdering` and its
   implementations, and their tests, in full before writing. Where the page states a number
   (a limit, a tolerance, a cost), cite the constant or test that establishes it.
3. **Write the test before polishing the prose.** Bring up `NeuralPopulationSimulationExamplesTest`
   with the exact example code, run it with `mcp__ar-test-runner__start_test_run`
   (`module: engine/utils`, `test_classes: ["NeuralPopulationSimulationExamplesTest"]`), and paste
   the code that passes into the page. If the adapter lambda does not run correctly under
   `SimulationRunner` (for example because of how the runner flattens and dispatches the step),
   that is a finding: record it with evidence in the page's known limits and as a tracker task
   ("make `LeakyIntegrateAndFirePopulation` a `Temporal`"), and show the pattern that does work.
   Do not change production code in this task.
4. **Run the existing tests the page leans on**, unchanged, to confirm the claims it repeats:
   `LeakyIntegrateAndFirePopulationTest`, `CounterRandomTests`, `SimulationRunnerTest`,
   `OrderedCollectionInputTests` (at most five classes per invocation).
5. **Validate**: `mcp__ar-build-validator__start_validation` (default checks) must pass, and
   `python3 -m unittest tools.tests.test_sweep_plans` must pass for the plan headers.
6. Language rules for the page: no source line numbers; reference by class and method; no
   reference to any file in `docs/plans/` from the page, the READMEs or the test.

## Success Criteria

1. `docs/internals/neural-population-simulation.md` exists and covers sections 1–10 above, with
   every code block either run by the drift-guard test or explicitly marked as a fragment.
2. `AR_RANDOM` and `CounterRandom` are documented in durable Markdown (the new page), and
   `collection-producer-operations.md` points to it.
3. `NeuralPopulationSimulationExamplesTest` passes, runs the page's example through
   `SimulationRunner` (no hand-written step loop), and asserts the rate, silence, delay and
   reproducibility claims with tolerances derived in the test.
4. `shape-and-traversal.md` describes the current `TraversalOrdering` contract: absent elements,
   kernel binding, signatures, the shipped orderings, and ordered collections as kernel inputs.
5. `llms.txt` lists the new page, `simulation-loop-examples.md`, `domain/biology` and
   `studio/bioscience`; `domain/graph/README.md` mentions the PDSL core and population dynamics.
6. Build validator passes; no existing test is modified.
7. Anything the work shows to be wrong or awkward in the code (for example the population not
   being a `Temporal`) is filed as a tracker task with evidence, not fixed here.

## Dependencies

- None blocking. Everything documented is on `master`.
- Soft: sections 5 and 8 widen if the sparse-matrix and rate-model branches merge first. The
  page is correct without them.

## What this sets up

- The connectome track's next agents (MaleCNS ingest into CSR form, the Shiu et al. reproduction,
  the throughput benchmark) start from a correct worked example instead of a test's manual loop.
- A follow-up that makes the spiking population a `Temporal` (if the adapter proves awkward).
- A worked, tested page about simulating living systems for the self-hosted LM's corpus.
