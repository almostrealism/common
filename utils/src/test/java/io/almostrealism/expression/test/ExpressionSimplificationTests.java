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
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.expression.Mod;
import io.almostrealism.kernel.DefaultKernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

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
		Assert.assertEquals("kernel0 % " + 64800L * 64800L, simple);
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
}
