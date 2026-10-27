---
status: completed
created: 2026-10-26
closed: 2026-10-26
tracker: [186697ce-3498-4aef-806d-9b6fc57cfce8]
---

# Plan: a cell-type-level rate model of the MaleCNS connectome on dense matmul

**Tracker task:** [186697ce-3498-4aef-806d-9b6fc57cfce8] "Cell-type-level rate model of the MaleCNS
connectome on dense matmul". Release: Common 0.76.

**Related:** `docs/plans/CONNECTOME_MODEL_ORGANISM.md` (Phase 1 task 9);
`docs/plans/PLAN-20261025-simulation-loop-owner.md` (the blocker, completed);
`docs/internals/simulation-loop-examples.md` (the loop owner's contract).

## Motivation

The neuron-level MaleCNS model cannot run until the sparse matrix–vector product exists, because a
dense 166k × 166k matrix is about 110 GB. Aggregated to its 11,710 cell types, the same connectome
is a dense matrix of about 137 million entries (about 0.5 GB in FP32), which today's `matmul`
handles. A firing-rate model on that matrix is an early, independent route to "the connectome runs
on AR" that needs no sparse capability:

- it exercises the data path from the published tables into collections, and the simulation loop
  owner (`SimulationRunner`), before the sparse work lands;
- it is the starting point for the trainable connectome-constrained rate model of Phase 3 (the
  Lappalainen et al. approach), which will train exactly the parameters this task fixes by hand.

## Where things stand

| Piece | State | Consequence for this task |
|---|---|---|
| Simulation loop owner [6d8bbb62] | **Landed.** `SimulationRunner extends TemporalRunner` in `compute/time` (`org.almostrealism.time`). A system implements `Temporal`; the runner compiles the tick once, dispatches one step per host iteration, advances a device-resident step counter, and offers `record(producer, interval, capacity)`, `addInput` and `addBoundaryListener`. | The model is a `Temporal`; it owns no loop. |
| Step contract | A single operation must not read state it also writes when other elements' values are involved. A coupled update writes a next-state collection, then copies it back in a later operation of the same step. | The rate update reads every type's rate (through `W · r`), so it needs the next-state buffer and copy-back. |
| Measured step cost | `SimulationRunnerDispatchTest`: a dense 1,024 × 1,024 `matmul` step with copy-back costs about 410 µs (native) and 440 µs (Metal). | At 11,710 types the step is bound by reading the matrix (0.55 GB in FP32, 1.1 GB in FP64), not by dispatch. |
| MaleCNS ingest [2218ffdf] | **Open**, with no workstream. It is blocked by the sparse matrix task [66b57639], so it will not land first. | As the task allows: the type-level matrix is written directly by a conversion script. The script is laid out so the ingest task extends it rather than writing a second reader (see Approach, part 1). |
| Integer dtypes in safetensors [3e0043d3] | **Landed.** `SafetensorsReference` reads integer dtypes, and `StateDictionary(String directory)` loads a directory of `.safetensors` files. | The script writes safetensors; Java loads it with no new reader. |
| Source tables | Present in `/Users/Shared/models/male-cns-v1.0/` with `SHA256SUMS`: annotations, neurotransmitters, body stats, connectome weights (the four flat-connectome Feather files). There is no `README.md` there yet, although the connectome plan says there should be one. | Input exists. |
| Python environment | Neither `pyarrow` nor `numpy` is installed for the node's `python3`. CI's `python-tests` job (in `analysis.yaml`) installs `tools/mcp/requirements.txt` (which happens to include `numpy`) and `pyyaml`, not `pyarrow` and not `pytest`, and runs `tools/tests` with `python3 -m unittest discover`. | The conversion needs a virtual environment; CI-run Python tests must be `unittest.TestCase` classes and must not need `pyarrow`. They should not rely on `numpy` either, since it is present only as an MCP-server dependency (see Approach, part 1). |
| Spiking LIF population [04470395] | On an open branch (`LeakyIntegrateAndFirePopulation` in `domain/graph`), not merged. | No dependency. The rate population sits beside it in the same package and follows the same placement rule. |

## Scope

In scope:

1. A Python conversion script under `tools/connectome/` that reads the MaleCNS Feather tables and
   writes a **type-level signed weight matrix** with its type names, type sizes and a set of
   independently computed spot checks.
2. A general **firing-rate population** in `domain/graph`, a `Temporal` whose tick is pure Producer
   math over a dense weight matrix. It carries no connectome or file-format knowledge.
3. A **type-level connectome** type in `engine/ml` that loads the script's output into collections
   and resolves type names to indices.
4. Tests: spot checks of the matrix, the population's dynamics on synthetic data against a closed
   form, a long stability run on the real matrix on Metal and native, the sugar-GRN → MN9 sanity
   check, and recorded time per step.

Out of scope: per-neuron (CSR) ingest [2218ffdf]; any sparse capability; spiking dynamics; training
the model's parameters (Phase 3); a Brian2 comparison (the rate model has no Brian2 counterpart, and
the Phase 1 gate is the spiking model).

