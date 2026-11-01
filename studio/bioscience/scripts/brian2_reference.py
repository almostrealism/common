"""Brian2 reference runs of the Shiu et al. (2024) whole-brain model.

The platform's spiking network (ConnectomeNetwork in studio/bioscience) is
checked against Brian2, the simulator the model was written for:

``synthetic``
    Runs the model's neuron and synapse equations on a converted connectome
    (by default the committed synthetic fixture) under a fixed, seeded schedule
    of membrane kicks, and writes the schedule and every spike as dense
    ``(step, neuron)`` events (the kicks in compressed rows by step, which is
    how the Java test builds its drive). The schedule makes the run deterministic, so
    the Java test can require the same spikes on the same steps.

``circuit``
    Cuts the sugar circuit out of the FlyWire 630 connectome: every neuron that
    fires in a ``sugar`` reference run, with the edges joining them, written as
    a converted connectome by ``connectome_csr``. Then runs it as ``synthetic``
    does, with the model's own synapse weight and the kicks on the sugar
    neurons, for a test that exercises a real connectome at a scale every
    runner can afford.

``sugar``
    Runs the authors' own ``model.run_exp`` unchanged (from a checkout of
    their repository) for the sugar-sensing experiment: Poisson input to the 21
    sugar gustatory receptor neurons. Writes the spike table it produces, the
    mean rate of every neuron in the order of the converted FlyWire connectome
    (the authors' completeness order), and a manifest.

Brian2 (and, for ``circuit`` and ``sugar``, pandas and pyarrow, and for
``sugar`` joblib) must be installed;
they are not in requirements.txt because only these reference runs need them.

Usage:
    brian2_reference.py synthetic --connectome <csr dir> --output <dir>
    brian2_reference.py circuit --source <FlyWire dir> --rates <sugar output> \\
        --connectome-output <dir> --output <dir>
    brian2_reference.py sugar --repo <model repo> --snapshot 630 --rate 150 \\
        --trials 30 --processes 8 --output <dir>
"""

import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import connectome_csr  # noqa: E402
import flywire  # noqa: E402
import male_cns  # noqa: E402

DT_MS = 0.1
KICK_MV = 250 * 0.275
SYNTHETIC_SEED = 20261031
SYNTHETIC_STEPS = 2000
SYNTHETIC_RATE_HZ = 150.0
SYNTHETIC_DRIVEN = (0, 1, 2, 3, 4)
SYNTHETIC_SYNAPSE_MV = 4.0
SYNAPSE_MV = 0.275
CIRCUIT_NAME = "flywire-630-sugar-circuit"
CIRCUIT_SEED = 20261101
CIRCUIT_STEPS = 5000
CIRCUIT_RATE_HZ = 200.0

SUGAR_NEURONS = (
    720575940624963786, 720575940630233916, 720575940637568838, 720575940638202345,
    720575940617000768, 720575940630797113, 720575940632889389, 720575940621754367,
    720575940621502051, 720575940640649691, 720575940639332736, 720575940616885538,
    720575940639198653, 720575940620900446, 720575940617937543, 720575940632425919,
    720575940633143833, 720575940612670570, 720575940628853239, 720575940629176663,
    720575940611875570,
)

KICKS_OUTPUT = "kicks.safetensors"
SPIKES_OUTPUT = "spikes.safetensors"
RATES_OUTPUT = "rates.safetensors"
MANIFEST_OUTPUT = "manifest.json"


def kick_schedule(neurons, driven, steps, rate_hz, seed):
    """Draws a fixed schedule of membrane kicks: independent Bernoulli trials.

    Returns:
        A ``[steps, neurons]`` float array holding ``KICK_MV`` where a driven
        neuron is kicked and 0 elsewhere.
    """
    rng = np.random.default_rng(seed)
    drive = np.zeros((steps, neurons))
    draws = rng.random((steps, len(driven))) < rate_hz * DT_MS * 1e-3
    drive[:, list(driven)] = draws * KICK_MV
    return drive


