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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Operation;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class CollectionComputationTests implements TestFeatures {

	@Test
	public void evaluateIntegers() {
		verboseLog(() -> {
			PackedCollection<?> result = integers(10, 100).get().evaluate();
			assertEquals(14, result.toDouble(4));
		});
	}

	@Test
	public void divideIntegers() {
		PackedCollection<?> result = divide(c(6, 18, 48), integers().add(c(2))).get().evaluate();

		assertEquals(3.0, result.toDouble(0));
		assertEquals(6.0, result.toDouble(1));
		assertEquals(12.0, result.toDouble(2));
	}

	@Test
	public void index() {
		PackedCollection<?> x = pack(1, 1, 1, 2, 2, 2);
		PackedCollection<?> y = pack(0, 1, 2, 0, 1, 2);
		PackedCollection<?> result = new PackedCollection<>(shape(6));

		verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> value = index(shape(3, 5), p(x), p(y));
			value.into(result).evaluate();
		});

		System.out.println(result.toArrayString());
		assertEquals(5.0, result.toDouble(0));
		assertEquals(6.0, result.toDouble(1));
		assertEquals(7.0, result.toDouble(2));
		assertEquals(10.0, result.toDouble(3));
		assertEquals(11.0, result.toDouble(4));
		assertEquals(12.0, result.toDouble(5));
	}

	@Test
	public void integersIndex() {
		int len = 10000;
		PackedCollection<?> in = tensor(shape(len, 1)).pack();
		PackedCollection<?> result = new PackedCollection<>(shape(len, 1).traverse(1));

		verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = c(p(in), integers(0, len)).traverseEach().multiply(c(2.0));
			product.get().into(result).evaluate();
		});

		System.out.println(result.valueAt(5000, 0));
		assertEquals(2 * 5000, result.valueAt(5000, 0));
	}

	@Test
	public void integersIndex2d() {
		int len = 10;
		PackedCollection<?> in = tensor(shape(2, len, 1)).pack();
		PackedCollection<?> result = new PackedCollection<>(shape(2));

		verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> value = c(p(in),
																shape(2, len, 1),
																c(0, 1),
																c(4, 8),
																c(0, 0));
			CollectionProducer<PackedCollection<?>> product = value.multiply(c(2.0));
			product.get().into(result).evaluate();
		});

		System.out.println(result.toArrayString());
		assertEquals(8.0, result.toDouble(0));
		assertEquals(18.0, result.toDouble(1));
	}

	@Test
	public void integersIndexAssignment() {
		integersIndexAssignment(false);
	}

	@Test
	public void integersIndexAssignmentOptimized() {
		integersIndexAssignment(true);
	}

	public void integersIndexAssignment(boolean optimize) {
		int count = 6;
		int size = 10;

		PackedCollection<?> buffer = new PackedCollection<>(shape(count, size)).fill(0.0);
		PackedCollection<?> bufferIndices = new PackedCollection<>(shape(count)).fill(1, 2, 3);
		PackedCollection<?> value = new PackedCollection<>(shape(count)).fill(pos -> 1 + Math.random());
		Assignment<?> c = a(
					traverse(0, c(p(buffer), shape(buffer), integers(0, count), traverseEach(p(bufferIndices)))),
					p(value));

		verboseLog(() -> {
			if (optimize) {
				Operation.optimized(c).get().run();
			} else {
				c.get().run();
			}
		});

		for (int i = 0; i < count; i++) {
			for (int j = 0; j < size; j++) {
				if (j == (int) bufferIndices.valueAt(i)) {
					assertEquals(value.toDouble(i), buffer.valueAt(i, j));
				} else {
					assertEquals(0.0, buffer.valueAt(i, j));
				}
			}
		}
	}

	@Test
	public void integersIndexAssignmentOperation() {
		integersIndexAssignmentOperation(false);
	}

	@Test
	public void integersIndexAssignmentOperationIsolated() {
		integersIndexAssignmentOperation(true);
	}

	public void integersIndexAssignmentOperation(boolean isolate) {
		int count = 3;
		int size = 10;

		PackedCollection<?> gain = pack(0.5);
		PackedCollection<?> input = new Scalar(3.0);
		PackedCollection<?> in = pack(2.0, 7.0, 5.0);
		PackedCollection<?> out = pack(0.0, 0.0, 0.0);
		PackedCollection<?> feedback = empty(shape(count, count))
								.fill(pos -> pos[0] == pos[1] ? 1.0 : 0.0);

		PackedCollection<?> buffer = new PackedCollection<>(shape(count, size)).fill(0.0);
		PackedCollection<?> bufferIndices = pack(1, 2, 5);

		OperationList op = new OperationList("Integers Index Assignment");
		op.add(a(
				p(out),
				matmul(p(feedback), p(in)).add(c(p(input), 0).mul(p(gain)).repeat(count))));

		Assignment<PackedCollection<?>> populate =
				a(traverse(0, c(p(buffer), shape(buffer), integers(0, count), traverseEach(p(bufferIndices)))),
				p(out));

		if (isolate) {
			op.add(populate.isolate());
		} else {
			op.add(populate);
		}

		verboseLog(() -> {
			op.get().run();
		});

		buffer.traverse().print();

		for (int i = 0; i < count; i++) {
			for (int j = 0; j < size; j++) {
				if (j == (int) bufferIndices.valueAt(i)) {
					assertEquals(in.toDouble(i) +
									input.toDouble(0) * gain.toDouble(0),
								buffer.valueAt(i, j));
				} else {
					assertEquals(0.0, buffer.valueAt(i, j));
				}
			}
		}
	}

	@Test
	public void addModAssignment() {
		int size = 3;
		PackedCollection<?> indices = pack(2, 4, 6);
		PackedCollection<?> lengths = pack(2, 3, 4);

		a(cp(indices), mod(add(p(indices), c(1).repeat(size)), cp(lengths))).get().run();
		indices.print();

		assertEquals(1.0, indices.toDouble(0));
		assertEquals(2.0, indices.toDouble(1));
		assertEquals(3.0, indices.toDouble(2));
	}

	@Test
	public void multiply() {
		verboseLog(() -> {
			PackedCollection<?> testInput = new PackedCollection<>(1);
			testInput.setMem(0, 9.0);
			PackedCollection<?> result = c(3).multiply(p(testInput)).get().evaluate();
			assertEquals(27, result.toDouble(0));
		});
	}

	@Test
	public void sum() {
		PackedCollection<?> input = tensor(shape(3, 5)).pack();

		verboseLog(() -> {
			PackedCollection<?> output = c(p(input)).sum().get().evaluate();

			double expected = 0;
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 5; j++) {
					expected += input.toDouble(input.getShape().index(i, j));
				}
			}

			System.out.println(output.getShape());
			System.out.println(output.toDouble(0));
			assertEquals(expected, output.toDouble(0));
		});
	}

	@Test
	public void concat() {
		int n = 2;
		int dim = 6;
		int hd = dim / 2;

		PackedCollection<?> va = new PackedCollection<>(n, hd).fill(pos -> 1.0 + pos[1] + 10 * pos[0]);
		PackedCollection<?> vb = new PackedCollection<>(n, hd).fill(pos -> -(1.0 + pos[1] + 10 * pos[0]));

		CollectionProducer<PackedCollection<?>> a = pad(shape(n, dim), cp(va), 0, 0);
		CollectionProducer<PackedCollection<?>> b = pad(shape(n, dim), cp(vb), 0, hd);
		CollectionProducer<PackedCollection<?>> concat = add(a, b);
		PackedCollection<?> output = concat.get().evaluate();
		output.traverse(1).print();

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < dim; j++) {
				double expected = j < hd ?
						va.valueAt(i, j) :
						vb.valueAt(i, j - hd);
				assertEquals(expected, output.valueAt(i, j));
			}
		}
	}

	@Test
	public void concatProduct() {
		int n = 2;
		int dim = 6;
		int hd = dim / 2;

		PackedCollection<?> va = new PackedCollection<>(hd).fill(pos -> 1.0 + pos[0]);
		PackedCollection<?> alt = pack(-1.0, 1.0).reshape(n, 1);

		CollectionProducer<PackedCollection<?>> product =
				multiply(
						cp(alt).repeat(1, hd).reshape(n, hd),
						cp(va).repeat(n).reshape(n, hd));

		CollectionProducer<PackedCollection<?>> a = pad(shape(n, dim),
				product.multiply(c(10)), 0, 0);
		CollectionProducer<PackedCollection<?>> b = pad(shape(n, dim),
				product.multiply(c(100)), 0, hd);

		CollectionProducer<PackedCollection<?>> concat = add(a, b);
		PackedCollection<?> output = concat.get().evaluate();
		output.traverse(1).print();

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < dim; j++) {
				double expected = j < hd ? 10 * (1 + j) : 100 * (1 + j - hd);
				expected *= alt.toDouble(i);
				assertEquals(expected, output.valueAt(i, j));
			}
		}
	}

	@Test
	public void concatSinCos() {
		int n = 2;
		int dim = 6;
		int hd = dim / 2;

		PackedCollection<?> va = new PackedCollection<>(hd).fill(pos -> 1.0 + pos[0]);
		PackedCollection<?> alt = pack(-1.0, 1.0).reshape(n, 1);

		CollectionProducer<PackedCollection<?>> product =
				multiply(
						cp(alt).repeat(1, hd).reshape(n, hd),
						cp(va).repeat(n).reshape(n, hd));

		CollectionProducer<PackedCollection<?>> concat = concat(shape(n, dim), sin(product), cos(product));
		PackedCollection<?> output = concat.get().evaluate();
		output.traverse(1).print();

		for (int i = 0; i < n; i++) {
			double altVal = alt.valueAt(i, 0);
			for (int j = 0; j < dim; j++) {
				double expected;

				if (j < hd) {
					double vaVal = va.valueAt(j);
					double p = altVal * vaVal;
					expected = Math.sin(p);
				} else {
					double vaVal = va.valueAt(j - hd);
					double p = altVal * vaVal;
					expected = Math.cos(p);
				}

				assertEquals(expected, output.valueAt(i, j));
			}
		}
	}

	@Test
	public void size() {
		PackedCollection<?> a = new PackedCollection<>(shape(10));
		PackedCollection<?> b = new PackedCollection<>(shape(15));

		Evaluable<PackedCollection<?>> size = sizeOf(cv(shape(1), 0)).get();
		PackedCollection<?> result = size.evaluate(a);
		result.print();
		assertEquals(10.0, result.toDouble(0));

		result = size.evaluate(b);
		result.print();
		assertEquals(15.0, result.toDouble(0));
	}

	@Test
	public void scale() {
		PackedCollection<?> timeline = new PackedCollection<>(shape(10), 1);
		IntStream.range(0, 10).forEach(i -> timeline.set(i, i + 1));

		Assert.assertEquals(10, multiply(c(2), c(p(timeline))).getCountLong());

		PackedCollection<?> destination = new PackedCollection<>(shape(10), 1);

		Evaluable<PackedCollection<?>> ev = multiply(c(2), c(p(timeline))).get();
		ev.into(destination.traverseEach()).evaluate();
		System.out.println(Arrays.toString(destination.toArray(0, 10)));
		assertEquals(6.0, destination.toDouble(2));
		assertEquals(8.0, destination.toDouble(3));

		destination = ev.evaluate();
		System.out.println(Arrays.toString(destination.toArray(0, 10)));
		assertEquals(6.0, destination.toDouble(2));
		assertEquals(8.0, destination.toDouble(3));
	}

	@Test
	public void scaleEvaluable() {
		PackedCollection<?> timeline = new PackedCollection<>(shape(10), 1);
		IntStream.range(0, 10).forEach(i -> timeline.set(i, i + 1));

		PackedCollection<?> destination = new PackedCollection<>(shape(10), 1);

		Evaluable<PackedCollection<?>> ev = multiply(c(2), c(timeline.getShape(), args -> timeline)).get();
		ev.into(destination.traverseEach()).evaluate();
		System.out.println(Arrays.toString(destination.toArray(0, 10)));
		assertEquals(6.0, destination.toDouble(2));
		assertEquals(8.0, destination.toDouble(3));

		destination = ev.evaluate();
		System.out.println(Arrays.toString(destination.toArray(0, 10)));
		assertEquals(6.0, destination.toDouble(2));
		assertEquals(8.0, destination.toDouble(3));
	}

	@Test
	public void max2d() {
		PackedCollection<?> value = pack(2.0, 3.0, 7.0, 1.0).reshape(2, 2).traverse(1);

		PackedCollection<?> m = max(cp(value)).get().evaluate();
		m.print();
		assertEquals(3.0, m.toDouble(0));
		assertEquals(7.0, m.toDouble(1));
	}

	@Test
	public void indexOfMax2d() {
		PackedCollection<?> value = pack(5.0, 3.0, 7.0, 10.0).reshape(2, 2).traverse(1);

		PackedCollection<?> m = cp(value).indexOfMax().get().evaluate();
		m.print();

		assertEquals(0.0, m.toDouble(0));
		assertEquals(1.0, m.toDouble(1));
	}

	@Test
	public void max3d() {
		PackedCollection<?> value = new PackedCollection<>(shape(2, 3, 2))
				.fill(pos -> (1.0 + pos[0]) * (-0.5 + pos[1] % 2) * (0.7 + pos[2]))
				.traverse(2);
		value.print();
		System.out.println("--");

		PackedCollection<?> m = max(cp(value)).get().evaluate();
		System.out.println(m.getShape());
		m.print();

		Assert.assertEquals(3, m.getShape().getDimensions());
		Assert.assertEquals(2, m.getShape().length(0));
		Assert.assertEquals(3, m.getShape().length(1));
		Assert.assertEquals(1, m.getShape().length(2));

		assertEquals(-0.35, m.toDouble(0));
		assertEquals(0.85, m.toDouble(1));
		assertEquals(-0.35, m.toDouble(2));
		assertEquals(-0.7, m.toDouble(3));
		assertEquals(1.7, m.toDouble(4));
		assertEquals(-0.7, m.toDouble(5));
	}

	// @Test
	public void dynamicMax() {
		PackedCollection<?> value = new PackedCollection<>(shape(2, 3, 2))
				.fill(pos -> (1.0 + pos[0]) * (-0.5 + pos[1] % 2) * (0.7 + pos[2]))
				.traverse(2);
		value.print();
		System.out.println("--");

		PackedCollection<?> output = new PackedCollection<>(shape(2, 3, 1)).traverse(2);

//		PackedCollection<?> m = max(new DynamicCollectionProducer<PackedCollection<?>>(shape(2, 3, 2), args -> value, false)).get().evaluate();
		PackedCollection<?> m = max(new DynamicProducer<PackedCollection<?>>(args -> value)).get().into(output).evaluate();
		System.out.println(m.getShape());
		m.print();

		Assert.assertEquals(3, m.getShape().getDimensions());
		Assert.assertEquals(2, m.getShape().length(0));
		Assert.assertEquals(3, m.getShape().length(1));
		Assert.assertEquals(1, m.getShape().length(2));

		assertEquals(-0.35, m.toDouble(0));
		assertEquals(0.85, m.toDouble(1));
		assertEquals(-0.35, m.toDouble(2));
		assertEquals(-0.7, m.toDouble(3));
		assertEquals(1.7, m.toDouble(4));
		assertEquals(-0.7, m.toDouble(5));
	}

	@Test
	public void collectionMaxTwoSeries() {
		PackedCollection<?> series = new PackedCollection(2, 10);
		series.setMem(0, 7.0, 5.0, 12.0, 13.0, 11.0, 14.0, 9.0, 12.0, 3.0, 12.0);
		series.setMem(10, 12.0, 3.0, 12.0, 10.0, 14.0, 16.0, 13.0, 12.0, 5.0, 7.0);
		System.out.println(series.traverse(1).getCountLong() + " series");

		Producer<PackedCollection<?>> max = max(v(shape(-1, 10), 0));
		PackedCollection dest = max.get().evaluate(series.traverse(1));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(14, dest.toArray(0, 1)[0]);
		assertEquals(16, dest.toArray(1, 1)[0]);
	}

	@Test
	public void collectionMax() {
		PackedCollection<?> series = new PackedCollection(10);
		series.setMem(0, 7.0, 5.0, 12.0, 13.0, 11.0, 14.0, 9.0, 12.0, 3.0, 12.0);
		System.out.println(series.traverse(0).getCountLong() + " series");

		Producer<PackedCollection<?>> max = max(v(shape(10), 0));
		PackedCollection<?> dest = new PackedCollection(2, 1);

		try {
			verboseLog(() ->
					max.get().into(dest.traverse(1)).evaluate(series.traverse(0)));
		} catch (IllegalArgumentException e) {
			// Expected due to mixmatch of output and operation count
			return;
		}

		// If this was permitted, it should perhaps repeat the
		// result for every position in the output
		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(14, dest.toArray(0, 1)[0]);
		assertEquals(14, dest.toArray(1, 1)[0]);
	}

	@Test
	public void greaterThanMax() {
		PackedCollection<?> series = new PackedCollection(10);
		series.setMem(0, 7.0, 5.0, 12.0, 13.0, 11.0, 14.0, 9.0, 12.0, 3.0, 12.0);
		System.out.println(series.traverse(0).getCountLong() + " series");

		PackedCollection<?> dest = new PackedCollection(1);
		CollectionProducer<PackedCollection<?>> max = cp(series.traverse(0)).max();
		CollectionProducer<PackedCollection<?>> auto = max.greaterThan(c(0.0), c(0.8).divide(max), c(1.0));

		verboseLog(() -> {
			OperationList op = new OperationList("greaterThanMax");
			op.add(a(1, p(dest), auto));
			op.get().run();
		});

		System.out.println("Max = " + dest.toDouble(0));
		assertEquals(0.8 / 14, dest.toDouble(0));
	}

	@Test
	public void dynamicProjection() {
		PackedCollection<?> in = pack(2.0, 6.0, 3.0, 1.0).reshape(2, 2).traverse(1);

		TraversalPolicy shape = shape(in).flatten(true);

		DynamicIndexProjectionProducerComputation<?> c = new DynamicIndexProjectionProducerComputation<>(null, shape(2), (args, idx) -> {
			Expression<?> result = null;

			for (int i = 0; i < shape.getSize(); i++) {
				Expression<?> index = shape.index(idx, e(i));

				if (result == null) {
					result = index;
				} else {
					result = conditional(args[1].getValueAt(index)
								.greaterThan(args[1].getValueAt(result)),
							index, result);
				}
			}

			return result;
		}, p(in)
		);

		PackedCollection<?> out = c.get().evaluate();
		print(2, 1, out);
		assertEquals(6.0, out.toDouble(0));
		assertEquals(3.0, out.toDouble(1));
	}
}
