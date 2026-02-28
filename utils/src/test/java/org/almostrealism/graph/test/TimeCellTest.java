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
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

public class TimeCellTest extends TestSuiteBase {
	@Test(timeout = 10000)
	public void timeCell() {
		TimeCell cell = new TimeCell(null, c(44100));
		cell.setup().get().run();

		Runnable tick = cell.tick().get();
		for (int i = 0; i < 100; i++) {
			tick.run();
		}

		assertEquals(100.0, cell.frame().evaluate());
	}

	/**
	 * Tests the TimeCell reset functionality with multiple reset slots.
	 * This verifies the fix for the reset slot indexing bug where the
	 * condition checked resets[1] instead of resets[i] in the loop.
	 */
	@Test(timeout = 10000)
	public void multipleResets() {
		// Create a TimeCell with 5 reset slots
		TimeCell cell = new TimeCell(5);

		// Schedule resets at frames 10, 20, and 30
		cell.setReset(0, 10);
		cell.setReset(1, 20);
		cell.setReset(2, 30);
		// Slots 3 and 4 remain disabled (-1)

		cell.setup().get().run();
		Runnable tick = cell.tick().get();

		// Advance to frame 9
		for (int i = 0; i < 9; i++) {
			tick.run();
		}
		assertEquals(9.0, cell.frame().evaluate());

		// Frame 10 should trigger reset to 0
		tick.run();
		assertEquals(0.0, cell.frame().evaluate());
	}

	@Test(timeout = 10000)
	public void fmod() {
		Pair time = new Pair();
		Producer<PackedCollection> loopDuration = c(2.0);

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
