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

package io.almostrealism.expression.test;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.expression.Mod;
import io.almostrealism.kernel.DefaultKernelStructureContext;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.OptionalLong;
import java.util.stream.IntStream;

public class ExpressionSimplificationTests implements ExpressionFeatures, TestFeatures {
	private LanguageOperations lang = new LanguageOperationsStub();

	@Test
	public void notEqual() {
		Assert.assertFalse(new BooleanConstant(true).equals(new BooleanConstant(false)));
	}

	@Test
	public void modLimit() {
		Assert.assertEquals(25, new IntegerConstant(25).upperBound(null).orElse(-1));
		Assert.assertEquals(100, kernel().withLimit(100).getLimit().orElse(-1));
		Assert.assertEquals(50, kernel().withLimit(100).imod(50).getLimit().orElse(-1));
		Assert.assertEquals(10, kernel().withLimit(100).divide(10).getLimit().orElse(-1));
	}

	@Test
	public void productToInt() {
		Expression a = new IntegerConstant(1);
		Expression b = new IntegerConstant(2);
		Expression c = new IntegerConstant(3);
		Expression out = a.add(b).multiply(c).toInt();

		System.out.println(out.getSimpleExpression(lang));
	}

	@Test
	public void divide() {
		String e = e(1).divide(e(2)).getSimpleExpression(lang);
		System.out.println(e);
		Assert.assertTrue(e.length() < 12);
	}

	@Test
	public void constantSum() {
		// 1 + (- ((2.0 - 0) / 4.0))
		Expression<?> out = e(1).add(e(2.0).subtract(e(0)).divide(e(4.0)).minus());
		System.out.println(out.getExpression(lang));

		out = out.simplify(new NoOpKernelStructureContext());
		Assert.assertEquals("0.5", out.getExpression(lang));
	}

	@Test
	public void castSumDivide1() {
		Expression d = e(1).divide(e(2));
		Expression out = e(25).add(d).toInt();

		System.out.println(out.getSimpleExpression(lang));
		Assert.assertEquals("25", out.getSimpleExpression(lang));
	}

	@Test
	public void castSumDivide2() {
		Expression d = e(1).divide(e(2));
		Expression c = e(3).add(e(4).multiply(e(5)));
		Expression b = c.add(e(2));
		Expression out = b.add(d).toInt();

		System.out.println(out.getSimpleExpression(lang));
		Assert.assertEquals("25", out.getSimpleExpression(lang));
	}

	@Test
	public void modCastSum() {
		Expression d = e(1).divide(e(2)).add(e(2).multiply(e(4)));
		Expression c = e(3).add(e(4).multiply(e(5)));
		Expression b = c.add(e(2));
		Expression a = d.multiply(e(4));
		Expression out = b.add(a).toInt();
		out = Mod.of(out, e(5), false);

		String simple = out.getSimpleExpression(lang);
		log(simple);
	}

	@Test
	public void castFloorDivide() {
		Expression exp = e(0.0).divide(e(4.0)).floor()
				.multiply(e(4)).add(e(0.0).toInt()
						.mod(e(4), false)).toInt();
		System.out.println(exp.getExpression(lang));
		Assert.assertEquals("0", exp.getSimpleExpression(lang));
	}

	@Test
	public void kernelProductMod1() {
		Expression<?> e =
				kernel().multiply(64800)
				.add(kernel().imod(64800))
				.imod(64800L * 64800L);
		String simple = e.getSimplified().getExpression(lang);
		log(simple);
		// Assert.assertEquals("kernel0 % " + 64800L * 64800L, simple);
		Assert.assertEquals("(kernel0 % 64800) * 64801", simple);
	}

	@Test
	public void kernelQuotient1() {
		Expression e = kernel().withLimit(10).divide(10);
		e = e.getSimplified();

		log(e.getExpression(lang));
		Assert.assertEquals("0", e.getExpression(lang));
	}

	@Test
	public void kernelQuotient2() {
		Expression e = kernel().withLimit(100).divide(10).divide(10);
		e = e.getSimplified();

		log(e.getExpression(lang));
		Assert.assertEquals("0", e.getExpression(lang));
	}

	@Test
	public void kernelSumQuotient1() {
		int n = 4;

		Expression<?> e =
				kernel().multiply(n)
						.add(kernel().imod(n))
						.divide(n * n);
		String simple = e.getSimplified().getExpression(lang);
		log(simple);
		Assert.assertEquals("kernel0 / " + n, simple);
	}

