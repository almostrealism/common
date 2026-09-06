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
}