def events(array):
    """Returns the ``(step, neuron)`` position of every nonzero entry of a
    ``[steps, neurons]`` array, in step-major order, as an int array of shape
    ``[k, 2]``."""
    return np.argwhere(array).astype(np.int64).reshape(-1, 2)


def compressed_steps(array):
    """Returns a ``[steps, neurons]`` array in compressed sparse rows, one row
    per step: the ``steps + 1`` row offsets, and the neuron and value of each
    nonzero entry in step-major order. This is the form the Java test reads a
    kick schedule in, as a collection with a compressed-row ordering."""
    steps, neurons = np.nonzero(array)
    offsets = np.zeros(array.shape[0] + 1, dtype=np.int64)
    np.cumsum(np.bincount(steps, minlength=array.shape[0]), out=offsets[1:])
    return offsets, neurons.astype(np.int64), array[steps, neurons]


def run_kicks(connectome, drive, driven, synapse_mv):
    """Runs the model's equations on a connectome under a kick schedule.

    Args:
        connectome: a dict of the converted tensors (row_offsets, presynaptic,
            weights), rows postsynaptic.
        drive: the ``[steps, neurons]`` kick schedule in millivolts.
        driven: the indices of the kicked neurons, which have no refractory
            period, as Poisson targets have in the model.
        synapse_mv: the synaptic potential of one synapse in millivolts: the
            model's ``SYNAPSE_MV`` for a real connectome; the small synthetic
            connectome needs more to carry activity beyond the kicked neurons.

    Returns:
        The ``[steps, neurons]`` spike array, 1 where a neuron spiked.
    """
    from brian2 import (NeuronGroup, Synapses, SpikeGeneratorGroup, SpikeMonitor,
                        Network, defaultclock, mV, ms)

    steps, n = drive.shape
    defaultclock.dt = DT_MS * ms
    namespace = {"v_0": -52 * mV, "v_rst": -52 * mV, "v_th": -45 * mV,
                 "t_mbr": 20 * ms, "tau": 5 * ms}
    neurons = NeuronGroup(n, """
        dv/dt = (v_0 - v + g) / t_mbr : volt (unless refractory)
        dg/dt = -g / tau               : volt (unless refractory)
        rfc                            : second
        """, method="linear", threshold="v > v_th", reset="v = v_rst; g = 0 * mV",
        refractory="rfc", namespace=namespace)
    neurons.v = -52 * mV
    neurons.g = 0 * mV
    neurons.rfc = 2.2 * ms
    neurons.rfc[list(driven)] = 0 * ms

    offsets = connectome["row_offsets"].astype(np.int64)
    post = np.repeat(np.arange(n), np.diff(offsets))
    synapses = Synapses(neurons, neurons, "w : volt", on_pre="g += w", delay=1.8 * ms)
    synapses.connect(i=connectome["presynaptic"].astype(np.int64), j=post)
    synapses.w = connectome["weights"].astype(np.float64) * synapse_mv * mV

    kick_steps, kick_neurons = np.nonzero(drive)
    generator = SpikeGeneratorGroup(n, kick_neurons, kick_steps * DT_MS * ms)
    kicks = Synapses(generator, neurons, on_pre="v_post += %r * mV" % KICK_MV)
    kicks.connect(j="i")

    monitor = SpikeMonitor(neurons)
    Network(neurons, synapses, generator, kicks, monitor).run(steps * DT_MS * ms)

    spikes = np.zeros((steps, n))
    spike_steps = np.round(np.asarray(monitor.t / ms) / DT_MS).astype(np.int64)
    spikes[spike_steps, np.asarray(monitor.i)] = 1
    return spikes


