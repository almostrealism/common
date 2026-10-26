/*
 * Copyright 2024 Michael Murray
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

/**
 * Utility class for bit manipulation operations.
 *
 * <p>Provides methods for packing values into specific bit positions within integers.
 * Useful for creating compact binary representations or bit fields.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Pack a 4-bit value (0-15) at position 0
 * int result = Bits.put(0, 4, 10);  // result = 10 (binary: 1010)
 *
 * // Pack an 8-bit value at position 4
 * result |= Bits.put(4, 8, 255);  // Shifts 255 left by 4 bits
 * }</pre>
 */
public class Bits {
	/**
	 * Places a value at a specific bit position within an integer.
	 *
	 * <p>The value is masked to the low {@code bits} bits of its two's-complement
	 * representation, then shifted to the specified position. Masking (rather
	 * than a sign-flip via {@code Math.abs}) is what keeps a negative value
	 * confined to its field without collapsing distinct values: {@code -1} and
	 * {@code 0xFFFF} share the same low 16 bits and therefore pack identically,
	 * as any bit-field extraction requires.</p>
	 *
	 * @param position the bit position to place the value (0 = least significant)
	 * @param bits the number of bits allocated for the value
	 * @param value the value to pack (will be masked to fit in 'bits' bits)
	 * @return the packed value at the specified position
	 */
	public static int put(int position, int bits, int value) {
		// Java masks shift counts to 5 bits for int, so (1 << 32) evaluates to
		// (1 << 0) == 1 rather than overflowing to 0. bits == 32 is special-cased
		// to an all-ones mask so a full-width field keeps every bit of value.
		int mask = bits == 32 ? -1 : (1 << bits) - 1;
		return (value & mask) << position;
	}

	/**
	 * Converts an IEEE 754 half-precision value to single precision.
	 *
	 * <p>Every half-precision value is exactly representable as a {@code float}, so this
	 * conversion is lossless. Signed zeros, subnormals, infinities and NaNs are all carried
	 * across: a subnormal half is renormalized by shifting its mantissa up until the leading
	 * bit becomes implicit, decrementing the exponent once per shift, which yields a normal
	 * {@code float}; a half exponent of {@code 0x1F} maps to the {@code float} infinity/NaN
	 * exponent. The constant {@code 127 - 15} rebiases from the half exponent bias to the
	 * single-precision bias, and the 13-bit shift widens the 10-bit mantissa to 23 bits.</p>
	 *
	 * <p>This exists because {@code Float.float16ToFloat} is only available from Java 20, while
	 * this project targets Java 17.</p>
	 *
	 * @param half the half-precision value, held in the low 16 bits
	 * @return the equivalent single-precision value
	 */
	public static float float16ToFloat(short half) {
		int bits = half & 0xFFFF;
		int sign = bits >>> 15;
		int exp = (bits >>> 10) & 0x1F;
		int mant = bits & 0x3FF;

		int result;
		if (exp == 0) {
			if (mant == 0) {
				result = sign << 31;
			} else {
				exp = 127 - 15 + 1;
				while ((mant & 0x400) == 0) {
					mant <<= 1;
					exp--;
				}
				mant &= 0x3FF;
				result = (sign << 31) | (exp << 23) | (mant << 13);
			}
		} else if (exp == 0x1F) {
			result = (sign << 31) | 0x7F800000 | (mant << 13);
		} else {
			result = (sign << 31) | ((exp + (127 - 15)) << 23) | (mant << 13);
		}
		return Float.intBitsToFloat(result);
	}

	/**
	 * Converts a single-precision value to IEEE 754 half precision, rounding to nearest with
	 * ties to even.
	 *
	 * <p>A value too large for half precision becomes a signed infinity, and a value too small
	 * becomes a signed zero. Infinities and NaNs are preserved, a non-zero mantissa being kept
	 * non-zero so a NaN stays a NaN. When the half exponent falls to zero or below the result is
	 * subnormal, so the implicit leading mantissa bit is restored before the mantissa is shifted
	 * down and rounded. On the normal path a mantissa carry out of rounding flows into the
	 * exponent correctly, and an exponent that reaches {@code 0x1F} becomes infinity, which is
	 * the correct overflow result.</p>
	 *
	 * <p>This exists because {@code Float.floatToFloat16} is only available from Java 20, while
	 * this project targets Java 17.</p>
	 *
	 * @param value the single-precision value
	 * @return the nearest half-precision value, held in the low 16 bits
	 */
	public static short floatToFloat16(float value) {
		int bits = Float.floatToIntBits(value);
		int sign = (bits >>> 16) & 0x8000;
		int exp = (bits >>> 23) & 0xFF;
		int mant = bits & 0x7FFFFF;

		if (exp == 0xFF) {
			return (short) (sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
		}

		int halfExp = exp - 127 + 15;
		if (halfExp >= 0x1F) {
			return (short) (sign | 0x7C00);
		}
		if (halfExp <= 0) {
			if (halfExp < -10) {
				return (short) sign;
			}
			mant |= 0x800000;
			int shift = 14 - halfExp;
			int half = mant >>> shift;
			int remainder = mant & ((1 << shift) - 1);
			int tie = 1 << (shift - 1);
			if (remainder > tie || (remainder == tie && (half & 1) != 0)) {
				half++;
			}
			return (short) (sign | half);
		}

		int half = (halfExp << 10) | (mant >>> 13);
		int remainder = mant & 0x1FFF;
		if (remainder > 0x1000 || (remainder == 0x1000 && (half & 1) != 0)) {
			half++;
		}
		return (short) (sign | half);
	}
}
