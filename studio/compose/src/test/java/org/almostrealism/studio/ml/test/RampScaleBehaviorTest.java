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
 * Exactness tests for {@link MultiChannelDspFeatures#rampScaleBlock} — the per-sample
 * parameter ramp behind the {@code ramp_scale} primitive. Sample {@code i} of channel
 * {@code ch} must be scaled by {@code prev[ch] + (curr[ch] - prev[ch]) * (i + 1) / S},
 * ending exactly at {@code curr[ch]} so consecutive frames (with the slots refreshed
 * between them) trace a continuous piecewise-linear envelope.
 */
public class RampScaleBehaviorTest extends TestSuiteBase implements MultiChannelDspFeatures {

	/** Comparison tolerance for single-precision kernel outputs. */
	private static final double EPS = 1e-6;

	/**
	 * Builds a compiled two-channel ramp over caller-owned gain slots.
	 *
	 * @param prev       the previous-gain slot, shape {@code [channels]}
	 * @param curr       the current-gain slot, shape {@code [channels]}
	 * @param channels   channel count
	 * @param signalSize samples per channel per frame
	 * @return the compiled model
	 */
	private CompiledModel build(PackedCollection prev, PackedCollection curr,
								int channels, int signalSize) {
		Block block = rampScaleBlock(cp(prev), cp(curr), channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		return m.compile();
	}

	/** Every sample follows the per-channel linear ramp and ends exactly at {@code curr}. */
	@Test(timeout = 120000)
	public void testRampFollowsSlotsExactly() {
		int channels = 2;
		int signalSize = 8;
		PackedCollection prev = new PackedCollection(channels);
		prev.setMem(new double[]{1.0, 4.0});
		PackedCollection curr = new PackedCollection(channels);
		curr.setMem(new double[]{2.0, 0.0});
		CompiledModel compiled = build(prev, curr, channels, signalSize);

		PackedCollection input = new PackedCollection(
				new TraversalPolicy(channels, signalSize));
		double[] ones = new double[channels * signalSize];
		Arrays.fill(ones, 1.0);
		input.setMem(ones);
		double[] out = compiled.forward(input).toArray(0, channels * signalSize);

		for (int ch = 0; ch < channels; ch++) {
			double p = ch == 0 ? 1.0 : 4.0;
			double c = ch == 0 ? 2.0 : 0.0;
			for (int i = 0; i < signalSize; i++) {
				double expected = p + (c - p) * (i + 1) / (double) signalSize;
				Assert.assertEquals(
						"channel " + ch + " sample " + i + " must follow the linear ramp",
						expected, out[ch * signalSize + i], EPS);
			}
			Assert.assertEquals("the ramp must end exactly at the current gain",
					c, out[ch * signalSize + signalSize - 1], EPS);
		}
	}

	/**
	 * Refreshing the slots between frames (prev takes the old current) keeps the
	 * envelope continuous: the first sample of the next frame steps by exactly one
	 * ramp increment from the previous frame's final value, not by a gain jump.
	 */
	@Test(timeout = 120000)
	public void testCrossFrameContinuity() {
		int signalSize = 8;
		PackedCollection prev = new PackedCollection(1);
		prev.setMem(new double[]{1.0});
		PackedCollection curr = new PackedCollection(1);
		curr.setMem(new double[]{2.0});
		CompiledModel compiled = build(prev, curr, 1, signalSize);

		PackedCollection input = new PackedCollection(new TraversalPolicy(1, signalSize));
		double[] ones = new double[signalSize];
		Arrays.fill(ones, 1.0);
		input.setMem(ones);

		double[] frame1 = compiled.forward(input).toArray(0, signalSize);
		Assert.assertEquals("frame 1 must end at the current gain",
				2.0, frame1[signalSize - 1], EPS);

		// The per-buffer refresh: prev takes the value the last ramp ended on.
		prev.setMem(new double[]{2.0});
		curr.setMem(new double[]{4.0});
		double[] frame2 = compiled.forward(input).toArray(0, signalSize);

		double increment = (4.0 - 2.0) / signalSize;
		Assert.assertEquals(
				"frame 2 must continue from frame 1's end value by one ramp increment"
						+ " (no gain jump at the buffer boundary)",
				frame1[signalSize - 1] + increment, frame2[0], EPS);
		Assert.assertEquals("frame 2 must end at the new current gain",
				4.0, frame2[signalSize - 1], EPS);
	}
}
