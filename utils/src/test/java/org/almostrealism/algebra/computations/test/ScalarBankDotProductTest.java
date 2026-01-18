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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class ScalarBankDotProductTest implements TestFeatures {
	public static final int SIZE = 32;

	public PackedCollection window() {
		PackedCollection window = new PackedCollection(shape(SIZE, 2));
		IntStream.range(0, SIZE).forEach(i -> {
			window.setMem(i * 2, i * 4);
			window.setMem(i * 2 + 1, i * 10);
		});
		return window;
	}

	@Test(timeout = 10000)
	public void scalarBankDotProduct32() {
		PackedCollection window = window();

		PackedCollection given = pack(IntStream.range(0, SIZE)
				.mapToDouble(i -> window.valueAt(i, 0) * window.valueAt(i, 0)).sum());

		verboseLog(() -> {
			Producer<PackedCollection> a = subset(shape(SIZE, 1), v(shape(SIZE, 2), 0), 0);
			Producer<PackedCollection> b = subset(shape(SIZE, 1), v(shape(SIZE, 2), 1), 0);
			Evaluable<PackedCollection> ev = multiply(a, b).sum().get();

			PackedCollection test = ev.evaluate(window().traverse(0), window().traverse(0));
			test.print();
			assertEquals(given, test);
		});
	}
}
