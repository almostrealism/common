# MaleCNS v1.0 cell-type sugar rate circuit

A sub-matrix of the MaleCNS v1.0 cell-type matrix (Janelia FlyEM, CC-BY 4.0), cut by
`studio/bioscience/scripts/male_cns_cell_types.py circuit` in almostrealism/common for the
rate model's sugar experiment, with the rates that model reaches on it.

## Contents

- `cell-type-weights.tsv`, `cell-type-counts.tsv`, `cell-types.txt` and `cell-type-annotations.tsv`: the matrix of 250 types, in the text layout
  `CellTypeConnectome` reads when there is no `cell-type-weights.safetensors`. `cell-type-weights.tsv` lists the
  post index, pre index and value of every non-zero entry of the exact sub-block of the
  full matrix (re-aggregated from the member bodies of the kept types and compared with it).
  `cell-type-counts.tsv` keeps each type's full-matrix size and outgoing synapse count, so the row
  scale `1 / type_size` is the full run's.
- `spot-checks.json`: entries recomputed from the raw edge table by a separate path.
- `rate-reference.json`: the constants, the driven types, and every kept type's final rate in the
  GRN and control runs of this sub-matrix (FP64 NumPy), with the full-matrix MN9 values.

## Selection

The full run activates 9471 (GRN) and 9779 (control) of the full matrix's types, more than the
250 this cut may keep, so the cut is not exact. The driven types and MN9 are kept, and the other
places go to the types whose activity reaches MN9 most strongly: the largest, over 2
upstream hops through active types, of the normalized coupling reach to MN9 times the type's
rate. Dropping the other active types removes their input, so rates on the cut differ from
the full run:

| Run | MN9, full matrix | MN9, cut | Largest deviation over kept types |
|---|---|---|---|
| grn | 84.2472 | 78.7383 | 95.7526 |
| control | 47.6821 | 52.7502 | 95.7615 |

MN9's GRN-over-control margin is 25.9882 on the cut against 36.5651 on the full matrix.
The tests' expectations are the cut's own rates, which the platform must reproduce.

## Sources

Source directory: `/Users/Shared/models/male-cns-v1.0`; full matrix SHA-256 `ee13774705f633b3e41debdf43b5fae40f04f8bf749d2de4dadc452bda46fd22`.

## Command

    python male_cns_cell_types.py circuit --source <male-cns release> --matrix <models-derived>/male-cns/cell-type-matrix --rates <models-derived>/male-cns/cell-type-rate-reference --output src/test/resources/connectome/cell-type-sugar-circuit
