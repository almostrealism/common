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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.DefaultScalarBankEvaluable;
import org.almostrealism.algebra.computations.Preemphasize;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DynamicAcceleratedEvaluable;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class PreemphasizeTest implements TestFeatures {
	public static final int SIZE = 25;

	public ScalarBank window() {
		ScalarBank window = new ScalarBank(SIZE);
		IntStream.range(0, 25).forEach(i -> window.set(i, i * 10, 1.0));
		return window;
	}

	@Test
	public void preemphasize() {
		Evaluable<ScalarBank> ev = new Preemphasize(SIZE,
				v(2 * SIZE, 0),
				v(Scalar.class, 1)).get();

		((OperationAdapter) ev).compile();

		ScalarBank b = ev.evaluate(window(), new Scalar(0.1));
		IntStream.range(0, b.getCount()).mapToObj(b::get).forEach(System.out::println);
	}
}