## Approach

### 1. Conversion script (`tools/connectome/`)

Two files, so the ingest task can reuse the reader rather than duplicate it:

- `tools/connectome/male_cns.py`: reading and filtering the MaleCNS tables. Loads the Feather files
  with `pyarrow`; keeps bodies that carry a cell type annotation (dropping unannotated fragments,
  as the connectome plan requires); joins each body's predicted neurotransmitter; maps the
  transmitter to a sign with the Shiu et al. rule (acetylcholine +1; GABA and glutamate −1; see
  open question 3 for the others). The stdlib-only parts (the sign table, column-name constants,
  the safetensors header writer) live in functions that take plain Python values, so they can be
  tested without `pyarrow` or `numpy`.
- `tools/connectome/male_cns_cell_types.py`: the command-line entry point. It aggregates
  `W[post, pre] = Σ sign(i) · count(i → j)` over neurons `i` of type `pre` and `j` of type `post`
  (vectorized with `numpy`: map body ids to type indices, then accumulate with `np.add.at` or a
  `bincount` on the flattened `post * T + pre` index), and writes, into an output directory:
  - `cell-type-weights.safetensors` with `weights` (F32, `[T, T]`, **row = postsynaptic type,
    column = presynaptic type**, so the input to the population is `W · r`), `type_sizes` (I32,
    `[T]`, neurons per type) and `type_out_synapses` (I32, `[T]`, total outgoing synapse count,
    used to match control sets);
  - `cell-types.txt`: one type name per line, in index order;
  - `spot-checks.json`: for about 200 `(pre, post)` type pairs (the 100 largest by magnitude, 80
    sampled with a fixed seed, and 20 pairs known to be zero), the value recomputed by a
    deliberately different path: filtering the raw edge table to the member bodies of the two
    types and summing row by row, without the vectorized index mapping;
  - `README.md` and `SHA256SUMS` recording the source files' checksums, the filter, the sign rule,
    `T`, and the script's command line.

  The raw signed counts are stored un-normalized; normalization is a model parameter (part 2), not
  a property of the data.

  The output goes to `/Users/Shared/models/male-cns-v1.0/derived/cell-type-matrix/`. Dependencies are
  listed in `tools/connectome/requirements.txt` (`pyarrow`, `numpy`) and installed in a virtual
  environment; nothing in CI runs the conversion.

  The script also writes the missing `/Users/Shared/models/male-cns-v1.0/README.md` (source URLs and
  the CC-BY 4.0 attribution), if the owner agrees (open question 5).

### 2. `RatePopulation` (`domain/graph`, `org.almostrealism.graph`)

A general population of firing-rate units over a dense, signed coupling matrix:

```
tau · dr/dt = -r + f(g · s ⊙ (W · r) + input)
```

