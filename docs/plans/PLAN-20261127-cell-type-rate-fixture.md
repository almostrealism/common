---
status: in-progress
created: 2026-11-27
closed:
tracker: [748c1c4a-1b70-45f5-85d3-eb8e8e4911f4]
---

# A pipeline-profile rate-model test on a committed MaleCNS cell-type sub-matrix

**Tracker task:** `748c1c4a-1b70-45f5-85d3-eb8e8e4911f4`, "Give the cell-type rate model a
scaled-down CI test on a real MaleCNS cell-type sub-matrix". **Release:** the one under development
(its version is the root `pom.xml`'s).

## Implementation status

Implemented on this branch. What the measurements decided:

- **Active set (open question 1).** The full-matrix run activates 9,471 types (GRN run) and 9,779
  (control run) of 11,751, so the exact cut is impossible within the size budget.
- **Fallback selection.** The top-K-by-peak fallback below does not preserve MN9's response: at
  250 types MN9 reaches 49.36 (GRN) against 49.41 (control), losing the margin (full matrix 84.25
  against 47.68). It was replaced by an influence ranking that stays within the budget and leaves
  the constants and the 1,000 steps unchanged: the driven types and MN9 are kept, and the other
  places go to the active types with the largest product of their rate and their normalized
  coupling reach to MN9 over two upstream hops. At 250 types MN9 reaches 78.74 (GRN) against 52.75
  (control), a margin of 25.99 against 36.57 on the full matrix. The whole-vector deviation from
  the full run is large (up to about 96 for some kept types), so the pipeline test's expectations
  are the cut's own FP64 rates, and the deviation is recorded in `rate-reference.json` and the
  fixture README.
- **Control set (open question 2).** The script ports `java.util.Random` and the candidate order,
  so the reference and the fixture use exactly the set `CellTypeRateModelTest.matchedControl`
  draws (`GNG642`, `JO-B1_a`, `SAxx01`, `BM_Vt_PoOc`).
- **Precision.** On the fixture the largest difference from the FP64 expectations is 1.1e-13 on
  native FP64 and 1.24e-4 in FP32 (native and Metal).
- **Reference location.** The shared derived-data tree is not writable by the job account, so the
  reference was written elsewhere and passed to `matchesRateReference` through
  `AR_MALE_CNS_CELL_TYPE_RATES`; it still needs copying to the default directory.
- **Fixture README.** It is generated (and checksummed) by the script, so the platform's measured
  full-matrix agreement is recorded in the module README and the test javadoc instead.

## Motivation

`CellTypeRateModelTest` (studio/bioscience) runs a `RatePopulation` with one unit per cell type over
the 11,751-type MaleCNS cell-type matrix. It covers stability, the sugar GRN to MN9 response against a
matched control, and the time per step. Both it and `CellTypeConnectomeTest` read the derived matrix
from `/Users/Shared/models-derived/male-cns/cell-type-matrix` (`AR_MALE_CNS_CELL_TYPES`). That is a
552 MB safetensors file that no CI runner has. Both classes are therefore
`@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)`, and they fail rather than
skip when the data is missing. For the rate model, CI runs only the data-free
`CellTypeConnectomeLoadTest`, which loads a synthetic directory and never steps a population on real
connectivity.

The spiking network already solved this problem. `brian2_reference.py circuit` cuts the FlyWire
sugar circuit (the neurons that fire in the full Brian2 sugar experiment) out of the source tables
into a committed fixture (`src/test/resources/connectome/sugar-circuit`, about 200 KB). It records
the reference run beside it, and `ConnectomeNetworkTest` runs on that fixture in CI. This plan gives
the rate model the same arrangement:

- a real but small MaleCNS cell-type sub-matrix, cut by `male_cns_cell_types.py` and checked by its
  spot-check path;
- a pipeline-profile test that runs `RatePopulation` on it and checks the sugar to MN9 response
  against the control;
- expectations that can be traced to the full-matrix run.

## Scope

In scope:

1. **A full-matrix rate reference.** A reproducible run of the sugar experiment on the whole
   11,751-type matrix records every type's rate. The experiment is three runs: no drive, the sugar
   GRNs, and the matched control. The record is written to the derived-data tree, not the repository.
2. **The cut.** `male_cns_cell_types.py` gains a mode that writes the cell-type matrix restricted to
   the types active in that reference. The output uses the same layout `CellTypeConnectome.load`
   reads, its spot checks are recomputed from the raw edge table by the existing `recompute` path,
   and a manifest records the expected rates traced to the full run.
3. **The committed fixture** under `studio/bioscience/src/test/resources/connectome/cell-type-sugar-circuit/`,
   a few hundred KB at most.
4. **A new pipeline-profile test class** in `org.almostrealism.bioscience.connectome.test` that
   loads the fixture, checks it against its spot checks, and runs the sugar/control/baseline
   experiment through `RatePopulation` and `SimulationRunner`. It asserts the expectations from the
   manifest.
5. **Closing the trace on the full matrix.** A new long-running, pipeline-excluded method in
   `CellTypeRateModelTest` runs the manifest's experiment on the full matrix with the platform and
   requires agreement with the reference. The "full-matrix result" the fixture's expectations come
   from is then the platform's own result as well as the script's.
6. **Python unit tests** for the new script code, and documentation of the fixture and its
   regeneration command.

Out of scope:

- Any change to `RatePopulation`, `CellTypeConnectome` or `SimulationRunner`.
- Any change to the existing `CellTypeRateModelTest` or `CellTypeConnectomeTest` methods. They stay
  long-running and pipeline-excluded, and are only added to.
- CI workflow changes. `studio/bioscience` is already run by every test lane: the
  `Run Bioscience Tests` steps in `.github/workflows/analysis.yaml` (`test-media`, `test-media-mac`,
  `test-media-cl`, `test-media-cuda`), all with `-DAR_TEST_PROFILE=pipeline`. A new test class in
  the module runs there with no wiring.

## Approach

### 1. The full-matrix reference (`male_cns_cell_types.py rates`)

The cut follows the sugar circuit's rule ("the neurons that fire in the full experiment"), so it
needs each type's rate over the full run. That reference is produced by the script that already
owns the cell-type data. A test does not write it: `brian2_reference.py` likewise runs its model
outside the platform and writes the reference that the Java tests then read.

The new `rates` mode reads the converted full matrix (`cell-type-weights.safetensors`,
`cell-types.txt`, `cell-type-annotations.tsv`). It runs the `RatePopulation` equations exactly as
the class javadoc states them, in FP64 NumPy:

    x  = g * s (.) (W r) + input,   f(x) = rMax * tanh(max(x, 0) / rMax)
    r' = decay * r + (1 - decay) * f(x),   decay = exp(-dt / tau)

It uses the constants of `CellTypeRateModelTest`: `DT = 1e-3`, `TAU = 0.02`, `R_MAX = 100`,
`GAIN = 0.003`, `DRIVE = 50`, 1,000 steps, and `s = 1 / type_size`. It makes three runs from zero
rates: no drive, the sugar GRNs `LB3a`–`LB3d`, and the matched control.

The control follows the rule `CellTypeRateModelTest.matchedControl` states: for each GRN type, one
sensory, non-gustatory type whose outgoing synapse count is within 20% of the GRN type's, drawn with
a fixed seed. The Java test draws with `java.util.Random(CONTROL_SEED)`. That stream is reproducible
outside the JVM: `java.util.Random` is a fully specified 48-bit LCG (the seed is scrambled with
`0x5DEECE66D`, and `nextInt(bound)` uses the documented power-of-two shortcut and rejection loop), so
a short Python port draws the identical sequence, and the candidate list it indexes into is already
deterministic (`IntStream.range` over type indices, filtered by superclass containing `sensory`, type
class not `gustatory`, within 20% of out-synapses, and not already chosen) given the same
`cell-types.txt` and annotations. The script can therefore draw the same control set the Java test
uses, or draw its own; either way it records the chosen types **by name** in the manifest, and every
consumer reads them from there. Whether to port the LCG so the fixture's control is identical to the
full-matrix test's, or to let the script draw its own set, is left to the approver (see open question
2). Recording by name means the existing `sugarDrivesMn9` need not change in either case.

