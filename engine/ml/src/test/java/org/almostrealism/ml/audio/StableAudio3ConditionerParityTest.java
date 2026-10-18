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

import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Numerical parity of the released conditioner against the conditioning the reference
 * implementation produced for the same prompt and duration.
 *
 * <p>This closes the last unverified link in the conditioning chain. The transformer parity test
 * reads {@code dit_cross_attn_cond} out of the reference dump rather than computing it, so until
 * now nothing checked that this platform turns a prompt into the same context the transformer was
 * given. The reference tensors are the released conditioner's own outputs: the dump records
 * {@code cond_inputs["cross_attn_cond"]} and {@code cond_inputs["global_cond"]} from
 * {@code stable_audio_3}'s conditioner for {@link #PROMPT} at {@link #SECONDS_TOTAL} seconds.</p>
 *
 * <p>Both halves of the conditioner are covered, and they fail independently. The context carries
 * the encoded prompt with the learned padding embedding substituted at padded positions, so a
 * disagreement there implicates the encoder or the substitution. The global conditioning is the
 * appended duration token alone, which is the only numerical check anywhere on the exponential
 * Fourier duration embedder.</p>
 *
 * <p>The prompt is given as the token ids the reference tokenizer produces for {@link #PROMPT};
 * {@code SentencePieceBPETokenizer} reproduces exactly these, which
 * {@code SentencePieceBPETokenizerTest} asserts separately. They are written out here so this test
 * exercises the conditioner alone and does not also require the exported tokenizer.</p>
 *
 * <p>Gated on the conditioner and prompt-encoder weights and on the transformer reference dump;
 * system property first, then environment variable. The test logs a skip and returns when any is
 * absent.</p>
 */
public class StableAudio3ConditionerParityTest extends SAMEResamplingTestBase {

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

	/** Candidate locations for the reference dump. */
	private static final String[] REFERENCE_DIRS = {
			System.getProperty("AR_SA3_DIT_REFERENCES", System.getenv("AR_SA3_DIT_REFERENCES")),
			"target/test-classes/sa3-dit-references",
			"engine/ml/target/test-classes/sa3-dit-references"
	};

	/** The prompt the reference dump conditioned on. */
	private static final String PROMPT = "a warm analog synth pad with slow filter sweeps";

	/** The token ids the reference tokenizer produces for {@link #PROMPT}. */
	private static final long[] PROMPT_TOKENS = {
			235250, 8056, 16335, 72896, 7174, 675, 6080, 7194, 123832
	};

	/** The duration the reference dump conditioned on, in seconds. */
	private static final double SECONDS_TOTAL = 10.0;

	/** Permitted largest absolute error relative to the largest reference magnitude. */
	private static final double RELATIVE_TOLERANCE = 2e-2;

	/**
	 * The conditioner reproduces the reference cross-attention context and global conditioning for
	 * the reference prompt and duration.
	 *
	 * @throws IOException if a weight directory or reference file cannot be read
	 */
	@Test(timeout = 1800000)
	public void conditioningMatchesTheReference() throws IOException {
		File conditionerDir = firstExisting(CONDITIONER_DIRS,
				"conditioner.conditioners.prompt.padding_embedding");
		File encoderDir = firstExisting(ENCODER_DIRS, "encoder.norm.weight");
		File refDir = firstExisting(REFERENCE_DIRS, "dit_cross_attn_cond");

		if (conditionerDir == null || encoderDir == null || refDir == null) {
			log("skipping conditioner parity; gated inputs absent (conditioner=" + conditionerDir
					+ ", encoder=" + encoderDir + ", refs=" + refDir + ")");
			return;
		}

		StateDictionary conditionerWeights = null;
		StateDictionary encoderWeights = null;
		StableAudio3Conditioner conditioner = null;

		try {
			conditionerWeights = new StateDictionary(conditionerDir.getPath());
			encoderWeights = new StateDictionary(encoderDir.getPath());
			conditioner = StableAudio3.smallConditioner(conditionerWeights, encoderWeights);

			log("conditioning on " + PROMPT + " at " + SECONDS_TOTAL + "s");
			AudioAttentionConditioner.ConditionerOutput output =
					conditioner.runConditioners(PROMPT_TOKENS, SECONDS_TOTAL);

			float[] refContext = loadFlat(refDir, "dit_cross_attn_cond");
			float[] refGlobal = loadFlat(refDir, "dit_global_cond");

			assertWithinRelative("crossAttentionContext", output.getCrossAttentionInput(),
					refContext, RELATIVE_TOLERANCE);
			assertWithinRelative("globalCond", output.getGlobalCond(), refGlobal, RELATIVE_TOLERANCE);
		} finally {
			if (conditioner != null) {
				conditioner.destroy();
			}

			if (conditionerWeights != null) {
				conditionerWeights.destroy();
			}
			if (encoderWeights != null) {
				encoderWeights.destroy();
			}
		}
	}
}
