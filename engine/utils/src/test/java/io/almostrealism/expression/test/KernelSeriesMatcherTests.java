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
import io.almostrealism.kernel.DefaultKernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.IndexRange;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.sequence.KernelSeriesMatcher;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies the closed forms recognised by {@link KernelSeriesMatcher}, that every
 * emitted expression reproduces the sequence it was derived from, and that a sequence
 * matching no form is abandoned after a handful of values.
 */
public class KernelSeriesMatcherTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions in log output. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/**
	 * A constant sequence is replaced by a constant.
	 */
	@Test(timeout = 30000)
	public void constant() {
		Expression<?> r = match(new double[] { 7, 7, 7, 7, 7, 7 }, true);
		Assert.assertEquals(7, r.intValue().getAsInt());

		r = match(new double[] { 2.5, 2.5, 2.5 }, false);
		Assert.assertEquals(2.5, r.doubleValue().getAsDouble(), 0.0);
	}

	/**
	 * A single non-zero position becomes a mask on an equality test, and a single
	 * contiguous run of one value becomes a mask on a range test.
	 */
	@Test(timeout = 30000)
	public void masks() {
		Expression<?> r = match(new double[] { 0, 0, 0, 5, 0, 0 }, true);
		Assert.assertTrue(r.isMasked());

		r = match(new double[] { 0, 0, 3, 3, 3, 0, 0, 0 }, true);
		Assert.assertTrue(r.isMasked());

		r = match(new double[] { 0, 2.0, 2.0, 0 }, false);
		Assert.assertTrue(r.isMasked());
		Assert.assertEquals(2.0, r.getChildren().get(1).doubleValue().getAsDouble(), 0.0);

		Assert.assertNull(match(new double[] { 0, 2.5, 2.5, 0 }, false));
	}

	/**
	 * Two separate runs of the same value are neither a mask nor a progression.
	 */
	@Test(timeout = 30000)
	public void twoRunsMatchNothing() {
		Assert.assertNull(match(new double[] { 0, 1, 1, 0, 1, 0 }, true));
		Assert.assertNull(match(new double[] { 0, 0, 4, 0, 0, 4, 4 }, true));
	}

	/**
	 * Arithmetic progressions are recognised with a scale and offset, with a granularity
	 * (runs of equal values), with a period (the progression restarting), and with both.
	 */
	@Test(timeout = 30000)
	public void arithmeticProgressions() {
		Assert.assertNotNull(match(new double[] { 0, 1, 2, 3, 4, 5 }, true));
		Assert.assertNotNull(match(new double[] { 3, 5, 7, 9, 11 }, true));
		Assert.assertNotNull(match(new double[] { 0, 0, 1, 1, 2, 2, 3, 3 }, true));
		Assert.assertNotNull(match(new double[] { 0, 1, 2, 0, 1, 2, 0, 1, 2 }, true));
		Assert.assertNotNull(match(new double[] { 0, 0, 1, 1, 0, 0, 1, 1 }, true));
		Assert.assertNotNull(match(new double[] { 1, 4, 7, 1, 4, 7 }, true));
		Assert.assertNotNull(match(new double[] { 0.5, 1.0, 1.5, 2.0 }, false));
	}

	/**
	 * A progression that changes its step, restarts at the wrong value, or restarts on
	 * a boundary that is not a multiple of its granularity is not recognised.
	 */
	@Test(timeout = 30000)
	public void brokenProgressionsMatchNothing() {
		Assert.assertNull(match(new double[] { 0, 1, 2, 4, 5, 6 }, true));
		Assert.assertNull(match(new double[] { 0, 1, 2, 1, 2, 3 }, true));
		Assert.assertNull(match(new double[] { 0, 0, 1, 1, 2, 0, 0, 1 }, true));
		Assert.assertNull(match(new double[] { 0, 0, 1, 2, 2, 3 }, true));
	}

	/**
	 * Every expression the matcher emits must reproduce the sequence it was derived
	 * from, for randomly generated progressions with random scale, offset, granularity
	 * and period, and for randomly placed masks.
	 */
	@Test(timeout = 60000)
	public void emittedExpressionsReproduceTheirSequences() {
		Random random = new Random(11);

		for (int trial = 0; trial < 200; trial++) {
			double[] values = randomStructuredSequence(random);
			Expression<?> r = match(values, true);
			Assert.assertNotNull("No form found for " + Arrays.toString(values), r);
			assertReproduces(values, r);
		}
	}

	/**
	 * The matcher recognises the same forms as the sequence it is fed, whether the
	 * values arrive one at a time, in blocks, or through
	 * {@link IndexSequence#getExpression(Expression, boolean)}.
	 */
	@Test(timeout = 60000)
	public void blockAndPointDeliveryAgree() {
		Random random = new Random(5);

		for (int trial = 0; trial < 100; trial++) {
			double[] values = random.nextBoolean() ? randomStructuredSequence(random) : randomNoise(random);

			KernelSeriesMatcher pointwise = new KernelSeriesMatcher(values.length);
			for (int i = 0; i < values.length && pointwise.isPossible(); i++) pointwise.accept(values[i]);

			KernelSeriesMatcher blocked = new KernelSeriesMatcher(values.length);
			for (int i = 0; i < values.length && blocked.isPossible(); i += 3) {
				double[] block = new double[3];
				int count = Math.min(3, values.length - i);
				System.arraycopy(values, i, block, 0, count);
				blocked.accept(block, count);
			}

			Expression<?> a = pointwise.getExpression(kernel(), true);
			Expression<?> b = blocked.getExpression(kernel(), true);
			Expression<?> c = ArrayIndexSequence.of(Integer.class, values).getExpression(kernel(), true);

			Assert.assertEquals(a == null, b == null);
			Assert.assertEquals(a == null, c == null);

			if (a != null) {
				Assert.assertEquals(a, b);
				Assert.assertEquals(a, c);
				assertReproduces(values, a);
			}
		}
	}

	/**
	 * Random values refute every form within the first few positions, so the matcher
	 * reports that nothing is possible long before the sequence ends.
	 */
	@Test(timeout = 30000)
	public void noiseIsRefutedEarly() {
		Random random = new Random(3);

		for (int trial = 0; trial < 100; trial++) {
			double[] values = randomNoise(random);
			KernelSeriesMatcher matcher = new KernelSeriesMatcher(values.length);

			int consumed = 0;
			while (consumed < values.length && matcher.isPossible()) {
				matcher.accept(values[consumed++]);
			}

			Assert.assertFalse(matcher.isPossible());
			Assert.assertTrue("Refuted only after " + consumed + " values", consumed <= 6);
			Assert.assertNull(matcher.getExpression(kernel(), true));
		}
	}

	/**
	 * Recognising the series of an expression whose sequence matches no form stops
	 * enumerating after the first block rather than evaluating every kernel index.
	 */
	@Test(timeout = 60000)
	public void irregularExpressionIsAbandonedAfterOneBlock() {
		int len = 64 * ScopeSettings.sequenceBlockSize;
		AtomicInteger positions = new AtomicInteger();

		Expression<?> irregular = new CountingProduct(
				kernel().imod(37).add(1), kernel().divide(11).imod(5).add(1), positions);

		KernelSeriesMatcher matcher = irregular.matchSeries(kernel(), len, Long.MAX_VALUE);
		Assert.assertFalse(matcher.isPossible());
		Assert.assertNull(matcher.getExpression(kernel(), true));
		Assert.assertEquals(ScopeSettings.sequenceBlockSize, positions.get());
	}

	/**
	 * When block evaluation refuses an integer intermediate as inexact, the point-evaluation
	 * fallback it triggers must be held to the same exactness standard. A constant this large
	 * added to a small kernel index produces sixteen distinct {@code long} values that all
	 * round to the same {@code double}; refusing every form is the only safe outcome; aliasing
	 * them into a false constant would be a silent miscompilation.
	 */
	@Test(timeout = 30000)
	public void inexactFallbackRefusesToAliasDistinctValues() {
		int len = 16;
		Expression<?> huge = e(Long.MAX_VALUE / 2).add(kernel());

		KernelSeriesMatcher matcher = huge.matchSeries(kernel(), len, Long.MAX_VALUE);
		Assert.assertFalse("A sequence that cannot be compared exactly as double must refuse every form",
				matcher.isPossible());
		Assert.assertNull(matcher.getExpression(kernel(), true));
	}

	/**
	 * Recognising the series of a progression enumerates every kernel index exactly
	 * once and emits an expression equal to the original sequence.
	 */
	@Test(timeout = 60000)
	public void regularExpressionIsEnumeratedOnce() {
		int len = 5 * ScopeSettings.sequenceBlockSize + 3;
		AtomicInteger positions = new AtomicInteger();

		Expression<?> regular = new CountingProduct(kernel().divide(6), e(3), positions);

		KernelSeriesMatcher matcher = regular.matchSeries(kernel(), len, Long.MAX_VALUE);
		Assert.assertTrue(matcher.isComplete());
		Assert.assertEquals(len, positions.get());

		Expression<?> r = matcher.getExpression(kernel(), true);
		Assert.assertNotNull(r);
		log(r.getExpression(lang));

		for (int i = 0; i < len; i++) {
			Assert.assertEquals((i / 6) * 3, r.value(new IndexValues().put(kernel(), i)).intValue());
		}
	}

	/**
	 * The default series provider converts a progression that is only recognisable
	 * from its values (a mixed-radix identity no structural rule reduces) and leaves
	 * an irregular expression untouched.
	 */
	@Test(timeout = 60000)
	public void defaultProviderUsesMatcher() {
		int len = 4 * 784;
		DefaultKernelStructureContext ctx = new DefaultKernelStructureContext(len);

		Expression<?> regular = kernel().imod(784).divide(28).multiply(28).add(kernel().imod(28));
		Assert.assertNull(regular.arithmeticSequence(kernel(), len));

		Expression<?> converted = ctx.getSeriesProvider().getSeries(regular);
		Assert.assertNotEquals(regular, converted);
		Assert.assertTrue(converted.countNodes() < regular.countNodes());
		assertReproduces(regular.sequence(kernel(), len), converted);

		Expression<?> irregular = kernel().imod(37).add(1).multiply(kernel().divide(11).imod(5).add(1));
		Assert.assertSame(irregular, ctx.getSeriesProvider().getSeries(irregular));
	}

	/**
	 * Feeds the values to a fresh matcher and returns its result.
	 */
	private Expression<?> match(double[] values, boolean isInt) {
		KernelSeriesMatcher matcher = new KernelSeriesMatcher(values.length);
		matcher.accept(values, values.length);
		Expression<?> r = matcher.getExpression(kernel(), isInt);
		if (r != null) log(Arrays.toString(values) + " -> " + r.getExpression(lang));
		return r;
	}

	/**
	 * Asserts that the expression takes the given values at kernel indices 0..n-1.
	 */
	private void assertReproduces(double[] values, Expression<?> r) {
		for (int i = 0; i < values.length; i++) {
			double actual = r.value(new IndexValues().put(kernel(), i)).doubleValue();
			Assert.assertEquals(r.getExpression(lang) + " at " + i, values[i], actual, 0.0);
		}
	}

	/**
	 * Asserts that the expression takes the sequence's values at every position.
	 */
	private void assertReproduces(IndexSequence seq, Expression<?> r) {
		double[] values = new double[seq.length()];
		for (int i = 0; i < values.length; i++) values[i] = seq.valueAt(i).doubleValue();
		assertReproduces(values, r);
	}

	/**
	 * Generates a sequence that is either an arithmetic progression with random
	 * parameters or a mask with a random run.
	 */
	private double[] randomStructuredSequence(Random random) {
		int granularity = 1 + random.nextInt(3);
		int steps = 2 + random.nextInt(5);
		int period = granularity * steps;
		int len = period * (1 + random.nextInt(3));
		double[] values = new double[len];

		if (random.nextInt(4) == 0) {
			int start = 1 + random.nextInt(len - 1);
			int run = 1 + random.nextInt(len - start);
			int value = 1 + random.nextInt(9);
			for (int i = start; i < start + run; i++) values[i] = value;
			return values;
		}

		int initial = random.nextInt(7) - 3;
		int delta = random.nextBoolean() ? 1 + random.nextInt(5) : -(1 + random.nextInt(5));
		boolean periodic = random.nextBoolean() && len > period;

		for (int i = 0; i < len; i++) {
			long step = (periodic ? i % period : i) / granularity;
			values[i] = initial + step * delta;
		}

		return values;
	}

	/**
	 * Generates a sequence of random values in a small range.
	 */
	private double[] randomNoise(Random random) {
		double[] values = new double[12 + random.nextInt(20)];
		for (int i = 0; i < values.length; i++) values[i] = random.nextInt(50) + 1;
		return values;
	}

	/**
	 * A product that counts how many positions it has been asked to evaluate.
	 */
	private static class CountingProduct extends Expression<Integer> {
		/** Shared counter of evaluated positions, incremented by every instance. */
		private final AtomicInteger positions;

		/**
		 * Creates a counting product of the two operands.
		 *
		 * @param a the first operand
		 * @param b the second operand
		 * @param positions the shared counter to increment per evaluated position
		 */
		CountingProduct(Expression<?> a, Expression<?> b, AtomicInteger positions) {
			super(Integer.class, a, b);
			this.positions = positions;
		}

		@Override
		public String getExpression(LanguageOperations lang) {
			return "(" + getChildren().get(0).getExpression(lang) + " * " +
					getChildren().get(1).getExpression(lang) + ")";
		}

		@Override
		public boolean isValue(IndexValues values) {
			return getChildren().stream().allMatch(c -> c.isValue(values));
		}

		@Override
		public Number computeValue(IndexValues indexValues) {
			positions.incrementAndGet();
			return getChildren().get(0).value(indexValues).longValue() *
					getChildren().get(1).value(indexValues).longValue();
		}

		@Override
		protected double[] computeValues(IndexRange range) {
			positions.addAndGet(range.getLength());

			double[] a = getChildren().get(0).values(range);
			double[] b = getChildren().get(1).values(range);
			double[] out = new double[range.getLength()];

			for (int i = 0; i < out.length; i++) {
				out[i] = IndexRange.exact((long) a[i] * (long) b[i]);
			}

			return out;
		}

		@Override
		protected Expression<Integer> recreate(List<Expression<?>> children) {
			return new CountingProduct(children.get(0), children.get(1), positions);
		}
	}
}
