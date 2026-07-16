/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * Data interface for biquad filter state.
 * Stores filter coefficients (b0, b1, b2, a1, a2) and delay line state (x1, x2, y1, y2).
 * <p>
 * Memory layout (each slot is 1 element):
 * <ul>
 *   <li>Slot 0: b0 - feedforward coefficient for x[n]</li>
 *   <li>Slot 1: b1 - feedforward coefficient for x[n-1]</li>
 *   <li>Slot 2: b2 - feedforward coefficient for x[n-2]</li>
 *   <li>Slot 3: a1 - feedback coefficient for y[n-1]</li>
 *   <li>Slot 4: a2 - feedback coefficient for y[n-2]</li>
 *   <li>Slot 5: x1 - previous input sample x[n-1]</li>
 *   <li>Slot 6: x2 - input sample x[n-2]</li>
 *   <li>Slot 7: y1 - previous output sample y[n-1]</li>
 *   <li>Slot 8: y2 - output sample y[n-2]</li>
 * </ul>
 *
 * @see BiquadFilterCell
 */
public interface BiquadFilterData extends CodeFeatures {
	/** Total number of memory slots in the biquad filter state. */
	int SIZE = 9;

	/**
	 * Returns the memory slot at the given index for direct access.
	 *
	 * @param index slot index (0–{@value #SIZE}-1)
	 * @return the PackedCollection at that slot
	 */
	PackedCollection get(int index);

	default PackedCollection b0() { return get(0); }
	default PackedCollection b1() { return get(1); }
	default PackedCollection b2() { return get(2); }
	default PackedCollection a1() { return get(3); }
	default PackedCollection a2() { return get(4); }
	default PackedCollection x1() { return get(5); }
	default PackedCollection x2() { return get(6); }
	default PackedCollection y1() { return get(7); }
	default PackedCollection y2() { return get(8); }

	default Producer<PackedCollection> getB0() { return p(b0().range(shape(1))); }
	default Producer<PackedCollection> getB1() { return p(b1().range(shape(1))); }
	default Producer<PackedCollection> getB2() { return p(b2().range(shape(1))); }
	default Producer<PackedCollection> getA1() { return p(a1().range(shape(1))); }
	default Producer<PackedCollection> getA2() { return p(a2().range(shape(1))); }
	default Producer<PackedCollection> getX1() { return p(x1().range(shape(1))); }
	default Producer<PackedCollection> getX2() { return p(x2().range(shape(1))); }
	default Producer<PackedCollection> getY1() { return p(y1().range(shape(1))); }
	default Producer<PackedCollection> getY2() { return p(y2().range(shape(1))); }

	default void setB0(double v) { b0().setMem(0, v); }
	default void setB1(double v) { b1().setMem(0, v); }
	default void setB2(double v) { b2().setMem(0, v); }
	default void setA1(double v) { a1().setMem(0, v); }
	default void setA2(double v) { a2().setMem(0, v); }
	default void setX1(double v) { x1().setMem(0, v); }
	default void setX2(double v) { x2().setMem(0, v); }
	default void setY1(double v) { y1().setMem(0, v); }
	default void setY2(double v) { y2().setMem(0, v); }

	/**
	 * Sets all filter coefficients at once.
	 */
	default void setCoefficients(double b0, double b1, double b2, double a1, double a2) {
		setB0(b0);
		setB1(b1);
		setB2(b2);
		setA1(a1);
		setA2(a2);
	}

	/**
	 * Resets the filter state (delay lines) to zero.
	 */
	default void resetState() {
		setX1(0);
		setX2(0);
		setY1(0);
		setY2(0);
	}

	/**
	 * Returns the contiguous {@code (5)} coefficient block {@code [b0, b1, b2, a1, a2]}.
	 *
	 * <p>This is the destination for {@link #updateCoefficients(CollectionProducer)}, which
	 * writes all five coefficients in a single device assignment.</p>
	 *
	 * @return the coefficient block, occupying slots 0 through 4
	 */
	PackedCollection coefficients();

	/**
	 * Returns an operation that writes the given {@code (5)} coefficient producer into the
	 * {@linkplain #coefficients() coefficient block} on the device.
	 *
	 * <p>The coefficients are produced by the {@code *Coefficients} methods from a cutoff (or
	 * center) frequency and Q supplied as producers, so the values are computed by the graph
	 * rather than on the host. The assignment compiles once and may be run whenever the inputs
	 * change — including every tick when the cutoff is modulated — without adding compiled
	 * operations, because the varying value flows as device data rather than as a baked-in
	 * kernel constant.</p>
	 *
	 * @param coefficients a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 * @return the assignment operation
	 */
	default Supplier<Runnable> updateCoefficients(CollectionProducer coefficients) {
		return a(cp(coefficients()), coefficients);
	}

