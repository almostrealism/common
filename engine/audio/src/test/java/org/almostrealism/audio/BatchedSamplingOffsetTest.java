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

import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the per-note sampling-offset overloads of
 * {@link BatchedPatternRenderer#buildVolumeEnvelopeCurve} and
 * {@link BatchedPatternRenderer#buildLayerEnvelopeCurve}: rendering a note over a
 * single {@code 2W}-frame window must be reproduced exactly by two {@code W}-frame
 * renders at sampling offsets {@code 0} and {@code W} (the property that lets a
 * note's render be split seamlessly across consecutive a2 windows — the basis for
 * rendering notes longer than one window and notes that continue across ticks).
 *
 * <p>The offset is applied through the shared {@code effectiveSampleIdx} helper,
 * which the kernel's resample uses identically, so these envelope assertions also
 * cover the source-read offset.</p>
 */
public class BatchedSamplingOffsetTest extends TestSuiteBase implements TemporalFeatures {

	/** Window width in frames; the full render is {@code 2 * WINDOW}. */
	private static final int WINDOW = 512;

	/** Audio sample rate. */
	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	private PackedCollection single(double value) {
		PackedCollection c = new PackedCollection(1);
		c.setMem(new double[] { value });
		return c;
	}

	/**
	 * The volume envelope rendered over {@code [0, 2W)} equals the concatenation of
	 * two {@code W}-frame renders at sampling offsets {@code 0} and {@code W}.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void volumeEnvelopeSplitsAcrossWindows() {
		// Phases chosen so attack, decay, sustain, release and the post-release zero
		// all occur within [0, 2W) and straddle the W boundary.
		PackedCollection attack = single(0.002);
		PackedCollection decay = single(0.001);
		PackedCollection sustain = single(0.5);
		PackedCollection release = single(0.004);
		PackedCollection duration = single(0.015);

		BatchedPatternRenderer full = new BatchedPatternRenderer(1, 4096, 2 * WINDOW, SAMPLE_RATE, 2);
		BatchedPatternRenderer win = new BatchedPatternRenderer(1, 4096, WINDOW, SAMPLE_RATE, 2);

		PackedCollection reference = full.buildVolumeEnvelopeCurve(
				attack, decay, sustain, release, duration).get().evaluate();
		PackedCollection window0 = win.buildVolumeEnvelopeCurve(
				attack, decay, sustain, release, duration, single(0)).get().evaluate();
		PackedCollection window1 = win.buildVolumeEnvelopeCurve(
				attack, decay, sustain, release, duration, single(WINDOW)).get().evaluate();

		assertSplitMatches("volume envelope", reference, window0, window1);
	}

	/**
	 * The per-layer envelope rendered over {@code [0, 2W)} equals the concatenation
	 * of two {@code W}-frame renders at sampling offsets {@code 0} and {@code W}.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void layerEnvelopeSplitsAcrossWindows() {
		// Segment ends d0=0.3*md, d1=0.6*md, d2=md straddle the W boundary.
		PackedCollection md = single(0.020);
		PackedCollection f0 = single(0.3);
		PackedCollection f1 = single(0.6);
		PackedCollection f2 = single(1.0);
		PackedCollection v0 = single(0.0);
		PackedCollection v1 = single(0.9);
		PackedCollection v2 = single(0.5);
		PackedCollection v3 = single(0.0);

		BatchedPatternRenderer full = new BatchedPatternRenderer(1, 4096, 2 * WINDOW, SAMPLE_RATE, 2);
		BatchedPatternRenderer win = new BatchedPatternRenderer(1, 4096, WINDOW, SAMPLE_RATE, 2);

		PackedCollection reference = full.buildLayerEnvelopeCurve(
				md, f0, f1, f2, v0, v1, v2, v3).get().evaluate();
		PackedCollection window0 = win.buildLayerEnvelopeCurve(
				md, f0, f1, f2, v0, v1, v2, v3, single(0)).get().evaluate();
		PackedCollection window1 = win.buildLayerEnvelopeCurve(
				md, f0, f1, f2, v0, v1, v2, v3, single(WINDOW)).get().evaluate();

		assertSplitMatches("layer envelope", reference, window0, window1);
	}

	/**
	 * Asserts {@code reference[0..W) == window0} and {@code reference[W..2W) == window1}
	 * to within {@code 1e-9}, and that the reference is non-trivial (so the test is
	 * not vacuously comparing zeros).
	 */
	private void assertSplitMatches(String label, PackedCollection reference,
									PackedCollection window0, PackedCollection window1) {
		double energy = 0.0;
		double worst = 0.0;
		for (int i = 0; i < WINDOW; i++) {
			double r0 = reference.toDouble(i);
			double r1 = reference.toDouble(WINDOW + i);
			energy += r0 * r0 + r1 * r1;
			worst = Math.max(worst, Math.abs(r0 - window0.toDouble(i)));
			worst = Math.max(worst, Math.abs(r1 - window1.toDouble(i)));
		}
		double refRms = Math.sqrt(energy / (2 * WINDOW));
		log(label + " split: refRms=" + refRms + " worstAbsDiff=" + worst);

		Assert.assertTrue(label + " reference is trivially zero (refRms=" + refRms + ")",
				refRms > 1e-3);
		Assert.assertEquals(label + " offset-W window diverges from the single render",
				0.0, worst, 1e-9);
	}
}
