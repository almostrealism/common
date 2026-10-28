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
| The biology modules (`domain/biology`, `studio/bioscience`) and the PDSL core's move to `domain/graph` (package `org.almostrealism.dsl`, including `PdslExtension`; `WeightGroup` is in `org.almostrealism.model`) | `domain/biology`, `studio/bioscience`, `domain/graph` | The module READMEs exist. `llms.txt` lists neither module and still describes `ar-graph` without PDSL. `domain/graph/README.md` never mentions PDSL, `WeightGroup` or the population classes. |

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
   `SparseMatrix.multiply` of the same (show it, and link the sparse section that branch adds to
   `dynamic-indexing.md`; `master` has no such section today).
   If it has not landed, state that the sparse product is the connectome-scale form and that a
   dense 166k x 166k matrix is about 110 GB in FP32. Do not document an unmerged API.
6. **Reproducible random drive.** A full section on random generation, which no page covers:
   `RandomProducer` as one concept with two implementations chosen once by `AR_RANDOM`
   (`java`, the default: host draw, delivered as data; `counter`: `CounterRandom`, a PCG hash of
   seed, counter and element index computed in the kernel); `refresh()`, the one method of
   `RandomProducer` that moves a generator on (for `Random` it discards the host-drawn values so
   the next evaluation draws new ones; for `CounterRandom` it runs a compiled counter increment),
   vs `CounterRandom.advance()` and `resetCounter()`, which exist only on `CounterRandom` and
   return the increment and reset as uncompiled `Assignment`s for a caller to compose into its
   own step; that the population owns its own `CounterRandom` and advances it inside the step
   regardless of `AR_RANDOM`; uniform output bit-identical across backends, normal output only to
   backend precision; the 2^24 bound on seed and counter on FP32 backends and what it means in
   simulated steps. Poisson drive as `uniform < rate * dt`, masked to an input set. State
   that the drive is combined with the threshold spike by `max` in `emittedSpikes()` without
   consulting the refractory countdown, so a driven neuron with no other input fires a
   Bernoulli(`rate * dt`) spike every step, refractory or not, and that `rate * dt` above 1
   simply fires every step (`setPoissonDrive` rejects only a negative rate).
7. **Driving it with `SimulationRunner`.** The population exposes `step(Producer)` rather than
   implementing `Temporal`, so show the adapter (`Temporal network = () -> pop.step(input);`) and
   a `SimulationRunner` that runs it, with `record(...)` capturing the spike vector into a
   device-resident buffer read after `run` returns. Show the wrong pattern next to it (a host
   `for` loop calling `step.run()` and reading `toDouble` per step) and say why it is wrong, with
   a link to `simulation-loop-examples.md`. Note the lifecycle order: destroy the runner (which
   releases the compiled step) before the population. The section must also state, from the
   code:
   - **How the runner dispatches the step.** `SimulationRunner` flattens the system's tick
     (`OperationList.flatten()` inlines a nested list that carries no compute requirements, and
     the `"lif-step"` list carries none), so each assignment of `step` becomes its own dispatch,
     plus the runner's counter advance. The direct `step(...).get()` path that
     `LeakyIntegrateAndFirePopulation`'s Javadoc and its test use is, by a reading of
     `OperationList.get()`, *not* one kernel either: a list is compiled as a single kernel only
     when it is uniform (or `OperationList.enableNonUniformCompilation`, default `false`, is set),
     and the `lif-step` list mixes parallelisms (`count` for the state assignments,
     `rows * count` for the ring write, 1 for the write-index and counter advances), so it falls
     through to an `OperationListRunner` that dispatches each operation separately (logging
     "OperationList was not compiled"). The writer must confirm this with an operation profile
     (`ar-profile-analyzer`) rather than repeat it. If it holds, the two paths have the same
     per-assignment dispatch pattern, and the page should say so instead of contrasting them.
     It should also point to the measured per-dispatch cost rather than restate it.
     The LIF step satisfies the runner's step contract because every assignment is element-local
     (the coupled read, the `matmul` over `delayedSpikes()`, reads the ring, which the same
     operation does not write). The open rate-model branch changes `SimulationRunner` to optimize
     the flattened step (`Process.optimized(...)`) before compiling it, keeping each top-level
     operation a separate dispatch; if that has merged, describe the merged behaviour.
   - **Two populations in one tick.** A network of an input and an output population needs a
     tick that adds both steps to one `OperationList`, not the single-population lambda above.
     The order of the two steps matters: see open question 1.
   - **Ordering constraints of the population API.** `setPoissonDrive` must be called before
     `step(...)` is built (the step captures the generator; `setPoissonDrive` replaces it), and
     the seed is fixed by `setPoissonDrive`, so a run with a different seed needs a
     `setPoissonDrive` call followed by a new runner, or a new population. Because the
     adapter's `tick()` is called once, when the `SimulationRunner` is constructed
     (`SimulationRunner.stepOperations`), "before `step(...)` is built" means before the runner
     is constructed. `setPoissonDrive` replaces the previous `CounterRandom` without destroying
     it (see open question 5). A repeat run with the *same* seed needs no new runner:
     `runner.reset()` followed by the population's own `reset()` (which also resets the
     Poisson counter) restores the initial condition.
   - **Recording cost.** A `Recording` write is a slot update over the whole buffer, so its cost
     is proportional to `capacity * width` (`SimulationRunner.Recording` Javadoc); recording the
     spike vector every step for `n` steps therefore costs `O(n^2 * width)` in total, and
     `capacity * width` must stay within 2^24 on FP32 backends. The page should give the reader
     that arithmetic and recommend a recording interval or a reduced quantity (for example a
     per-step spike count) for long runs.
