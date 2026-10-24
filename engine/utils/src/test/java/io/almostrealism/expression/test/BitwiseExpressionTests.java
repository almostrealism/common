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
import io.almostrealism.expression.And;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.ShiftRight;
import io.almostrealism.expression.Xor;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the bitwise integer expressions ({@link And}, {@link Xor}, {@link ShiftRight}),
 * explicit 32-bit wraparound, and the PCG hash built from them. Every host-side evaluation
 * path must agree with an independent Java implementation, since the generated kernel code
 * performs the same integer operations.
 */
public class BitwiseExpressionTests extends TestSuiteBase implements ExpressionFeatures {
	/** Language operations used to render expressions. */
	private static final LanguageOperations lang = new LanguageOperationsStub();

	/** The 32-bit mask. */
	private static final long MASK = 0xFFFFFFFFL;

	/**
	 * Reference implementation of the 32-bit PCG hash (Jarzynski and Olano, 2020).
	 *
	 * @param value the input, reduced modulo 2^32
	 * @return the hash, in [0, 2^32)
	 */
	public static long pcg(long value) {
		int v = (int) value;
		int state = v * 747796405 + (int) 2891336453L;
		int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
		return Integer.toUnsignedLong((word >>> 22) ^ word);
	}

	/** Constant operands fold with Java semantics for each operator and width. */
	@Test(timeout = 10000)
	public void constantFolding() {
		Assert.assertEquals(0b0110, Xor.of(e(0b1100), e(0b1010)).intValue().getAsInt());
		Assert.assertEquals(0b11, ShiftRight.of(e(0b1100), e(2)).intValue().getAsInt());
		Assert.assertEquals(0b1000, And.of(e(0b1100), e(0b1010)).intValue().getAsInt());

		long big = 0x1234_5678_9ABCL;
		Expression<?> masked = And.of(new LongConstant(big), new LongConstant(MASK));
		Assert.assertEquals(Long.class, masked.getType());
		Assert.assertEquals(big & MASK, masked.longValue().getAsLong());
		Assert.assertEquals(big ^ 0xFFL, Xor.of(new LongConstant(big), e(0xFF)).longValue().getAsLong());
		Assert.assertEquals(big >> 13, ShiftRight.of(new LongConstant(big), e(13)).longValue().getAsLong());
	}

	/** The operators render as C infix operators with wrapped operands. */
	@Test(timeout = 10000)
	public void rendering() {
		Expression<?> x = kernel().xor(e(7));
		Assert.assertEquals(Integer.class, x.getType());
		Assert.assertTrue(x.getExpression(lang), x.getExpression(lang).contains(" ^ 7"));

		Expression<?> s = kernel().toLong(true).shiftRight(3);
		Assert.assertEquals(Long.class, s.getType());
		Assert.assertTrue(s.getExpression(lang), s.getExpression(lang).contains(" >> 3"));
	}

	/**
	 * A boolean operand is integral, as in the generated C code, so bitwise operators and
	 * unsigned wraparound accept it and produce an integer result.
	 */
	@Test(timeout = 10000)
	public void acceptsBoolean() {
		Expression<?> flag = new BooleanConstant(true);
		Assert.assertFalse(flag.isFP());
		Assert.assertEquals(Integer.class, Xor.of(flag, e(3)).getType());
		Assert.assertEquals(Long.class, flag.toUnsigned(Precision.FP32).getType());
	}

	/** Bitwise operators reject floating-point operands. */
	@Test(timeout = 10000)
	public void rejectsFloatingPoint() {
		try {
			Xor.of(kernel(), new DoubleConstant(1.0));
			Assert.fail("Xor accepted a floating-point operand");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue(expected.getMessage().contains("Double"));
		}

		try {
			ShiftRight.of(new DoubleConstant(1.0), e(1));
			Assert.fail("ShiftRight accepted a floating-point operand");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue(expected.getMessage().contains("Double"));
		}
	}

	/**
	 * Wrapping to the width of a precision is explicit: a negative value maps to its two's
	 * complement residue and a value above 2^32 to its low 32 bits at {@link Precision#FP32}
	 * (or its low 16 bits at {@link Precision#FP16}), with conservative bounds.
	 */
	@Test(timeout = 10000)
	public void unsigned() {
		Assert.assertEquals(MASK, e(-1).toUnsigned(Precision.FP32).longValue().getAsLong());
		Assert.assertEquals(5L, new LongConstant((1L << 32) + 5).toUnsigned(Precision.FP32).longValue().getAsLong());
		Assert.assertEquals(0xFFFFL, e(-1).toUnsigned(Precision.FP16).longValue().getAsLong());
		Assert.assertEquals(5L, e(0x10005).toUnsigned(Precision.FP16).longValue().getAsLong());

		Expression<?> wrapped = kernel().toUnsigned(Precision.FP32);
		Assert.assertFalse(wrapped.isPossiblyNegative());
		Assert.assertEquals(0L, wrapped.lowerBound(null).getAsLong());
		Assert.assertTrue(wrapped.upperBound(null).getAsLong() <= MASK);
	}

