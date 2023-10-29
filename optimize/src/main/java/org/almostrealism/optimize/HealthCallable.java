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
import org.almostrealism.time.Temporal;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class HealthCallable<T extends Temporal, S extends HealthScore> implements Callable<S>, CodeFeatures {
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
			S health = null;

			try {
				this.health.setTarget(target.get());
				health = this.health.computeHealth();
				scoring.pushScore(health);

				if (healthListener != null) {
					healthListener.accept(health);
				}
			} catch (Exception e) {
				e.printStackTrace();
				throw e;
			} finally {
				this.health.reset();
				this.cleanup.run();
			}

			return health;
		};

		if (heap != null) {
			call = heap.wrap(call);
		}

		if (computeRequirements == null || computeRequirements.length <= 0) {
			return call.call();
		} else {
			return cc(call, computeRequirements);
		}
	}

	public static void setComputeRequirements(ComputeRequirement... expectations) {
		computeRequirements = expectations;
	}
}
