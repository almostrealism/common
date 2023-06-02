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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class PreemphasizeTest implements TestFeatures {
	public static final int SIZE = 25;

	public PackedCollection<Scalar>  window() {
		PackedCollection<Scalar> window = Scalar.scalarBank(SIZE);
		IntStream.range(0, 25).forEach(i -> window.set(i, i * 10, 1.0));
		return window;
	}

	@Test
	public void preemphasize() {
		Evaluable<PackedCollection<Scalar>> ev = preemphasizeOld(SIZE,
				v(2 * SIZE, 0),
				v(Scalar.shape(), 1)).get();

		System.out.println("Standard...");
		PackedCollection<Scalar> b = ev.evaluate(window(), new Scalar(0.1));
		IntStream.range(0, b.getCount()).mapToObj(b::get).forEach(System.out::println);

		System.out.println("Fast...");
		PackedCollection<Scalar> c = preemphasize(SIZE, v(2 * SIZE, 0),
				v(Scalar.shape(), 1)).get().evaluate(window(), new Scalar(0.1));
		IntStream.range(0, c.getCount()).mapToObj(c::get).forEach(System.out::println);

		IntStream.range(0, c.getCount()).forEach(i -> assertEquals(b.get(i), c.get(i)));
	}
}
