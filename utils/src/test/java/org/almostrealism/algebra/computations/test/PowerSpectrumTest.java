/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.jni.NativePowerSpectrum512;
import org.almostrealism.algebra.computations.PowerSpectrum;
import org.almostrealism.hardware.jni.NativeComputationEvaluable;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class PowerSpectrumTest implements TestFeatures {
	public static final int SIZE = 512;

	public PairBank window() {
		PairBank window = new PairBank(SIZE);
		IntStream.range(0, SIZE).forEach(i -> window.set(i, i * 4, i * 10));
		return window;
	}

	@Test
	public void nativePowerSpectrum512() {
		assert SIZE == 512;

		Evaluable<ScalarBank> ev = new PowerSpectrum(SIZE, v(2 * SIZE, 0)).get();

		((OperationAdapter) ev).compile();
		// System.out.println(((NativeComputationEvaluable) ev).getFunctionDefinition());

		ScalarBank given = ev.evaluate(window());
		IntStream.range(0, given.getCount()).mapToObj(given::get).forEach(System.out::println);

		ev = new NativePowerSpectrum512().get();

		((OperationAdapter) ev).compile();
		System.out.println(((NativeComputationEvaluable) ev).getFunctionDefinition());

		ScalarBank test = ev.evaluate(window());
		IntStream.range(0, test.getCount()).mapToObj(test::get).forEach(System.out::println);

		IntStream.range(0, SIZE).forEach(i -> assertEquals(given.get(i), test.get(i)));
	}
}
