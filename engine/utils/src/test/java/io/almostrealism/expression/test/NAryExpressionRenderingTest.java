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

import io.almostrealism.expression.Difference;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.expression.Sum;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

/**
 * Tests how {@link io.almostrealism.expression.NAryExpression} places parentheses when it
 * renders a chain of operations.
 *
 * <p>A product built by repeated binary multiplication is a left-nested tree as deep as the
 * number of multiplications. Rendering every operand in parentheses emits one nesting level
 * per multiplication, and a native compiler rejects the generated source once that passes its
 * bracket nesting limit (256 by default for clang), so whether such a kernel compiled depended
 * on the toolchain of the host that built it. A leading operand that continues the same
 * left-associative chain needs no parentheses, so the rendered nesting must not grow with the
 * length of the chain, while every operand whose grouping matters keeps its parentheses.</p>
 */
public class NAryExpressionRenderingTest extends TestSuiteBase {
	/** The number of multiplications in the left-nested chain, well past clang's limit of 256. */
	private static final int CHAIN_LENGTH = 500;

	/** The language operations used to render expressions. */
	private final LanguageOperations lang = new LanguageOperationsStub();

	/**
	 * A product formed by {@value #CHAIN_LENGTH} successive binary multiplications renders as a
	 * flat chain with no nested parentheses, though the expression tree itself stays deep.
	 */
	@Test(timeout = 60000)
	public void leftNestedProductChainIsNotNested() {
		Expression<?> x = new StaticReference<>(Double.class, "x");
		Expression<?> product = x;

		for (int i = 0; i < CHAIN_LENGTH; i++) {
			product = Product.of(product, x);
		}

		Assert.assertTrue("the product should remain a deep tree, depth=" + product.treeDepth(),
				product.treeDepth() >= CHAIN_LENGTH);

		String rendered = product.getExpression(lang);
		Assert.assertEquals(String.join(" * ", Collections.nCopies(CHAIN_LENGTH + 1, "x")), rendered);
		Assert.assertEquals(0, maximumNesting(rendered));
	}

	/**
	 * A left-nested chain of additions renders without nesting, as a chain of
	 * multiplications does.
	 */
	@Test(timeout = 60000)
	public void leftNestedSumChainIsNotNested() {
		Expression<?> x = new StaticReference<>(Double.class, "x");
		Expression<?> sum = x;

		for (int i = 0; i < CHAIN_LENGTH; i++) {
			sum = Sum.of(sum, x);
		}

		Assert.assertEquals(0, maximumNesting(sum.getExpression(lang)));
	}

	/**
	 * Parentheses are kept wherever they change the meaning: around a nested operand that is
	 * not the leading one, and around a leading operand with a different operator.
	 */
	@Test(timeout = 60000)
	public void groupingThatMattersKeepsParentheses() {
		Expression<?> a = new StaticReference<>(Double.class, "a");
		Expression<?> b = new StaticReference<>(Double.class, "b");
		Expression<?> c = new StaticReference<>(Double.class, "c");

		Assert.assertEquals("a - (b - c)",
				Difference.of(a, Difference.of(b, c)).getExpression(lang));
		Assert.assertEquals("a - b - c",
				Difference.of(Difference.of(a, b), c).getExpression(lang));
		Assert.assertEquals("(a + b) * c",
				Product.of(Sum.of(a, b), c).getExpression(lang));
	}

	/**
	 * Returns the deepest parenthesis nesting in {@code code}.
	 *
	 * @param code the rendered expression
	 * @return the maximum number of simultaneously open parentheses
	 */
	private static int maximumNesting(String code) {
		int depth = 0;
		int max = 0;

		for (int i = 0; i < code.length(); i++) {
			char ch = code.charAt(i);

			if (ch == '(') {
				depth++;
				max = Math.max(max, depth);
			} else if (ch == ')') {
				depth--;
			}
		}

		return max;
	}
}
