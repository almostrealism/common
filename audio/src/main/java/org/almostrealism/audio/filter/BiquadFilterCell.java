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
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A temporal cell that implements a biquad IIR filter for audio processing.
 * <p>
 * This cell processes incoming audio through a biquad filter using the
 * Direct Form I equation:
 * {@code y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]}
 * <p>
 * The filter can be configured for various filter types using the static
 * factory methods or by directly setting coefficients.
 * <p>
 * Supported filter types:
 * <ul>
 *   <li>Low-pass filter</li>
 *   <li>High-pass filter</li>
 *   <li>Band-pass filter</li>
 *   <li>Notch (band-reject) filter</li>
 *   <li>All-pass filter</li>
 *   <li>Peaking EQ filter</li>
 *   <li>Low shelf filter</li>
 *   <li>High shelf filter</li>
 * </ul>
 *
 * @see BiquadFilterData
 * @see CollectionTemporalCellAdapter
 */
public class BiquadFilterCell extends CollectionTemporalCellAdapter implements CodeFeatures {

	private final BiquadFilterData data;
	private final PackedCollection outputValue;
	private final int sampleRate;

	/**
	 * Creates a new BiquadFilterCell with default data storage.
	 */
	public BiquadFilterCell() {
		this(OutputLine.sampleRate);
	}

	/**
	 * Creates a new BiquadFilterCell with the specified sample rate.
	 *
	 * @param sampleRate the sample rate in Hz
	 */
	public BiquadFilterCell(int sampleRate) {
		this(new DefaultBiquadFilterData(), sampleRate);
	}

	/**
	 * Creates a new BiquadFilterCell with the specified data storage.
	 *
	 * @param data the filter data storage
	 * @param sampleRate the sample rate in Hz
	 */
	public BiquadFilterCell(BiquadFilterData data, int sampleRate) {
		this.data = data;
		this.outputValue = new PackedCollection(1);
		this.sampleRate = sampleRate;
	}

	/**
	 * Returns the filter data storage.
	 */
	public BiquadFilterData getData() {
		return data;
	}

	/**
	 * Sets the filter coefficients directly.
	 *
	 * @param b0 feedforward coefficient for x[n]
	 * @param b1 feedforward coefficient for x[n-1]
	 * @param b2 feedforward coefficient for x[n-2]
	 * @param a1 feedback coefficient for y[n-1]
	 * @param a2 feedback coefficient for y[n-2]
	 */
	public void setCoefficients(double b0, double b1, double b2, double a1, double a2) {
		data.setCoefficients(b0, b1, b2, a1, a2);
	}

