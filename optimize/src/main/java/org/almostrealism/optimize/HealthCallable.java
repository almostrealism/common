/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.optimize;

import io.almostrealism.code.ComputeRequirement;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.time.Temporal;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class HealthCallable<T extends Temporal, S extends HealthScore> implements Callable<S>, CodeFeatures, ConsoleFeatures {
	public static Console console = Console.root().child();
	public static boolean enableVerbose = false;

	public static ComputeRequirement computeRequirements[] = {};

	private HealthComputation<T, S> health;
	private Supplier<T> target;
	private Consumer<S> healthListener;
	private HealthScoring scoring;
	private Runnable cleanup;

	private Heap heap;

	public HealthCallable(Supplier<T> target, HealthComputation health, HealthScoring scoring, Consumer<S> healthListener, Runnable cleanup) {
		this.health = health;
		this.target = target;
		this.scoring = scoring;
		this.healthListener = healthListener;
		this.cleanup = cleanup;
	}

	public Heap getHeap() { return heap; }

	public void setHeap(Heap heap) { this.heap = heap; }

	@Override
	public S call() throws Exception {
		Callable<S> call = () -> {
			S healthResult = null;

			try {
				this.health.setTarget(target.get());
				if (enableVerbose) log("Running " + this.health.getClass().getSimpleName());
				healthResult = this.health.computeHealth();
				if (enableVerbose) log("Completed " + this.health.getClass().getSimpleName());
				scoring.pushScore(healthResult);

				if (healthListener != null) {
					healthListener.accept(healthResult);
				}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			} finally {
				this.health.reset();
				this.cleanup.run();
			}

			return healthResult;
		};

		if (heap != null) {
			call = heap.wrap(call);
		}

		if (enableVerbose) {
			log(computeRequirements == null ?
					"No compute requirements" : "Compute requirements: " + Arrays.toString(computeRequirements));
		}

		if (computeRequirements == null || computeRequirements.length <= 0) {
			return call.call();
		} else {
			return cc(call, computeRequirements);
		}
	}

	@Override
	public Console console() { return console; }

	public static void setComputeRequirements(ComputeRequirement... expectations) {
		computeRequirements = expectations;
	}
}
