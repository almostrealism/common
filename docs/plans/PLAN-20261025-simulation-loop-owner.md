# Plan: a simulation loop owner for recurrent dynamical systems

**Tracker task:** [6d8bbb62-cf36-481c-8dbb-f82147029a78] "A simulation loop owner for recurrent
dynamical systems". Release: Common 0.76.

**Related:** `docs/plans/CONNECTOME_MODEL_ORGANISM.md` (Phase 1 task 7). This plan also answers
the "simulation loop owner" half of that plan's open question 3: where the owner lives.

## Motivation

The platform's rule is that one class owns each loop. `ModelOptimizer` owns training and
`DiffusionSampler` owns sampling. Nothing owns the loop that steps a recurrent dynamical system
(a neural population, a physical system, an ODE integrator) forward in time. So every consumer
writes its own `for (int step ...)` loop, with its own step counter, its own recording, and its
own way of injecting input.

The connectome model organism needs this loop twice: for the cell-type rate model [186697ce] and
for the neuron-level spiking model [04470395, d72c5ca4, 2a8fe992]. Both are globally coupled: every
element's next state depends on the previous state of other elements. One step therefore needs a
global synchronization, and per-step dispatch overhead is what limits throughput. At a 0.1 ms step,
one simulated second is 10,000 steps. Phase 2 will also need to inject input from a body simulator
at step boundaries. That hook should exist before anyone builds a closed loop on top of an ad-hoc
loop.

## What exists today (and why none of it is the owner as-is)

| Type | Module | What it gives | Why it is not the owner on its own |
|---|---|---|---|
| `Temporal` | `compute/time` | `tick()`: one step as a `Supplier<Runnable>` | It describes the system being stepped. It does not own a loop. |
| `TemporalRunner` | `compute/time` | Compiles `setup()` and `tick()` once, with flatten/optimize/profile/destroy; `get()` runs setup then tick; `getContinue()` runs tick only | It has no step counter, no recording and no inputs. Worse, `iter > 1` wraps the tick in `HardwareFeatures.loop` and so in `Loop` (`base/hardware`, `computations/Loop`), which emits a `Repeated` scope **inside one kernel**. Each kernel thread then iterates on its own with no barrier between iterations, so a globally coupled step races: thread *i* reads thread *j*'s state from an unknown iteration. That is correct only for element-local steps. |
| `CellList` | `engine/audio` | Cell graphs, `Temporal` ticking, audio buffering | Audio-specific, and it sits above `domain/graph`, so the population dynamics in `domain/graph` could not depend on it. Its push/receptor model is also not a state-update model. |
| `AutoregressiveModel` | `engine/ml` | Token generation with a **device-resident position**: `resetPosition`/`advancePosition` are compiled once and the host never writes the position | The domain is tokens, prompts and sampling. Its device-resident counter is the pattern to copy, not a type to extend. |
| `Periodic` | `base/hardware` | Runs an atom every *n* invocations, with the counter in compiled code | It increments its counter inside the atom's kernel. If the atom has count > 1, every thread increments it. It is unsuitable for recording a vector, so recording is scheduled from the host step count instead (see below). |
| `CollectionReceptor(dest, pos)` | `domain/graph` | Writes a row at a position | It evaluates `pos` on the host (`toDouble`), a round trip per write. |
| `CollectionSlotUpdateComputation` | `compute/algebra` | Replaces one slot of a collection at a **device-resident** position, in one expression | It works for recording, but every write rewrites the whole base (cost O(capacity × width)). See open question 2. |
| `OperationDispatchBatchingTests` | `engine/utils` (test) | The reference harness for per-dispatch overhead, by backend | It measures independent operations, not a stateful step. Its numbers are a baseline to compare against. |

