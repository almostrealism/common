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
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class PowerSpectrumTest implements TestFeatures {
	public PackedCollection<Pair<?>> window(int size) {
		PackedCollection<Pair<?>> window = Pair.bank(size);
		IntStream.range(0, size).forEach(i -> window.set(i, (i + 1) * 4, (i + 1) * 10));
		return window;
	}

	@Test
	public void powerSpectrum4() {
		int size = 4;

		System.out.println("Window:");
		PackedCollection<Pair<?>> window = window(size);
		IntStream.range(0, window.getCount()).mapToObj(window::get).forEach(System.out::println);

		Evaluable<PackedCollection<Scalar>> ev = powerSpectrum(size, v(2 * size, 0)).get();

		PackedCollection<Scalar> spectrum = ev.evaluate(window(size));
		IntStream.range(0, spectrum.getCount()).mapToObj(spectrum::get).forEach(System.out::println);
	}

	@Test
	public void powerSpectrum512() {
		int size = 512;

		Evaluable<PackedCollection<Scalar>> ev = powerSpectrum(size, v(2 * size, 0)).get();

		PackedCollection<Scalar> spectrum = ev.evaluate(window(size));
		IntStream.range(0, spectrum.getCount()).mapToObj(spectrum::get).forEach(System.out::println);

		assertEquals(16, spectrum.get(0));
		assertEquals(9396, spectrum.get(8));
		assertEquals(100, spectrum.get(spectrum.getCount() - 1));
	}
}