- `W` is `[n, n]`; `s` is an optional per-row scale `[n]` (for this task, `1 / type_size[post]`,
  so a type's input is the mean input per neuron of that type; all ones when absent); `g` is a
  global gain; `input` is an `[n]` collection the caller (or a `SimulationRunner` boundary input)
  writes.
- Applying the row scale after the product (`s ⊙ (W · r)`) is the same as `diag(s) · W · r`, costs
  `n` multiplications per step, and needs no copy of the 0.5 GB matrix.
- `f` defaults to a saturating rectifier, `f(x) = rMax · tanh(rectify(x) / rMax)`, built from
  `rectify` (`ArithmeticFeatures`) and `tanh` (`GeometryFeatures`). The caller may pass another
  `UnaryOperator<CollectionProducer>`.
- Integration is exponential Euler: with `decay = exp(-dt / tau)` (a host constant),
  `r' = decay · r + (1 − decay) · f(...)`. Because `r'` is a convex combination of `r` and a value in
  `[0, rMax]`, the rates stay in `[0, rMax]` for any step size and any `W`. Boundedness is therefore
  a property of the update, which the stability test checks, not an accident of the chosen gain.
  Whether the dynamics settle (rather than oscillate within the bounds) still depends on `g · W`,
  which the stability test also reports.
- `tick()` follows the step contract: one `OperationList` of two operations,
  `a(cp(next), decay · r + (1 − decay) · f(...))` and `a(cp(rates), cp(next))`. Both are Producer
  graphs; there is no host arithmetic, no `evaluate()` and no element access inside the class.
- It implements `Temporal`, `LayerFeatures` and `Destroyable`; it owns `rates`, `next` and `input`,
  and borrows `W` and `s` (the caller manages their lifetime). It has no `Cell`/`Block` suffix and
  is not one: like `LeakyIntegrateAndFirePopulation`, it is a stateful system, not a single-signal
  graph element.

Placement: the connectome plan's placement table puts "spiking and rate neuron population dynamics"
in `domain/graph` with no format knowledge. `domain/graph` reaches `ar-time` (through
`ar-heredity`), so it can implement `Temporal`, and it already supplies `LayerFeatures.matmul`.

### 3. `CellTypeConnectome` (`engine/ml`, package `org.almostrealism.ml.connectome`)

The type-level connectome as data, loaded from the script's output directory:

- `CellTypeConnectome.load(File directory)` reads the safetensors through `StateDictionary` (no new
  reader) and `cell-types.txt`, and checks that the shapes agree with the number of names.
- `getWeights()` (`[T, T]`, post × pre), `getTypeSizes()`, `getOutSynapses()`, `getTypeNames()`,
  `indexOf(String type)`, `indicesMatching(Predicate<String>)`, and
  `inverseTypeSizes()` (a `CollectionProducer` computing `1 / type_size`, for the row scale).
- It implements `Destroyable` and owns the loaded collections.

Placement follows the connectome plan: code that depends on a third-party data layout lives in
`engine/ml` for now, written so it can move as a unit to a future connectome module; nothing else in
`engine/ml` depends on it. It knows the file layout; `RatePopulation` does not.

### 4. Tests

All Java tests extend `TestSuiteBase` and carry `@Test(timeout = ...)`. Module placement follows the
dependency direction: `engine/ml`'s pom depends on `ar-utils`, and `engine/utils`'s pom does not
depend on `ar-ml`. So a test that uses `CellTypeConnectome` cannot live in `engine/utils`. It lives
in `engine/ml`'s test sources, as the existing asset test `GruDecoderAssetTest`
(`org.almostrealism.ml.midi.test`) does.

- **`RatePopulationTest`** (`engine/utils`, package `org.almostrealism.graph.test`), synthetic data,
  runs everywhere in CI:
  - *closed form*: with `f` the identity and a constant input `b`, one step is the linear map
    `r' = A · r + c`, with `A = decay · I + (1 − decay) · g · W` and `c = (1 − decay) · b`. For a
    4 × 4 contracting `W`, the test computes `r_k = A^k · r_0 + (I − A^k)(I − A)^-1 · c` on the host
    and compares it with the state after `k = 5,000` `SimulationRunner` steps. The discrete
    solution is exact, so the tolerance can be tight;
  - *bounds*: a random 512 × 512 `W` with a large gain stays in `[0, rMax]` for 20,000 steps, with no
    NaN;
  - *row scale*: `s ⊙ (W · r)` equals `diag(s) · W · r` built on the host;
  - *orientation*: driving one unit raises exactly the units in its column of `W`.
- **`CellTypeConnectomeTest`** (`engine/ml`, package `org.almostrealism.ml.connectome.test`), real
  data:
  - every entry in `spot-checks.json` equals the loaded `W[post, pre]` exactly (signed counts are
    integers, and FP32 holds them exactly only below 2^24. The script asserts `max |W| < 2^24`
    before writing, so that this exactness is checked rather than assumed);
  - `matmul(W, e_k)` evaluated on the device equals column `k` of the loaded matrix for a few `k`,
    including the last column. This is the early check that a 137-million-element operand is
    addressed correctly on Metal (beyond 2^24 elements; see open question 6).
- **`CellTypeRateModelTest`** (`engine/ml`, same package; it needs both `CellTypeConnectome` and
  `RatePopulation`, and only `engine/ml` reaches both), real data, on Metal and native:
  - *stability*: `RatePopulation` on the MaleCNS matrix with the row scale, run for a long
    simulated duration (proposed: 10 s at `dt = 1 ms`, `tau = 20 ms`, so 10,000 steps), recording all
    rates every 100 steps with `SimulationRunner.record`; after the run, assert every sample is
    finite and in `[0, rMax]`, and log the late-window change to show whether the rates settled;
  - *sugar GRN → MN9*: three runs from the same initial state with the same gain: no drive; constant
    drive into the sugar-sensing GRN types; the same drive into a matched control set (the same
    number of sensory types with total outgoing synapse counts within ±20% of each GRN type's,
    chosen with a fixed seed, excluding gustatory types). Assert that the MN9 rate rise under GRN
    drive exceeds the rise under control drive. The drive is written into the population's `input`
    collection before the run;
  - *time per step*: log `perStepMicros=` for 1,000 timed steps after a warm-up, for each backend,
    in the same style as `SimulationRunnerDispatchTest`.

  The measured times per step are recorded in the Javadoc of `CellTypeRateModelTest` and in the
  connectome section of a durable document (open question 7), with node, backend and precision.
- **Python**: `tools/tests/test_male_cns.py` covers the stdlib-only pieces (sign table, safetensors
  header layout) with no `numpy`/`pyarrow`, so it runs in CI's `python-tests` job. That job runs
  `unittest discover`, so the tests are `unittest.TestCase` classes (bare pytest-style functions
  would not be collected). They import `male_cns` by inserting `tools/connectome` into `sys.path`,
  as other tests under `tools/tests` do for `tools/ci`. `male_cns.py` must therefore import
  `pyarrow` and `numpy` lazily, inside the functions that use them. The vectorized
  aggregation is verified end to end by the spot checks above.

## Success criteria

The task's "done when", made checkable:

1. **Matrix matches the per-neuron data on spot checks.** `CellTypeConnectomeTest` passes: all
   ~200 spot checks (computed from the raw edge table by an independent path) equal the loaded
   matrix, and device `matmul` columns match host columns.
2. **Stable for a long simulated duration with bounded rates, on Metal and native.**
   `CellTypeRateModelTest` stability passes under `AR_HARDWARE_DRIVER=mtl` and `native`, with every
   recorded rate finite and in `[0, rMax]` over 10 s simulated. `RatePopulationTest` passes in CI.
3. **Qualitative check.** Driving the sugar-sensing GRN types raises MN9's rate more than driving
   the matched control set does, on both backends.
4. **Time per step recorded** for Metal and native, in the test's log and in the documentation.
5. Build validator clean (checkstyle, code policy, test timeouts, duplicate code).

## Dependencies

- [6d8bbb62] simulation loop owner: landed.
- `/Users/Shared/models/male-cns-v1.0/`: present. The real-data tests need this directory, so they
  run on the Mac nodes that mount it.
- A Python virtual environment with `pyarrow` and `numpy` to run the conversion once.
- Metal, for criterion 2 and 3 on Metal. See the workstream settings below.

## Open questions

1. **Missing data in CI.** The real-data tests cannot run where `/Users/Shared/models` is absent.
   The existing precedent (`GruDecoderAssetTest`) skips with `Assume` when its weights are missing,
   but a new asset guard is exactly the shape that `docs/internals/test-skip-gating-prevention.md`
   asks to be decided deliberately. Proposed: the real-data tests are new tests at `@TestDepth(2)`,
   guarded only by the data directory's presence, and the plan's reviewer confirms this is
   acceptable; the synthetic `RatePopulationTest` carries the CI-visible correctness coverage.
2. **Coordination with ingest [2218ffdf].** Proposed: that task adds its CSR writer to
   `tools/connectome/` reusing `male_cns.py` (filter and sign rule), so both outputs agree by
   construction; once it lands, a test can also aggregate the CSR to types in Java and compare with
   this matrix. Should the ingest task's description be updated to say so? (Not edited here: this
   round may change only its own task.)