	@Test
	public void kernelSumQuotient2() {
		Expression e = kernel().divide(5).multiply(5).add(1).divide(5);
		log(e.getExpression(lang));
		Assert.assertEquals("kernel0 / 5", e.getExpression(lang));
	}

	@Test
	public void kernelSumQuotient3() {
		// (((kernel0 + ((kernel0 / 12) * -12)) / 3) + 3) * 10
		KernelIndex idx = kernel().withLimit(24);
		Expression e = idx.add(idx.divide(12).multiply(-12)).divide(3);
		e = e.getSimplified();
		log(e.getExpression(lang));
		Assert.assertFalse(e instanceof Constant);
		// Assert.assertEquals("(kernel0 + ((kernel0 / 12) * -12)) / 3", e.getExpression(lang));
	}

	@Test
	public void kernelModQuotient1() {
		KernelIndex kernel = kernel().withLimit(2100);

		// (((((kernel0 / 210) * 10) % 100) / 10) * 10) + 7
		Expression e = kernel
				.divide(210).multiply(10)
				.imod(100).divide(10)
				.multiply(10).add(7);
		Assert.assertEquals("((kernel0 / 210) * 10) + 7", e.getSimpleExpression(lang));
	}

	@Test
	public void kernelModProduct1() {
		Expression kernel0 = new KernelIndex();
		Expression result = kernel0.multiply(e(4)).imod(e(8)).imod(e(4));
		System.out.println(Arrays.toString(result.sequence(new KernelIndex(), 4).toArray()));
		Assert.assertTrue(result.isValue(new IndexValues(0)));

		String simple = new DefaultKernelStructureContext(64).simplify(result).getExpression(lang);
		log(simple);
		Assert.assertEquals("0", simple);
	}

	@Test
	public void kernelModProduct2() {
		Expression e = kernel().imod(16).multiply(17).divide(16);
		e = e.getSimplified();
		log(e.getExpression(lang));
		Assert.assertEquals("kernel0 % 16", e.getExpression(lang));
	}

	@Test
	public void kernelModProduct3() {
		// (((kernel0 * 64) + _52_i) % 64) * 72

		KernelIndex kernel = kernel().withLimit(288);
		DefaultIndex child = new DefaultIndex("_52_i", 64);
		Expression e = kernel.multiply(64).add(child).imod(64).multiply(72);

		log(e.getExpression(lang));
		Assert.assertFalse(e.isPossiblyNegative());
	}

	@Test
	public void kernelModProduct4() {
		int n = 64;
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(256 * n + 1);
		KernelIndex kernel = kernel(ctx);
		Expression e = kernel.imod(256).add(985*n)
				.divide(18 * n);
		log(e.getExpression(lang));

		if (Quotient.enableLowerBoundedNumeratorReplace) {
			Assert.assertEquals("54", e.getExpression(lang));
		} else {
			e = ctx.getSeriesProvider().getSeries(e);
			log(e.getExpression(lang));
			Assert.assertEquals("54", e.getExpression(lang));
		}
	}

	@Test
	public void kernelModProduct5() {
		int n = 64;
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(256 * n + 1);
		KernelIndex kernel = kernel(ctx);
		Expression e = kernel.imod(256).add(985*n)
				.imod(324 * n)
				.divide(18 * n);
		log(e.getExpression(lang));

		if (Quotient.enableLowerBoundedNumeratorReplace) {
			Assert.assertEquals("0", e.getExpression(lang));
		} else {
			e = ctx.getSeriesProvider().getSeries(e);
			log(e.getExpression(lang));
		}
	}

