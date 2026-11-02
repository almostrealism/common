"""Aggregates the MaleCNS v1.0 connectome to a signed cell-type-by-cell-type matrix.

Usage (in a virtual environment with studio/bioscience/scripts/requirements.txt):

    python male_cns_cell_types.py --source /Users/Shared/models/male-cns-v1.0 \\
        --output <directory>

For neurons ``i`` of type ``pre`` and ``j`` of type ``post`` the output holds

    W[post, pre] = sum over i, j of sign(i) * count(i -> j)

where ``sign`` is the Shiu et al. transmitter rule in ``male_cns``. Rows are
postsynaptic types and columns presynaptic types, so a rate model's input is
``W . r``. Only bodies carrying a cell type annotation take part. The counts
are stored un-normalized.

The output directory receives:

- ``cell-type-weights.safetensors`` with ``weights`` (F32, [T, T]),
  ``type_sizes`` (I32, [T], neurons per type) and ``type_out_synapses``
  (I32, [T], outgoing synapses onto typed bodies);
- ``cell-types.txt``, one type name per line, in index order;
- ``cell-type-annotations.tsv``, a header line then ``type``, ``superclass``
  and ``class`` per type in index order, each the most common among the
  type's members;
- ``spot-checks.json``, about 200 entries recomputed by a different path;
- ``README.md`` and ``SHA256SUMS``.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import male_cns  # noqa: E402

WEIGHTS_OUTPUT = "cell-type-weights.safetensors"
TYPES_OUTPUT = "cell-types.txt"
ANNOTATIONS_OUTPUT = "cell-type-annotations.tsv"
SPOT_CHECKS_OUTPUT = "spot-checks.json"

LARGEST_CHECKS = 100
SAMPLED_CHECKS = 80
ZERO_CHECKS = 20
SEED = 20261026
CHUNK_ROWS = 20_000_000


def aggregate(body_ids, body_types, transmitters, weights):
    """Builds the signed type-level matrix with vectorized index mapping.

    Args:
        body_ids: int64 numpy array of typed body ids.
        body_types: the type name of each body.
        transmitters: dict from body id to transmitter name.
        weights: the pyarrow weight table.

    Returns:
        ``(names, matrix, sizes, out_synapses, kept_edges)`` with ``matrix`` a
        float64 [T, T] array of exact integer sums.
    """
    import numpy as np

    names = sorted(set(body_types))
    index = {name: i for i, name in enumerate(names)}
    t = len(names)

    order = np.argsort(body_ids)
    sorted_bodies = body_ids[order]
    sorted_types = np.array([index[body_types[i]] for i in order], dtype=np.int64)
    sorted_signs = np.array([male_cns.transmitter_sign(transmitters.get(int(b)))
                             for b in sorted_bodies], dtype=np.int64)
    sizes = np.bincount(sorted_types, minlength=t)

    matrix = np.zeros(t * t, dtype=np.float64)
    out_synapses = np.zeros(t, dtype=np.int64)
    kept = 0

    def locate(column):
        position = np.searchsorted(sorted_bodies, column)
        clipped = np.minimum(position, len(sorted_bodies) - 1)
        return clipped, sorted_bodies[clipped] == column

    for start in range(0, weights.num_rows, CHUNK_ROWS):
        chunk = weights.slice(start, CHUNK_ROWS)
        pre = chunk[male_cns.WEIGHT_PRE].to_numpy()
        post = chunk[male_cns.WEIGHT_POST].to_numpy()
        count = chunk[male_cns.WEIGHT_COUNT].to_numpy()

        pre_position, pre_found = locate(pre)
        post_position, post_found = locate(post)
        keep = pre_found & post_found
        pre_position = pre_position[keep]
        post_position = post_position[keep]
        count = count[keep]
        kept += int(keep.sum())

        pre_type = sorted_types[pre_position]
        flat = sorted_types[post_position] * t + pre_type
        signed = (sorted_signs[pre_position] * count).astype(np.float64)
        matrix += np.bincount(flat, weights=signed, minlength=t * t)
        out_synapses += np.bincount(pre_type, weights=count, minlength=t).astype(np.int64)
        print("aggregated %d of %d edges" % (min(start + CHUNK_ROWS, weights.num_rows), weights.num_rows),
              file=sys.stderr)

    return names, matrix.reshape(t, t), sizes, out_synapses, kept


def choose_pairs(matrix):
    """Chooses the (post, pre) index pairs to spot check.

    Args:
        matrix: the [T, T] matrix.

    Returns:
        A list of ``(post, pre, kind)``: the largest entries by magnitude,
        nonzero entries sampled with a fixed seed, and entries that are zero.
    """
    import numpy as np

    t = matrix.shape[0]
    flat = matrix.reshape(-1)
    magnitude = np.abs(flat)
    largest = np.argpartition(magnitude, -LARGEST_CHECKS)[-LARGEST_CHECKS:]
    chosen = [(int(i), "largest") for i in largest]
    taken = set(int(i) for i in largest)

    rng = np.random.default_rng(SEED)
    nonzero = np.flatnonzero(flat)
    for i in rng.choice(nonzero, size=SAMPLED_CHECKS * 2, replace=False):
        if len(chosen) >= LARGEST_CHECKS + SAMPLED_CHECKS:
            break
        if int(i) not in taken:
            chosen.append((int(i), "sampled"))
            taken.add(int(i))

    zeros = 0
    while zeros < ZERO_CHECKS:
        i = int(rng.integers(0, t * t))
        if flat[i] == 0 and i not in taken:
            chosen.append((i, "zero"))
            taken.add(i)
            zeros += 1

    return [(i // t, i % t, kind) for i, kind in chosen]


def recompute(pairs, names, body_ids, body_types, transmitters, weights):
    """Recomputes the chosen entries without the vectorized index mapping.

    For each presynaptic type the raw edge table is filtered to the type's
    member bodies, and each pair's value is summed row by row over the edges
    whose postsynaptic body belongs to the postsynaptic type.

    Returns:
        A list of spot-check dicts.
    """
    import pyarrow as pa
    import pyarrow.compute as pc

    members = {}
    for body, name in zip(body_ids.tolist(), body_types):
        members.setdefault(name, set()).add(body)

    by_pre = {}
    for post, pre, kind in pairs:
        by_pre.setdefault(pre, []).append((post, kind))

    checks = []
    for count, (pre, posts) in enumerate(sorted(by_pre.items())):
        pre_members = members[names[pre]]
        mask = pc.is_in(weights[male_cns.WEIGHT_PRE], value_set=pa.array(sorted(pre_members), pa.int64()))
        edges = weights.filter(mask).to_pylist()
        for post, kind in posts:
            post_members = members[names[post]]
            value = 0
            for edge in edges:
                if edge[male_cns.WEIGHT_POST] in post_members:
                    sign = male_cns.transmitter_sign(transmitters.get(edge[male_cns.WEIGHT_PRE]))
                    value += sign * edge[male_cns.WEIGHT_COUNT]
            checks.append({"post": names[post], "pre": names[pre], "post_index": post,
                           "pre_index": pre, "kind": kind, "value": value})
        print("recomputed %d of %d presynaptic types" % (count + 1, len(by_pre)), file=sys.stderr)

    return checks


def write_readme(path, source, names, sizes, kept, source_sums, command):
    """Writes the output README recording provenance, filter and sign rule."""
    lines = [
        "# MaleCNS v1.0 cell-type matrix",
        "",
        "Derived from the MaleCNS v1.0 connectome (Janelia FlyEM, CC-BY 4.0) by",
        "`studio/bioscience/scripts/male_cns_cell_types.py` in almostrealism/common.",
        "",
        "## Contents",
        "",
        "- `%s`: `weights` F32 [%d, %d], row = postsynaptic type, column = presynaptic type;"
        % (WEIGHTS_OUTPUT, len(names), len(names)),
        "  `type_sizes` I32 [%d]; `type_out_synapses` I32 [%d]." % (len(names), len(names)),
        "- `%s`: one type name per line, in index order." % TYPES_OUTPUT,
        "- `%s`: type, superclass and class (most common among members), in index order."
        % ANNOTATIONS_OUTPUT,
        "- `%s`: entries recomputed from the raw edge table by a separate path." % SPOT_CHECKS_OUTPUT,
        "",
        "## Construction",
        "",
        "- Filter: only bodies with a non-empty `%s` annotation (%d bodies, %d types);"
        % (male_cns.ANNOTATION_TYPE, int(sizes.sum()), len(names)),
        "  %d edges join two such bodies." % kept,
        "- Sign: from `%s` in the neurotransmitter table. GABA and glutamate are -1;" % male_cns.TRANSMITTER_COLUMN,
        "  every other value (acetylcholine, dopamine, serotonin, octopamine, tyramine, histamine,",
        "  unclear, missing) is +1, as in Shiu et al. (2024).",
        "- `W[post, pre] = sum sign(i) * weight(i -> j)` over bodies i of type pre and j of",
        "  type post. Counts are un-normalized; max |W| < 2^24, so FP32 holds them exactly.",
        "",
        "## Sources",
        "",
        "Source directory: `%s`" % source,
        "",
    ]
    lines += ["    %s  %s" % (source_sums[name], name) for name in male_cns.SOURCE_FILES]
    lines += ["", "## Command", "", "    " + command, ""]
    with open(path, "w") as out:
        out.write("\n".join(lines))


def main(argv=None):
    """Runs the conversion."""
    import numpy as np

    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source", required=True, help="the MaleCNS v1.0 release directory")
    parser.add_argument("--output", required=True, help="the directory to write")
    args = parser.parse_args(argv)

    source_sums = male_cns.source_checksums(args.source, male_cns.SOURCE_FILES)

    body_ids, body_types, superclasses, classes = male_cns.read_typed_bodies(args.source)
    transmitters = male_cns.read_transmitters(args.source)
    weights = male_cns.read_weights(args.source)

    names, matrix, sizes, out_synapses, kept = aggregate(body_ids, body_types, transmitters, weights)
    if np.abs(matrix).max() >= male_cns.FP32_EXACT_LIMIT:
        raise SystemExit("an entry reaches 2^24 and would not be exact in FP32")
    if out_synapses.max() >= 2 ** 31:
        raise SystemExit("an outgoing synapse count does not fit in I32")

    checks = recompute(choose_pairs(matrix), names, body_ids, body_types, transmitters, weights)
    for check in checks:
        if check["value"] != matrix[check["post_index"], check["pre_index"]]:
            raise SystemExit("spot check disagrees with the aggregate: " + json.dumps(check))

    os.makedirs(args.output, exist_ok=True)
    male_cns.write_safetensors(os.path.join(args.output, WEIGHTS_OUTPUT), [
        ("weights", "F32", matrix.astype(np.float32)),
        ("type_sizes", "I32", sizes),
        ("type_out_synapses", "I32", out_synapses),
    ])
    with open(os.path.join(args.output, TYPES_OUTPUT), "w") as out:
        out.write("".join(name + "\n" for name in names))
    annotations = male_cns.majority_annotations(body_types, superclasses, classes)
    with open(os.path.join(args.output, ANNOTATIONS_OUTPUT), "w") as out:
        out.write("type\tsuperclass\tclass\n")
        out.write("".join("%s\t%s\t%s\n" % ((name,) + annotations[name]) for name in names))
    with open(os.path.join(args.output, SPOT_CHECKS_OUTPUT), "w") as out:
        json.dump(checks, out, indent=1)

    command = "python " + " ".join([os.path.basename(__file__)] + (argv or sys.argv[1:]))
    write_readme(os.path.join(args.output, "README.md"), args.source, names, sizes, kept,
                 source_sums, command)

    outputs = [WEIGHTS_OUTPUT, TYPES_OUTPUT, ANNOTATIONS_OUTPUT, SPOT_CHECKS_OUTPUT, "README.md"]
    with open(os.path.join(args.output, "SHA256SUMS"), "w") as out:
        out.write("".join("%s  %s\n" % (male_cns.sha256(os.path.join(args.output, n)), n) for n in outputs))

    print("types=%d typedBodies=%d keptEdges=%d maxAbs=%d spotChecks=%d"
          % (len(names), int(sizes.sum()), kept, int(np.abs(matrix).max()), len(checks)))


if __name__ == "__main__":
    main()
