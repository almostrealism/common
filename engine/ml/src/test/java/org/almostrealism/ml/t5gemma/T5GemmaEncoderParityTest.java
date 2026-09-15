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

package org.almostrealism.ml.t5gemma;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.ReferenceActivations;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Numerical-parity test of {@link T5GemmaEncoder} against the released base encoder. The
 * reference activations come from {@code engine/ml/scripts/dump_t5gemma_reference.py} (a fixed
 * prompt padded to 256 tokens) and the weights from {@code extract_t5gemma_weights.py}; both are
 * gated inputs that are never committed, so the test logs a skip and returns when either is
 * absent.
 */
public class T5GemmaEncoderParityTest extends TestSuiteBase {

	/** Candidate locations for the extracted weight directory (first existing wins). */
	private static final String[] WEIGHT_DIRS = {
			System.getenv("AR_T5GEMMA_WEIGHTS"),
			"/workspace/t5gemma-weights"
	};

	/** Candidate locations for the reference directory (first existing wins). */
	private static final String[] REFERENCE_DIRS = {
			System.getenv("AR_T5GEMMA_REFERENCES"),
			"target/test-classes/t5gemma-references",
			"engine/ml/target/test-classes/t5gemma-references"
	};

	/** Permitted largest absolute error relative to the largest reference magnitude. */
	private static final double RELATIVE_TOLERANCE = 2e-2;

	/**
	 * The encoder reproduces the reference hidden states of the fixed prompt at every position,
	 * prompt tokens and padding alike, within a small fraction of the reference magnitude.
	 *
	 * @throws IOException if a reference file cannot be read
	 */
	@Test(timeout = 900000)
	public void lastHiddenStateMatchesReference() throws IOException {
		File weightDir = ReferenceActivations.firstExisting(WEIGHT_DIRS, "weights");
		ReferenceActivations references = ReferenceActivations.locate(REFERENCE_DIRS, "t5_last_hidden_state.bin");
		if (weightDir == null || references == null) {
			log("skipping T5Gemma parity; gated inputs absent (weights=" + weightDir + ", refs=" + references + ")");
			return;
		}

		T5GemmaConfig config = T5GemmaConfig.baseUl2();
		float[] ids = references.load("t5_input_ids.bin");
		float[] mask = references.load("t5_attention_mask.bin");
		float[] expected = references.load("t5_last_hidden_state.bin");
		assertEquals(config.getMaxLength(), ids.length);
		assertEquals(config.getMaxLength() * config.getHiddenSize(), expected.length);

		int valid = 0;
		while (valid < mask.length && mask[valid] > 0.5) {
			valid++;
		}
		long[] prompt = new long[valid];
		for (int i = 0; i < valid; i++) {
			prompt[i] = Math.round(ids[i]);
		}

		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(weightDir.getPath()));
		PackedCollection hidden = encoder.forward(prompt);

		double maxRef = 0.0;
		double maxErrorPrompt = 0.0;
		double maxErrorPadding = 0.0;
		int width = config.getHiddenSize();
		for (int i = 0; i < expected.length; i++) {
			maxRef = Math.max(maxRef, Math.abs(expected[i]));
			double error = Math.abs(hidden.toDouble(i) - expected[i]);
			if (i / width < valid) {
				maxErrorPrompt = Math.max(maxErrorPrompt, error);
			} else {
				maxErrorPadding = Math.max(maxErrorPadding, error);
			}
		}

		log("t5gemma parity: validTokens=" + valid + " maxRef=" + maxRef
				+ " maxErrorPrompt=" + maxErrorPrompt + " maxErrorPadding=" + maxErrorPadding);
		encoder.destroy();

		double tolerance = RELATIVE_TOLERANCE * maxRef;
		assertTrue("prompt positions differ from the reference by " + maxErrorPrompt, maxErrorPrompt <= tolerance);
		assertTrue("padded positions differ from the reference by " + maxErrorPadding, maxErrorPadding <= tolerance);
	}
}
