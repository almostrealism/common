/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.function.Supplier;

public class PoolTests implements TestFeatures, KernelAssertions {

	@Test
	public void pool2d() {
		int r = 12;
		int c = 16;
		int d = 3;
		int w = 2;
		pool(r, c, d, w, false);
	}

	@Test
	public void pool2dSquare() {
		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;
		pool(r, c, d, w, false);
	}

	@Test
	public void pool2dSquareSteps() {
		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;
		pool(r, c, d, w, true);
	}

	@Test
	public void pool2dSquareOptimize() {
		if (skipLongTests) return;

		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;

		PackedCollection<?> input = tensor(shape(r, c, d)).pack();
		input.fill(pos -> Math.random());

		Supplier<Producer<PackedCollection<?>>> pool =
				() -> (Producer) c(p(input)).enumerate(1, w)
						.enumerate(1, w)
						.traverse(2)
						.map(shape(d, 1), v ->
								enumerate(shape(1, 1, w, w, 1), v)
										.traverse(1).reduce(slice ->
												max(slice))).optimize();

		kernelTest(pool, output -> pool2d(r, c, d, w, input, output), true, false, false);
	}

	public void pool(int r, int c, int d, int w, boolean steps) {
		if (skipLongTests) return;

		PackedCollection<?> input = tensor(shape(r, c, d)).pack();
		input.fill(pos -> Math.random());

		{
			CollectionProducer<?> pt = c(p(input));
			System.out.println("1: " + pt.getShape() + " - " + pt.getShape().getCount() +
					"x" + pt.getShape().getSize());
			pt = pt.enumerate(1, w);
			System.out.println("2: " + pt.getShape() + " - " + pt.getShape().getCount() +
					"x" + pt.getShape().getSize());
			pt = pt.enumerate(1, w);
			System.out.println("3: " + pt.getShape() + " - " + pt.getShape().getCount() +
					"x" + pt.getShape().getSize());
			pt = pt.traverse(2);
			System.out.println("4: " + pt.getShape() + " - " + pt.getShape().getCount() +
					"x" + pt.getShape().getSize());
			pt = pt.map(shape(d, 1), v ->
					enumerate(shape(1, 1, w, w, 1), v)
							.traverse(1).reduce(slice ->
									max(slice)));
			System.out.println("5: " + pt.getShape() + " - " + pt.getShape().getCount() +
					"x" + pt.getShape().getSize());
		}

		if (steps) {
			Supplier<Producer<PackedCollection<?>>> pool = () -> () -> args -> {
				long start = System.currentTimeMillis();
				Evaluable<PackedCollection<?>> step1 = c(p(input)).enumerate(1, w)
						.enumerate(1, w).get();
				System.out.println("Step 1 Compile: " + (System.currentTimeMillis() - start) / 1000 + "s");

				start = System.currentTimeMillis();
				PackedCollection<?> step1Out = step1.evaluate();
				System.out.println("Step 1 Evaluate: " + (System.currentTimeMillis() - start) / 1000 + "s");

				start = System.currentTimeMillis();
				Evaluable<PackedCollection<?>> step2 =
						c(p(step1Out)).traverse(2).map(shape(d, 1), v ->
						enumerate(shape(1, 1, w, w, 1), v)
								.traverse(1).reduce(slice ->
										max(slice))).get();
				System.out.println("Step 2 Compile: " + (System.currentTimeMillis() - start) / 1000 + "s");

				start = System.currentTimeMillis();
				PackedCollection<?> step2Out = step2.evaluate();
				System.out.println("Step 2 Evaluate: " + (System.currentTimeMillis() - start) / 1000 + "s");
				return step2Out;
			};

			kernelTest(pool, output -> pool2d(r, c, d, w, input, output), true, false, false);
		} else {
			Supplier<CollectionProducer<PackedCollection<?>>> pool =
					() -> c(p(input)).enumerate(1, w)
							.enumerate(1, w)
							.traverse(2)
							.map(shape(d, 1), v ->
									enumerate(shape(1, 1, w, w, 1), v)
											.traverse(1).reduce(slice ->
													max(slice)));

			kernelTest(pool, output -> pool2d(r, c, d, w, input, output), false, false, true);
		}
	}
}