	/**
	 * Integer constants are folded exactly even when their product or sum exceeds 2^53,
	 * where a double accumulator would round.
	 */
	@Test(timeout = 10000)
	public void exactLongConstantFolding() {
		long a = 4294967291L;
		long b = 747796405L;
		Assert.assertEquals(a * b, new LongConstant(a).multiply(new LongConstant(b)).longValue().getAsLong());
		Assert.assertEquals(a * b + 2891336453L, new LongConstant(a * b)
				.add(new LongConstant(2891336453L)).longValue().getAsLong());
	}

	/**
	 * Substituting a constant for the index in a {@code Long} product or sum folds it
	 * exactly, even beyond 2^53 where a double accumulator would round.
	 */
	@Test(timeout = 10000)
	public void exactLongFoldingWithIndex() {
		KernelIndex index = kernel();
		long a = 4294967291L;
		long b = 747796405L;

		Expression<?> product = index.toLong(true).multiply(new LongConstant(b));
		Assert.assertEquals(a * b, product.withIndex(index, new LongConstant(a)).longValue().getAsLong());

		long big = (1L << 53) + 1;
		Expression<?> sum = index.toLong(true).add(new LongConstant(big));
		Assert.assertEquals(big + 2, sum.withIndex(index, new LongConstant(2)).longValue().getAsLong());
	}

	/**
	 * A {@link LongConstant} outside the {@code int} range reports its value as a double and
	 * as both of its bounds, so it is recognized as a constant and as negative or not.
	 */
	@Test(timeout = 10000)
	public void longConstantOutsideIntRange() {
		long large = 3L << 40;
		LongConstant positive = new LongConstant(large);
		Assert.assertEquals((double) large, positive.doubleValue().getAsDouble(), 0.0);
		Assert.assertEquals(large, positive.lowerBound(null).getAsLong());
		Assert.assertEquals(large, positive.upperBound(null).getAsLong());
		Assert.assertFalse(positive.isPossiblyNegative());

		LongConstant negative = new LongConstant(-large);
		Assert.assertEquals(-large, negative.lowerBound(null).getAsLong());
		Assert.assertTrue(negative.isPossiblyNegative());
	}

	/**
	 * Bounds of the bitwise operators are conservative: AND is bounded by the smaller
	 * non-negative operand and is non-negative when either operand is; XOR is bounded by the
	 * all-ones value covering the larger operand; a right shift divides the bound.
	 */
	@Test(timeout = 10000)
	public void bounds() {
		Expression<?> wide = kernel().toUnsigned(Precision.FP32);
		Expression<?> byte8 = kernel().bitwiseAnd(e(0xFF));

		Expression<?> and = And.of(wide, e(0x0F));
		Assert.assertEquals(0x0F, and.upperBound(null).getAsLong());
		Assert.assertEquals(0L, and.lowerBound(null).getAsLong());

		Expression<?> mixed = And.of(byte8, e(-16));
		Assert.assertFalse(mixed.isPossiblyNegative());
		Assert.assertEquals(0xFF, mixed.upperBound(null).getAsLong());

		Expression<?> xor = Xor.of(byte8, e(0x100));
		Assert.assertEquals(0x1FF, xor.upperBound(null).getAsLong());
		Assert.assertFalse(xor.isPossiblyNegative());

		Expression<?> shift = ShiftRight.of(wide, e(8));
		Assert.assertEquals(MASK >> 8, shift.upperBound(null).getAsLong());
		Assert.assertEquals(0L, shift.lowerBound(null).getAsLong());

		Expression<?> negative = Xor.of(kernel().toLong(true).multiply(-1), e(1));
		Assert.assertTrue(negative.isPossiblyNegative());
		Assert.assertFalse(negative.upperBound(null).isPresent());
	}

	/**
	 * When both operands of an AND may be negative, neither operand's upper bound limits the
	 * result ({@code 0x7FFF & -1 == 0x7FFF}, above the right operand's bound of 3), so no
	 * bound is reported. A non-negative right operand still bounds the result when the left
	 * may be negative.
	 */
	@Test(timeout = 10000)
	public void andBoundWithSignedOperands() {
		KernelIndex index = kernel();
		Expression<?> signedLeft = index.toLong(true).multiply(-1).add(new LongConstant(0x7FFF));
		Expression<?> signedRight = index.toLong(true).multiply(-1).add(new LongConstant(3));
		Assert.assertTrue(signedLeft.isPossiblyNegative());
		Assert.assertTrue(signedRight.isPossiblyNegative());

		Expression<?> both = And.of(signedLeft, signedRight);
		Assert.assertTrue(both.isPossiblyNegative());
		Assert.assertFalse(both.upperBound(null).isPresent());
		Assert.assertEquals(0x7FFBL, both.value(new IndexValues().put(index, 4)).longValue());

		Expression<?> masked = And.of(signedLeft, new LongConstant(0xFF));
		Assert.assertFalse(masked.isPossiblyNegative());
		Assert.assertEquals(0xFF, masked.upperBound(null).getAsLong());
	}

