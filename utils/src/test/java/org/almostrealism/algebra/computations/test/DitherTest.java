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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.ParallelProcess;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class DitherTest implements TestFeatures {
	boolean enableCountAssertions = false;

	@Test
	public void dither() {
		ExpressionComputation<PackedCollection<Scalar>> dither = dither(200, v(400, 0), v(Scalar.shape(), 1));
 		if (enableCountAssertions) Assert.assertFalse(ParallelProcess.isFixedCount(dither));
		PackedCollection<Scalar> result = dither.get().evaluate(Scalar.scalarBank(200), new Scalar(1.0));
		assertNotEquals(0.0, result.get(20));
	}

	@Test
	public void random1() {
		PackedCollection<Scalar> random = Scalar.scalarBank(160);
		IntStream.range(0, 160).forEach(i ->  random.set(i, 100 * Math.random()));
		ExpressionComputation<PackedCollection<Scalar>> dither = dither(160, v(320, 0), v(Scalar.shape(), 1));
		PackedCollection<Scalar> out = dither.get().evaluate(random, new Scalar(1.0));
		assertNotEquals(0.0, out.get(20));
	}

	@Test
	public void random2() {
		PackedCollection<Scalar> random = Scalar.scalarBank(160);
		IntStream.range(0, 160).forEach(i ->  random.set(i, 100 * Math.random()));
		ExpressionComputation<PackedCollection<Scalar>> dither = dither(160, v(Scalar.shape(), 0), v(Scalar.shape(), 1));
		Assert.assertFalse(ParallelProcess.isFixedCount(dither));
		PackedCollection<Scalar> out = dither.get().evaluate(random, new Scalar(1.0));
		assertNotEquals(0.0, out.get(20));
	}
}
