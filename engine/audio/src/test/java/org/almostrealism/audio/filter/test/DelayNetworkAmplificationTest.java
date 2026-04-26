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

package org.almostrealism.audio.filter.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link DelayNetwork}'s output stage normalises by the
 * delay-line count {@code size} so the {@code gain} parameter has its
 * intended {@code wet ~= input * gain} semantics independent of the size
 * of the network.
 *
 * <p>Originally the implementation summed all {@code N} delay lines
 * without dividing by {@code N}, which produced an {@code N x input x gain}
 * steady-state output. With the default {@code size=128, gain=0.1} that
 * was a 12.8x amplification, and feeding 6 EFX-filtered channels into the
 * reverb in {@link org.almostrealism.studio.arrange.MixdownManager}'s EFX
 * bus pushed the bus 70x+ above unity. The fix divides the output sum by
 * {@code size}; the tests below pin that down with concrete numbers and
 * verify size-invariance of the gain semantics.</p>
 */
public class DelayNetworkAmplificationTest extends TestSuiteBase implements CellFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** Default {@code size=128, gain=0.1}: DC steady-state output should be ~0.1 (= input * gain). */
	@Test(timeout = 180000)
	public void defaultDcAmplification() {
		DelayNetwork verb = new DelayNetwork(SAMPLE_RATE, false);
		double peak = measureDcSteadyState(verb, 1.0, SAMPLE_RATE * 2);
		log("default (size=128, gain=0.1): DC peak output = " + peak);
		assertEquals("Default DelayNetwork DC output should be ~ input * gain (= 0.1)",
				0.1, peak, 0.05);
	}

	/** Same {@code gain=0.1} at smaller size should produce the same wet level. */
	@Test(timeout = 180000)
	public void gainSemanticsAreSizeInvariant() {
		double sizeBig = 128;
		double sizeSmall = 8;
		double gain = 0.1;
		double peakBig = measureDcSteadyState(
				new DelayNetwork(gain, (int) sizeBig, 1.5, SAMPLE_RATE, false),
				1.0, SAMPLE_RATE * 2);
		double peakSmall = measureDcSteadyState(
				new DelayNetwork(gain, (int) sizeSmall, 1.5, SAMPLE_RATE, false),
				1.0, SAMPLE_RATE * 2);
		log("size=128 peak=" + peakBig + ", size=8 peak=" + peakSmall);
		assertEquals("size=128 DC output should be ~gain", gain, peakBig, 0.05);
		assertEquals("size=8 DC output should be ~gain", gain, peakSmall, 0.05);
	}

	/** Input gain scales the wet linearly. */
	@Test(timeout = 180000)
	public void inputGainScalesLinearly() {
		double gain = 0.5;
		DelayNetwork verb = new DelayNetwork(gain, 128, 1.5, SAMPLE_RATE, false);
		double peak = measureDcSteadyState(verb, 1.0, SAMPLE_RATE * 2);
		log("size=128 gain=0.5: DC peak output = " + peak);
		assertEquals("DC output should track gain", gain, peak, 0.05);
	}

	/** Wet should never exceed input + a small margin given gain<=1. */
	@Test(timeout = 180000)
	public void unityGainOutputIsBounded() {
		DelayNetwork verb = new DelayNetwork(1.0, 128, 1.5, SAMPLE_RATE, false);
		double peak = measureDcSteadyState(verb, 1.0, SAMPLE_RATE * 2);
		log("size=128 gain=1.0: DC peak output = " + peak);
		assertTrue("Unity-gain DelayNetwork should keep DC output near 1.0; peak=" + peak,
				peak < 1.5);
	}

	/**
	 * Pushes a constant DC value into the network for {@code samples} ticks
	 * and returns the largest absolute output value observed in the second
	 * half of the run (steady state, after the buffer has filled).
	 */
	private double measureDcSteadyState(DelayNetwork network, double dcLevel, int samples) {
		PackedCollection input = new PackedCollection(1);
		input.setMem(0, dcLevel);

		Evaluable<PackedCollection> ev = network.getResultant(p(input)).get();
		Runnable tick = network.tick().get();

		double peak = 0.0;
		int windowStart = samples / 2;
		for (int i = 0; i < samples; i++) {
			double v = ev.evaluate().toDouble(0);
			tick.run();
			if (i >= windowStart) peak = Math.max(peak, Math.abs(v));
		}
		return peak;
	}
}
