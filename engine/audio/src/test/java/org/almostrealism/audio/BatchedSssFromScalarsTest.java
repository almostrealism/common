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

import java.util.Random;

/**
 * Verifies that the fully fused production entry point
 * {@link BatchedPatternRenderer#buildBatchedSssChainPlacedFromScalars} — which
 * generates all envelope curves inside the kernel from per-note ADSR scalars —
 * produces the same output as the already-verified
 * {@link BatchedPatternRenderer#buildBatchedSssChainPlaced} fed the same curves
 * materialized from those scalars. This is the gather's target API: a single
 * dispatch consuming only cheap per-note scalars, ratios, sources, and offsets.
 */
public class BatchedSssFromScalarsTest extends TestSuiteBase implements TemporalFeatures {

	/** Number of notes in each batch. */
	private static final int N = 4;

	/** Number of layers in the SSS chain. */
	private static final int LAYERS = 3;

	/** Number of samples in each source audio buffer. */
	private static final int SOURCE_LENGTH = 2048;

	/** Number of samples in the target output buffer. */
	private static final int TARGET_LENGTH = 1024;

	/** Number of output samples written by the placement window. */
	private static final int WINDOW_WIDTH = 1536;

	/** Audio sample rate used for envelope curve construction. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Filter order used when constructing the multi-order filter processor. */
	private static final int FILTER_ORDER = 40;

	/**
	 * Creates a {@link PackedCollection} populated with the given double values.
	 */
	private PackedCollection col(double[] values) {
		PackedCollection c = new PackedCollection(values.length);
		a(cp(c), c(values)).get().run();
		return c;
	}

	/**
	 * Verifies that the fused scalar-driven path produces output identical
	 * to the pre-materialized envelope-curve path within a tight RMS threshold.
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
			double[] batchData = new double[N * SOURCE_LENGTH];
			double[] ratioData = new double[N];
			double[] md = new double[N];
			double[] f0 = new double[N];
			double[] f1 = new double[N];
			double[] f2 = new double[N];
			double[] v0 = new double[N];
			double[] v1 = new double[N];
			double[] v2 = new double[N];
			double[] v3 = new double[N];
			for (int nn = 0; nn < N; nn++) {
				for (int i = 0; i < SOURCE_LENGTH; i++) {
					batchData[nn * SOURCE_LENGTH + i] = rng.nextDouble() * 2.0 - 1.0;
				}
				ratioData[nn] = 1.0 + 0.1 * l + 0.05 * nn;
				md[nn] = 0.012 + 0.002 * nn;
				f0[nn] = 0.3;
				f1[nn] = 0.6;
				f2[nn] = 1.0;
				v0[nn] = 0.0;
				v1[nn] = 0.85 + 0.02 * l;
				v2[nn] = 0.5 + 0.03 * nn;
				v3[nn] = 0.0;
			}
			sources[l] = new PackedCollection(shape(N, SOURCE_LENGTH));
			a(cp(sources[l]), c(sources[l].getShape(), batchData)).get().run();
			ratios[l] = col(ratioData);
			layerEnvParams[l] = new PackedCollection[] {
					col(md), col(f0), col(f1), col(f2), col(v0), col(v1), col(v2), col(v3)
			};
			layerCurves[l] = renderer.buildLayerEnvelopeCurve(
					layerEnvParams[l][0], layerEnvParams[l][1], layerEnvParams[l][2], layerEnvParams[l][3],
					layerEnvParams[l][4], layerEnvParams[l][5], layerEnvParams[l][6], layerEnvParams[l][7])
					.get().evaluate();
		}

		double[] fAtt = new double[N];
		double[] fDec = new double[N];
		double[] fSus = new double[N];
		double[] fRel = new double[N];
		double[] fDur = new double[N];
		double[] vAtt = new double[N];
		double[] vDec = new double[N];
		double[] vSus = new double[N];
		double[] vRel = new double[N];
		double[] vDur = new double[N];
		for (int nn = 0; nn < N; nn++) {
			fDur[nn] = 0.016 + 0.002 * nn;
			fAtt[nn] = 0.002 + 0.0003 * nn;
			fDec[nn] = 0.0015 + 0.0002 * nn;
			fSus[nn] = 0.5 + 0.05 * nn;
			fRel[nn] = 0.004 + 0.0005 * nn;
			vDur[nn] = 0.018 + 0.002 * nn;
			vAtt[nn] = 0.0015 + 0.0003 * nn;
			vDec[nn] = 0.0010 + 0.0002 * nn;
			vSus[nn] = 0.45 + 0.05 * nn;
			vRel[nn] = 0.003 + 0.0005 * nn;
		}
		PackedCollection[] filterAdsr = { col(fAtt), col(fDec), col(fSus), col(fRel), col(fDur) };
		PackedCollection[] volumeAdsr = { col(vAtt), col(vDec), col(vSus), col(vRel), col(vDur) };

		PackedCollection filterCutoffs = renderer.buildVolumeEnvelopeCurve(
				filterAdsr[0], filterAdsr[1], filterAdsr[2], filterAdsr[3], filterAdsr[4])
				.multiply(c(MultiOrderFilterEnvelopeProcessor.filterPeak))
				.get().evaluate();
		PackedCollection volumeEnvelopes = renderer.buildVolumeEnvelopeCurve(
				volumeAdsr[0], volumeAdsr[1], volumeAdsr[2], volumeAdsr[3], volumeAdsr[4])
				.get().evaluate();

		double[] destOffsetValues = { 0, 200, 512, 700 };
		PackedCollection destOffsets = col(destOffsetValues);

		// Materialized-curve path (already verified against the per-note reference).
		PackedCollection materialized = renderer.buildBatchedSssChainPlaced(
				sources, ratios, layerCurves, filterCutoffs, volumeEnvelopes, destOffsets, WINDOW_WIDTH)
				.get().evaluate();

		// Fully fused path: curves generated inside the kernel from the same scalars.
		PackedCollection fused = renderer.buildBatchedSssChainPlacedFromScalars(
				sources, ratios, layerEnvParams, filterAdsr, volumeAdsr, destOffsets, WINDOW_WIDTH)
				.get().evaluate();

		double sumSqDiff = 0.0;
		for (int i = 0; i < WINDOW_WIDTH; i++) {
			double diff = materialized.toDouble(i) - fused.toDouble(i);
			sumSqDiff += diff * diff;
		}
		double rms = Math.sqrt(sumSqDiff / WINDOW_WIDTH);
		log(String.format("Fused-from-scalars vs materialized RMS: %.2e", rms));

		Assert.assertTrue(
				"Fused-from-scalars output differs from materialized-curve output (RMS " + rms + ")",
				rms < 1e-6);
	}
}
