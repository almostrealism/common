/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.io;

import org.junit.Assert;
import org.junit.Test;

/**
 * Locks the bit-field packing contract of {@link Bits#put(int, int, int)}.
 *
 * <p>{@code put} documents that the value is "masked to fit within the
 * specified number of bits", which for a bit field means keeping the low
 * {@code bits} bits of the two's-complement representation. The realistic
 * caller — {@code Expression.hash()} — packs a {@code short} hash whose value
 * is routinely negative, so the negative-input behavior is exercised in
 * practice and must honor the masking contract.</p>
 */
public class BitsTest {

	/** The documented positive-value examples pack unchanged. */
	@Test(timeout = 10000)
	public void positiveValuesPackAsDocumented() {
		Assert.assertEquals(10, Bits.put(0, 4, 10));
		Assert.assertEquals(255 << 4, Bits.put(4, 8, 255));
	}

	/**
	 * A negative value is masked to the low {@code bits} bits of its
	 * two's-complement representation, not sign-flipped. {@code -1} at 16 bits
	 * is {@code 0xFFFF == 65535}.
	 */
	@Test(timeout = 10000)
	public void negativeValueIsMaskedToLowBits() {
		Assert.assertEquals(65535, Bits.put(0, 16, -1));
	}

	/**
	 * Two inputs that share the same low {@code bits} bits must pack to the same
	 * result. {@code -1} and {@code 65535} have identical low 16 bits, so a
	 * correct 16-bit field pack cannot distinguish them.
	 */
	@Test(timeout = 10000)
	public void inputsWithEqualLowBitsPackEqually() {
		Assert.assertEquals(Bits.put(0, 16, 65535), Bits.put(0, 16, -1));
	}

	/**
	 * A full 32-bit field must keep every bit of the value. Java masks shift
	 * counts to 5 bits for {@code int}, so a naive {@code (1 << 32) - 1} mask
	 * wraps to {@code 0} and would silently zero out any value packed here.
	 */
	@Test(timeout = 10000)
	public void fullWidthFieldPreservesAllBits() {
		Assert.assertEquals(-1, Bits.put(0, 32, -1));
	}

	/**
	 * Values exactly representable in half precision round-trip through both conversions.
	 * The comparison is on raw {@code float} bits so that {@code -0.0} is distinguished from
	 * {@code 0.0}.
	 */
	@Test(timeout = 10000)
	public void halfPrecisionRoundTripsExactValues() {
		float[] exact = new float[] { 0.0f, -0.0f, 0.5f, -1.25f, 1.0f, 2.0f, 65504.0f, -65504.0f };
		for (float value : exact) {
			float decoded = Bits.float16ToFloat(Bits.floatToFloat16(value));
			Assert.assertTrue(Float.floatToIntBits(value) == Float.floatToIntBits(decoded));
		}
	}

	/** The smallest positive half subnormal decodes to {@code 2^-24} exactly. */
	@Test(timeout = 10000)
	public void halfPrecisionDecodesSmallestSubnormal() {
		float smallest = Math.scalb(1.0f, -24);
		float decoded = Bits.float16ToFloat((short) 0x0001);
		Assert.assertTrue(Float.floatToIntBits(smallest) == Float.floatToIntBits(decoded));
		Assert.assertEquals(0x0001, Bits.floatToFloat16(smallest) & 0xFFFF);
	}

	/** Infinities, zero and NaN are carried across the conversion. */
	@Test(timeout = 10000)
	public void halfPrecisionCarriesSpecialValues() {
		Assert.assertEquals(0x7C00, Bits.floatToFloat16(Float.POSITIVE_INFINITY) & 0xFFFF);
		Assert.assertEquals(0xFC00, Bits.floatToFloat16(Float.NEGATIVE_INFINITY) & 0xFFFF);
		Assert.assertEquals(0x8000, Bits.floatToFloat16(-0.0f) & 0xFFFF);
		Assert.assertTrue(Float.POSITIVE_INFINITY == Bits.float16ToFloat((short) 0x7C00));
		Assert.assertTrue(Float.isNaN(Bits.float16ToFloat((short) 0x7E00)));
		Assert.assertTrue(Float.isNaN(Bits.float16ToFloat(Bits.floatToFloat16(Float.NaN))));
	}

	/** A value beyond the half-precision range saturates to a signed infinity. */
	@Test(timeout = 10000)
	public void halfPrecisionOverflowsToInfinity() {
		float beyond = Math.scalb(1.0f, 20);
		Assert.assertEquals(0x7C00, Bits.floatToFloat16(beyond) & 0xFFFF);
		Assert.assertEquals(0xFC00, Bits.floatToFloat16(-beyond) & 0xFFFF);
	}
}
