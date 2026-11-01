"""Converts a neuron-level connectome to signed compressed sparse rows.

Usage (in a virtual environment with studio/bioscience/scripts/requirements.txt):

    python connectome_csr.py --dataset male-cns \\
        --source /Users/Shared/models/male-cns-v1.0 --output <directory>
    python connectome_csr.py --dataset flywire-630 \\
        --source /Users/Shared/models/flywire-783 --output <directory>
    python connectome_csr.py --dataset synthetic --neurons 40 --output <directory>

Every dataset is reduced to the same form, so one Java loader reads them all:
neurons ``0 .. N-1`` and, for each edge, a presynaptic neuron, a postsynaptic
neuron and a synapse count. The matrix ``W[post, pre] = sign(pre) * count`` is
written in compressed sparse rows ordered by **postsynaptic** neuron, so a
simulation's synaptic input is the product ``W . s`` of the matrix and the
spike vector.

- ``male-cns``: the bodies carrying a cell type annotation, in increasing body
  id order; only edges joining two of them are kept. The sign of a neuron is
  the Shiu et al. transmitter rule in ``male_cns``.
- ``flywire-630`` / ``flywire-783``: the neurons of the authors' completeness
  table, in its row order (the index of their Brian2 model), with the signs of
  their connectivity table.
- ``synthetic``: a small random connectome drawn with a fixed seed, written by
  the same path, for tests that must not depend on the datasets.

``--min-count`` drops edges with fewer synapses. Shiu et al. keep every edge of
the FlyWire tables, so the default is 1.

The output directory receives:

- ``connectome.safetensors`` with ``row_offsets`` (I32, [N + 1]),
  ``presynaptic`` (I32, [E], the column of each entry), ``weights`` (I32, [E],
  the signed synapse count of each entry) and ``signs`` (I32, [N]);
- ``neurons.tsv``, a header line then ``body_id``, ``type``, ``superclass``,
  ``class`` and ``transmitter`` per neuron in index order (empty when the
  dataset does not annotate it). Body ids are kept out of the tensors because
  FlyWire root ids exceed the 2^53 a tensor value can carry exactly;
- ``connectome.json``, the dataset, filters, sign rule and totals;
- ``spot-checks.json``, the degrees, synapse totals and strongest partners of
  selected neurons, recomputed from the source table by body id;
- ``README.md`` and ``SHA256SUMS``.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import flywire  # noqa: E402
import male_cns  # noqa: E402

TENSORS_OUTPUT = "connectome.safetensors"
NEURONS_OUTPUT = "neurons.tsv"
METADATA_OUTPUT = "connectome.json"
SPOT_CHECKS_OUTPUT = "spot-checks.json"
NEURON_COLUMNS = ("body_id", "type", "superclass", "class", "transmitter")

SEED = 20261030
SAMPLED_CHECKS = 12
PARTNERS = 3
CHUNK_ROWS = 20_000_000

# Neurons every spot check of a dataset includes: the proboscis-extension motor
# neuron MN9 and, for FlyWire, the first sugar-sensing GRN of Shiu et al.
NAMED_NEURONS = {
    "male-cns": (10331, 16949),
    "flywire-630": (720575940660219265, 720575940624963786),
    "flywire-783": (720575940660219265, 720575940624963786),
    "flywire-630-sugar-circuit": (720575940660219265, 720575940624963786),
}


class Dataset:
    """A connectome reduced to indexed neurons and an edge list.

    Attributes:
        name: the dataset name.
        body_ids: int64 numpy array, the body id of each neuron.
        annotations: a list of ``(type, superclass, class, transmitter)`` per
            neuron, each "" when unknown.
        signs: int64 numpy array of +1/-1 per neuron.
        pre, post, count: int64 numpy arrays, one entry per edge.
        sources: the source file names, relative to the source directory.
        description: a dict recorded in ``connectome.json``.
        edges_by_body: a callable taking a body id and returning
            ``(pre_ids, post_ids, counts)`` of its incoming and outgoing edges,
            read from the source table by body id (the spot-check path).
    """

    def __init__(self, name, body_ids, annotations, signs, pre, post, count,
                 sources, description, edges_by_body):
        self.name = name
        self.body_ids = body_ids
        self.annotations = annotations
        self.signs = signs
        self.pre = pre
        self.post = post
        self.count = count
        self.sources = sources
        self.description = description
        self.edges_by_body = edges_by_body

    def subset(self, name, keep, rule):
        """Returns the connectome induced on some of these neurons.

        Args:
            name: the name of the subset.
            keep: a boolean numpy array, True for each neuron the subset keeps.
            rule: a description of which neurons are kept, recorded as the
                subset's neuron rule.

        Returns:
            A Dataset of the kept neurons, in their order here, and the edges
            joining two of them. Its spot checks still read the source table,
            which ``spot_checks`` filters to the kept neurons.
        """
        import numpy as np

        index = np.cumsum(keep) - 1
        edges = keep[self.pre] & keep[self.post]
        description = dict(self.description, neurons="%s, of %s" % (rule, self.description["neurons"]))
        return Dataset(name, self.body_ids[keep], [a for a, k in zip(self.annotations, keep) if k],
                       self.signs[keep], index[self.pre[edges]], index[self.post[edges]],
                       self.count[edges], self.sources, description, self.edges_by_body)


def read_male_cns(source):
    """Reads the typed bodies of MaleCNS v1.0 and the edges between them."""
    import numpy as np
    import pyarrow as pa
    import pyarrow.compute as pc

    body_ids, types, superclasses, classes = male_cns.read_typed_bodies(source)
    transmitters = male_cns.read_transmitters(source)
    weights = male_cns.read_weights(source)

    order = np.argsort(body_ids)
    body_ids = body_ids[order]
    annotations = [(types[i] or "", superclasses[i] or "", classes[i] or "",
                    transmitters.get(int(body_ids[k])) or "") for k, i in enumerate(order)]
    signs = np.array([male_cns.transmitter_sign(a[3] or None) for a in annotations], dtype=np.int64)

    def locate(column):
        position = np.searchsorted(body_ids, column)
        clipped = np.minimum(position, len(body_ids) - 1)
        return clipped, body_ids[clipped] == column

    pres, posts, counts = [], [], []
    for start in range(0, weights.num_rows, CHUNK_ROWS):
        chunk = weights.slice(start, CHUNK_ROWS)
        pre, pre_found = locate(chunk[male_cns.WEIGHT_PRE].to_numpy())
        post, post_found = locate(chunk[male_cns.WEIGHT_POST].to_numpy())
        keep = pre_found & post_found
        pres.append(pre[keep])
        posts.append(post[keep])
        counts.append(chunk[male_cns.WEIGHT_COUNT].to_numpy()[keep])
        print("read %d of %d edges" % (min(start + CHUNK_ROWS, weights.num_rows), weights.num_rows),
              file=sys.stderr)

    def edges_by_body(body):
        value = pa.scalar(body, pa.int64())
        mask = pc.or_(pc.equal(weights[male_cns.WEIGHT_PRE], value),
                      pc.equal(weights[male_cns.WEIGHT_POST], value))
        table = weights.filter(mask)
        return (table[male_cns.WEIGHT_PRE].to_numpy(), table[male_cns.WEIGHT_POST].to_numpy(),
                table[male_cns.WEIGHT_COUNT].to_numpy())

    description = {
        "source": "MaleCNS v1.0 (Janelia FlyEM, CC-BY 4.0)",
        "neurons": "bodies with a non-empty '%s' annotation, in increasing body id order"
                   % male_cns.ANNOTATION_TYPE,
        "sign": "Shiu et al. (2024): -1 when '%s' is gaba or glutamate, +1 otherwise "
                "(acetylcholine, dopamine, serotonin, octopamine, tyramine, histamine, "
                "unclear, missing)" % male_cns.TRANSMITTER_COLUMN,
    }
    return Dataset("male-cns", body_ids, annotations, signs, np.concatenate(pres),
                   np.concatenate(posts), np.concatenate(counts), list(male_cns.SOURCE_FILES),
                   description, edges_by_body)


def read_flywire(source, snapshot):
    """Reads a FlyWire snapshot as distributed with the Shiu et al. model."""
    import numpy as np

    body_ids = flywire.read_neurons(source, snapshot)
    table = flywire.read_connectivity(source, snapshot)
    pre = table[flywire.PRE_INDEX].to_numpy(dtype=np.int64)
    post = table[flywire.POST_INDEX].to_numpy(dtype=np.int64)
    if not (np.array_equal(body_ids[pre], table[flywire.PRE_ID].to_numpy())
            and np.array_equal(body_ids[post], table[flywire.POST_ID].to_numpy())):
        raise SystemExit("connectivity indices disagree with the completeness table order")

    excitatory = table[flywire.EXCITATORY].to_numpy(dtype=np.int64)
    signs = np.ones(len(body_ids), dtype=np.int64)
    signs[pre] = excitatory
    if not np.array_equal(signs[pre], excitatory):
        raise SystemExit("a presynaptic neuron has edges of both signs")

    pre_ids = table[flywire.PRE_ID].to_numpy()
    post_ids = table[flywire.POST_ID].to_numpy()
    counts = table[flywire.COUNT].to_numpy(dtype=np.int64)

    def edges_by_body(body):
        mask = (pre_ids == body) | (post_ids == body)
        return pre_ids[mask], post_ids[mask], counts[mask]

    description = {
        "source": "FlyWire snapshot %s as distributed with Shiu et al. (2024), "
                  "github.com/philshiu/Drosophila_brain_model" % snapshot,
        "neurons": "the rows of %s, in order (the index of the authors' Brian2 model)"
                   % flywire.SNAPSHOTS[snapshot][0],
        "sign": "the '%s' column of %s" % (flywire.EXCITATORY, flywire.SNAPSHOTS[snapshot][1]),
    }
    annotations = [("", "", "", "")] * len(body_ids)
    return Dataset("flywire-" + snapshot, body_ids, annotations, signs, pre, post, counts,
                   list(flywire.SNAPSHOTS[snapshot]), description, edges_by_body)


def synthetic(neurons):
    """Draws a small random connectome with a fixed seed.

    Neuron ``i`` has body id ``1000 + 7 * i`` and type ``T<i mod 5>``; every
    third neuron is inhibitory. Each neuron receives between 0 and 5 edges
    from distinct presynaptic neurons, with 1 to 20 synapses each.
    """
    import numpy as np

    rng = np.random.default_rng(SEED)
    body_ids = 1000 + 7 * np.arange(neurons, dtype=np.int64)
    signs = np.where(np.arange(neurons) % 3 == 2, -1, 1).astype(np.int64)
    annotations = [("T%d" % (i % 5), "synthetic", "", "gaba" if signs[i] < 0 else "acetylcholine")
                   for i in range(neurons)]

    pres, posts = [], []
    for post in range(neurons):
        sources = rng.choice(neurons, size=int(rng.integers(0, 6)), replace=False)
        pres.extend(int(s) for s in sources)
        posts.extend([post] * len(sources))
    pre = np.array(pres, dtype=np.int64)
    post = np.array(posts, dtype=np.int64)
    count = rng.integers(1, 21, size=len(pre)).astype(np.int64)

    def edges_by_body(body):
        index = int((body - 1000) // 7)
        mask = (pre == index) | (post == index)
        return body_ids[pre[mask]], body_ids[post[mask]], count[mask]

    description = {
        "source": "synthetic, seed %d" % SEED,
        "neurons": "%d neurons; body id 1000 + 7 i, type T(i mod 5)" % neurons,
        "sign": "-1 for every third neuron (i mod 3 == 2), +1 otherwise",
    }
    return Dataset("synthetic", body_ids, annotations, signs, pre, post, count, [],
                   description, edges_by_body)


def compressed_rows(n, pre, post, signed):
    """Sorts an edge list into compressed sparse rows by postsynaptic neuron.

    Args:
        n: the number of neurons.
        pre, post: int64 arrays of presynaptic and postsynaptic indices.
        signed: the value of each edge.

    Returns:
        ``(row_offsets, columns, values)``: the ``n + 1`` row boundaries, and
        the presynaptic index and value of each entry, sorted by postsynaptic
        then presynaptic index.

    Raises:
        ValueError: if an index is outside ``[0, n)`` or a (pre, post) pair
            occurs twice.
    """
    import numpy as np

    if len(pre) and (min(pre.min(), post.min()) < 0 or max(pre.max(), post.max()) >= n):
        raise ValueError("an edge index is outside [0, %d)" % n)

    order = np.lexsort((pre, post))
    columns = pre[order]
    rows = post[order]
    if np.any((rows[1:] == rows[:-1]) & (columns[1:] == columns[:-1])):
        raise ValueError("an edge occurs twice")

    row_offsets = np.zeros(n + 1, dtype=np.int64)
    np.cumsum(np.bincount(rows, minlength=n), out=row_offsets[1:])
    return row_offsets, columns, signed[order]


def spot_checks(dataset, row_offsets, columns, values):
    """Recomputes the connectivity of selected neurons from the source table.

    The named neurons of the dataset and a seeded sample are checked. For
    each, the source table is filtered by body id (not by index) and its
    in-degree, out-degree, synapse totals and strongest presynaptic partners
    are compared with the compressed rows.

    Returns:
        A list of spot-check dicts, as written to ``spot-checks.json``.

    Raises:
        SystemExit: if the compressed rows disagree with the source table.
    """
    import numpy as np

    n = len(dataset.body_ids)
    index_of = {int(b): i for i, b in enumerate(dataset.body_ids)}
    chosen = [index_of[b] for b in NAMED_NEURONS.get(dataset.name, ()) if b in index_of]
    rng = np.random.default_rng(SEED)
    chosen += [int(i) for i in rng.choice(n, size=min(SAMPLED_CHECKS, n), replace=False)
               if int(i) not in chosen]

    checks = []
    for i in chosen:
        body = int(dataset.body_ids[i])
        pre_ids, post_ids, counts = dataset.edges_by_body(body)
        kept = (np.isin(pre_ids, dataset.body_ids) & np.isin(post_ids, dataset.body_ids)
                & (counts >= dataset.description["min_count"]))
        pre_ids, post_ids, counts = pre_ids[kept], post_ids[kept], counts[kept]
        incoming = post_ids == body
        outgoing = pre_ids == body
        sign = int(dataset.signs[i])
        partners = sorted(((int(c) * int(dataset.signs[index_of[int(p)]]), int(p))
                           for p, c in zip(pre_ids[incoming], counts[incoming])),
                          key=lambda e: (-abs(e[0]), e[1]))[:PARTNERS]
        check = {
            "index": i,
            "body_id": str(body),
            "in_degree": int(incoming.sum()),
            "in_synapses": int(counts[incoming].sum()),
            "out_degree": int(outgoing.sum()),
            "out_synapses": int(counts[outgoing].sum()),
            "strongest_inputs": [{"body_id": str(p), "weight": w} for w, p in partners],
        }

        row = slice(int(row_offsets[i]), int(row_offsets[i + 1]))
        out = columns == i
        derived = (row.stop - row.start, int(np.abs(values[row]).sum()),
                   int(out.sum()), int(np.abs(values[out]).sum()), int(np.unique(np.sign(values[out])).size))
        expected = (check["in_degree"], check["in_synapses"], check["out_degree"],
                    check["out_synapses"], 1 if check["out_degree"] else 0)
        if derived != expected or (check["out_degree"] and int(np.sign(values[out][0])) != sign):
            raise SystemExit("spot check disagrees with the compressed rows: %s (derived %s)"
                             % (json.dumps(check), derived))
        checks.append(check)

    return checks


def write_readme(path, dataset, metadata, source_sums, command):
    """Writes the output README recording provenance, construction and totals."""
    lines = [
        "# %s connectome in compressed sparse rows" % dataset.name,
        "",
        "Written by `studio/bioscience/scripts/connectome_csr.py` in almostrealism/common.",
        "",
        "## Contents",
        "",
        "- `%s`: `row_offsets` I32 [%d], `presynaptic` I32 [%d], `weights` I32 [%d],"
        % (TENSORS_OUTPUT, metadata["neurons"] + 1, metadata["edges"], metadata["edges"]),
        "  `signs` I32 [%d]. Rows are postsynaptic neurons; `weights` holds sign(pre) * count."
        % metadata["neurons"],
        "- `%s`: %s per neuron, in index order." % (NEURONS_OUTPUT, ", ".join(NEURON_COLUMNS)),
        "- `%s`: dataset, filters, sign rule and totals." % METADATA_OUTPUT,
        "- `%s`: selected neurons recomputed from the source table by body id." % SPOT_CHECKS_OUTPUT,
        "",
        "## Construction",
        "",
        "- Source: %s" % metadata["source"],
        "- Neurons: %s (%d)." % (metadata["neurons_rule"], metadata["neurons"]),
        "- Edges: both ends kept, at least %d synapses (%d edges, %d synapses)."
        % (metadata["min_count"], metadata["edges"], metadata["synapses"]),
        "- Sign: %s." % metadata["sign"],
        "",
    ]
    if source_sums:
        lines += ["## Sources", ""]
        lines += ["    %s  %s" % (source_sums[name], name) for name in dataset.sources]
        lines += [""]
    lines += ["## Command", "", "    " + command, ""]
    with open(path, "w") as out:
        out.write("\n".join(lines))


def convert(dataset, output, min_count, source_sums, command):
    """Writes the compressed rows, neuron table, metadata and spot checks."""
    import numpy as np

    keep = dataset.count >= min_count
    pre, post, count = dataset.pre[keep], dataset.post[keep], dataset.count[keep]
    n = len(dataset.body_ids)
    row_offsets, columns, values = compressed_rows(n, pre, post, dataset.signs[pre] * count)
    if len(values) >= 2 ** 31 or (len(values) and np.abs(values).max() >= male_cns.FP32_EXACT_LIMIT):
        raise SystemExit("the edge count or a synapse count is out of range")

    dataset.description["min_count"] = min_count
    checks = spot_checks(dataset, row_offsets, columns, values)

    metadata = {
        "dataset": dataset.name,
        "source": dataset.description["source"],
        "neurons_rule": dataset.description["neurons"],
        "sign": dataset.description["sign"],
        "min_count": min_count,
        "neurons": n,
        "edges": int(len(values)),
        "synapses": int(count.sum()),
        "excitatory_edges": int((values > 0).sum()),
        "inhibitory_edges": int((values < 0).sum()),
        "inhibitory_neurons": int((dataset.signs < 0).sum()),
        "max_in_degree": int(np.diff(row_offsets).max()) if n else 0,
        "source_sha256": source_sums,
    }

    os.makedirs(output, exist_ok=True)
    male_cns.write_safetensors(os.path.join(output, TENSORS_OUTPUT), [
        ("row_offsets", "I32", row_offsets),
        ("presynaptic", "I32", columns),
        ("weights", "I32", values),
        ("signs", "I32", dataset.signs),
    ])
    with open(os.path.join(output, NEURONS_OUTPUT), "w") as out:
        out.write("\t".join(NEURON_COLUMNS) + "\n")
        out.write("".join("%d\t%s\n" % (int(b), "\t".join(a))
                          for b, a in zip(dataset.body_ids, dataset.annotations)))
    with open(os.path.join(output, METADATA_OUTPUT), "w") as out:
        json.dump(metadata, out, indent=1)
    with open(os.path.join(output, SPOT_CHECKS_OUTPUT), "w") as out:
        json.dump(checks, out, indent=1)
    write_readme(os.path.join(output, "README.md"), dataset, metadata, source_sums, command)

    outputs = [TENSORS_OUTPUT, NEURONS_OUTPUT, METADATA_OUTPUT, SPOT_CHECKS_OUTPUT, "README.md"]
    with open(os.path.join(output, "SHA256SUMS"), "w") as out:
        out.write("".join("%s  %s\n" % (male_cns.sha256(os.path.join(output, name)), name)
                          for name in outputs))
    return metadata


def main(argv=None):
    """Runs the conversion."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--dataset", required=True,
                        choices=["male-cns", "flywire-630", "flywire-783", "synthetic"])
    parser.add_argument("--source", help="the dataset directory (not used by synthetic)")
    parser.add_argument("--output", required=True, help="the directory to write")
    parser.add_argument("--min-count", type=int, default=1, help="the fewest synapses an edge keeps")
    parser.add_argument("--neurons", type=int, default=40, help="the size of a synthetic connectome")
    args = parser.parse_args(argv)

    if args.dataset == "synthetic":
        dataset = synthetic(args.neurons)
    elif args.source is None:
        parser.error("--source is required for " + args.dataset)
    elif args.dataset == "male-cns":
        dataset = read_male_cns(args.source)
    else:
        dataset = read_flywire(args.source, args.dataset.split("-")[1])

    source_sums = male_cns.source_checksums(args.source, dataset.sources) if dataset.sources else {}
    command = "python " + " ".join([os.path.basename(__file__)] + (argv or sys.argv[1:]))
    metadata = convert(dataset, args.output, args.min_count, source_sums, command)
    print(json.dumps({k: v for k, v in metadata.items() if k != "source_sha256"}))


if __name__ == "__main__":
    main()
