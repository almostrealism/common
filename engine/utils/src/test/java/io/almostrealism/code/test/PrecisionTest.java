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

package io.almostrealism.code.test;

import io.almostrealism.code.Precision;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the numeric literals {@link Precision} emits into generated source.
 */
public class PrecisionTest extends TestSuiteBase {

	/**
	 * Parses a literal the way a target compiler would, tolerating the single
	 * precision suffix.
	 */
	private double parse(String literal) {
		return Double.parseDouble(literal.endsWith("f")
				? literal.substring(0, literal.length() - 1) : literal);
	}

	/**
	 * Literals below {@link Precision#FP64} must carry the {@code f} suffix. Without it the
	 * literal has no type of its own, a target compiler reads it as a double, and a call
	 * that also takes a single precision operand becomes ambiguous.
	 */
	@Test(timeout = 30000)
	public void singlePrecisionLiteralsAreTyped() {
		Assert.assertEquals("0.5f", Precision.FP32.stringForDouble(0.5));
		Assert.assertEquals("0.5f", Precision.FP16.stringForDouble(0.5));
		Assert.assertEquals("0.5", Precision.FP64.stringForDouble(0.5));
	}

	/**
	 * Every literal must still parse as the value it stands for once the suffix is taken
	 * into account, including the values {@link Precision#epsilon(boolean)} and
	 * {@link Precision#minValue()} produce.
	 */
	@Test(timeout = 30000)
	public void literalsParseBackToTheirValue() {
		for (Precision p : Precision.values()) {
			Assert.assertEquals(p.name(), 0.5, parse(p.stringForDouble(0.5)), 0.0);
			Assert.assertEquals(p.name(), p.epsilon(true),
					parse(p.stringForDouble(p.epsilon(true))), p.epsilon(true) / 1e3);
			Assert.assertTrue(p.name(), parse(p.stringForDouble(p.minValue())) < 0);
		}
	}

	/**
	 * An infinite value is replaced with the furthest finite value of the SAME SIGN. The
	 * negative case is the one worth pinning down: {@link Float#MIN_VALUE} and
	 * {@link Double#MIN_VALUE} are the smallest positive values, so using them here turned
	 * negative infinity into a tiny positive number.
	 */
	@Test(timeout = 30000)
	public void infiniteValuesSaturateWithTheirSign() {
		for (Precision p : Precision.values()) {
			double positive = parse(p.stringForDouble(Double.POSITIVE_INFINITY));
			double negative = parse(p.stringForDouble(Double.NEGATIVE_INFINITY));

			Assert.assertTrue(p.name(), positive > 0);
			Assert.assertTrue(p.name(), negative < 0);
			Assert.assertEquals(p.name(), positive, -negative, 0.0);
			Assert.assertFalse(p.name(), Double.isInfinite(positive));
			Assert.assertFalse(p.name(), Double.isInfinite(negative));
		}
	}

	/**
	 * A finite value too large for the target precision saturates the same way, rather than
	 * reaching generated source as an infinity the target cannot express.
	 */
	@Test(timeout = 30000)
	public void valuesBeyondTheTargetRangeSaturateWithTheirSign() {
		Assert.assertTrue(parse(Precision.FP32.stringForDouble(-1.0e300)) < 0);
		Assert.assertTrue(parse(Precision.FP32.stringForDouble(1.0e300)) > 0);
		Assert.assertEquals(parse(Precision.FP32.stringForDouble(1.0e300)),
				-parse(Precision.FP32.stringForDouble(-1.0e300)), 0.0);
	}

	/**
	 * NaN has no literal form in the target languages, so it is emitted as zero.
	 */
	@Test(timeout = 30000)
	public void notANumberBecomesZero() {
		for (Precision p : Precision.values()) {
			Assert.assertEquals(p.name(), 0.0, parse(p.stringForDouble(Double.NaN)), 0.0);
		}
	}
}
