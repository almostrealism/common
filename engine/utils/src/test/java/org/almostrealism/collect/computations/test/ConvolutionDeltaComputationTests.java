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
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.GradientTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

/**
 * Delta-computation tests for convolution and 2D enumeration operations.
 *
 * <p>These tests verify gradient computation through enumerate-based convolution
 * patterns including sparse delta tensors and weight gradients for conv2d layers.</p>
 */
public class ConvolutionDeltaComputationTests extends TestSuiteBase implements GradientTestFeatures {

	@Test(timeout = 60000)
	public void enumerate2d() {
		int dim = 6;
		int size = 3;
		int filterCount = 2;
		int pad = size - 1;
		TraversalPolicy outputShape = shape(dim - pad, dim - pad, filterCount);

		PackedCollection input = integers(1, 1 + dim * dim).evaluate().reshape(dim, dim);
		PackedCollection filters = new PackedCollection(shape(size, size, filterCount)).fill(Math::random);

		CollectionProducer c = cp(input)
				.enumerate(1, size, 1)
				.enumerate(1, size, 1)
				.traverse(2)
				.repeat(filterCount)
				.traverse(2)
				.multiply(cp(filters)
						.repeat(outputShape.length(1)).traverse(0)
						.repeat(outputShape.length(0)).traverse(2))
				.traverse();

		PackedCollection result = Process.optimized(c.delta(p(input))).get().evaluate();
		result.print();
	}

	@Test(timeout = 60000)
	public void conv2d() {
		int size = 3;
		int filterCount = 8;

		PackedCollection input = integers(1, 101).evaluate().reshape(10, 10);
		PackedCollection filters = pack(1, 2, 3, 4, 5, 6, 7, 8);

		CollectionProducer c = cp(input)
						.enumerate(1, size, 1)
						.enumerate(1, size, 1)
						.traverse(2)
						.repeat(filterCount)
						.multiply(p(filters))
						.traverse()
						.reduce(v -> v.sum());

		c.delta(p(filters)).evaluate();
		// TODO  assertions
	}

	@Test(timeout = 60000)
	public void conv2dEnumerateProduct() {
		int h = 3; // 10;
		int w = 4; // 10;
		int size = 3;
		int filterCount = 2; // 8;

		PackedCollection input = integers(1, (h * w) + 1).evaluate().reshape(h, w);
		PackedCollection filters = integers(1, filterCount + 1).evaluate();

		CollectionProducer c = cp(input)
				.enumerate(1, size, 1)
				.enumerate(1, size, 1)
				.traverse(2)
				.repeat(filterCount)
				.multiply(cp(filters))
				.traverse()
				.reduce(v -> v.sum());

		int outSize = shape(c).getTotalSize();
		PackedCollection g = integers(1, outSize + 1).evaluate().reshape(shape(c));
		Producer<PackedCollection> weightFlat = reshape(shape(filterCount), p(filters));

		Producer<PackedCollection> cdy = c.delta(p(filters))
				.reshape(outSize, filterCount)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(filterCount))
				.traverse(0)
				.enumerate(1, 1)
				.sum(1)
				.reshape(shape(filterCount))
				.each();

		PackedCollection sparse = new PackedCollection(shape(outSize, filterCount));

		c.delta(p(filters)).into(sparse.traverse()).evaluate();
		// print(h, filterCount, sparse);

		c.delta(p(filters))
				.reshape(outSize, filterCount)
				.traverse(1)
				.multiply(c(g).reshape(outSize).traverse(1).expand(filterCount))
				.enumerate(1, 1)
				.into(sparse.each()).evaluate();
		// print(h, filterCount, sparse);

		Supplier<Runnable> cda = a(each(weightFlat), subtract(each(weightFlat), multiply(c(2.0), cdy)));
		cda.get().run();
	}
}
