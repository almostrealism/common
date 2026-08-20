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

/**
 * Receipts for the fractional-delay trajectory form of
 * {@link MultiChannelDspFeatures#feedbackNetworkBlock} — the mechanism behind the
 * bus-line network's cursor-rate pitch bend.
 *
 * <p>Each test drives a single delay line with no recirculation (zero feedback
 * matrix, identity passthrough) using the same globally increasing ramp the other
 * ring receipts use (sample value = absolute sample index + 1). Because the input
 * is linear in time, the linearly interpolated read of a fractional position equals
 * the ramp evaluated at that exact fractional position, so the expected output of
 * any delay trajectory is available in closed form: {@code out[t] = x(t - D(t)) =
 * t - D(t) + 1} once the ring holds history back to {@code t - D(t)}.</p>
 *
 * <p>The trajectory semantics under test: the delay at frame sample {@code i} ramps
 * linearly from the previous frame's value (exclusive) to the current value
 * (inclusive at the final sample), so a flat segment ({@code prev == current}) must
 * match the whole-sample read exactly, a moving segment must read at the resample
 * ratio {@code 1 - dD/dt}, and the seam between a flat frame and a moving frame
 * must be continuous (no positional splice).</p>
 */
public class DelayRateModulationTest extends TestSuiteBase
		implements MultiChannelDspFeatures {

	/** Comparison tolerance for exact integer-valued samples. */
	private static final double EPS = 1e-9;

	/** Comparison tolerance for interpolated fractional-position samples. */
	private static final double LERP_EPS = 1e-4;

	/** Current per-line delay slot, written between passes via {@code fill}. */
	private PackedCollection delay;

	/** Previous-frame per-line delay slot, written between passes via {@code fill}. */
	private PackedCollection delayPrev;

	/**
	 * Builds a compiled single-line network over a fresh zero ring with no
	 * recirculation, reading through the fractional trajectory form.
	 *
	 * @param signalSize samples per pass
	 * @param bufSize    ring size in samples (whole multiple of signalSize)
	 * @param initial    delay value seeded into both the current and previous slots
	 * @return the compiled model
	 */
	private CompiledModel build(int signalSize, int bufSize, double initial) {
		delay = new PackedCollection(1).fill(initial);
		delayPrev = new PackedCollection(1).fill(initial);
		PackedCollection feedback = new PackedCollection(1);
		PackedCollection buffer = new PackedCollection(bufSize);
		PackedCollection heads = new PackedCollection(1);

		Block block = feedbackNetworkBlock(cp(delay), cp(delayPrev), cp(feedback),
				null, cp(buffer), cp(heads), 1, signalSize);
		Model m = new Model(new TraversalPolicy(1, signalSize));
		m.add(block);
		return m.compile();
	}

	/**
	 * Runs one forward pass of the globally increasing ramp.
	 *
	 * @param model      the compiled network
	 * @param signalSize samples per pass
	 * @param firstIndex absolute index of the pass's first sample
	 * @return the pass output
	 */
	private PackedCollection forward(CompiledModel model, int signalSize, int firstIndex) {
		// The ramp continues the global sample count: the pass's first sample is
		// firstIndex + 1, and each sample after it increases by one.
		PackedCollection input = integers(0, signalSize).add(c(firstIndex + 1.0))
				.evaluate().reshape(1, signalSize);
		return model.forward(input);
	}

	/**
	 * The fractional read must compose with a passthrough routing matrix exactly like
	 * the whole-sample read does — this pins the shape contract between the
	 * interpolated gather composite and {@code matmul}, which the production bus-line
	 * network exercises on every pass ({@code bus_wet_out} is its passthrough).
	 */
	@Test(timeout = 120000)
	public void flatTrajectoryComposesWithPassthroughMatrix() {
		int channels = 2;
		int signalSize = 8;
		int bufSize = 64;
		double d = 20.0;

		delay = new PackedCollection(channels).fill(d);
		delayPrev = new PackedCollection(channels).fill(d);
		PackedCollection feedback =
				new PackedCollection(new TraversalPolicy(channels, channels));
		PackedCollection passthrough =
				new PackedCollection(new TraversalPolicy(channels, channels))
						.fill(1.0, 0.0, 0.0, 1.0);
		PackedCollection buffer = new PackedCollection(channels * bufSize);
		PackedCollection heads = new PackedCollection(channels);

		Block block = feedbackNetworkBlock(cp(delay), cp(delayPrev), cp(feedback),
				cp(passthrough), cp(buffer), cp(heads), channels, signalSize);
		Model m = new Model(new TraversalPolicy(channels, signalSize));
		m.add(block);
		CompiledModel model = m.compile();

		for (int pass = 0; pass < 6; pass++) {
			int first = pass * signalSize;
			// Each channel carries the global sample count offset into its own
			// thousands band, so a cross-channel read is visible in the output.
			PackedCollection input =
					repeat(1, signalSize, integers(0, channels).reshape(channels, 1))
							.multiply(c(1000.0))
							.add(repeat(0, channels, integers(0, signalSize).reshape(1, signalSize)))
							.add(c(1000.0 + first + 1))
							.evaluate().reshape(channels, signalSize);
			PackedCollection out = model.forward(input);

			Assert.assertEquals("identity-routed flat trajectory must read t - "
							+ (int) d + " on every channel", 0.0,
					largestDeviation(shape(channels, signalSize), pos -> {
						int t = first + pos[1];
						return t - d >= 0 ? (pos[0] + 1) * 1000 + t - d + 1 : 0.0;
					}, out), EPS);
		}
	}

	/**
	 * A flat trajectory ({@code prev == current}) must be sample-exact against the
	 * whole-sample definition {@code out[t] = in[t - D]} — the fractional machinery
	 * degenerates to the integer read when the segment does not move.
	 */
	@Test(timeout = 120000)
	public void flatTrajectoryMatchesWholeSampleRead() {
		int signalSize = 8;
		int bufSize = 64;
		double d = 20.0;
		CompiledModel model = build(signalSize, bufSize, d);

		for (int pass = 0; pass < 6; pass++) {
			int first = pass * signalSize;
			PackedCollection out = forward(model, signalSize, first);

			Assert.assertEquals("flat trajectory must read t - " + (int) d
							+ " exactly at every sample", 0.0,
					largestDeviation(shape(signalSize), pos -> {
						int t = first + pos[0];
						return t - d >= 0 ? t - d + 1 : 0.0;
					}, out), EPS);
		}
	}

	/**
	 * A moving trajectory must read the ramp at the interpolated fractional
	 * positions of the per-sample delay segment, advancing at the resample ratio
	 * {@code 1 - dD/dt}, and must join the preceding flat frame continuously.
	 *
	 * <p>With {@code prev = 20}, {@code current = 24}, and eight samples per frame,
	 * the delay at sample {@code i} is {@code 20.5 + 0.5 i}, so the read position
	 * {@code t - D} advances by half a sample per output sample — a pitch-down
	 * octave for the duration of the segment — and every read lands halfway between
	 * two ring samples.</p>
	 */
	@Test(timeout = 120000)
	public void rampedDelayFollowsFractionalTrajectory() {
		int signalSize = 8;
		int bufSize = 64;
		double prev = 20.0;
		double current = 24.0;
		CompiledModel model = build(signalSize, bufSize, prev);

		double lastFlat = 0.0;
		int warmPasses = 6;
		for (int pass = 0; pass < warmPasses; pass++) {
			lastFlat = forward(model, signalSize, pass * signalSize).toDouble(signalSize - 1);
		}

		delayPrev.fill(prev);
		delay.fill(current);
		int first = warmPasses * signalSize;
		PackedCollection out = forward(model, signalSize, first);

		Assert.assertEquals("ramped trajectory must read the interpolated"
						+ " fractional position at every sample", 0.0,
				largestDeviation(shape(signalSize), pos -> {
					double d = prev * (signalSize - 1 - pos[0]) / signalSize
							+ current * (pos[0] + 1) / (double) signalSize;
					return first + pos[0] - d + 1;
				}, out), LERP_EPS);

		double ratio = 1.0 - (current - prev) / signalSize;
		Assert.assertEquals("the moving segment must join the preceding flat frame"
						+ " at the resample ratio, not with a positional splice",
				lastFlat + ratio, out.toDouble(0), LERP_EPS);

		// Within the segment the read advances by a constant step, so the whole
		// progression is settled by the largest departure from that step.
		int steps = signalSize - 1;
		PackedCollection advance = cp(out.range(shape(steps), 1))
				.subtract(cp(out.range(shape(steps), 0))).evaluate();

		Assert.assertEquals("the read must advance at the resample ratio"
						+ " within the segment", 0.0,
				largestDeviation(ratio, advance), LERP_EPS);
	}
}
