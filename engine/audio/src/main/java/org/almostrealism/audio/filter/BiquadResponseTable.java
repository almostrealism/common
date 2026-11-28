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

package org.almostrealism.audio.filter;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * The FIR realisation of {@link AudioPassFilter}'s biquad: a {@code [BINS, taps]} table of
 * its truncated impulse responses over log-spaced cutoffs in
 * {@code [MIN_FREQUENCY, 20000]} Hz, and the device-side gather that selects the row for a
 * cutoff. A swept filter re-selects its row each buffer instead of recomputing the response.
 *
 * <p>With {@code c = tan(PI*f/sr)} (high-pass) or its reciprocal (low-pass) and
 * {@code a1 = 1/(1 + r*c + c*c)}, the impulse response follows the recurrence
 * {@code y[n] = a1*x[n] + a2*x[n-1] + a3*x[n-2] - b1*y[n-1] - b2*y[n-2]} driven by a unit
 * impulse. Rather than unroll that recurrence (whose naive expression tree grows
 * exponentially), the response is generated in closed form: for the project resonance
 * the discriminant {@code b1*b1 - 4*b2} reduces to {@code -c*c*(4 - r*r) < 0}, so the
 * poles are <em>always</em> a complex-conjugate pair {@code rho*exp(+/- i*theta)} with
 * {@code rho = sqrt(b2)} and {@code theta = acos(-b1 / (2*rho))}. The homogeneous IR is
 * therefore {@code y[n] = rho^n * (A*cos(n*theta) + B*sin(n*theta))} for {@code n >= 1}
 * (with {@code A}, {@code B} fit to {@code y[1]}, {@code y[2]}), and {@code y[0] = a1}.
 * Every coefficient is a per-bin scalar producer; the table is the outer combination of
 * those with the tap index. This matches Java's exact 12 dB/oct biquad (a windowed-sinc FIR
 * of the same order is far steeper and audibly diverges during a cutoff sweep).</p>
 */
public class BiquadResponseTable implements CodeFeatures {
	/** Number of log-spaced cutoff bins in the table. */
	public static final int BINS = 1024;

