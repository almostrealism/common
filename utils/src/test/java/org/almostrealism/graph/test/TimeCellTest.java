/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.graph.test;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class TimeCellTest implements TestFeatures {
	@Test
	public void timeCell() {
		TimeCell cell = new TimeCell(null, c(44100));
		cell.setup().get().run();

		Runnable tick = cell.tick().get();
		for (int i = 0; i < 100; i++) {
			tick.run();
		}

		assertEquals(100.0, cell.frame().evaluate());
	}

	@Test
	public void fmod() {
		Pair<?> time = new Pair<>();
		Producer<PackedCollection<?>> loopDuration = c(2.0);

		CollectionProducer left = l(cp(time));
		left = greaterThan(loopDuration, c(0.0),
				mod(add(left, c(1.0)), loopDuration),
				add(left, c(1.0)), false);

		CollectionProducer right = r(cp(time));
		right = add(right, c(1.0));

		Runnable r = new Assignment<>(2, cp(time), pair(left, right)).get();

		verboseLog(() -> {
			for (int i = 0; i < 5; i++) {
				r.run();
			}
		});

		assertEquals(1.0, time.toDouble(0));
	}
}