	/**
	 * Configures this cell as a low-pass filter.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param q Q factor (resonance), typically 0.707 for Butterworth response
	 */
	public void configureLowPass(double cutoffHz, double q) {
		double[] coeffs = calculateLowPassCoefficients(cutoffHz, q, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as a high-pass filter.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param q Q factor (resonance), typically 0.707 for Butterworth response
	 */
	public void configureHighPass(double cutoffHz, double q) {
		double[] coeffs = calculateHighPassCoefficients(cutoffHz, q, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as a band-pass filter.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor determining bandwidth
	 */
	public void configureBandPass(double centerHz, double q) {
		double[] coeffs = calculateBandPassCoefficients(centerHz, q, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as a notch (band-reject) filter.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor determining bandwidth
	 */
	public void configureNotch(double centerHz, double q) {
		double[] coeffs = calculateNotchCoefficients(centerHz, q, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as an all-pass filter.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor
	 */
	public void configureAllPass(double centerHz, double q) {
		double[] coeffs = calculateAllPassCoefficients(centerHz, q, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as a peaking EQ filter.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor determining bandwidth
	 * @param gainDb gain in decibels
	 */
	public void configurePeakingEQ(double centerHz, double q, double gainDb) {
		double[] coeffs = calculatePeakingEQCoefficients(centerHz, q, gainDb, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as a low shelf filter.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param gainDb gain in decibels
	 */
	public void configureLowShelf(double cutoffHz, double gainDb) {
		double[] coeffs = calculateLowShelfCoefficients(cutoffHz, gainDb, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Configures this cell as a high shelf filter.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param gainDb gain in decibels
	 */
	public void configureHighShelf(double cutoffHz, double gainDb) {
		double[] coeffs = calculateHighShelfCoefficients(cutoffHz, gainDb, sampleRate);
		setCoefficients(coeffs[0], coeffs[1], coeffs[2], coeffs[3], coeffs[4]);
	}

	/**
	 * Resets the filter state (delay lines) to zero.
	 */
	public void resetState() {
		data.resetState();
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("BiquadFilterCell Setup");
		setup.add(super.setup());
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		OperationList push = new OperationList("BiquadFilterCell Push");

		// Get producers for all coefficients and state
		Producer<PackedCollection> b0 = data.getB0();
		Producer<PackedCollection> b1 = data.getB1();
		Producer<PackedCollection> b2 = data.getB2();
		Producer<PackedCollection> a1 = data.getA1();
		Producer<PackedCollection> a2 = data.getA2();
		Producer<PackedCollection> x1 = data.getX1();
		Producer<PackedCollection> x2 = data.getX2();
		Producer<PackedCollection> y1 = data.getY1();
		Producer<PackedCollection> y2 = data.getY2();

		// Compute Direct Form I: y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
		CollectionProducer feedforward = add(
				multiply(b0, protein),
				add(multiply(b1, x1), multiply(b2, x2))
		);

		CollectionProducer feedback = add(
				multiply(a1, y1),
				multiply(a2, y2)
		);

		CollectionProducer y0 = subtract(feedforward, feedback);

		// Write output
		push.add(a(p(outputValue), y0));

		// Update delay lines: shift history
		// Order matters: must read old values before overwriting
		push.add(a(p(data.x2()), x1));
		push.add(a(p(data.x1()), protein));
		push.add(a(p(data.y2()), y1));
		push.add(a(p(data.y1()), y0));

		push.add(super.push(p(outputValue)));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("BiquadFilterCell Tick");
		tick.add(super.tick());
		return tick;
	}

	// Static factory methods

	/**
	 * Creates a low-pass filter cell.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param q Q factor (resonance)
	 * @return configured filter cell
	 */
	public static BiquadFilterCell lowPass(double cutoffHz, double q) {
		return lowPass(cutoffHz, q, OutputLine.sampleRate);
	}

	/**
	 * Creates a low-pass filter cell with specified sample rate.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param q Q factor (resonance)
	 * @param sampleRate sample rate in Hz
	 * @return configured filter cell
	 */
	public static BiquadFilterCell lowPass(double cutoffHz, double q, int sampleRate) {
		BiquadFilterCell cell = new BiquadFilterCell(sampleRate);
		cell.configureLowPass(cutoffHz, q);
		return cell;
	}

	/**
	 * Creates a high-pass filter cell.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param q Q factor (resonance)
	 * @return configured filter cell
	 */
	public static BiquadFilterCell highPass(double cutoffHz, double q) {
		return highPass(cutoffHz, q, OutputLine.sampleRate);
	}

	/**
	 * Creates a high-pass filter cell with specified sample rate.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param q Q factor (resonance)
	 * @param sampleRate sample rate in Hz
	 * @return configured filter cell
	 */
	public static BiquadFilterCell highPass(double cutoffHz, double q, int sampleRate) {
		BiquadFilterCell cell = new BiquadFilterCell(sampleRate);
		cell.configureHighPass(cutoffHz, q);
		return cell;
	}

	/**
	 * Creates a band-pass filter cell.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor
	 * @return configured filter cell
	 */
	public static BiquadFilterCell bandPass(double centerHz, double q) {
		return bandPass(centerHz, q, OutputLine.sampleRate);
	}

	/**
	 * Creates a band-pass filter cell with specified sample rate.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor
	 * @param sampleRate sample rate in Hz
	 * @return configured filter cell
	 */
	public static BiquadFilterCell bandPass(double centerHz, double q, int sampleRate) {
		BiquadFilterCell cell = new BiquadFilterCell(sampleRate);
		cell.configureBandPass(centerHz, q);
		return cell;
	}

	/**
	 * Creates a notch (band-reject) filter cell.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor
	 * @return configured filter cell
	 */
	public static BiquadFilterCell notch(double centerHz, double q) {
		return notch(centerHz, q, OutputLine.sampleRate);
	}

	/**
	 * Creates a notch filter cell with specified sample rate.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor
	 * @param sampleRate sample rate in Hz
	 * @return configured filter cell
	 */
	public static BiquadFilterCell notch(double centerHz, double q, int sampleRate) {
		BiquadFilterCell cell = new BiquadFilterCell(sampleRate);
		cell.configureNotch(centerHz, q);
		return cell;
	}

	// Coefficient calculation methods using Robert Bristow-Johnson's Audio EQ Cookbook formulas

	/**
	 * Calculates low-pass filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateLowPassCoefficients(double cutoffHz, double q, int sampleRate) {
		double w0 = 2.0 * Math.PI * cutoffHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / (2.0 * q);

		double b0 = (1.0 - cosW0) / 2.0;
		double b1 = 1.0 - cosW0;
		double b2 = (1.0 - cosW0) / 2.0;
		double a0 = 1.0 + alpha;
		double a1 = -2.0 * cosW0;
		double a2 = 1.0 - alpha;

		// Normalize by a0
		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates high-pass filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateHighPassCoefficients(double cutoffHz, double q, int sampleRate) {
		double w0 = 2.0 * Math.PI * cutoffHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / (2.0 * q);

		double b0 = (1.0 + cosW0) / 2.0;
		double b1 = -(1.0 + cosW0);
		double b2 = (1.0 + cosW0) / 2.0;
		double a0 = 1.0 + alpha;
		double a1 = -2.0 * cosW0;
		double a2 = 1.0 - alpha;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates band-pass filter coefficients (constant skirt gain, peak gain = Q).
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateBandPassCoefficients(double centerHz, double q, int sampleRate) {
		double w0 = 2.0 * Math.PI * centerHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / (2.0 * q);

		double b0 = alpha;
		double b1 = 0.0;
		double b2 = -alpha;
		double a0 = 1.0 + alpha;
		double a1 = -2.0 * cosW0;
		double a2 = 1.0 - alpha;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates notch (band-reject) filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateNotchCoefficients(double centerHz, double q, int sampleRate) {
		double w0 = 2.0 * Math.PI * centerHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / (2.0 * q);

		double b0 = 1.0;
		double b1 = -2.0 * cosW0;
		double b2 = 1.0;
		double a0 = 1.0 + alpha;
		double a1 = -2.0 * cosW0;
		double a2 = 1.0 - alpha;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates all-pass filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateAllPassCoefficients(double centerHz, double q, int sampleRate) {
		double w0 = 2.0 * Math.PI * centerHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / (2.0 * q);

		double b0 = 1.0 - alpha;
		double b1 = -2.0 * cosW0;
		double b2 = 1.0 + alpha;
		double a0 = 1.0 + alpha;
		double a1 = -2.0 * cosW0;
		double a2 = 1.0 - alpha;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates peaking EQ filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculatePeakingEQCoefficients(double centerHz, double q, double gainDb, int sampleRate) {
		double A = Math.pow(10.0, gainDb / 40.0);
		double w0 = 2.0 * Math.PI * centerHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / (2.0 * q);

		double b0 = 1.0 + alpha * A;
		double b1 = -2.0 * cosW0;
		double b2 = 1.0 - alpha * A;
		double a0 = 1.0 + alpha / A;
		double a1 = -2.0 * cosW0;
		double a2 = 1.0 - alpha / A;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates low shelf filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateLowShelfCoefficients(double cutoffHz, double gainDb, int sampleRate) {
		double A = Math.pow(10.0, gainDb / 40.0);
		double w0 = 2.0 * Math.PI * cutoffHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / 2.0 * Math.sqrt((A + 1.0 / A) * (1.0 / 0.9 - 1.0) + 2.0);
		double sqrtA2Alpha = 2.0 * Math.sqrt(A) * alpha;

		double b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + sqrtA2Alpha);
		double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0);
		double b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - sqrtA2Alpha);
		double a0 = (A + 1.0) + (A - 1.0) * cosW0 + sqrtA2Alpha;
		double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0);
		double a2 = (A + 1.0) + (A - 1.0) * cosW0 - sqrtA2Alpha;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}

	/**
	 * Calculates high shelf filter coefficients.
	 *
	 * @return array of [b0, b1, b2, a1, a2]
	 */
	public static double[] calculateHighShelfCoefficients(double cutoffHz, double gainDb, int sampleRate) {
		double A = Math.pow(10.0, gainDb / 40.0);
		double w0 = 2.0 * Math.PI * cutoffHz / sampleRate;
		double cosW0 = Math.cos(w0);
		double sinW0 = Math.sin(w0);
		double alpha = sinW0 / 2.0 * Math.sqrt((A + 1.0 / A) * (1.0 / 0.9 - 1.0) + 2.0);
		double sqrtA2Alpha = 2.0 * Math.sqrt(A) * alpha;

		double b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + sqrtA2Alpha);
		double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0);
		double b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - sqrtA2Alpha);
		double a0 = (A + 1.0) - (A - 1.0) * cosW0 + sqrtA2Alpha;
		double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0);
		double a2 = (A + 1.0) - (A - 1.0) * cosW0 - sqrtA2Alpha;

		return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
	}
}
