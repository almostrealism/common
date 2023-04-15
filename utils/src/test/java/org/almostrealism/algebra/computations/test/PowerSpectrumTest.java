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
import org.almostrealism.algebra.ScalarBank;
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

		System.out.println("Standard...");
		Evaluable<ScalarBank> ev = powerSpectrumOld(size, v(2 * size, 0)).get();

		ScalarBank spectrum = ev.evaluate(window(size));
		IntStream.range(0, spectrum.getCount()).mapToObj(spectrum::get).forEach(System.out::println);

		System.out.println("Fast...");
		ev = powerSpectrum(size, v(2 * size, 0)).get();

		spectrum = ev.evaluate(window(size));
		IntStream.range(0, spectrum.getCount()).mapToObj(spectrum::get).forEach(System.out::println);
	}

	@Test
	public void powerSpectrum512() {
		int size = 512;

		System.out.println("Standard...");
		Evaluable<ScalarBank> ev = powerSpectrumOld(size, v(2 * size, 0)).get();

		ScalarBank spectrum = ev.evaluate(window(size));
		IntStream.range(0, spectrum.getCount()).mapToObj(spectrum::get).forEach(System.out::println);

		System.out.println("Fast...");
		ev = powerSpectrum(size, v(2 * size, 0)).get();

		ScalarBank spectrumFast = ev.evaluate(window(size));
		IntStream.range(0, spectrumFast.getCount()).mapToObj(spectrumFast::get).forEach(System.out::println);

		IntStream.range(0, spectrumFast.getCount()).forEach(i -> assertEquals(spectrum.get(i), spectrumFast.get(i)));
	}
}