def write_events(output, drive, spikes):
    """Writes the kicks of a run in compressed rows by step (``kick_offsets``,
    ``kick_neurons``, ``kick_values``) and its spikes as ``(step, neuron)``
    events."""
    os.makedirs(output, exist_ok=True)
    offsets, neurons, values = compressed_steps(drive)
    male_cns.write_safetensors(os.path.join(output, KICKS_OUTPUT), [
        ("kick_offsets", "I32", offsets), ("kick_neurons", "I32", neurons), ("kick_values", "F32", values)])
    male_cns.write_safetensors(os.path.join(output, SPIKES_OUTPUT), [("spikes", "I32", events(spikes))])


def write_manifest(output, manifest):
    """Writes the manifest and the checksums of every file in the output."""
    import brian2

    manifest = dict(manifest, brian2_version=brian2.__version__, dt_ms=DT_MS)
    with open(os.path.join(output, MANIFEST_OUTPUT), "w") as out:
        json.dump(manifest, out, indent=2, sort_keys=True)
        out.write("\n")
    names = sorted(name for name in os.listdir(output) if name != "SHA256SUMS")
    with open(os.path.join(output, "SHA256SUMS"), "w") as out:
        for name in names:
            out.write("%s  %s\n" % (male_cns.sha256(os.path.join(output, name)), name))


def synthetic_command(args):
    connectome = male_cns.read_safetensors(os.path.join(args.connectome, connectome_csr.TENSORS_OUTPUT))
    n = len(connectome["row_offsets"]) - 1
    drive = kick_schedule(n, SYNTHETIC_DRIVEN, args.steps, SYNTHETIC_RATE_HZ, SYNTHETIC_SEED)
    spikes = run_kicks(connectome, drive, SYNTHETIC_DRIVEN, args.synapse_weight)

    write_events(args.output, drive, spikes)
    write_manifest(args.output, {
        "experiment": "synthetic kick schedule",
        "connectome": os.path.basename(os.path.normpath(args.connectome)),
        "steps": args.steps,
        "driven": list(SYNTHETIC_DRIVEN),
        "rate_hz": SYNTHETIC_RATE_HZ,
        "kick_mv": KICK_MV,
        "synapse_mv": args.synapse_weight,
        "seed": SYNTHETIC_SEED,
        "spikes": int(spikes.sum()),
        "spikes_per_neuron": spikes.sum(axis=0).astype(int).tolist(),
    })
    print("%d spikes over %d steps" % (spikes.sum(), args.steps))


def circuit_command(args):
    rates = male_cns.read_safetensors(os.path.join(args.rates, RATES_OUTPUT))["rates"]
    with open(os.path.join(args.rates, MANIFEST_OUTPUT)) as source:
        reference = json.load(source)
    full = connectome_csr.read_flywire(args.source, reference["snapshot"])
    keep = rates > 0
    keep[reference["stimulated_indices"]] = True
    rule = "the neurons that fire in %s (%d trials)" % (reference["experiment"], reference["trials"])
    circuit = full.subset(CIRCUIT_NAME, keep, rule)
    source_sums = male_cns.source_checksums(args.source, circuit.sources)
    command = "python " + " ".join([os.path.basename(__file__)] + sys.argv[1:])
    connectome_csr.convert(circuit, args.connectome_output, 1, source_sums, command)

    connectome = male_cns.read_safetensors(os.path.join(args.connectome_output, connectome_csr.TENSORS_OUTPUT))
    index = {int(b): i for i, b in enumerate(circuit.body_ids)}
    driven = [index[b] for b in SUGAR_NEURONS]
    drive = kick_schedule(len(circuit.body_ids), driven, args.steps, CIRCUIT_RATE_HZ, CIRCUIT_SEED)
    spikes = run_kicks(connectome, drive, driven, SYNAPSE_MV)

    write_events(args.output, drive, spikes)
    write_manifest(args.output, {
        "experiment": "sugar circuit kick schedule",
        "connectome": CIRCUIT_NAME,
        "circuit_source": reference["experiment"],
        "steps": args.steps,
        "driven": driven,
        "rate_hz": CIRCUIT_RATE_HZ,
        "kick_mv": KICK_MV,
        "synapse_mv": SYNAPSE_MV,
        "seed": CIRCUIT_SEED,
        "spikes": int(spikes.sum()),
        "spikes_per_neuron": spikes.sum(axis=0).astype(int).tolist(),
    })
    print("%d neurons, %d spikes over %d steps" % (len(circuit.body_ids), spikes.sum(), args.steps))


