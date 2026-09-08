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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mask;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexRange;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that evaluating an {@link Expression} over an {@link IndexRange} agrees with
 * point evaluation through {@link Expression#value(IndexValues)} at every position, for
 * the kinds of index arithmetic kernel series detection encounters.
 */
public class IndexRangeEvaluationTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions in failure messages. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/**
	 * Randomly generated integer index arithmetic (sums, products, quotients, moduli,
	 * negations, masks and conditionals over the kernel index) must produce the same
	 * sequence whether evaluated in blocks or point by point, across block boundaries.
	 */
	@Test(timeout = 60000)
	public void randomIntegerArithmeticMatchesPointEvaluation() {
		Random random = new Random(7);
		int len = 3 * ScopeSettings.sequenceBlockSize + 17;

		for (int trial = 0; trial < 60; trial++) {
			Expression<?> exp = randomIntegerExpression(random, 4);
			assertMatchesPointEvaluation(exp, kernel(), len);
		}
	}

	/**
	 * Floating-point index arithmetic, including floor and floating-point modulus, must
	 * agree between block and point evaluation.
	 */
	@Test(timeout = 60000)
	public void floatingPointArithmeticMatchesPointEvaluation() {
		int len = 2 * ScopeSettings.sequenceBlockSize + 5;
		Expression<?> scaled = kernel().toDouble().multiply(0.25).add(e(1.5));

		assertMatchesPointEvaluation(scaled, kernel(), len);
		assertMatchesPointEvaluation(scaled.floor(), kernel(), len);
		assertMatchesPointEvaluation(scaled.mod(e(3.0)), kernel(), len);
		assertMatchesPointEvaluation(scaled.minus().multiply(kernel().toDouble()), kernel(), len);
		assertMatchesPointEvaluation(scaled.toInt().multiply(3).imod(7), kernel(), len);
	}

	/**
	 * A range over a kernel index child assigns the child positions directly and derives
	 * the kernel index from them, exactly as {@link IndexValues#put(Index, Integer)} does.
	 */
	@Test(timeout = 60000)
	public void kernelIndexChildRangeMatchesPointEvaluation() {
		int kernelCount = 5;
		KernelIndex k = new KernelIndex(new NoOpKernelStructureContext(kernelCount));
		DefaultIndex inner = new DefaultIndex("i", 4);
		Index child = Index.child(k, inner);

		Expression<?> exp = ((Expression<?>) child).multiply(3).add(k.multiply(100)).imod(37);
		long len = ((Expression<?>) child).upperBound(null).getAsLong() + 1;
		Assert.assertEquals(kernelCount * 4, len);

		assertMatchesPointEvaluation(exp, child, (int) len);
	}

	/**
	 * A node shared by many paths of the expression graph is computed once per block,
	 * not once per path and not once per index.
	 */
	@Test(timeout = 60000)
	public void sharedNodesEvaluatedOncePerBlock() {
		AtomicInteger evaluations = new AtomicInteger();
		int depth = 20;

		Expression<Integer> node = new CountingSum(kernel(), e(1), evaluations);
		for (int i = 0; i < depth; i++) {
			node = new CountingSum(node, node, evaluations);
		}

		int blocks = 3;
		IndexSequence seq = node.sequence(kernel(), (long) blocks * ScopeSettings.sequenceBlockSize);

		Assert.assertEquals((depth + 1) * blocks, evaluations.get());
		Assert.assertEquals((1L << depth) * (0 + 1), seq.valueAt(0).longValue());
		Assert.assertEquals((1L << depth) * (5 + 1), seq.valueAt(5).longValue());
	}

	/**
	 * When an integer intermediate exceeds the range a double represents exactly, block
	 * evaluation refuses the value and the sequence falls back to exact point evaluation.
	 */
	@Test(timeout = 60000)
	public void inexactIntermediateFallsBackToPointEvaluation() {
		Expression<?> huge = kernel().add(1).multiply(1L << 40).multiply(1L << 20);
		int len = 16;

		IndexRange range = new IndexRange(kernel(), 0, len);
		try {
			huge.values(range);
			Assert.fail("Expected the block evaluation to refuse an inexact value");
		} catch (IndexRange.InexactValueException e) {
			log("Refused: " + e.getMessage());
		}

		IndexSequence seq = huge.sequence(kernel(), len);

		for (int i = 0; i < len; i++) {
			long expected = huge.value(new IndexValues().put(kernel(), i)).longValue();
			Assert.assertEquals(expected, seq.valueAt(i).longValue());
		}
	}

	/**
	 * Asserts that the block-evaluated sequence of {@code exp} over {@code index} equals
	 * its point evaluation at every position.
	 */
	private void assertMatchesPointEvaluation(Expression<?> exp, Index index, int len) {
		IndexSequence seq = exp.sequence(index, len);
		Assert.assertEquals(len, seq.lengthLong());

		for (int i = 0; i < len; i++) {
			double expected = exp.value(new IndexValues().put(index, i)).doubleValue();
			double actual = seq.valueAt(i).doubleValue();

			if (expected != actual) {
				Assert.fail(exp.getExpression(lang) + " differs at " + i +
						": point=" + expected + " block=" + actual);
			}
		}
	}

	/**
	 * Builds a random integer expression over the kernel index with at most the given
	 * nesting depth. Divisors and moduli are always positive constants.
	 */
	private Expression<?> randomIntegerExpression(Random random, int depth) {
		if (depth == 0 || random.nextInt(4) == 0) {
			return random.nextBoolean() ? kernel() : e(random.nextInt(9) - 4);
		}

		Expression<?> a = randomIntegerExpression(random, depth - 1);
		Expression<?> b = randomIntegerExpression(random, depth - 1);

		switch (random.nextInt(8)) {
			case 0: return a.add(b);
			case 1: return a.multiply(b);
			case 2: return a.divide(e(1 + random.nextInt(11)));
			case 3: return a.imod(1 + random.nextInt(13));
			case 4: return a.minus();
			case 5: return a.greaterThan(b).conditional(a, b);
			case 6: return Mask.of(a.imod(3).eq(e(random.nextInt(3))), b.add(1));
			default: return a.subtract((Expression) b);
		}
	}

	/**
	 * A sum that counts how many blocks it is asked to evaluate, to verify memoization.
	 */
	private static class CountingSum extends Expression<Integer> {
		/** Shared counter of block evaluations, incremented by every instance. */
		private final AtomicInteger evaluations;

		/**
		 * Creates a counting sum of the two operands.
		 *
		 * @param a the first operand
		 * @param b the second operand
		 * @param evaluations the shared counter to increment per block evaluation
		 */
		CountingSum(Expression<?> a, Expression<?> b, AtomicInteger evaluations) {
			super(Integer.class, a, b);
			this.evaluations = evaluations;
		}

		@Override
		public String getExpression(LanguageOperations lang) {
			return "(" + getChildren().get(0).getExpression(lang) + " + " +
					getChildren().get(1).getExpression(lang) + ")";
		}

		@Override
		public boolean isValue(IndexValues values) {
			return getChildren().stream().allMatch(c -> c.isValue(values));
		}

		@Override
		public Number computeValue(IndexValues indexValues) {
			return getChildren().get(0).value(indexValues).longValue() +
					getChildren().get(1).value(indexValues).longValue();
		}

		@Override
		protected double[] computeValues(IndexRange range) {
			evaluations.incrementAndGet();

			double[] a = getChildren().get(0).values(range);
			double[] b = getChildren().get(1).values(range);
			double[] out = new double[range.getLength()];

			for (int i = 0; i < out.length; i++) {
				out[i] = IndexRange.exact((long) a[i] + (long) b[i]);
			}

			return out;
		}

		@Override
		protected Expression<Integer> recreate(List<Expression<?>> children) {
			return new CountingSum(children.get(0), children.get(1), evaluations);
		}
	}
}
