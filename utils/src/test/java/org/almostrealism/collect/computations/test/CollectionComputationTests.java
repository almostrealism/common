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

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionMax;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

public class CollectionComputationTests implements TestFeatures {

	@Test
	public void integers() {
		CLOperator.verboseLog(() -> {
			PackedCollection<?> result = integers(10, 100).get().evaluate();
			assertEquals(14, result.toDouble(4));
		});
	}

	@Test
	public void index() {
		int len = 10000;
		PackedCollection<?> in = tensor(shape(len, 1)).pack();
		PackedCollection<?> result = new PackedCollection<>(shape(len, 1).traverse(1));

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> product = c(p(in), integers(0, len)).traverseEach().multiply(c(2.0));
			product.get().into(result).evaluate();
		});

		System.out.println(result.valueAt(5000, 0));
		assertEquals(2 * 5000, result.valueAt(5000, 0));
	}

	@Test
	public void multiply() {
		CLOperator.verboseLog(() -> {
			PackedCollection<?> testInput = new PackedCollection<>(1);
			testInput.setMem(0, 9.0);
			PackedCollection<?> result = c(3).multiply(p(testInput)).get().evaluate();
			assertEquals(27, result.toDouble(0));
		});
	}

	@Test
	public void sum() {
		PackedCollection<?> input = tensor(shape(3, 5)).pack();
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
	}

	@Test
	public void expressionComputation() {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				new Sum(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

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
				new Sum(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

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
				new Sum(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

		PackedCollection<?> a = new PackedCollection(1);
		PackedCollection<?> b = new PackedCollection(1);
		a.setMem(3.0);
		b.setMem(5.0);

		HardwareOperator.verboseLog(() -> {
			ExpressionComputation<PackedCollection<?>> computation =
					new ExpressionComputation<>(List.of(expression),
							() -> new Provider<>(a),
							() -> new Provider<>(b));

			KernelizedEvaluable<PackedCollection<?>> ev = computation.get();

			PackedCollection out = ev.evaluate();
			assertEquals(8.0, out.toArray(0, 1)[0]);

			if (a.getMem().getProvider() == Hardware.getLocalHardware().getDataContext().getKernelMemoryProvider()) {
				Assert.assertEquals(3, ev.getArgsCount());
			} else {
				Assert.assertEquals(2, ev.getArgsCount());
			}
		});
	}

	@Test
	public void expressionComputationKernel() {
		Function<List<ArrayVariable<Double>>, Expression<Double>> expression = args ->
				new Sum(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0));

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

		KernelizedEvaluable<PackedCollection<?>> ev = multiply(c(2), c(p(timeline))).get();
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

		KernelizedEvaluable<PackedCollection<?>> ev = multiply(c(2), c(timeline.getShape(), args -> timeline)).get();
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
	public void collectionMaxTwoSeries() {
		PackedCollection<?> series = new PackedCollection(2, 10);
		series.setMem(0, 7.0, 5.0, 12.0, 13.0, 11.0, 14.0, 9.0, 12.0, 3.0, 12.0);
		series.setMem(10, 12.0, 3.0, 12.0, 10.0, 14.0, 16.0, 13.0, 12.0, 5.0, 7.0);
		System.out.println(series.traverse(1).getCount() + " series");

		PackedCollectionMax max = new PackedCollectionMax(new PassThroughProducer<>(10, 0));
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

		PackedCollectionMax max = new PackedCollectionMax(new PassThroughProducer<>(shape(10), 0));
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
		CollectionProducer<PackedCollection<?>> max = new PackedCollectionMax(p(series.traverse(0)));
		CollectionProducer<PackedCollection<?>> auto = max._greaterThan(c(0.0), c(0.8).divide(max), c(1.0));

		CLOperator.verboseLog(() -> {
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
	public void clear() {
		PackedCollection data = new PackedCollection(4);
		data.setMem(0, 1.0, 2.0, 3.0, 4.0);
		data.clear();
		assertEquals(0, data.toArray(0, 4)[1]);
	}
}
