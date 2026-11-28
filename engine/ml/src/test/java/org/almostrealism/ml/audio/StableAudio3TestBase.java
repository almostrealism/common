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

import org.almostrealism.ml.ReferenceActivations;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Locates the released Stable Audio 3 weight sets and the exported prompt tokenizer, and builds
 * the released generator from them, for tests that need the real model.
 *
 * <p>Each weight set is a directory of protobuf shards as written by {@code extract_sa3_weights.py}
 * ({@code --target dit|ae|conditioner}) and {@code extract_t5gemma_weights.py}, and the tokenizer is
 * the file exported by {@code export_tokenizer.py}. Each location is taken from a system property
 * first, then an environment variable, then a default path.</p>
 */
public abstract class StableAudio3TestBase extends TestSuiteBase {

	/** Candidate locations for the transformer weights. */
	protected static final String[] DIT_DIRS = {
			System.getProperty("AR_SA3_DIT_WEIGHTS", System.getenv("AR_SA3_DIT_WEIGHTS")),
			"/workspace/sa3-dit-weights"
	};

	/** Candidate locations for the autoencoder weights. */
	protected static final String[] AE_DIRS = {
			System.getProperty("AR_SA3_AE_WEIGHTS", System.getenv("AR_SA3_AE_WEIGHTS")),
			"/workspace/sa3-ae-weights"
	};

	/** Candidate locations for the conditioner weights. */
	protected static final String[] CONDITIONER_DIRS = {
			System.getProperty("AR_SA3_CONDITIONER_WEIGHTS", System.getenv("AR_SA3_CONDITIONER_WEIGHTS")),
			"/workspace/sa3-conditioner-weights"
	};

	/** Candidate locations for the prompt encoder weights. */
	protected static final String[] ENCODER_DIRS = {
			System.getProperty("AR_T5GEMMA_WEIGHTS", System.getenv("AR_T5GEMMA_WEIGHTS")),
			"/workspace/t5gemma-weights"
	};

	/** Candidate locations for the exported prompt tokenizer. */
	protected static final String[] TOKENIZER_PATHS = {
			System.getProperty("AR_T5GEMMA_TOKENIZER", System.getenv("AR_T5GEMMA_TOKENIZER")),
			"/workspace/t5gemma-tokenizer.bin"
	};

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
	 * Builds the released model, compiled for {@code maxSeconds} of audio, from whichever weight
	 * directories are present and runs {@code body} against it with the model and its autoencoder
	 * weights, logging a skip and returning when any weight set is absent.
	 *
	 * <p>Every dictionary is released on every path, including a failure while loading a later shard:
	 * re-destroying a dictionary the model already owns is a no-op, so releasing all four frees the
	 * conditioner and autoencoder weights the model does not own.</p>
	 *
	 * @param maxSeconds the longest duration the model generates, in seconds
	 * @param body       what to do with the model and the autoencoder weights it was built from
	 * @throws IOException if a weight directory cannot be read
	 */
	protected void withModel(double maxSeconds, BiConsumer<StableAudio3, StateDictionary> body) throws IOException {
		File dit = ReferenceActivations.firstExisting(DIT_DIRS, "model.model.preprocess_conv.weight");
		File ae = ReferenceActivations.firstExisting(AE_DIRS, "bottleneck.scaling_factor");
		File conditioner = ReferenceActivations.firstExisting(CONDITIONER_DIRS,
				"conditioner.conditioners.prompt.padding_embedding");
		File encoder = ReferenceActivations.firstExisting(ENCODER_DIRS, "encoder.norm.weight");

		requireLocalResource("SA3 DiT weights (tried " + Arrays.toString(DIT_DIRS) + ")",
				"AR_SA3_DIT_WEIGHTS", dit != null);
		requireLocalResource("SA3 autoencoder weights (tried " + Arrays.toString(AE_DIRS) + ")",
				"AR_SA3_AE_WEIGHTS", ae != null);
		requireLocalResource("SA3 conditioner weights (tried " + Arrays.toString(CONDITIONER_DIRS) + ")",
				"AR_SA3_CONDITIONER_WEIGHTS", conditioner != null);
		requireLocalResource("T5Gemma encoder weights (tried " + Arrays.toString(ENCODER_DIRS) + ")",
				"AR_T5GEMMA_WEIGHTS", encoder != null);

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

			model = StableAudio3.small(ditWeights, conditionerWeights, encoderWeights, aeWeights, maxSeconds);
			body.accept(model, aeWeights);
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
}