3. **Sign of other transmitters.** Shiu et al. treat acetylcholine as excitatory and GABA and
   glutamate as inhibitory. MaleCNS also predicts dopamine, serotonin, octopamine, tyramine and
   histamine. Proposed: follow Shiu et al.'s handling exactly (to be read from their code in
   `/Users/Shared/models/flywire-783/Drosophila_brain_model/`) and record the table in the output
   `README.md`; the owner should confirm.
4. **Which types are "sugar-sensing GRNs" and "MN9" in MaleCNS.** Shiu et al. selected FlyWire
   neurons by id. MaleCNS type names differ. Proposed: the implementer lists candidate types from
   `body-annotations` (e.g. names containing `Gr5a`/`Gr64f`/sugar, and `MN9`), records the chosen
   names in the test, and the owner confirms them in review.
5. **Writing to the shared model directory.** The script writes a derived directory and the missing
   top-level `README.md` under `/Users/Shared/models/male-cns-v1.0/`. Is that acceptable, or should
   the derived output live elsewhere?
6. **Large operands on Metal.** `W` has more elements than 2^24. Kernel indexing should be integer,
   but this has not been demonstrated for a 137-million-element operand on Metal. The column check
   in `CellTypeConnectomeTest` is the first thing the implementation runs; if it fails, that is a
   defect to file and fix in the hardware layer, not to work around here.
