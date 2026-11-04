# Simulating a Neuron-Level Connectome

This page follows one capability end to end: loading a real brain circuit as a signed
compressed-sparse-row connectome, and running a spiking simulation of leaky integrate-and-fire
neurons with exponentially decaying synaptic potentials (in millivolts, not conductances) over it that
reproduces, spike for spike, a Brian2 reference run of the same circuit under the same inputs. With
its default constants the network's equations are those of the whole-brain model of Shiu et al.
(2024), "A Drosophila computational brain model reveals sensorimotor processing", which its authors
simulate with Brian2; the committed fixtures validate the platform against Brian2 on that model's
equations at test scale, while reproducing the full published whole-brain experiment is larger,
rate-based work on the curated datasets (section 7). Each piece has thorough Javadoc; this page
shows how the pieces fit together, from a dataset on disk to a recorded raster.

This is a different, finer model than the one in
[Simulating a Spiking Neural Population](neural-population-simulation.md). That page is the
**aggregate** path: a `LeakyIntegrateAndFirePopulation` or `RatePopulation`, where one collection
stands for a whole population's state and connectivity is a dense matrix. This page is the
**neuron-level** path: every neuron of a named connectome is one unit, every stored synapse is one
edge of a sparse matrix, and the connectivity is the measured wiring of a brain. Read the
population page first for the shared machinery — device-resident state, the ring-buffer delay, the
exact-exponential LIF step, the reproducible `CounterRandom` drive, and `SimulationRunner` as the
loop owner — which this page links rather than restates.

The worked example is `NeuronLevelConnectomeSimulationExamplesTest` in `studio/bioscience`
(`org.almostrealism.bioscience.connectome.test`). Code blocks on this page are taken from that test
unless they are marked as fragments. The test asserts every quantitative claim the page makes on
the committed synthetic connectome, so the page cannot silently drift from the code.

---

## 1. What the model is and where each part lives

The subsystem spans four modules, each holding the part that matches its place in the stack:

| Part | Module | Classes / files |
|---|---|---|
| The CSR ordering and the sparse product | `compute/algebra` (`org.almostrealism.collect`) | `CompressedRowTraversalOrdering`, the `matmul` lowering |
| The loop that steps the network forward | `compute/time` (`org.almostrealism.time`) | `SimulationRunner`, `Temporal` |
| The neuron and the Poisson-input `Block`s | `domain/biology` (`org.almostrealism.biology.neuron`) | `ExponentialSynapseNeurons`, `PoissonInput` |
| The connectome loader, the network, the PDSL step and the ingest scripts | `studio/bioscience` (`org.almostrealism.bioscience.connectome`, `scripts/`) | `NeuronConnectome`, `ConnectomeNetwork`, `/pdsl/connectome.pdsl`, `connectome_csr.py`, `brian2_reference.py` |

The split follows the "what does not belong here" rule of the module READMEs. A general sparse or
tensor operation belongs in `compute/`, where every domain can use it, so the compressed-sparse-row
ordering and the product that reads it live there. A biological model that needs neither `ar-ml`
nor a third-party data format belongs in `domain/biology`, where lower layers can use it — so the
neuron and the Poisson input, which are pure dynamics, live there as `Block`s. Code that reads a
third-party connectome format through the engine's tensor machinery, or composes a whole network,
belongs in `studio/bioscience`, which depends on both `ar-biology` and `ar-ml`. No model owns the
loop that drives it: that loop belongs to `SimulationRunner` in `compute/time`.

The neuron and the Poisson input are Java types rather than PDSL primitives because their dynamics
need comparisons (threshold, refractory) and persistent state (membrane, synapse, countdown) that
PDSL cannot express yet. As `Block`s they are still placed in a PDSL pipe by naming them
(section 4), so the network around them is composed in PDSL even though its two innermost stages
are Java.

---

## 2. The CSR connectome representation

