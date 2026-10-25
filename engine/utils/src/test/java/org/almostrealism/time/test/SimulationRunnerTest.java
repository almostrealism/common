/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.time.test;

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Tests for {@link SimulationRunner}: closed-form agreement of element-local and coupled
 * systems over long runs, boundary inputs, the device-resident step counter, recording,
 * lifecycle, reset, and the inherited {@link TemporalRunner} entry points.
 */
public class SimulationRunnerTest extends TestSuiteBase {
	/** Step size shared by the tests. */
	private static final double DT = 1e-3;

	/**
	 * A vector of leaky integrators {@code dx/dt = (I - x) / tau} with per-element time
	 * constants, stepped in place with the exact exponential update
	 * {@code x <- I + (x - I) * exp(-dt / tau)}, which is element-local.
	 */
	private static class LeakyIntegrator implements Temporal {
		/** The state. */
		private final PackedCollection x;
		/** The drive {@code I}. */
		private final PackedCollection drive;
		/** The per-element time constants. */
		private final PackedCollection tau;

		/**
		 * @param tau   the per-element time constants
		 * @param x     the state, holding the initial condition
		 * @param drive the drive collection
		 */
		LeakyIntegrator(PackedCollection tau, PackedCollection x, PackedCollection drive) {
			this.x = x;
			this.drive = drive;
			this.tau = tau;
		}

		@Override
		public Supplier<Runnable> tick() {
			return a("leakyIntegrator", cp(x), cp(drive).add(cp(x).subtract(cp(drive))
					.multiply(exp(cp(tau).pow(-1.0).multiply(-DT)))));
		}
	}

	/**
	 * Returns geometrically spaced time constants from {@code 0.1} to {@code 5}.
	 *
	 * @param n the number of time constants
	 * @return the time constants
	 */
	private PackedCollection timeConstants(int n) {
		return integers(0, n).multiply(Math.log(50.0) / (n - 1)).exp().multiply(0.1).evaluate();
	}

	/**
	 * Returns the closed-form leaky integrator state after {@code steps} steps.
	 *
	 * @param x0    the initial state
	 * @param drive the constant drive
	 * @param tau   the time constant
	 * @param steps the number of steps
	 * @return the exact state
	 */
	private static double leaky(double x0, double drive, double tau, long steps) {
		return drive + (x0 - drive) * Math.exp(-steps * DT / tau);
	}

	/**
	 * After 10,000 steps, and at every recorded sample, the leaky integrator matches its closed
	 * form.
	 */
	@Test(timeout = 300000)
	public void leakyIntegratorClosedForm() {
		int n = 8;
		int steps = 10000;
		int interval = 1000;
		PackedCollection tau = timeConstants(n);
		PackedCollection drive = new PackedCollection(n).fill(1.0);
		LeakyIntegrator system = new LeakyIntegrator(tau, new PackedCollection(n), drive);

		SimulationRunner runner = new SimulationRunner(system, DT);
		SimulationRunner.Recording recording = runner.record(cp(system.x), interval, steps / interval);
		runner.run(steps);

		assertEquals(steps, runner.getStepCount());
		assertEquals(steps / interval, recording.getSampleCount());

		PackedCollection samples = recording.getBuffer();
		for (int i = 0; i < n; i++) {
			assertEquals(leaky(0.0, 1.0, tau.toDouble(i), steps), system.x.toDouble(i), 2e-3);

			for (int j = 0; j < steps / interval; j++) {
				long at = (long) (j + 1) * interval;
				assertEquals(leaky(0.0, 1.0, tau.toDouble(i), at), samples.toDouble(j * n + i), 2e-3);
			}
		}

		runner.destroy();
	}

