/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.code.Computation;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.ProducerWithOffset;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionMax;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.RootDelegateSegmentsAdd;
import org.almostrealism.collect.computations.ScalarFromPackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class CollectionComputationTests implements TestFeatures {
	private static class TestProducer implements Producer<PackedCollection<?>>, Shape<Producer<PackedCollection<?>>> {
		@Override
		public TraversalPolicy getShape() {
			return new TraversalPolicy(1);
		}

		@Override
		public Evaluable<PackedCollection<?>> get() {
			return args -> {
				PackedCollection<?> c = new PackedCollection<>(1);
				c.setMem(0, 9);
				return c;
			};
		}

		@Override
		public Producer<PackedCollection<?>> reshape(TraversalPolicy shape) {
			return new ReshapeProducer<>(shape, (Producer) this);
		}
	}

	@Test
	public void multiply() {
		PackedCollection<?> testInput = new PackedCollection<>(1);
		testInput.setMem(0, 9);
		PackedCollection<?> result = c(3)._multiply(p(testInput)).get().evaluate();
		assertEquals(27, result.toDouble(0));
	}

	@Test
	public void expressionComputation() {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Sum(args.get(1).getValue(0), args.get(2).getValue(0));

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
	public void providerExpressionComputation() {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Sum(args.get(1).getValue(0), args.get(2).getValue(0));

		PackedCollection<?> a = new PackedCollection(1);
		PackedCollection<?> b = new PackedCollection(1);
		a.setMem(3.0);
		b.setMem(5.0);

		ExpressionComputation<?> computation =
				new ExpressionComputation(List.of(expression),
						() -> new Provider<>(a),
						() -> new Provider<>(b));

		KernelizedEvaluable<?> ev = computation.get();
		Assert.assertEquals(2, ev.getArgsCount());

  		PackedCollection out = (PackedCollection) ev.evaluate();
		assertEquals(8.0, out.toArray(0, 1)[0]);
	}

	@Test
	public void expressionComputationKernel() {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Sum(args.get(1).getValue(0), args.get(2).getValue(0));

		ExpressionComputation<?> computation =
				new ExpressionComputation(List.of(expression),
						new PassThroughProducer(1, 0),
						new PassThroughProducer(1, 1));

		PackedCollection<?> a = new PackedCollection(4);
		PackedCollection<?> b = new PackedCollection(4);
		a.setMem(0, 3.0, 4.0, 5.0, 6.0);
		b.setMem(0, 5.0, 7.0, 9.0, 11.0);

		PackedCollection<?> out = new PackedCollection(4);

		computation.get().kernelEvaluate(out.traverseEach(), a.traverseEach(), b.traverseEach());
		System.out.println(Arrays.toString(out.toArray(0, 4)));
		assertEquals(8.0, out.toArray(0, 1)[0]);
		assertEquals(14.0, out.toArray(2, 1)[0]);
	}

	@Test
	public void collectionMaxTwoSeries() {
		PackedCollection<?> series = new PackedCollection(2, 10);
		series.setMem(0, 7, 5, 12, 13, 11, 14, 9, 12, 3, 12);
		series.setMem(10, 12, 3, 12, 10, 14, 16, 13, 12, 5, 7);
		System.out.println(series.traverse(1).getCount() + " series");

		PackedCollectionMax max = new PackedCollectionMax(new PassThroughProducer<>(10, 0, 0));
		PackedCollection dest = max.get().evaluate(series.traverse(1));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(14, dest.toArray(0, 1)[0]);
		assertEquals(16, dest.toArray(1, 1)[0]);
	}

	@Test
	public void collectionMax() {
		PackedCollection<?> series = new PackedCollection(10);
		series.setMem(0, 7, 5, 12, 13, 11, 14, 9, 12, 3, 12);
		System.out.println(series.traverse(0).getCount() + " series");

		PackedCollectionMax max = new PackedCollectionMax(new PassThroughProducer<>(10, 0, -1));
		PackedCollection<?> dest = new PackedCollection(2, 1);

		HardwareOperator.verboseLog(() ->
			max.get().kernelEvaluate(dest.traverse(1), series.traverse(0)));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(14, dest.toArray(0, 1)[0]);
		assertEquals(14, dest.toArray(1, 1)[0]);
	}

	@Test
	public void scalarFromCollection() {
		Tensor<Scalar> values = new Tensor<>();
		values.insert(new Scalar(1.0), 0);
		values.insert(new Scalar(2.0), 1);
		values.insert(new Scalar(3.0), 2);

		PackedCollection collection = values.pack();

		ScalarFromPackedCollection scalar = new ScalarFromPackedCollection(collection.getShape(), p(collection), v(1));
		assertEquals(2.0, scalar.get().evaluate());
	}

	@Test
	public void rootDelegateAdd() {
		PackedCollection root = Hardware.getLocalHardware().getClDataContext().deviceMemory(() -> new PackedCollection(3, 5));
		// PackedCollection root = new PackedCollection(3, 5);

		PackedCollection a = new PackedCollection(new TraversalPolicy(5), 1, root, 0);
		Scalar s = new Scalar(a, 0);
		s.setLeft(4);
		s.setRight(6);

		PackedCollection b = new PackedCollection(new TraversalPolicy(5), 1, root, 5);
		s = new Scalar(b, 0);
		s.setLeft(4);
		s.setRight(6);

		PackedCollection dest = new PackedCollection(new TraversalPolicy(5), 1, root, 10);

		RootDelegateSegmentsAdd<PackedCollection> op = new RootDelegateSegmentsAdd<>(
				List.of(new ProducerWithOffset<>(v(a), 1),
						new ProducerWithOffset<>(v(b), 2)),
				dest);
		Runnable r = op.get();
		r.run();

		assertEquals(4.0, new Scalar(root, 11));
		assertEquals(10.0, new Scalar(root, 12));
		assertEquals(6.0, new Scalar(root, 13));
	}

	@Test
	public void clear() {
		PackedCollection data = new PackedCollection(4);
		data.setMem(0, 1, 2, 3, 4);
		data.clear();
		assertEquals(0, data.toArray(0, 4)[1]);
	}
}