The mode writes `rates.safetensors` and `manifest.json` to
`<models-derived>/male-cns/cell-type-rate-reference/`. The safetensors file holds, per run, every
type's final rate, its peak rate, and MN9's rate trace. The manifest holds the constants, the
driven and control type names, MN9's final rate per run, and the SHA-256 of the input matrix.

### 2. The cut (`male_cns_cell_types.py circuit`)

**Selection.** Keep every type whose peak rate is above zero in the GRN run or in the control run.
Also keep the driven types and MN9. The rates start at zero and the activation is rectified, so a
type whose rate stays exactly zero adds nothing to any other type's input. A sub-matrix restricted
to the active types therefore reproduces the full run's rates on those types up to summation order.
The cut is then **exact by construction**, not an approximation that needs a tolerance argument.

**Size.** The weights are stored dense in F32, so T types cost 4·T² bytes. 250 types is 250 KB, the
budget the task allows. The first implementation step measures how many types become active. If the
active set is larger than about 250 types, the cut falls back to the top-K types by peak rate. The
script then:

- runs the cut sub-matrix with the same equations;
- records the sub-matrix rates (the test's expectations) and their deviation from the full run in
  the manifest (MN9 per run, and the maximum over kept types).

The deviation is the stated, measured cost of the cut, and the README says which case applies. If
even the fallback leaves MN9's GRN-over-control margin poorly preserved, the plan stops and asks
(see the open questions) rather than shortening the experiment on its own.

**Writing.** The cut restricts the typed bodies to members of the kept types and aggregates them
with the existing `aggregate`. `W[post, pre]` sums only over bodies of those two types, so this is
exactly the sub-block of the full matrix. Spot checks are chosen by the existing `choose_pairs` (its
counts scaled to the smaller matrix) and recomputed by the existing `recompute` from the raw edge
table, as the task asks. A disagreement aborts the write, as it does in `main` today.

`type_sizes` and `type_out_synapses` keep their **full-matrix** values for the kept types, because
the row scale `1 / type_size` must be the same as in the full run. The fixture README states this.

The output directory receives:

- `cell-type-weights.tsv`, `cell-type-counts.tsv`, `cell-types.txt` and
  `cell-type-annotations.tsv` (the text layout `CellTypeConnectome.load` reads when there is no
  `cell-type-weights.safetensors`; the job harness does not stage binary files, so the committed
  fixture cannot hold a safetensors file);
- `spot-checks.json`, `README.md` and `SHA256SUMS`;
- `rate-reference.json`: the constants, the driven and control names, the expected final rate of
  every kept type in each run, and the full-run MN9 values with the cut's deviation.

The circuit's own steps (selection, restriction and the reference run of the sub-matrix) are
separate functions, so the Python tests can exercise them on a small synthetic table.

Regeneration, recorded in the module README beside the sugar-circuit command:

    male_cns_cell_types.py rates --matrix <models-derived>/male-cns/cell-type-matrix \
        --output <models-derived>/male-cns/cell-type-rate-reference
    male_cns_cell_types.py circuit --source <male-cns release> \
        --rates <models-derived>/male-cns/cell-type-rate-reference \
        --output src/test/resources/connectome/cell-type-sugar-circuit

The existing `--source/--output` invocation that writes the full matrix stays as it is. How the CLI
admits the two new modes beside it (subcommands with the current form as the default, or mode
flags) is an implementation detail; the README command table must stay correct.

### 3. The pipeline test (`CellTypeSugarCircuitTest`)

This is a new class in `studio/bioscience/src/test/java/org/almostrealism/bioscience/connectome/test/`.
It extends `TestSuiteBase` and has no `longRunning` or `excludeProfiles`. It loads the fixture from
the classpath as `NeuronConnectomeChecks.resourceDirectory` does for the sugar circuit:

- `spotChecks()`: the fixture loads through `CellTypeConnectome.load`, and every entry in its
  `spot-checks.json` matches the loaded weights. The check is the same as
  `CellTypeConnectomeTest.spotChecks`. Move that check to a method that takes a directory, for
  example a static method beside the existing one that both classes call, so it is not duplicated.
- `sugarDrivesMn9()`: run baseline, GRN and control (names from `rate-reference.json`) for 1,000
  steps each with the full-run constants, through `SimulationRunner`, as
  `CellTypeRateModelTest.response` does. Move `response` and the population construction to a
  shared place rather than copying them. Assert that:
  - the baseline is exactly zero;
  - MN9's GRN and control rates equal the manifest values within the precision allowance below;
  - the GRN rise exceeds the control rise by at least the margin the manifest records (stated as a
    fraction of that margin, so the claim survives FP32);
  - every kept type's rate is within the allowance in both driven runs, a whole-vector check that
    catches a wrong matrix orientation or row scale that MN9 alone might miss.

**Precision allowance.** The expectations are FP64. CI runs the module on native (FP64 on the
hosted `test-media` lane), Metal, OpenCL and CUDA, and the GPU backends run FP32. The allowance is
measured on native and on Metal during implementation and stated in the javadoc with the measured
error. It is set once, from the FP32 measurement, never loosened later to pass.

**Run time.** About 250 units and 3 × 1,000 steps of a 250 × 250 product is negligible. The class is
dominated by kernel compilation and should run in well under a minute, far inside "a few minutes".
Timeouts follow the module's existing pattern (`timeout = 300000`).

### 4. Closing the trace (`CellTypeRateModelTest.matchesRateReference`)

This new method is `@TestDepth(2)` and `@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)`,
like its siblings, and requires the reference directory through `requireResourceIn` with
`ComputeRequirement.GPU, ComputeRequirement.CPU`, so a missing reference fails it. It runs the
reference's three runs on the full matrix through the platform and requires MN9's final rates, and
every type's final rate, to agree with `rates.safetensors` within the same precision allowance. It
is run on `mtl` and `native` on a host with the data. Its measured agreement goes in the fixture
README, so the chain from full matrix to platform run to reference to fixture to pipeline test is
written down.

### 5. Python tests and docs

- `studio/bioscience/scripts/test_connectome_csr.py` already needs NumPy, so the new NumPy tests go
  beside it, or in a new `studio/bioscience/scripts/test_male_cns_cell_types.py` imported by `tools/tests/test_bioscience_scripts.py` so
  that the `python-tests` job collects it. They cover:
  - one rate step against the stated equation;
  - restricting the bodies to a type subset produces the sub-block of the full aggregate;
  - dropping types whose rate stays zero leaves the other types' rates unchanged (the exactness
    claim);
  - the control rule (sensory, non-gustatory, within 20%, no repeats).