	/**
	 * Computes low-pass coefficients from a cutoff frequency and Q, matching the RBJ Audio EQ
	 * Cookbook formulas that {@link BiquadFilterCell#calculateLowPassCoefficients} evaluates on
	 * the host.
	 *
	 * @param cutoff     cutoff frequency in Hz, as a {@code (1)} producer
	 * @param q          Q factor (resonance), as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer lowPassCoefficients(
			Producer<PackedCollection> cutoff, Producer<PackedCollection> q, int sampleRate) {
		CollectionProducer cosW0 = cos(omega(cutoff, sampleRate));
		CollectionProducer alpha = alpha(cutoff, q, sampleRate);
		CollectionProducer oneMinusCos = subtract(c(1.0), cosW0);
		return simpleCoefficients(oneMinusCos.divide(2.0), oneMinusCos, oneMinusCos.divide(2.0),
				cosW0, alpha);
	}

	/**
	 * Computes high-pass coefficients; see {@link #lowPassCoefficients}.
	 *
	 * @param cutoff     cutoff frequency in Hz, as a {@code (1)} producer
	 * @param q          Q factor, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer highPassCoefficients(
			Producer<PackedCollection> cutoff, Producer<PackedCollection> q, int sampleRate) {
		CollectionProducer cosW0 = cos(omega(cutoff, sampleRate));
		CollectionProducer alpha = alpha(cutoff, q, sampleRate);
		CollectionProducer onePlusCos = add(c(1.0), cosW0);
		return simpleCoefficients(onePlusCos.divide(2.0), onePlusCos.multiply(-1.0),
				onePlusCos.divide(2.0), cosW0, alpha);
	}

	/**
	 * Computes band-pass coefficients (constant skirt gain, peak gain Q); see
	 * {@link #lowPassCoefficients}.
	 *
	 * @param center     center frequency in Hz, as a {@code (1)} producer
	 * @param q          Q factor, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer bandPassCoefficients(
			Producer<PackedCollection> center, Producer<PackedCollection> q, int sampleRate) {
		CollectionProducer cosW0 = cos(omega(center, sampleRate));
		CollectionProducer alpha = alpha(center, q, sampleRate);
		return simpleCoefficients(alpha, c(0.0), alpha.multiply(-1.0), cosW0, alpha);
	}

	/**
	 * Computes notch (band-reject) coefficients; see {@link #lowPassCoefficients}.
	 *
	 * @param center     center frequency in Hz, as a {@code (1)} producer
	 * @param q          Q factor, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer notchCoefficients(
			Producer<PackedCollection> center, Producer<PackedCollection> q, int sampleRate) {
		CollectionProducer cosW0 = cos(omega(center, sampleRate));
		CollectionProducer alpha = alpha(center, q, sampleRate);
		return simpleCoefficients(c(1.0), cosW0.multiply(-2.0), c(1.0), cosW0, alpha);
	}

	/**
	 * Computes all-pass coefficients; see {@link #lowPassCoefficients}.
	 *
	 * @param center     center frequency in Hz, as a {@code (1)} producer
	 * @param q          Q factor, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer allPassCoefficients(
			Producer<PackedCollection> center, Producer<PackedCollection> q, int sampleRate) {
		CollectionProducer cosW0 = cos(omega(center, sampleRate));
		CollectionProducer alpha = alpha(center, q, sampleRate);
		return simpleCoefficients(subtract(c(1.0), alpha), cosW0.multiply(-2.0),
				add(c(1.0), alpha), cosW0, alpha);
	}

	/**
	 * Computes peaking-EQ coefficients; see {@link #lowPassCoefficients}. Unlike the non-gain
	 * responses, the denominator depends on the gain, so the shared {@code a1}/{@code a2} of
	 * {@link #simpleCoefficients} does not apply.
	 *
	 * @param center     center frequency in Hz, as a {@code (1)} producer
	 * @param q          Q factor, as a {@code (1)} producer
	 * @param gainDb     gain in decibels, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer peakingEQCoefficients(
			Producer<PackedCollection> center, Producer<PackedCollection> q,
			Producer<PackedCollection> gainDb, int sampleRate) {
		CollectionProducer a = gainAmplitude(gainDb);
		CollectionProducer cosW0 = cos(omega(center, sampleRate));
		CollectionProducer alpha = alpha(center, q, sampleRate);
		CollectionProducer alphaA = multiply(alpha, a);
		CollectionProducer alphaOverA = divide(alpha, a);
		return normalize(add(c(1.0), alphaA), cosW0.multiply(-2.0), subtract(c(1.0), alphaA),
				cosW0.multiply(-2.0), subtract(c(1.0), alphaOverA), add(c(1.0), alphaOverA));
	}

	/**
	 * Computes low-shelf coefficients; see {@link #lowPassCoefficients}.
	 *
	 * @param cutoff     cutoff frequency in Hz, as a {@code (1)} producer
	 * @param gainDb     gain in decibels, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer lowShelfCoefficients(
			Producer<PackedCollection> cutoff, Producer<PackedCollection> gainDb, int sampleRate) {
		CollectionProducer a = gainAmplitude(gainDb);
		CollectionProducer cosW0 = cos(omega(cutoff, sampleRate));
		CollectionProducer shelfAlpha2 = shelfAlpha2(cutoff, a, sampleRate);
		CollectionProducer aPlus = add(a, c(1.0));
		CollectionProducer aMinus = subtract(a, c(1.0));
		CollectionProducer aMinusCos = multiply(aMinus, cosW0);
		CollectionProducer aPlusCos = multiply(aPlus, cosW0);
		return normalize(
				multiply(a, add(subtract(aPlus, aMinusCos), shelfAlpha2)),
				multiply(a, subtract(aMinus, aPlusCos)).multiply(2.0),
				multiply(a, subtract(subtract(aPlus, aMinusCos), shelfAlpha2)),
				add(aMinus, aPlusCos).multiply(-2.0),
				subtract(add(aPlus, aMinusCos), shelfAlpha2),
				add(add(aPlus, aMinusCos), shelfAlpha2));
	}

	/**
	 * Computes high-shelf coefficients; see {@link #lowPassCoefficients}.
	 *
	 * @param cutoff     cutoff frequency in Hz, as a {@code (1)} producer
	 * @param gainDb     gain in decibels, as a {@code (1)} producer
	 * @param sampleRate sample rate in Hz
	 * @return a {@code (5)} producer of {@code [b0, b1, b2, a1, a2]}
	 */
	default CollectionProducer highShelfCoefficients(
			Producer<PackedCollection> cutoff, Producer<PackedCollection> gainDb, int sampleRate) {
		CollectionProducer a = gainAmplitude(gainDb);
		CollectionProducer cosW0 = cos(omega(cutoff, sampleRate));
		CollectionProducer shelfAlpha2 = shelfAlpha2(cutoff, a, sampleRate);
		CollectionProducer aPlus = add(a, c(1.0));
		CollectionProducer aMinus = subtract(a, c(1.0));
		CollectionProducer aMinusCos = multiply(aMinus, cosW0);
		CollectionProducer aPlusCos = multiply(aPlus, cosW0);
		return normalize(
				multiply(a, add(add(aPlus, aMinusCos), shelfAlpha2)),
				multiply(a, add(aMinus, aPlusCos)).multiply(-2.0),
				multiply(a, subtract(add(aPlus, aMinusCos), shelfAlpha2)),
				subtract(aMinus, aPlusCos).multiply(2.0),
				subtract(subtract(aPlus, aMinusCos), shelfAlpha2),
				add(subtract(aPlus, aMinusCos), shelfAlpha2));
	}

