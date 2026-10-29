"""Reading and filtering the MaleCNS v1.0 connectome tables.

The MaleCNS release ships four flat Feather tables: body annotations, predicted
neurotransmitters, body statistics and the body-to-body synapse weights. This
module holds what every consumer of those tables shares: the file and column
names, the filter (keep bodies that carry a cell type annotation), the
neurotransmitter sign rule, and a safetensors writer.

Everything that needs ``pyarrow`` or ``numpy`` imports it inside the function
that uses it, so the sign rule, the column constants and the safetensors header
layout can be imported and tested with the standard library alone.
"""

import json
import struct

ANNOTATIONS_FILE = "body-annotations-male-cns-v1.0-minconf-0.5.feather"
NEUROTRANSMITTERS_FILE = "body-neurotransmitters-male-cns-v1.0.feather"
WEIGHTS_FILE = "connectome-weights-male-cns-v1.0-minconf-0.5.feather"
SOURCE_FILES = (ANNOTATIONS_FILE, NEUROTRANSMITTERS_FILE, WEIGHTS_FILE)

ANNOTATION_BODY = "bodyId"
ANNOTATION_TYPE = "type"
ANNOTATION_SUPERCLASS = "superclass"
ANNOTATION_CLASS = "class"
TRANSMITTER_BODY = "body"
TRANSMITTER_COLUMN = "consensus_nt"
WEIGHT_PRE = "body_pre"
WEIGHT_POST = "body_post"
WEIGHT_COUNT = "weight"

# Shiu et al. (2024) model every neuron as excitatory except those predicted to
# release GABA or glutamate, which are inhibitory. Their connectivity table
# carries only the two signs. Every other transmitter (dopamine, serotonin,
# octopamine, tyramine, histamine), "unclear" and a missing prediction are
# therefore excitatory.
INHIBITORY_TRANSMITTERS = frozenset(("gaba", "glutamate"))

# FP32 represents every integer whose magnitude is below this bound exactly.
FP32_EXACT_LIMIT = 2 ** 24

SAFETENSORS_DTYPES = {"F32": 4, "F64": 8, "I32": 4, "I64": 8}


def transmitter_sign(transmitter):
    """Returns the synaptic sign of a predicted neurotransmitter.

    Args:
        transmitter: the transmitter name, or None when there is no prediction.

    Returns:
        -1 for GABA and glutamate, +1 for everything else (Shiu et al.).
    """
    if transmitter is not None and transmitter.lower() in INHIBITORY_TRANSMITTERS:
        return -1
    return 1


def safetensors_header(tensors):
    """Builds the header of a safetensors file.

    Args:
        tensors: a list of ``(name, dtype, shape)`` in the order the data is
            written, with dtype one of ``SAFETENSORS_DTYPES``.

    Returns:
        The bytes that precede the tensor data: the little-endian 8-byte header
        length followed by the JSON header, padded with spaces to a multiple of
        8 bytes so that the data starts aligned.
    """
    header = {}
    offset = 0
    for name, dtype, shape in tensors:
        if dtype not in SAFETENSORS_DTYPES:
            raise ValueError("unsupported dtype %s for %s" % (dtype, name))
        size = SAFETENSORS_DTYPES[dtype]
        for dim in shape:
            size *= int(dim)
        header[name] = {
            "dtype": dtype,
            "shape": [int(dim) for dim in shape],
            "data_offsets": [offset, offset + size],
        }
        offset += size

    encoded = json.dumps(header, separators=(",", ":")).encode("utf-8")
    encoded += b" " * ((8 - (8 + len(encoded)) % 8) % 8)
    return struct.pack("<Q", len(encoded)) + encoded


def write_safetensors(path, arrays):
    """Writes numpy arrays as a safetensors file.

    Args:
        path: the output file.
        arrays: a list of ``(name, dtype, array)``; each array is converted to
            the little-endian numpy type matching dtype before it is written.
    """
    import numpy as np

    numpy_types = {"F32": "<f4", "F64": "<f8", "I32": "<i4", "I64": "<i8"}
    converted = [(name, dtype, np.ascontiguousarray(array, dtype=numpy_types[dtype]))
                 for name, dtype, array in arrays]
    with open(path, "wb") as out:
        out.write(safetensors_header([(n, d, a.shape) for n, d, a in converted]))
        for _, _, array in converted:
            out.write(array.tobytes())


def majority_annotations(types, superclasses, classes):
    """Returns the most common superclass and class of the members of each type.

    Args:
        types: the type name of each body.
        superclasses: the superclass of each body (None when absent).
        classes: the class of each body (None when absent).

    Returns:
        A dict from type name to ``(superclass, class)``, each the most common
        non-empty value among the type's members (ties broken alphabetically),
        or "" when no member has one.
    """
    counts = {}
    for name, superclass, cls in zip(types, superclasses, classes):
        entry = counts.setdefault(name, ({}, {}))
        if superclass:
            entry[0][superclass] = entry[0].get(superclass, 0) + 1
        if cls:
            entry[1][cls] = entry[1].get(cls, 0) + 1

    def most_common(tally):
        return min(tally, key=lambda value: (-tally[value], value)) if tally else ""

    return {name: (most_common(s), most_common(c)) for name, (s, c) in counts.items()}


def read_typed_bodies(directory):
    """Reads the bodies that carry a cell type annotation.

    Unannotated fragments are dropped.

    Args:
        directory: the MaleCNS release directory.

    Returns:
        ``(body_ids, types, superclasses, classes)``: an int64 numpy array of
        body ids and lists of the same length holding each body's type name,
        superclass and class.
    """
    import os

    import numpy as np
    import pyarrow.compute as pc
    import pyarrow.feather as feather

    table = feather.read_table(os.path.join(directory, ANNOTATIONS_FILE),
                               columns=[ANNOTATION_BODY, ANNOTATION_TYPE,
                                        ANNOTATION_SUPERCLASS, ANNOTATION_CLASS])
    present = pc.and_(pc.invert(pc.is_null(table[ANNOTATION_TYPE])),
                      pc.not_equal(table[ANNOTATION_TYPE], ""))
    table = table.filter(present)
    return (np.asarray(table[ANNOTATION_BODY].to_numpy(), dtype=np.int64),
            table[ANNOTATION_TYPE].to_pylist(),
            table[ANNOTATION_SUPERCLASS].to_pylist(),
            table[ANNOTATION_CLASS].to_pylist())


def read_transmitters(directory):
    """Reads each body's predicted neurotransmitter.

    Args:
        directory: the MaleCNS release directory.

    Returns:
        A dict from body id to the ``TRANSMITTER_COLUMN`` value (possibly None).
    """
    import os

    import pyarrow.feather as feather

    table = feather.read_table(os.path.join(directory, NEUROTRANSMITTERS_FILE),
                               columns=[TRANSMITTER_BODY, TRANSMITTER_COLUMN])
    return dict(zip(table[TRANSMITTER_BODY].to_pylist(), table[TRANSMITTER_COLUMN].to_pylist()))


def read_weights(directory):
    """Reads the body-to-body synapse weight table, memory-mapped.

    Args:
        directory: the MaleCNS release directory.

    Returns:
        A pyarrow table with columns ``WEIGHT_PRE``, ``WEIGHT_POST`` and
        ``WEIGHT_COUNT``.
    """
    import os

    import pyarrow.feather as feather

    return feather.read_table(os.path.join(directory, WEIGHTS_FILE), memory_map=True)
