# flywire-630-sugar-circuit connectome in compressed sparse rows

Written by `studio/bioscience/scripts/connectome_csr.py` in almostrealism/common.

## Contents

- `connectome.safetensors`: `row_offsets` I32 [452], `presynaptic` I32 [21785], `weights` I32 [21785],
  `signs` I32 [451]. Rows are postsynaptic neurons; `weights` holds sign(pre) * count.
- `neurons.tsv`: body_id, type, superclass, class, transmitter per neuron, in index order.
- `connectome.json`: dataset, filters, sign rule and totals.
- `spot-checks.json`: selected neurons recomputed from the source table by body id.

## Construction

- Source: FlyWire snapshot 630 as distributed with Shiu et al. (2024), github.com/philshiu/Drosophila_brain_model
- Neurons: the neurons that fire in sugarR_630_200Hz (30 trials), of the rows of 2023_03_23_completeness_630_final.csv, in order (the index of the authors' Brian2 model) (451).
- Edges: both ends kept, at least 1 synapses (21785 edges, 256557 synapses).
- Sign: the 'Excitatory' column of 2023_03_23_connectivity_630_final.parquet.

## Sources

    e6b71e17671a9bdb05f55e4bc6774640a1418cb7a05125e0fc994ad40f9bfdfb  2023_03_23_completeness_630_final.csv
    94db8c650533bc36ffa3223f2e62325d5648b8d6bd31c3a4e1c804628c7557b3  2023_03_23_connectivity_630_final.parquet

## Command

    python brian2_reference.py circuit --source /Users/Shared/models/flywire-783 --rates /Users/Shared/models-derived/flywire/brian2-reference/sugarR_630_200Hz --connectome-output src/test/resources/connectome/sugar-circuit --output src/test/resources/connectome/sugar-circuit-brian2
