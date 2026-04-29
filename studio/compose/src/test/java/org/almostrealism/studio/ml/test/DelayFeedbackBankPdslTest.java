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
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates the {@code delay_feedback_bank} PDSL layer and generates a WAV proof file.
 *
 * <p>The {@code delay_feedback_bank} layer exercises the multi-channel DSP constructs
 * added in Deliverable 2: {@code fan_out(N)}, {@code for each channel { ... }},
 * {@code route(matrix)}, and {@code sum_channels()}. A mono 440 Hz sine wave is
 * fanned out to three parallel delay lines, mixed via a cross-channel routing matrix,
 * then summed back to mono. The WAV output at
 * {@code results/pdsl-audio-dsp/delay_feedback_bank.wav} can be listened to as
 * audible confirmation of the delay-feedback effect.</p>
 *
 * @see org.almostrealism.ml.dsl.PdslLoader
 * @see org.almostrealism.ml.dsl.MultiChannelDspFeatures
 */
public class DelayFeedbackBankPdslTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Number of audio samples per processing buffer. */
	private static final int SIGNAL_SIZE = 256;

	/** Audio sample rate (Hz). */
	private static final int SAMPLE_RATE = 44100;

	/** Number of parallel delay channels. */
	private static final int CHANNELS = 3;

	/** Delay per channel in samples (quarter-buffer offset). */
	private static final int DELAY_SAMPLES = 64;

	/**
	 * Builds and runs the {@code delay_feedback_bank} layer over 1 second of audio
	 * and writes the result to {@code results/pdsl-audio-dsp/delay_feedback_bank.wav}.
	 *
	 * <p>A 440 Hz sine input is fanned out to {@value #CHANNELS} delay channels, each
	 * delayed by {@value #DELAY_SAMPLES} samples. The channels are mixed via a
	 * near-identity routing matrix and then summed to mono. The test verifies:
	 * <ul>
	 *   <li>The model compiles without error.</li>
	 *   <li>The WAV file is created and is non-empty.</li>
	 *   <li>The output differs from the raw input (delay and routing are active).</li>
	 * </ul>
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testDelayFeedbackBankProducesAudio() throws IOException {
		File outputDir = new File("results/pdsl-audio-dsp");
		outputDir.mkdirs();

		int totalSamples = SAMPLE_RATE;
		int numPasses = totalSamples / SIGNAL_SIZE;
		TraversalPolicy inputShape = new TraversalPolicy(1, SIGNAL_SIZE);

		// Per-channel state: each channel gets its own slice of these collections.
		// buffers: channels * signal_size elements (circular delay buffer per channel)
		// heads:   channels elements (write-head counter per channel)
		PackedCollection buffers = new PackedCollection(CHANNELS * SIGNAL_SIZE);
		buffers.setMem(new double[CHANNELS * SIGNAL_SIZE]);
		PackedCollection heads = new PackedCollection(CHANNELS);
		heads.setMem(new double[CHANNELS]);

		// Routing matrix: near-identity with slight cross-channel bleed.
		// Row i is output channel i. Each row sums to 1.0 for unity gain.
		double[][] m = {
				{0.4, 0.3, 0.3},
				{0.3, 0.4, 0.3},
				{0.3, 0.3, 0.4}
		};
		PackedCollection transmission = new PackedCollection(
				new TraversalPolicy(CHANNELS, CHANNELS));
		for (int i = 0; i < CHANNELS; i++) {
			for (int j = 0; j < CHANNELS; j++) {
				transmission.setMem(i * CHANNELS + j, m[i][j]);
			}
		}

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/delay_feedback_bank.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("channels", CHANNELS);
		args.put("signal_size", SIGNAL_SIZE);
		args.put("delay_samples", DELAY_SAMPLES);
		args.put("transmission", transmission);
		args.put("buffers", buffers);
		args.put("heads", heads);

		Block block = loader.buildLayer(program, "delay_feedback_bank", inputShape, args);
		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		float[] drySignal = new float[totalSamples];
		float[] wetSignal = new float[totalSamples];

		for (int pass = 0; pass < numPasses; pass++) {
			final int offset = pass * SIGNAL_SIZE;
			PackedCollection input = createSignal(SIGNAL_SIZE, i -> {
				double t = (double) (offset + i) / SAMPLE_RATE;
				return Math.sin(2.0 * Math.PI * 440.0 * t);
			});
			PackedCollection output = compiled.forward(input);
			double[] inArr = input.toArray(0, SIGNAL_SIZE);
			double[] outArr = output.toArray(0, SIGNAL_SIZE);
			for (int i = 0; i < SIGNAL_SIZE; i++) {
				drySignal[offset + i] = (float) inArr[i];
				wetSignal[offset + i] = (float) outArr[i];
			}
		}

		File wavFile = new File(outputDir, "delay_feedback_bank.wav");
		PdslAudioDemoTest.writeDemoWav(wavFile, wetSignal, SAMPLE_RATE);

		Assert.assertTrue("WAV file must exist and be non-empty", wavFile.length() > 0);

		// The delay-feedback bank output must differ from the raw dry input
		// because the delay shifts the signal and the routing matrix scales it.
		double diffEnergy = 0.0;
		for (int i = 0; i < totalSamples; i++) {
			double diff = wetSignal[i] - drySignal[i];
			diffEnergy += diff * diff;
		}
		Assert.assertTrue(
				"Output must differ from dry input (delay + routing must be active)",
				diffEnergy > 0.0);
	}
}
