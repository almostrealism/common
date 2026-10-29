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

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Profiles the per-step cost of {@link SimulationRunner} for a one-element decay step, the
 * system {@link SimulationRunnerDispatchTest#singleElement()} measures. The operation profile of
 * the step is saved to {@code results/simulationStep.xml}, and the per-step time is logged with
 * the number of kernel dispatches and command-buffer commits each step issued on Metal (both are
 * zero on other backends), so the time a step spends outside its operations can be read against
 * the operations themselves.
 */
public class SimulationStepProfileTest extends TestSuiteBase {
	/** Steps run before profiling. */
	private static final int WARMUP = 500;

	/** Steps profiled. */
	private static final int STEPS = 10000;

	/** Per-step decay factor. */
	private static final double DECAY = 0.9999;

	/**
	 * Runs the decay step with a profile assigned, logs the per-step cost and the dispatches and
	 * commits per step, saves the profile and checks the closed form {@code DECAY^n}.
	 *
	 * @throws Exception if the profile cannot be saved
	 */
	@Test(timeout = 300000)
	public void profileSimulationStep() throws Exception {
		PackedCollection x = new PackedCollection(1).fill(1.0);
		Temporal system = () -> a("decay", cp(x), cp(x).multiply(DECAY));

		OperationProfileNode profile = new OperationProfileNode("simulationStep");
		SimulationRunner runner = new SimulationRunner(system, 1e-4, new PackedCollection(1), profile);

		try {
			runner.run(WARMUP);

			long dispatches = MetalCommandRunner.totalDispatchCount.get();
			long commits = MetalCommandRunner.totalCommitCount.get();
			long start = System.nanoTime();
			runner.run(STEPS);
			double micros = (System.nanoTime() - start) / 1000.0 / STEPS;

			log("perStepMicros=" + micros
					+ " dispatchesPerStep=" + (MetalCommandRunner.totalDispatchCount.get() - dispatches) / (double) STEPS
					+ " commitsPerStep=" + (MetalCommandRunner.totalCommitCount.get() - commits) / (double) STEPS);

			assertEquals(Math.pow(DECAY, WARMUP + STEPS), x.toDouble(0), 1e-3);
		} finally {
			runner.destroy();
			runner.getStep().destroy();
			x.destroy();
			profile.save("results/simulationStep.xml");
		}
	}
}