	@Test
	public void modSumSeq1() {
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx.imod(2));
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void modSumSeq2() {
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx).imod(4);
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void modSumSeq3() {
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.add(idx.imod(2)).imod(4);
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void modSumSeq5() {
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx.imod(2)).imod(4);
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void modSumSeq6() {
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx.imod(2)).imod(4).divide(2);
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void modSumSeq7() {
		// (((((ind0 * 2) + (ind0 % 2)) % 4) / 2) % 2)
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx.imod(2)).imod(4).divide(2).imod(2);
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void modSumSeq8() {
		// (((((ind0 * 4) + (ind0 % 4)) % 4)
		DefaultIndex idx = new DefaultIndex("ind0", 16);
		Expression<?> e = idx.multiply(4).add(idx.imod(4)).imod(4);
		System.out.println(e.getExpression(lang));
		compareSimplifiedSequence(e);

		Assert.assertEquals("ind0 % 4", e.getSimpleExpression(lang));
	}

	@Test
	public void modSumSeq9() {
		// ((ind0 % 1024) + 1024) % 256
		DefaultIndex idx = new DefaultIndex("ind0", 2048);
		Expression<?> e = idx.imod(1024).add(1024).imod(256);
		e.simplify();

		log(e.getExpression(lang));
		compareSimplifiedSequence(e);

		Assert.assertEquals("ind0 % 256", e.getSimpleExpression(lang));
	}

	@Test
	public void modSum1() {
		// (((((ind0 * 2) + (ind0 % 2)) % 4) / 2) % 2)
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx.imod(2)).imod(4).divide(2).imod(2);
		System.out.println(e.getExpression(lang));

		IndexSequence seq = e.sequence(idx, 4, 4);
		System.out.println(Arrays.toString(seq.toArray()));

		e = e.getSimplified();
		System.out.println(e.getExpression(lang));

		seq = e.sequence(idx, 4, 4);
		System.out.println(Arrays.toString(seq.intValues().limit(4).toArray()));
	}

	@Test
	public void equals1() {
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.imod(4).eq(idx.imod(8));
		System.out.println(e.getExpression(lang));

		Expression se = new DefaultKernelStructureContext().getSeriesProvider().getSeries(e);
		System.out.println(se.getExpression(lang));
		Assert.assertEquals("true", se.getExpression(lang));

		e = e.getSimplified();
		System.out.println(e.getExpression(lang));
		Assert.assertEquals("true", e.getExpression(lang));
	}

	@Test
	public void equals2() {
		// (((((ind0 * 2) + (ind0 % 2)) % 4) / 2) % 2) == (((ind0 * 2) + (ind0 % 2)) % 2)
		DefaultIndex idx = new DefaultIndex("ind0", 4);
		Expression<?> e = idx.multiply(2).add(idx.imod(2)).imod(4).divide(2).imod(2)
							.eq(idx.multiply(2).add(idx.imod(2)).imod(2));
		System.out.println(e.getExpression(lang));

		IndexSequence seq = e.sequence(idx, 4, 4);
		System.out.println(Arrays.toString(seq.toArray()));
		Assert.assertEquals("1", seq.getExpression(idx).getExpression(lang));

		e = e.getSimplified(new DefaultKernelStructureContext());
		System.out.println(e.getExpression(lang));

		seq = e.sequence(idx, 4, 4);
		System.out.println(Arrays.toString(seq.toArray()));
		Assert.assertEquals("1", seq.getExpression(idx).getExpression(lang));

		Assert.assertEquals("true", e.getExpression(lang));
	}

	@Test
	public void sequenceMax1() {
		Expression e = kernel().withLimit(3).multiply(Integer.MAX_VALUE);
		IndexSequence seq = e.sequence();
		System.out.println(Arrays.toString(seq.toArray()));
		Assert.assertEquals(2L * Integer.MAX_VALUE, seq.toArray()[2]);
	}

	@Test
	public void sequenceMax2() {
		Expression e = kernel().withLimit(3).multiply(Integer.MAX_VALUE);
		IndexSequence seq = e.sequence();
		System.out.println(Arrays.toString(seq.toArray()));
		Assert.assertEquals(2L * Integer.MAX_VALUE, seq.toArray()[2]);
	}

	@Test
	public void sequenceMax3() {
		int n = 1600;


		Expression e = kernel().withLimit(n*n).multiply(n).add(kernel().imod(n));
		long o = e.value(new IndexValues().put(kernel(), 1342178)).longValue();
		System.out.println(o);
		Assert.assertTrue(o > 0);

		IndexSequence seq = e.sequence();

		OptionalLong negative = e.sequence().longStream().filter(l -> l < 0).findAny();
		if (negative.isPresent()) {
			warn("unexpected value -> " + negative.getAsLong());
			Number v[] = seq.toArray();
			int index = IntStream.range(0, v.length).filter(i -> v[i].longValue() < 0).findFirst().orElse(-1);
			Assert.fail("negative value at index " + index);
		}
	}

