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
import io.almostrealism.expression.Cast;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that a cast to {@code long} truncates the fractional part when its
 * value is computed by the framework, agreeing with the generated
 * {@code (long)} code and with the sibling {@code int} cast.
 *
 * <p>{@link io.almostrealism.expression.ExpressionArithmetic#toLong()} wraps a
 * floating-point expression in a {@code (long)} {@link Cast}, and the generated
 * source truncates that value toward zero exactly as C does. The framework's own
 * value-computation path ({@link Expression#value(IndexValues)} /
 * {@link Expression#evaluate(Number...)}) is required to agree with the generated
 * code, as the sibling {@code (int)} cast does. A {@code long} cast that reports
 * the un-truncated double instead disagrees with the kernel it compiles to.</p>
 *
 * <p>The dividend used throughout is {@code (kernel index) * 0.5}: a non-constant
 * floating-point expression that equals {@code 1.5} at kernel index {@code 3},
 * which {@code (long)} truncates to {@code 1}.</p>
 */
public class CastLongTruncationTest extends TestSuiteBase implements ExpressionFeatures {

	/** Language operations used to render expressions in messages. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/**
	 * A {@code (long)} cast of a fractional floating-point value must compute the
	 * truncated integer through {@link Expression#value(IndexValues)}, matching
	 * the {@code (long)} truncation in the generated code.
	 */
	@Test(timeout = 30000)
	public void longCastTruncatesInValuePath() {
		Expression<?> half = Product.of(kernel(), new DoubleConstant(0.5));
		Assert.assertTrue("The dividend expression must be floating-point", half.isFP());

		Expression<?> cast = (Expression<?>) half.toLong();

		String rendered = cast.getExpression(lang);
		Assert.assertTrue("A long cast must render a (long) truncation: " + rendered,
				rendered.contains("(long)"));

		IndexValues at3 = new IndexValues().put(kernel(), 3);

		Assert.assertEquals("control: the un-cast dividend is fractional at index 3",
				1.5, half.value(at3).doubleValue(), 0.0);

		Assert.assertEquals("(long) 1.5 must compute to 1, matching the generated code",
				1.0, cast.value(at3).doubleValue(), 0.0);
	}

	/**
	 * A {@code (long)} cast must truncate through {@link Expression#evaluate(Number...)}
	 * too, exactly as the sibling {@code (int)} cast does.
	 */
	@Test(timeout = 30000)
	public void longCastTruncatesInEvaluate() {
		Expression<?> half = Product.of(kernel(), new DoubleConstant(0.5));

		Expression<?> longCast = (Expression<?>) half.toLong();
		Expression<?> intCast = (Expression<?>) half.toInt();

		Assert.assertEquals("(int) 1.5 is the reference and truncates to 1",
				1.0, intCast.evaluate(Double.valueOf(1.5)).doubleValue(), 0.0);

		Assert.assertEquals("(long) 1.5 must evaluate to 1, matching the (int) cast",
				1.0, longCast.evaluate(Double.valueOf(1.5)).doubleValue(), 0.0);
	}
}