	/**
	 * Damped harmonic oscillators {@code x'' + 2 gamma x' + omega0^2 x = 0} whose state
	 * {@code (x, v)} is coupled: each step applies the exact 2x2 discrete propagator with
	 * {@code matmul} into a next-state buffer, then copies it back as a later operation of the
	 * same step. After 10,000 steps the positions match
	 * {@code exp(-gamma t) (A cos(omega t) + B sin(omega t))}.
	 */
	@Test(timeout = 300000)
	public void dampedOscillatorCoupledState() {
		int n = 4;
		int steps = 10000;
		double gamma = 0.1;
		double omega0 = 2 * Math.PI;
		double omega = Math.sqrt(omega0 * omega0 - gamma * gamma);
		double decay = Math.exp(-gamma * DT);
		double cos = Math.cos(omega * DT);
		double sin = Math.sin(omega * DT);

		PackedCollection propagator = pack(
				decay * (cos + gamma / omega * sin), decay * sin / omega,
				-decay * omega0 * omega0 / omega * sin, decay * (cos - gamma / omega * sin))
				.reshape(shape(2, 2));

		double[] x0 = { 1.0, 0.0, -0.5, 0.25 };
		double[] v0 = { 0.0, 1.0, 2.0, -1.5 };
		PackedCollection state = pack(1.0, 0.0, -0.5, 0.25, 0.0, 1.0, 2.0, -1.5).reshape(shape(2, n));
		PackedCollection next = new PackedCollection(shape(2, n));

		Temporal system = () -> {
			OperationList tick = new OperationList("Damped Oscillator");
			tick.add(a("propagate", cp(next), matmul(cp(propagator), cp(state))));
			tick.add(a("copyBack", cp(state), cp(next)));
			return tick;
		};

		SimulationRunner runner = new SimulationRunner(system, DT);
		SimulationRunner.Recording recording = runner.record(cp(state), 2500, 4);
		runner.run(steps);

		PackedCollection samples = recording.getBuffer();
		for (int i = 0; i < n; i++) {
			double a = x0[i];
			double b = (v0[i] + gamma * x0[i]) / omega;

			for (int j = 0; j < 4; j++) {
				double t = (j + 1) * 2500 * DT;
				double expected = Math.exp(-gamma * t) * (a * Math.cos(omega * t) + b * Math.sin(omega * t));
				assertEquals(expected, samples.toDouble(j * 2 * n + i), 5e-3);
			}

			double t = steps * DT;
			double expected = Math.exp(-gamma * t) * (a * Math.cos(omega * t) + b * Math.sin(omega * t));
			assertEquals(expected, state.toDouble(i), 5e-3);
		}

		runner.destroy();
	}

	/**
	 * The drive changes at step {@code k}: a boundary listener switches an external collection
	 * and a boundary input folds it into the drive. The trajectory matches the piecewise closed
	 * form and the listener is called exactly {@code steps / interval} times.
	 */
	@Test(timeout = 300000)
	public void boundaryInput() {
		int n = 4;
		int steps = 10000;
		int k = 5000;
		int interval = 100;
		PackedCollection tau = timeConstants(n);

		PackedCollection drive = new PackedCollection(n);
		PackedCollection external = new PackedCollection(n).fill(1.0);
		PackedCollection second = new PackedCollection(n).fill(-2.0);
		Runnable switchDrive = a(cp(external), cp(second)).get();

		LeakyIntegrator system = new LeakyIntegrator(tau, new PackedCollection(n), drive);
		SimulationRunner runner = new SimulationRunner(system, DT);
		AtomicInteger calls = new AtomicInteger();
		runner.addBoundaryListener(interval, step -> {
			calls.incrementAndGet();
			if (step == k) switchDrive.run();
		});
		runner.addInput(interval, a(cp(drive), cp(external)));
		SimulationRunner.Recording recording = runner.record(cp(system.x), 2500, 4);
		runner.run(steps);

		assertEquals(steps / interval, calls.get());

		PackedCollection samples = recording.getBuffer();
		for (int i = 0; i < n; i++) {
			double atSwitch = leaky(0.0, 1.0, tau.toDouble(i), k);
			assertEquals(leaky(0.0, 1.0, tau.toDouble(i), 2500), samples.toDouble(i), 2e-3);
			assertEquals(atSwitch, samples.toDouble(n + i), 2e-3);
			assertEquals(leaky(atSwitch, -2.0, tau.toDouble(i), 2500), samples.toDouble(2 * n + i), 2e-3);
			assertEquals(leaky(atSwitch, -2.0, tau.toDouble(i), steps - k), system.x.toDouble(i), 2e-3);
		}

		runner.destroy();
	}

	/**
	 * After {@code run(a)} and {@code run(b)} the device counter equals {@code a + b}, and a
	 * driven system that reads time as {@code step * dt} from the counter matches its closed
	 * form at every step.
	 */
	@Test(timeout = 300000)
	public void stepCounter() {
		int a = 300;
		int b = 700;
		double frequency = 2 * Math.PI;
		PackedCollection step = new PackedCollection(1);
		PackedCollection y = new PackedCollection(1);

		Temporal driven = () -> a("driven", cp(y), cos(cp(step).multiply(DT * frequency)));
		SimulationRunner runner = new SimulationRunner(driven, DT, step);
		SimulationRunner.Recording recording = runner.record(cp(y), 1, a + b);

		runner.run(a);
		runner.run(b);

		assertEquals(a + b, runner.getStepCount());
		assertEquals(a + b, cp(runner.getStep()).evaluate().toDouble(0), 0.0);
		assertEquals((a + b) * DT, runner.time().evaluate().toDouble(0), 1e-6);

		PackedCollection samples = recording.getBuffer();
		for (int j = 0; j < a + b; j++) {
			assertEquals(Math.cos(frequency * j * DT), samples.toDouble(j), 1e-4);
		}

		runner.destroy();
		assertFalse(step.isDestroyed());
	}

