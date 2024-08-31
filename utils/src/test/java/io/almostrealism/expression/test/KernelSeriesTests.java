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
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.DefaultKernelStructureContext;
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
		Expression<?> c = a.add(b);
		Expression e = c.divide(18).multiply(9).imod(9).simplify(new DefaultKernelStructureContext(18));
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

	@Test
	public void productQuotientSumEquals() {
		for (int kernel0 = 0; kernel0 < 1800; kernel0++) {
			// int result = (((((((kernel0 * 8) % (144)) / 8) + (((kernel0 * 8) / 144) * 144)) % (18)) == (0)) ? (1) : (0));
			int result = ((((kernel0 * 8) % (144)) / 8) + (((kernel0 * 8) / 144) * 144)) % (18);
			int simple = kernel0 % 18;
			if (kernel0 % 100 == 0) System.out.println(kernel0 + " " + result);
			Assert.assertEquals(result, simple);
		}

		for (int kernel0 = 0; kernel0 < 100; kernel0++) {
			int global_id = kernel0;
			int result = ((((((((((((((((((((((((global_id * 8) + 1) % (144)) / 8) + ((((global_id * 8) + 1) / 144) * 144) + 18) / 18) * 9) + 4) / 9) * 9) + 4) / 18) * 9) + 4) / 9) * 9) + 4) / 36) * 36) + 4) % (24)) / 12) + 5 + ((((((((((((((((((((((global_id * 8) + 1) % (144)) / 8) + ((((global_id * 8) + 1) / 144) * 144) + 18) / 18) * 9) + 4) / 9) * 9) + 4) / 18) * 9) + 4) / 9) * 9) + 4) / 36) * 36) + 4) / 24) * 24)) % (16));
			System.out.println(kernel0 + " " + result);
		}
	}

	@Test
	public void productModAndQuotientSum() {
		// ((((kernel0 * 8) % (144)) / 8) + (((kernel0 * 8) / 144) * 144)) % (18);
		Expression a = kernel().multiply(8)
				.imod(144)
				.divide(8);
		Expression b = kernel().multiply(8).divide(144).multiply(144);
		Expression c = a.add(b).imod(18);
		validateSeries(c);

//		TODO  This should also succeed
//		Expression e = c.sequence(new KernelIndex(), 36).match(
//				new KernelIndex(), true);
//		Assert.assertEquals("(kernel0) % (18)", e.getExpression(new LanguageOperationsStub()));
		Expression e = c.sequence(new KernelIndex(), 18L).getExpression(
				new KernelIndex(), true);
		Assert.assertEquals("kernel0", e.getExpression(new LanguageOperationsStub()));
	}

	@Test
	public void divideMultiply1() {
		Expression p = kernel()
				.divide(4)
				.multiply(2)
				.imod(5);
		validateSeries(p);
	}

	@Test
	public void divideMultiply2() {
		Expression p = kernel()
				.divide(144)
				.divide(36)
				.multiply(36)
				.imod(24);
		validateSeries(p);
	}

	// @Test
	public void largeSum2() {
		Expression p = kernel().multiply(8)
				.divide(144)
				.multiply(9)
				.add(4)
				.divide(9)
				.multiply(9)
				.add(4)
				.divide(18)
				.multiply(9)
				.add(4)
				.divide(36)
				.multiply(36)
				.imod(24);
		validateSeries(p);
	}

	// @Test
	public void largeSum3() {
		Expression p = kernel().multiply(8).add(1).divide(144)
								.multiply(144).add(18).divide(18)
								.multiply(9).add(4).divide(9)
								.multiply(9).add(4).divide(18)
								.multiply(9).add(4).divide(36)
								.multiply(36)
								.imod(24);
		validateSeries(p);
	}

	// @Test
	public void largeSum4() {
		Expression p = kernel().multiply(8)
				.add(1)
				.imod(144)
				.divide(8)
				.add(kernel().multiply(8).add(1).divide(144).multiply(144).add(18).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(36).multiply(36))
				.imod(24);
		validateSeries(p);
	}

	// @Test
	public void largeSum5() {
		Expression p = kernel().multiply(8)
				.add(1)
				.imod(144)
				.divide(8)
				.add(kernel().multiply(8).add(1).divide(144).multiply(144).add(18).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(36).multiply(36).add(4))
				.imod(24)
				.divide(12);
		validateSeries(p);
	}

	// @Test
	public void largeSum6() {
		// ((((((((((((((((((((((((kernel0 * 8) + 1) % (144)) / 8) + ((((kernel0 * 8) + 1) / 144) * 144) + 18) / 18) * 9) + 4) / 9) * 9) + 4) / 18) * 9) + 4) / 9) * 9) + 4) / 36) * 36) + 4) % (24)) / 12) + 5 + ((((((((((((((((((((((kernel0 * 8) + 1) % (144)) / 8) + ((((kernel0 * 8) + 1) / 144) * 144) + 18) / 18) * 9) + 4) / 9) * 9) + 4) / 18) * 9) + 4) / 9) * 9) + 4) / 36) * 36) + 4) / 24) * 24)) % (16))
		// (((((((((kernel0 * 8) + 1) % (144)) / 8) + ((((((((((((((((((((kernel0 * 8) + 1) / 144) * 144) + 18) / 18) * 9) + 4) / 9) * 9) + 4) / 18) * 9) + 4) / 9) * 9) + 4) / 36) * 36) + 4)) % (24)) / 12) + 5) + (((((((kernel0 * 8) + 1) % (144)) / 8) + ((((((((((((((((((((kernel0 * 8) + 1) / 144) * 144) + 18) / 18) * 9) + 4) / 9) * 9) + 4) / 18) * 9) + 4) / 9) * 9) + 4) / 36) * 36) + 4)) / 24) * 24)) % (16)
		Expression p = kernel().multiply(8)
				.add(1)
				.imod(144)
				.divide(8)
				.add(kernel().multiply(8).add(1).divide(144).multiply(144).add(18).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(36).multiply(36).add(4))
				.imod(24)
				.divide(12)
				.add(5)
				.add(kernel().multiply(8).add(1).imod(144).divide(8).add(kernel().multiply(8).add(1).divide(144).multiply(144).add(18).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(18).multiply(9).add(4).divide(9).multiply(9).add(4).divide(36).multiply(36).add(4)).divide(24).multiply(24))
				.imod(16);
		System.out.println(p.getExpression(new LanguageOperationsStub()));
		validateSeries(p);
	}

	@Test
	public void simpleSum() {
		KernelSeries series = e(0).kernelSeries();
		Assert.assertEquals(1, series.getScale().getAsInt());

		series = kernel().add(0).kernelSeries();
		Assert.assertEquals(1, series.getScale().getAsInt());

		series = kernel().divide(1).multiply(8).add(0).kernelSeries();
	}

	@Test
	public void simpleMod() {
		KernelSeries series = kernel().imod(10).kernelSeries();
		Assert.assertEquals(10, series.getPeriod().getAsInt());
		Assert.assertEquals(1, series.getScale().getAsInt());

		series = kernel().imod(10).divide(5).kernelSeries();
		Assert.assertEquals(10, series.getPeriod().getAsInt());
		Assert.assertEquals(5, series.getScale().getAsInt());

		validateSeries(kernel().imod(10).divide(5));
		validateSeries(kernel().imod(10).divide(5).multiply(2));
		validateSeries(kernel().imod(10).divide(5).imod(2));
		validateSeries(kernel().imod(10).divide(5).imod(3));
		validateSeries(kernel().imod(10).multiply(5).imod(3));
	}

	protected void validateSeries(Expression exp) {
		System.out.println("Expression: " + exp.getExpression(lang));

		KernelSeries series = exp.kernelSeries();
		int period = series.getPeriod().orElseThrow();
		System.out.println("Reported Period: " + period);

		Number[] values = exp.sequence(new KernelIndex(), period * 4L).toArray();

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
