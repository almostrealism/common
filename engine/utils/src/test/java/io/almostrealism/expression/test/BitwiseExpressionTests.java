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
import io.almostrealism.expression.And;
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
	 * Wrapping to 32 bits is explicit: a negative value maps to its two's complement
	 * residue and a value above 2^32 to its low 32 bits, with conservative bounds.
	 */
	@Test(timeout = 10000)
	public void unsigned32() {
		Assert.assertEquals(MASK, e(-1).toUnsigned32().longValue().getAsLong());
		Assert.assertEquals(5L, new LongConstant((1L << 32) + 5).toUnsigned32().longValue().getAsLong());

		Expression<?> wrapped = kernel().toUnsigned32();
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
