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

package org.almostrealism.audio;

import org.almostrealism.audio.filter.MultiOrderFilterEnvelopeProcessor;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies that the fully fused production entry point
 * {@link BatchedPatternRenderer#buildBatchedSssChainPlacedFromScalars} — which
 * generates all envelope curves inside the kernel from per-note ADSR scalars —
 * and {@link BatchedPatternRenderer#buildBatchedSssChainPlaced} fed the same curves
 * materialized from those scalars both reproduce the same window. This is the
 * gather's target API: a single dispatch consuming only cheap per-note scalars,
 * ratios, sources, and offsets.
 *
 * <p>Each path is checked against a double-precision reference assembled on the host,
 * not against the other path. The two kernels group the same arithmetic differently,
 * and generated kernels make no promise of rounding-exact grouping, so on a 32-bit
 * backend they legitimately differ by about as much as each differs from the exact
 * result. Comparing each to the exact result is what says whether either is wrong.</p>
 *
 * <p>The reference is host arithmetic on purpose, in the same spirit as
 * {@link org.almostrealism.util.FirFilterTestFeatures#referenceConvolve}: it is the
 * oracle the generated kernels are judged against, so it must not share their code.</p>
 */
public class BatchedSssFromScalarsTest extends BatchedSssTestBase {

	/** Audio sample rate used for envelope curve construction. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/**
	 * Largest RMS difference from the double-precision reference that either path may
	 * show. On OpenCL both paths measure about 6e-7 off the reference (signal RMS about
	 * 0.25), the FP32 rounding accumulated over three layers and the 41-tap filter; 5e-6
	 * is roughly eight times that, headroom for backends that round differently. A real
	 * defect — a wrong envelope segment, cutoff, ratio, filter window or placement — moves
	 * the output by orders of magnitude more (a collapsed filter window measured 0.14).
	 */
	private static final double REFERENCE_RMS_LIMIT = 5e-6;

	/**
	 * Verifies that the fused scalar-driven path and the pre-materialized envelope-curve
	 * path each match a double-precision reference to within FP32 rounding error, and so
	 * match each other.
	 */
	@Test(timeout = 240000)
	@TestDepth(2)
	public void testFromScalarsMatchesMaterialized() {
		BatchedPatternRenderer renderer = new BatchedPatternRenderer(
				N, SOURCE_LENGTH, TARGET_LENGTH, SAMPLE_RATE, FILTER_ORDER);
		Random rng = new Random(23L);

		PackedCollection[] sources = new PackedCollection[LAYERS];
		PackedCollection[] ratios = new PackedCollection[LAYERS];
		PackedCollection[][] layerEnvParams = new PackedCollection[LAYERS][8];
		PackedCollection[] layerCurves = new PackedCollection[LAYERS];

		for (int l = 0; l < LAYERS; l++) {
			PackedCollection batch =
					rand(shape(N, SOURCE_LENGTH), rng).multiply(2.0).add(-1.0).evaluate();
			sources[l] = batch;
			ratios[l] = perNote(1.0 + 0.1 * l, 0.05);
			layerEnvParams[l] = layerEnvelopeParameters(l);
			layerCurves[l] = renderer.buildLayerEnvelopeCurve(
					layerEnvParams[l][0], layerEnvParams[l][1], layerEnvParams[l][2], layerEnvParams[l][3],
					layerEnvParams[l][4], layerEnvParams[l][5], layerEnvParams[l][6], layerEnvParams[l][7])
					.get().evaluate();
		}

		PackedCollection[] filterAdsr = filterAdsr();
		PackedCollection[] volumeAdsr = volumeAdsr();

		PackedCollection filterCutoffs = renderer.buildVolumeEnvelopeCurve(
				filterAdsr[0], filterAdsr[1], filterAdsr[2], filterAdsr[3], filterAdsr[4])
				.multiply(c(MultiOrderFilterEnvelopeProcessor.filterPeak))
				.get().evaluate();
		PackedCollection volumeEnvelopes = renderer.buildVolumeEnvelopeCurve(
				volumeAdsr[0], volumeAdsr[1], volumeAdsr[2], volumeAdsr[3], volumeAdsr[4])
				.get().evaluate();

		PackedCollection destOffsets = destinationOffsets();

		double[] expected = referenceWindow(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr, destOffsets);

		PackedCollection materialized = renderer.buildBatchedSssChainPlaced(
				sources, ratios, layerCurves, filterCutoffs, volumeEnvelopes, destOffsets, WINDOW_WIDTH)
				.get().evaluate();
		assertMatchesReference("Materialized-curve path", expected, materialized);

		PackedCollection fused = renderer.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr, destOffsets, WINDOW_WIDTH)
				.get().evaluate();
		assertMatchesReference("Fused-from-scalars path", expected, fused);
	}

	/**
	 * Asserts that a rendered window is within {@link #REFERENCE_RMS_LIMIT} RMS of the
	 * double-precision reference, and that the reference is not silent (which would make
	 * any small difference meaningless).
	 *
	 * @param label    names the path in the log and in any failure
	 * @param expected the double-precision reference window
	 * @param actual   the rendered window
	 */
	private void assertMatchesReference(String label, double[] expected, PackedCollection actual) {
		double[] rendered = actual.toArray();
		double sumSqDiff = 0.0;
		double sumSqRef = 0.0;
		for (int i = 0; i < WINDOW_WIDTH; i++) {
			double diff = rendered[i] - expected[i];
			sumSqDiff += diff * diff;
			sumSqRef += expected[i] * expected[i];
		}

		double rms = Math.sqrt(sumSqDiff / WINDOW_WIDTH);
		double refRms = Math.sqrt(sumSqRef / WINDOW_WIDTH);
		log(String.format("%s: reference RMS %.4f, difference RMS %.2e", label, refRms, rms));

		Assert.assertTrue("Reference window is silent (RMS " + refRms + ")", refRms > 0.01);
		Assert.assertTrue(label + " differs from the double-precision reference (RMS " + rms + ")",
				rms < REFERENCE_RMS_LIMIT);
	}

	/**
	 * Computes the placed SSS window on the host in double precision, following the
	 * definitions the renderer documents: each layer is linearly resampled by its ratio
	 * and scaled by its layer envelope, the layers are summed, the sum is low-passed with
	 * a per-sample cutoff (the filter ADSR times the filter peak) using taps that read
	 * zeros beyond the note, scaled by the volume ADSR, and added into the window at the
	 * note's destination offset, truncated at the window edge.
	 *
	 * @param sources        per-layer flattened sources, each shape {@code [N, SOURCE_LENGTH]}
	 * @param ratios         per-layer per-note pitch ratios, each shape {@code [N]}
	 * @param layerEnvParams per-layer envelope scalars, {@code [layer][8]}, each {@code [N]}
	 * @param filterAdsr     filter-envelope scalars {@code [5]}, each {@code [N]}
	 * @param volumeAdsr     volume-envelope scalars {@code [5]}, each {@code [N]}
	 * @param destOffsets    per-note destination offsets in frames, shape {@code [N]}
	 * @return the reference window, {@code WINDOW_WIDTH} samples
	 */
	private double[] referenceWindow(PackedCollection[] sources, PackedCollection[] ratios,
											 PackedCollection[][] layerEnvParams,
											 PackedCollection[] filterAdsr, PackedCollection[] volumeAdsr,
											 PackedCollection destOffsets) {
		double[][] src = new double[LAYERS][];
		double[][] ratio = new double[LAYERS][];
		double[][][] layerParams = new double[LAYERS][8][];
		for (int l = 0; l < LAYERS; l++) {
			src[l] = sources[l].toArray();
			ratio[l] = ratios[l].toArray();
			for (int p = 0; p < 8; p++) layerParams[l][p] = layerEnvParams[l][p].toArray();
		}

		double[][] filter = new double[5][];
		double[][] volume = new double[5][];
		for (int p = 0; p < 5; p++) {
			filter[p] = filterAdsr[p].toArray();
			volume[p] = volumeAdsr[p].toArray();
		}

		double[] offsets = destOffsets.toArray();
		double[] window = new double[WINDOW_WIDTH];
		int half = FILTER_ORDER / 2;

		for (int n = 0; n < N; n++) {
			double[] merged = new double[TARGET_LENGTH];
			for (int s = 0; s < TARGET_LENGTH; s++) {
				double time = s / (double) SAMPLE_RATE;
				for (int l = 0; l < LAYERS; l++) {
					double pos = s * ratio[l][n];
					int base = (int) Math.floor(pos);
					double s0 = src[l][n * SOURCE_LENGTH + base];
					double s1 = src[l][n * SOURCE_LENGTH + base + 1];
					merged[s] += (s0 + (pos - base) * (s1 - s0)) * layerGain(time, layerParams[l], n);
				}
			}

			int offset = (int) offsets[n];
			for (int s = 0; s < TARGET_LENGTH && offset + s < WINDOW_WIDTH; s++) {
				double time = s / (double) SAMPLE_RATE;
				double cutoff = adsrGain(time, filter, n) * MultiOrderFilterEnvelopeProcessor.filterPeak;
				double[] taps = referenceLowPassTaps(cutoff, SAMPLE_RATE, FILTER_ORDER);

				double filtered = 0.0;
				for (int k = 0; k <= FILTER_ORDER; k++) {
					int idx = s + k - half;
					if (idx >= 0 && idx < TARGET_LENGTH) filtered += merged[idx] * taps[k];
				}

				window[offset + s] += filtered * adsrGain(time, volume, n);
			}
		}

		return window;
	}

	/**
	 * The volume-style ADSR gain documented on
	 * {@link BatchedPatternRenderer#buildVolumeEnvelopeCurve}, in double precision.
	 *
	 * @param time   note-elapsed time in seconds
	 * @param params (attack, decay, sustain, release, duration), each per note
	 * @param n      the note index
	 * @return the gain
	 */
	private static double adsrGain(double time, double[][] params, int n) {
		double sustain = params[2][n];
		double release = params[3][n];
		double duration = params[4][n];
		double attack = Math.min(params[0][n], duration * 0.75);
		double decay = Math.min(params[1][n], duration * 0.25);

		if (time > duration) return segment(time, duration, release, sustain, 0.0);
		if (time > attack + decay) return sustain;
		if (time > attack) return segment(time, attack, decay, 1.0, sustain);
		return Math.min(time / attack, 1.0);
	}

	/**
	 * The three-segment layer gain documented on
	 * {@link BatchedPatternRenderer#buildLayerEnvelopeCurve}, in double precision.
	 *
	 * @param time   note-elapsed time in seconds
	 * @param params (mainDuration, f0, f1, f2, v0, v1, v2, v3), each per note
	 * @param n      the note index
	 * @return the gain
	 */
	private static double layerGain(double time, double[][] params, int n) {
		double d0 = params[0][n] * params[1][n];
		double d1 = params[0][n] * params[2][n];
		double d2 = params[0][n] * params[3][n];

		if (time > d1) return segment(time, d1, d2 - d1, params[6][n], params[7][n]);
		if (time > d0) return segment(time, d0, d1 - d0, params[5][n], params[6][n]);
		return segment(time, 0.0, d0, params[4][n], params[5][n]);
	}

	/**
	 * One clamped linear envelope segment: {@code max(0, (1 - p) * start + p * end)}
	 * with {@code p = (time - offset) / duration}.
	 *
	 * @param time     time in seconds
	 * @param offset   segment start time in seconds
	 * @param duration segment duration in seconds
	 * @param start    level at the segment start
	 * @param end      level at the segment end
	 * @return the segment level
	 */
	private static double segment(double time, double offset, double duration, double start, double end) {
		double p = (time - offset) / duration;
		return Math.max((1.0 - p) * start + p * end, 0.0);
	}
}