	@Test
	public void sequenceMax4() {
		int n = 1400;

		Expression e = kernel().withLimit(n*n).multiply(n).add(kernel().imod(n)).imod(n*n);
		long o = e.value(new IndexValues().put(kernel(), 1342178)).longValue();
		System.out.println(o);
		Assert.assertTrue(o > 0);

		IndexSequence seq = e.sequence();

		OptionalLong negative = seq.longStream().filter(l -> l < 0).findAny();
		if (negative.isPresent()) {
			warn("unexpected value -> " + negative.getAsLong());
			Number v[] = seq.toArray();
			int index = IntStream.range(0, v.length).filter(i -> v[i].longValue() < 0).findFirst().orElse(-1);
			Assert.fail("negative value at index " + index);
		}
	}

	@Test
	public void sumProductQuotient1() {
		// (
		// 		((v92[0] + (- ((v92[0] + v92[1]) / 2.0))) * (v92[0] + (- ((v92[0] + v92[1]) / 2.0)))) +
		// 		((v92[1] + (- ((v92[0] + v92[1]) / 2.0))) * (v92[1] + (- ((v92[0] + v92[1]) / 2.0))))
		// ) / 2.0
		ArrayVariable v92 = new ArrayVariable(null, Double.class, "v92", e(4));
		Expression ref0 = v92.valueAt(0);
		Expression ref1 = v92.valueAt(1);
		Expression e = ref0.add(ref0.add(ref1).divide(2.0).minus()).multiply(ref0.add(ref0.add(ref1).divide(2.0).minus()))
				.add(ref1.add(ref0.add(ref1).divide(2.0).minus()).multiply(ref1.add(ref0.add(ref1).divide(2.0).minus())))
				.divide(2.0);
		System.out.println(e.getExpression(lang));
		System.out.println(e.getSimplified().getExpression(lang));
	}

	@Test
	public void kernelMultiSum1() {
		Expression kernel = kernel().withLimit(2100);

		// kernel0 + ((kernel0 / 210) * -210) + ((kernel0 / 210) * 210)
		Expression e = Sum.of(kernel,
				kernel.divide(210).multiply(-210),
				kernel.divide(210).multiply(210));
		Assert.assertEquals("kernel0", e.getExpression(lang));
	}

	@Test
	public void kernelConditional1() {
		// ((0 == (kernel0 / 3)) ? 1 : 0)
		Expression e = e(0).eq(kernel().divide(3)).conditional(e(1), e(0));
		System.out.println(e.getExpression(lang));
		System.out.println(Arrays.toString(e.sequence(9).toArray()));

		System.out.println(new DefaultKernelStructureContext(9).getSeriesProvider().getSeries(e).getExpression(lang));
	}

	@Test
	public void kernelSumMod1() {
		// (((((kernel0 % 3) * 3) + (kernel0 / 3) + ((kernel0 / 9) * 9)) / 3) % 3)
		Expression e = kernel().imod(3).multiply(3).add(kernel().divide(3)).add(kernel().divide(9).multiply(9)).divide(3).imod(3);
		System.out.println(e.getExpression(lang));
		System.out.println(Arrays.toString(e.sequence(9).toArray()));

		e = new DefaultKernelStructureContext(9).getSeriesProvider().getSeries(e);
		System.out.println(e.getExpression(lang));
		Assert.assertEquals("kernel0 % 3", e.getSimpleExpression(lang));
	}

	@Test
	public void kernelSumMod2() {
		if (testDepth < 1) return;

//		int n = 4;
		int n = 1400;

		// (((kernel0 * n) + (kernel0 % n)) % n^2) / n
		Expression e = kernel().withLimit(n*n).multiply(n).add(kernel().imod(n)).imod(n * n).divide(n);
		compareSimplifiedSequence(e);

		System.out.println(e.getExpression(lang));
	}

	@Test
	public void kernelSumMod3() {
		int n = 1300;

		// ((kernel0 * 1300) + (kernel0 % 1300)) % 1300
		Expression e = kernel().withLimit(n*n).multiply(n).add(kernel().withLimit(n*n).imod(n)).imod(n);
		log(e.getExpression(lang));

		Assert.assertTrue(e.value(new IndexValues().put(kernel(), 1651910)).doubleValue() > 0);
		compareSimplifiedSequence(e);
	}