- `studio/bioscience/README.md`:
  - describe the new fixture under "What CI runs";
  - add the reference to the "Converted datasets" table, with `AR_MALE_CNS_CELL_TYPE_RATES` as its
    override property;
  - add the regeneration command;
  - correct the sentence saying the module's Java tests are not yet in the CI test matrix, which
    `analysis.yaml` now contradicts;
  - list the new class under "Tests".
- The javadoc of `CellTypeRateModelTest` names the pipeline class as its scaled-down counterpart.

## Success criteria

- `src/test/resources/connectome/cell-type-sugar-circuit/` is committed, totals a few hundred KB at
  most (target ≤ 300 KB), and was written by `male_cns_cell_types.py circuit` with spot checks
  recomputed from the raw MaleCNS edge table.
- `CellTypeSugarCircuitTest` passes with `AR_TEST_PROFILE=pipeline` on `native` and `mtl`, each in
  well under a few minutes. It runs in CI's `Run Bioscience Tests` steps.
- Its expectations come from `rate-reference.json`, which records the full-matrix MN9 values and
  the cut's deviation from them (zero up to rounding when the cut is exact).
- `CellTypeRateModelTest.matchesRateReference` passes on `native` and `mtl` on a host with the data,
  and its measured agreement is in the fixture README.