8. **Firing-rate populations** — only if `RatePopulation` has landed on `master`: a short section
   contrasting it (a `Temporal` with a next-state buffer and copy-back) with the spiking
   population, and when to use each. If it has not landed, omit the section entirely.
9. **Known limits.** Evidenced facts only, each with the class that establishes it: no surrogate
   gradients or backpropagation through time (so spiking dynamics are forward-only; the evidence
   is that the spike is a `greaterThanOrEqual` comparison in
   `LeakyIntegrateAndFirePopulation.emittedSpikes()` and that no class in the repository defines
   a surrogate gradient, which the writer must re-check rather than repeat); the
   per-step global synchronization and its measured dispatch cost (link the measured-overhead
   table, do not copy it); the 2^24 bound on the `CounterRandom` counter (`CounterRandom.SEED_LIMIT`
   bounds the seed, and the class Javadoc states the same bound for the counter), on the
   `SimulationRunner` step counter, and on a `Recording`'s `capacity * width`; spike-for-spike
   parity with Brian2 not yet established (the `LeakyIntegrateAndFirePopulationTest` class Javadoc
   says so). The population is not a `Temporal`, `Setup` or `Lifecycle`, so
   `SimulationRunner.reset()` does not reset its state; a second simulation calls the
   population's own `reset()` as well.
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
  number of steps, stated in the test (not tuned). The binomial model is exact only because the
  drive ignores the refractory countdown (section 6) and the input population receives no
  synaptic input, so each driven neuron spikes independently with probability `rate * dt` per
  step; the test should say so, and use a `rate * dt` well below 1;
- undriven input neurons never fire;
- an input spike reaches the output population exactly `delaySteps` steps later (assert the
  step at which the output conductance first becomes non-zero, as
  `LeakyIntegrateAndFirePopulationTest.synapticDelayDeliversWeightedInputAfterDelay` does for one
  population, rather than only "no earlier than"; a lower bound would not catch the off-by-one
  described in open question 1);
- two runs with the same seed record identical spike rasters, and a different seed records a
  different one.

The test must exercise exactly the code the page shows. If a claim on the page cannot be pinned
(for example a measured number), the page states where the number comes from instead.

### 3. Corrections and cross-links to existing documentation

- `docs/internals/shape-and-traversal.md`, "IndexSet and TraversalOrdering": bring the interface
  sketch up to date (`bind`, `signature`, `getLength`, `compose`), state the absent-element
  contract (a negative position reads as zero on the host and in generated code), and the rule
  that an ordering which reads a collection returns a `null` signature so its kernel is never
  reused. List the orderings that ship: in `base/code`, `DefaultTraversalOrdering` (identity),
  `RepeatTraversalOrdering`, `IndexExpressionTraversalOrdering` and `DelegatedTraversalOrdering`
  (what `compose` returns); in `compute/algebra`, `ExplicitIndexTraversalOrdering`,
  `IndexMaskTraversalOrdering` (its lookup has no closed form, so each lookup costs one
  comparison per mask entry, which suits small masks and not a large sparse structure) and `SplitIndexTraversalOrdering`. The current sketch also
  omits that `TraversalOrdering` extends `Signature` as well as `IndexSet`. State
  that an ordered collection may be the input to any kernel (the subject of
  `OrderedCollectionInputTests`) while a computation's own output never carries an ordering
  (`CollectionProducerComputationBase` strips it). Link `dynamic-indexing.md` for split indices.
- `docs/internals/collection-producer-operations.md`: one sentence under `rand()`/`randn()`
  pointing to the new page's random-generation section and naming `AR_RANDOM`.
