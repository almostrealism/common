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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Operation;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicIndexProjectionProducerComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

public class CollectionComputationTests implements TestFeatures {

	@Test
	public void evaluateIntegers() {
		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> result = integers(10, 100).get().evaluate();
			assertEquals(14, result.toDouble(4));
		});
	}

	@Test
	public void index() {
		PackedCollection<?> x = pack(1, 1, 1, 2, 2, 2);
		PackedCollection<?> y = pack(0, 1, 2, 0, 1, 2);
		PackedCollection<?> result = new PackedCollection<>(shape(6));

		HardwareOperator.verboseLog(() -> {
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

		HardwareOperator.verboseLog(() -> {
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

		HardwareOperator.verboseLog(() -> {
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

	// @Test
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

		HardwareOperator.verboseLog(() -> {
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
		op.add(a(
				traverse(0, c(p(buffer), shape(buffer), integers(0, count), traverseEach(p(bufferIndices)))),
				p(out)).isolate());

		HardwareOperator.verboseLog(() -> {
			op.get().run();
		});

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
	public void multiply() {
		HardwareOperator.verboseLog(() -> {
			PackedCollection<?> testInput = new PackedCollection<>(1);
			testInput.setMem(0, 9.0);
			PackedCollection<?> result = c(3).multiply(p(testInput)).get().evaluate();
			assertEquals(27, result.toDouble(0));
		});
	}

	@Test
	public void sum() {
		PackedCollection<?> input = tensor(shape(3, 5)).pack();

		HardwareOperator.verboseLog(() -> {
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
	public void expressionComputation() {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				Sum.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

		ExpressionComputation<?> computation =
				new ExpressionComputation(List.of(expression),
						new PassThroughProducer(1, 0),
						new PassThroughProducer(1, 1));

		PackedCollection a = new PackedCollection(1);
		PackedCollection b = new PackedCollection(1);
		a.setMem(0, 3.0);
		b.setMem(0, 5.0);

		PackedCollection out = computation.get().evaluate(a, b);
		assertEquals(8.0, out.toArray(0, 1)[0]);
	}


	@Test
	public void expressionComputationDynamic() {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				Sum.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

		ExpressionComputation<?> computation =
				new ExpressionComputation(List.of(expression),
						(Producer) () -> args -> {
							PackedCollection<?> c = new PackedCollection<>(1);
							c.setMem(0, 2.0 * (Integer) args[1]);
							return c;
						},
						new PassThroughProducer(1, 0));

		PackedCollection a = new PackedCollection(1);
		a.setMem(0, 3.0);

		PackedCollection out = computation.get().evaluate(a, Integer.valueOf(6));
		assertEquals(15.0, out.toArray(0, 1)[0]);
	}

	@Test
	public void providerExpressionComputation() {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				Sum.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

		PackedCollection<?> a = new PackedCollection(1);
		PackedCollection<?> b = new PackedCollection(1);
		a.setMem(3.0);
		b.setMem(5.0);

		HardwareOperator.verboseLog(() -> {
			ExpressionComputation<PackedCollection<?>> computation =
					new ExpressionComputation<>(List.of(expression),
							() -> new Provider<>(a),
							() -> new Provider<>(b));

			Evaluable<PackedCollection<?>> ev = computation.get();

			PackedCollection out = ev.evaluate();
			assertEquals(8.0, out.toArray(0, 1)[0]);

			if (a.getMem().getProvider() == Hardware.getLocalHardware().getDataContext().getKernelMemoryProvider()) {
				Assert.assertEquals(3, ((KernelizedEvaluable) ev).getArgsCount());
			} else {
				Assert.assertEquals(2, ((KernelizedEvaluable) ev).getArgsCount());
			}
		});
	}

	@Test
	public void expressionComputationKernel() {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				Sum.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

		ExpressionComputation<?> computation =
				new ExpressionComputation(List.of(expression),
						new PassThroughProducer(1, 0),
						new PassThroughProducer(1, 1));

		PackedCollection<?> a = new PackedCollection(4);
		PackedCollection<?> b = new PackedCollection(4);
		a.setMem(0, 3.0, 4.0, 5.0, 6.0);
		b.setMem(0, 5.0, 7.0, 9.0, 11.0);

		PackedCollection<?> out = new PackedCollection(4);

		computation.get().into(out.traverseEach()).evaluate(a.traverseEach(), b.traverseEach());
		System.out.println(Arrays.toString(out.toArray(0, 4)));
		assertEquals(8.0, out.toArray(0, 1)[0]);
		assertEquals(14.0, out.toArray(2, 1)[0]);
	}

	@Test
	public void scale() {
		PackedCollection<?> timeline = new PackedCollection<>(shape(10), 1);
		IntStream.range(0, 10).forEach(i -> timeline.set(i, i + 1));

		Assert.assertEquals(10, multiply(c(2), c(p(timeline))).getCount());

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
		System.out.println(series.traverse(1).getCount() + " series");

		Producer<PackedCollection<?>> max = max(new PassThroughProducer<>(10, 0));
		PackedCollection dest = max.get().evaluate(series.traverse(1));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(14, dest.toArray(0, 1)[0]);
		assertEquals(16, dest.toArray(1, 1)[0]);
	}

	@Test
	public void collectionMax() {
		PackedCollection<?> series = new PackedCollection(10);
		series.setMem(0, 7.0, 5.0, 12.0, 13.0, 11.0, 14.0, 9.0, 12.0, 3.0, 12.0);
		System.out.println(series.traverse(0).getCount() + " series");

		Producer<PackedCollection<?>> max = max(new PassThroughProducer<>(shape(10), 0));
		PackedCollection<?> dest = new PackedCollection(2, 1);

		HardwareOperator.verboseLog(() ->
			max.get().into(dest.traverse(1)).evaluate(series.traverse(0)));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(14, dest.toArray(0, 1)[0]);
		assertEquals(14, dest.toArray(1, 1)[0]);
	}

	@Test
	public void greaterThanMax() {
		PackedCollection<?> series = new PackedCollection(10);
		series.setMem(0, 7.0, 5.0, 12.0, 13.0, 11.0, 14.0, 9.0, 12.0, 3.0, 12.0);
		System.out.println(series.traverse(0).getCount() + " series");

		PackedCollection<?> dest = new PackedCollection(1);
		CollectionProducer<PackedCollection<?>> max = cp(series.traverse(0)).max();
		CollectionProducer<PackedCollection<?>> auto = max._greaterThan(c(0.0), c(0.8).divide(max), c(1.0));

		HardwareOperator.verboseLog(() -> {
			OperationList op = new OperationList("greaterThanMax");
			op.add(a(1, p(dest), auto));
			op.get().run();
		});

		System.out.println("Max = " + dest.toDouble(0));
		assertEquals(0.8 / 14, dest.toDouble(0));
	}

	@Test
	public void scalarFromCollection() {
		Tensor<Scalar> values = new Tensor<>();
		values.insert(new Scalar(1.0), 0);
		values.insert(new Scalar(2.0), 1);
		values.insert(new Scalar(3.0), 2);

		PackedCollection collection = values.pack();

		Producer<Scalar> scalar = scalar(collection.getShape().traverse(1), p(collection), v(1));
		Scalar output = scalar.get().evaluate();
		assertEquals(2.0, output);
		assertEquals(1.0, output.toDouble(1));
	}

	@Test
	public void dynamicProjection() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		PackedCollection<?> in = pack(2.0, 6.0, 3.0, 1.0).reshape(2, 2).traverse(1);

		TraversalPolicy shape = shape(in).flatten(true);

		DynamicIndexProjectionProducerComputation<?> c = new DynamicIndexProjectionProducerComputation<>(shape(2), (args, idx) -> {
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

	@Test
	public void clear() {
		PackedCollection data = new PackedCollection(4);
		data.setMem(0, 1.0, 2.0, 3.0, 4.0);
		data.clear();
		assertEquals(0, data.toArray(0, 4)[1]);
	}
}