`connectome_csr.py` reduces every supported dataset — MaleCNS, the FlyWire snapshots distributed
with Shiu et al. (2024), and a small synthetic connectome drawn from a fixed seed — to one form, so
one Java loader reads them all. For `N` neurons and `E` stored edges it writes a directory holding
a tensor file and a neuron table, which together are the connectome, plus metadata and provenance
files:

- `connectome.safetensors`, read through `StateDictionary`, holding
  - `row_offsets` (`[N + 1]`): where each postsynaptic neuron's edges begin and end,
  - `presynaptic` (`[E]`): the presynaptic neuron of each stored edge,
  - `weights` (`[E]`): the presynaptic neuron's sign times the synapse count, and
  - `signs` (`[N]`): +1 or -1 per neuron (inhibitory neurons carry -1);
- `neurons.tsv`: a header line, then the body id, type, superclass, class, predicted
  transmitter and side of every neuron, tab-separated, in index order. The side is `L`, `R` or `M`
  (midline): the soma side, or for a sensory neuron, whose soma lies outside the nervous system,
  the side its axon enters on; FlyWire's tables carry no side, so it is empty there. Body ids are kept out of the tensors
  because FlyWire root ids exceed the `2^53` a tensor value holds exactly;
- `connectome.json`: the dataset, the filters, the sign rule and the totals (neurons, edges,
  synapses);
- `spot-checks.json`: the degrees, synapse totals and strongest partners of selected neurons,
  recomputed from the source table by body id;
- `README.md` and `SHA256SUMS`: the provenance and construction of the directory, and the checksum
  of every file in it.

`NeuronConnectome` reads only the first two; the loader tests read the metadata and spot checks
(see the end of this section).

Rows are **postsynaptic** neurons. The matrix is therefore `W[post, pre] = sign(pre) * count`, and
the summed signed synaptic input arriving at every neuron is the product `W s` of the matrix and
the spike vector `s`. `NeuronConnectome.getWeights()` returns this `W` as an `[N, N]`
`PackedCollection` whose shape carries a `CompressedRowTraversalOrdering` and whose memory holds
only the `E` stored entries. `matmul` recognises such a matrix — when its ordering enumerates its
rows — and compiles it to a sparse product that visits only the stored synapses, so

```java
matmul(cp(connectome.getWeights()), spikes)
```

