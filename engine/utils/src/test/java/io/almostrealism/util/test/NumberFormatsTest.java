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

package io.almostrealism.util.test;

import io.almostrealism.uml.Signature;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Tests that {@link NumberFormats#formatNumber(Number)} produces exactly what
 * {@link NumberFormats#displayFormat} produces for values in the display range,
 * including the values it formats without {@link java.text.DecimalFormat}, and that
 * {@link Signature#hex(byte[])} produces zero-padded lowercase hexadecimal.
 */
public class NumberFormatsTest extends TestSuiteBase {

	/**
	 * Every whole number of hundredths in the display range, and values with more
	 * decimal places around them, must format the same as the display format does.
	 */
	@Test(timeout = 60_000)
	public void hundredthsMatchDisplayFormat() {
		for (long k = 0; k <= 50_000_000L; k += (k < 100_000 ? 1 : 9973)) {
			assertMatchesDisplayFormat(k / 100.0);
			assertMatchesDisplayFormat(k / 1000.0);
		}

		double[] values = { 0.0005, 0.005, 0.07, 0.29, 0.5, 1.0, 1.15, 2.0, 2.675, 499999.99, 500000.0 };
		for (double value : values) {
			assertMatchesDisplayFormat(value);
		}
	}

	/**
	 * Arbitrary values in the display range must format the same as the display format does.
	 */
	@Test(timeout = 60_000)
	public void randomValuesMatchDisplayFormat() {
		Random random = new Random(7);

		for (int i = 0; i < 200_000; i++) {
			assertMatchesDisplayFormat(random.nextDouble() * (i % 2 == 0 ? 10.0 : 500000.0));
		}
	}

	/**
	 * Hexadecimal output is lowercase with two digits per byte.
	 */
	@Test(timeout = 10_000)
	public void hexIsZeroPaddedLowercase() {
		Assert.assertEquals("abcd010f00ff",
				Signature.hex(new byte[] { (byte) 0xAB, (byte) 0xCD, 0x01, 0x0F, 0x00, (byte) 0xFF }));
		Assert.assertEquals(32, Signature.md5("signature").length());
	}

	/**
	 * Asserts that {@code value} formats the same through {@link NumberFormats#formatNumber(Number)}
	 * as through {@link NumberFormats#displayFormat}, when it is in the display range.
	 */
	private static void assertMatchesDisplayFormat(double value) {
		if (value < 0.0005 || value > 500000) return;

		Assert.assertEquals("Formatting " + value,
				NumberFormats.displayFormat.format(value), NumberFormats.formatNumber(value));
	}
}