def sugar_command(args):
    import pandas as pd
    from brian2 import Hz

    sys.path.insert(0, args.repo)
    import model

    neurons_file, connectivity_file = flywire.SNAPSHOTS[args.snapshot]
    name = "sugarR_%s_%gHz" % (args.snapshot, args.rate)
    params = dict(model.default_params)
    params["r_poi"] = args.rate * Hz
    params["n_run"] = args.trials
    os.makedirs(args.output, exist_ok=True)
    model.run_exp(exp_name=name, neu_exc=list(SUGAR_NEURONS), path_res=args.output,
                  path_comp=os.path.join(args.repo, neurons_file),
                  path_con=os.path.join(args.repo, connectivity_file),
                  params=params, n_proc=args.processes, force_overwrite=True)

    order = pd.read_csv(os.path.join(args.repo, neurons_file), index_col=0).index
    spikes = pd.read_parquet(os.path.join(args.output, name + ".parquet"))
    duration_s = float(params["t_run"] / model.ms) * 1e-3
    counts = spikes.groupby("flywire_id").size().reindex(order, fill_value=0)
    rates = counts.to_numpy(dtype=np.float64) / args.trials / duration_s
    male_cns.write_safetensors(os.path.join(args.output, RATES_OUTPUT), [("rates", "F32", rates)])
    write_manifest(args.output, {
        "experiment": name,
        "snapshot": args.snapshot,
        "rate_hz": args.rate,
        "trials": args.trials,
        "duration_s": duration_s,
        "stimulated": [str(i) for i in SUGAR_NEURONS],
        "stimulated_indices": [int(order.get_loc(i)) for i in SUGAR_NEURONS],
        "neurons": len(order),
        "source_sha256": {f: male_cns.sha256(os.path.join(args.repo, f))
                          for f in (neurons_file, connectivity_file, "model.py")},
    })
    print("%s: %d neurons active, mean active rate %.2f Hz" % (name, (rates > 0).sum(), rates[rates > 0].mean()))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    synthetic = commands.add_parser("synthetic", help="deterministic spikes on a converted connectome")
    synthetic.add_argument("--connectome", required=True, help="a connectome_csr.py output directory")
    synthetic.add_argument("--output", required=True)
    synthetic.add_argument("--steps", type=int, default=SYNTHETIC_STEPS)
    synthetic.add_argument("--synapse-weight", type=float, default=SYNTHETIC_SYNAPSE_MV,
                           help="synaptic potential of one synapse in mV")
    synthetic.set_defaults(run=synthetic_command)

    circuit = commands.add_parser("circuit", help="deterministic spikes on the FlyWire sugar circuit")
    circuit.add_argument("--source", required=True, help="the FlyWire snapshot directory")
    circuit.add_argument("--rates", required=True, help="a sugar command output directory")
    circuit.add_argument("--connectome-output", required=True, help="the directory to write the circuit to")
    circuit.add_argument("--output", required=True, help="the directory to write the Brian2 run to")
    circuit.add_argument("--steps", type=int, default=CIRCUIT_STEPS)
    circuit.set_defaults(run=circuit_command)

    sugar = commands.add_parser("sugar", help="the authors' sugar experiment")
    sugar.add_argument("--repo", required=True, help="checkout of the authors' model repository")
    sugar.add_argument("--snapshot", choices=sorted(flywire.SNAPSHOTS), default="630")
    sugar.add_argument("--rate", type=float, default=150.0, help="Poisson rate in Hz")
    sugar.add_argument("--trials", type=int, default=30)
    sugar.add_argument("--processes", type=int, default=1)
    sugar.add_argument("--output", required=True)
    sugar.set_defaults(run=sugar_command)

    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    main()
