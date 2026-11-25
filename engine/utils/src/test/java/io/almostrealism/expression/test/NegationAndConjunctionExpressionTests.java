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
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Conjunction;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Negation;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

/**
 * Pins the arithmetic negation ({@link Minus}), boolean negation ({@link Negation}), and
 * boolean conjunction ({@link Conjunction}) expressions.
 *
 * <p>These assert the factory reductions each performs — constant folding, double-negation
 * elimination, distribution of arithmetic negation into a product, and the pruning and
 * collapsing a conjunction applies to constant operands — alongside the host evaluation that
 * must match the operator rendered into generated code.</p>
 */
public class NegationAndConjunctionExpressionTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions to source. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/** A boolean reference whose value is unknown at build time, so conjunctions do not fold it. */
	private Expression<Boolean> flag(String name) {
		return new StaticReference<>(Boolean.class, name);
	}

	/** {@link Minus#of} negates a constant and leaves zero unchanged rather than emitting {@code -0}. */
	@Test(timeout = 30000)
	public void minusFoldsConstants() {
		Assert.assertEquals(-5, Minus.of(e(5)).intValue().getAsInt());
		Assert.assertEquals(e(0), Minus.of(e(0)));
	}

	/** Negating a negation returns the inner operand, cancelling the double negation. */
	@Test(timeout = 30000)
	public void minusCancelsDoubleNegation() {
		Expression<Integer> index = kernel();
		Expression<?> once = Minus.of(index);
		Assert.assertTrue(once instanceof Minus);
		Assert.assertEquals(index, Minus.of(once));
	}

	/** Negating a product with a constant factor distributes the negation onto that factor. */
	@Test(timeout = 30000)
	public void minusDistributesOverProduct() {
		Expression<Double> x = new StaticReference<>(Double.class, "x");
		Expression<?> negated = Minus.of(Product.of(e(3), x));
		Assert.assertEquals(Product.of(e(-3), x), negated);
	}

	/** A {@link Minus} node negates its operand under point evaluation and reports it as possibly negative. */
	@Test(timeout = 30000)
	public void minusNodeEvaluatesAndBounds() {
		Expression<?> node = Minus.of(kernel().imod(4));
		Assert.assertTrue(node instanceof Minus);
		Assert.assertEquals(-5.0, node.evaluate(5.0).doubleValue(), 0.0);
		Assert.assertTrue(node.isPossiblyNegative());
		Assert.assertEquals(0, node.upperBound(null).orElseThrow());
		Assert.assertEquals(-5, Minus.of(kernel()).value(new IndexValues(5)).intValue());
	}

	/** {@link Negation#of} folds a boolean constant to its complement. */
	@Test(timeout = 30000)
	public void negationFoldsConstants() {
		Assert.assertEquals(Optional.of(false), Negation.of(new BooleanConstant(true)).booleanValue());
		Assert.assertEquals(Optional.of(true), Negation.of(new BooleanConstant(false)).booleanValue());
	}

	/** Negating a negation returns the inner boolean operand. */
	@Test(timeout = 30000)
	public void negationCancelsDoubleNegation() {
		Expression<Boolean> guard = kernel().lessThan(e(3));
		Expression<?> once = Negation.of(guard);
		Assert.assertTrue(once instanceof Negation);
		Assert.assertEquals(guard, Negation.of(once));
	}

	/** A {@link Negation} node renders with a {@code !} and evaluates as a logical complement. */
	@Test(timeout = 30000)
	public void negationNodeRendersAndEvaluates() {
		Expression<?> node = Negation.of(kernel().lessThan(e(3)));
		Assert.assertTrue(node.getExpression(lang).contains("!"));
		Assert.assertEquals(1, node.evaluate(0.0).intValue());
		Assert.assertEquals(0, node.evaluate(1.0).intValue());
	}

	/** A conjunction prunes constant-true operands, leaving a single remaining operand unwrapped. */
	@Test(timeout = 30000)
	public void conjunctionPrunesTrueOperands() {
		Expression<Boolean> a = flag("a");
		Assert.assertEquals(a, Conjunction.of(new BooleanConstant(true), a));
		Assert.assertEquals(Optional.of(true),
				Conjunction.of(new BooleanConstant(true), new BooleanConstant(true)).booleanValue());
	}

	/** A conjunction with any statically-false operand collapses to {@code false}. */
	@Test(timeout = 30000)
	public void conjunctionCollapsesOnFalse() {
		Expression<Boolean> collapsed = Conjunction.of(new BooleanConstant(false), flag("a"));
		Assert.assertTrue(collapsed instanceof BooleanConstant);
		Assert.assertEquals(Optional.of(false), collapsed.booleanValue());
	}

	/** A conjunction of two unknown operands evaluates to true only when both are non-zero. */
	@Test(timeout = 30000)
	public void conjunctionEvaluatesLogicalAnd() {
		Expression<Boolean> node = Conjunction.of(flag("a"), flag("b"));
		Assert.assertTrue(node instanceof Conjunction);
		Assert.assertEquals(Boolean.class, node.getType());
		Assert.assertEquals(1, node.evaluate(1.0, 1.0).intValue());
		Assert.assertEquals(0, node.evaluate(1.0, 0.0).intValue());
		Assert.assertEquals(0, node.evaluate(0.0, 1.0).intValue());
	}
}
