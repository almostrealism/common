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

package org.almostrealism.ml.audio;

/**
 * Log-SNR spaced timestep warp with a length-adaptive upper bound.
 *
 * <p>The uniform timestep {@code t} is mapped through
 * {@code logSNR = logSnrEnd - t * (logSnrEnd - logSnrStart)} and back to a noise level with
 * {@code sigmoid(-logSNR)}, so that the sampling steps are spaced uniformly in log signal-to-noise
 * ratio. The high-noise bound {@code logSnrStart} follows the "one per doubling" rule
 * {@code anchorLogSnr - rate * log2(sequenceLength / anchorLength)}: the noise level at which structure
 * emerges drops by {@code rate} each time the sequence length doubles, while the low-noise bound
 * {@code logSnrEnd} is fixed because late refinement is local. Endpoints are preserved exactly
 * ({@code 0 -> 0}, {@code 1 -> 1}).</p>
 *
 * <p>With {@code rate = 0} the warp is independent of the sequence length; that configuration
 * reproduces the classic fixed log-SNR schedule of {@link PingPongSamplingStrategy}.</p>
 */
public class LogSNRShift implements DistributionShift {

	/** Sequence length at which {@code logSnrStart} equals {@code anchorLogSnr}. */
	private final double anchorLength;

	/** High-noise log-SNR bound at the anchor length. */
	private final double anchorLogSnr;

	/** Drop in {@code logSnrStart} per doubling of the sequence length. */
	private final double rate;

	/** Low-noise log-SNR bound, fixed for every length. */
	private final double logSnrEnd;

	/**
	 * Creates the length-adaptive warp with the reference defaults
	 * ({@code anchorLength = 2000}, {@code anchorLogSnr = -6.2}, {@code rate = 1}, {@code logSnrEnd = 2}).
	 */
	public LogSNRShift() {
		this(2000, -6.2, 1.0, 2.0);
	}

	/**
	 * Creates a log-SNR warp.
	 *
	 * @param anchorLength sequence length at which the high-noise bound equals {@code anchorLogSnr}
	 * @param anchorLogSnr high-noise log-SNR bound at the anchor length
	 * @param rate         drop in the high-noise bound per doubling of the sequence length
	 *                     ({@code 0} makes the warp length-invariant)
	 * @param logSnrEnd    low-noise log-SNR bound
	 */
	public LogSNRShift(double anchorLength, double anchorLogSnr, double rate, double logSnrEnd) {
		if (anchorLength <= 0) {
			throw new IllegalArgumentException("anchorLength must be positive");
		}

		this.anchorLength = anchorLength;
		this.anchorLogSnr = anchorLogSnr;
		this.rate = rate;
		this.logSnrEnd = logSnrEnd;
	}

	/**
	 * Creates a length-invariant log-SNR warp spanning a fixed log-SNR range.
	 *
	 * @param logSnrStart high-noise log-SNR bound (the value reached at {@code t = 1})
	 * @param logSnrEnd   low-noise log-SNR bound (the value reached at {@code t = 0})
	 * @return the warp
	 */
	public static LogSNRShift fixed(double logSnrStart, double logSnrEnd) {
		return new LogSNRShift(1.0, logSnrStart, 0.0, logSnrEnd);
	}

	/**
	 * The high-noise log-SNR bound for the given sequence length.
	 *
	 * @param sequenceLength latent sequence length
	 * @return {@code anchorLogSnr - rate * log2(sequenceLength / anchorLength)}
	 */
	public double getLogSnrStart(int sequenceLength) {
		if (rate == 0.0) {
			return anchorLogSnr;
		}

		double log2Ratio = Math.log(sequenceLength / anchorLength) / Math.log(2.0);
		return anchorLogSnr - rate * log2Ratio;
	}

	@Override
	public boolean isLengthDependent() {
		return rate != 0.0;
	}

	@Override
	public double shift(double t, int sequenceLength) {
		if (t <= 0.0) {
			return 0.0;
		} else if (t >= 1.0) {
			return 1.0;
		}

		double logSnr = logSnrEnd - t * (logSnrEnd - getLogSnrStart(sequenceLength));
		return 1.0 / (1.0 + Math.exp(logSnr));
	}
}