	/** A 32-bit operation truncates each operand to its low 32 bits, as {@code int} code does. */
	@Test(timeout = 10000)
	public void integerWidthTruncates() {
		Expression<?> xor = Xor.of(e(-1), e(0x0F));
		Assert.assertEquals(Integer.class, xor.getType());
		Assert.assertEquals(-1 ^ 0x0F, xor.intValue().getAsInt());

		KernelIndex index = kernel();
		Expression<?> shift = index.shiftRight(4);
		Assert.assertEquals(Integer.class, shift.getType());
		Assert.assertEquals(0x12345678 >> 4, shift.value(new IndexValues().put(index, 0x12345678)).intValue());
	}

	/**
	 * Unsigned wraparound and the PCG hash reject floating-point input rather than silently
	 * truncating it; the caller converts explicitly.
	 */
	@Test(timeout = 10000)
	public void unsignedRejectsFloatingPoint() {
		try {
			new DoubleConstant(1.5).toUnsigned(Precision.FP32);
			Assert.fail("toUnsigned accepted a floating-point operand");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue(expected.getMessage().contains("Double"));
		}

		try {
			new DoubleConstant(1.5).pcgHash();
			Assert.fail("pcgHash accepted a floating-point operand");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue(expected.getMessage().contains("Double"));
		}

		Assert.assertEquals(pcg(1), new DoubleConstant(1.5).toLong().pcgHash().longValue().getAsLong());
	}

	/**
	 * An unsigned 64-bit value cannot be held in a {@code long}, so wraparound at
	 * {@link Precision#FP64} is refused rather than producing a value that reads as negative.
	 */
	@Test(timeout = 10000)
	public void unsignedRejectsFP64() {
		try {
			e(-1).toUnsigned(Precision.FP64);
			Assert.fail("toUnsigned accepted FP64");
		} catch (UnsupportedOperationException expected) {
			Assert.assertTrue(expected.getMessage().contains("FP64"));
		}
	}

	/** The PCG hash of a constant folds on the host to the reference value. */
	@Test(timeout = 30000)
	public void pcgHashConstant() {
		long[] inputs = { 0, 1, 2, 42, 0x7FFFFFFFL, 0x80000000L, MASK, -1, 123456789012L };

		for (long input : inputs) {
			long expected = pcg(input);
			Expression<?> hash = new LongConstant(input).pcgHash();
			Assert.assertTrue(hash.getClass().getSimpleName(), hash instanceof LongConstant);
			Assert.assertEquals("pcg(" + input + ")", expected, hash.longValue().getAsLong());
		}
	}

	/**
	 * The PCG hash of the kernel index agrees with the reference implementation under
	 * point evaluation and under block (sequence) evaluation.
	 */
	@Test(timeout = 60000)
	public void pcgHashOverIndex() {
		KernelIndex index = kernel();
		int len = 4096;
		Expression<?> hash = index.xor(new LongConstant(0x9E3779B9L)).pcgHash();
		Assert.assertEquals(Long.class, hash.getType());
		Assert.assertFalse(hash.isPossiblyNegative());

		IndexSequence seq = hash.sequence(index, len);

		for (int i = 0; i < len; i++) {
			long expected = pcg(i ^ 0x9E3779B9L);
			Assert.assertEquals(expected, hash.value(new IndexValues().put(index, i)).longValue());
			Assert.assertEquals(expected, seq.valueAt(i).longValue());
		}
	}

	/** The rendered hash contains only integer arithmetic and the 32-bit mask. */
	@Test(timeout = 10000)
	public void pcgHashRendersIntegerArithmetic() {
		String code = kernel().pcgHash().getExpression(lang);
		Assert.assertTrue(code, code.contains("4294967295"));
		Assert.assertTrue(code, code.contains("747796405"));
		Assert.assertTrue(code, code.contains("277803737"));
		Assert.assertTrue(code, code.contains(" ^ "));
		Assert.assertTrue(code, code.contains(" >> "));
		Assert.assertFalse(code, code.contains("."));
		Assert.assertEquals(Long.class, new IntegerConstant(3).pcgHash().getType());
	}
}
