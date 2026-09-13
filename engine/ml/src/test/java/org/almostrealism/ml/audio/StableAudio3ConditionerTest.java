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
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.t5gemma.T5GemmaConfig;
import org.almostrealism.ml.t5gemma.T5GemmaEncoder;
import org.almostrealism.ml.t5gemma.T5GemmaWeightFixture;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Random;

/**
 * Verifies the assembly of {@link StableAudio3Conditioner}: prompt positions carry the encoder's
 * hidden states, padded positions carry the learned padding embedding, the last context token
 * and the global conditioning are the duration embedding, and every context token is valid.
 */
public class StableAudio3ConditionerTest extends TestSuiteBase {

	/** Token positions the small encoder is compiled for. */
	private static final int LENGTH = 8;

	/** Fourier feature count of the small duration embedder. */
	private static final int FOURIER = 8;

	/**
	 * The conditioner output is assembled from its three parts. The duration embedding is
	 * compared against a separately evaluated copy of the same graph, so the comparison allows
	 * for the single-precision phase error of the Fourier features (phases reach a few hundred
	 * radians at the top of the frequency ladder).
	 */
	@Test(timeout = 240000)
	public void assemblesPromptPaddingAndDuration() {
		T5GemmaWeightFixture fixture = new T5GemmaWeightFixture();
		T5GemmaConfig config = fixture.smallConfig(LENGTH);
		int hidden = config.getHiddenSize();
		Random random = new Random(21);

		PackedCollection padding = fixture.random(random, hidden);
		NumberConditioner duration = NumberConditioner.expo(0.0, 384.0, hidden, FOURIER, 0.5, 10000.0,
				fixture.random(random, hidden, FOURIER), fixture.random(random, hidden));

		long[] prompt = new long[]{5, 7, 9};
		double seconds = 10.0;

		T5GemmaEncoder reference = new T5GemmaEncoder(config, new StateDictionary(fixture.weights(config, 3)));
		PackedCollection encoded = reference.forward(prompt).reshape(shape(1, LENGTH, hidden)).clone();
		PackedCollection durationEmbedding = duration.embed(seconds).evaluate();

		StableAudio3Conditioner conditioner = new StableAudio3Conditioner(
				new T5GemmaEncoder(config, new StateDictionary(fixture.weights(config, 3))), padding, duration);
		AudioAttentionConditioner.ConditionerOutput output = conditioner.runConditioners(prompt, seconds);

		PackedCollection context = output.getCrossAttentionInput().reshape(shape(1, LENGTH + 1, hidden));
		PackedCollection mask = output.getCrossAttentionMask();
		PackedCollection global = output.getGlobalCond();
		assertEquals(LENGTH + 1, mask.getShape().getTotalSize());
		assertEquals(hidden, global.getShape().getTotalSize());

		for (int p = 0; p <= LENGTH; p++) {
			assertEquals(1.0, mask.toDouble(p), 0.0);
			for (int f = 0; f < hidden; f++) {
				double expected;
				if (p < prompt.length) {
					expected = encoded.valueAt(0, p, f);
				} else if (p < LENGTH) {
					expected = padding.toDouble(f);
				} else {
					expected = durationEmbedding.toDouble(f);
					assertEquals(context.valueAt(0, p, f), global.toDouble(f), 1e-7);
				}
				assertEquals("position " + p + " feature " + f, expected, context.valueAt(0, p, f), 1e-4);
			}
		}

		reference.destroy();
		conditioner.destroy();
	}
}