	@Test
	public void kernelSumMod4() {
		if (testDepth < 1) return;

		int n = 1300;

		Expression idx = kernel().withLimit(n*n);

		// (((((kernel0 * n) + (kernel0 % n)) / n^2) * n) + (((kernel0 * n) + (kernel0 % n)) % n)) % n^2
		Expression e = idx.multiply(n).add(idx.imod(n)).divide(n * n).multiply(n)
				.add(idx.multiply(n).add(idx.imod(n)).imod(n)).imod(n * n);
		log(e.getExpression(lang));
		compareSimplifiedSequence(e);
	}

	@Test
	public void kernelSumMod5() {
		// (((((kernel0 * 64) + _52_i) / 64) * 4608) +
		// ((((kernel0 * 64) + _52_i) % 64) * 72)) % 4608
		KernelIndex kernel = kernel().withLimit(288);
		DefaultIndex child = new DefaultIndex("_52_i", 64);

		Expression e = kernel.multiply(64).add(child).divide(64).multiply(4608)
				.add(kernel.multiply(64).add(child).imod(64).multiply(72)).imod(4608);

		log(e.getExpression(lang));
		Assert.assertFalse(e.isPossiblyNegative());
	}

	@Test
	public void kernelSumMod6() {
		//((((((kernel0 * 64) + _52_i) / 64) * 4608) +
		// ((((((kernel0 * 64) + _52_i) / 64) * 4608) +
		// ((((kernel0 * 64) + _52_i) % 64) * 72)) % 4608)) % 4608) * 4609

		KernelIndex kernel = kernel().withLimit(288);
		DefaultIndex child = new DefaultIndex("_52_i", 64);
		Expression e = kernel.multiply(64).add(child).divide(64).multiply(4608)
				.add(kernel.multiply(64).add(child).divide(64).multiply(4608)
						.add(kernel.multiply(64).add(child).imod(64)
								.multiply(72)).imod(4608)).imod(4608).multiply(4609);

		log(e.getExpression(lang));
		Assert.assertFalse(e.isPossiblyNegative());
	}

	@Test
	public void kernelSumMod7() {
		KernelIndex kernel = kernel().withLimit(441000);

		// ((((kernel0 % 2100) * 2100) % 4410000) / 2100) * -2100
		Expression e =
				kernel.imod(2100).multiply(2100)
						.imod(4410000).divide(2100)
						.multiply(-2100);

		Assert.assertEquals("(kernel0 % 2100) * -2100", e.getSimpleExpression(lang));
	}

	@Test
	public void sumProductQuotient2() {
		// kernel0 + ((kernel0 / 256) * -256)
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(82944);
		KernelIndex kernel = kernel(ctx);
		Expression<?> e = kernel.add(kernel.divide(256).multiply(-256));
		log(e.getExpression(lang));

		Assert.assertEquals("kernel0 % 256", e.getExpression(lang));
	}

	@Test
	public void sumProductQuotient3() {
		// (kernel0 + ((kernel0 / 256) * -256)) / 256
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(82944);
		KernelIndex kernel = kernel(ctx);
		Expression<?> e = kernel.add(kernel.divide(256).multiply(-256)).divide(256);
		log(e.getExpression(lang));

		Assert.assertEquals("0", e.getExpression(lang));
	}

	@Test
	public void kernelMultiSum2() {
		// ((kernel0 / 256) * 400) + (((kernel0 + ((kernel0 / 256) * -256)) / 256) * 400)
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(82944);
		KernelIndex kernel = kernel(ctx);
		Expression<?> e = kernel.divide(256).multiply(400)
				.add(kernel.add(kernel.divide(256).multiply(-256))
						.divide(256).multiply(400));
		log(e.getExpression(lang));

		Assert.assertEquals("(kernel0 / 256) * 400", e.getExpression(lang));
	}

	@Test
	public void kernelSumMod8() {
		// (((kernel0 / 256) * 400) + (((kernel0 + ((kernel0 / 256) * -256)) / 256) * 400)) % 129600
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(82944);
		KernelIndex kernel = kernel(ctx);
		Expression<?> e = kernel.divide(256).multiply(400)
				.add(kernel.add(kernel.divide(256).multiply(-256))
						.divide(256).multiply(400)).imod(129600);
		log(e.getExpression(lang));

		Assert.assertEquals("(kernel0 / 256) * 400", e.getExpression(lang));
	}

