# Simulation Loop Architecture - Detailed Examples

This document contains detailed examples for the simulation loop architecture.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules.

**`SimulationRunner` owns the simulation loop.** It is to recurrent dynamical systems (neural
populations, physical systems, ODE integrators) what `ModelOptimizer` is to training and
`DiffusionSampler` is to sampling. No other class writes `for (long step ...)` around a compiled
step.

`SimulationRunner` lives in `compute/time` (`org.almostrealism.time`) and extends
`TemporalRunner`, so `domain/physics`, `domain/graph` and everything above them can use it.

## Correct Pattern

A system implements `Temporal`: its `tick()` describes one time step as a computation graph. A thin
caller configures a `SimulationRunner` and calls `run` once.

```java
// CORRECT: the system describes one step; it owns no loop
public class OscillatorBank implements Temporal, CollectionFeatures {
    private final PackedCollection state;       // shape (2, n): positions, velocities
    private final PackedCollection next;        // next-state buffer, shape (2, n)
    private final PackedCollection propagator;  // exact 2x2 discrete propagator

    @Override
    public Supplier<Runnable> tick() {
        OperationList tick = new OperationList("Oscillator Bank");
        tick.add(a(cp(next), matmul(cp(propagator), cp(state))));  // reads every element's state
        tick.add(a(cp(state), cp(next)));                           // copy back: a later operation
        return tick;
    }
}

// CORRECT: a thin caller configures the runner and delegates - NO LOOP HERE
SimulationRunner runner = new SimulationRunner(bank, dt);
SimulationRunner.Recording positions = runner.record(cp(bank.getState()), 100, 100);
runner.run(10000);
PackedCollection samples = positions.getBuffer();   // read on the host after run returns
runner.destroy();
```

A system whose graph needs time (a driven input, a counter-based random stream) consumes the
runner's device-resident step counter. Because the system's tick is built when the runner is
constructed, the caller allocates the counter and hands it to both:

```java
PackedCollection step = new PackedCollection(1);
Temporal driven = () -> a(cp(drive), sin(cp(step).multiply(dt * frequency)));
SimulationRunner runner = new SimulationRunner(driven, dt, step);
```

## Wrong Patterns (DO NOT USE)

```java
// WRONG: a step loop anywhere other than SimulationRunner
Runnable step = system.tick().get();
for (long i = 0; i < steps; i++) {        // WRONG! SimulationRunner owns this loop
    step.run();
    if (i % 100 == 0) record(state);      // WRONG! use runner.record(...)
}

// WRONG: buffer(n) / Loop for a globally coupled step
system.buffer(10000).get().run();         // WRONG! fuses 10,000 ticks into ONE kernel:
                                          // threads iterate with no barrier and race

// WRONG: reading state on the host per step
for (...) {
    step.run();
    double v = state.toDouble(0);         // WRONG! a device round trip every step
}
```

`buffer(n)` and `TemporalRunner` with `iter > 1` build a hardware `Loop` when the tick is a
`Computation`. The `Loop` repeats the tick inside one kernel with no barrier between iterations,
so thread *i* reads thread *j*'s state from an unknown iteration. That is correct only for
element-local steps.

## The Step Contract

The runner dispatches the step as a sequence of operations: every top-level operation of the
system's flattened tick is compiled and dispatched on its own, followed by the counter advance.
Each boundary between operations is a global synchronization.

- A single operation is one kernel. It must not read state that it also writes when other
  elements' values of that state are involved.
- A coupled update writes a next-state collection in one operation and copies it back over the
  state in a later operation of the same step.
- An element-local update (each element reads only its own previous value) may update the state
  in place.
- An `OperationList` that carries its own compute requirements is kept intact by flattening and may
  compile to one kernel, so it must itself be element-local.

## Order of One Step

1. Boundary listeners that are due are called with the index of the step about to run.
2. Boundary inputs that are due are dispatched.
3. The compiled step runs: the system's tick, then the counter advance.
4. Recordings that are due write one sample.

A listener or input with interval `k` is due before steps `0, k, 2k, ...`. A recording with
interval `k` samples the state after steps `k, 2k, ...`, so sample `j` holds the state at time
`(j + 1) * k * dt`. The system's setup (if it is a `Setup`) and the counter reset run once, before
the first step of the first `run`, and again after `reset()`.

## Boundary Inputs

`addInput(interval, operation)` takes a compiled operation, typically an assignment that folds an
external collection into the state. `addBoundaryListener(interval, listener)` is the one place in
the loop where host code runs: it is where a closed loop attaches, for example an external body
simulator writing into the collection that the input folds in.

