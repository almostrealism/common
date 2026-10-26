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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Measures the per-step overhead of {@link SimulationRunner} for steps of three sizes, each
 * with and without one recording, over 10,000 steps after warm-up. The figures are logged as
 * {@code perStepMicros=} values. Only agreement with the closed form is asserted, never a time
 * threshold, so the test cannot flake on a slow node.
 */
public class SimulationRunnerDispatchTest extends TestSuiteBase {
	/** Steps run before timing. */
	private static final int WARMUP = 500;

	/** Steps timed. */
	private static final int STEPS = 10000;

	/** Per-step decay factor of the element-wise systems. */
	private static final double DECAY = 0.9999;

	/**
	 * Runs {@code system} for {@link #WARMUP} then {@link #STEPS} steps, with or without a
	 * recording of {@code state}, logs the timed per-step cost, and returns the runner.
	 *
	 * @param label  the case label for the log
	 * @param system the system to step
	 * @param state  the state to record
	 * @param record whether to add a recording
	 * @return the runner after the run, for the caller to check and destroy
	 */
	private SimulationRunner measure(String label, Temporal system, PackedCollection state, boolean record) {
		SimulationRunner runner = new SimulationRunner(system, 1e-4);
		if (record) runner.record(cp(state), 100, (WARMUP + STEPS) / 100);

		runner.run(WARMUP);
		long start = System.nanoTime();
		runner.run(STEPS);
		double micros = (System.nanoTime() - start) / 1000.0 / STEPS;

		log(label + " recorder=" + record + " perStepMicros=" + micros);
		return runner;
	}

	/**
	 * Measures an element-wise decay step {@code x <- x * DECAY} of the given size, with and
	 * without a recording, and checks the closed form {@code x0 * DECAY^n}.
	 *
	 * @param label the case label
	 * @param size  the number of elements
	 */
	private void assertElementWiseClosedForm(String label, int size) {
		for (boolean record : new boolean[] { false, true }) {
			PackedCollection x = new PackedCollection(size).fill(1.0);
			Temporal system = () -> a(label, cp(x), cp(x).multiply(DECAY));

			SimulationRunner runner = measure(label, system, x, record);
			double expected = Math.pow(DECAY, WARMUP + STEPS);
			assertEquals(expected, x.toDouble(0), 1e-3);
			assertEquals(expected, x.toDouble(size - 1), 1e-3);
			runner.destroy();
			x.destroy();
		}
	}

	/** A one-element step. */
	@Test(timeout = 600000)
	public void singleElement() {
		assertElementWiseClosedForm("singleElement", 1);
	}

	/** A 100,000-element element-wise step. */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void elementWiseStep() {
		assertElementWiseClosedForm("elementWise", 100000);
	}

	/**
	 * A dense 1,024 x 1,024 {@code matmul} step with copy-back. The matrix is
	 * {@code M = a I + (b / size) J}, where {@code J} is all ones, stored densely so that the
	 * product is a full dense matmul. Its mean component scales by {@code a + b} per step and the
	 * rest by {@code a}, so after {@code n} steps
	 * {@code x = a^n (x0 - mean) + (a + b)^n mean}.
	 */
	@Test(timeout = 1800000)
	@TestDepth(2)
	public void denseMatmulStep() {
		if (skipLongTests) return;

		int size = 1024;
		double a = 0.9999;
		double b = -0.00005;
		PackedCollection matrix = identity(size).multiply(a).add(b / size).evaluate();

		for (boolean record : new boolean[] { false, true }) {
			PackedCollection state = integers(0, size).divide(size).evaluate();
			PackedCollection next = new PackedCollection(size);
			double mean = (size - 1) / (2.0 * size);

			Temporal system = () -> {
				OperationList tick = new OperationList("Dense Matmul Step");
				tick.add(a("matmul", cp(next), matmul(cp(matrix), cp(state))));
				tick.add(a("copyBack", cp(state), cp(next)));
				return tick;
			};

			SimulationRunner runner = measure("denseMatmul", system, state, record);
			int n = WARMUP + STEPS;
			double local = Math.pow(a, n);
			double global = Math.pow(a + b, n) * mean;
			assertEquals(local * (0.0 - mean) + global, state.toDouble(0), 5e-3);
			assertEquals(local * (512.0 / size - mean) + global, state.toDouble(512), 5e-3);
			assertEquals(local * ((size - 1.0) / size - mean) + global, state.toDouble(size - 1), 5e-3);
			runner.destroy();
			state.destroy();
			next.destroy();
		}

		matrix.destroy();
	}
}
