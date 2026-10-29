# Simulating a Spiking Neural Population

This page follows one example from start to finish: a small network of leaky integrate-and-fire
(LIF) neurons, with a reproducible Poisson drive, a synaptic delay and dense connectivity, stepped
by the simulation loop owner, with its spikes recorded on the device and read back once the run
is over. Each piece has thorough Javadoc. This page shows how the pieces fit together.

The worked example is `NeuralPopulationSimulationExamplesTest` in `engine/utils`
(`org.almostrealism.graph.test`). Code blocks on this page are taken from that test unless they
are marked as fragments. The test also asserts every behavioral claim the page makes about the
example: the driven rate, the silence of undriven neurons, the exact delay, reproducibility and
the destruction order.

---

## 1. What the platform simulates and where each part lives

A simulation is put together from five modules. Each has its own job:

| Part | Module | Classes |
|---|---|---|
| Population dynamics (one time step as a computation graph) | `domain/graph` (`org.almostrealism.graph`) | `LeakyIntegrateAndFirePopulation`, `RatePopulation` |
| The loop that steps a system forward | `compute/time` (`org.almostrealism.time`) | `SimulationRunner`, `Temporal` |
| Random numbers and sparse structure | `compute/algebra` (`org.almostrealism.collect`, `...collect.computations`) | `RandomProducer`, `CounterRandom`, `Random`, the `TraversalOrdering` implementations |
| Biological models in their own terms | `domain/biology` | (neurons, synapses, cell types, connectomes) |
| Models that need the ML engine or third-party data | `studio/bioscience` | `CellTypeConnectome` and the dataset conversion scripts |

The placement rule comes from the module READMEs. Code that needs `ar-ml`, or that reads a
third-party data format through the engine, goes in `studio/bioscience`. Biological models that
need neither go in `domain/biology`, where lower layers can use them. General sparse or tensor
operations go in `compute/` or `base/`, where every domain can use them. No model owns the loop
that drives it. That loop belongs to `SimulationRunner`.

---

## 2. State is device-resident collections

A `LeakyIntegrateAndFirePopulation` of `count` neurons holds these `PackedCollection`s. They are
allocated once in the constructor and live on the device for the whole run:

| State | Shape | Meaning |
|---|---|---|
| `getMembrane()` | `[count]` | Membrane potential `v` |
| `getConductance()` | `[count]` | Synaptic conductance `g` |
| `getRefractory()` | `[count]` | Refractory countdown in whole steps |
| `getSpikes()` | `[count]` | Spike vector emitted by the latest step (1 or 0) |
| `getRing()` | `[delaySteps + 1, count]` | The most recent spike vectors, one per row |
| `getWriteIndex()` | `[1]` | The ring row the next step writes |

One time step is a list of assignments over these collections (`step(Producer)` returns an
`OperationList`). Each assignment reads the state that the assignment before it left behind. The
list is compiled once and then dispatched again every step. Nothing in the run reads state back to
the host: a step boundary is a global synchronization on the device, not a host round trip. The
state reaches the device through the sanctioned paths described in
[Host→Device Write Policy](host-device-write-policy.md). The examples write it with `fill`, and
the population's own `reset()` writes it with a compiled operation.

---

## 3. One step of a LIF population

`LeakyIntegrateAndFirePopulation.step(synapticInput)` adds these assignments to the list, in
order. `I` is the step's synaptic input. `d_syn = exp(-dt / tauSyn)` and `d_m = exp(-dt / tauM)`
are computed once in the constructor.

1. **Conductance.** `g <- d_syn * g + I`.
2. **Membrane.** The neuron is active when its refractory countdown is zero, so
   `active = (refractory > 0) ? 0 : 1`. The steady state is `v_inf = vRest + R * g`. The update is
   `v <- active * (v_inf + (v - v_inf) * d_m) + (1 - active) * vReset`. An active neuron relaxes
   toward its steady state by the exact exponential. A refractory neuron is held at reset.