Module dependencies (from the `pom.xml` files): `compute/time` depends on `ar-geometry`, which
depends on `ar-algebra` and so on `ar-hardware`. `domain/heredity` depends on `ar-time`, and
`domain/graph` depends on `ar-heredity`. `domain/physics` depends on `ar-time` directly. So
`domain/physics` and `domain/graph` both reach `ar-time`, and `engine/ml` reaches it through
`ar-utils`.

## Placement decision

**`SimulationRunner extends TemporalRunner`**, in `compute/time`, package `org.almostrealism.time`.

- It *is* a `TemporalRunner`: it compiles a `Temporal`'s setup and tick once and keeps the same
  flatten/optimize/profile/destroy lifecycle. It always constructs its supertype with `iter = 1`,
  so the in-kernel `Loop` is never used for the step. The class adds the host-side loop that
  `TemporalRunner` lacks, and the state around it: the step counter, recorders, and boundary inputs.
- `compute/time` is the lowest module that holds `Temporal`, and the owner is domain-agnostic. A
  damped oscillator in `domain/physics`, a rate or spiking population in `domain/graph`, and an
  ODE in `compute/algebra` users can all reach it. Placing it in `domain/graph` would hide it
  from physics; placing it in `engine/*` would put it above the population dynamics that need it.
- The rule follows the existing ones: **`SimulationRunner` owns the simulation loop.** No other
  class writes `for (long step ...)` around a compiled step.

## Scope

1. **`SimulationRunner`** (new, `compute/time`, `org.almostrealism.time`), with javadoc:
   - Constructed from a `Temporal` (with `Setup` where the system has one), a time step `dt`, and
     an optional `OperationProfile`.
   - **Device-resident step counter.** A single-element `PackedCollection` that the runner owns
     and exposes (`getStep()`), so the system's own graph can consume `p(step)`: for example, time
     as `step × dt` for a driven input, or as the counter for counter-based device random numbers
     [18c76a7a]. `reset` and `advance` are compiled once, as in `AutoregressiveModel`. The advance
     is appended to the compiled step's `OperationList`, so it adds an operation to the step but no
     separate host call. The host keeps a matching `long` count for scheduling, which is
     orchestration rather than computation, and never reads the device counter back.
   - **`run(long steps)`**: the one loop. Per step, it runs the boundary inputs that are due, then
     the compiled step (which includes advancing the counter), then the recorders that are due.
     There is no `.evaluate()` and no host read inside the loop.
   - **Boundary inputs**: `addInput(int interval, Supplier<Runnable> operation)` takes a compiled
     operation (typically an assignment that folds an external `PackedCollection` into state)
     that runs before every `interval`-th step. A host hook,
     `addBoundaryListener(int interval, LongConsumer listener)`, is called at the same boundary
     with the step number. That listener is the one place a closed loop (a body simulator writing
     into an input collection) runs host code, and it is documented as such.
   - **Recording**: `record(Producer<PackedCollection> selected, int interval, int capacity)`
     returns a recording handle that owns a device buffer of shape `(capacity, width)` and a
     device-resident sample index. Its write is compiled once, as a `CollectionSlotUpdateComputation`
     or the cheaper write chosen in open question 2, and dispatched every `interval` steps. The
     selection is any producer of the state. Chosen neurons are, for example, a gather or subset
     of the population's rate or spike collection. The buffer is read on the host only when the
     caller asks for it, after `run` returns. When a run would exceed `capacity`, `run` fails fast
     before it starts (see open question 3).
   - **`destroy()`** releases the counter, the compiled counter, input and recorder operations,
     and the recording buffers it allocated, and then delegates to `TemporalRunner.destroy()`.
   - **The step contract**, stated in the javadoc: one compiled step may consist of several
     kernels, and each kernel boundary is a global synchronization. A kernel must not read state
     that it also writes, if other elements' values of that state are involved. A coupled update
     writes a next-state collection and copies it back as a later operation of the same step.
     This is the system's responsibility; the runner documents it, and the tests exercise it.
