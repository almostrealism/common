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
- `scripts/`: Python scripts that convert third-party biological datasets into the formats the
  Java loaders read. `male_cns.py` reads and filters the MaleCNS tables;
  `male_cns_cell_types.py` writes the cell-type matrix. Their dependencies are listed in
  `scripts/requirements.txt`. The standard-library parts are tested by `scripts/test_male_cns.py`:

  ```bash
  python3 -m unittest discover -s studio/bioscience/scripts -p "test_*.py"
  ```

  CI's `python-tests` job runs them through `tools/tests/test_bioscience_scripts.py`, which
  imports their test cases so that discovery under `tools/tests` collects them.

## What does not belong here

- Biological models that do not need `ar-ml`. Put them in `domain/biology`, where lower
  layers can use them.

## Dependencies

- `ar-biology`
- `ar-ml`
