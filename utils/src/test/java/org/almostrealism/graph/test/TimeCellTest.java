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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
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

		assertEquals(100.0, cell.frameScalar().get().evaluate());
	}

	@Test
	public void fmod() {
		Scalar time = new Scalar();
		Producer<Scalar> loopDuration = scalar(2.0);

		Producer<Scalar> left = l(() -> new Provider<>(time));
		left = scalarGreaterThan(loopDuration, scalar(0.0),
				scalarMod(scalarAdd(left, ScalarFeatures.of(new Scalar(1.0))), loopDuration),
				scalarAdd(left, ScalarFeatures.of(new Scalar(1.0))), false);

		Producer<Scalar> right = r(() -> new Provider<>(time));
		right = scalarAdd(right, ScalarFeatures.of(1.0));

		Runnable r = new Assignment<>(2, () -> new Provider<>(time), pair(left, right)).get();

		verboseLog(() -> {
			for (int i = 0; i < 5; i++) {
				r.run();
			}
		});

		assertEquals(1.0, time);
	}
}
