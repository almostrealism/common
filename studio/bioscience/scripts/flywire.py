"""Reading the FlyWire tables distributed with the Shiu et al. (2024) model.

The authors' repository (github.com/philshiu/Drosophila_brain_model) ships each
FlyWire snapshot as two files: a completeness table, whose row order defines
the neuron index used by their Brian2 model, and a connectivity table of
signed synapse counts already expressed in that index. This module holds the
file and column names of both snapshots and reads them.

``pandas`` and ``pyarrow`` are imported inside the functions that use them, so
the constants can be imported with the standard library alone.
"""

SNAPSHOTS = {
    "783": ("Completeness_783.csv", "Connectivity_783.parquet"),
    "630": ("2023_03_23_completeness_630_final.csv", "2023_03_23_connectivity_630_final.parquet"),
}

PRE_ID = "Presynaptic_ID"
POST_ID = "Postsynaptic_ID"
PRE_INDEX = "Presynaptic_Index"
POST_INDEX = "Postsynaptic_Index"
COUNT = "Connectivity"
EXCITATORY = "Excitatory"


def read_neurons(directory, snapshot):
    """Reads the neuron list of a snapshot in the authors' index order.

    Args:
        directory: the directory holding the snapshot's files.
        snapshot: a key of ``SNAPSHOTS``.

    Returns:
        An int64 numpy array of FlyWire root ids; position ``i`` is neuron ``i``.
    """
    import os

    import numpy as np
    import pandas as pd

    table = pd.read_csv(os.path.join(directory, SNAPSHOTS[snapshot][0]), index_col=0)
    return np.asarray(table.index.values, dtype=np.int64)


def read_connectivity(directory, snapshot):
    """Reads the connectivity table of a snapshot.

    Args:
        directory: the directory holding the snapshot's files.
        snapshot: a key of ``SNAPSHOTS``.

    Returns:
        A pandas DataFrame with the ``PRE_ID``, ``POST_ID``, ``PRE_INDEX``,
        ``POST_INDEX``, ``COUNT`` and ``EXCITATORY`` columns.
    """
    import os

    import pyarrow.parquet as pq

    columns = [PRE_ID, POST_ID, PRE_INDEX, POST_INDEX, COUNT, EXCITATORY]
    return pq.read_table(os.path.join(directory, SNAPSHOTS[snapshot][1]), columns=columns).to_pandas()
