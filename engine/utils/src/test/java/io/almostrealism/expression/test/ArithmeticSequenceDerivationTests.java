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
import io.almostrealism.expression.ArithmeticGenerator;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.ExpressionProperties;
import io.almostrealism.expression.LongConstant;
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
import io.almostrealism.sequence.IndexSequence;
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
	 * Dividing by {@link Long#MIN_VALUE} cannot be represented, since negating it
	 * overflows back to itself; recursing on the negated operand must not loop forever,
	 * and instead reports that no arithmetic sequence represents the quotient.
	 */
	@Test(timeout = 30000)
	public void dividedExactlyByLongMinValueReturnsNull() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(2, 1, 8);
		Assert.assertNull(seq.dividedExactly(Long.MIN_VALUE));
	}

	/**
	 * {@link ArithmeticIndexSequence#plus(ArithmeticIndexSequence)} combines the scale
	 * of two non-constant sequences by addition. When that addition overflows, the sum
	 * must be reported as not representable rather than silently wrapping into an
	 * arithmetic sequence with an incorrect scale.
	 */
	@Test(timeout = 30000)
	public void plusScaleOverflowReturnsNull() {
		ArithmeticIndexSequence a = new ArithmeticIndexSequence(0, Long.MAX_VALUE, 1, 8, 8);
		ArithmeticIndexSequence b = new ArithmeticIndexSequence(0, 1, 1, 8, 8);
		Assert.assertNull("an overflowing scale addition must not silently wrap", a.plus(b));
	}

	/**
	 * {@link ArithmeticIndexSequence#plus(ArithmeticIndexSequence)} combines the offset
	 * of a sequence with a constant (zero-scale) sequence by addition. When that addition
	 * overflows, the sum must be reported as not representable.
	 */
	@Test(timeout = 30000)
	public void plusOffsetOverflowReturnsNull() {
		ArithmeticIndexSequence a = new ArithmeticIndexSequence(Long.MAX_VALUE, 1, 1, 8, 8);
		ArithmeticIndexSequence constant = new ArithmeticIndexSequence(1, 0, 1, 8, 8);
		Assert.assertNull("an overflowing offset addition must not silently wrap", a.plus(constant));
	}

	/**
	 * {@link ArithmeticIndexSequence#sumOf(List)} adds its terms in order under
	 * {@link ArithmeticIndexSequence#plus(ArithmeticIndexSequence)}: a single term is its own
	 * sum, several terms agree with the element-wise sum, and a partial sum that is not an
	 * arithmetic sequence (terms stepping at different granularities) leaves the whole sum
	 * not representable.
	 */
	@Test(timeout = 30000)
	public void sumOfAddsTermsInOrder() {
		ArithmeticIndexSequence a = new ArithmeticIndexSequence(1, 2, 1, 8, 8);
		ArithmeticIndexSequence b = new ArithmeticIndexSequence(3, 5, 1, 8, 8);
		ArithmeticIndexSequence constant = new ArithmeticIndexSequence(4, 0, 1, 8, 8);
		ArithmeticIndexSequence coarse = new ArithmeticIndexSequence(0, 1, 2, 8, 8);

		Assert.assertSame(a, ArithmeticIndexSequence.sumOf(List.of(a)));

		ArithmeticIndexSequence sum = ArithmeticIndexSequence.sumOf(List.of(a, b, constant));
		Assert.assertEquals(a.plus(b).plus(constant), sum);

		for (long pos = 0; pos < 8; pos++) {
			long expected = a.valueAt(pos).longValue() + b.valueAt(pos).longValue()
					+ constant.valueAt(pos).longValue();
			Assert.assertEquals("mismatch at position " + pos, expected, sum.valueAt(pos).longValue());
		}

		Assert.assertNull("terms of different granularity have no arithmetic sum",
				ArithmeticIndexSequence.sumOf(List.of(a, coarse, constant)));
	}

	/**
	 * {@link ArithmeticIndexSequence#quotientOf(List, long)} divides the sum of its terms
	 * exactly. When the terms do not add up to an arithmetic sequence, it drops a
	 * non-negative remainder bounded below a factor of the divisor:
	 * {@code (8 * (i % 10) + i % 3 + i % 5) / 8} is {@code i % 10}. A remainder that can
	 * reach that factor, or a coarse term that can be negative, leaves the quotient
	 * underivable, and a zero divisor is rejected.
	 */
	@Test(timeout = 30000)
	public void quotientOfDropsBoundedRemainder() {
		long len = 240;
		ArithmeticIndexSequence coarse = new ArithmeticIndexSequence(0, 8, 1, 10, len);
		ArithmeticIndexSequence remainderA = new ArithmeticIndexSequence(0, 1, 1, 3, len);
		ArithmeticIndexSequence remainderB = new ArithmeticIndexSequence(0, 1, 1, 5, len);

		ArithmeticIndexSequence quotient = ArithmeticIndexSequence.quotientOf(List.of(coarse, remainderA, remainderB), 8);
		Assert.assertEquals(new ArithmeticIndexSequence(0, 1, 1, 10, len), quotient);

		for (long pos = 0; pos < len; pos++) {
			long expected = (coarse.valueAt(pos).longValue() + remainderA.valueAt(pos).longValue()
					+ remainderB.valueAt(pos).longValue()) / 8;
			Assert.assertEquals("mismatch at position " + pos, expected, quotient.valueAt(pos).longValue());
		}

		ArithmeticIndexSequence x = new ArithmeticIndexSequence(0, 2, 1, len, len);
		ArithmeticIndexSequence y = new ArithmeticIndexSequence(8, 6, 1, len, len);
		Assert.assertEquals(new ArithmeticIndexSequence(2, 2, 1, len, len),
				ArithmeticIndexSequence.quotientOf(List.of(x, y), 4));

		ArithmeticIndexSequence reachesFactor = new ArithmeticIndexSequence(0, 1, 1, 9, len);
		Assert.assertNull("a remainder reaching the shared factor can carry into the quotient",
				ArithmeticIndexSequence.quotientOf(List.of(coarse, reachesFactor), 8));
		Assert.assertNull("a possibly negative coarse term keeps the remainder",
				ArithmeticIndexSequence.quotientOf(List.of(coarse.negated(), remainderA), 8));

		try {
			ArithmeticIndexSequence.quotientOf(List.of(coarse), 0);
			Assert.fail("A zero divisor must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("zero"));
		}
	}

	/**
	 * {@link ArithmeticIndexSequence#quotientOf(List, long)} has no representable result
	 * for a divisor of {@link Long#MIN_VALUE}. It must report that as {@code null}, as
	 * {@link ArithmeticIndexSequence#dividedExactly(long)} does. It must not throw while
	 * looking for a bounded remainder, because the absolute value of that divisor
	 * overflows and the zero term's common factor of {@code 0} has a greatest common
	 * divisor of {@code 2^63} with it.
	 */
	@Test(timeout = 30000)
	public void quotientOfMinimumDivisorIsNotRepresentable() {
		long len = 240;
		ArithmeticIndexSequence zero = new ArithmeticIndexSequence(0, 0, 1, len, len);
		ArithmeticIndexSequence coarse = new ArithmeticIndexSequence(0, 8, 1, 10, len);
		ArithmeticIndexSequence fine = new ArithmeticIndexSequence(0, 1, 1, 3, len);

		Assert.assertNull("terms of different modulus have no arithmetic sum",
				ArithmeticIndexSequence.sumOf(List.of(zero, coarse, fine)));
		Assert.assertNull(ArithmeticIndexSequence.quotientOf(List.of(zero, coarse, fine), Long.MIN_VALUE));
		Assert.assertNull(ArithmeticIndexSequence.quotientOf(List.of(coarse), Long.MIN_VALUE));
		Assert.assertEquals(new ArithmeticIndexSequence(0, -1, 1, 10, len),
				ArithmeticIndexSequence.quotientOf(List.of(coarse), -8));
	}

	/**
	 * {@link ArithmeticIndexSequence#remainderOf(List, long)} drops the terms that are
	 * multiples of the modulus before reducing the sum of the rest:
	 * {@code (12 * (i % 5) + i % 4) % 4} is {@code i % 4}. When every term is a multiple
	 * the remainder is zero everywhere; when a dropped term leaves a possibly negative
	 * rest, truncating and flooring remainders disagree and nothing is derived; and a
	 * modulus that is not positive is rejected.
	 */
	@Test(timeout = 30000)
	public void remainderOfDropsMultiplesOfTheModulus() {
		long len = 240;
		ArithmeticIndexSequence multiple = new ArithmeticIndexSequence(0, 12, 1, 5, len);
		ArithmeticIndexSequence rest = new ArithmeticIndexSequence(0, 1, 1, 4, len);

		ArithmeticIndexSequence remainder = ArithmeticIndexSequence.remainderOf(List.of(multiple, rest), 4);
		Assert.assertEquals(rest, remainder);

		for (long pos = 0; pos < len; pos++) {
			long expected = (multiple.valueAt(pos).longValue() + rest.valueAt(pos).longValue()) % 4;
			Assert.assertEquals("mismatch at position " + pos, expected, remainder.valueAt(pos).longValue());
		}

		ArithmeticIndexSequence otherMultiple = new ArithmeticIndexSequence(8, 4, 1, 3, len);
		Assert.assertEquals(new ArithmeticIndexSequence(0, 0, 1, len, len),
				ArithmeticIndexSequence.remainderOf(List.of(multiple, otherMultiple), 4));
		Assert.assertNull("dropping a multiple beside a negative rest changes a truncating remainder",
				ArithmeticIndexSequence.remainderOf(List.of(multiple, rest.negated()), 4));

		try {
			ArithmeticIndexSequence.remainderOf(List.of(rest), 0);
			Assert.fail("A modulus that is not positive must be rejected");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("positive"));
		}
	}

	/**
	 * {@link ArithmeticIndexSequence#remainderOf(List, long)} must not drop a negative
	 * multiple of the modulus beside a non-negative rest. Under truncating remainders
	 * {@code (-4 + (i % 2)) % 2} is {@code 0, -1}, but dropping {@code -4} would derive
	 * {@code i % 2}, which is {@code 0, 1}. When every term is a multiple, the remainder is
	 * zero whatever their signs.
	 */
	@Test(timeout = 30000)
	public void remainderOfKeepsNegativeMultiples() {
		long len = 240;
		ArithmeticIndexSequence negativeMultiple = new ArithmeticIndexSequence(-4, 0, 1, len, len);
		ArithmeticIndexSequence rest = new ArithmeticIndexSequence(0, 1, 1, 2, len);

		ArithmeticIndexSequence remainder = ArithmeticIndexSequence.remainderOf(List.of(negativeMultiple, rest), 2);
		if (remainder != null) {
			for (long pos = 0; pos < len; pos++) {
				long expected = (negativeMultiple.valueAt(pos).longValue() + rest.valueAt(pos).longValue()) % 2;
				Assert.assertEquals("mismatch at position " + pos, expected, remainder.valueAt(pos).longValue());
			}
		}

		Assert.assertNull("dropping a negative multiple changes a truncating remainder", remainder);

		ArithmeticIndexSequence otherMultiple = new ArithmeticIndexSequence(0, 6, 1, 3, len);
		Assert.assertEquals(new ArithmeticIndexSequence(0, 0, 1, len, len),
				ArithmeticIndexSequence.remainderOf(List.of(negativeMultiple, otherMultiple), 2));
	}

	/**
	 * {@link ArithmeticIndexSequence#remainderOf(List, long)} may drop a negative multiple
	 * of the modulus when the rest is non-negative and the full sum is never negative:
	 * {@code (x - 8) % 4} with {@code x = 8 + i} is {@code x % 4}. Once the full sum can be
	 * negative ({@code x = 7 + i}), nothing is derived, and a sum of minimums that would
	 * overflow is treated as possibly negative.
	 */
	@Test(timeout = 30000)
	public void remainderOfDropsNegativeMultipleBesideLargerRest() {
		long len = 240;
		ArithmeticIndexSequence negativeMultiple = new ArithmeticIndexSequence(-8, 0, 1, len, len);
		ArithmeticIndexSequence x = new ArithmeticIndexSequence(8, 1, 1, len, len);

		ArithmeticIndexSequence remainder = ArithmeticIndexSequence.remainderOf(List.of(negativeMultiple, x), 4);
		Assert.assertNotNull("(x - 8) % 4 with x >= 8 is a progression", remainder);
		Assert.assertEquals(new ArithmeticIndexSequence(0, 1, 1, 4, len), remainder);

		for (long pos = 0; pos < len; pos++) {
			long expected = (negativeMultiple.valueAt(pos).longValue() + x.valueAt(pos).longValue()) % 4;
			Assert.assertEquals("mismatch at position " + pos, expected, remainder.valueAt(pos).longValue());
		}

		ArithmeticIndexSequence belowMultiple = new ArithmeticIndexSequence(7, 1, 1, len, len);
		Assert.assertNull("the full sum reaches -1, so truncating and flooring disagree",
				ArithmeticIndexSequence.remainderOf(List.of(negativeMultiple, belowMultiple), 4));

		ArithmeticIndexSequence hugeMultiple = new ArithmeticIndexSequence(Long.MAX_VALUE - 3, 0, 1, len, len);
		ArithmeticIndexSequence large = new ArithmeticIndexSequence(4, 0, 1, len, len);
		ArithmeticIndexSequence rest = new ArithmeticIndexSequence(0, 1, 1, 4, len);
		Assert.assertNull("an overflowing sum of minimums is treated as possibly negative",
				ArithmeticIndexSequence.remainderOf(List.of(hugeMultiple, large, rest), 4));
	}

	/**
	 * {@link ArithmeticIndexSequence#scaled(long)} multiplies both the offset and the
	 * scale by the operand. When that multiplication overflows, the result must be
	 * reported as not representable rather than silently wrapping into an arithmetic
	 * sequence with an incorrect scale.
	 */
	@Test(timeout = 30000)
	public void scaledOverflowReturnsNull() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, Long.MAX_VALUE, 1, 8, 8);
		Assert.assertNull("an overflowing scale multiplication must not silently wrap", seq.scaled(2));
	}

	/**
	 * {@link ArithmeticIndexSequence#multiply(long)} delegates to
	 * {@link ArithmeticIndexSequence#scaled(long)}; when that overflows, it must fall
	 * back to the default element-wise multiplication rather than propagate {@code null}
	 * or throw, matching the fallback {@link ArithmeticIndexSequence#divide(long)} and
	 * {@link ArithmeticIndexSequence#mod(long)} already use for their own exact paths.
	 */
	@Test(timeout = 30000)
	public void multiplyFallsBackWhenScaledOverflows() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, Long.MAX_VALUE, 1, 4, 4);
		IndexSequence result = seq.multiply(2);
		Assert.assertNotNull("multiply must not propagate a null result", result);
		Assert.assertEquals(4, result.lengthLong());
		Assert.assertEquals("position 0 never touches the overflowing scale",
				0L, result.valueAt(0).longValue());
	}

	/**
	 * {@link Product#arithmeticSequence(Index, long)} multiplies a product's constant
	 * factors together to scale the progression of its one non-constant factor. With
	 * {@link Product#enableConstantExtraction} disabled, {@link Product#of} keeps
	 * multiple constant factors unfolded, so this exercises that accumulation directly:
	 * two constants whose product overflows a {@code long} must not derive a progression
	 * with a silently wrapped scale, and ordinary evaluation of the product must still
	 * agree with plain arithmetic.
	 */
	@Test(timeout = 30000)
	public void productArithmeticSequenceConstantOverflowReturnsNull() {
		boolean previous = Product.enableConstantExtraction;
		Product.enableConstantExtraction = false;

		try {
			long big1 = 3_000_000_000L;
			long big2 = 4_000_000_000L;
			Expression<?> a = kernel().imod(4);
			Expression<?> product = Product.of(a, e(big1), e(big2));

			Assert.assertNull("an overflowing constant accumulation must not derive a wrapped progression",
					product.arithmeticSequence(kernel(), 4));

			for (int i = 0; i < 4; i++) {
				long expected = (i % 4) * big1 * big2;
				long actual = product.value(new IndexValues().put(kernel(), i)).longValue();
				Assert.assertEquals("at " + i, expected, actual);
			}
		} finally {
			Product.enableConstantExtraction = previous;
		}
	}

	/**
	 * {@link ExpressionProperties#constantIntegerFactor()} multiplies a product's constant
	 * factors together to report a value every evaluation of the product is a multiple of.
	 * With {@link Product#enableConstantExtraction} disabled, {@link Product#of} keeps
	 * multiple constant factors unfolded, so this exercises that accumulation directly:
	 * two constants whose product overflows a {@code long} must report {@code 1} (no known
	 * factor) rather than a silently wrapped, incorrect factor that
	 * {@link Quotient#tryBoundedRemainderSimplify(Sum, long)} would otherwise trust to
	 * decide which terms of a sum are safe to drop.
	 */
	@Test(timeout = 30000)
	public void constantIntegerFactorOverflowReportsNoFactor() {
		boolean previous = Product.enableConstantExtraction;
		Product.enableConstantExtraction = false;

		try {
			long big1 = 3_000_000_000L;
			long big2 = 4_000_000_000L;
			Expression<?> product = Product.of(kernel().imod(2), e(big1), e(big2));

			Assert.assertEquals("an overflowing constant accumulation must report no known factor",
					1L, product.constantIntegerFactor());
		} finally {
			Product.enableConstantExtraction = previous;
		}
	}

	/**
	 * Each expression reports the factor its own structure establishes through
	 * {@link ExpressionProperties#constantIntegerFactor()}: a constant its magnitude, a
	 * product, including the {@link ArithmeticGenerator} a product can be, the product of
	 * its constant factors, and any other expression {@code 1}.
	 */
	@Test(timeout = 30000)
	public void constantIntegerFactorIsReportedByEachExpression() {
		Assert.assertEquals(4L, e(-4).constantIntegerFactor());
		Assert.assertEquals(6L, Product.of(kernel().imod(5), e(6)).constantIntegerFactor());

		Expression<?> generator = ArithmeticGenerator.create(kernel(), 6, 4, 16);
		Assert.assertTrue(generator instanceof ArithmeticGenerator);
		Assert.assertEquals(6L, generator.constantIntegerFactor());

		Assert.assertEquals(1L, Sum.of(kernel().imod(5), e(6)).constantIntegerFactor());
	}

	/**
	 * A factor whose magnitude does not fit in a {@code long}, a constant or a product whose
	 * constant factors multiply to {@link Long#MIN_VALUE}, is reported as {@code 1} (no known
	 * factor) rather than as the negative value {@link Math#abs(long)} would wrap it to.
	 */
	@Test(timeout = 30000)
	public void constantIntegerFactorIsPositiveAtTheLongMinimum() {
		Assert.assertEquals(1L, new LongConstant(Long.MIN_VALUE).constantIntegerFactor());
		Assert.assertEquals(Long.MAX_VALUE, new LongConstant(-Long.MAX_VALUE).constantIntegerFactor());

		Expression<?> product = Product.of(kernel().imod(5), new LongConstant(Long.MIN_VALUE));
		Assert.assertTrue(product instanceof Product);
		Assert.assertEquals(1L, product.constantIntegerFactor());
	}

	/**
	 * The interval arithmetic used by {@link Product#lowerBound} to bound a product with
	 * a possibly-negative factor multiplies combinations of each factor's lower and upper
	 * bound together across every child. When two children each carry a bound whose
	 * magnitude is close to {@code Long.MAX_VALUE}, those intermediate products overflow;
	 * the bound must be reported as unknown rather than silently wrapping to an incorrect,
	 * arbitrary value that a caller such as {@link Expression#isPossiblyNegative()} would
	 * otherwise trust.
	 */
	@Test(timeout = 30000)
	public void productLowerBoundOverflowIsUnknown() {
		long c = Long.MAX_VALUE / 4;
		Expression<?> coarse1 = kernel().imod(10).multiply(-c);
		Expression<?> coarse2 = kernel().imod(10).multiply(-c);
		Assert.assertTrue(coarse1.isPossiblyNegative());
		Assert.assertTrue(coarse2.isPossiblyNegative());

		Expression<?> product = Product.of(coarse1, coarse2);
		Assert.assertTrue("an overflowing product bound must be reported as unknown",
				product.lowerBound().isEmpty());
	}

	/**
	 * The product/divisor fold in {@link Quotient#create} multiplies a numerator's constant
	 * factors together to see whether the divisor divides them evenly. With
	 * {@link Product#enableConstantExtraction} disabled, {@link Product#of} keeps multiple
	 * constant factors unfolded, so this exercises that multiplication directly: two
	 * constants whose product overflows a {@code long} must not silently wrap into an
	 * incorrect fold decision, and the quotient must keep agreeing with plain arithmetic
	 * on the original, unfolded product.
	 */
	@Test(timeout = 30000)
	public void quotientProductDivisorFoldSkippedOnConstantOverflow() {
		boolean previous = Product.enableConstantExtraction;
		Product.enableConstantExtraction = false;

		try {
			long big1 = 3_000_000_000L;
			long big2 = 4_000_000_000L;
			Expression<?> a = kernel().imod(4);
			Expression<?> numerator = Product.of(a, e(big1), e(big2));
			Expression<?> quotient = numerator.divide(e(big1));

			for (int i = 0; i < 4; i++) {
				long expected = (i * big1 * big2) / big1;
				long actual = quotient.value(new IndexValues().put(kernel(), i)).longValue();
				Assert.assertEquals("at " + i, expected, actual);
			}
		} finally {
			Product.enableConstantExtraction = previous;
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