3. **Spike.** `spike <- emittedSpikes()`. This is `v >= vThreshold`, combined with the Poisson
   drive by `max` when a drive is configured (section 6).
4. **Reset.** `v <- spike * vReset + (1 - spike) * v`.
5. **Refractory countdown.** It is reloaded to `refractorySteps` where a spike occurred, and
   elsewhere it counts down and stops at zero:
   `refractory <- spike * refractorySteps + (1 - spike) * max(refractory - 1, 0)`.
6. **Ring write.** The spike vector replaces row `writeIndex` of the ring. This is a
   `CollectionSlotUpdateComputation` over the whole ring, named `lif-ring-write`.
7. **Write index.** `writeIndex <- (writeIndex + 1) mod (delaySteps + 1)`.
8. **Poisson counter.** When a drive is configured, `CounterRandom.advance()` runs.

**Why the exact exponential.** For the linear LIF equations, with the input held constant across
a step, `v_inf + (v - v_inf) * exp(-dt / tauM)` is the exact solution sampled at step boundaries.
This is different from a forward-Euler approximation. Between spikes the membrane therefore
follows the continuous trajectory, and a neuron under a constant current `I` fires at the analytic
LIF rate:

```
r = 1 / (t_ref + tauM * ln((R*I - V_reset) / (R*I - V_th)))
```

`LeakyIntegrateAndFirePopulationTest.analyticFiringRate` checks this rate, and
`membraneFollowsExactExponential` checks the membrane trajectory itself.
`refractoryPeriodFloorsInterSpikeInterval` checks that the refractory countdown makes the
inter-spike interval at least `refractorySteps + 1` steps.

---

## 4. Synaptic delay through the ring buffer

`delayedSpikes()` returns the spike vector that was emitted `delaySteps` steps ago. It reads
ring row

```
(writeIndex - delaySteps) mod rows,   rows = delaySteps + 1
```

through `c(shape(count), ring, index)`, a gather with a computed index. The ring has
`delaySteps + 1` rows so that the row being read is never the row the same step overwrites. One
step reads row `w - delaySteps` (mod `rows`) and writes row `w`. With `rows = delaySteps + 1`,
those two rows are always different. So within one population's step, the order of the read and
the write cannot change the delay. Relative to the population's own step, the delay is exactly
`delaySteps`. `LeakyIntegrateAndFirePopulationTest.ringBufferDelaysSpikeExactly` and
`synapticDelayDeliversWeightedInputAfterDelay` pin this.

Across two populations the guarantee needs care: see the tick order in section 7.

---

## 5. Connectivity

The step's synaptic input is a `Producer<PackedCollection>` argument to `step`. The population does
not care how that input is formed. In the worked example, a dense weight matrix `W`
(`[targets, sources]`, row = target) multiplies the input population's delayed spikes:

```java
weights = new PackedCollection(shape(OUTPUTS, INPUTS)).fill(WEIGHT);
silence = new PackedCollection(shape(INPUTS));
Producer<PackedCollection> synapticInput = matmul(cp(weights), input.delayedSpikes());
```