7. **Where the time per step is documented.** Proposed: a "dense type-level step" row in
   `docs/internals/simulation-loop-examples.md`'s measured-overhead table, since that is where step
   costs are already recorded.
8. **Memory on native.** Native runs in FP64, so `W` is 1.1 GB. The default memory scale (about
   4 GB) should hold it; if not, the test documents the `AR_HARDWARE_MEMORY_SCALE` it needs.

## Outcome

Implemented on 2026-10-26. Where the implementation departs from the plan, or settles an open
question:

- **Output location (question 5).** `/Users/Shared/models/male-cns-v1.0/` is not writable by the
  node's worker account, so the matrix was written to
  `/Users/Shared/models-derived/male-cns/cell-type-matrix/`. The real-data tests read the
  directory from `AR_MALE_CNS_CELL_TYPES` (system property or environment variable) and default to
  that path. The top-level `README.md` of the source directory could not be written for the same
  reason; the derived directory's `README.md` carries the provenance and attribution.
- **Data.** 164,506 typed bodies in 11,751 types (not 11,710); 25,135,616 of the 151,856,684 edges
  join two typed bodies; max |W| = 414,893. The sign comes from `consensus_nt` (fewest `unclear`
  among typed bodies). The script also writes `cell-type-annotations.tsv` (superclass and class of
  each type, by member majority), which the matched control needs.
- **Sign rule (question 3).** Shiu et al.'s connectivity table has only the signs +1 and -1:
  GABA and glutamate are inhibitory and everything else, histamine included, is excitatory. That
  rule is applied as is. The owner should confirm histamine.
- **Sugar GRNs and MN9 (question 4).** MN9 is the MaleCNS type `MN9`. The sugar GRNs are taken to
  be `LB3a`-`LB3d` (labellar bristle, FlyWire type `LB3`), because their multi-hop signed influence
  on MN9 is the most excitatory among the labellar types, while `LB1a`-`LB1d` are inhibitory. The
  owner should confirm this. Result on both backends: MN9 rises to 84.2 under GRN drive and to 47.7
  under the matched control (`GNG642`, `JO-B1_a`, `SAxx01`, `BM_Vt_PoOc`), and stays at 0 with no
  drive.
- **Large operands on Metal (question 6).** No defect. Device `matmul` columns equal host columns,
  the last column included.
- **A defect found in `SimulationRunner`.** The runner compiled its step without
  `Process.optimize()`. As a result the matrix-vector reduction was generated as one unrolled
  expression per row, and Metal rejected the 11,751-row step with "Compute function exceeds
  available stack space". The runner now optimizes each top-level operation of the step on its own,
  which keeps the dispatch contract. `SimulationRunnerTest.largeDenseStep` crashes without the fix
  and passes with it.
- **Time per step (question 7).** Native FP64 14,527 µs and Metal FP32 11,380 µs, recorded in
  `simulation-loop-examples.md` and in `CellTypeRateModelTest`.
- **Missing data (question 1).** As proposed: the real-data tests are new `@TestDepth(2)` tests that
  are skipped where the derived directory is absent.

- **Review (PR #759).** `CellTypeConnectome` and its tests moved from `engine/ml` to
  `studio/bioscience` (package `org.almostrealism.bioscience.connectome`), since a connectome is
  biology-specific and `ar-bioscience` depends on both `ar-biology` and `ar-ml`. The conversion
  scripts and their test moved from `tools/connectome/` and `tools/tests/` to
  `studio/bioscience/scripts/`. `SimulationRunner` now optimizes the flattened step as a whole
  (`Process.optimized(op.flatten())`); for that to keep each operation a separate dispatch,
  `OperationList` segmentation and subdivision now carry the list's `enableCompilation` setting
  into the lists they create instead of re-enabling compilation.

## The machine the work needs

The task requires the model to run on Metal, and only macOS nodes provide Metal. The MaleCNS tables
are also in `/Users/Shared/models`, which the macOS nodes read in place. The settings file
`docs/plans/task-20261026-175758-workstream.yaml` therefore requires `platform: macos`.

## Estimated complexity

**Medium.** Three small classes, one script and four test classes, all on top of existing
capabilities (`matmul`, `SimulationRunner`, safetensors loading). The risks are in the data
(type selection, sign rule) and in the large Metal operand, each of which is checked early.
