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
import java.util.stream.IntStream;

public class ExpressionSimplificationTests implements ExpressionFeatures, TestFeatures {
	private LanguageOperations lang = new LanguageOperationsStub();

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
	public void kernelSumQuotient() {
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
	public void kernelModProduct() {
		Expression kernel0 = new KernelIndex();
		Expression result = kernel0.multiply(e(4)).imod(e(8)).imod(e(4));
		System.out.println(Arrays.toString(result.sequence(new KernelIndex(), 4).toArray()));
		Assert.assertTrue(result.isValue(new IndexValues(0)));

		String simple = new DefaultKernelStructureContext(64).simplify(result).getExpression(lang);
		System.out.println(simple);
		Assert.assertEquals("0", simple);
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

	protected void compareSimplifiedSequence(Expression e) {
		compareSequences(e, e.getSimplified());
	}

	protected void compareSequences(Expression a, Expression b) {
		System.out.println(b.getExpression(lang));

		int seqA[] = a.sequence().intValues().toArray();

		IndexSequence s = b.sequence();
		int seqB[] = IntStream.range(0, seqA.length).map(i -> s.valueAt(i).intValue()).toArray();

		System.out.println(Arrays.toString(seqA));
		System.out.println(Arrays.toString(seqB));
		Assert.assertArrayEquals(seqA, seqB);
	}
}