- The new Python tests pass and are collected by `python-tests`. The build validator is clean, and
  `CellTypeConnectomeLoadTest` and `CellTypeConnectomeTest.spotChecks` still pass after the
  spot-check helper is shared.

## Dependencies

- **Data and machine.** The reference and the cut need the converted matrix
  (`/Users/Shared/models-derived/male-cns/cell-type-matrix`) and the MaleCNS v1.0 source tables
  (`/Users/Shared/models/male-cns-v1.0`). The platform side needs Metal to measure the FP32
  allowance and run the full-matrix check on `mtl`. Both are present on the macOS node that
  produced the existing derived data, which is why `docs/plans/task-20261127-200720-workstream.yaml`
  requires `platform: macos`.
- **NumPy and pyarrow.** The scripts' dependencies (`scripts/requirements.txt`) must be installed in
  a virtual environment. The system `python3` on the macOS node does not have NumPy.

## Open questions

1. **The size of the active set is not yet measured.** If it fits in about 250 types the cut is
   exact; otherwise the top-K fallback carries a measured deviation. If the fallback cannot preserve
   MN9's GRN-over-control margin, a shorter experiment (fewer steps, so the activity reaches fewer
   types) or a higher size budget would be needed. Both change what the task specifies, so either
   needs the owner's decision.
2. **Should the control set be the one the full-matrix Java test draws?** `java.util.Random` is a
   documented 48-bit LCG and its `nextInt(bound)` stream is reproducible from Python (see the
   matched-control paragraph in Approach 1), and the candidate list it indexes is deterministic, so
   the script *can* reproduce the exact set `CellTypeRateModelTest.matchedControl` draws. There are
   three viable choices, and this is a decision for the approver, not a constraint:
   - The script ports the LCG and candidate order and draws the same set. The fixture's control is
     then identical to the full-matrix test's, and `matchesRateReference` compares like with like, at
     the cost of maintaining the port in lockstep with the Java selection rule.
   - The script draws its own set with its own RNG and records it by name. Simpler, but the fixture's
     control differs from the full-matrix test's.
   - `sugarDrivesMn9` reads its control from the reference manifest too, which changes an existing
     test's inputs (and so is the most invasive).
   Recording the chosen types by name keeps `sugarDrivesMn9` unchanged under either of the first two.

3. **The machine label.** `platform: macos` is the closest label that exists, but it does not
   guarantee that the derived data and source tables are present on a given macOS node. A data label
   on the nodes that carry `/Users/Shared/models` would pin this exactly.
4. The backlog task "Wire studio/bioscience into the CI test matrix (test-media + analysis
   coverage)" appears to have been delivered already: `analysis.yaml` runs `studio/bioscience` in
   every media lane. It may be ready to close; this plan does not touch it.

## Estimated complexity

Medium. Two script modes and their unit tests, one committed fixture, one new test class, one new
long-running method, a small refactor to share the spot-check and response code, and documentation.
None of it changes production code. The main risk is the active-set size (open question 1).