```java
PackedCollection external = new PackedCollection(n);   // written by the external simulator
runner.addBoundaryListener(10, step -> bodySimulator.writeSensoryInput(external, step));
runner.addInput(10, a(cp(drive), cp(external)));
runner.run(steps);
```

A listener must not read the system's state back from the device on every call.

## Recording

`record(selected, interval, capacity)` returns a `Recording` that owns a device buffer of shape
`(capacity, width)` and a device-resident sample index. Each write is one compiled operation list:
a `CollectionSlotUpdateComputation` replacing row `index` of the buffer, assigned back over the
buffer, then an index advance. A write costs `O(capacity * width)`. A run that would exceed the
capacity fails before any step runs.

## Inherited Entry Points

`SimulationRunner` is also a `Supplier<Runnable>`, a `Temporal` and a `Setup`. The inherited
`get()`, `getContinue()` and `tick()` all run one step through `run(1)`, so the counter, schedules
and recordings stay consistent however the runner is driven. The system's setup and the counter
reset are the supertype's setup, compiled and released by `TemporalRunner`; the runner dispatches
them through `TemporalRunner.getCompiledSetup()` once per simulation, and its `setup()` supplies a
runnable that does the same, so composing the runner as a `Setup` cannot re-run the system's setup.

## Step Counter Precision

The counter is a floating-point collection. On FP32 backends it counts exactly only to 2^24
steps (about 28 simulated minutes at 0.1 ms). Time derived as `step * dt` in FP32 loses
resolution earlier, because the product has a 24-bit mantissa.

## Measured Overhead

`SimulationRunnerDispatchTest` (in `engine/utils`) times 10,000 steps after a 500-step warm-up and
logs `perStepMicros=` for each case. "Recorder" adds one recording of the whole state every 100
steps.

Measured on a Mac Studio (Apple silicon, macOS, aarch64), with the working tree on top of commit
`db17e5a3f`. Native ran in FP64 and Metal in FP32. Each step dispatches at least two operations:
the system's tick and the counter advance.

| Step | Backend | perStepMicros, no recorder | perStepMicros, one recorder |
|---|---|---|---|
| One element, element-wise | native | 78.3 | 84.9 |
| One element, element-wise | mtl | 313.4 | 321.3 |
| 100,000 elements, element-wise | native | 237.8 | 271.1 |
| 100,000 elements, element-wise | mtl | 345.8 | 339.1 |
| Dense 1,024 x 1,024 `matmul` + copy-back | native | 413.5 | 412.6 |
| Dense 1,024 x 1,024 `matmul` + copy-back | mtl | 440.0 | 440.1 |

A dense type-level step at connectome scale, from `CellTypeRateModelTest.timePerStep` (in
`engine/ml`): a `RatePopulation` on the 11,751 x 11,751 MaleCNS cell-type matrix, with row scale and
saturating rectifier, timed over 1,000 steps after a 100-step warm-up, on the same node. It needs
the derived matrix, which is not in the repository, so it runs only where that data is present.

| Step | Backend | perStepMicros |
|---|---|---|
| Dense 11,751 x 11,751 rate update + copy-back | native (FP64) | 14,527 |
| Dense 11,751 x 11,751 rate update + copy-back | mtl (FP32) | 11,380 |

On Metal the step reads the 0.55 GB matrix at about 48 GB/s, far below the device's memory
bandwidth, so it is the looped reduction kernel, not dispatch, that sets the cost.

The runner optimizes every operation of the step on its own before compiling it. That is what
isolates a reduction such as a large `matmul` into a looped kernel. Compiled without optimization,
each output element's dot product is generated as one unrolled expression, and at about ten
thousand terms Metal rejects the kernel for exceeding its stack space
(`SimulationRunnerTest.largeDenseStep` covers this).

For comparison, `OperationDispatchBatchingTests.manySmallDispatches` on the same node and commit
measured 104.6 µs per dispatch on native and 91.5 µs per dispatch on Metal. (That figure comes
from only 32 timed iterations, so it is noisy.) On Metal a one-element step costs about 155 µs per
dispatch, which is roughly 1.8 times the reference. Each step is a separate run of the compiled
step, and that is the cost a single-step workload pays today; this measurement does not establish
where the difference arises. Each run in the table dispatches more than 21,000 operations on Metal, well past the
roughly 2,300-dispatch point where `manySmallDispatches` notes a Metal stall. The per-step cost
stayed flat and every run completed, so no dispatch-count stall showed up at this scale. No CUDA
node was available for this measurement.
