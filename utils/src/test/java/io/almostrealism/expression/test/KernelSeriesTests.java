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

package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.junit.Assert;
import org.junit.Test;

public class KernelSeriesTests implements ExpressionFeatures {
	private static LanguageOperations lang = new LanguageOperationsStub();

	@Test
	public void quotientMod1() {
		validateSeries(kernel().divide(5).imod(3));
	}

	@Test
	public void productMod1() {
		validateSeries(kernel().multiply(e(0.5)).mod(e(3.0)));
	}

	@Test
	public void productQuotientMod1() {
		validateSeries(kernel().multiply(2).divide(5).imod(3));
	}

	@Test
	public void productQuotientMod2() {
		validateSeries(kernel().multiply(e(0.5)).divide(6).toInt().imod(3));
	}

	@Test
	public void productModSum1() {
		Expression a = kernel().multiply(2).divide(5).imod(3);
		Expression b = kernel().multiply(5).divide(8).imod(7);
		validateSeries(a.add(b));
	}

	// (((((((kernel0 * 4) / 4) * 4) / 12) * 4) / 4) * 4) % (16)
	@Test
	public void repeatedQuotientProduct() {
		Expression p = kernel().multiply(4)
							.divide(4)
							.multiply(4)
							.divide(12)
							.multiply(4)
							.divide(4);
		Assert.assertEquals(3, p.kernelSeries().getScale().getAsInt());

		Expression a = kernel().multiply(4).divide(4).multiply(4)
				.divide(12).multiply(4).divide(4).multiply(4)
				.imod(16);
		validateSeries(a);
	}

	// (((((kernel0 * 8) % (144)) / 8) + (((kernel0 * 8) / 144) * 144)) / 18) * 9
	@Test
	public void productQuotientSum() {
		Expression a = kernel().multiply(8)
				.imod(144)
				.divide(8);
		a.kernelSeries();

		Expression b = kernel().multiply(8).divide(144).multiply(144);
		Expression c = a.add(b).divide(18).multiply(9).imod(9);

		Expression p = kernel().multiply(8)
				.imod(144)
				.divide(8)
				.add(kernel().multiply(8).divide(144).multiply(144))
				.divide(18)
				.multiply(9)
				.imod(9);

		validateSeries(p);
	}


	// (((((((((((kernel0 * 8) % (144)) / 8) + (((kernel0 * 8) / 144) * 144)) / 18) * 9) / 9) * 9) / 18) * 9) / 9) * 9
	@Test
	public void productQuotientSumSimplify() {
		Expression a = kernel().multiply(8)
				.imod(144)
				.divide(8);
		a.kernelSeries();

		Expression b = kernel().multiply(8).divide(144).multiply(144);
		Expression c = a.add(b);
		Expression e = c.divide(18).multiply(9).imod(9).getSimplified();
		Assert.assertTrue(e instanceof IntegerConstant);
		Assert.assertEquals(0, e.intValue().getAsInt());

		Expression p = kernel().multiply(8)
				.imod(144)
				.divide(8)
				.add(kernel().multiply(8).divide(144).multiply(144))
				.divide(18)
				.multiply(9)
				.divide(9)
				.multiply(9)
				.divide(18)
				.multiply(9)
				.divide(9)
				.multiply(9)
				.imod(36);

		validateSeries(p);
	}

	protected void validateSeries(Expression exp) {
		System.out.println("Expression: " + exp.getExpression(lang));

		KernelSeries series = exp.kernelSeries();
		int period = series.getPeriod().orElseThrow();
		System.out.println("Reported Period: " + period);

		Number[] values = exp.kernelSeq(period * 4);

		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < period; j++) {
				System.out.print(values[i * period + j] + " ");
			}

			System.out.println();
		}

		for (int i = 0; i < period; i++) {
			Assert.assertEquals(values[i], values[i + period]);
			Assert.assertEquals(values[i], values[i + 2 * period]);
			Assert.assertEquals(values[i], values[i + 3 * period]);
		}
	}
}