- `domain/graph/README.md`: add short sections for the PDSL language core
  (`org.almostrealism.dsl`, `PdslExtension`, and `org.almostrealism.model.WeightGroup`, linking `pdsl-language.md` rather
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
   `python3 -m unittest tools.tests.test_sweep_plans` must pass for the plan headers. (When this
   plan was written that test failed on `master` because
   `PLAN-20261025-spiking-lif-population.md` had no lifecycle header; this branch adds the same
   header the open rate-model branch adds, so the two merge cleanly.)
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

## Open questions

Raised in review of this plan, for the person deciding whether it goes ahead.

1. **Delay across two populations depends on the order of their steps.** `delayedSpikes()` reads
   row `(writeIndex - delaySteps) mod rows`, and the population's Javadoc guarantees a delay of
   exactly `delaySteps` with respect to *its own* step. In a network tick that steps the input
   population before the output population, the output's synaptic input reads the input's ring
   after the input has already advanced its write index, so the effective delay becomes
   `delaySteps - 1`; stepping the output first gives `delaySteps`. The page must state which order
   it uses and why, and the drift guard must pin the exact delay (Scope section 2). If the writer
   finds this too fragile to teach, that is evidence for the `Temporal` follow-up below rather
   than something to paper over in prose.
2. **Document the adapter, or fix the type first?** The plan teaches a lambda adapter
   (`() -> pop.step(input)`) around a class whose own Javadoc says the loop belongs to a separate
   owner but which does not implement `Temporal`. The adapter also leaves the population's reset
   outside `SimulationRunner.reset()` (the runner resets only a system that is a `Lifecycle`) and
   its setup outside the runner's setup. A small production change (make the population a
   `Temporal`, or provide one) would let the page show the same pattern as
   `simulation-loop-examples.md` with no caveats. This plan defers that to a tracker task; the
   approver may prefer to sequence that task first and write the page once.
3. **Open branches change sections 5, 7 and 8.** At review time `SparseMatrix` and
   `SegmentedWeightedSumComputation` were on an open branch (not on `master`), and `RatePopulation`,
   `CellTypeConnectome` and a `SimulationRunner` change (optimizing the flattened step before
   compiling it) were on another. If the page is written before they merge, it will need a second
   pass when they do. Writing it after both merge avoids that, at the cost of delay.
4. **Scope.** The deliverables span one new page, one new test, and edits to two internals pages,
   `llms.txt` and two module READMEs. Splitting the cross-link corrections (Scope section 3,
   which do not depend on the new page except for its link targets) from the page and test
   would let the `shape-and-traversal.md` and `llms.txt` corrections, which are stale today,
   land on their own.

5. **Two lifecycle gaps the page will have to describe.** Both are in production code this plan
   does not touch, and both affect what the page tells a reader to do:
   - `setPoissonDrive` assigns a new `CounterRandom` to the population's `poisson` field without
     destroying the previous one, so calling it twice (for example to rerun with a different
     seed, as section 7 describes) leaks the first generator's seed and counter collections.
     `destroy()` releases only the current generator.
   - Destroying the runner destroys the compiled step and its operations, and destroying a
     computation recursively destroys its input producers (`ComputableBase.destroy`,
     `OperationAdapter.destroy`). Whether that reaches the population's state collections
     through `cp(...)` (a `CollectionProviderProducer`, which does not override `destroy`) has
     not been checked, so the page's "destroy the runner, then the population" order must be
     confirmed by the drift guard (destroy in that order without error) rather than asserted.
   The approver may want the first filed as a tracker task alongside the `Temporal` follow-up.
6. **The plan's dispatch description is unverified.** Section 7 contrasts runner dispatch with
   `step(...).get()`, but a reading of `OperationList.get()` suggests the non-uniform `lif-step`
   list is not compiled to one kernel on either path (see section 7). If a profile confirms
   that, the Javadoc of `LeakyIntegrateAndFirePopulation` ("compiles it once ... and runs the
   resulting operation") is not wrong, but it invites a single-kernel reading; correcting that
   Javadoc is outside `docs/` and would be a separate change.

## Existing work this plan builds on

- `PLAN-20261025-spiking-lif-population.md` (status `in-progress`) delivered
  `LeakyIntegrateAndFirePopulation` and its test; its Brian2 parity criterion is still open, so
  the page must not claim parity. This plan does not duplicate any of its deliverables.
- `PLAN-20261025-simulation-loop-owner.md` delivered `SimulationRunner`,
  `simulation-loop-examples.md` and the `compute/time/README.md` section, which this page links
  rather than repeats.
- `PLAN-20261023-counter-based-random.md` delivered `CounterRandom`/`RandomProducer`; its
  durable documentation is Javadoc only, which is the gap section 6 fills.
- The open sparse-matrix branch adds a "Gather with a Segmented Reduction (Sparse
  Matrix–Vector Product)" section to `dynamic-indexing.md`; the open rate-model branch adds a
  connectome-scale row and a paragraph on `Process.optimized(op.flatten())` to
  `simulation-loop-examples.md`'s measured-overhead section. Section 3's "do not edit the
  measured overhead section" is because of the latter.

## What this sets up

- The connectome track's next agents (MaleCNS ingest into CSR form, the Shiu et al. reproduction,
  the throughput benchmark) start from a correct worked example instead of a test's manual loop.
- A follow-up that makes the spiking population a `Temporal` (if the adapter proves awkward).
- A worked, tested page about simulating living systems for the self-hosted LM's corpus.
