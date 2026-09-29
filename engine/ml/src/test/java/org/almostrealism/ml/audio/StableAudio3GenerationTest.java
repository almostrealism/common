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
import org.almostrealism.ml.tokenization.SentencePieceBPETokenizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Generation with the released Stable Audio 3 weights, end to end: the conditioner, the diffusion
 * transformer and the autoencoder's decoder, at the dimensions the released model was trained at.
 *
 * <p>This asks only whether the assembled pipeline produces sound — a clip of the requested length,
 * every sample finite and within the clamp, and carrying signal rather than silence. It is not a
 * numerical-parity test: a few ping-pong steps ({@code STEPS}) from a seeded latent do not reproduce another
 * implementation's samples, and the per-block parity tests are where agreement with the reference is
 * established. What this covers is the part those cannot: that the blocks compose, that the real
 * weight layout loads into them, and that the whole graph compiles and runs at released dimensions.</p>
 *
 * <p>One test drives the model with token ids, which carry no meaning and exercise no tokenizer, and
 * one drives it with prompt text through {@link SentencePieceBPETokenizer}. Neither asks what the
 * model made of the prompt; that it makes bounded audio of the right length is the subject.</p>
 *
 * <p><b>Requires {@code AR_HARDWARE_MEMORY_SCALE=7}.</b> At the default device-memory ceiling
 * (~4GB) building the transformer's cross-attention throws
 * {@code HardwareException: Memory Max Reached}. The property is read before the environment
 * variable, so the test runner can supply it through its JVM arguments.</p>
 *
 * <p>Gated on all four released weight sets, each a directory of protobuf shards as written by
 * {@code extract_sa3_weights.py} ({@code --target dit|ae|conditioner}) and
 * {@code extract_t5gemma_weights.py}, and — for the text prompt — on the tokenizer exported by
 * {@code export_tokenizer.py}; system property first, then environment variable. The tests log a
 * skip and return when any is absent.</p>
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

	/** Candidate locations for the exported prompt tokenizer. */
	private static final String[] TOKENIZER_PATHS = {
			System.getProperty("AR_T5GEMMA_TOKENIZER", System.getenv("AR_T5GEMMA_TOKENIZER")),
			"/workspace/t5gemma-tokenizer.bin"
	};

	/** Duration to generate, in seconds. Short, since the cost is per latent frame. */
	private static final double SECONDS = 2.0;

	/** Sampling steps. Fewer than the released default, to keep a first run affordable. */
	private static final int STEPS = 4;

	/** The prompt used for the text-driven generation. */
	private static final String PROMPT = "a warm analog synth pad with slow filter sweeps";

	/**
	 * The released model generates a clip of the requested length, bounded and not silent, from
	 * token ids.
	 *
	 * @throws IOException if a weight directory cannot be read
	 */
	@Test(timeout = 2400000)
	public void generatesAudioFromTheReleasedWeights() throws IOException {
		withModel(model ->
				assertGeneratedAudio(model.generate(7, new long[]{5, 7, 9, 11}, SECONDS).evaluate()));
	}

	/**
	 * The released model generates a clip from prompt text, tokenized as the reference implementation
	 * tokenizes it. This is the path a caller actually uses: the tokenizer, the conditioner, the
	 * transformer and the decoder, driven by a sentence.
	 *
	 * @throws IOException if a weight directory or the exported tokenizer cannot be read
	 */
	@Test(timeout = 2400000)
	public void generatesAudioFromATextPrompt() throws IOException {
		File exported = firstFile(TOKENIZER_PATHS);

		if (exported == null) {
			log("skipping text-prompt generation; exported tokenizer absent");
			return;
		}

		SentencePieceBPETokenizer tokenizer = new SentencePieceBPETokenizer(exported.getPath());

		withModel(model -> {
			model.setTokenizer(tokenizer);
			log("generating from " + PROMPT);
			assertGeneratedAudio(model.generate(7, PROMPT, SECONDS).evaluate());
		});
	}

	/**
	 * The first existing candidate path, or {@code null} when none is present.
	 *
	 * @param candidates the paths to try, in order; a {@code null} entry is skipped
	 * @return the first existing file, or {@code null}
	 */
	protected File firstFile(String[] candidates) {
		for (String path : candidates) {
			if (path == null) continue;

			File file = new File(path);
			if (file.isFile()) return file;
		}

		return null;
	}

	/**
	 * Builds the released model from whichever weight directories are present and runs {@code body}
	 * against it, logging a skip and returning when any weight set is absent.
	 *
	 * <p>Every dictionary is released on every path, including a failure while loading a later shard:
	 * re-destroying a dictionary the model already owns is a no-op, so releasing all four frees the
	 * conditioner and autoencoder weights the model does not own.</p>
	 *
	 * @param body what to do with the model
	 * @throws IOException if a weight directory cannot be read
	 */
	protected void withModel(Consumer<StableAudio3> body) throws IOException {
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

		StateDictionary ditWeights = null;
		StateDictionary conditionerWeights = null;
		StateDictionary encoderWeights = null;
		StateDictionary aeWeights = null;
		StableAudio3 model = null;

		try {
			ditWeights = new StateDictionary(dit.getPath());
			conditionerWeights = new StateDictionary(conditioner.getPath());
			encoderWeights = new StateDictionary(encoder.getPath());
			aeWeights = new StateDictionary(ae.getPath());

			model = StableAudio3.small(ditWeights, conditionerWeights, encoderWeights, aeWeights, SECONDS)
					.setSteps(STEPS);

			body.accept(model);
		} finally {
			if (model != null) {
				model.destroy();
			}

			if (ditWeights != null) {
				ditWeights.destroy();
			}
			if (conditionerWeights != null) {
				conditionerWeights.destroy();
			}
			if (encoderWeights != null) {
				encoderWeights.destroy();
			}
			if (aeWeights != null) {
				aeWeights.destroy();
			}
		}
	}

	/**
	 * Asserts that generated audio has the requested length, is finite, stays within the clamp and
	 * is not silent, then releases it.
	 *
	 * @param audio the generated audio
	 */
	protected void assertGeneratedAudio(PackedCollection audio) {
		try {
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
		} finally {
			audio.destroy();
		}
	}
}