	/**
	 * {@code destroy()} releases the counter and recording buffers the runner allocated, and a
	 * run that would exceed a recording's capacity fails before any step runs.
	 */
	@Test(timeout = 120000)
	public void lifecycle() {
		PackedCollection tau = timeConstants(2);
		LeakyIntegrator system = new LeakyIntegrator(tau,
				new PackedCollection(2).fill(0.5), new PackedCollection(2).fill(1.0));
		SimulationRunner runner = new SimulationRunner(system, DT);
		SimulationRunner.Recording recording = runner.record(cp(system.x), 10, 5);

		try {
			runner.run(60);
			Assert.fail("A run exceeding the recording capacity should fail");
		} catch (IllegalStateException expected) {
			// expected
		}

		assertEquals(0, runner.getStepCount());
		assertEquals(0, recording.getSampleCount());
		assertEquals(0.5, system.x.toDouble(0), 0.0);

		runner.run(50);
		assertEquals(5, recording.getSampleCount());

		try {
			runner.run(10);
			Assert.fail("A run exceeding the remaining recording capacity should fail");
		} catch (IllegalStateException expected) {
			// expected
		}

		assertEquals(50, runner.getStepCount());
		runner.run(9);
		assertEquals(59, runner.getStepCount());

		PackedCollection step = runner.getStep();
		PackedCollection buffer = recording.getBuffer();
		runner.destroy();
		assertTrue(step.isDestroyed());
		assertTrue(buffer.isDestroyed());
	}

	/**
	 * A system with a counted setup and a recording and listener on every step, used to check
	 * that every way of running the runner keeps its bookkeeping consistent.
	 */
	private static class CountedSystem implements Temporal, Setup {
		/** The state, incremented by one per step. */
		private final PackedCollection x = new PackedCollection(1);
		/** The number of times setup ran. */
		private final AtomicInteger setups = new AtomicInteger();

		@Override
		public Supplier<Runnable> setup() {
			OperationList setup = new OperationList("Counted Setup");
			setup.add(() -> setups::incrementAndGet);
			setup.add(a(cp(x), c(0.0)));
			return setup;
		}

		@Override
		public Supplier<Runnable> tick() {
			return a(cp(x), cp(x).add(1.0));
		}
	}

	/**
	 * Interleaving {@code run} with the inherited {@code getContinue()}, {@code get()} and
	 * {@code tick()} keeps the device counter, host count, recording and listener calls equal to
	 * the number of steps, with setup run once; composing the runner in another
	 * {@link TemporalRunner} does not re-run setup.
	 */
	@Test(timeout = 120000)
	public void inheritedEntryPoints() {
		CountedSystem system = new CountedSystem();
		SimulationRunner runner = new SimulationRunner(system, DT);
		SimulationRunner.Recording recording = runner.record(cp(system.x), 1, 32);
		AtomicInteger calls = new AtomicInteger();
		runner.addBoundaryListener(1, step -> calls.incrementAndGet());

		runner.run(10);
		runner.getContinue().run();
		runner.get().run();
		runner.tick().get().run();
		assertCounts(runner, system, recording, calls, 13);

		runner.setup().get().run();
		assertCounts(runner, system, recording, calls, 13);

		TemporalRunner composed = new TemporalRunner(runner, 4);
		composed.get().run();
		composed.getContinue().run();
		assertCounts(runner, system, recording, calls, 21);

		for (int j = 0; j < 21; j++) {
			assertEquals(j + 1, recording.getBuffer().toDouble(j), 0.0);
		}

		runner.destroy();
	}

	/**
	 * {@code reset()} returns the counter, host count and recording to zero and re-runs setup on
	 * the next run, without recompiling.
	 */
	@Test(timeout = 120000)
	public void reset() {
		CountedSystem system = new CountedSystem();
		SimulationRunner runner = new SimulationRunner(system, DT);
		SimulationRunner.Recording recording = runner.record(cp(system.x), 1, 8);
		AtomicInteger calls = new AtomicInteger();
		runner.addBoundaryListener(1, step -> calls.incrementAndGet());

		runner.run(5);
		runner.reset();
		calls.set(0);
		runner.run(3);

		assertEquals(2, system.setups.get());
		assertEquals(3, runner.getStepCount());
		assertEquals(3, recording.getSampleCount());
		assertEquals(3, cp(runner.getStep()).evaluate().toDouble(0), 0.0);
		assertEquals(3, system.x.toDouble(0), 0.0);
		assertEquals(3, calls.get());
		runner.destroy();
	}

