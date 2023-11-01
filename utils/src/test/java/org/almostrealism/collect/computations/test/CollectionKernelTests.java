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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class CollectionKernelTests implements TestFeatures {
	@Test
	public void func() {
		DynamicCollectionProducer<PackedCollection<?>> a = func(shape(2, 5), args ->
				c(shape(2, 5), 2.0, 3.0, 4.0, 6.0, 7.0, 8.0, 11.0, 13.0, 15.0, 17.0)
						.get().evaluate(args));
		PackedCollection<?> out = a.traverse(1).get().evaluate();
		System.out.println("CollectionKernelTests.func: Out shape = " + out.getShape());
		System.out.println("CollectionKernelTests.func: Out count = " + out.getCount());
		System.out.println("CollectionKernelTests.func: Out atomic length = " + out.getAtomicMemLength());

		Assert.assertEquals(2, out.getShape().length(0));
		Assert.assertEquals(5, out.getShape().length(1));
		Assert.assertEquals(2, out.getCount());
	}

	@Test
	public void multiply() {
		double v1[] = { 2.0, 3.0, 4.0, 6.0, 7.0, 8.0, 11.0, 13.0, 15.0, 17.0};
		double v2[] = { 2.0, 3.0, 0.5, 0.25, 0.1 };

		CollectionProducer<PackedCollection<?>> a = func(shape(2, 5), args ->
				c(shape(2, 5), v1)
						.get().evaluate(args));
		CollectionProducer<PackedCollection<?>> b = func(shape(5), args ->
				c(v2).get().evaluate(args));

		CLOperator.verboseLog(() -> {
			CollectionProducerComputation<PackedCollection<?>> c = relativeMultiply(shape(2, 5).traverse(1), a.traverse(1), b.traverse(0), null);
			Evaluable<PackedCollection<?>> eval = c.get();
			PackedCollection<?> out = eval.evaluate();

			System.out.println("CollectionKernelTests.divide: Out shape = " + out.getShape());
			System.out.println("CollectionKernelTests.divide: Out count = " + out.getCount());

			Assert.assertEquals(2, out.getShape().length(0));
			Assert.assertEquals(5, out.getShape().length(1));
			Assert.assertEquals(2, out.getCount());

			double values[] = out.toArray(0, 10);
			System.out.println(Arrays.toString(values));

			for (int i = 0; i < 5; i++) {
				assertEquals(v1[i] * v2[i], values[i]);
				assertEquals(v1[i + 5] * v2[i], values[i + 5]);
			}
		});
	}

	@Test
	public void divide() {
		double v1[] = { 2.0, 3.0, 4.0, 6.0, 7.0, 8.0, 11.0, 13.0, 15.0, 17.0};
		double v2[] = { 2.0 };

		CollectionProducer<PackedCollection<?>> a = func(shape(2, 5), args ->
				c(shape(2, 5), v1)
						.get().evaluate(args));
		CollectionProducer<PackedCollection<?>> b = func(shape(1), args ->
				c(v2).get().evaluate(args));

		CLOperator.verboseLog(() -> {
			Producer<PackedCollection<?>> c = divide(a.traverseEach(), b.traverse(0)).reshape(shape(2, 5));
			Evaluable<PackedCollection<?>> eval = c.get();
			PackedCollection<?> out = eval.evaluate();

			System.out.println("CollectionKernelTests.divide: Out shape = " + out.getShape());
			System.out.println("CollectionKernelTests.divide: Out count = " + out.getCount());

			Assert.assertEquals(2, out.getShape().length(0));
			Assert.assertEquals(5, out.getShape().length(1));

			double values[] = out.toArray(0, 10);
			System.out.println(Arrays.toString(values));

			for (int i = 0; i < 10; i++) {
				assertEquals(v1[i] / v2[0], values[i]);
			}
		});
	}

	@Test
	public void providerAddKernel() {
		PackedCollection<?> a = tensor(shape(10)).pack().traverse();
		PackedCollection<?> b = tensor(shape(10)).pack().traverse();

		CLOperator.verboseLog(() -> {
			CollectionProducer<PackedCollection<?>> p = add(traverse(1, p(a)), traverse(1, p(b)));
			PackedCollection<?> out = p.get().evaluate();

			Assert.assertEquals(10, out.getShape().length(0));

			for (int i = 0; i < out.getShape().length(0); i++) {
				assertEquals(a.toDouble(i) + b.toDouble(i), out.toDouble(i));
			}
		});
	}

	@Test
	public void conditionalKernel() {
		Producer<PackedCollection<?>> in = v(1, 0);
		Producer<PackedCollection<?>> t = integers(0, 100);
		Producer<PackedCollection<?>> conditional =
				greaterThanConditional(t, c(50),
						multiply(in, c(0.5)),
						multiply(in, c(1.5)));

		PackedCollection<?> value = tensor(shape(100)).pack();
		PackedCollection<?> out = conditional.get().evaluate(value.traverseEach());

		System.out.println(out.valueAt(45));
		System.out.println(out.valueAt(60));

		assertEquals(67.5, out.valueAt(45));
		assertEquals(30.0, out.valueAt(60));
	}
}
