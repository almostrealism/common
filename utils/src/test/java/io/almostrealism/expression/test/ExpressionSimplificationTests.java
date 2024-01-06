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
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IndexValues;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.expression.Mod;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class ExpressionSimplificationTests implements ExpressionFeatures {
	private OpenCLLanguageOperations lang = new OpenCLLanguageOperations(Precision.FP64);

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
		out = new Mod(out, e(5), false);

		System.out.println(out.getSimpleExpression(lang));
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
	public void kernelModProduct() {
		boolean temp = false;

		if (temp) {
			// TODO  Remove - this is just for reference
			int kernel0 = 1;
			int result = (((kernel0 * 4) % (8)) % (4));
			result = (((((kernel0 * 4) % (8)) % (4)) + (-(((kernel0 * 4) % (8)) % (4))) + (((kernel0 * 4) % (8)) / 4) + ((((kernel0 * 4) % (8)) % (4)) * 2) + (((kernel0 * 4) / 8) * 8)) / 2) % (4);
		}

		Expression kernel0 = new KernelIndex();
		Expression result = kernel0.multiply(e(4)).imod(e(8)).imod(e(4));
		System.out.println(Arrays.toString(result.sequence(new KernelIndex(), 4)));
		Assert.assertTrue(result.isKernelValue(new IndexValues()));

		String simple = result.getSimplified(new NoOpKernelStructureContext(64)).getExpression(lang);
		System.out.println(simple);
		Assert.assertEquals("0", simple);
	}
}