	/** Bin-selection scale: {@code (BINS - 1) / ln(20000 / MIN_FREQUENCY)}. */
	private static final double BIN_SCALE =
			(BINS - 1) / Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);

	/** Audio sample rate in Hz. */
	private final int sampleRate;

	/** FIR taps per response. */
	private final int taps;

	/** The biquad resonance. */
	private final double resonance;

	/** Whether the responses are of the high-pass design rather than the low-pass one. */
	private final boolean high;

	/**
	 * Describes the table of one biquad design.
	 *
	 * @param sampleRate audio sample rate in Hz
	 * @param taps       FIR taps per response
	 * @param resonance  the biquad resonance
	 * @param high       {@code true} for the high-pass design, {@code false} for low-pass
	 */
	public BiquadResponseTable(int sampleRate, int taps, double resonance, boolean high) {
		this.sampleRate = sampleRate;
		this.taps = taps;
		this.resonance = resonance;
		this.high = high;
	}

	/**
	 * Computes the table, entirely as a {@link CollectionProducer} graph evaluated into a new
	 * {@code [BINS, taps]} collection, which the caller owns.
	 *
	 * @return the response table
	 */
	public PackedCollection tabulate() {
		int tail = taps - 1;
		double r = resonance;
		double span = Math.log(20000.0 / AudioPassFilter.MIN_FREQUENCY);
		double piOverSr = Math.PI / sampleRate;

		CollectionProducer cutoff = exp(
				integers(0, BINS).multiply(span / (BINS - 1.0)))
				.multiply(AudioPassFilter.MIN_FREQUENCY);
		CollectionProducer t = tan(cutoff.multiply(piOverSr));
		CollectionProducer c = high ? t : t.pow(-1.0);
		CollectionProducer csq = c.multiply(c);
		CollectionProducer rc = c.multiply(r);
		CollectionProducer a1 = add(rc, csq).add(1.0).pow(-1.0);
		CollectionProducer a2 = high ? a1.multiply(-2.0) : a1.multiply(2.0);
		CollectionProducer a3 = a1;
		CollectionProducer b1 = high
				? csq.subtract(1.0).multiply(2.0).multiply(a1)
				: csq.multiply(-1.0).add(1.0).multiply(2.0).multiply(a1);
		CollectionProducer b2 = add(rc.multiply(-1.0), csq).add(1.0).multiply(a1);

		CollectionProducer rho = sqrt(b2);
		CollectionProducer theta = acos(
				divide(b1.multiply(-1.0), rho.multiply(2.0)));

		// Impulse-response seeds y[0], y[1], y[2].
		CollectionProducer y0 = a1;
		CollectionProducer y1 = subtract(a2, multiply(b1, y0));
		CollectionProducer y2 = subtract(
				subtract(a3, multiply(b1, y1)), multiply(b2, y0));

		// Closed-form amplitudes: det = cos(t)sin(2t) - cos(2t)sin(t) = sin(t).
		CollectionProducer c1 = cos(theta);
		CollectionProducer s1 = sin(theta);
		CollectionProducer c2 = cos(theta.multiply(2.0));
		CollectionProducer s2 = sin(theta.multiply(2.0));
		CollectionProducer y1n = divide(y1, rho);
		CollectionProducer y2n = divide(y2, b2);
		CollectionProducer aCoef = divide(
				subtract(multiply(y1n, s2), multiply(y2n, s1)), s1);
		CollectionProducer bCoef = divide(
				subtract(multiply(y2n, c1), multiply(y1n, c2)), s1);

		// repeat(axis, n) inserts a new dimension: a [BINS] column repeated on axis 1 and a
		// [tail] row repeated on axis 0 broadcast to the n*theta / n*ln(rho) outer combination.
		CollectionProducer nRow = integers(0, tail).add(1.0).repeat(0, BINS);
		CollectionProducer nTheta = multiply(theta.repeat(1, tail), nRow);
		CollectionProducer rhoPow = exp(
				multiply(log(rho).repeat(1, tail), nRow));
		CollectionProducer general = multiply(rhoPow,
				add(multiply(aCoef.repeat(1, tail), cos(nTheta)),
						multiply(bCoef.repeat(1, tail), sin(nTheta))));

		CollectionProducer response = concat(1, a1.reshape(BINS, 1), general);
		PackedCollection table = new PackedCollection(new TraversalPolicy(BINS, taps));
		a(BINS * taps, cp(table), response).get().run();
		return table;
	}

	/**
	 * Builds a {@code [rows, taps]} producer selecting, for each of the {@code [rows]} cutoffs,
	 * the row of {@code table} for the bin nearest that cutoff:
	 * {@code bin = floor(BIN_SCALE * ln(cutoff / MIN_FREQUENCY) + 0.5)}. Entirely device-side
	 * (log, scale, floor, indexed gather). The bins of every cutoff are computed and gathered
	 * together, so a bank of per-channel cutoffs compiles to one kernel rather than one per
	 * channel.
	 *
	 * @param table   a table {@link #tabulate() tabulated} from this description
	 * @param cutoffs shape-{@code [rows]} cutoff producer in Hz (bounded to the table's range)
	 * @return a {@code [rows, taps]} coefficient producer
	 */
	public CollectionProducer rows(PackedCollection table, Producer<PackedCollection> cutoffs) {
		int rows = shape(cutoffs).getTotalSize();
		CollectionProducer bin = floor(add(
				multiply(
						log(divide(cutoffs,
								c(AudioPassFilter.MIN_FREQUENCY))),
						c(BIN_SCALE)),
				c(0.5)));
		Producer<PackedCollection> positions = add(
				bin.multiply((double) taps).repeat(1, taps),
				integers(0, taps).repeat(0, rows));
		return c(shape(rows, taps), cp(table), positions);
	}
}
