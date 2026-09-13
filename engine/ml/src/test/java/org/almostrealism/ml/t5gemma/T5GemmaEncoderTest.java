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
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Map;

/**
 * Structural tests of {@link T5GemmaEncoder} over small synthetic weights: output shape and
 * finiteness, the attention mask's isolation of prompt positions from padding, and weight
 * dictionary validation.
 */
public class T5GemmaEncoderTest extends TestSuiteBase {

	/** Hidden width of the small encoder. */
	private static final int HIDDEN = 16;

	/** Builder of the synthetic weights. */
	private final T5GemmaWeightFixture fixture = new T5GemmaWeightFixture();

	/** The encoder maps a prompt to one hidden vector per compiled position, all finite. */
	@Test(timeout = 240000)
	public void encodesPromptToHiddenStates() {
		T5GemmaConfig config = smallConfig(8);
		T5GemmaEncoder encoder = new T5GemmaEncoder(config, new StateDictionary(syntheticWeights(config, 3)));

		PackedCollection hidden = encoder.forward(new long[]{5, 7, 9});
		assertEquals(config.getMaxLength() * HIDDEN, hidden.getShape().getTotalSize());
		for (int i = 0; i < hidden.getShape().getTotalSize(); i++) {
			assertTrue(Double.isFinite(hidden.toDouble(i)));
		}

		PackedCollection mask = encoder.getAttentionMask();
		assertEquals(1.0, mask.toDouble(2), 0.0);
		assertEquals(0.0, mask.toDouble(3), 0.0);
		encoder.destroy();
	}

	/**
	 * Prompt positions attend only to prompt keys: the same prompt encoded at two padded lengths
	 * yields identical hidden states at the prompt positions, while adding a fourth token changes
	 * them (so the equality is not vacuous).
	 */
	@Test(timeout = 240000)
	public void paddingDoesNotReachPromptPositions() {
		Map<String, PackedCollection> weights = syntheticWeights(smallConfig(8), 3);
		T5GemmaEncoder shortEncoder = new T5GemmaEncoder(smallConfig(8), new StateDictionary(weights));
		T5GemmaEncoder longEncoder = new T5GemmaEncoder(smallConfig(12), new StateDictionary(weights));

		long[] prompt = new long[]{5, 7, 9};
		PackedCollection shortOut = shortEncoder.forward(prompt).reshape(shape(1, 8, HIDDEN)).clone();
		PackedCollection longOut = longEncoder.forward(prompt).reshape(shape(1, 12, HIDDEN)).clone();
		PackedCollection extended = shortEncoder.forward(new long[]{5, 7, 9, 11}).reshape(shape(1, 8, HIDDEN)).clone();

		double promptDifference = 0.0;
		double extensionDifference = 0.0;
		for (int p = 0; p < prompt.length; p++) {
			for (int f = 0; f < HIDDEN; f++) {
				promptDifference = Math.max(promptDifference,
						Math.abs(shortOut.valueAt(0, p, f) - longOut.valueAt(0, p, f)));
				extensionDifference = Math.max(extensionDifference,
						Math.abs(shortOut.valueAt(0, p, f) - extended.valueAt(0, p, f)));
			}
		}

		assertTrue("padding length changed prompt positions by " + promptDifference, promptDifference < 1e-4);
		assertTrue("a fourth token must influence the earlier positions", extensionDifference > 1e-4);
		shortEncoder.destroy();
		longEncoder.destroy();
	}

	/** A dictionary missing a weight, or holding one the architecture does not read, is rejected. */
	@Test(timeout = 120000)
	public void weightDictionaryIsValidated() {
		T5GemmaConfig config = smallConfig(8);

		Map<String, PackedCollection> missing = syntheticWeights(config, 3);
		missing.remove("encoder.layers.1.mlp.down_proj.weight");
		try {
			new T5GemmaEncoder(config, new StateDictionary(missing)).block();
			throw new AssertionError("a missing weight must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("down_proj"));
		}

		Map<String, PackedCollection> extra = syntheticWeights(config, 3);
		extra.put("encoder.layers.0.self_attn.q_proj.weight", new PackedCollection(shape(HIDDEN, HIDDEN)));
		try {
			new T5GemmaEncoder(config, new StateDictionary(extra)).block();
			throw new AssertionError("an unread weight must be rejected");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("q_proj"));
		}
	}

	/**
	 * A two-layer configuration of the small encoder compiled for the given length.
	 *
	 * @param maxLength number of token positions
	 * @return the configuration
	 */
	private T5GemmaConfig smallConfig(int maxLength) {
		return fixture.smallConfig(maxLength);
	}

	/**
	 * Random weights in the layout the encoder reads.
	 *
	 * @param config the architecture
	 * @param seed   seed of the random weights
	 * @return the weights
	 */
	private Map<String, PackedCollection> syntheticWeights(T5GemmaConfig config, long seed) {
		return fixture.weights(config, seed);
	}
}
