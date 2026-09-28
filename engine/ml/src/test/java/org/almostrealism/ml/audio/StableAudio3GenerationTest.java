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

package org.almostrealism.ml.audio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ReferenceActivations;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Generation with the released Stable Audio 3 weights, end to end: the conditioner, the diffusion
 * transformer and the autoencoder's decoder, at the dimensions the released model was trained at.
 *
 * <p>This asks only whether the assembled pipeline produces sound — a clip of the requested length,
 * every sample finite and within the clamp, and carrying signal rather than silence. It is not a
 * numerical-parity test: eight ping-pong steps from a seeded latent do not reproduce another
 * implementation's samples, and the per-block parity tests are where agreement with the reference is
 * established. What this covers is the part those cannot: that the blocks compose, that the real
 * weight layout loads into them, and that the whole graph compiles and runs at released dimensions.</p>
 *
 * <p>The prompt is supplied as token ids rather than text, so this does not exercise a tokenizer and
 * the ids here carry no meaning. What the model makes of them is not the subject; that it makes
 * bounded audio of the right length from them is.</p>
 *
 * <p>Gated on all four released weight sets, each a directory of protobuf shards as written by
 * {@code extract_sa3_weights.py} ({@code --target dit|ae|conditioner}) and
 * {@code extract_t5gemma_weights.py}; system property first, then environment variable. The test
 * logs a skip and returns when any is absent.</p>
 */
public class StableAudio3GenerationTest extends TestSuiteBase {

	/** Candidate locations for the transformer weights. */
	private static final String[] DIT_DIRS = {
			System.getProperty("AR_SA3_DIT_WEIGHTS", System.getenv("AR_SA3_DIT_WEIGHTS")),
			"/workspace/sa3-dit-weights"
	};

	/** Candidate locations for the autoencoder weights. */
	private static final String[] AE_DIRS = {
			System.getProperty("AR_SA3_AE_WEIGHTS", System.getenv("AR_SA3_AE_WEIGHTS")),
			"/workspace/sa3-ae-weights"
	};

	/** Candidate locations for the conditioner weights. */
	private static final String[] CONDITIONER_DIRS = {
			System.getProperty("AR_SA3_CONDITIONER_WEIGHTS", System.getenv("AR_SA3_CONDITIONER_WEIGHTS")),
			"/workspace/sa3-conditioner-weights"
	};

	/** Candidate locations for the prompt encoder weights. */
	private static final String[] ENCODER_DIRS = {
			System.getProperty("AR_T5GEMMA_WEIGHTS", System.getenv("AR_T5GEMMA_WEIGHTS")),
			"/workspace/t5gemma-weights"
	};

	/** Duration to generate, in seconds. Short, since the cost is per latent frame. */
	private static final double SECONDS = 2.0;

	/** Sampling steps. Fewer than the released default, to keep a first run affordable. */
	private static final int STEPS = 4;

	/**
	 * The released model generates a clip of the requested length, bounded and not silent.
	 *
	 * @throws IOException if a weight directory cannot be read
	 */
	@Test(timeout = 5400000)
	public void generatesAudioFromTheReleasedWeights() throws IOException {
		File dit = ReferenceActivations.firstExisting(DIT_DIRS, "model.model.preprocess_conv.weight");
		File ae = ReferenceActivations.firstExisting(AE_DIRS, "bottleneck.scaling_factor");
		File conditioner = ReferenceActivations.firstExisting(CONDITIONER_DIRS,
				"conditioner.conditioners.prompt.padding_embedding");
		File encoder = ReferenceActivations.firstExisting(ENCODER_DIRS, "encoder.norm.weight");

		if (dit == null || ae == null || conditioner == null || encoder == null) {
			log("skipping released-weight generation; weights absent (dit=" + dit + ", ae=" + ae
					+ ", conditioner=" + conditioner + ", encoder=" + encoder + ")");
			return;
		}

		StableAudio3 model = StableAudio3.small(
				new StateDictionary(dit.getPath()),
				new StateDictionary(conditioner.getPath()),
				new StateDictionary(encoder.getPath()),
				new StateDictionary(ae.getPath()),
				SECONDS).setSteps(STEPS);

		try {
			PackedCollection audio = model.generate(7, new long[]{5, 7, 9, 11}, SECONDS).evaluate();

			int samples = (int) (SECONDS * StableAudio3.SAMPLE_RATE);
			assertEquals(2, audio.getShape().getDimensions());
			assertEquals(samples, audio.getShape().length(1));

			double peak = 0;
			double energy = 0;
			int count = audio.getShape().getTotalSize();

			for (int i = 0; i < count; i++) {
				double value = audio.toDouble(i);
				assertTrue("sample " + i + " is " + value, Double.isFinite(value));
				assertTrue("sample " + i + " outside the clamp: " + value, Math.abs(value) <= 1.0);
				peak = Math.max(peak, Math.abs(value));
				energy += value * value;
			}

			double rms = Math.sqrt(energy / count);
			log(String.format("generated %d channels x %d samples, peak=%.6f rms=%.6f",
					audio.getShape().length(0), audio.getShape().length(1), peak, rms));

			assertTrue("the decoder produced silence (peak " + peak + ")", peak > 1e-4);
			audio.destroy();
		} finally {
			model.destroy();
		}
	}
}
