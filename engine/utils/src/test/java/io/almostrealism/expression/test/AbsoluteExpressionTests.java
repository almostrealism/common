/*
 * Copyright 2026 Michael Murray
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
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import org.almostrealism.hardware.cl.OpenCLLanguageOperations;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Pins that {@link Absolute} never emits an integer argument to the target language's
 * absolute value function. OpenCL C's {@code fabs} has only floating-point overloads, so a
 * kernel containing {@code fabs(0)} fails to compile as an ambiguous call.
 */
// TODO(review): near-duplicate of org.almostrealism.expression.test.AbsoluteExpressionTest; consider merging these methods into it.
public class AbsoluteExpressionTests extends TestSuiteBase implements ExpressionFeatures {

	/** A constant integer operand folds to a constant holding its absolute value. */
	@Test(timeout = 30000)
	public void constantOperandFolds() {
		Expression<Double> zero = Absolute.of(new IntegerConstant(0));
		Assert.assertTrue(zero.doubleValue().isPresent());
		Assert.assertEquals(0.0, zero.doubleValue().getAsDouble(), 0.0);

		Expression<Double> three = Absolute.of(new IntegerConstant(-3));
		Assert.assertTrue(three.doubleValue().isPresent());
		Assert.assertEquals(3.0, three.doubleValue().getAsDouble(), 0.0);
	}

	/** A non-constant integer operand is widened, so the emitted call has an FP argument. */
	@Test(timeout = 30000)
	public void integerOperandIsWidened() {
		Expression<Double> abs = Absolute.of(kernel());
		Assert.assertFalse(abs.doubleValue().isPresent());
		Assert.assertTrue(abs.getChildren().get(0).isFP());
	}

	/**
	 * Recreating an {@link Absolute} around a child that simplification reduced to an integer
	 * constant folds instead of rendering {@code fabs(0)} for OpenCL.
	 */
	@Test(timeout = 30000)
	public void recreateWithIntegerConstantFolds() {
		Absolute abs = new Absolute(kernel().toDouble());
		Expression<Double> recreated = abs.recreate(List.of(new IntegerConstant(0)));
		Assert.assertEquals(0.0, recreated.doubleValue().getAsDouble(), 0.0);

		String source = recreated.getExpression(new OpenCLLanguageOperations(Precision.FP32));
		Assert.assertFalse(source, source.contains("fabs"));
	}
}