is the summed signed synapse count arriving at every neuron, computed without ever forming a dense
`[N, N]` matrix. At connectome scale that matters: a dense `166k x 166k` FP32 MaleCNS matrix would
be about 110 GB, while the stored edges are a few hundred megabytes. The compressed-sparse-row
layout, and how an ordered collection is read as a kernel input, are described in
[Shape and Traversal](shape-and-traversal.md#indexset-and-traversalordering) and
[Dynamic Indexing](dynamic-indexing.md#sparse-matrices-compressed-sparse-row-ordering); this page
relies on them rather than re-deriving them.

`NeuronConnectome` knows the file layout and nothing about dynamics. It owns the loaded tensors and
the index collections of the ordering, exposes the body id and annotations of every neuron
(`getBodyId`, `getType`, `getSide`, `indexOf`, `indicesOfType`), builds an input-set `mask(...)` over a chosen
handful of neurons, and releases its native state in `destroy()`. `NeuronConnectomeTest` and
`NeuronConnectomeChecks` validate a loaded connectome against the totals and spot checks the script
records beside it.

---

## 3. The neuron model

`ExponentialSynapseNeurons` (`domain/biology`) is a population of leaky integrate-and-fire neurons
whose synaptic potential decays exponentially. Each neuron has a membrane potential `v` and a
synaptic potential `g`:

```
dv/dt = (v0 - v + g) / tauMembrane
dg/dt = -g / tauSynapse
```

The step advances this coupled linear system by its **exact propagator** over one step, not by a
forward-Euler approximation:

```
v <- v0 + (v - v0) * dm + g * k
g <- g * ds
```

with `dm = exp(-dt / tauMembrane)`, `ds = exp(-dt / tauSynapse)` and
`k = tauSynapse / (tauSynapse - tauMembrane) * (ds - dm)`, all computed once in the constructor.

As a `Block` its input is `2 * count` values: the first `count` are membrane kicks (an external
drive, such as a Poisson input) added to `v`, and the second `count` are the weighted spikes
arriving from the network, added to `g`. One step is a list of element-local assignments over
device-resident state, in the order of a Brian2 time step — state update, threshold, input, reset:

1. the refractory countdown decreases by one, stopping at zero; a neuron whose countdown is zero is
   **active**;
2. an active neuron advances by the exact propagator above;
3. an active neuron whose `v` exceeds the threshold spikes;
4. the input of an active neuron is added (the kick to `v`, the synaptic input to `g`); a
   refractory neuron's input is **discarded**, as Brian2 discards input to a variable it holds
   during refractoriness;
5. a neuron that spiked is reset: `v` to the reset potential, `g` to zero, and its countdown to the
   refractory period in steps;
6. each spike is added to the neuron's spike count.

The refractory period in steps is computed the way Brian2 converts a duration to steps,
`(int) ((period + 1e-3 * dt) / dt)`, and a neuron spiking at step `s` is held through steps `s + 1`
to `s + period - 1`. `exemptFromRefractoryPeriod(mask)` removes the period for a chosen set of
neurons: a spike never holds them, which is how Brian2 models a neuron under a Poisson drive. The
population owns its state but not the loop; `setup()` returns every neuron to rest with no spikes
counted. The step is forward-only: a spike is a comparison, so there is no gradient through it (the
same limit the population page states).

With the model's default constants `ConnectomeNetwork` fills these parameters with the values of
Shiu et al. (2024), in milliseconds and millivolts:

| Constant | Value | Meaning |
|---|---|---|
| `DT` | 0.1 ms | step duration |
| `REST_POTENTIAL` / `RESET_POTENTIAL` | -52 mV | resting and reset potential |
| `THRESHOLD_POTENTIAL` | -45 mV | spike threshold |
| `MEMBRANE_TIME` | 20 ms | membrane time constant |
| `SYNAPTIC_TIME` | 5 ms | synaptic time constant |
| `REFRACTORY_PERIOD` | 2.2 ms | refractory period |
| `DELAY` | 1.8 ms | synaptic delay |
| `SYNAPSE_WEIGHT` | 0.275 mV | the synaptic potential of one synapse |
| `POISSON_SYNAPSES` | 250 | one Poisson spike kicks the membrane by this times the synapse weight (68.75 mV at `SYNAPSE_WEIGHT`) |

---

## 4. One connectome step

One network step is the PDSL layer `connectome_step` in `/pdsl/connectome.pdsl`:

```
// One time step of a spiking connectome; see ConnectomeNetwork.
layer connectome_step(weights: weight, synapse_weight: scalar,
                      ring: weight, replica_shape: shape, read_row: producer([1]), write_row: producer([1]),
                      poisson: block, neurons: block) {
    poisson
    reshape(replica_shape)
    concat_blocks(
        { identity() },
        { cache_read(ring, read_row); reshape(replica_shape); dense(weights); scale(synapse_weight) })
    neurons
    cache_write(ring, write_row)
}
```

Reading it top to bottom, over one step:

- the external drive flows in (`[replicas * N]`, one membrane kick per neuron per replica, zero
  unless something stimulates it), and `poisson` — the `PoissonInput` `Block` named in the layer —
  adds its kicks to it;
- the delayed spikes are read from the delay line `ring` at `read_row`, the row written `DELAY`
  steps earlier, put through `dense(weights)` — the sparse product with the connectome's signed
  synapse counts of section 2 — and scaled by `synapse_weight`, giving the synaptic input;
- `concat_blocks` places the drive (`identity()`) and that synaptic input side by side into the
  `2 * N` input the neuron block expects: membrane kicks, then synaptic input;
- `neurons` — the `ExponentialSynapseNeurons` `Block` — takes both and emits this step's spikes;
- `cache_write` writes those spikes into the delay line at `write_row`, so they will be read
  `DELAY` steps later.

The delay line has `DELAY/DT + 1` rows of `replicas * N` spikes. On step `k` it is written at row
`k mod rows` and read at row `(k + 1) mod rows` — the row written `DELAY` steps earlier — so the
read never sees the row the same step writes. The read-one-behind, write-this-step discipline is
the ring-buffer delay the population page explains in full; here it is expressed with PDSL's
`cache_read`/`cache_write` over a shared ring rather than a population's own buffer.

`PoissonInput` (`domain/biology`) is the counterpart of a Brian2 `PoissonInput` with one source per
target: on every step each selected neuron independently receives a fixed kick with probability
`rate * dt`. Its draws come from a `CounterRandom` indexed by the simulation's step counter, so the
drive is reproducible on every backend and `setSeed(...)` moves to an independent stream for the
next trial. See the population page's
[reproducible random drive](neural-population-simulation.md) section for `CounterRandom` and the
`2^24` seed/counter bound.

---

## 5. Running it

`ConnectomeNetwork` is a `Temporal`: its `tick()` returns one `connectome_step` as a
`Supplier<Runnable>`, and `getRunner()` returns the `SimulationRunner` that steps it over its step
counter. The runner owns the loop (see [Simulation Loop Examples](simulation-loop-examples.md)).
The network is also a `Setup`: `setup()` returns every neuron to rest, empties the delay line, and
writes each replica's Poisson targets, so each run after a reset is a fresh trial.

The worked example loads the committed synthetic connectome and drives it with the membrane-kick
schedule of its committed Brian2 reference. The drive is a `[steps, N]` collection stored sparsely
through a `CompressedRowTraversalOrdering` — one compressed row of kicks per step — so a schedule
that touches a handful of neurons each step occupies only those entries in memory rather than a
dense `steps * N` grid of mostly zeros. Its storage is sparse, but its per-step read is not the
stored-entry enumeration the connectome's sparse product performs: `scheduled` reads the current
step's row at every one of the `replicas * N` logical positions, each resolved through the
ordering's bounded per-row search (an absent kick reads as zero), so each step still expands the
row to one drive value per neuron per replica. The ordering saves storage, not per-step reads:

```java
connectome = NeuronConnectome.load(synthetic());
n = connectome.getNeuronCount();

StateDictionary reference = new StateDictionary(
        resourceDirectory(BRIAN2_RESOURCE + "manifest.json").getPath());
int[] events = reference.require("spikes").toIntArray();

schedule = CompressedRowTraversalOrdering.of(n, reference.require("kick_offsets"),
        reference.require("kick_neurons"));
drive = new PackedCollection(shape(steps, n), 0, reference.require("kick_values"), 0, schedule);

network = new ConnectomeNetwork(connectome, drive, synapseWeight, replicas, 0.0, 0, driven);
```

The driven neurons are passed as the network's stimulated set with a Poisson rate of `0.0`: the
kicks themselves come from the deterministic `drive` schedule, but passing the set exempts those
neurons from the refractory period, as Brian2 does for Poisson-driven neurons (section 3). The
`synapseWeight` is read from the reference manifest, not left at the model default: the synthetic
reference uses an amplified **4.0 mV** per synapse (its `synapse_mv`), chosen so a 40-neuron toy
connectome propagates activity, rather than the model's own `SYNAPSE_WEIGHT` of 0.275 mV (which the
sugar-circuit reference does use). The run and the recording follow the same discipline as the
population page — record on the device, read the buffer once after the run, never read state back
mid-loop:

```java
runner = network.getRunner();
raster = runner.record(cp(network.getNeurons().getSpikes()), 1, steps);
runner.run(steps);
```

`meanRates(trials, steps[, seed])` runs a number of trials — as many at once as there are replicas,
each pass reseeding the Poisson input — resets between passes, and returns each neuron's mean firing
rate in hertz. Under the deterministic schedule above, with no Poisson draws, it returns each
neuron's reference spike count scaled to hertz:

```java
double[] rates = network.meanRates(1, steps).evaluate().toArray();
// rates[i] == counts[i] * 1000 / (steps * DT)
```

**Replicas.** The network may hold several independent replicas of the connectome stepped together:
every neuron, every Poisson draw and every row of the delay line is repeated per replica, while the
connectome itself is shared, so the synaptic input of all replicas is **one** sparse product over a
batch. Replicas are the trials of an experiment run at once; with one replica the network is a
single brain. The worked example runs three replicas under the same schedule and checks that each
emits the reference's spikes, so the batched product keeps them apart.

**Lifecycle.** Destroy the network first — which releases the runner, the compiled step, the
recordings, the neurons and the Poisson input — then the connectome and the collections the drive
read. The connectome belongs to the caller, so the network never destroys it.

---

## 6. Cross-validation against Brian2

`brian2_reference.py` runs the model's own neuron and synapse equations in Brian2, the simulator
Shiu et al. wrote them for, and writes a reference the Java test must reproduce. Its `synthetic`
mode runs a converted connectome under a fixed, seeded schedule of membrane kicks and writes that
schedule and every spike that follows as dense `(step, neuron)` events (the kicks in compressed
rows by step, which is how the Java test builds its drive). Because the schedule makes the run
deterministic, the platform's spikes must match Brian2's exactly.

`ConnectomeNetworkTest` is the correctness test. It asserts that, under the reference's schedule and
synapse weight, the network emits the spikes Brian2 emits: each neuron's count exactly, each spike
within one step of the reference's, and at most one spike in a thousand displaced at all. The
one-step allowance is for FP32 only — Brian2 integrates in FP64, and an FP32 backend can put a
membrane that the reference leaves a hair above threshold just below it, so the neuron crosses one
step later. A NumPy FP64 run of the same step reproduces every spike; an FP32 run shifts one. A run
with fewer than a thousand spikes therefore must match **exactly**. The synthetic reference, at 486
spikes, is such a run, so `NeuronLevelConnectomeSimulationExamplesTest` asserts a spike-for-spike
match with no timing allowance, which holds on any backend.

The same test also runs the FlyWire 630 "sugar circuit" — the 451 neurons that fire in the Brian2
sugar experiment, with every edge joining them, at the model's own 0.275 mV synapse weight — and
requires the network to reproduce Brian2's 7,917 spikes over half a second, MN9 firing near its
whole-brain rate. That run has more than a thousand spikes, so it is held to the one-step FP32
allowance above (an FP64 NumPy run reproduces every spike; an FP32 run shifts exactly one), **not**
the exact match the synthetic fixture gets. The two committed fixtures therefore carry different
per-fixture contracts: the synthetic connectome exact, the sugar circuit exact up to a single
one-step FP32 shift. Both fixtures ship committed, so these run everywhere. Neither is a
reproduction of the full published whole-brain experiment — that is a larger, rate-based
validation on the curated datasets (section 7), still open tracker work.

---

## 7. Known limits

- **Forward-only dynamics.** The spike is a comparison and the Poisson draw is a comparison, both
  with zero derivative almost everywhere, and the repository defines no surrogate gradient. There is
  no backpropagation through spiking dynamics; the network is for simulation, not training. (Same
  limit as the population page.)
- **The neuron and Poisson `Block`s are Java, not PDSL.** Their comparisons and persistent state are
  outside what PDSL can express today, so they are Java `Block`s placed in the PDSL pipe by name.
  Replacing them with PDSL primitives is open work, not done here.
- **FP32 timing on larger runs.** On a run of more than a thousand spikes an FP32 backend may shift
  a rare spike by one step relative to a FP64 reference (section 6). The committed synthetic
  reference is below that and matches exactly.
- **`2^24` bounds on FP32 backends.** These bound the `CounterRandom` seed and counter and the
  `SimulationRunner` step counter, exactly as the population page describes. The delay line, the
  drive schedule and a `Recording` address an element by row, forming `row * rowSize + i` on
  integers inside the kernel, so their *element* count is not bounded this way: a network of 30
  replicas of FlyWire 630 (a 72.6M-element delay line) runs every trial of an experiment in one
  pass. A `Recording`'s sample count (its `capacity`) is still a floating-point counter and shares
  the step counter's 2^24 bound, but its element count (`capacity * width`) is not.
- **The whole-brain reproduction is larger work.** The MaleCNS whole-brain ingest and the full Shiu
  et al. reproduction, with its performance benchmark, are open tracker tasks for a later release
  and build on this pipeline. The dataset tests
  (`NeuronConnectomeDatasetTest`, `ConnectomeNetworkDatasetTest`) require their curated datasets on
  every host — a missing dataset fails the test, it is never gated into a skip — and are excluded
  from the pipeline profile as long-running.

---

## 8. Related files

| Path | What it holds |
|---|---|
| `studio/bioscience/src/main/java/org/almostrealism/bioscience/connectome/NeuronConnectome.java` | The CSR connectome loader: tensors, neuron table, `getWeights`, `mask` |
| `studio/bioscience/src/main/java/org/almostrealism/bioscience/connectome/ConnectomeNetwork.java` | The spiking network, the model constants, replicas, `meanRates`, `getRunner` |
| `studio/bioscience/src/main/resources/pdsl/connectome.pdsl` | The `connectome_step` layer |
| `domain/biology/src/main/java/org/almostrealism/biology/neuron/ExponentialSynapseNeurons.java` | The exact-exponential LIF neuron `Block` |
| `domain/biology/src/main/java/org/almostrealism/biology/neuron/PoissonInput.java` | The reproducible Poisson-input `Block` |
| `compute/algebra/src/main/java/org/almostrealism/collect/CompressedRowTraversalOrdering.java` | The compressed-sparse-row ordering the matrix and the drive carry |
| `studio/bioscience/scripts/connectome_csr.py` | Reduces each dataset to the signed CSR safetensors form |
| `studio/bioscience/scripts/brian2_reference.py` | The Brian2 cross-validation oracle |
| `studio/bioscience/src/test/java/org/almostrealism/bioscience/connectome/test/NeuronLevelConnectomeSimulationExamplesTest.java` | The worked example of this page and its assertions |
| `studio/bioscience/src/test/java/org/almostrealism/bioscience/connectome/test/ConnectomeNetworkTest.java` | The Brian2 spike-for-spike correctness test |
| `studio/bioscience/src/test/java/org/almostrealism/bioscience/connectome/test/NeuronConnectomeTest.java` | The connectome loader against the committed fixtures |
| `studio/bioscience/src/test/resources/connectome/synthetic` | The committed synthetic connectome (40 neurons, 111 edges) |
| `studio/bioscience/src/test/resources/connectome/synthetic-brian2` | Its committed Brian2 reference (schedule and spikes) |

## See also

- [Simulating a Spiking Neural Population](neural-population-simulation.md): the aggregate LIF/rate
  population path, and the shared machinery (device-resident state, ring-buffer delay, exact
  exponential, `CounterRandom`, `SimulationRunner`) this page builds on
- [Simulation Loop Examples](simulation-loop-examples.md): the loop-owner rules, the step contract,
  and the measured dispatch overhead
- [Shape and Traversal](shape-and-traversal.md#indexset-and-traversalordering): ordered collections
  as kernel inputs
- [Dynamic Indexing](dynamic-indexing.md#sparse-matrices-compressed-sparse-row-ordering): the
  compressed-sparse-row layout and integer-exact indices above `2^24`
- [PDSL Language Reference](pdsl-language.md): the PDSL the `connectome_step` layer is written in
- [`studio/bioscience` README](../../studio/bioscience/README.md) and
  [`domain/biology` README](../../domain/biology/README.md)