2. **Tests** in `engine/utils/src/test/java/org/almostrealism/time/test/` (where the `compute/time`
   tests already live), all extending `TestSuiteBase` with `@Test(timeout = ...)`:
   - `SimulationRunnerTest`:
     - *Leaky integrator, closed form.* `dx/dt = (I − x)/τ` for a vector of N elements with
       different τ, stepped with the exact exponential update
       `x ← I + (x − I)·exp(−dt/τ)` as Producer math. After 10,000 steps, and at every recorded
       sample (interval > 1), it matches `x(n·dt) = I + (x₀ − I)·exp(−n·dt/τ)` to FP32 tolerance.
     - *Damped harmonic oscillator, coupled state.* N independent oscillators whose 2-vector state
       is advanced by the exact discrete propagator (a 2×2 matrix applied with `matmul`, written to
       a next-state buffer and copied back). After 10,000 steps it matches
       `e^{−γt}(A cos ωt + B sin ωt)`. This proves the coupled-step contract on every backend.
     - *Boundary input.* The drive `I` changes at step *k* through `addInput` from an external
       collection that a boundary listener writes. The trajectory matches the piecewise closed
       form, and the listener is called exactly `steps / interval` times.
     - *Step counter.* After `run(a)` and then `run(b)`, a compiled read of `p(step)`, evaluated by
       the test at the top of its stack, equals `a + b`. A driven system that uses `step × dt`
       matches its closed form.
     - *Lifecycle.* `destroy()` releases what the runner allocated, and a capacity overflow fails
       before any step runs.
   - `SimulationRunnerDispatchTest`: microseconds per step for (a) a one-element step, (b) a
     100,000-element element-wise step, and (c) a dense 1,024 × 1,024 `matmul` step with
     copy-back, each with and without one recorder, over 10,000 steps after warm-up. The numbers
     are logged as `perStepMicros=` values. It asserts only that the runs complete and agree with
     the closed form, never a time threshold, so it cannot flake on a slow node.
3. **Measurement**: run `SimulationRunnerDispatchTest` with `AR_HARDWARE_DRIVER=native` and
   `AR_HARDWARE_DRIVER=mtl` on a macOS node, and on CUDA if a CUDA node is available to the
   implementation. Record the table, with the node and commit, in the internals document. Compare
   with the `OperationDispatchBatchingTests.manySmallDispatches` per-dispatch figure on the same
   node.
4. **Documentation**:
   - New `docs/internals/simulation-loop-examples.md`, matching `training-loop-examples.md` and
     `sampling-loop-examples.md`: the correct pattern (a system implements `Temporal`, and a thin
     caller configures `SimulationRunner` and calls `run` once), the wrong patterns (a step loop
     anywhere else; `buffer(n)`/`Loop` for a coupled step; reading state on the host per step),
     the step contract, the boundary-input hook, and the measured overhead table.
   - Root `CLAUDE.md`, "Architectural Principles": add a **Simulation Loops** line beside the
     training and sampling rules, pointing at the new document.
   - `compute/time/README.md`: a `SimulationRunner` section next to `TemporalRunner`, plus a
     javadoc note on `TemporalRunner` and `Temporal.buffer` that `iter > 1` fuses ticks inside one
     kernel and is correct only for element-local steps.
   - `docs/plans/CONNECTOME_MODEL_ORGANISM.md`: fill in the placement table row for the
     simulation loop owner (`compute/time`, `SimulationRunner`).

Out of scope: neuron dynamics (task 04470395), the sparse product (task 66b57639), device random
numbers (task 18c76a7a), the body-simulator bridge itself (Phase 2), and any change to how
backends batch or submit dispatches.

## Approach notes

- The step `OperationList` is built as setup/tick suppliers plus the counter advance, and is passed
  to `TemporalRunner` with `iter = 1`, so flattening and the optional `Process.optimized` path
  apply to the step exactly as they do today. The compiled runnable comes from
  `getContinue()`.
