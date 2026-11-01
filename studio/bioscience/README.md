# Almost Realism Bioscience Module (`ar-bioscience`)

The bioscience module combines the biological models of `ar-biology` with the
machine-learning engine of `ar-ml`.

## Purpose

This module exists to:

1. **Couple simulated organisms to trained networks**, for example an artificial network that
   reads from a chosen set of simulated neurons and writes back into others.
2. **Ingest biological datasets** through the engine's model and tensor formats.
3. **Host bioscience applications** that need both a biological model and the ML engine.

## What belongs here

- Anything that needs both `ar-biology` and `ar-ml`.
- Loaders that read third-party biological data formats into the platform's collections.
- Experiments and applications that study a biological model with machine-learning tools.

## Contents

- `org.almostrealism.bioscience.connectome.CellTypeConnectome`: a connectome aggregated to cell
  types (a dense, signed type-by-type matrix with type names, sizes and annotations), loaded
  from the output of the conversion script below.
- `org.almostrealism.bioscience.connectome.NeuronConnectome`: a connectome at the level of
  individual neurons, a signed synapse-count matrix in compressed sparse rows (rows are
  postsynaptic neurons) with the body id and annotations of every neuron. Its weight matrix is a
  collection whose shape carries a `CompressedRowTraversalOrdering`, so `matmul` with it is a
  sparse product. It is loaded from the output of `connectome_csr.py`.
- `org.almostrealism.bioscience.connectome.ConnectomeNetwork`: a spiking network over a
  `NeuronConnectome`, by default the whole-brain model of Shiu et al. (2024). One time step is the
  PDSL layer `connectome_step` in `src/main/resources/pdsl/connectome.pdsl`: an external drive and
  Poisson kicks go to the membrane, the spikes of 1.8 ms earlier go through the connectome (a sparse
  `dense`) to the synapse, the `ExponentialSynapseNeurons` of `ar-biology` emit the step's spikes,
  and those enter a delay line (`cache_read`/`cache_write`). It is stepped by a `SimulationRunner`,
  and `meanRates` runs the trials of an experiment.
- `scripts/`: Python scripts that convert third-party biological datasets into the formats the
  Java loaders read. `male_cns.py` reads and filters the MaleCNS tables and holds the shared
  safetensors and checksum helpers; `flywire.py` reads the FlyWire tables distributed with the
  Shiu et al. (2024) model; `male_cns_cell_types.py` writes the cell-type matrix;
  `connectome_csr.py` writes the neuron-level compressed rows of MaleCNS, either FlyWire
  snapshot, or a small synthetic connectome (the committed test fixture under
  `src/test/resources/connectome/synthetic` is its output); `brian2_reference.py` writes the Brian2
  references the network is checked against: `synthetic` runs the model's equations on a converted
  connectome under a seeded kick schedule (the committed fixture under
  `src/test/resources/connectome/synthetic-brian2`), `circuit` cuts the sugar circuit out of
  FlyWire 630 and runs it the same way (both fixtures below), and `sugar` runs the authors' own
  model for the sugar experiment and writes the mean rate of every neuron. A kick-schedule
  reference stores the kicks and the spikes as `(step, neuron)` events. Brian2 is needed only for
  these runs. Their dependencies are listed in `scripts/requirements.txt`. They are tested by
  `scripts/test_male_cns.py` (standard library only) and `scripts/test_connectome_csr.py` (needs
  numpy):

  ```bash
  python3 -m unittest discover -s studio/bioscience/scripts -p "test_*.py"
  ```

  CI's `python-tests` job runs both through `tools/tests/test_bioscience_scripts.py`, which imports
  their test cases so that discovery under `tools/tests` collects them.

### What CI runs, and the full experiments

The full datasets are too large for the repository and for the runners, so the committed fixtures
are cut to a size every runner can afford and the same simulation code is exercised against them
under the pipeline test profile; the full experiments run only when asked for. Of this, CI runs
the Python conversion-script tests (the `python-tests` job, above) and the sparse-matrix ordering
that backs the loader (`CompressedRowOrderedComputationTests` in `engine/utils`); the
`studio/bioscience` Java tests below are not yet in the CI test matrix, as adding the module to it
is a change to the locked CI configuration made on a `ci/` branch (tracked as a follow-up):

