/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.graph.model.test;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

public class PoolTests extends TestSuiteBase implements KernelAssertions {

	@Test(timeout = 60000)
	public void pool2dSquareAndOptimize() {
		pool2dSquareOptimize();
		pool2dSquare();
		pool2dSquare();
	}

	@Test(timeout = 60000)
	public void pool2d() {
		int r = 12;
		int c = 16;
		int d = 3;
		int w = 2;
		pool(r, c, d, w, false);
	}

	@Test(timeout = 60000)
	public void pool2dSquare() {
		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;
		pool(r, c, d, w, false);
	}

	@Test(timeout = 60000)
	public void pool2dSquareSteps() {
		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;
		pool(r, c, d, w, true);
	}

	@Test(timeout = 60000)
	public void pool2dSquareOptimize() {
		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;

		PackedCollection input = tensor(shape(r, c, d)).pack();
		input.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection>> pool =
				() -> (Producer) Process.optimized(cp(input)
						.enumerate(2, 1)
						.enumerate(2, w)
						.enumerate(2, w)
						.traverse(3)
						.max()
						.reshape(r / w, c / 2, d));

		kernelTest(pool, output -> pool2d(r, c, d, w, input, output), true, false, false);
	}

	public void pool(int r, int c, int d, int w, boolean kernel) {
		PackedCollection input = new PackedCollection(shape(r, c, d)).randFill();

		Supplier<CollectionProducer> pool =
				() -> cp(input)
						.enumerate(2, 1)
						.enumerate(2, w)
						.enumerate(2, w)
						.traverse(3)
						.max()
						.reshape(r / w, c / 2, d);

		if (kernel) {
			kernelTest(pool, output -> pool2d(r, c, d, w, input, output), true, false, false);
		} else {
			kernelTest(pool, output -> pool2d(r, c, d, w, input, output), false, false, true);
		}
	}
}
