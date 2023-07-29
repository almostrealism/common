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

package org.almostrealism.hardware.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.test.KernelAssertions;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class KernelOperationTests implements TestFeatures, KernelAssertions {
	public static boolean enableAssignment = false;

	@Test
	public void assignment() {
		PackedCollection<?> x = new PackedCollection<>(shape(10)).traverse();
		PackedCollection<?> a = tensor(shape(10)).pack().traverse();
		PackedCollection<?> b = tensor(shape(10)).pack().traverse();

		boolean enableRelativeAssignment = Assignment.enableRelative;

		try {
			Assignment.enableRelative = false;

			HardwareOperator.verboseLog(() -> {
				OperationList op = new OperationList();
				op.add(a(1, traverse(1, p(x)), add(traverse(1, p(a)), traverse(1, p(b)))));
				op.get().run();
			});
		} finally {
			Assignment.enableRelative = enableRelativeAssignment;
		}

		for (int i = 0; i < x.getShape().length(0); i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), x.toDouble(i));
		}
	}

	@Test
	public void doubleAssignment() {
		PackedCollection<?> x = new PackedCollection<>(shape(10)).traverse();
		PackedCollection<?> y = new PackedCollection<>(shape(10)).traverse();
		PackedCollection<?> a = tensor(shape(10)).pack().traverse();
		PackedCollection<?> b = tensor(shape(10)).pack().traverse();

		boolean enableRelativeAssignment = Assignment.enableRelative;

		try {
			Assignment.enableRelative = false;

			HardwareOperator.verboseLog(() -> {
				OperationList op = new OperationList();
				op.add(a(1, traverse(1, p(x)), add(traverse(1, p(a)), traverse(1, p(b)))));
				op.add(a(1, traverse(1, p(y)), multiply(traverse(1, p(a)), traverse(1, p(b)))));

				Assert.assertEquals(10, op.getCount());
				op.get().run();
			});
		} finally {
			Assignment.enableRelative = enableRelativeAssignment;
		}

		for (int i = 0; i < x.getShape().length(0); i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), x.toDouble(i));
			assertEquals(a.toDouble(i) * b.toDouble(i), y.toDouble(i));
		}
	}

	// TODO  This should notice that the two assignments have different counts,
	// TODO  breaking them up into two stages with different parallelism
	@Test
	public void doubleAssignmentMultipleCount() {
		PackedCollection<?> x = new PackedCollection<>(shape(10)).traverse();
		PackedCollection<?> y = new PackedCollection<>(shape(6)).traverse();
		PackedCollection<?> a = tensor(shape(10)).pack().traverse();
		PackedCollection<?> b = tensor(shape(6)).pack().traverse();

		boolean enableRelativeAssignment = Assignment.enableRelative;

		try {
			Assignment.enableRelative = false;

			HardwareOperator.verboseLog(() -> {
				OperationList op = new OperationList();
				op.add(a(1, traverse(1, p(x)), add(traverse(1, p(a)), traverse(1, p(a)))));
				op.add(a(1, traverse(1, p(y)), multiply(traverse(1, p(b)), traverse(1, p(b)))));

				Assert.assertEquals(1, op.getCount());
				op.optimize().get().run();
			});
		} finally {
			Assignment.enableRelative = enableRelativeAssignment;
		}

		for (int i = 0; i < x.getShape().length(0); i++) {
			assertEquals(a.toDouble(i) + a.toDouble(i), x.toDouble(i));
		}

		for (int i = 0; i < y.getShape().length(0); i++) {
			assertEquals(b.toDouble(i) * b.toDouble(i), y.toDouble(i));
		}
	}

	// TODO  This should notice that 10 multiplications can be done in parallel
	// TODO  even though the final output is only one value, breaking it up into
	// TODO  two stages of 10 parallel multiplications followed by one sum.
	@Test
	public void doubleAssignmentReduceCount() {
		PackedCollection<?> x = new PackedCollection<>(shape(1)).traverse();
		PackedCollection<?> a = tensor(shape(10)).pack().traverse();
		PackedCollection<?> b = tensor(shape(10)).pack().traverse();

		boolean enableRelativeAssignment = Assignment.enableRelative;

		try {
			Assignment.enableRelative = false;

			HardwareOperator.verboseLog(() -> {
				OperationList op = new OperationList();
				op.add(a(1, traverse(1, p(x)), multiply(traverse(1, p(a)), traverse(1, p(b))).traverse(0).sum()));

				Runnable o = op.optimize().get();
				Assert.assertEquals(2, ((AcceleratedComputationOperation) o).getInputs().size());

				Runnable r = op.get();
				Assert.assertEquals(3, ((AcceleratedComputationOperation) r).getInputs().size());

				o.run();
			});
		} finally {
			Assignment.enableRelative = enableRelativeAssignment;
		}

		double expected = 0;
		for (int i = 0; i < a.getShape().length(0); i++) {
			expected += a.toDouble(i) * b.toDouble(i);
		}

		assertEquals(expected, x.toDouble(0));
	}

	// @Test
	public void kernelList() {
		PackedCollection<?> timeline = new PackedCollection<>(shape(10), 1);
		IntStream.range(0, 10).forEach(i -> timeline.set(i, i + 1));

		PackedCollection<?> params = new PackedCollection<>(shape(5), 1);
		IntStream.range(0, 5).forEach(i -> params.set(i, i + 1));

		PackedCollection<?> destination = new PackedCollection<>(shape(5, 10));

		KernelizedEvaluable<PackedCollection<?>> ev = c(p(params)).traverseEach().map(v -> v.multiply(traverseEach(p(timeline)))).get();
		ev.into(destination.traverseEach()).evaluate();
		System.out.println(Arrays.toString(destination.toArray(20, 10)));
	}

	@Test
	public void enumerateRepeatMapReduce() {
		int r = 10;
		int c = 10;
		int w = 3;
		int s = 1;
		int pad = 2;

		int n = 4; // 8;

		PackedCollection<?> input = tensor(shape(r, c)).pack();
		PackedCollection<?> filter = tensor(shape(n, w, w)).pack();

		boolean enableRelativeAssignment = Assignment.enableRelative;

		try {
			Assignment.enableRelative = false;

			HardwareOperator.verboseLog(() -> {
				PackedCollection<?> output = new PackedCollection<>(shape(8, 8, 4, 1));

				CollectionProducer<PackedCollection<?>> conv = c(p(input))
						.enumerate(1, w, s)
						.enumerate(1, w, s)
						.traverse(2)
						.expand(n, v -> v.repeat(n).multiply(p(filter)))
						.traverse()
						.reduce(v -> v.sum());
				System.out.println(conv.getShape());

				OperationList op = new OperationList();
				op.add(a(1, traverse(3, p(output)), conv));
				op.get().run();

				output = output.reshape(shape(8, 8, 4));

				for (int filterIndex = 0; filterIndex < n; filterIndex++) {
					for (int i = 0; i < r - pad; i++) {
						for (int j = 0; j < c - pad; j++) {
							double expected = 0;

							for (int k = 0; k < w; k++) {
								for (int l = 0; l < w; l++) {
									expected += input.toDouble(input.getShape().index(i + k, j + l)) * filter.toDouble(filter.getShape().index(filterIndex, k, l));
								}
							}

							double actual = output.toDouble(output.getShape().index(i, j, filterIndex));

							System.out.println("PackedCollectionMapTests: " + expected + " vs " + actual);
							Assert.assertEquals(expected, actual, 0.0001);
						}
					}
				}
			});
		} finally {
			Assignment.enableRelative = enableRelativeAssignment;
		}
	}

	@Test
	public void pool2d() {
		if (TestSettings.skipLongTests) return;

		int r = 12;
		int c = 16;
		int d = 3;
		int w = 2;
		pool(r, c, d, w);
	}

	@Test
	public void pool2dSquare() {
		if (TestSettings.skipLongTests) return;

		int r = 8;
		int c = 8;
		int d = 8;
		int w = 2;
		pool(r, c, d, w);
	}

	public void pool(int r, int c, int d, int w) {
		PackedCollection<?> input = tensor(shape(r, c, d)).pack();
		input.fill(pos -> Math.random());

		CollectionProducer<PackedCollection<?>> pool =
				c(p(input)).enumerate(1, w)
						.enumerate(1, w)
						.traverse(2)
						.map(shape(d, 1), v ->
								enumerate(shape(1, 1, w, w, 1), v)
										.traverse(1).reduce(slice ->
												max(slice)));
		System.out.println("KernelOperationTests: Computation shape = " + pool.getShape());

		PackedCollection<?> output = new PackedCollection<>(shape(r / w, c / w, d)).traverse(2);

		if (enableAssignment) {
			OperationList op = new OperationList();
			op.add(a(1, traverse(3, p(output)), pool));
			op.get().run();
		} else {
			OperationList op = new OperationList();
			op.add((Producer<? extends MemoryData>) pool, output);
			op.get().run();
		}

		pool2d(r, c, d, w, input, output);
	}
}