- Inputs and recorders are separate compiled runnables. They cannot be folded into the step
  because they run on a different schedule. Each adds one dispatch on the steps where it is due.
  The dispatch test measures that cost with and without one recorder.
- The host loop holds only `long` arithmetic and `Runnable.run()` calls, which is what "Java is
  orchestration" permits at a step boundary. `CodePolicyViolationDetector` must pass unchanged.
- On Metal, earlier work moved completion signalling to once per command buffer (see the recent
  `MetalCommandRunner` history). 10,000-step runs are the first sustained single-step workload in
  the test suite, so the dispatch test also confirms that no dispatch-count stall remains at that
  scale. If one does, it is filed as a defect, not worked around in the runner.

## Success criteria

- `SimulationRunnerTest` passes on native and Metal: the leaky integrator and the damped
  oscillator match their closed forms after 10,000 steps, the boundary-input case matches its
  piecewise closed form, and the counter and lifecycle cases pass.
- `SimulationRunnerDispatchTest` has been run on native and Metal (and CUDA if available), and
  its per-step figures for the three step sizes are recorded in
  `docs/internals/simulation-loop-examples.md`.
- The rule "`SimulationRunner` owns the simulation loop" appears in `CLAUDE.md` and in the new
  internals document, next to the training- and sampling-loop rules.
- `mvn clean install -DskipTests` succeeds, and the build validator (checkstyle, code_policy,
  test_timeouts, duplicate_code) is clean.

## Dependencies

- None blocking: the task has no `blocked_by` entries, and everything it builds on
  (`TemporalRunner`, `OperationList`, `CollectionSlotUpdateComputation`, `matmul`) is on master.
- Measuring on Metal needs a macOS node (see `task-20261025-161748-workstream.yaml`).
- The rate model [186697ce] and both Shiu et al. reproductions [d72c5ca4, 2a8fe992] build on this
  owner. The throughput benchmark [1cd4083d] reuses its dispatch measurement.

## Open questions

1. **Name and placement.** This plan proposes `SimulationRunner extends TemporalRunner` in
   `compute/time`. The alternative is to keep `TemporalRunner` unchanged and put a non-extending
   owner beside it. That costs duplicating the compile/flatten/destroy logic, so the plan prefers
   extension.
   <!-- TODO(review): state how inherited get()/getContinue()/tick() are handled; they step the device counter without the host count, inputs, or recorders. -->

2. **Recording write cost.** `CollectionSlotUpdateComputation` rewrites the whole recording buffer
   on each write. With a large capacity this could dominate a record step. The first
   implementation step measures it. If it is too slow, the alternatives are (a) a small device
   buffer of K rows that is flushed to a host-side collection every K samples (one round trip per
   K samples, not per step), or (b) an assignment into a device-positioned subset of the buffer,
   if the assignment path can write to a dynamic offset in place. Option (b) would be general
   capability in `compute/algebra`; if it needs real work, it is filed separately rather than
   absorbed here.
3. **Capacity overflow.** Should overflow fail fast (proposed), wrap as a ring buffer, or trigger
   the chunked flush from question 2?
4. **Step counter precision.** The counter is a floating-point collection. On FP32 backends it is
   exact only to 2^24 steps, which is about 28 simulated minutes at 0.1 ms. That is ample for
   Phase 1, but it is the same limit as task [2c0793a7] (integer-exact index storage), and the
   counter should move to that storage once it exists.
5. **Steps per dispatch.** For element-local systems, `Loop` *could* fuse many steps per dispatch.
   Should `SimulationRunner` offer that as an explicit opt-in, or refuse it so that no caller can
   apply it to a coupled step by mistake? The plan proposes refusing it in this task and revisiting
   after the measurements.

## Estimated complexity

**Medium.** One new production class of moderate size, two test classes, documentation, and a
measurement pass on two or three backends. The main risks are the recording write cost (open
question 2) and a possible sustained-dispatch defect on Metal, which would be filed separately.
