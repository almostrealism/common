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

package org.almostrealism.color.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.computations.GreaterThanRGB;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ColorMathTest implements TestFeatures, RGBFeatures {
	@Test
	public void fixedSum() {
		CLOperator.verboseLog(() -> {
			Producer<RGB> p1 = black();
			Producer<RGB> p2 = white();
			Producer<RGB> sum = add(p1, p2);

			PackedCollection<?> result = sum.get().evaluate();
			assertEquals(1.0, result.toDouble(0));
			assertEquals(1.0, result.toDouble(1));
			assertEquals(1.0, result.toDouble(2));
		});
	}

	@Test
	public void greaterThan() {
		CLOperator.verboseLog(() -> {
			Producer<Scalar> arg0 = v(Scalar.shape(), 0);
			Producer<RGB> arg1 = v(RGB.shape(), 1);

			GreaterThanRGB greater = new GreaterThanRGB(arg0, scalar(0.0), arg1, black());
			RGB result = greater.get().evaluate(new Scalar(0.1), new RGB(0.0, 1.0, 0.0));
			assertEquals(0.0, result.getRed());
			assertEquals(1.0, result.getGreen());
			assertEquals(0.0, result.getBlue());
		});
	}

	@Test
	public void greaterThanKernel() {
		HardwareOperator.verboseLog(() -> {
			Producer<Scalar> arg0 = v(Scalar.shape(), 0);

			PackedCollection<RGB> result = RGB.bank(5);
			PackedCollection<Scalar> input = Scalar.scalarBank(5);
			input.set(0, 0.0);
			input.set(1, -1.0);
			input.set(2, 1.0);
			input.set(3, -0.1);
			input.set(4, 0.1);

			GreaterThanRGB greater = new GreaterThanRGB(arg0, scalar(0.0), white(), black());
			greater.get().into(result).evaluate(input);
			assertEquals(0.0, result.get(0).getGreen());
			assertEquals(0.0, result.get(1).getGreen());
			assertEquals(1.0, result.get(2).getGreen());
			assertEquals(0.0, result.get(3).getGreen());
			assertEquals(1.0, result.get(4).getGreen());
		});
	}
}
