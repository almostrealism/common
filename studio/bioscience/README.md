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
  `src/test/resources/connectome/synthetic-brian2`, which `ConnectomeNetworkTest` must reproduce
  spike for spike), and `sugar` runs the authors' own model for the sugar experiment and writes the
  mean rate of every neuron. Brian2 is needed only for these runs. Their dependencies are listed in
  `scripts/requirements.txt`. They are tested by `scripts/test_male_cns.py` (standard library
  only) and `scripts/test_connectome_csr.py` (needs numpy):

  ```bash
  python3 -m unittest discover -s studio/bioscience/scripts -p "test_*.py"
  ```

  CI's `python-tests` job runs the standard-library tests through
  `tools/tests/test_bioscience_scripts.py`, which imports their test cases so that discovery
  under `tools/tests` collects them.

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

`ConnectomeNetworkDatasetTest` runs the sugar experiment (30 trials of 1 s) on FlyWire 630 and
requires agreement with the Brian2 rates within the spread of two independent Brian2 runs: MN9
within 5%, a median relative difference of at most 3% and a 90th percentile of at most 8% over
neurons above 10 Hz, and at least 48 of the 50 fastest neurons in common.

## What does not belong here

- Biological models that do not need `ar-ml`. Put them in `domain/biology`, where lower
  layers can use them.

## Dependencies

- `ar-biology`
- `ar-ml`
