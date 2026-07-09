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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.studio.dsl.audio.MultiChannelDspFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Effective-delay assertions for {@link MultiChannelDspFeatures#multiChannelDelayBlock}
 * — the write-first ring behind the {@code delay} primitive (both its bank and its
 * single-channel forms, which share this implementation).
 *
 * <p>Every test feeds a globally increasing ramp (sample value = absolute sample index
 * + 1, so zero means "before the signal started") across several passes and asserts
 * {@code out[t] == in[t - D]} for every sample — the definition of a {@code D}-sample
 * delay. The write-first ring holds samples of age {@code D} for
 * {@code 0 <= D <= ring - signalSize}; requests outside that band are clamped
 * device-side to the nearest supported delay. These tests are the regression guard for
 * the ring-sizing defect in which a one-frame ring turned a positive delay into a
 * non-causal within-frame rotation.</p>
 */
public class DelayBankBehaviorTest extends TestSuiteBase
		implements MultiChannelDspFeatures {

	/** Comparison tolerance for exact integer-valued samples. */
	private static final double EPS = 1e-9;

	/**
	 * Builds a compiled delay bank over a fresh zero ring.
	 *
	 * @param channels   channel count
	 * @param signalSize samples per channel per pass
	 * @param bufSize    ring size per channel in samples (whole multiple of signalSize)
	 * @param delay      requested delay in samples (shared across channels)
	 * @return the compiled model
	 */
	private CompiledModel build(int channels, int signalSize, int bufSize, double delay) {
		PackedCollection delaySamples = new PackedCollection(1);
		delaySamples.setMem(new double[]{delay});
		PackedCollection buffer = new PackedCollection(channels * bufSize);
		buffer.setMem(new double[channels * bufSize]);
		PackedCollection heads = new PackedCollection(channels);
		heads.setMem(new double[channels]);

		Block block = multiChannelDelayBlock(cp(delaySamples), cp(buffer), cp(heads),
				channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		return m.compile();
	}

	/**
	 * Runs {@code passes} ramp passes through the bank and asserts the effective delay
	 * equals {@code expectedDelay} at every (channel, sample).
	 *
	 * @param channels      channel count
	 * @param signalSize    samples per channel per pass
	 * @param bufSize       ring size per channel in samples
	 * @param requested     the delay passed to the block
	 * @param expectedDelay the delay the ring band should deliver (after any clamp)
	 * @param passes        number of forward passes to verify
	 */
	private void assertEffectiveDelay(int channels, int signalSize, int bufSize,
									  double requested, int expectedDelay, int passes) {
		CompiledModel compiled = build(channels, signalSize, bufSize, requested);
		for (int pass = 0; pass < passes; pass++) {
			PackedCollection input = new PackedCollection(
					new TraversalPolicy(channels, signalSize));
			double[] in = new double[channels * signalSize];
			for (int ch = 0; ch < channels; ch++) {
				for (int i = 0; i < signalSize; i++) {
					// Distinct per-channel ramps so cross-channel mix-ups are visible.
					in[ch * signalSize + i] = (ch + 1) * 1000.0 + pass * signalSize + i + 1;
				}
			}
			input.setMem(in);
			double[] out = compiled.forward(input).toArray(0, channels * signalSize);

			for (int ch = 0; ch < channels; ch++) {
				for (int i = 0; i < signalSize; i++) {
					int t = pass * signalSize + i;
					int src = t - expectedDelay;
					double expected = src < 0 ? 0.0
							: (ch + 1) * 1000.0 + src + 1;
					Assert.assertEquals(
							"channel " + ch + " absolute sample " + t + " must carry the"
									+ " input from " + expectedDelay + " samples earlier"
									+ " (requested " + requested + "); pass output="
									+ Arrays.toString(out),
							expected, out[ch * signalSize + i], EPS);
				}
			}
		}
	}

	/** A multi-frame delay (spanning frames, not a frame multiple) is sample-exact. */
	@Test(timeout = 120000)
	public void testMultiFrameDelayExact() {
		assertEffectiveDelay(2, 8, 32, 13.0, 13, 6);
	}

	/** A sub-frame delay is sample-exact once the ring holds two frames. */
	@Test(timeout = 120000)
	public void testSubFrameDelayExact() {
		assertEffectiveDelay(2, 8, 16, 3.0, 3, 5);
	}

	/** A delay equal to the write-first band's top ({@code ring - signalSize}) is exact. */
	@Test(timeout = 120000)
	public void testBandTopDelayExact() {
		assertEffectiveDelay(1, 8, 32, 24.0, 24, 7);
	}

	/** The production defect shape: 6500 samples through a 3-frame ring at 4096. */
	@Test(timeout = 300000)
	public void testProductionDelayShapeExact() {
		assertEffectiveDelay(2, 4096, 3 * 4096, 6500.0, 6500, 4);
	}

	/**
	 * A one-frame ring supports only a zero delay: a positive request clamps to
	 * pass-through instead of the former non-causal within-frame rotation.
	 */
	@Test(timeout = 120000)
	public void testOneFrameRingClampsToPassThrough() {
		assertEffectiveDelay(1, 8, 8, 5.0, 0, 4);
	}

	/** A request beyond the band's top clamps to {@code ring - signalSize}. */
	@Test(timeout = 120000)
	public void testOversizedDelayClampsToBandTop() {
		assertEffectiveDelay(1, 8, 16, 12.0, 8, 5);
	}
}