	/**
	 * Invalid arguments are rejected with {@link IllegalArgumentException} before they can affect
	 * the simulation: a counter that is not a single element, a negative step count, and a
	 * non-positive interval or capacity for an input, listener or recording.
	 */
	@Test(timeout = 120000)
	public void invalidArguments() {
		CountedSystem system = new CountedSystem();
		assertThrowsIllegalArgument(() -> new SimulationRunner(system, DT, new PackedCollection(2)));
		assertThrowsIllegalArgument(() -> new SimulationRunner(system, DT, (PackedCollection) null));

		SimulationRunner runner = new SimulationRunner(system, DT);
		assertThrowsIllegalArgument(() -> runner.run(-1));
		assertThrowsIllegalArgument(() -> runner.addInput(0, a(cp(system.x), c(5.0))));
		assertThrowsIllegalArgument(() -> runner.addBoundaryListener(-3, step -> { }));
		assertThrowsIllegalArgument(() -> runner.record(cp(system.x), 0, 4));
		assertThrowsIllegalArgument(() -> runner.record(cp(system.x), 1, 0));

		runner.run(4);
		assertEquals(4, runner.getStepCount());
		assertEquals(4, system.x.toDouble(0), 0.0);
		assertEquals(1, system.setups.get());
		runner.destroy();
	}

	/**
	 * {@code run(0)} runs the pending setup and the counter reset but no step, and runs no
	 * listener, input or recording; the accessors report the configuration the runner was built
	 * with, including the profile given to the profiling constructor.
	 */
	@Test(timeout = 120000)
	public void zeroStepsAndAccessors() {
		CountedSystem system = new CountedSystem();
		PackedCollection step = new PackedCollection(1).fill(7.0);
		OperationProfile profile = new OperationProfile("simulationRunnerTest");
		SimulationRunner runner = new SimulationRunner(system, DT, step, profile);
		SimulationRunner.Recording recording = runner.record(cp(system.x), 3, 5);
		AtomicInteger calls = new AtomicInteger();
		runner.addBoundaryListener(1, s -> calls.incrementAndGet());

		Assert.assertSame(system, runner.getSystem());
		assertEquals(DT, runner.getTimeStep(), 0.0);
		Assert.assertSame(step, runner.getStep());
		Assert.assertSame(profile, runner.getProfile());
		assertEquals(3, recording.getInterval());
		assertEquals(5, recording.getCapacity());
		assertEquals(1, recording.getWidth());

		runner.run(0);
		assertEquals(0, runner.getStepCount());
		assertEquals(0.0, step.toDouble(0), 0.0);
		assertEquals(1, system.setups.get());
		assertEquals(0, calls.get());
		assertEquals(0, recording.getSampleCount());

		runner.run(7);
		assertEquals(7, step.toDouble(0), 0.0);
		assertEquals(7, calls.get());
		assertEquals(2, recording.getSampleCount());
		assertEquals(3, recording.getBuffer().toDouble(0), 0.0);
		assertEquals(6, recording.getBuffer().toDouble(1), 0.0);
		assertEquals(1, system.setups.get());

		runner.destroy();
		assertFalse(step.isDestroyed());
	}

	/**
	 * Asserts that {@code action} throws {@link IllegalArgumentException}.
	 *
	 * @param action the action expected to throw
	 */
	private static void assertThrowsIllegalArgument(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected an IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	/**
	 * Asserts that every count the runner keeps equals {@code steps} and setup ran once.
	 *
	 * @param runner    the runner
	 * @param system    the system
	 * @param recording the per-step recording
	 * @param calls     the per-step listener call count
	 * @param steps     the expected number of steps
	 */
	private void assertCounts(SimulationRunner runner, CountedSystem system,
							  SimulationRunner.Recording recording, AtomicInteger calls, int steps) {
		assertEquals(steps, runner.getStepCount());
		assertEquals(steps, cp(runner.getStep()).evaluate().toDouble(0), 0.0);
		assertEquals(steps, system.x.toDouble(0), 0.0);
		assertEquals(steps, recording.getSampleCount());
		assertEquals(steps, calls.get());
		assertEquals(1, system.setups.get());
	}
}
