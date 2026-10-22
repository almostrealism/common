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

package org.almostrealism.studio.optimize.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.studio.optimize.OptimizeFactorFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link OptimizeFactorFeatures}. These exercise the parameter
 * encoders (the {@code factorFor*} methods) and the time-varying producer builders
 * ({@link OptimizeFactorFeatures#durationAdjustment}, {@code adjustment},
 * {@code polycyclic}, and {@code riseFall}).
 *
 * <p>Each encoder maps a real quantity (a duration in seconds, an exponent, a repeat
 * rate) to a value in the unit range. The tests verify this encoding by decoding the
 * result with an independently written inverse and asserting the original value is
 * recovered — this pins both the inverse relationship and the specific scaling
 * constants chosen for each parameter, rather than restating the implementation.</p>
 *
 * <p>The producer builders return computation graphs; the tests evaluate them at
 * chosen sample positions (a sanctioned use of {@code evaluate()} at the top of the
 * call stack) and assert on the numeric output.</p>
 */
public class OptimizeFactorFeaturesTest extends TestSuiteBase implements OptimizeFactorFeatures {
	/** Tolerance for floating-point comparisons. */
	private static final double EPS = 1.0e-6;

	/**
	 * The inverse of the {@code invertOneToInfinity} encoding, written independently of
	 * the production code: given an encoded unit-range factor it recovers the original
	 * quantity. For the factor {@code f} produced from a value {@code v} with the given
	 * {@code multiplier} and {@code exp}, this returns {@code v}.
	 *
	 * @param f          the encoded factor in the unit range
	 * @param multiplier the scaling constant the encoder used
	 * @param exp        the exponent the encoder used
	 * @return the decoded original quantity
	 */
	private double decode(double f, double multiplier, double exp) {
		return multiplier * (1.0 / (1.0 - Math.pow(f, exp)) - 1.0);
	}

	/**
	 * Asserts that {@code factor} is a valid unit-range encoding of {@code value} using
	 * the given {@code multiplier} and {@code exp}.
	 *
	 * @param value      the original quantity that was encoded
	 * @param multiplier the scaling constant expected of the encoder
	 * @param exp        the exponent expected of the encoder
	 * @param factor     the encoded factor returned by the encoder under test
	 */
	private void assertRoundTrip(double value, double multiplier, double exp, double factor) {
		Assert.assertTrue("encoded factor must lie in [0, 1): " + factor,
				factor >= 0.0 && factor < 1.0);
		Assert.assertEquals(value, decode(factor, multiplier, exp), Math.abs(value) * EPS + 1.0e-9);
	}

	/**
	 * Evaluates a scalar-shaped collection producer to a single {@code double}.
	 *
	 * @param producer the producer to evaluate
	 * @return the first element of the evaluated result
	 */
	private double eval(CollectionProducer producer) {
		return ((PackedCollection) producer.evaluate()).toDouble(0);
	}

	/** {@code factorForRepeat} must encode beats as a base-2 logarithm scaled into the unit range. */
	@Test(timeout = 60000)
	public void repeatFactorEncodesLogBeats() {
		Assert.assertEquals(0.5, factorForRepeat(1.0), EPS);
		Assert.assertEquals(2.0 / 16 + 0.5, factorForRepeat(4.0), EPS);

		double f = factorForRepeat(8.0);
		Assert.assertEquals(8.0, Math.pow(2.0, 16 * (f - 0.5)), 1.0e-6);
	}

	/** Delay seconds must encode with multiplier 60 and exponent 3. */
	@Test(timeout = 60000)
	public void delayFactorInvertsToSeconds() {
		assertRoundTrip(5.0, 60, 3, factorForDelay(5.0));
		assertRoundTrip(0.25, 60, 3, factorForDelay(0.25));
	}

	/** The repeat speed-up duration must encode with multiplier 60 and exponent 3. */
	@Test(timeout = 60000)
	public void repeatSpeedUpDurationFactorInvertsToSeconds() {
		assertRoundTrip(12.0, 60, 3, factorForRepeatSpeedUpDuration(12.0));
	}

	/** An exponent must encode with multiplier 10 and exponent 1. */
	@Test(timeout = 60000)
	public void exponentFactorInvertsToExponent() {
		assertRoundTrip(2.5, 10, 1, factorForExponent(2.5));
	}

	/** The periodic and polynomial adjustment durations must encode with multiplier 60, exponent 3. */
	@Test(timeout = 60000)
	public void adjustmentDurationFactorsInvertToSeconds() {
		assertRoundTrip(8.0, 60, 3, factorForPeriodicAdjustmentDuration(8.0));
		assertRoundTrip(3.0, 60, 3, factorForPolyAdjustmentDuration(3.0));
	}

	/** The polynomial adjustment exponent and initial value must encode with multiplier 10, exponent 1. */
	@Test(timeout = 60000)
	public void adjustmentExponentAndInitialFactorsInvert() {
		assertRoundTrip(4.0, 10, 1, factorForPolyAdjustmentExponent(4.0));
		assertRoundTrip(1.5, 10, 1, factorForAdjustmentInitial(1.5));
	}

	/** The adjustment offset must encode with multiplier 60 and exponent 3. */
	@Test(timeout = 60000)
	public void adjustmentOffsetFactorInvertsToSeconds() {
		assertRoundTrip(30.0, 60, 3, factorForAdjustmentOffset(30.0));
	}

	/** The speed-up and slow-down ramp durations must encode with multiplier 60, exponent 3. */
	@Test(timeout = 60000)
	public void speedAndSlowDurationFactorsInvertToSeconds() {
		assertRoundTrip(6.0, 60, 3, factorForSpeedUpDuration(6.0));
		assertRoundTrip(9.0, 60, 3, factorForSlowDownDuration(9.0));
	}

	/** The speed-up percentage must encode with multiplier 10 and exponent 0.5. */
	@Test(timeout = 60000)
	public void speedUpPercentageFactorInvertsToDecimal() {
		assertRoundTrip(0.2, 10, 0.5, factorForSpeedUpPercentage(0.2));
	}

	/** The polynomial speed-up duration and exponent must encode with the documented constants. */
	@Test(timeout = 60000)
	public void polySpeedUpFactorsInvert() {
		assertRoundTrip(7.0, 60, 3, factorForPolySpeedUpDuration(7.0));
		assertRoundTrip(3.0, 10, 1, factorForPolySpeedUpExponent(3.0));
	}

	/** The slow-down percentage is passed through unchanged (an identity encoding). */
	@Test(timeout = 60000)
	public void slowDownPercentageIsIdentity() {
		Assert.assertEquals(0.37, factorForSlowDownPercentage(0.37), 0.0);
		Assert.assertEquals(0.0, factorForSlowDownPercentage(0.0), 0.0);
	}

	/**
	 * {@code durationAdjustment} starts at the initial speed factor and halves it at each
	 * elapsed interval past the offset. Here {@code factorForRepeat(1.0)} encodes an
	 * initial factor of one, the interval is four samples and the offset is ten samples;
	 * the factor stays at one until the offset, then halves at each four-sample interval.
	 */
	@Test(timeout = 120000)
	public void durationAdjustmentHalvesEachInterval() {
		Producer<PackedCollection> rp = c(factorForRepeat(1.0));
		Producer<PackedCollection> interval = c(4.0);
		Producer<PackedCollection> offset = c(10.0);

		Assert.assertEquals(1.0, eval(durationAdjustment(rp, interval, offset, c(0.0))), EPS);
		Assert.assertEquals(1.0, eval(durationAdjustment(rp, interval, offset, c(10.0))), EPS);
		Assert.assertEquals(0.5, eval(durationAdjustment(rp, interval, offset, c(14.0))), EPS);
		Assert.assertEquals(0.25, eval(durationAdjustment(rp, interval, offset, c(18.0))), EPS);
	}

	/**
	 * {@code adjustment} holds the initial value before the offset, then follows the
	 * polynomial curve, clamped to the supplied range. With unit polynomial parameters
	 * the value one sample past the offset is {@code initial + 1}; ten samples past the
	 * offset the raw value of eleven is clamped to the configured maximum of ten.
	 */
	@Test(timeout = 120000)
	public void adjustmentHoldsThenRisesAndClamps() {
		Producer<PackedCollection> one = c(1.0);

		Assert.assertEquals(1.0, eval(adjustment(one, one, one, one, one, c(10.0),
				c(0.0), 0.0, 10.0, false)), EPS);
		Assert.assertEquals(2.0, eval(adjustment(one, one, one, one, one, c(10.0),
				c(11.0), 0.0, 10.0, false)), EPS);
		Assert.assertEquals(10.0, eval(adjustment(one, one, one, one, one, c(10.0),
				c(20.0), 0.0, 10.0, false)), EPS);
	}

	/**
	 * When {@code relative} is set, the scale is multiplied by the initial value. With an
	 * initial of two and a scale of one, the effective scale is two, so one sample past
	 * the offset the value is {@code 1 * 2 + 2 == 4}.
	 */
	@Test(timeout = 120000)
	public void adjustmentRelativeScalesByInitial() {
		Producer<PackedCollection> one = c(1.0);

		Assert.assertEquals(4.0, eval(adjustment(one, one, one, c(2.0), one, c(10.0),
				c(11.0), 0.0, 100.0, true)), EPS);
	}

	/** {@code polycyclic} applies no modulation at frame zero, whatever the parameters. */
	@Test(timeout = 120000)
	public void polycyclicIsUnityAtFrameZero() {
		Producer<PackedCollection> p = c(3.0);
		Assert.assertEquals(1.0,
				eval(polycyclic(p, c(0.5), p, c(0.5), p, c(2.0), c(0.0), 44100)), EPS);
	}

	/**
	 * At the slow-down sinusoid's peak the slow-down amplitude attenuates the factor.
	 * With a sample rate of four, a slow-down wavelength of one and frame one, the
	 * slow-down angle is {@code pi/2}, so its sine is one. A zero speed-up amplitude
	 * removes the speed-up term and a very large polynomial wavelength makes the
	 * polynomial term negligible, leaving {@code 1 - (1 * 0.5)^2 == 0.75}.
	 */
	@Test(timeout = 120000)
	public void polycyclicSlowDownAttenuatesAtPeak() {
		double result = eval(polycyclic(c(1.0), c(0.0), c(1.0), c(0.5), c(1.0e6), c(1.0),
				c(1.0), 4));
		Assert.assertEquals(0.75, result, 1.0e-3);
	}

	/**
	 * {@code riseFall} travels the full configured range over its duration and, with a
	 * linear exponent, reaches the midpoint halfway through.
	 */
	@Test(timeout = 120000)
	public void riseFallTraversesFullRangeLinearly() {
		Producer<PackedCollection> d = c(0.75);
		Producer<PackedCollection> m = c(1.0);
		Producer<PackedCollection> p = c(0.0);
		Producer<PackedCollection> e = c(1.0);
		Producer<PackedCollection> duration = c(10.0);

		double start = eval(riseFall(0, 1, 0.0, d, m, p, e, c(0.0), duration));
		double end = eval(riseFall(0, 1, 0.0, d, m, p, e, c(10.0), duration));
		double mid = eval(riseFall(0, 1, 0.0, d, m, p, e, c(5.0), duration));

		Assert.assertEquals("full range must be traversed", 1.0, Math.abs(end - start), EPS);
		Assert.assertEquals("linear exponent must reach the midpoint halfway",
				(start + end) / 2.0, mid, EPS);
	}

	/**
	 * A higher exponent delays the rise, so the halfway sample sits a quarter of the way
	 * from start to end ({@code pos = 0.5^2 = 0.25}) rather than at the linear midpoint.
	 */
	@Test(timeout = 120000)
	public void riseFallExponentShapesTheCurve() {
		Producer<PackedCollection> d = c(0.75);
		Producer<PackedCollection> m = c(1.0);
		Producer<PackedCollection> p = c(0.0);
		Producer<PackedCollection> duration = c(10.0);

		double start = eval(riseFall(0, 1, 0.0, d, m, p, c(2.0), c(0.0), duration));
		double end = eval(riseFall(0, 1, 0.0, d, m, p, c(2.0), c(10.0), duration));
		double mid = eval(riseFall(0, 1, 0.0, d, m, p, c(2.0), c(5.0), duration));

		Assert.assertEquals(start + (end - start) * 0.25, mid, EPS);
	}

	/**
	 * The minimum-scale floor bounds how far the value travels regardless of the magnitude
	 * input: a magnitude of zero with a 0.3 floor still traverses 0.3 of the unit range.
	 */
	@Test(timeout = 120000)
	public void riseFallMinScaleBoundsMagnitude() {
		Producer<PackedCollection> d = c(0.75);
		Producer<PackedCollection> p = c(0.0);
		Producer<PackedCollection> e = c(1.0);
		Producer<PackedCollection> duration = c(10.0);

		double start = eval(riseFall(0, 1, 0.3, d, c(0.0), p, e, c(0.0), duration));
		double end = eval(riseFall(0, 1, 0.3, d, c(0.0), p, e, c(10.0), duration));

		Assert.assertEquals(0.3, Math.abs(end - start), EPS);
	}
}
