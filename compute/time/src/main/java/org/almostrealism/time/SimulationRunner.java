/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.time;

import io.almostrealism.concurrent.Submittable;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.lifecycle.Setup;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Producer;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionSlotUpdateComputation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Owns the loop that steps a recurrent dynamical system forward in time.
 *
 * <p>{@link SimulationRunner} is to simulation what {@code ModelOptimizer} is to training and
 * {@code DiffusionSampler} is to sampling: <strong>{@link SimulationRunner} owns the simulation
 * loop.</strong> A system (a neural population, a physical system, an ODE integrator) implements
 * {@link Temporal}, describing one time step as a computation graph in {@link #tick()}; a caller
 * configures a runner around it and calls {@link #run(long)}. No other class writes a
 * {@code for (long step ...)} loop around a compiled step.</p>
 *
 * <h2>What the runner adds to {@link TemporalRunner}</h2>
 * <ul>
 *   <li><strong>A host loop of single steps.</strong> The supertype is always constructed with
 *       {@code iter = 1}, so the step is never fused into an in-kernel
 *       {@link org.almostrealism.hardware.computations.Loop}. Such a loop lets every kernel thread
 *       iterate on its own with no barrier between iterations, which is correct only for
 *       element-local steps; a globally coupled step needs a global synchronization per step.
 *       There is deliberately no way to request several steps per dispatch. The synchronization
 *       is the device's where the device can provide it: each step's operations are chained on
 *       the completion of the work dispatched before them, and where the device orders them
 *       after it on its own the host does not wait for each step (see {@link #run(long)}).</li>
 *   <li><strong>A device-resident step counter</strong> ({@link #getStep()}): a single-element
 *       collection that the system's own graph can consume, for example as time
 *       ({@link #time()}) for a driven input. It is reset by a compiled operation when the
 *       simulation starts and advanced by a compiled operation appended to the step, so the host
 *       never writes it and never reads it back. The host keeps a matching {@code long} count
 *       ({@link #getStepCount()}) for scheduling, which is orchestration, not computation.</li>
 *   <li><strong>Boundary inputs</strong> ({@link #addInput(int, Supplier)}) and
 *       <strong>boundary listeners</strong> ({@link #addBoundaryListener(int, LongConsumer)}),
 *       which run before every {@code interval}-th step.</li>
 *   <li><strong>Recording</strong> ({@link #record(Producer, int, int)}) of any producer of the
 *       state into a device buffer every {@code interval} steps.</li>
 * </ul>
 *
 * <h2>The step contract</h2>
 * <p>The step is dispatched as a sequence of operations: every top-level operation of the
 * system's (flattened) {@link Temporal#tick()} is compiled and dispatched on its own, followed by
 * the counter advance, and each boundary between two such operations is a global
 * synchronization. A single operation is one kernel, so it must not read state that it also
 * writes when other elements' values of that state are involved. A coupled update therefore
 * writes a next-state collection in one operation and copies it back over the state in a later
 * operation of the same step. Element-local updates (each element reads only its own previous
 * value) may update the state in place. Honoring this is the system's responsibility; an
 * {@link OperationList} that carries its own compute requirements is kept intact by flattening
 * and may compile to a single kernel, so it must itself be element-local.</p>
 *
 * <h2>Order of one step</h2>
 * <ol>
 *   <li>Boundary listeners that are due are called with the index of the step about to run,
 *       once every operation dispatched before them has completed.</li>
 *   <li>Boundary inputs that are due are dispatched.</li>
 *   <li>The compiled step runs (the system's tick, then the counter advance).</li>
 *   <li>Recordings that are due write one sample.</li>
 * </ol>
 * <p>A listener or input with interval {@code k} is due before steps {@code 0, k, 2k, ...}; a
 * recording with interval {@code k} samples the state after steps {@code k, 2k, ...}, so its
 * sample {@code j} holds the state at time {@code (j + 1) * k * dt}. The system's setup, if the
 * system is a {@link Setup}, runs once, together with the counter reset, before the first step
 * of the first {@link #run(long)} (and again after {@link #reset()}).</p>
 *
 * <h2>Inherited entry points</h2>
 * <p>As a {@link TemporalRunner}, this class is also a {@link Supplier} of {@link Runnable}, a
 * {@link Temporal} and a {@link Setup}. Every inherited member that would run the compiled step
 * goes through the same bookkeeping as {@link #run(long)}: {@link #get()} and
 * {@link #getContinue()} return a runnable that performs {@code run(1)}, and {@link #tick()}
 * supplies that runnable. The tick so returned is not a computation, so wrapping this runner in
 * another {@link TemporalRunner} with {@code iter > 1} produces a host loop of full steps. The
 * system's setup and the counter reset are the supertype's setup, compiled and released by it;
 * {@link #setup()} supplies a runnable that dispatches them only if they have not run since
 * construction or the last {@link #reset()}, so composing this runner as a {@link Setup} cannot
 * run the system's setup a second time.
 * {@link #getScope(io.almostrealism.kernel.KernelStructureContext)} throws, as it does for every
 * {@link TemporalRunner}, so the runner cannot be embedded as a computation.</p>
 *
 * <h2>Step counter precision</h2>
 * <p>The counter is a floating-point collection. On FP32 backends it counts exactly only up to
 * 2<sup>24</sup> steps. A system that derives time as {@code step * dt} in FP32 loses resolution
 * earlier, because the product has a 24-bit mantissa and its quantum grows with the step
 * count. For the same reason, a {@link Recording} computes its write position
 * {@code index * width} exactly on FP32 backends only while {@code capacity * width} stays
 * within 2<sup>24</sup> elements; a larger recording writes to the wrong rows without an
 * error.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * Temporal population = ...;   // tick() updates the population state as Producers
 * SimulationRunner runner = new SimulationRunner(population, 1e-4);
 * SimulationRunner.Recording rates = runner.record(cp(population.getRates()), 10, 1000);
 * runner.run(10000);
 * PackedCollection samples = rates.getBuffer();   // read on the host after run returns
 * runner.destroy();
 * }</pre>
 *
 * @see TemporalRunner
 * @see Temporal
 *
 * @author Michael Murray
 */
public class SimulationRunner extends TemporalRunner {
	/**
	 * The most steps {@link #run(long)} dispatches ahead of the device before it waits for the
	 * work dispatched so far to complete. The host runs ahead only of completions that the device
	 * orders the next step after (see {@link Semaphore#isOrderedOnDevice()}), so this bounds the
	 * work queued on such a device, and the memory held until its completion is observed, while
	 * letting one wait cover many steps on a backend that batches them. {@code 1} restores a wait
	 * after every step. Controlled by {@code AR_SIMULATION_STEPS_IN_FLIGHT}.
	 */
	public static int maxStepsInFlight = SystemUtils.getInt("AR_SIMULATION_STEPS_IN_FLIGHT").orElse(256);

	/** The system being stepped. */
	private final Temporal system;

	/** The duration of one step, in the system's time unit. */
	private final double dt;

	/** The device-resident step counter. */
	private final PackedCollection step;

	/** Whether {@link #step} was allocated by this runner and is released by {@link #destroy()}. */
	private final boolean ownsStep;

	/** Whether setup has run since construction or the last {@link #reset()}. */
	private boolean setupDone;

	/** The number of steps run since construction or the last {@link #reset()}. */
	private long stepCount;

	/** Compiled boundary inputs, each with its interval. */
	private final List<Scheduled<Runnable>> inputs;

	/** Host boundary listeners, each with its interval. */
	private final List<Scheduled<LongConsumer>> listeners;

	/** The recordings created by {@link #record(Producer, int, int)}. */
	private final List<Recording> recordings;

	/** A runnable performing {@code run(1)}, returned by the inherited entry points. */
	private final Runnable singleStep;

	/**
	 * Creates a runner that allocates and owns its step counter.
	 *
	 * @param system the system to step; if it is a {@link Setup}, its setup runs once before the
	 *               first step
	 * @param dt     the duration of one step
	 */
	public SimulationRunner(Temporal system, double dt) {
		this(system, dt, new PackedCollection(1), true);
	}

	/**
	 * Creates a runner around a step counter supplied by the caller. This is the constructor for a
	 * system whose graph consumes the counter (for example as {@code p(step)} for time), since the
	 * system's tick is built when the runner is constructed. The runner resets and advances the
	 * counter but does not release it.
	 *
	 * @param system the system to step; if it is a {@link Setup}, its setup runs once before the
	 *               first step
	 * @param dt     the duration of one step
	 * @param step   a single-element collection to hold the step counter
	 * @throws IllegalArgumentException if {@code step} does not have exactly one element
	 */
	public SimulationRunner(Temporal system, double dt, PackedCollection step) {
		this(system, dt, step, false);
	}

	/**
	 * Creates a runner around a step counter supplied by the caller, recording per-operation
	 * timing in the given profile.
	 *
	 * @param system  the system to step
	 * @param dt      the duration of one step
	 * @param step    a single-element collection to hold the step counter
	 * @param profile the profile used when compiling the step, setup, inputs and recordings
	 */
	public SimulationRunner(Temporal system, double dt, PackedCollection step,
							OperationProfile profile) {
		this(system, dt, step, false);
		setProfile(profile);
	}

	/**
	 * Shared constructor.
	 *
	 * @param system   the system to step
	 * @param dt       the duration of one step
	 * @param step     the single-element step counter
	 * @param ownsStep whether this runner releases {@code step} when destroyed
	 */
	private SimulationRunner(Temporal system, double dt, PackedCollection step, boolean ownsStep) {
		super(setupOperations(system, step), stepOperations(system, step), 1);
		this.system = system;
		this.dt = dt;
		this.step = step;
		this.ownsStep = ownsStep;
		this.inputs = new ArrayList<>();
		this.listeners = new ArrayList<>();
		this.recordings = new ArrayList<>();
		this.singleStep = () -> run(1);
	}

	/**
	 * Builds the step: the system's tick followed by the counter advance, flattened and marked
	 * so that every top-level operation is dispatched on its own (see the step contract).
	 *
	 * @param system the system being stepped
	 * @param step   the step counter
	 * @return the step operations
	 */
	private static OperationList stepOperations(Temporal system, PackedCollection step) {
		CollectionFeatures f = CollectionFeatures.getInstance();
		OperationList op = new OperationList("Simulation Step", false);
		op.add(system.tick());
		op.add(f.a("Advance Simulation Step", f.cp(step), f.add(f.cp(step), f.c(1.0))));
		return op.flatten();
	}

	/**
	 * Builds the setup: the system's setup, if it has one, followed by the counter reset. This is
	 * the first operation built by the constructor, so it also validates the counter.
	 *
	 * @param system the system being stepped
	 * @param step   the step counter
	 * @return the setup operations
	 * @throws IllegalArgumentException if {@code step} does not have exactly one element
	 */
	private static OperationList setupOperations(Temporal system, PackedCollection step) {
		if (step == null || step.getMemLength() != 1) {
			throw new IllegalArgumentException("The step counter must be a single-element collection");
		}

		CollectionFeatures f = CollectionFeatures.getInstance();
		OperationList op = new OperationList("Simulation Setup", false);
		if (system instanceof Setup) op.add(((Setup) system).setup());
		op.add(f.a("Reset Simulation Step", f.cp(step), f.c(0.0)));
		return op.flatten();
	}

	/** Returns the system being stepped. */
	public Temporal getSystem() { return system; }

	/** Returns the duration of one step. */
	public double getTimeStep() { return dt; }

	/**
	 * Returns the device-resident step counter. Before a step runs it holds the index of that
	 * step, so a system's tick that reads it sees {@code 0} during the first step.
	 *
	 * @return the single-element step counter
	 */
	public PackedCollection getStep() { return step; }

	/**
	 * Returns the simulated time at the start of the current step, {@code step * dt}, as a
	 * producer for use in a system's graph. See the class documentation on precision.
	 *
	 * @return a producer of the current simulated time
	 */
	public CollectionProducer time() {
		return cp(step).multiply(dt);
	}

	/**
	 * Returns the number of steps run since construction or the last {@link #reset()}. This is
	 * the host-side count used for scheduling; it equals the device counter once the counter reset
	 * has run, that is, from the first {@link #run(long)} after construction or {@link #reset()}.
	 *
	 * @return the host step count
	 */
	public long getStepCount() { return stepCount; }

	/**
	 * Adds a compiled operation that runs before every {@code interval}-th step (before steps
	 * {@code 0, interval, 2 * interval, ...}). It is typically an assignment that folds an
	 * external collection into the system's state. The operation is compiled now, with the
	 * runner's profile, and released by {@link #destroy()}.
	 *
	 * @param interval  the number of steps between dispatches; must be positive
	 * @param operation the operation to dispatch
	 * @throws IllegalArgumentException if {@code interval} is not positive
	 */
	public void addInput(int interval, Supplier<Runnable> operation) {
		OperationList op = new OperationList("Simulation Input", false);
		op.add(operation);
		Runnable compiled = op.get(getProfile());

		try {
			inputs.add(new Scheduled<>(interval, compiled));
		} catch (IllegalArgumentException e) {
			Destroyable.destroy(compiled);
			throw e;
		}
	}

	/**
	 * Adds a host listener called with the index of the step about to run, before every
	 * {@code interval}-th step and before the boundary inputs due at that step. This is the one
	 * place in the loop where host code runs: it is where a closed loop (an external simulator
	 * writing into a collection that a boundary input folds into the state) is attached. A
	 * listener must not read the system's state back from the device on every call.
	 *
	 * @param interval the number of steps between calls; must be positive
	 * @param listener the listener
	 * @throws IllegalArgumentException if {@code interval} is not positive
	 */
	public void addBoundaryListener(int interval, LongConsumer listener) {
		listeners.add(new Scheduled<>(interval, listener));
	}

	/**
	 * Records {@code selected} into a device buffer of {@code capacity} samples after every
	 * {@code interval}-th step. The buffer is read on the host only when the caller asks for it,
	 * after {@link #run(long)} returns. A run that would record more than {@code capacity}
	 * samples fails before any step runs.
	 *
	 * @param selected any producer of (a selection of) the state
	 * @param interval the number of steps between samples; must be positive
	 * @param capacity the maximum number of samples; must be positive
	 * @return the recording, owned and released by this runner
	 * @throws IllegalArgumentException if {@code interval} or {@code capacity} is not positive
	 */
	public Recording record(Producer<PackedCollection> selected, int interval, int capacity) {
		Recording recording = new Recording(selected, interval, capacity, getProfile());
		recordings.add(recording);
		return recording;
	}

	/**
	 * Runs {@code steps} steps of the simulation. This is the simulation loop: each iteration
	 * dispatches compiled operations and performs {@code long} arithmetic for scheduling, and
	 * nothing in it evaluates a producer or reads device memory.
	 *
	 * <p>The inputs, steps and recordings run in the order of this loop. Each compiled operation
	 * is chained on the completion of the one dispatched before it wherever the device orders it
	 * after that completion on its own (see {@link Submittable#chainOnDevice(Semaphore)} and
	 * {@link Semaphore#isOrderedOnDevice()}): the host then does not wait for a step before
	 * dispatching the next, and a backend that batches dispatches (Metal) accumulates many steps
	 * into one command buffer instead of committing and waiting for every step. A backend that
	 * would hold a host thread for every dispatch issued ahead of its dependency is waited for
	 * before each operation instead, as running ahead of it gains nothing. Either way the host
	 * also waits where host code needs the work ahead of it to be complete &mdash; before
	 * calling boundary listeners that are due, before an operation that cannot be chained, and
	 * once at the end of the run, so the state may be read when this method returns &mdash; and
	 * after every {@link #maxStepsInFlight} steps, which bounds how far it runs ahead of the
	 * device. If dispatching fails, the work already dispatched is waited for before the
	 * failure propagates.</p>
	 *
	 * @param steps the number of steps to run; must not be negative
	 * @throws IllegalArgumentException if {@code steps} is negative
	 * @throws IllegalStateException    if a recording would exceed its capacity
	 */
	public void run(long steps) {
		if (steps < 0) {
			throw new IllegalArgumentException("Cannot run a negative number of steps (" + steps + ")");
		}

		for (Recording recording : recordings) {
			recording.checkCapacity(stepCount, steps);
		}

		runSetupIfPending();
		Submittable compiledStep = Submittable.of(super.getContinue());
		Semaphore pending = null;
		int inFlight = 0;

		try {
			for (long i = 0; i < steps; i++) {
				boolean listenersDue = isListenerDue(stepCount);

				// A listener is host code, so everything dispatched ahead of it must be complete
				if (listenersDue || inFlight >= maxStepsInFlight) {
					if (pending != null) {
						pending.waitFor();
						pending = null;
					}

					inFlight = 0;
				}

				if (listenersDue) {
					for (Scheduled<LongConsumer> listener : listeners) {
						if (listener.isDue(stepCount)) listener.getAction().accept(stepCount);
					}
				}

				for (Scheduled<Runnable> input : inputs) {
					if (input.isDue(stepCount)) pending = Submittable.of(input.getAction()).chainOnDevice(pending);
				}

				pending = compiledStep.chainOnDevice(pending);
				stepCount++;
				inFlight++;

				for (Recording recording : recordings) {
					if (recording.isDue(stepCount)) pending = recording.write(pending);
				}
			}
		} catch (RuntimeException | Error e) {
			if (pending != null) pending.waitForSettled(e);
			throw e;
		}

		if (pending != null) pending.waitFor();
	}

	/**
	 * Returns whether any boundary listener is due before the given step.
	 *
	 * @param step the index of the step about to run
	 * @return true when at least one listener's interval divides {@code step}
	 */
	private boolean isListenerDue(long step) {
		for (Scheduled<LongConsumer> listener : listeners) {
			if (listener.isDue(step)) return true;
		}

		return false;
	}

	/**
	 * Returns the simulation to its initial condition: the host step count and every recording's
	 * sample count return to zero, the system's setup (and the counter reset) run again before
	 * the next step, and, if the system is a {@link Lifecycle}, its {@link Lifecycle#reset()} is
	 * called, as {@link Temporal#iter(int, boolean)} does. Compiled operations are kept, so a
	 * second simulation needs no recompilation.
	 */
	public void reset() {
		stepCount = 0;
		setupDone = false;
		recordings.forEach(Recording::reset);
		if (system instanceof Lifecycle) ((Lifecycle) system).reset();
	}

	/**
	 * Dispatches the compiled setup (the system's setup and the counter reset) if it has not run
	 * since construction or the last {@link #reset()}.
	 */
	protected void runSetupIfPending() {
		if (setupDone) return;
		getCompiledSetup().run();
		setupDone = true;
	}

	/**
	 * Returns a supplier of a runnable that runs the setup only if it is pending (see
	 * {@link #runSetupIfPending()}), so that composing this runner as a {@link Setup} runs the
	 * system's setup at most once per simulation.
	 *
	 * @return a supplier of a runnable that runs pending setup
	 */
	@Override
	public Supplier<Runnable> setup() { return () -> this::runSetupIfPending; }

	/**
	 * Returns a runnable that performs {@code run(1)}, so that a caller using this runner as a
	 * {@link Supplier} keeps the step counter, schedules and recordings in step. The system's
	 * setup still runs only once.
	 *
	 * @return a runnable that runs one step through {@link #run(long)}
	 */
	@Override
	public Runnable get() { return singleStep; }

	/**
	 * Returns a runnable that performs {@code run(1)}.
	 *
	 * @return a runnable that runs one step through {@link #run(long)}
	 */
	@Override
	public Runnable getContinue() { return singleStep; }

	/**
	 * Returns a supplier of the runnable that performs {@code run(1)}. It is a plain supplier,
	 * not a computation, so a {@link TemporalRunner} that wraps this runner with
	 * {@code iter > 1} produces a host loop of full steps rather than an in-kernel loop.
	 *
	 * @return a supplier of a runnable that runs one step through {@link #run(long)}
	 */
	@Override
	public Supplier<Runnable> tick() { return this::getContinue; }

	/**
	 * Releases the compiled inputs and recordings, the recording buffers, and the step counter if
	 * this runner allocated it, and delegates to {@link TemporalRunner#destroy()} for the setup
	 * and the step. Every release is attempted even when an earlier one fails.
	 */
	@Override
	public void destroy() {
		List<Runnable> releases = new ArrayList<>();
		inputs.forEach(input -> releases.add(() -> Destroyable.destroy(input.getAction())));
		recordings.forEach(recording -> releases.add(recording::destroy));
		releases.add(super::destroy);
		if (ownsStep) releases.add(step::destroy);

		Destroyable.releaseAll(releases);
		inputs.clear();
		recordings.clear();
		listeners.clear();
	}

	/**
	 * An action dispatched on a fixed step interval.
	 *
	 * @param <T> the type of the action
	 */
	private static final class Scheduled<T> {
		/** The number of steps between dispatches. */
		private final int interval;
		/** The action. */
		private final T action;

		/**
		 * @param interval the number of steps between dispatches; must be positive
		 * @param action   the action
		 * @throws IllegalArgumentException if {@code interval} is not positive
		 */
		Scheduled(int interval, T action) {
			if (interval <= 0) {
				throw new IllegalArgumentException("interval must be positive (" + interval + ")");
			}

			this.interval = interval;
			this.action = action;
		}

		/** Returns the action. */
		T getAction() { return action; }

		/**
		 * Returns whether the action is due before the given step.
		 *
		 * @param step the index of the step about to run
		 * @return true when {@code step} is a multiple of the interval
		 */
		boolean isDue(long step) { return step % interval == 0; }
	}

	/**
	 * A device-resident recording of a producer of a simulation's state, created by
	 * {@link SimulationRunner#record(Producer, int, int)}.
	 *
	 * <p>The recording owns a buffer of shape {@code (capacity, width)}, where {@code width} is
	 * the size of the recorded producer, and a device-resident sample index. One write is a
	 * single compiled operation list: a {@link CollectionSlotUpdateComputation} that replaces row
	 * {@code index} of the buffer with the recorded values, assigned back over the buffer, then
	 * an advance of the index. Each output element of the slot update reads only its own element
	 * of the buffer, so writing the buffer in the kernel that reads it is safe. The cost of a
	 * write is proportional to {@code capacity * width}.</p>
	 */
	public static class Recording implements Destroyable {
		/** The number of steps between samples. */
		private final int interval;
		/** The maximum number of samples. */
		private final int capacity;
		/** The number of elements per sample. */
		private final int width;
		/** The sample buffer, shape {@code (capacity, width)}. */
		private final PackedCollection buffer;
		/** The device-resident index of the next sample. */
		private final PackedCollection index;
		/** The compiled write of one sample (including the index advance). */
		private final Runnable write;
		/** The compiled reset of {@link #index} to zero. */
		private final Runnable resetIndex;
		/** The number of samples written, kept on the host for capacity checks. */
		private long sampleCount;

		/**
		 * Creates a recording, compiles its write and index reset, and resets the index. If any of
		 * these fails, everything already allocated or compiled is released before the failure
		 * propagates.
		 *
		 * @param selected the producer to record
		 * @param interval the number of steps between samples
		 * @param capacity the maximum number of samples
		 * @param profile  the profile used when compiling, or {@code null}
		 * @throws IllegalArgumentException if {@code interval} or {@code capacity} is not positive
		 */
		protected Recording(Producer<PackedCollection> selected, int interval, int capacity,
							OperationProfile profile) {
			if (interval <= 0 || capacity <= 0) {
				throw new IllegalArgumentException("interval and capacity must be positive ("
						+ interval + ", " + capacity + ")");
			}

			CollectionFeatures f = CollectionFeatures.getInstance();
			this.interval = interval;
			this.capacity = capacity;
			this.width = f.shape(selected).getTotalSize();
			this.buffer = new PackedCollection(f.shape(capacity, width));
			this.index = new PackedCollection(1);

			OperationList op = new OperationList("Simulation Recording", false);
			op.add(f.a("Record Simulation Sample", f.cp(buffer),
					new CollectionSlotUpdateComputation(buffer.getShape(), 1, capacity * width, width,
							f.cp(buffer), selected, f.cp(index).multiply(width))));
			op.add(f.a("Advance Recording Index", f.cp(index), f.add(f.cp(index), f.c(1.0))));

			OperationList reset = new OperationList("Reset Recording Index", false);
			reset.add(f.a("Reset Recording Index", f.cp(index), f.c(0.0)));

			Runnable compiledWrite = null;
			Runnable compiledReset = null;

			try {
				compiledWrite = op.get(profile);
				compiledReset = reset.get(profile);
				compiledReset.run();
			} catch (RuntimeException e) {
				Destroyable.destroy(compiledWrite);
				Destroyable.destroy(compiledReset);
				buffer.destroy();
				index.destroy();
				throw e;
			}

			this.write = compiledWrite;
			this.resetIndex = compiledReset;
		}

		/** Returns the number of steps between samples. */
		public int getInterval() { return interval; }

		/**
		 * Returns whether a sample is due after the given number of steps.
		 *
		 * @param stepCount the number of steps run so far, including the step just completed
		 * @return true when {@code stepCount} is a multiple of the interval
		 */
		protected boolean isDue(long stepCount) { return stepCount % interval == 0; }

		/** Returns the maximum number of samples. */
		public int getCapacity() { return capacity; }

		/** Returns the number of elements per sample. */
		public int getWidth() { return width; }

		/** Returns the number of samples written so far. */
		public long getSampleCount() { return sampleCount; }

		/**
		 * Returns the sample buffer, shape {@code (capacity, width)}; row {@code j} holds sample
		 * {@code j} for {@code j < getSampleCount()}.
		 *
		 * @return the sample buffer
		 */
		public PackedCollection getBuffer() { return buffer; }

		/**
		 * Throws if running {@code steps} more steps from host step {@code from} would write more
		 * samples than the capacity.
		 *
		 * @param from  the host step count before the run
		 * @param steps the number of steps to run
		 * @throws IllegalStateException if the capacity would be exceeded
		 */
		protected void checkCapacity(long from, long steps) {
			long projected = sampleCount + (from + steps) / interval - from / interval;
			if (projected > capacity) {
				throw new IllegalStateException("Running " + steps + " steps would record "
						+ projected + " samples, exceeding the capacity of " + capacity);
			}
		}

		/**
		 * Writes one sample, ordered after {@code dependsOn} (see
		 * {@link Submittable#chainOnDevice(Semaphore)}).
		 *
		 * @param dependsOn the completion the write must be ordered after, or {@code null}
		 * @return the completion of the write, or {@code null} if it has already completed
		 */
		protected Semaphore write(Semaphore dependsOn) {
			Semaphore written = Submittable.of(write).chainOnDevice(dependsOn);
			sampleCount++;
			return written;
		}

		/** Returns the sample index to zero. */
		protected void reset() {
			resetIndex.run();
			sampleCount = 0;
		}

		/** Releases the compiled operations and the buffers. */
		@Override
		public void destroy() {
			Destroyable.releaseAll(List.of(
					() -> Destroyable.destroy(write),
					() -> Destroyable.destroy(resetIndex),
					buffer::destroy,
					index::destroy));
		}
	}
}
