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
import io.almostrealism.expression.ArcCosine;
import io.almostrealism.expression.Cosine;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Logarithm;
import io.almostrealism.expression.Sine;
import io.almostrealism.expression.Tangent;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Pins the behavior of the single-operand math-function expressions
 * ({@link Sine}, {@link Cosine}, {@link ArcCosine}, {@link Tangent}, {@link Exp},
 * {@link Logarithm}) so that consolidating their shared skeleton onto a common base
 * class is provably behavior-preserving.
 *
 * <p>Each function shares an identical structure — a {@code fname(operand)} rendering,
 * a single-operand {@link Expression#evaluate(Number...)} delegating to a {@link Math}
 * call, and a {@code recreate}/{@code of} factory shape — while differing in the
 * function name, the backing {@link Math} function, the compute cost, the upper bound,
 * and the derivative. These tests assert both the shared and the divergent behaviors.</p>
 */
public class MathFunctionExpressionTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions to source. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/** A non-constant double operand, so the {@code of} factories build nodes rather than fold. */
	private Expression<Double> operand() {
		return kernel().toDouble();
	}

	/**
	 * Each function renders as {@code fname(operand)} with its own library name, and
	 * {@link Tangent} switches between {@code tan} and {@code tanh} on its hyperbolic flag.
	 */
	@Test(timeout = 30000)
	public void rendersAsFunctionCall() {
		Assert.assertTrue(Sine.of(operand()).getExpression(lang).startsWith("sin("));
		Assert.assertTrue(Cosine.of(operand()).getExpression(lang).startsWith("cos("));
		Assert.assertTrue(ArcCosine.of(operand()).getExpression(lang).startsWith("acos("));
		Assert.assertTrue(Tangent.of(operand()).getExpression(lang).startsWith("tan("));
		Assert.assertTrue(Tangent.of(operand(), true).getExpression(lang).startsWith("tanh("));
		Assert.assertTrue(((Expression) Exp.of(operand())).getExpression(lang).startsWith("exp("));
		Assert.assertTrue(((Expression) Logarithm.of(operand())).getExpression(lang).startsWith("log("));
	}

	/**
	 * A node evaluated on a single operand value delegates to the corresponding
	 * {@link Math} function.
	 */
	@Test(timeout = 30000)
	public void evaluatesViaMath() {
		double x = 0.7;
		Assert.assertEquals(Math.sin(x), Sine.of(operand()).evaluate(x).doubleValue(), 1e-12);
		Assert.assertEquals(Math.cos(x), Cosine.of(operand()).evaluate(x).doubleValue(), 1e-12);
		Assert.assertEquals(Math.acos(x), ArcCosine.of(operand()).evaluate(x).doubleValue(), 1e-12);
		Assert.assertEquals(Math.tan(x), Tangent.of(operand()).evaluate(x).doubleValue(), 1e-12);
		Assert.assertEquals(Math.tanh(x), Tangent.of(operand(), true).evaluate(x).doubleValue(), 1e-12);
		Assert.assertEquals(Math.exp(x), ((Expression) Exp.of(operand())).evaluate(x).doubleValue(), 1e-12);
		Assert.assertEquals(Math.log(x), ((Expression) Logarithm.of(operand())).evaluate(x).doubleValue(), 1e-12);
	}

	/**
	 * Each function reports its own fixed compute cost: 25 for the trigonometric
	 * functions, 30 for the tangent, and 15 for {@code exp}/{@code log}.
	 */
	@Test(timeout = 30000)
	public void reportsComputeCost() {
		Assert.assertEquals(25, Sine.of(operand()).getComputeCost());
		Assert.assertEquals(25, Cosine.of(operand()).getComputeCost());
		Assert.assertEquals(25, ArcCosine.of(operand()).getComputeCost());
		Assert.assertEquals(30, Tangent.of(operand()).getComputeCost());
		Assert.assertEquals(30, Tangent.of(operand(), true).getComputeCost());
		Assert.assertEquals(15, ((Expression) Exp.of(operand())).getComputeCost());
		Assert.assertEquals(15, ((Expression) Logarithm.of(operand())).getComputeCost());
	}

	/**
	 * The trigonometric factories fold a constant operand to a constant whose value is the
	 * {@link Math} function applied to the operand.
	 */
	@Test(timeout = 30000)
	public void trigFactoriesFoldConstants() {
		Assert.assertEquals(Math.sin(0.5), Sine.of(e(0.5)).doubleValue().getAsDouble(), 1e-12);
		Assert.assertEquals(Math.cos(0.5), Cosine.of(e(0.5)).doubleValue().getAsDouble(), 1e-12);
		Assert.assertEquals(Math.acos(0.5), ArcCosine.of(e(0.5)).doubleValue().getAsDouble(), 1e-12);
		Assert.assertEquals(Math.tan(0.5), Tangent.of(e(0.5)).doubleValue().getAsDouble(), 1e-12);
		Assert.assertEquals(Math.tanh(0.5), Tangent.of(e(0.5), true).doubleValue().getAsDouble(), 1e-12);
		Assert.assertEquals(Math.exp(0.5), ((Expression) Exp.of(e(0.5))).doubleValue().getAsDouble(), 1e-12);
	}

	/**
	 * {@link Logarithm#of} does not fold a constant operand; instead it keeps a
	 * {@link Logarithm} node, and cancels {@code log(exp(x))} back to {@code x}.
	 */
	@Test(timeout = 30000)
	public void logarithmFactoryKeepsNodeAndCancelsExp() {
		Expression<?> log = Logarithm.of(e(0.5));
		Assert.assertTrue(log instanceof Logarithm);

		Expression<Double> x = operand();
		Expression<?> cancelled = Logarithm.of(Exp.of(x));
		Assert.assertFalse(cancelled instanceof Logarithm);
		Assert.assertEquals(x, cancelled);
	}

	/**
	 * {@code recreate} rebuilds a node around a new operand. Most functions rebuild the node
	 * even when the new operand is constant, while {@link Exp} folds it through its factory.
	 */
	@Test(timeout = 30000)
	public void recreatePreservesFoldingContract() {
		Expression<?> sine = ((Sine) Sine.of(operand())).recreate(List.of(e(0.0)));
		Assert.assertTrue(sine instanceof Sine);

		Expression<?> cosine = ((Cosine) Cosine.of(operand())).recreate(List.of(e(0.0)));
		Assert.assertTrue(cosine instanceof Cosine);

		Expression<?> tangent = ((Tangent) Tangent.of(operand(), true)).recreate(List.of(e(0.0)));
		Assert.assertTrue(tangent instanceof Tangent);

		Exp expNode = (Exp) (Expression) Exp.of(operand());
		Expression<?> exp = expNode.recreate(List.of(e(0.0)));
		Assert.assertFalse(exp instanceof Exp);
		Assert.assertEquals(Math.exp(0.0), exp.doubleValue().getAsDouble(), 1e-12);
	}

	/**
	 * {@code recreate} rejects an operand list whose arity is not one.
	 */
	@Test(timeout = 30000)
	public void recreateRejectsWrongArity() {
		try {
			((Sine) Sine.of(operand())).recreate(List.of(e(0.0), e(1.0)));
			Assert.fail("expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// expected
		}
	}
}