	@Test
	public void kernelSumMod9() {
		// (((kernel0 % 1024) / 256) * 256) + (((kernel0 % 256) / 16) * 16)
		int n = 2;
		int m = 2;
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(4624 * m * n);
		KernelIndex kernel = kernel(ctx);
		Expression<?> e = kernel.imod(4 * m * n).divide(m * n).multiply(m * n)
				.add(kernel.imod(m * n).divide(n).multiply(n));
		log(e.getExpression(lang));

		if (!Quotient.enableArithmeticGenerator) {
			e = ctx.getSeriesProvider().getSeries(e);
			log(e.getExpression(lang));
		}

		Assert.assertEquals("((kernel0 % " + (4 * m * n) + ") / " + n + ") * " + n, e.getExpression(lang));
	}

	@Test
	public void kernelSumMod10() {
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(1183744);
		KernelIndex kernel = kernel(ctx);
		Expression<?> e = kernel.divide(1024)
				.add(kernel.divide(295936).multiply(-289))
				.add(kernel.imod(295936).divide(1024).multiply(1).divide(17).multiply(-17));

		log(e.getExpression(lang));

		e = ctx.getSeriesProvider().getSeries(e);
		String result = e.getExpression(lang);
		log(result);

		// Either result is acceptable (see ArithmeticGenerator)
		if (!"((kernel0 % 17408) / 1024) * 1".equals(result)) {
			Assert.assertEquals("(kernel0 % 17408) / 1024", e.getExpression(lang));
		}
	}

	@Test
	public void kernelConditionalSum1() {
//		int a = ((0 == (kernel0 / 3)) ? 1 : 0);
//		int b = ((1 == (kernel0 / 3)) ? 1 : 0);
//		int c = ((2 == (kernel0 / 3)) ? 1 : 0);
//		double r = (((a + b + c) * 3.0) / 9.0);
		Expression e = e(0).eq(kernel().divide(3)).conditional(e(1), e(0))
				.add(e(1).eq(kernel().divide(3)).conditional(e(1), e(0)))
				.add(e(2).eq(kernel().divide(3)).conditional(e(1), e(0)))
				.multiply(3.0).divide(9.0);
		System.out.println(e.getExpression(lang));
		System.out.println(Arrays.toString(e.sequence(9).toArray()));

		e = new DefaultKernelStructureContext(9).getSeriesProvider().getSeries(e);

		System.out.println(e.getExpression(lang));
		Assert.assertEquals(String.valueOf(1.0 / 3.0), e.getSimpleExpression(lang));
	}

	@Test
	public void kernelConditionalSum2() {
		// (((((((kernel0 % 3) * 3) + (kernel0 / 3) + ((kernel0 / 9) * 9)) / 3) % 3) == (kernel0 / 3)) ? 1 : 0)
		Expression e = kernel().imod(3).multiply(3)
				.add(kernel().divide(3))
				.add(kernel().divide(9).multiply(9))
				.divide(3).imod(3)
				.eq(kernel().divide(3)).conditional(e(1), e(0));
		System.out.println(e.getExpression(lang));

		e = e.getSimplified();
		System.out.println(e.getExpression(lang));
		System.out.println(Arrays.toString(e.sequence(9).toArray()));

		e = e.getSimplified(new DefaultKernelStructureContext(9));
		Assert.assertEquals("((kernel0 % 3) == (kernel0 / 3)) ? 1 : 0", e.getExpression(lang));
	}

	@Test
	public void redundantQuotientProduct1() {
		// ((((((kernel0 % 20) / 5) * 5) + 1) / 5) * 5) % 20
		Expression e = kernel().withLimit(400)
						.imod(20).divide(5).multiply(5).add(1)
						.divide(5).multiply(5)
						.imod(20);
		System.out.println(e.getExpression(lang));
		// Assert.assertEquals("(((kernel0 % 20) / 5) * 5) % 20", e.getExpression(lang));
		Assert.assertEquals("((kernel0 % 20) / 5) * 5", e.getExpression(lang));
	}

	protected void compareSimplifiedSequence(Expression e) {
		compareSequences(e, e.getSimplified());
	}

	protected void compareSequences(Expression a, Expression b) {
		System.out.println(b.getExpression(lang));

		long seqA[] = a.sequence().longValues().toArray();

		IndexSequence s = b.sequence();
		long seqB[] = IntStream.range(0, seqA.length).mapToLong(i -> s.valueAt(i).longValue()).toArray();

		if (seqA.length < 100) {
			System.out.println(Arrays.toString(seqA));
			System.out.println(Arrays.toString(seqB));
		}

		Assert.assertArrayEquals(seqA, seqB);
	}
}
