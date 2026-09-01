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

package org.almostrealism.color.test;

import org.almostrealism.color.RGB;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Round-trip tests for {@link RGB#encode()} and {@link RGB#decode(char[])}.
 *
 * <p>{@code decode} is documented as the inverse of {@code encode}: encode
 * writes each channel's IEEE&nbsp;754 bits as sixteen 4-bit nibble characters,
 * and decode reassembles those nibbles back into the original {@code long} bit
 * pattern. A color pushed through both must come back unchanged.</p>
 */
public class RGBEncodeDecodeTest extends TestSuiteBase {

	/** A color encoded and then decoded must reproduce its original channel values. */
	@Test(timeout = 5000)
	public void encodeDecodeRoundTrip() {
		RGB original = new RGB(0.25, 0.5, 0.75);

		char[] encoded = original.encode();
		Assert.assertEquals("encode must emit 48 characters", 48, encoded.length);

		RGB decoded = RGB.decode(encoded);

		Assert.assertEquals("red must survive encode/decode",
				original.getRed(), decoded.getRed(), 1e-9);
		Assert.assertEquals("green must survive encode/decode",
				original.getGreen(), decoded.getGreen(), 1e-9);
		Assert.assertEquals("blue must survive encode/decode",
				original.getBlue(), decoded.getBlue(), 1e-9);
		Assert.assertTrue("decoded color must equal the original", original.equals(decoded));
	}

	/** Decoding from a non-zero offset must recover the color written at that offset. */
	@Test(timeout = 5000)
	public void encodeDecodeRoundTripAtOffset() {
		RGB original = new RGB(0.1, 0.2, 0.9);

		char[] encoded = original.encode();
		char[] padded = new char[encoded.length + 5];
		System.arraycopy(encoded, 0, padded, 5, encoded.length);

		RGB decoded = RGB.decode(padded, 5);

		Assert.assertTrue("decoded color must equal the original", original.equals(decoded));
	}

	/** Several representative colors must all survive the round trip. */
	@Test(timeout = 5000)
	public void encodeDecodeRoundTripMultipleColors() {
		double[][] colors = {
				{0.0, 0.0, 0.0},
				{1.0, 1.0, 1.0},
				{0.333, 0.444, 0.555},
				{0.03125, 0.0, 0.984375}
		};

		for (double[] c : colors) {
			RGB original = new RGB(c[0], c[1], c[2]);
			RGB decoded = RGB.decode(original.encode());
			Assert.assertEquals("red must survive for " + original,
					original.getRed(), decoded.getRed(), 1e-9);
			Assert.assertEquals("green must survive for " + original,
					original.getGreen(), decoded.getGreen(), 1e-9);
			Assert.assertEquals("blue must survive for " + original,
					original.getBlue(), decoded.getBlue(), 1e-9);
		}
	}
}