- `src/test/resources/connectome/sugar-circuit` is the FlyWire 630 sugar circuit: the 451
  neurons that fire in the Brian2 sugar experiment at 200 Hz (including the 21 sugar GRNs and
  MN9) and the 21,785 edges joining them, written by `brian2_reference.py circuit` through
  `connectome_csr.py`, with spot checks recomputed from the FlyWire table.
  `src/test/resources/connectome/sugar-circuit-brian2` is Brian2's run of it at the model's own
  synapse weight under 200 Hz kicks to the sugar GRNs: 7917 spikes in 0.5 s, MN9 at 96 Hz against
  92.7 Hz in the whole brain. `NeuronConnectomeTest` checks the circuit as a converted dataset and
  `ConnectomeNetworkTest` requires the network to reproduce Brian2's spikes: every neuron's count
  exactly, each spike within one step, and at most one in a thousand displaced. The allowance is
  for FP32 backends only: a NumPy run of the same step in FP64 reproduces all 7917 spikes, and in
  FP32 it shifts one spike by one step, which is what the network does on Metal. The synthetic
  reference, with fewer than a thousand spikes, must match exactly.
- The tests on the full datasets (`NeuronConnectomeDatasetTest`, `ConnectomeNetworkDatasetTest`,
  `CellTypeConnectomeTest`, `CellTypeRateModelTest`) are declared
  `@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)`: excluded from
  pipeline-profile runs, and run locally when long tests are enabled
  (`AR_LONG_TESTS=enabled`).
  When one runs, a missing dataset fails it on every host: they require their data through
  `TestFeatures.requireResourceIn` with `ComputeRequirement.GPU, ComputeRequirement.CPU`, so none
  of them skips because data is absent.

### Converted datasets

The converted datasets live outside the repository, by default under
`/Users/Shared/models-derived`, and the dataset tests read them from there (or from the
directory named by the property in parentheses):

| Dataset | Command | Directory |
|---|---|---|
| MaleCNS | `connectome_csr.py --dataset male-cns --source <male-cns release>` | `male-cns/csr` (`AR_MALE_CNS_CSR`) |
| FlyWire 630 | `connectome_csr.py --dataset flywire-630 --source <flywire-783 directory>` | `flywire/csr-630` (`AR_FLYWIRE_630_CSR`) |
| FlyWire 783 | `connectome_csr.py --dataset flywire-783 --source <flywire-783 directory>` | `flywire/csr-783` (`AR_FLYWIRE_783_CSR`) |
| Cell types | `male_cns_cell_types.py --source <male-cns release>` | `male-cns/cell-type-matrix` (`AR_MALE_CNS_CELL_TYPES`) |
| Brian2 sugar references | `brian2_reference.py sugar --repo <model repo> --rate <100, 150 or 200>` | `flywire/brian2-reference/sugarR_630_<rate>Hz` (`AR_FLYWIRE_BRIAN2_REFERENCE`, the parent directory) |

The committed sugar circuit fixtures are regenerated from the source tables and the 200 Hz
reference with:

    brian2_reference.py circuit --source <flywire-783 directory> \
        --rates <models-derived>/flywire/brian2-reference/sugarR_630_200Hz \
        --connectome-output src/test/resources/connectome/sugar-circuit \
        --output src/test/resources/connectome/sugar-circuit-brian2

`ConnectomeNetworkDatasetTest` runs the sugar experiment (30 trials of 1 s) on FlyWire 630 and
requires agreement with the Brian2 rates within the spread of two independent Brian2 runs: MN9
within 5%, a median relative difference of at most 3% and a 90th percentile of at most 8% over
neurons above 10 Hz, and at least 48 of the 50 fastest neurons in common.

## Tests

Unlike the `domain/` modules, this module has test sources of its own, in
`org.almostrealism.bioscience.connectome.test`:

- `CellTypeConnectomeLoadTest` loads a small synthetic directory written in the script's layout.
  It needs no external data.
- `CellTypeConnectomeTest` and `CellTypeRateModelTest` (a `RatePopulation` with one unit per cell
  type) run on the derived MaleCNS matrix, which is not in the repository. Its directory is the
  `AR_MALE_CNS_CELL_TYPES` system property or environment variable; each test is skipped where the
  directory does not hold the weights file.

## What does not belong here

- Biological models that do not need `ar-ml`. Put them in `domain/biology`, where lower
  layers can use them.

## Dependencies

- `ar-biology`
- `ar-ml`
