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

package org.almostrealism.ml.audio.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;

/**
 * Fast tests for {@link DiffusionTransformer#forward} argument handling.
 *
 * <p>These reproduce the exact configuration used in AggressiveFineTuningTest
 * (globalCondDim=768, condSeqLen=0) without the expensive encoding step.</p>
 */
public class DiffusionTransformerForwardTest extends TestSuiteBase {

	private static final Path WEIGHTS_DIR = Path.of("/workspace/project/weights");

	/**
	 * Reproduces the Step 5 failure from AggressiveFineTuningTest:
	 * {@code forward(x, t, null, null)} when globalCondDim=768, condSeqLen=0.
	 *
	 * <p>The model architecture includes a global conditioning input (globalCondDim &gt; 0),
	 * but the caller passes null for globalCond during unconditional generation.
	 * This must not cause UnsupportedOperationException.</p>
	 */
	@Test
	public void testForwardWithNullGlobalCond() throws IOException {
		if (!Files.exists(WEIGHTS_DIR)) {
			log("Weights directory not found, skipping test");
			return;
		}

		StateDictionary weights = new StateDictionary(WEIGHTS_DIR.toString());

		int ioChannels = 64;
		int embedDim = 1024;
		int depth = 16;
		int numHeads = 8;
		int patchSize = 1;
		int condTokenDim = 768;
		int globalCondDim = 768;
		int audioSeqLen = 4;
		int condSeqLen = 0;

		DiffusionTransformer model = new DiffusionTransformer(
				ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, "rf_denoiser",
				audioSeqLen, condSeqLen, weights, false
		);

		PackedCollection x = new PackedCollection(1, ioChannels, audioSeqLen);
		PackedCollection t = new PackedCollection(1);
		t.setMem(0, 0.5);

		// This is exactly what DiffusionSampler.runSamplingLoop does
		// during unconditional generation
		PackedCollection result = model.forward(x, t, null, null);
		assertNotNull("forward should return a result", result);

		log("Forward pass succeeded with null conditioning, output shape: " + result.getShape());
	}
}
