/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.audio.test.support.TestAudioData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link DelayNetwork} feedback delay matrix functionality.
 * Uses synthetic test data instead of external audio files.
 */
public class FeedbackDelayMatrixTest implements CellFeatures, TestFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/**
	 * Tests that DelayNetwork can be created with default parameters.
	 */
	@Test
	public void delayNetworkCreation() {
		DelayNetwork verb = new DelayNetwork(SAMPLE_RATE, false);
		Assert.assertNotNull("DelayNetwork should be created", verb);
	}

	/**
	 * Tests that DelayNetwork can be created with custom parameters.
	 */
	@Test
	public void delayNetworkCustomParameters() {
		DelayNetwork verb = new DelayNetwork(0.001, 512, 1.5, SAMPLE_RATE, true);
		Assert.assertNotNull("DelayNetwork with custom params should be created", verb);
	}

	/**
	 * Tests that DelayNetwork can process a signal without errors.
	 */
	@Test
	public void signalProcessing() {
		DelayNetwork verb = new DelayNetwork(0.5, 2, 1.0 / 44100.0, SAMPLE_RATE, false);

		// Apply a short tone
		PackedCollection tone = TestAudioData.sineWave(440.0, 0.1, SAMPLE_RATE);
		PackedCollection output = applyDelayNetwork(verb, tone);

		// Should produce output of same length
		Assert.assertNotNull("Output should not be null", output);
		Assert.assertEquals("Output length should match input",
				tone.getMemLength(), output.getMemLength());
	}

	/**
	 * Tests that default delay network can be constructed and used.
	 */
	@Test
	public void defaultDelayNetworkProcessing() {
		DelayNetwork verb = new DelayNetwork(SAMPLE_RATE, false);

		// Use a sine wave signal
		PackedCollection signal = TestAudioData.sineWave(440.0, 0.1, SAMPLE_RATE);
		PackedCollection output = applyDelayNetwork(verb, signal);

		// Output should be correct length
		Assert.assertNotNull("Output should not be null", output);
		Assert.assertEquals("Output length should match input",
				signal.getMemLength(), output.getMemLength());
	}

	/**
	 * Tests that silence through delay network remains silent.
	 */
	@Test
	public void silenceRemainsSilent() {
		DelayNetwork verb = new DelayNetwork(SAMPLE_RATE, false);
		PackedCollection silence = TestAudioData.silence(SAMPLE_RATE / 10);
		PackedCollection output = applyDelayNetwork(verb, silence);

		Assert.assertTrue("Silence through delay network should remain silent",
				TestAudioData.isSilent(output, 0.001));
	}

	/**
	 * Helper method to apply delay network to input.
	 */
	private PackedCollection applyDelayNetwork(DelayNetwork network, PackedCollection input) {
		PackedCollection output = new PackedCollection(input.getMemLength());
		PackedCollection current = new PackedCollection(1);

		Evaluable<PackedCollection> ev = network.getResultant(p(current)).get();
		Runnable tick = network.tick().get();

		for (int i = 0; i < input.getMemLength(); i++) {
			current.setMem(input.toDouble(i));
			output.setMem(i, ev.evaluate().toDouble(0));
			tick.run();
		}

		return output;
	}
}
