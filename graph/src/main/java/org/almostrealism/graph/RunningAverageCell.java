/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph;

import org.almostrealism.algebra.Scalar;
import io.almostrealism.relation.Producer;

import java.util.function.Supplier;

public class RunningAverageCell extends ScalarCachedStateCell {
	private double total;
	private int pushes;

	@Override
	public Supplier<Runnable> push(Producer<Scalar> protein) {
		return () -> () -> {
			this.total = total + protein.get().evaluate().getValue();
			this.pushes++;

			// Update the cached value to the current
			// running average of values received
			setCachedValue(new Scalar(this.total / pushes));
		};
	}

	@Override
	public Supplier<Runnable> tick() {
		Supplier<Runnable> tick = super.tick();
		
		return () -> () -> {
			this.total = 0;
			this.pushes = 0;
			tick.get().run();
		};
	}
}
