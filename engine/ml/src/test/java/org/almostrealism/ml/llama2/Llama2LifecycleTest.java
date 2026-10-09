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

package org.almostrealism.ml.llama2;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests the lifecycle of {@link Llama2} against a small synthetic checkpoint and tokenizer, so
 * that no downloaded model is needed.
 *
 * @author Michael Murray
 */
public class Llama2LifecycleTest extends TestSuiteBase {
	/** Model dimension of the synthetic checkpoint. */
	private static final int DIM = 8;

	/** Feed-forward hidden dimension of the synthetic checkpoint. */
	private static final int HIDDEN_DIM = 16;

	/** Attention heads of the synthetic checkpoint. */
	private static final int HEADS = 2;

	/** Vocabulary size of the synthetic checkpoint and tokenizer. */
	private static final int VOCAB = 8;

	/** Sequence length of the synthetic checkpoint. */
	private static final int SEQ_LEN = 8;

	/** Number of generation steps run by each instance. */
	private static final int STEPS = 6;

	/**
	 * Destroying a {@link Llama2} releases the position its generator was built with; a second
	 * destroy is harmless, and a new instance over the same checkpoint generates the same text
	 * greedily, so nothing that instance relies on was shared and released.
	 */
	@Test(timeout = 600000)
	public void destroyReleasesGenerator() throws IOException {
		Path dir = Files.createTempDirectory("llama2-lifecycle");
		Path checkpointFile = dir.resolve("model.bin");
		Path tokenizerFile = dir.resolve("tokenizer.bin");
		try {
			String checkpoint = writeCheckpoint(checkpointFile).toString();
			String tokenizer = writeTokenizer(tokenizerFile).toString();

			Llama2 first = new Llama2(checkpoint, tokenizer);
			PackedCollection position = first.getAutoregressiveModel().getPosition();
			List<String> before;
			try {
				before = generate(first);
			} finally {
				first.destroy();
			}

			Assert.assertEquals(STEPS, before.size());
			Assert.assertTrue("generator position was not released", position.isDestroyed());
			first.destroy();

			try (Llama2 second = new Llama2(checkpoint, tokenizer)) {
				Assert.assertFalse("a new instance shares the released position",
						second.getAutoregressiveModel().getPosition().isDestroyed());
				Assert.assertEquals(before, generate(second));
			}
		} finally {
			Files.deleteIfExists(checkpointFile);
			Files.deleteIfExists(tokenizerFile);
			Files.deleteIfExists(dir);
		}
	}

	/**
	 * Runs unconditional greedy generation.
	 *
	 * @param llama the model
	 * @return every emitted string, including the leading start marker
	 */
	private static List<String> generate(Llama2 llama) {
		List<String> out = new ArrayList<>();
		llama.setTemperature(0.0);
		llama.run(STEPS, null, out::add);
		return out;
	}

	/**
	 * Writes a checkpoint in the Llama2 binary format: the configuration header followed by
	 * every weight tensor in the order {@link Llama2Weights} reads them, with shared embedding
	 * and classifier weights. Values are small and deterministic.
	 *
	 * @param path the file to write
	 * @return the path
	 */
	private static Path writeCheckpoint(Path path) throws IOException {
		int layerWeights = DIM + 4 * DIM * DIM + DIM + 3 * HIDDEN_DIM * DIM;
		int freqs = 2 * SEQ_LEN * (DIM / HEADS / 2);
		int floats = VOCAB * DIM + layerWeights + DIM + freqs;

		ByteBuffer buffer = ByteBuffer.allocate(7 * 4 + floats * 4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(DIM).putInt(HIDDEN_DIM).putInt(1).putInt(HEADS).putInt(HEADS)
				.putInt(VOCAB).putInt(SEQ_LEN);
		for (int i = 0; i < floats; i++) {
			buffer.putFloat((float) (0.1 * Math.sin(0.7 * i + 0.3)));
		}

		return Files.write(path, buffer.array());
	}

	/**
	 * Writes a tokenizer in the Llama2 binary format, with a distinct one-character string for
	 * each token.
	 *
	 * @param path the file to write
	 * @return the path
	 */
	private static Path writeTokenizer(Path path) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(4 + VOCAB * 9).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(1);
		for (int i = 0; i < VOCAB; i++) {
			buffer.putFloat(i).putInt(1).put(String.valueOf((char) ('a' + i)).getBytes(StandardCharsets.US_ASCII));
		}

		return Files.write(path, buffer.array());
	}
}
