# synthetic connectome in compressed sparse rows

Written by `studio/bioscience/scripts/connectome_csr.py` in almostrealism/common.

## Contents

- `connectome.safetensors`: `row_offsets` I32 [41], `presynaptic` I32 [111], `weights` I32 [111],
  `signs` I32 [40]. Rows are postsynaptic neurons; `weights` holds sign(pre) * count.
- `neurons.tsv`: body_id, type, superclass, class, transmitter per neuron, in index order.
- `connectome.json`: dataset, filters, sign rule and totals.
- `spot-checks.json`: selected neurons recomputed from the source table by body id.

## Construction

- Source: synthetic, seed 20261030
- Neurons: 40 neurons; body id 1000 + 7 i, type T(i mod 5) (40).
- Edges: both ends kept, at least 1 synapses (111 edges, 1147 synapses).
- Sign: -1 for every third neuron (i mod 3 == 2), +1 otherwise.

## Command

    python connectome_csr.py --dataset synthetic --output studio/bioscience/src/test/resources/connectome/synthetic
