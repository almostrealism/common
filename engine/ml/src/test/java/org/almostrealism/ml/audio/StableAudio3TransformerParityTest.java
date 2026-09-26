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
import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Numerical parity of the released small Stable Audio 3 transformer against one reference forward
 * pass captured by {@code engine/ml/scripts/dump_sa3_dit_reference.py}: a seeded latent at
 * timestep one half, conditioned on the fixed prompt and a ten-second duration, with every
 * latent position valid and no inpainting input.
 *
 * <p>Gated on the extracted transformer weights ({@code AR_SA3_DIT_WEIGHTS}, the {@code dit}
 * target of {@code extract_sa3_weights.py}) and the reference directory
 * ({@code AR_SA3_DIT_REFERENCES}); system property first, then environment variable. The test
 * logs a skip and returns when either is absent.</p>
 */
public class StableAudio3TransformerParityTest extends SAMEResamplingTestBase {

	/** Candidate locations for the extracted weight directory (first existing wins). */
	private static final String[] WEIGHT_DIRS = {
			System.getProperty("AR_SA3_DIT_WEIGHTS", System.getenv("AR_SA3_DIT_WEIGHTS")),
			"/workspace/sa3-dit-weights"
	};

	/** Candidate locations for the reference directory (first existing wins). */
	private static final String[] REFERENCE_DIRS = {
			System.getProperty("AR_SA3_DIT_REFERENCES", System.getenv("AR_SA3_DIT_REFERENCES")),
			"target/test-classes/sa3-dit-references",
			"engine/ml/target/test-classes/sa3-dit-references"
	};

	/** Width of the conditioner's context tokens and global conditioning. */
	private static final int COND_DIM = 768;

	/** Context tokens: the padded prompt plus the duration token. */
	private static final int COND_SEQ_LEN = 257;

	/** Latent channels of the small model. */
	private static final int CHANNELS = 256;

	/** Latent length of the reference dump. */
	private static final int LATENT_LEN = 64;

	/** Permitted largest absolute error relative to the largest reference magnitude. */
	private static final double RELATIVE_TOLERANCE = 2e-2;

	/**
	 * The transformer reproduces the reference output, and the state entering and leaving the
	 * block stack, within a small fraction of the reference magnitude.
	 *
	 * @throws IOException if a reference file cannot be read
	 */
	@Test(timeout = 1800000)
	public void forwardMatchesReference() throws IOException {
		// Older extractions named their first shard dit_0 rather than dit.
		File weightDir = firstExisting(WEIGHT_DIRS, "dit");
		if (weightDir == null) {
			weightDir = firstExisting(WEIGHT_DIRS, "dit_0");
		}

		File refDir = firstExisting(REFERENCE_DIRS, "dit_output");
		if (weightDir == null || refDir == null) {
			log("skipping SA3 transformer parity; gated inputs absent (weights=" + weightDir + ", refs=" + refDir + ")");
			return;
		}

		DiffusionTransformerConfig config = StableAudio3.smallTransformer(COND_DIM, COND_SEQ_LEN)
				.withSequenceLengths(LATENT_LEN, COND_SEQ_LEN);
		DiffusionTransformer transformer = new DiffusionTransformer(config, new StateDictionary(weightDir.getPath()));

		PackedCollection x = loadShaped(refDir, "dit_x", 1, CHANNELS, LATENT_LEN);
		PackedCollection t = loadShaped(refDir, "dit_t", 1, 1);
		PackedCollection context = loadShaped(refDir, "dit_cross_attn_cond", 1, COND_SEQ_LEN, COND_DIM);
		PackedCollection global = loadShaped(refDir, "dit_global_cond", 1, COND_DIM);

		PackedCollection output = transformer.forward(x, t, context, global);

		float[] refOutput = loadFlat(refDir, "dit_output");
		float[] refPre = loadFlat(refDir, "dit_pre_transformer");
		float[] refPost = loadFlat(refDir, "dit_post_transformer");

		assertWithinRelative("output", output, refOutput, RELATIVE_TOLERANCE);
		assertWithinRelative("preTransformer", transformer.getPreTransformerState(), refPre, RELATIVE_TOLERANCE);
		assertWithinRelative("postTransformer", transformer.getPostTransformerState(), refPost, RELATIVE_TOLERANCE);
		transformer.destroy();
	}
}