	/**
	 * Angular frequency {@code w0 = 2*pi*frequency/sampleRate}.
	 */
	private CollectionProducer omega(Producer<PackedCollection> frequency, int sampleRate) {
		return multiply(frequency, c(2.0 * Math.PI / sampleRate));
	}

	/**
	 * RBJ intermediate {@code alpha = sin(w0) / (2*Q)}.
	 */
	private CollectionProducer alpha(
			Producer<PackedCollection> frequency, Producer<PackedCollection> q, int sampleRate) {
		return divide(sin(omega(frequency, sampleRate)), multiply(q, c(2.0)));
	}

	/**
	 * Linear gain amplitude {@code A = 10^(gainDb/40)} used by the shelving and peaking responses.
	 */
	private CollectionProducer gainAmplitude(Producer<PackedCollection> gainDb) {
		return pow(c(10.0), divide(gainDb, c(40.0)));
	}

	/**
	 * Shelving intermediate {@code 2*sqrt(A)*alpha}, with the shelving {@code alpha} that fixes
	 * the shelf slope at the value the host formulas use.
	 */
	private CollectionProducer shelfAlpha2(
			Producer<PackedCollection> cutoff, CollectionProducer a, int sampleRate) {
		CollectionProducer slope =
				add(multiply(add(a, divide(c(1.0), a)), c(1.0 / 0.9 - 1.0)), c(2.0));
		CollectionProducer shelfAlpha =
				multiply(sin(omega(cutoff, sampleRate)).divide(2.0), sqrt(slope));
		return multiply(sqrt(a), shelfAlpha).multiply(2.0);
	}

	/**
	 * Assembles the five non-gain responses, which share {@code a0 = 1 + alpha},
	 * {@code a1 = -2*cos(w0)} and {@code a2 = 1 - alpha}; only {@code b0}, {@code b1} and
	 * {@code b2} differ between them.
	 */
	private CollectionProducer simpleCoefficients(
			CollectionProducer b0, CollectionProducer b1,
			CollectionProducer b2, CollectionProducer cosW0,
			CollectionProducer alpha) {
		return normalize(b0, b1, b2, cosW0.multiply(-2.0), subtract(c(1.0), alpha), add(c(1.0), alpha));
	}

	/**
	 * Normalizes {@code [b0, b1, b2, a1, a2]} by {@code a0} and concatenates them into the
	 * {@code (5)} coefficient vector.
	 */
	private CollectionProducer normalize(
			CollectionProducer b0, CollectionProducer b1,
			CollectionProducer b2, CollectionProducer a1,
			CollectionProducer a2, CollectionProducer a0) {
		return concat(shape(5), b0.divide(a0), b1.divide(a0), b2.divide(a0),
				a1.divide(a0), a2.divide(a0));
	}
}
