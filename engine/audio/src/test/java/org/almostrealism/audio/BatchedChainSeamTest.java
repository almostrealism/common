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
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Characterizes the windowed-FIR seam in the batched melodic-SSS chain. Rendering
 * a sustained note over a single {@code 2W}-frame window is compared to rendering
 * it as two {@code W}-frame windows at sampling offsets {@code 0} and {@code W}
 * (the way {@code BatchedPatternLayerRenderer} sub-windows a note spanning a window
 * boundary).
 *
 * <p>The resample and all envelope stages carry no cross-row state, so the split
 * reproduces the single render <em>exactly</em> everywhere except within
 * {@code padHalf = filterOrder/2} samples of the boundary, where the per-row FIR
 * (each row zero-padded by {@code padHalf}) reads padding instead of the note's
 * continuation. This test asserts the interior is exact and that all divergence is
 * confined to the {@code +/-padHalf} seam, and logs the seam magnitude so the
 * cost of sub-window splitting is quantified for the production-on decision.</p>
 */
public class BatchedChainSeamTest extends TestSuiteBase implements TemporalFeatures {

	/** Half-window width in frames; the single render spans {@code 2 * W}. */
	private static final int W = 512;

	/** Per-note source buffer length (longer than {@code 2 * W}). */
	private static final int SOURCE_LENGTH = 2048;

	/** Audio sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Production FIR order; the seam half-width is {@code filterOrder / 2}. */
	private static final int FILTER_ORDER = MultiOrderFilterEnvelopeProcessor.filterOrder;

	private static final int LAYERS = 3;

	private PackedCollection single(double value) {
		PackedCollection c = new PackedCollection(1);
		c.setMem(new double[] { value });
		return c;
	}

	/** A sine source so the FIR operates on real spectral content. */
	private PackedCollection sineSource(double cyclesPerSample) {
		double[] data = new double[SOURCE_LENGTH];
		for (int i = 0; i < SOURCE_LENGTH; i++) {
			data[i] = Math.sin(2.0 * Math.PI * cyclesPerSample * i);
		}
		PackedCollection c = new PackedCollection(SOURCE_LENGTH);
		c.setMem(data);
		return c;
	}

	@Test(timeout = 120000)
	@TestDepth(2)
	public void chainSplitsAcrossWindowsWithBoundedSeam() {
		double duration = (2.0 * W) / SAMPLE_RATE;

		PackedCollection[] sources = {
				sineSource(0.02), sineSource(0.031), sineSource(0.043) };
		PackedCollection[] ratios = { single(1.0), single(1.0), single(1.0) };

		// Flat-ish layer envelopes (~1 throughout) so the note sounds across the
		// whole span and the seam lands on active audio rather than a silent tail.
		PackedCollection[][] layerEnvParams = new PackedCollection[LAYERS][8];
		for (int l = 0; l < LAYERS; l++) {
			double[] p = { duration, 0.3, 0.6, 1.0, 1.0, 1.0, 1.0, 1.0 };
			for (int i = 0; i < 8; i++) layerEnvParams[l][i] = single(p[i]);
		}

		// High, sustained cutoff and volume so the note is active across the boundary.
		PackedCollection[] filterAdsr = {
				single(0.0005), single(0.0005), single(0.9), single(0.02), single(duration) };
		PackedCollection[] volumeAdsr = {
				single(0.0005), single(0.0005), single(0.9), single(0.02), single(duration) };

		BatchedPatternRenderer full = new BatchedPatternRenderer(1, SOURCE_LENGTH, 2 * W, SAMPLE_RATE, FILTER_ORDER);
		BatchedPatternRenderer win = new BatchedPatternRenderer(1, SOURCE_LENGTH, W, SAMPLE_RATE, FILTER_ORDER);

		PackedCollection reference = full.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr,
				single(0), single(0), 2 * W).get().evaluate();
		PackedCollection window0 = win.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr,
				single(0), single(0), W).get().evaluate();
		PackedCollection window1 = win.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr,
				single(0), single(W), W).get().evaluate();

		int padHalf = FILTER_ORDER / 2;
		double energy = 0.0;
		double interiorWorst = 0.0;
		double seamSumSq = 0.0;
		int seamCount = 0;
		for (int i = 0; i < 2 * W; i++) {
			double ref = reference.toDouble(i);
			double split = i < W ? window0.toDouble(i) : window1.toDouble(i - W);
			double diff = Math.abs(ref - split);
			energy += ref * ref;
			if (Math.abs(i - W) <= padHalf) {
				seamSumSq += diff * diff;
				seamCount++;
			} else {
				interiorWorst = Math.max(interiorWorst, diff);
			}
		}
		double refRms = Math.sqrt(energy / (2 * W));
		double seamRms = Math.sqrt(seamSumSq / seamCount);

		log("chain seam: refRms=" + refRms + " interiorWorstAbsDiff=" + interiorWorst
				+ " seamRms=" + seamRms + " seamSamples=" + seamCount + "/" + (2 * W));

		Assert.assertTrue("reference is trivially silent (refRms=" + refRms + ")", refRms > 1e-3);
		// Away from the boundary the split is exact: resample and envelopes carry no
		// cross-row state, and the FIR interior reads identical audio.
		Assert.assertEquals("split diverges from the single render outside the FIR seam",
				0.0, interiorWorst, 1e-6);
		// Inside the seam the per-row FIR reads zero padding instead of the note's
		// continuation; the divergence is real but confined to the +/-padHalf band.
		Assert.assertTrue("seam divergence unexpectedly large relative to signal (seamRms="
				+ seamRms + ", refRms=" + refRms + ")", seamRms < refRms);
	}
}
