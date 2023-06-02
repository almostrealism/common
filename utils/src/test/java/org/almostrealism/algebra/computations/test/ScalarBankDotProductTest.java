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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class ScalarBankDotProductTest implements TestFeatures {
	public static final int SIZE = 32;

	public PackedCollection<Scalar>  window() {
		PackedCollection<Scalar>  window = Scalar.scalarBank(SIZE);
		IntStream.range(0, SIZE).forEach(i -> window.set(i, i * 4, i * 10));
		return window;
	}

	@Test
	public void scalarBankDotProduct32() {
		PackedCollection<Scalar>  window = window();

		Scalar given = new Scalar(IntStream.range(0, SIZE)
				.mapToDouble(i -> window.get(i).getValue() * window.get(i).getValue()).sum());

		Producer<PackedCollection<?>> a = subset(shape(SIZE, 1), v(shape(SIZE, 2), 0), 0);
		Producer<PackedCollection<?>> b = subset(shape(SIZE, 1), v(shape(SIZE, 2), 1), 0);
		Evaluable<? extends Scalar> ev = scalar(multiply(a, b).sum()).get();

		Scalar test = ev.evaluate(window(), window());
		System.out.println(test);
		assertEquals(given, test);
	}
}
