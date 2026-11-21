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

package io.almostrealism.sequence.test;

import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.IndexSequence;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests the bounding contract of {@link IndexSequence#min()} and
 * {@link IndexSequence#max()} for floating-point sequences.
 *
 * <p>{@link IndexSequence#min()} is documented as returning "the minimum value in
 * this sequence" and {@link IndexSequence#max()} "the maximum value". Because the
 * result is returned as a {@code long}, a fractional value must be rounded, and the
 * only rounding that preserves the meaning of the words is outward: a minimum rounds
 * <em>down</em> so it never exceeds any element (a lower bound), and a maximum rounds
 * <em>up</em> so it never falls below any element (an upper bound). {@code max()}
 * rounds up with {@code Math.ceil}; the symmetric choice for {@code min()} is
 * {@code Math.floor}.</p>
 */
public class IndexSequenceMinTest extends TestSuiteBase {

	/**
	 * A floating-point sequence whose minimum is {@code -2.5}. {@code min()} must not
	 * report a value greater than an element that is actually present: {@code -2.5}
	 * is in the sequence, so {@code min() <= -2.5} must hold. Rounding the minimum up
	 * to {@code -2} violates this, because {@code -2 > -2.5} — the reported "minimum"
	 * is larger than a real element.
	 *
	 * <p>The exact floored value {@code -3} is asserted rather than a mere inequality,
	 * because a value such as {@link Long#MIN_VALUE} would satisfy a lower-bound
	 * inequality without being the intended result.</p>
	 */
	@Test(timeout = 10000)
	public void minimumIsALowerBoundForFractionalValues() {
		IndexSequence seq = ArrayIndexSequence.of(Double.class, new double[] { -2.5, 10.0 });

		long min = seq.min();

		Assert.assertEquals(
				"min() == " + min + " but the sequence minimum -2.5 must floor to -3",
				-3, min);
	}

	/**
	 * The minimum must be a lower bound for <em>every</em> element of the sequence,
	 * mirroring the upper-bound guarantee that {@link IndexSequence#max()} already
	 * provides. This pins the symmetry between the two methods.
	 */
	@Test(timeout = 10000)
	public void minAndMaxBoundEveryElement() {
		double[] values = { -2.5, 1.25, 7.75 };
		IndexSequence seq = ArrayIndexSequence.of(Double.class, values);

		long min = seq.min();
		long max = seq.max();

		for (double v : values) {
			Assert.assertTrue("min() == " + min + " is not <= element " + v, min <= v);
			Assert.assertTrue("max() == " + max + " is not >= element " + v, max >= v);
		}
	}

	/**
	 * A constant fractional sequence takes the {@code isConstant()} short-circuit in
	 * {@link IndexSequence#min()}. That path must also floor: for a sequence whose only
	 * value is {@code -2.5}, {@code min()} must be {@code <= -2.5}. Returning
	 * {@code valueAt(0).longValue()} truncates toward zero to {@code -2}, which is greater
	 * than the single element present and so violates the lower-bound contract.
	 */
	@Test(timeout = 10000)
	public void constantFractionalMinimumIsALowerBound() {
		IndexSequence seq = ArrayIndexSequence.of(Double.valueOf(-2.5), 4);

		Assert.assertTrue(seq.isConstant());

		long min = seq.min();

		// The constant -2.5 floors to -3; assert the exact value so the test
		// verifies the intended rounding rather than only an inequality.
		Assert.assertEquals(
				"min() == " + min + " but the constant value -2.5 must floor to -3",
				-3, min);
	}

	/**
	 * A constant sequence may hold an exact numeric type (such as {@link BigInteger} or
	 * {@link BigDecimal}) whose magnitude cannot be represented exactly as a
	 * {@code double}. {@link IndexSequence#min()} must preserve that precision rather
	 * than rounding the value through a {@code double} conversion, which would both
	 * perturb large integral values and round a {@link BigDecimal} upward before
	 * flooring, breaking the lower-bound contract.
	 */
	@Test(timeout = 10000)
	public void constantPreservesExactNumericPrecision() {
		// 2^53 + 1: the smallest positive long not exactly representable as a double.
		long inexactAsDouble = 9007199254740993L;
		Assert.assertNotEquals(inexactAsDouble, (long) (double) inexactAsDouble);

		IndexSequence integral = ArrayIndexSequence.of(BigInteger.valueOf(inexactAsDouble), 3);
		Assert.assertTrue(integral.isConstant());
		Assert.assertEquals(inexactAsDouble, integral.min());

		// A BigDecimal just above the same value must floor down to it, not round up.
		IndexSequence decimal = ArrayIndexSequence.of(
				new BigDecimal(BigInteger.valueOf(inexactAsDouble)).add(new BigDecimal("0.5")), 3);
		Assert.assertTrue(decimal.isConstant());
		Assert.assertEquals(inexactAsDouble, decimal.min());
	}

	/**
	 * A <em>non-constant</em> sequence of exact numeric types must also floor each
	 * element without losing precision. The earlier fix only guarded the
	 * {@code isConstant()} short-circuit; the general path still routed every value
	 * through {@code doubleValues()} before flooring, so large {@link BigDecimal}
	 * elements could collapse together and report a "minimum" greater than an actual
	 * element. Here the two distinct values {@code 2^53+1.5} and {@code 2^53+2.5}
	 * both round to {@code 2^53+2.0} as doubles, so a double-based minimum would be
	 * {@code 9007199254740994} — greater than the first element.
	 */
	@Test(timeout = 10000)
	public void nonConstantPreservesExactNumericPrecision() {
		// 2^53 + 1: the smallest positive long not exactly representable as a double.
		long base = 9007199254740993L;
		BigDecimal first = new BigDecimal(BigInteger.valueOf(base)).add(new BigDecimal("0.5"));
		BigDecimal second = new BigDecimal(BigInteger.valueOf(base + 1)).add(new BigDecimal("0.5"));

		IndexSequence seq = ArrayIndexSequence.of(new Number[] { first, second });

		Assert.assertFalse(seq.isConstant());

		// first floors to 2^53+1 exactly; a double round-trip would report 2^53+2.
		Assert.assertEquals(base, seq.min());
	}

	/**
	 * Values whose floor lies below {@link Long#MIN_VALUE} must saturate to
	 * {@link Long#MIN_VALUE} rather than silently wrapping through
	 * {@link java.math.BigInteger#longValue()}. An out-of-range {@link BigInteger}
	 * such as {@code Long.MIN_VALUE - 1} wraps to {@link Long#MAX_VALUE} under the
	 * naive conversion, which is greater than the element and so grossly violates the
	 * lower-bound contract.
	 */
	@Test(timeout = 10000)
	public void outOfRangeValuesSaturateToLongBounds() {
		BigInteger belowRange = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE);
		Assert.assertEquals(Long.MIN_VALUE, IndexSequence.floorToLong(belowRange));

		BigInteger aboveRange = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
		Assert.assertEquals(Long.MAX_VALUE, IndexSequence.floorToLong(aboveRange));

		// A BigDecimal below the long range must floor and then saturate downward.
		BigDecimal decimalBelowRange =
				new BigDecimal(BigInteger.valueOf(Long.MIN_VALUE)).subtract(new BigDecimal("0.5"));
		Assert.assertEquals(Long.MIN_VALUE, IndexSequence.floorToLong(decimalBelowRange));

		// A constant sequence holding the out-of-range value must stay a lower bound.
		IndexSequence seq = ArrayIndexSequence.of(belowRange, 2);
		Assert.assertEquals(Long.MIN_VALUE, seq.min());
	}

	/**
	 * A {@link BigDecimal} whose magnitude is known to saturate must not materialize its
	 * full integral representation before clamping. {@code new BigDecimal("1E+1000000000")}
	 * rounds to a billion-digit {@link BigInteger} under {@code setScale(0, ...).toBigInteger()},
	 * which would exhaust memory even though the result is already known to be
	 * {@link Long#MAX_VALUE}. The {@code long} bounds must be checked first, so this test
	 * both pins the saturated result and, via its timeout, that it is reached cheaply.
	 */
	@Test(timeout = 10000)
	public void extremeMagnitudeBigDecimalSaturatesWithoutMaterializing() {
		BigDecimal huge = new BigDecimal("1E+1000000000");
		Assert.assertEquals(Long.MAX_VALUE, IndexSequence.floorToLong(huge));
		Assert.assertEquals(Long.MAX_VALUE, IndexSequence.ceilToLong(huge));

		BigDecimal hugeNegative = new BigDecimal("-1E+1000000000");
		Assert.assertEquals(Long.MIN_VALUE, IndexSequence.floorToLong(hugeNegative));
		Assert.assertEquals(Long.MIN_VALUE, IndexSequence.ceilToLong(hugeNegative));

		// The same value flowing through a constant sequence stays a correct bound.
		IndexSequence seq = ArrayIndexSequence.of(huge, 2);
		Assert.assertEquals(Long.MAX_VALUE, seq.min());
		Assert.assertEquals(Long.MAX_VALUE, seq.max());
	}

	/**
	 * {@link ArrayIndexSequence#of(Number[])} accepts mixed {@link Number}
	 * implementations, so the type of the first element must not select a primitive
	 * fast path. With an {@link Integer} first and a negative fractional
	 * {@link Double} later, an {@code intValues()}-based minimum truncates
	 * {@code -2.5} to {@code -2}, which is greater than an element that is present.
	 */
	@Test(timeout = 10000)
	public void mixedIntegerAndFractionalElementsAreFloored() {
		IndexSequence seq = ArrayIndexSequence.of(new Number[] { 0, -2.5 });

		Assert.assertFalse(seq.isConstant());
		Assert.assertEquals(-3, seq.min());
	}

	/**
	 * Integral {@link Number} implementations other than the primitive box types
	 * must also be handled exactly. An {@link AtomicLong} holding {@code 2^53 + 1}
	 * rounds to {@code 2^53} through {@code doubleValue()}, so a double-based
	 * minimum would report a value below the true minimum element.
	 */
	@Test(timeout = 10000)
	public void atomicLongAboveDoublePrecisionIsExact() {
		// 2^53 + 1: the smallest positive long not exactly representable as a double.
		long inexactAsDouble = 9007199254740993L;
		Assert.assertEquals(inexactAsDouble, IndexSequence.floorToLong(new AtomicLong(inexactAsDouble)));

		IndexSequence seq = ArrayIndexSequence.of(new Number[] {
				new AtomicLong(inexactAsDouble + 2), new AtomicLong(inexactAsDouble) });

		Assert.assertFalse(seq.isConstant());
		Assert.assertEquals(inexactAsDouble, seq.min());
	}

	/**
	 * {@link IndexSequence#max()} must not dispatch on the type of the first element
	 * either. With an {@link Integer} first and a positive fractional {@link Double}
	 * later, a {@code longValues()}-based maximum truncates {@code 2.5} to {@code 2},
	 * which is less than an element that is present.
	 */
	@Test(timeout = 10000)
	public void mixedIntegerAndFractionalElementsAreCeiled() {
		IndexSequence seq = ArrayIndexSequence.of(new Number[] { 0, 2.5 });

		Assert.assertFalse(seq.isConstant());
		Assert.assertEquals(3, seq.max());
	}

	/**
	 * The constant short-circuit of {@link IndexSequence#max()} must ceil a fractional
	 * value, and exact numeric types must be ceiled without a {@code double} round-trip
	 * and saturated outside the {@code long} range.
	 */
	@Test(timeout = 10000)
	public void maximumIsAnExactUpperBound() {
		IndexSequence constant = ArrayIndexSequence.of(Double.valueOf(2.5), 4);
		Assert.assertTrue(constant.isConstant());
		Assert.assertEquals(3, constant.max());

		// 2^53 + 1: the smallest positive long not exactly representable as a double.
		long inexactAsDouble = 9007199254740993L;
		BigDecimal justBelow = new BigDecimal(BigInteger.valueOf(inexactAsDouble)).subtract(new BigDecimal("0.5"));
		Assert.assertEquals(inexactAsDouble, IndexSequence.ceilToLong(justBelow));
		Assert.assertEquals(inexactAsDouble, IndexSequence.ceilToLong(new AtomicLong(inexactAsDouble)));

		BigInteger aboveRange = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
		Assert.assertEquals(Long.MAX_VALUE, IndexSequence.ceilToLong(aboveRange));
		Assert.assertEquals(Long.MAX_VALUE, ArrayIndexSequence.of(aboveRange, 2).max());
	}
}
