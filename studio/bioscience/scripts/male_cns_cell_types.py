"""Aggregates the MaleCNS v1.0 connectome to a signed cell-type-by-cell-type matrix.

Usage (in a virtual environment with studio/bioscience/scripts/requirements.txt):

    python male_cns_cell_types.py --source /Users/Shared/models/male-cns-v1.0 \\
        --output <directory>
    python male_cns_cell_types.py rates --matrix <cell-type matrix> --output <directory>
    python male_cns_cell_types.py circuit --source <male-cns release> \\
        --matrix <cell-type matrix> --rates <rates directory> --output <directory>

The first form writes the full matrix. ``rates`` runs the rate model's sugar
experiment (no drive, the sugar GRNs, and the control set the Java test draws)
on the full matrix in FP64 and writes every type's rates (``rates.safetensors``
and ``manifest.json``). ``circuit`` cuts a sub-matrix of at most
``CIRCUIT_LIMIT`` types for that experiment out of the full matrix, with spot
checks and ``rate-reference.json`` holding the rates the experiment reaches on
it. The cut is small enough to commit, so its tensors are written as text
(``cell-type-counts.tsv`` and ``cell-type-weights.tsv``, which
``CellTypeConnectome`` reads when there is no safetensors file) instead of
``cell-type-weights.safetensors``.

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
WEIGHTS_TEXT_OUTPUT = "cell-type-weights.tsv"
COUNTS_OUTPUT = "cell-type-counts.tsv"

LARGEST_CHECKS = 100
SAMPLED_CHECKS = 80
ZERO_CHECKS = 20
SEED = 20261026
CHUNK_ROWS = 20_000_000

RATE_CONSTANTS = {"dt": 1e-3, "tau": 0.02, "r_max": 100.0, "gain": 0.003, "drive": 50.0, "steps": 1000}
RATE_RUNS = ("baseline", "grn", "control")
RATES_OUTPUT = "rates.safetensors"
MANIFEST_OUTPUT = "manifest.json"
RATE_REFERENCE_OUTPUT = "rate-reference.json"
SUGAR_GRNS = ("LB3a", "LB3b", "LB3c", "LB3d")
MN9 = "MN9"
CONTROL_SEED = 20261026
CIRCUIT_LIMIT = 250
INFLUENCE_HOPS = 2
CIRCUIT_LARGEST_CHECKS = 50
CIRCUIT_SAMPLED_CHECKS = 40
CIRCUIT_ZERO_CHECKS = 10
CIRCUIT_COMMAND = ("python male_cns_cell_types.py circuit --source <male-cns release>"
                   " --matrix <models-derived>/male-cns/cell-type-matrix"
                   " --rates <models-derived>/male-cns/cell-type-rate-reference"
                   " --output src/test/resources/connectome/cell-type-sugar-circuit")


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


def choose_pairs(matrix, largest_checks=LARGEST_CHECKS, sampled_checks=SAMPLED_CHECKS,
                 zero_checks=ZERO_CHECKS):
    """Chooses the (post, pre) index pairs to spot check.

    Args:
        matrix: the [T, T] matrix.
        largest_checks: the number of largest entries.
        sampled_checks: the number of sampled nonzero entries.
        zero_checks: the number of zero entries.

    Returns:
        A list of ``(post, pre, kind)``: the largest entries by magnitude,
        nonzero entries sampled with a fixed seed, and entries that are zero.
    """
    import numpy as np

    t = matrix.shape[0]
    flat = matrix.reshape(-1)
    magnitude = np.abs(flat)
    largest = np.argpartition(magnitude, -largest_checks)[-largest_checks:]
    chosen = [(int(i), "largest") for i in largest]
    taken = set(int(i) for i in largest)

    rng = np.random.default_rng(SEED)
    nonzero = np.flatnonzero(flat)
    for i in rng.choice(nonzero, size=sampled_checks * 2, replace=False):
        if len(chosen) >= largest_checks + sampled_checks:
            break
        if int(i) not in taken:
            chosen.append((int(i), "sampled"))
            taken.add(int(i))

    zeros = 0
    while zeros < zero_checks:
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


class JavaRandom:
    """The ``java.util.Random`` generator, so a seeded draw matches the Java tests'.

    ``java.util.Random`` is a 48-bit linear congruential generator whose seed is
    scrambled with the multiplier. ``next_int(bound)`` follows the documented
    algorithm: a power-of-two bound takes the high bits, any other bound uses
    the rejection loop that avoids modulo bias.
    """

    MULTIPLIER = 0x5DEECE66D
    ADDEND = 0xB
    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ self.MULTIPLIER) & self.MASK

    def next_bits(self, bits):
        """Returns the next ``bits`` random bits as a signed 32-bit int."""
        self.seed = (self.seed * self.MULTIPLIER + self.ADDEND) & self.MASK
        value = self.seed >> (48 - bits)
        return value - (1 << 32) if value >= (1 << 31) else value

    def next_int(self, bound):
        """Returns ``nextInt(bound)``: a value in ``[0, bound)``."""
        if bound <= 0:
            raise ValueError("bound must be positive")
        r = self.next_bits(31)
        m = bound - 1
        if bound & m == 0:
            return (bound * r) >> 31
        u = r
        r = u % bound
        while u - r + m >= (1 << 31):
            u = self.next_bits(31)
            r = u % bound
        return r


def matched_control(grns, superclasses, classes, out_synapses, seed=CONTROL_SEED):
    """Chooses the control types of the sugar experiment.

    This is the rule of ``CellTypeRateModelTest.matchedControl``, with the same
    candidate order and the same ``java.util.Random`` stream: for each GRN type,
    in order, one type whose superclass contains ``sensory``, whose class is not
    ``gustatory``, whose outgoing synapse count is within 20% of the GRN type's
    and which is not already chosen.

    Args:
        grns: the GRN type indices.
        superclasses: the superclass of every type, in index order.
        classes: the class of every type, in index order.
        out_synapses: the outgoing synapse count of every type.
        seed: the ``java.util.Random`` seed.

    Returns:
        The control type indices, one per GRN type.
    """
    random = JavaRandom(seed)
    chosen = []
    for grn in grns:
        target = float(out_synapses[grn])
        candidates = [i for i in range(len(superclasses))
                      if "sensory" in superclasses[i] and classes[i] != "gustatory"
                      and abs(float(out_synapses[i]) - target) <= 0.2 * target
                      and i not in chosen]
        if not candidates:
            raise SystemExit("no control candidate for type %d" % grn)
        chosen.append(candidates[random.next_int(len(candidates))])
    return chosen


def experiment_driven(names, superclasses, classes, out_synapses):
    """Returns the driven type names of each run of the sugar experiment.

    The baseline drives nothing, the GRN run drives ``SUGAR_GRNS`` and the
    control run drives the types ``matched_control`` draws for them.

    Args:
        names: the type names, in index order.
        superclasses: the superclass of every type, in index order.
        classes: the class of every type, in index order.
        out_synapses: the outgoing synapse count of every type.

    Returns:
        A dict from run name to the driven type names, in order.
    """
    index = {name: i for i, name in enumerate(names)}
    grns = [index[n] for n in SUGAR_GRNS]
    control = [names[i] for i in matched_control(grns, superclasses, classes, out_synapses)]
    return {"baseline": [], "grn": list(SUGAR_GRNS), "control": control}


def rate_step(weights, scale, rates, drive, constants):
    """Returns the rates after one step of the ``RatePopulation`` update.

    With ``x = g * s (.) (W r) + input`` and ``f(x) = rMax * tanh(max(x, 0) / rMax)``
    the step is ``r' = decay * r + (1 - decay) * f(x)``, ``decay = exp(-dt / tau)``.

    Args:
        weights: the [T, T] matrix, row = postsynaptic type.
        scale: the per-row scale ``s``.
        rates: the current rates ``r``.
        drive: the external input of every type.
        constants: a dict with ``gain``, ``dt``, ``tau`` and ``r_max``.
    """
    import numpy as np

    decay = np.exp(-constants["dt"] / constants["tau"])
    x = constants["gain"] * scale * (weights @ rates) + drive
    r_max = constants["r_max"]
    return decay * rates + (1.0 - decay) * (r_max * np.tanh(np.maximum(x, 0.0) / r_max))


def rate_run(weights, scale, driven, target, constants):
    """Runs the rate model from zero rates with the given types driven.

    Args:
        weights: the [T, T] matrix, row = postsynaptic type.
        scale: the per-row scale.
        driven: the driven type indices; each receives ``constants["drive"]``.
        target: the index of the type whose rate is traced.
        constants: a dict with ``gain``, ``dt``, ``tau``, ``r_max``, ``drive`` and ``steps``.

    Returns:
        ``(final, peak, trace)``: every type's rate after the last step, every
        type's largest rate over the run, and the target's rate after each step.
    """
    import numpy as np

    t = weights.shape[0]
    drive = np.zeros(t)
    drive[list(driven)] = constants["drive"]
    rates = np.zeros(t)
    peak = np.zeros(t)
    trace = np.zeros(constants["steps"])
    for step in range(constants["steps"]):
        rates = rate_step(weights, scale, rates, drive, constants)
        peak = np.maximum(peak, rates)
        trace[step] = rates[target]
    return rates, peak, trace


def influence(coupling, rates, target, hops):
    """Scores every type by how strongly its activity reaches the target type.

    Starting from the target, the reach is carried ``hops`` synapses upstream
    through the active types (those with a rate above zero), each hop through
    the magnitude of the effective coupling and normalized to a largest value
    of one. A type's score is the largest, over the hops, of its reach times
    its rate.

    Args:
        coupling: the effective [T, T] coupling ``g * s (.) W``, row = postsynaptic.
        rates: the rate of every type in the run the cut must preserve.
        target: the target type index.
        hops: the number of upstream synapses followed.

    Returns:
        The score of every type.
    """
    import numpy as np

    active = (rates > 0.0).astype(np.float64)
    reach = np.zeros(len(rates))
    reach[target] = 1.0
    score = np.zeros(len(rates))
    magnitude = np.abs(coupling)
    for _ in range(hops):
        reach = magnitude.T @ (reach * active)
        if reach.max() > 0.0:
            reach = reach / reach.max()
        score = np.maximum(score, reach * rates)
    return score


def select_types(peaks, scores, required, limit):
    """Chooses the types the circuit keeps.

    Every type whose peak rate is above zero in any of the given runs is
    active. A type whose rate stays exactly zero adds nothing to any other
    type's input, so when at most ``limit`` types (counting the required ones)
    are active, all of them are kept and the cut is exact. Otherwise the
    required types are kept and the remaining places go to the types of largest
    score.

    Args:
        peaks: a list of per-type peak-rate arrays, one per run.
        scores: the per-type ranking used when not every active type fits.
        required: the type indices that are always kept.
        limit: the largest number of types to keep.

    Returns:
        ``(kept, exact)``: the sorted kept indices and whether every active
        type was kept.
    """
    import numpy as np

    peak = np.max(np.stack(peaks), axis=0)
    required = set(int(i) for i in required)
    active = set(int(i) for i in np.flatnonzero(peak > 0.0)) | required
    if len(active) <= limit:
        return sorted(active), True
    if len(required) > limit:
        raise SystemExit("the required types exceed the limit")
    ranked = [int(i) for i in np.argsort(-np.asarray(scores), kind="stable") if int(i) not in required]
    return sorted(required | set(ranked[:limit - len(required)])), False


def restrict_bodies(body_ids, body_types, superclasses, classes, kept_names):
    """Restricts the typed bodies to the members of the kept types.

    Returns:
        ``(body_ids, body_types, superclasses, classes)`` for the members only.
    """
    keep = [i for i, name in enumerate(body_types) if name in kept_names]
    return (body_ids[keep], [body_types[i] for i in keep],
            [superclasses[i] for i in keep], [classes[i] for i in keep])


def read_matrix(directory):
    """Reads a cell-type matrix directory written by this script.

    Returns:
        ``(names, superclasses, classes, tensors)`` with ``tensors`` the dict of
        ``weights``, ``type_sizes`` and ``type_out_synapses``.
    """
    with open(os.path.join(directory, TYPES_OUTPUT)) as source:
        names = [line.rstrip("\n") for line in source if line.strip()]
    superclasses, classes = [], []
    with open(os.path.join(directory, ANNOTATIONS_OUTPUT)) as source:
        rows = [line.rstrip("\n") for line in source if line.strip()][1:]
    for name, row in zip(names, rows):
        fields = row.split("\t")
        if fields[0] != name:
            raise SystemExit("annotations do not follow " + TYPES_OUTPUT)
        superclasses.append(fields[1])
        classes.append(fields[2])
    return names, superclasses, classes, male_cns.read_safetensors(os.path.join(directory, WEIGHTS_OUTPUT))


def experiment(weights, sizes, names, driven_sets, constants):
    """Runs the baseline, GRN and control runs of the sugar experiment.

    Args:
        weights: the [T, T] matrix as float64.
        sizes: the neurons per type; the row scale is ``1 / size``.
        names: the type names.
        driven_sets: a dict from run name to the driven type names.
        constants: the rate constants.

    Returns:
        A dict from run name to ``(final, peak, trace)`` of ``rate_run``.
    """
    import numpy as np

    index = {name: i for i, name in enumerate(names)}
    scale = 1.0 / np.asarray(sizes, dtype=np.float64)
    results = {}
    for run in RATE_RUNS:
        results[run] = rate_run(weights, scale, [index[n] for n in driven_sets[run]], index[MN9], constants)
        print("run=%s mn9=%r" % (run, results[run][0][index[MN9]]), file=sys.stderr)
    return results


def rates_main(argv):
    """Runs the full-matrix rate reference and writes rates.safetensors and manifest.json."""
    import numpy as np

    parser = argparse.ArgumentParser(description="Runs the sugar rate experiment on the full matrix.")
    parser.add_argument("--matrix", required=True, help="the converted cell-type matrix directory")
    parser.add_argument("--output", required=True, help="the directory to write")
    args = parser.parse_args(argv)

    names, superclasses, classes, tensors = read_matrix(args.matrix)
    index = {name: i for i, name in enumerate(names)}
    driven = experiment_driven(names, superclasses, classes, tensors["type_out_synapses"])

    weights = tensors["weights"].astype(np.float64)
    results = experiment(weights, tensors["type_sizes"], names, driven, RATE_CONSTANTS)

    os.makedirs(args.output, exist_ok=True)
    arrays = []
    for run in RATE_RUNS:
        final, peak, trace = results[run]
        arrays += [(run + "_final", "F64", final), (run + "_peak", "F64", peak), (run + "_mn9_trace", "F64", trace)]
    male_cns.write_safetensors(os.path.join(args.output, RATES_OUTPUT), arrays)

    manifest = {
        "constants": RATE_CONSTANTS,
        "row_scale": "1 / type_size",
        "control_seed": CONTROL_SEED,
        "driven": driven,
        "target": MN9,
        "mn9_final": {run: float(results[run][0][index[MN9]]) for run in RATE_RUNS},
        "active_types": {run: int((results[run][1] > 0.0).sum()) for run in RATE_RUNS},
        "matrix_sha256": male_cns.sha256(os.path.join(args.matrix, WEIGHTS_OUTPUT)),
        "command": "python " + " ".join([os.path.basename(__file__), "rates"] + argv),
    }
    with open(os.path.join(args.output, MANIFEST_OUTPUT), "w") as out:
        json.dump(manifest, out, indent=1)
    print(json.dumps({k: manifest[k] for k in ("driven", "mn9_final", "active_types")}))


def circuit_experiment(matrix, sizes, names, driven, reference, kept):
    """Runs the sugar experiment on a cut and measures its deviation from the full run.

    Args:
        matrix: the cut [K, K] matrix.
        sizes: the full-matrix type sizes of the kept types.
        names: the kept type names, in index order.
        driven: a dict from run name to the driven type names.
        reference: the full-matrix ``rates.safetensors`` tensors.
        kept: the full-matrix index of every kept type.

    Returns:
        ``(results, deviation)``: the ``experiment`` results and, per driven
        run, the cut's MN9 deviation and its largest deviation over kept types.
    """
    import numpy as np

    results = experiment(np.asarray(matrix, dtype=np.float64), sizes, names, driven, RATE_CONSTANTS)
    target = names.index(MN9)
    deviation = {}
    for run in ("grn", "control"):
        difference = results[run][0] - reference[run + "_final"][kept]
        deviation[run] = {"mn9": float(difference[target]), "max_abs": float(np.abs(difference).max())}
    return results, deviation


def write_text_tensors(directory, names, matrix, sizes, out_synapses):
    """Writes a matrix in the text layout: the per-type counts and the non-zero entries.

    Args:
        directory: the output directory.
        names: the type names, in index order.
        matrix: the [T, T] matrix, row = postsynaptic type.
        sizes: the neurons of each type.
        out_synapses: the outgoing synapses of each type.
    """
    import numpy as np

    with open(os.path.join(directory, COUNTS_OUTPUT), "w") as out:
        out.write("type\tsize\tout_synapses\n")
        out.write("".join("%s\t%d\t%d\n" % (n, s, o) for n, s, o in zip(names, sizes, out_synapses)))
    values = np.asarray(matrix, dtype=np.float32)
    with open(os.path.join(directory, WEIGHTS_TEXT_OUTPUT), "w") as out:
        out.write("post\tpre\tweight\n")
        out.write("".join("%d\t%d\t%.9g\n" % (post, pre, values[post, pre])
                          for post, pre in zip(*np.nonzero(values))))


def read_text_tensors(directory):
    """Reads the tensors of a matrix written by ``write_text_tensors``.

    Returns:
        a dictionary of ``weights`` (float32, [T, T]), ``type_sizes`` and
        ``type_out_synapses`` (int32, [T]).
    """
    import numpy as np

    with open(os.path.join(directory, COUNTS_OUTPUT)) as source:
        rows = [line.rstrip("\n").split("\t") for line in source][1:]
    weights = np.zeros((len(rows), len(rows)), dtype=np.float32)
    with open(os.path.join(directory, WEIGHTS_TEXT_OUTPUT)) as source:
        for line in list(source)[1:]:
            post, pre, value = line.split("\t")
            weights[int(post), int(pre)] = float(value)
    return {"weights": weights,
            "type_sizes": np.array([int(r[1]) for r in rows], dtype=np.int32),
            "type_out_synapses": np.array([int(r[2]) for r in rows], dtype=np.int32)}


def write_circuit_readme(path, source, names, reference, cut, command):
    """Writes the README of the committed rate circuit.

    The README is committed, so ``command`` is the regeneration command written with the
    ``<male-cns release>``/``<models-derived>`` placeholders of the module README rather than the paths
    of the host that ran it; the source directory actually read is recorded separately.
    """
    full = reference["full_matrix"]["mn9_final"]
    lines = [
        "# MaleCNS v1.0 cell-type sugar rate circuit",
        "",
        "A sub-matrix of the MaleCNS v1.0 cell-type matrix (Janelia FlyEM, CC-BY 4.0), cut by",
        "`studio/bioscience/scripts/male_cns_cell_types.py circuit` in almostrealism/common for the",
        "rate model's sugar experiment, with the rates that model reaches on it.",
        "",
        "## Contents",
        "",
        "- `%s`, `%s`, `%s` and `%s`: the matrix of %d types, in the text layout"
        % (WEIGHTS_TEXT_OUTPUT, COUNTS_OUTPUT, TYPES_OUTPUT, ANNOTATIONS_OUTPUT, len(names)),
        "  `CellTypeConnectome` reads when there is no `%s`. `%s` lists the" % (WEIGHTS_OUTPUT, WEIGHTS_TEXT_OUTPUT),
        "  post index, pre index and value of every non-zero entry of the exact sub-block of the",
        "  full matrix (re-aggregated from the member bodies of the kept types and compared with it).",
        "  `%s` keeps each type's full-matrix size and outgoing synapse count, so the row" % COUNTS_OUTPUT,
        "  scale `1 / type_size` is the full run's.",
        "- `%s`: entries recomputed from the raw edge table by a separate path." % SPOT_CHECKS_OUTPUT,
        "- `%s`: the constants, the driven types, and every kept type's final rate in the" % RATE_REFERENCE_OUTPUT,
        "  GRN and control runs of this sub-matrix (FP64 NumPy), with the full-matrix MN9 values.",
        "",
        "## Selection",
        "",
        "The full run activates %d (GRN) and %d (control) of the full matrix's types, more than the"
        % (reference["full_matrix"]["active_types"]["grn"], reference["full_matrix"]["active_types"]["control"]),
        "%d this cut may keep, so the cut is %s. The driven types and MN9 are kept, and the other"
        % (CIRCUIT_LIMIT, "exact" if cut["exact"] else "not exact"),
        "places go to the types whose activity reaches MN9 most strongly: the largest, over %d"
        % cut["influence_hops"],
        "upstream hops through active types, of the normalized coupling reach to MN9 times the type's",
        "rate. Dropping the other active types removes their input, so rates on the cut differ from",
        "the full run:",
        "",
        "| Run | MN9, full matrix | MN9, cut | Largest deviation over kept types |",
        "|---|---|---|---|",
    ]
    for run in ("grn", "control"):
        lines.append("| %s | %.4f | %.4f | %.4f |" % (run, full[run], reference["mn9_final"][run],
                                                     cut["deviation"][run]["max_abs"]))
    lines += [
        "",
        "MN9's GRN-over-control margin is %.4f on the cut against %.4f on the full matrix."
        % (cut["margin"], cut["full_margin"]),
        "The tests' expectations are the cut's own rates, which the platform must reproduce.",
        "",
        "## Sources",
        "",
        "Source directory: `%s`; full matrix SHA-256 `%s`." % (source, reference["full_matrix"]["matrix_sha256"]),
        "",
        "## Command",
        "",
        "    " + command,
        "",
    ]
    with open(path, "w") as out:
        out.write("\n".join(lines))


def circuit_main(argv):
    """Cuts the rate circuit out of the full matrix and writes the committed fixture."""
    import numpy as np

    parser = argparse.ArgumentParser(description="Cuts the sugar rate circuit out of the cell-type matrix.")
    parser.add_argument("--source", required=True, help="the MaleCNS v1.0 release directory")
    parser.add_argument("--matrix", required=True, help="the converted full cell-type matrix directory")
    parser.add_argument("--rates", required=True, help="the directory written by the rates mode")
    parser.add_argument("--output", required=True, help="the directory to write")
    args = parser.parse_args(argv)

    names, superclasses, classes, tensors = read_matrix(args.matrix)
    with open(os.path.join(args.rates, MANIFEST_OUTPUT)) as source:
        manifest = json.load(source)
    if manifest["matrix_sha256"] != male_cns.sha256(os.path.join(args.matrix, WEIGHTS_OUTPUT)):
        raise SystemExit("the rate reference was not computed from this matrix")
    reference = male_cns.read_safetensors(os.path.join(args.rates, RATES_OUTPUT))

    index = {name: i for i, name in enumerate(names)}
    driven = manifest["driven"]
    if driven != experiment_driven(names, superclasses, classes, tensors["type_out_synapses"]):
        raise SystemExit("the rate reference does not drive the types of the sugar experiment")
    required = [index[n] for n in driven["grn"] + driven["control"]] + [index[MN9]]
    full = tensors["weights"].astype(np.float64)
    sizes = tensors["type_sizes"]
    coupling = RATE_CONSTANTS["gain"] * (1.0 / sizes.astype(np.float64))[:, None] * full
    preserved = np.maximum(reference["grn_final"], reference["control_final"])
    scores = influence(coupling, preserved, index[MN9], INFLUENCE_HOPS)
    del coupling
    kept, exact = select_types([reference["grn_peak"], reference["control_peak"]], scores, required, CIRCUIT_LIMIT)
    kept_names = [names[i] for i in kept]

    male_cns.source_checksums(args.source, male_cns.SOURCE_FILES)
    body_ids, body_types, body_superclasses, body_classes = male_cns.read_typed_bodies(args.source)
    body_ids, body_types, body_superclasses, body_classes = restrict_bodies(
        body_ids, body_types, body_superclasses, body_classes, set(kept_names))
    transmitters = male_cns.read_transmitters(args.source)
    weights = male_cns.read_weights(args.source)
    cut_names, matrix, cut_sizes, _, _ = aggregate(body_ids, body_types, transmitters, weights)
    if cut_names != kept_names or not np.array_equal(cut_sizes, sizes[kept]):
        raise SystemExit("the cut's types do not match the full matrix")
    if not np.array_equal(matrix, full[np.ix_(kept, kept)]):
        raise SystemExit("the cut is not the sub-block of the full matrix")

    checks = recompute(choose_pairs(matrix, CIRCUIT_LARGEST_CHECKS, CIRCUIT_SAMPLED_CHECKS, CIRCUIT_ZERO_CHECKS),
                       cut_names, body_ids, body_types, transmitters, weights)
    for check in checks:
        if check["value"] != matrix[check["post_index"], check["pre_index"]]:
            raise SystemExit("spot check disagrees with the aggregate: " + json.dumps(check))

    results, deviation = circuit_experiment(matrix, sizes[kept], cut_names, driven, reference, kept)
    target = cut_names.index(MN9)
    mn9 = {run: float(results[run][0][target]) for run in RATE_RUNS}
    if mn9["baseline"] != 0.0:
        raise SystemExit("the baseline run of the cut is not silent")
    full_mn9 = manifest["mn9_final"]
    cut = {
        "exact": exact,
        "kept_types": len(kept),
        "influence_hops": INFLUENCE_HOPS,
        "deviation": deviation,
        "margin": mn9["grn"] - mn9["control"],
        "full_margin": full_mn9["grn"] - full_mn9["control"],
    }
    if cut["margin"] <= 0.0:
        raise SystemExit("the cut does not preserve MN9's GRN-over-control margin")

    rate_reference = {
        "constants": RATE_CONSTANTS,
        "row_scale": "1 / type_size",
        "driven": driven,
        "target": MN9,
        "mn9_final": mn9,
        "final": {run: [float(v) for v in results[run][0]] for run in ("grn", "control")},
        "full_matrix": {"mn9_final": full_mn9, "active_types": manifest["active_types"],
                        "matrix_sha256": manifest["matrix_sha256"]},
        "cut": cut,
    }

    os.makedirs(args.output, exist_ok=True)
    write_text_tensors(args.output, cut_names, matrix, sizes[kept], tensors["type_out_synapses"][kept])
    with open(os.path.join(args.output, TYPES_OUTPUT), "w") as out:
        out.write("".join(name + "\n" for name in cut_names))
    with open(os.path.join(args.output, ANNOTATIONS_OUTPUT), "w") as out:
        out.write("type\tsuperclass\tclass\n")
        out.write("".join("%s\t%s\t%s\n" % (names[i], superclasses[i], classes[i]) for i in kept))
    with open(os.path.join(args.output, SPOT_CHECKS_OUTPUT), "w") as out:
        json.dump(checks, out, indent=1)
    with open(os.path.join(args.output, RATE_REFERENCE_OUTPUT), "w") as out:
        json.dump(rate_reference, out, indent=1)

    write_circuit_readme(os.path.join(args.output, "README.md"), args.source, cut_names, rate_reference, cut,
                         CIRCUIT_COMMAND)

    outputs = [WEIGHTS_TEXT_OUTPUT, COUNTS_OUTPUT, TYPES_OUTPUT, ANNOTATIONS_OUTPUT, SPOT_CHECKS_OUTPUT,
               RATE_REFERENCE_OUTPUT, "README.md"]
    with open(os.path.join(args.output, "SHA256SUMS"), "w") as out:
        out.write("".join("%s  %s\n" % (male_cns.sha256(os.path.join(args.output, n)), n) for n in outputs))
    print(json.dumps({"kept": len(kept), "exact": exact, "mn9": mn9, "cut": cut,
                      "spot_checks": len(checks)}))


def main(argv=None):
    """Runs the conversion, or the ``rates`` or ``circuit`` mode when named first."""
    import numpy as np

    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "rates":
        return rates_main(argv[1:])
    if argv and argv[0] == "circuit":
        return circuit_main(argv[1:])

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