At connectome scale a dense matrix is not feasible. For the full MaleCNS connectome, about 166,000
neurons, a dense `166k x 166k` matrix in FP32 is about 110 GB. The connectome-scale form is a
sparse matrix–vector product over the delayed spikes: the weight matrix is a `PackedCollection`
whose shape carries a `CompressedRowTraversalOrdering` (compressed sparse row) and whose memory
holds only the stored synapses. `matmul` recognises such a matrix — when its ordering enumerates
its rows — and compiles it to a `SegmentedWeightedSumComputation` that visits only the stored
entries. It therefore slots in as the same `Producer` argument to `step`, with no change to the
population step. The compressed-sparse-row layout, and the ordered collections a sparse product
reads, are described in
[Dynamic Indexing](dynamic-indexing.md#sparse-matrices-compressed-sparse-row-ordering) and, for
indices above 2^24, the split layout in the same document; see also
[Shape and Traversal](shape-and-traversal.md#indexset-and-traversalordering).

---

## 6. Reproducible random drive

### `RandomProducer`: one concept, two implementations

Random generation is one concept, `RandomProducer` (in `org.almostrealism.collect.computations`),
with two implementations. The application chooses one once, at startup, through the `AR_RANDOM`
setting. The setting is read into `RandomProducer.IMPLEMENTATION`. `rand(...)`/`randn(...)` in
`CollectionCreationFeatures` and `RandomProducer.create(...)` both return the selected
implementation.

| `AR_RANDOM` | Class | How values are made |
|---|---|---|
| `java` (default) | `Random` | Drawn on the host with `java.util.Random` and delivered to the device as data |
| `counter` | `CounterRandom` | Computed inside the kernel as a hash of seed, counter and element index |

Both implementations keep their values fixed between calls to `refresh()`, the one method of
`RandomProducer` that moves a generator on to new values. What it does differs:

- for `Random`, `refresh()` discards the cached host-drawn values, so the next evaluation draws
  new ones on the host;
- for `CounterRandom`, `refresh()` runs a compiled device-side increment of the counter. It is
  compiled on the first call and reused after that.

### `CounterRandom`

Every output element of `CounterRandom` is a pure function of `(seed, counter, index)`:

```
stream = pcg(pcg(seed) ^ counter)
word   = pcg(stream ^ index)
```

Here `pcg` is the 32-bit PCG hash of Jarzynski and Olano. The seed and the counter are each held
in a single-element `PackedCollection` and passed to the kernel as arguments. Generators that
differ only in seed or counter therefore share one compiled kernel, and moving to a new stream
needs no host generation, no transfer and no recompilation.

- **Uniform output** takes the top 24 bits of the word and scales them by 2^-24
  (`CounterRandom.UNIT_SCALE`), giving `[0, 1)`. Both steps are exact in single and in double
  precision, so uniform output is **bit-identical on every backend**.
- **Normal output** uses Box–Muller on the words for indices `2i` and `2i + 1`. The
  transcendental functions run at the backend's precision, so normal output agrees across
  backends **only to within that precision**.

Besides `refresh()`, `CounterRandom` has two methods that `RandomProducer` does not:

- `advance()` returns the counter increment as an `Assignment`, uncompiled and not yet run;
- `resetCounter()` returns the counter reset to zero as an `Assignment`, also uncompiled.

These two are for a caller that composes the generator's state changes into its own step or
reset, in order with its other assignments. That is exactly what the LIF population does.

**The 2^24 bound.** The seed and the counter are read from floating-point memory and converted
to integers in the kernel. On an FP32 backend a collection holds integers exactly only up to
2^24. `CounterRandom.SEED_LIMIT` (2^24) is the exclusive bound on the seed, and the constructor
rejects anything outside it. The class Javadoc states the same bound for the counter. Past
2^24 = 16,777,216 advances, consecutive counters round to the same value and their streams
repeat. A population advances its counter once per step, so at `dt = 0.1 ms` the bound is about
1,678 simulated seconds, roughly 28 minutes.

### Poisson drive in the LIF population

`setPoissonDrive(rate, mask, seed)` gives the population **its own** uniform `CounterRandom`
of shape `[count]`, seeded with `seed`. This happens **whatever `AR_RANDOM` says**: the
population constructs a `CounterRandom` directly rather than calling `rand`. The step advances
that generator's counter with `advance()` as its last assignment, and `reset()` resets the counter
with `resetCounter()`. On every step, a neuron in the input set fires when its uniform draw is
below `rate * dt`:

```
drive = (uniform < rate * dt) ? 1 : 0      // then multiplied by the mask, if one is given
spike = max(v >= vThreshold, drive)        // emittedSpikes()
```

Two consequences follow from `emittedSpikes()`, and the worked example relies on both:

- The drive is combined with the threshold spike **without consulting the refractory
  countdown**. A driven neuron that receives no other input therefore fires a Bernoulli(`rate *
  dt`) spike on every step, refractory or not. The refractory countdown holds only the membrane.
- `rate * dt` above 1 simply fires on every step. `setPoissonDrive` rejects only a negative rate
  (and a mask of the wrong size).

---

## 7. Driving it with `SimulationRunner`

### The adapter

`SimulationRunner` steps a `Temporal`: a system whose `tick()` returns one step as a
`Supplier<Runnable>`. The LIF population does not implement `Temporal`. It exposes
`step(Producer)` instead, so a lambda adapts it. For a single population, the adapter is one line
(fragment):

```java
// Fragment: one population, synaptic input formed elsewhere
Temporal network = () -> pop.step(input);
```

A network of an input population and an output population needs a tick that puts both steps into
one `OperationList`. This is the worked example in full: two populations, a masked Poisson drive,
dense connectivity, a runner and two device-resident recordings.

```java
// Two populations: dt, tauM, tauSyn, vRest, vReset, vThreshold, R,
// refractory steps, delay steps
input = new LeakyIntegrateAndFirePopulation(INPUTS, DT, 20.0, 5.0,
        0.0, 0.0, 1.0, 1.0, 20, DELAY_STEPS);
output = new LeakyIntegrateAndFirePopulation(OUTPUTS, DT, 20.0, 5.0,
        0.0, 0.0, 1.0, 1.0, 20, DELAY_STEPS);

// The first DRIVEN input neurons fire at RATE; setPoissonDrive comes before step(...)
mask = lessThan(integers(0, INPUTS), c((double) DRIVEN), c(1.0), c(0.0)).evaluate();
input.setPoissonDrive(RATE, mask, seed);
input.reset();

// Dense connectivity: the output's synaptic input is W times the input's delayed spikes
weights = new PackedCollection(shape(OUTPUTS, INPUTS)).fill(WEIGHT);
silence = new PackedCollection(shape(INPUTS));
Producer<PackedCollection> synapticInput = matmul(cp(weights), input.delayedSpikes());

// One tick steps the output before the input, so the delay is exactly DELAY_STEPS
Temporal network = () -> {
    OperationList tick = new OperationList("two-population tick");
    tick.add(output.step(synapticInput));
    tick.add(input.step(cp(silence)));
    return tick;
};

runner = new SimulationRunner(network, DT);
raster = runner.record(cp(input.getSpikes()), 1, STEPS);
received = runner.record(cp(output.getConductance()), 1, STEPS);
```

The run itself is one call. The buffers are read on the host only after `run` returns:

```java
net.runner.run(STEPS);
double[] raster = net.raster.getBuffer().toArray();
```

In the example, `INPUTS = 8`, `DRIVEN = 4`, `OUTPUTS = 2`, `DELAY_STEPS = 5`, `DT = 0.1` (ms),
`RATE = 0.5` spikes/ms (500 Hz, so `rate * dt = 0.05`) and `STEPS = 2000`.

### The wrong pattern

```java
// WRONG: a host loop around the step, reading state back on every step
Runnable step = pop.step(synapticInput).get();
for (int t = 0; t < steps; t++) {          // WRONG! SimulationRunner owns this loop
    step.run();
    if (pop.getSpikes().toDouble(0) > 0.5) // WRONG! a device round trip every step
        fired.add(t);
}
```

This is how `LeakyIntegrateAndFirePopulationTest` steps a single population to check its
dynamics in isolation. Do not copy it into a simulation. The loop belongs to `SimulationRunner`,
which also owns the step counter, the boundary inputs and the recordings, and every `toDouble`
is a JNI round trip that forces the device to finish. Record with `runner.record(...)` instead,
and read the buffer once after the run. See
[Simulation Loop Examples](simulation-loop-examples.md) for the general rules.

### How the runner dispatches the step

`SimulationRunner` builds its step once, in the constructor (`SimulationRunner.stepOperations`).
It calls the adapter's `tick()` a single time, adds the counter advance, flattens the list
(`OperationList.flatten()`) and optimizes it (`Process.optimized(...)`) before compiling. Flattening
inlines every nested list that carries no compute requirements. The `lif-step` list carries none,
so each assignment of `step` becomes a separate top-level operation, followed by the runner's
`Advance Simulation Step`. Optimization keeps compilation of the outer list disabled, so each
top-level operation is still dispatched on its own.

Running `step(...).get()` directly, as the population's Javadoc and its test do, does **not**
compile the step to one kernel either. `OperationList.get()` compiles a list to a single kernel
only when the list is uniform, or when `OperationList.enableNonUniformCompilation` is set (it
defaults to `false`). The `lif-step` list mixes parallelisms: `count` for the five state
assignments, `rows * count` for the ring write, and 1 for the write-index and counter advances.
`get()` therefore logs "OperationList was not compiled (uniform = false)" and returns an
`OperationListRunner` that dispatches each of the eight operations separately.

`NeuralPopulationSimulationExamplesTest.stepDispatchesEachAssignment` asserts this. It also writes
the operation profiles of both paths to `engine/utils/results/lifStepDirect.xml` and
`lifStepRunner.xml`. In the profiles, the direct path is eight operations (five `Assignment (8)`,
the `lif-ring-write`, two `Assignment (1)`). The runner path is the same eight, plus
`Advance Simulation Step`. **The two paths have the same per-assignment dispatch pattern.** The
cost of a dispatch is measured in the "Measured Overhead" section of
[Simulation Loop Examples](simulation-loop-examples.md#measured-overhead).

The LIF step satisfies the runner's step contract (a single operation must not read state that
it writes when other elements' values are involved) because every assignment is element-local.
The one coupled read, the `matmul` over `delayedSpikes()`, reads the ring, and the operation that
performs that read does not write the ring.

### Two populations in one tick: order matters

`delayedSpikes()` guarantees a delay of exactly `delaySteps` **relative to its own population's
step**. In a network, the output's synaptic input reads the **input** population's ring and
write index. If the tick steps the input first, the input has already advanced its write index by
the time the output reads, so the output reads a row one step newer and the effective delay is
`delaySteps - 1`. If the tick steps the output first, the output reads before the input advances,
and the delay is exactly `delaySteps`.

The worked example therefore steps the **output before the input**. In general, a tick should step
every population that reads another population's ring before the population that owns that ring.
`NeuralPopulationSimulationExamplesTest.inputSpikeArrivesAfterExactDelay` asserts the exact delay
for the example's order: the output conductance first becomes non-zero exactly `DELAY_STEPS` steps
after the first input spike. `inputFirstShortensDelayByOneStep` asserts `DELAY_STEPS - 1` for the
reverse order. Both orders compile and run without error, so the ordering rule is not enforced by
the code. A network with recurrent connections, where each population reads the other's ring, has
no order that is right for both directions; there the delay seen by one direction is one step
shorter.

### Ordering constraints of the population API

- **`setPoissonDrive` before `step(...)` is built.** The step captures the generator, and
  `setPoissonDrive` replaces it. The runner calls the adapter's `tick()` once, when it is
  constructed, so in practice `setPoissonDrive` must come **before the runner is constructed**.
  After `setPoissonDrive`, call the population's `reset()`, which rebuilds the compiled reset so
  that it includes the new generator's counter. The example does both.
- **The seed is fixed by `setPoissonDrive`.** A run with a different seed needs another
  `setPoissonDrive` call followed by a new runner, or a new population. `setPoissonDrive` replaces
  the previous `CounterRandom` without destroying it, so calling it twice leaves the first
  generator's seed and counter collections unreleased (`destroy()` releases only the current
  generator). Prefer a new population.
- **A repeat run with the same seed needs no new runner.** Call `runner.reset()`, then each
  population's own `reset()`, which also resets the Poisson counter. That restores the initial
  condition, and the compiled step is reused. `rasterIsReproducibleForASeed` asserts that the
  repeat run records the identical raster.

### Lifecycle order

Destroy the runner first, which releases the compiled step and the recordings, and the
populations after it:

```java
runner.destroy();
input.destroy();
output.destroy();
weights.destroy();
mask.destroy();
silence.destroy();
```

Destroying the runner does not release the populations' state collections: the test asserts
that the spikes, ring, conductance and weights are still allocated after `runner.destroy()`.
It does release more than the compiled code. Destroying a computation destroys its input
producers (`OperationAdapter.destroy`, `ComputableBase.destroy`), and in a driven population
those inputs include the population's own `CounterRandom`, whose `destroy()` releases its seed and
counter collections (the generator is an input of the drive comparison in `emittedSpikes()`).
The state collections reach the step through `cp(...)`, a `CollectionProviderProducer`, which does
not override `destroy`, so they survive. A population whose compiled step has been destroyed is therefore finished:
destroy it too, and build a new population (or call `setPoissonDrive` again, which creates a new
generator) for another run.

### Recording cost

A `Recording` write is a slot update over the whole buffer, so each write costs time proportional
to `capacity * width` (`SimulationRunner.Recording` Javadoc). Recording a `width`-element vector
every step for `n` steps needs `capacity = n`, so the total cost is `O(n^2 * width)`. The example's
raster is 2,000 x 8 = 16,000 elements per write: harmless. Recording 166,000 spikes every step
for 10,000 steps would be 1.66 x 10^9 elements per write, and that also breaks a second limit.
On FP32 backends a recording computes its write position `index * width` exactly only while
`capacity * width` stays within 2^24 (16,777,216). A larger recording writes to the wrong rows
**without an error**.

For long runs, record at an interval (`record(selected, interval, capacity)` with
`capacity = n / interval`) or record a reduced quantity, for example a per-step spike count
(`cp(pop.getSpikes()).sum()`, width 1) or a subset of the neurons, and keep
`capacity * width` below 2^24.

---

## 8. Firing-rate populations

`RatePopulation` (`domain/graph`) models firing rates instead of spikes:
`tau * dr/dt = -r + f(g * s (.) (W r) + input)`, with a saturating rectifier `f` and exponential
Euler integration, which keeps the rates in `[0, rMax]` for any step size. Unlike the LIF
population it **is** a `Temporal`, so it goes straight into `new SimulationRunner(population, dt)`
with no adapter. Its update reads every unit's rate through `W r`, which is a coupled read, so
`tick()` writes a next-state buffer and copies it back in a second operation, as the step contract
requires.

| | `LeakyIntegrateAndFirePopulation` | `RatePopulation` |
|---|---|---|
| State | Membrane, conductance, refractory, spikes, ring | Rates (plus next-state buffer) |
| Loop integration | Adapter lambda around `step(Producer)` | Is a `Temporal` |
| Connectivity | Caller-formed `Producer` (any form) | Borrowed dense `[n, n]` matrix, optional row scale |
| Synaptic delay | `delaySteps` through the ring | None |
| Noise | Poisson drive (`CounterRandom`) | External `getInput()` collection |

Use the rate model for population-level dynamics at the level of cell types, where a dense
matrix fits. `CellTypeRateModelTest` in `studio/bioscience` runs it on the 11,751-type MaleCNS
matrix. Use the spiking population when spike timing, refractoriness or delays matter.

---

## 9. Known limits

- **Forward-only dynamics.** The spike is a comparison, `greaterThanOrEqual(membrane,
  threshold)` in `LeakyIntegrateAndFirePopulation.emittedSpikes()`, and the Poisson drive is a
  `lessThan`. Comparisons have zero derivative almost everywhere. The repository defines no
  surrogate gradient (a search of the Java sources for "surrogate" finds only an unrelated
  tokenizer), so there is no backpropagation through time for spiking dynamics. The population is
  for simulation, not training.
- **One global synchronization per operation.** The runner dispatches every assignment of the
  step separately (section 7), and each boundary between them is a global synchronization. The
  measured per-step and per-dispatch costs are in
  [Simulation Loop Examples](simulation-loop-examples.md#measured-overhead).
- **2^24 bounds on FP32 backends.** These are the bound on the `CounterRandom` seed
  (`CounterRandom.SEED_LIMIT`) and counter (the `CounterRandom` class Javadoc), the bound on the
  `SimulationRunner` step counter (the `SimulationRunner` class Javadoc, "Step counter
  precision"), and the bound on a `Recording`'s `capacity * width`. At `dt = 0.1 ms`, 2^24 steps
  are about 28 simulated minutes.
- **No Brian2 parity yet.** Spike-for-spike parity against a Brian2 reference raster has not been
  established. The `LeakyIntegrateAndFirePopulationTest` class Javadoc says so.
- **The population is not a `Temporal`, `Setup` or `Lifecycle`.** `SimulationRunner.reset()` resets
  only a system that is a `Lifecycle`, so it does not reset the population. A second simulation
  calls the population's own `reset()` as well (section 7).
- **Tick order sets the cross-population delay.** Nothing checks it. See section 7.

---

## 10. Related files

| Path | What it holds |
|---|---|
| `domain/graph/src/main/java/org/almostrealism/graph/LeakyIntegrateAndFirePopulation.java` | The LIF population: state, `step`, `delayedSpikes`, `setPoissonDrive`, `reset` |
| `domain/graph/src/main/java/org/almostrealism/graph/RatePopulation.java` | The firing-rate population |
| `compute/time/src/main/java/org/almostrealism/time/SimulationRunner.java` | The simulation loop owner, recordings, step contract |
| `compute/algebra/src/main/java/org/almostrealism/collect/computations/RandomProducer.java` | The random-generation concept and `AR_RANDOM` |
| `compute/algebra/src/main/java/org/almostrealism/collect/computations/CounterRandom.java` | The in-kernel counter-based generator |
| `compute/algebra/src/main/java/org/almostrealism/collect/computations/Random.java` | The host-drawn generator |
| `base/hardware/src/main/java/org/almostrealism/hardware/OperationList.java` | `get()`, `flatten()`, `enableNonUniformCompilation` |
| `engine/utils/src/test/java/org/almostrealism/graph/test/NeuralPopulationSimulationExamplesTest.java` | The worked example of this page and its assertions |
| `engine/utils/src/test/java/org/almostrealism/graph/test/LeakyIntegrateAndFirePopulationTest.java` | The LIF dynamics in isolation |
| `engine/utils/src/test/java/org/almostrealism/collect/computations/test/CounterRandomTests.java` | `CounterRandom` and `AR_RANDOM` |
| `engine/utils/src/test/java/org/almostrealism/time/test/SimulationRunnerTest.java` | `SimulationRunner` |

## See also

- [Simulation Loop Examples](simulation-loop-examples.md): the loop-owner rules, the step
  contract, and the measured overhead
- [Shape and Traversal](shape-and-traversal.md#indexset-and-traversalordering): ordered collections
  as kernel inputs
- [Dynamic Indexing](dynamic-indexing.md): gathers and integer-exact indices above 2^24
- [Host→Device Write Policy](host-device-write-policy.md): how state reaches the device
- [CollectionProducer Operations](collection-producer-operations.md): `rand`/`randn` and the rest
  of the fluent API
- [`compute/time` README](../../compute/time/README.md) and
  [`domain/biology` README](../../domain/biology/README.md)
