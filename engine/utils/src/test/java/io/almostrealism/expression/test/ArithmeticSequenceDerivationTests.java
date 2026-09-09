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
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.DefaultKernelStructureContext;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.NoOpKernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexRange;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that {@link Expression#arithmeticSequence(Index, long)} derives progressions
 * that agree with evaluation at every position, that it recognises the identities
 * convolution index arithmetic produces, and that the series provider uses it in
 * preference to enumeration.
 */
public class ArithmeticSequenceDerivationTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions in messages. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/**
	 * Whenever a progression is derived for a random integer expression, it must take
	 * exactly the values point evaluation produces, at every position.
	 */
	@Test(timeout = 120000)
	public void derivedProgressionsAgreeWithEvaluation() {
		Random random = new Random(19);
		int len = 3000;
		int derived = 0;

		for (int trial = 0; trial < 400; trial++) {
			Expression<?> exp = randomIntegerExpression(random, 4);
			ArithmeticIndexSequence seq = exp.arithmeticSequence(kernel(), len);
			if (seq == null) continue;

			derived++;
			assertAgrees(exp, kernel(), seq);
		}

		log(derived + " of 400 random expressions were derived");
		Assert.assertTrue("Too few derivations to be meaningful: " + derived, derived > 40);
	}

	/**
	 * The index arithmetic that convolution produces for its filter taps, in which a
	 * scaled quotient cancels a product, is derived as a constant rather than enumerated.
	 */
	@Test(timeout = 30000)
	public void convolutionTapIdentityIsConstant() {
		int len = 175616;
		Expression<?> step = kernel().imod(43904).divide(784);
		Expression<?> exp = step.multiply(-504).divide(9).add(step.multiply(56)).add(37);

		ArithmeticIndexSequence seq = exp.arithmeticSequence(kernel(), len);
		Assert.assertNotNull(exp.getExpression(lang), seq);
		Assert.assertTrue(seq.isConstant());
		Assert.assertEquals(37, seq.valueAt(0).intValue());
		Assert.assertEquals(37, seq.valueAt(len - 1).intValue());
	}

	/**
	 * A mixed-radix decomposition that does not reduce to a single progression is not
	 * derived, so it is left to enumeration rather than mis-described.
	 */
	@Test(timeout = 30000)
	public void mixedRadixSumIsNotDerived() {
		Expression<?> exp = kernel().imod(784).divide(28).multiply(28)
				.add(kernel().divide(43904).multiply(43904))
				.add(kernel().imod(28));

		Assert.assertNull(exp.arithmeticSequence(kernel(), 175616));
	}

	/**
	 * Granularity, period, scale and offset all compose: the expression
	 * {@code ((i % 300) / 5) * 3 + 7} is derived with exactly those parameters.
	 */
	@Test(timeout = 30000)
	public void parametersCompose() {
		Expression<?> exp = kernel().imod(300).divide(5).multiply(3).add(7);
		ArithmeticIndexSequence seq = exp.arithmeticSequence(kernel(), 1200);

		Assert.assertNotNull(seq);
		Assert.assertEquals(7, seq.getOffset());
		Assert.assertEquals(3, seq.getScale());
		Assert.assertEquals(5, seq.getGranularity());
		Assert.assertEquals(300, seq.getMod());
		assertAgrees(exp, kernel(), seq);
	}

	/**
	 * Over a kernel index child, the kernel index itself steps once per run of child
	 * positions, and expressions mixing the two are derived accordingly.
	 */
	@Test(timeout = 30000)
	public void kernelIndexChildDerivation() {
		KernelIndex k = new KernelIndex(new NoOpKernelStructureContext(6));
		Index child = Index.child(k, new DefaultIndex("i", 4));
		long len = 24;

		Expression<?> exp = ((Expression<?>) child).multiply(3).add(k.multiply(100)).imod(37);
		Assert.assertNull("Modulus of an incompatible sum cannot be derived", exp.arithmeticSequence(child, len));

		Expression<?> derivable = k.multiply(5).add(2);
		ArithmeticIndexSequence seq = derivable.arithmeticSequence(child, len);
		Assert.assertNotNull(seq);
		Assert.assertEquals(4, seq.getGranularity());
		assertAgrees(derivable, child, seq);
	}

	/**
	 * The series provider converts a derivable expression without enumerating a single
	 * position of it.
	 */
	@Test(timeout = 30000)
	public void providerDerivesWithoutEnumerating() {
		AtomicInteger positions = new AtomicInteger();
		Expression<?> derivable = new CountingSum(kernel().imod(96).divide(8), e(5), positions);

		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(4096);
		Expression<?> converted = ctx.getSeriesProvider().getSeries(derivable);

		Assert.assertNotSame(derivable, converted);
		Assert.assertTrue("Sample verification alone should touch far fewer than 4096 positions, saw " + positions.get(),
				positions.get() < 4096);

		for (int i = 0; i < 4096; i++) {
			Assert.assertEquals((i % 96) / 8 + 5, converted.value(new IndexValues().put(kernel(), i)).intValue());
		}
	}

	/**
	 * Dividing a product by a constant that divides its constant factor folds at
	 * construction, for either sign of the factor.
	 */
	@Test(timeout = 30000)
	public void quotientOfDivisibleProductFolds() {
		Expression<?> x = kernel().imod(43904).divide(784);

		Expression<?> negative = Quotient.of(Product.of(x, e(-504)), e(9));
		Assert.assertEquals(x.multiply(-56).getExpression(lang), negative.getExpression(lang));

		Expression<?> positive = Quotient.of(Product.of(x, e(504)), e(9));
		Assert.assertEquals(x.multiply(56).getExpression(lang), positive.getExpression(lang));

		Expression<?> exact = Quotient.of(Product.of(x, e(9)), e(9));
		Assert.assertEquals(x.getExpression(lang), exact.getExpression(lang));

		Expression<?> inexact = Quotient.of(Product.of(x, e(10)), e(9));
		Assert.assertTrue(inexact instanceof Quotient);

		for (int i = 0; i < 2000; i++) {
			IndexValues v = new IndexValues().put(kernel(), i);
			int step = (i % 43904) / 784;
			Assert.assertEquals(step * -56, negative.value(v).intValue());
			Assert.assertEquals(step * 56, positive.value(v).intValue());
		}
	}

	/**
	 * When a sum has more than one term that cannot be resolved by the simpler
	 * single-remainder fold in {@link Quotient#create}, the construction-time
	 * bounded-remainder rule still drops every term below the shared factor, exactly
	 * as its derivation-time counterpart does for {@link ArithmeticIndexSequence}.
	 */
	@Test(timeout = 30000)
	public void boundedRemainderFoldsMultipleRemainderTermsAtConstruction() {
		Expression<?> coarse = kernel().imod(10).multiply(8);
		Expression<?> remainderA = kernel().imod(3);
		Expression<?> remainderB = kernel().imod(5);
		Expression<?> quotient = Quotient.of(Sum.of(coarse, remainderA, remainderB), e(8));
		log(quotient.getExpression(lang));

		Assert.assertEquals(kernel().imod(10).getExpression(lang), quotient.getExpression(lang));

		for (int i = 0; i < 200; i++) {
			long expected = ((i % 10) * 8 + (i % 3) + (i % 5)) / 8;
			Assert.assertEquals("at " + i, expected, quotient.value(new IndexValues().put(kernel(), i)).longValue());
		}
	}

	/**
	 * The mixed-radix forms produced by convolution deltas fold at construction: a
	 * modulus drops the terms that are multiples of it, and a quotient drops a
	 * remainder term bounded below a factor of the divisor.
	 */
	@Test(timeout = 30000)
	public void mixedRadixFolds() {
		Expression<?> a = kernel().divide(82944).multiply(72).add(kernel().divide(288).imod(72));
		log(a.getExpression(lang));

		Expression<?> mod72 = a.imod(72);
		log(mod72.getExpression(lang));
		Assert.assertEquals(kernel().divide(288).imod(72).getExpression(lang), mod72.getExpression(lang));

		Expression<?> mod8 = a.imod(8);
		log(mod8.getExpression(lang));
		Assert.assertEquals(kernel().divide(288).imod(8).getExpression(lang), mod8.getExpression(lang));

		Expression<?> div288 = a.divide(288);
		log(div288.getExpression(lang));
		Assert.assertEquals(kernel().divide(331776).getExpression(lang), div288.getExpression(lang));

		for (int i = 0; i < 400000; i += 37) {
			IndexValues v = new IndexValues().put(kernel(), i);
			long expected = (i / 82944) * 72 + (i / 288) % 72;
			Assert.assertEquals(expected % 72, mod72.value(v).longValue());
			Assert.assertEquals(expected % 8, mod8.value(v).longValue());
			Assert.assertEquals(expected / 288, div288.value(v).longValue());
		}
	}

	/**
	 * A product of a non-negative factor and a negative constant is possibly negative,
	 * so the bounded-remainder fold must leave a sum containing it alone: with
	 * {@code (k % 10) * -1024} as the coarse term, {@code (-1024 + 3) / 1024} is 0 while
	 * {@code -1024 / 1024} is -1.
	 */
	@Test(timeout = 30000)
	public void negativeProductIsPossiblyNegative() {
		Expression<?> coarse = kernel().imod(10).multiply(-1024);
		Assert.assertTrue(coarse.isPossiblyNegative());
		Assert.assertTrue(coarse.lowerBound().getAsLong() <= -9 * 1024);

		Expression<?> quotient = Quotient.of(Sum.of(coarse, kernel().imod(1024)), e(1024));
		log(quotient.getExpression(lang));

		for (int i = 0; i < 4096; i += 7) {
			long expected = ((i % 10) * -1024 + (i % 1024)) / 1024;
			Assert.assertEquals("at " + i, expected, quotient.value(new IndexValues().put(kernel(), i)).longValue());
		}
	}

	/**
	 * A floating-point quotient keeps its remainder even when every constant involved
	 * has an integral value: {@code (k + 1024.0) / 2048.0} is not {@code 0.5}.
	 */
	@Test(timeout = 30000)
	public void floatingPointQuotientKeepsRemainder() {
		Expression<?> quotient = Quotient.of(Sum.of(kernel(), e(1024.0)), e(2048.0));
		log(quotient.getExpression(lang));
		Assert.assertTrue(quotient.isFP());

		for (int i = 0; i < 2048; i += 341) {
			Assert.assertEquals("at " + i, (i + 1024.0) / 2048.0,
					quotient.value(new IndexValues().put(kernel(), i)).doubleValue(), 0.0);
		}
	}

	/**
	 * The bounded-remainder rule must not fire when the coarse terms can be negative,
	 * because division truncates toward zero: {@code (-4 + 3) / 4} is {@code 0} while
	 * {@code -4 / 4} is {@code -1}. Both the construction fold and the derivation must
	 * agree with evaluation on such a sum.
	 */
	@Test(timeout = 30000)
	public void negativeCoarseTermsKeepTheRemainder() {
		Expression<?> coarse = kernel().imod(2).multiply(-4);
		Expression<?> remainder = kernel().imod(4);
		Expression<?> quotient = Quotient.of(Sum.of(coarse, remainder), e(4));
		log(quotient.getExpression(lang));

		for (int i = 0; i < 16; i++) {
			long expected = ((i % 2) * -4 + (i % 4)) / 4;
			Assert.assertEquals("at " + i, expected, quotient.value(new IndexValues().put(kernel(), i)).longValue());
		}

		Assert.assertTrue("The fold must not fire for a possibly negative coarse term",
				quotient instanceof Quotient);
		Assert.assertNull(quotient.arithmeticSequence(kernel(), 16));
	}

	/**
	 * Asserts that the derived progression agrees with point evaluation at every position.
	 */
	private void assertAgrees(Expression<?> exp, Index index, ArithmeticIndexSequence seq) {
		Assert.assertTrue(seq.agreesWith(exp, index));

		for (long i = 0; i < seq.lengthLong(); i++) {
			long expected = exp.value(new IndexValues().put(index, (int) i)).longValue();
			long actual = seq.valueAt(i).longValue();

			if (expected != actual) {
				Assert.fail(exp.getExpression(lang) + " derived as " + seq +
						" differs at " + i + ": " + expected + " vs " + actual);
			}
		}
	}

	/**
	 * Builds a random integer expression over the kernel index from the operations the
	 * derivation understands, so that a useful fraction of trials is derivable.
	 */
	private Expression<?> randomIntegerExpression(Random random, int depth) {
		if (depth == 0 || random.nextInt(4) == 0) {
			return random.nextBoolean() ? kernel() : e(random.nextInt(9) - 4);
		}

		Expression<?> a = randomIntegerExpression(random, depth - 1);
		Expression<?> b = randomIntegerExpression(random, depth - 1);

		switch (random.nextInt(7)) {
			case 0: return a.add(b);
			case 1: return a.multiply(b);
			case 2: return a.divide(e(1 + random.nextInt(11)));
			case 3: return a.imod(1 + random.nextInt(13));
			case 4: return a.minus();
			case 5: return a.subtract((Expression) b);
			default: return a.multiply(e(random.nextInt(7) - 3));
		}
	}

	/**
	 * A sum that counts how many positions it is asked to evaluate, so the test can
	 * tell derivation from enumeration.
	 */
	private static class CountingSum extends Expression<Integer> {
		/** Shared counter of evaluated positions. */
		private final AtomicInteger positions;

		/**
		 * Creates a counting sum of the two operands.
		 *
		 * @param a the first operand
		 * @param b the second operand
		 * @param positions the counter to increment per evaluated position
		 */
		CountingSum(Expression<?> a, Expression<?> b, AtomicInteger positions) {
			super(Integer.class, a, b);
			this.positions = positions;
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
			positions.incrementAndGet();
			return getChildren().get(0).value(indexValues).longValue() +
					getChildren().get(1).value(indexValues).longValue();
		}

		@Override
		protected double[] computeValues(IndexRange range) {
			positions.addAndGet(range.getLength());

			double[] a = getChildren().get(0).values(range);
			double[] b = getChildren().get(1).values(range);
			double[] out = new double[range.getLength()];

			for (int i = 0; i < out.length; i++) {
				out[i] = IndexRange.exact((long) a[i] + (long) b[i]);
			}

			return out;
		}

		@Override
		public ArithmeticIndexSequence arithmeticSequence(Index index, long len) {
			ArithmeticIndexSequence a = getChildren().get(0).arithmeticSequence(index, len);
			ArithmeticIndexSequence b = getChildren().get(1).arithmeticSequence(index, len);
			return a == null || b == null ? null : a.plus(b);
		}

		@Override
		protected Expression<Integer> recreate(List<Expression<?>> children) {
			return new CountingSum(children.get(0), children.get(1), positions);
		}
	}
}
