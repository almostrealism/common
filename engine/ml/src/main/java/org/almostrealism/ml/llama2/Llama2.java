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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.BPE;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * Llama2 transformer implementation using the AR compute pipeline.
 *
 * <p>Loads a binary checkpoint in the llama2.c format and constructs
 * an {@link AutoregressiveModel} backed by the {@link AttentionFeatures}
 * building blocks. Supports greedy and temperature-based sampling with
 * BPE tokenization.</p>
 *
 * @author Michael Murray
 * @see Llama2Config
 * @see Llama2Weights
 * @see AutoregressiveModel
 */
public class Llama2 implements AttentionFeatures {
	static {
		System.setProperty("AR_HARDWARE_OFF_HEAP_SIZE", "0");
		System.setProperty("AR_EXPRESSION_WARNINGS", "disabled");
		System.setProperty("AR_GRAPH_PROPAGATION_WARNINGS", "disabled");
	}

	private Llama2Config config;
	private Llama2Weights weights;
	private String[] vocab;
	private float[] vocabScores;

	private AutoregressiveModel model;
	private OperationProfile profile;

	/**
	 * Entry point for standalone inference.
	 *
	 * @param args checkpoint path (optional), prompt (optional)
	 * @throws IOException if the checkpoint or tokenizer cannot be read
	 */
	public static void main(String[] args) throws IOException {
		int steps = 256;

		String checkpoint = args.length > 0 ? args[0] : "stories110M.bin";
		String prompt = args.length > 1 ? args[1] : null;

		Llama2 llama = new Llama2(checkpoint);
		llama.setTemperature(0.0);

		long duration = llama.run(steps, prompt,
				token -> {
					System.out.printf("%s", token);
					System.out.flush();
				});

		System.out.printf("\ntokens per second: %f\n", (steps - 1) / (double) duration * 1000);
		llama.getProfile().print();

		System.out.println("Done");
	}

	/**
	 * Loads a Llama2 model from a binary checkpoint, looking for
	 * {@code tokenizer.bin} in the current working directory.
	 *
	 * @param checkpoint path to the checkpoint file
	 * @throws IOException if the checkpoint or tokenizer cannot be read
	 */
	public Llama2(String checkpoint) throws IOException {
		this(checkpoint, "tokenizer.bin");
	}

	/**
	 * Loads a Llama2 model from a binary checkpoint and tokenizer.
	 *
	 * @param checkpoint path to the checkpoint file
	 * @param tokenizer  path to the tokenizer binary file
	 * @throws IOException if the checkpoint or tokenizer cannot be read
	 */
	public Llama2(String checkpoint, String tokenizer) throws IOException {
		long start = System.currentTimeMillis();

		try (FileChannel fileChannel = FileChannel.open(Paths.get(checkpoint), StandardOpenOption.READ)) {
			ByteBuffer bb = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
			bb.order(ByteOrder.LITTLE_ENDIAN);

			config = new Llama2Config(bb);
			weights = new Llama2Weights(config, bb.asFloatBuffer());
			System.out.println("Loaded weights in " + (System.currentTimeMillis() - start) + "ms");
		}

		// Load the tokenizer
		vocab = new String[config.vocabSize];
		vocabScores = new float[config.vocabSize];

		try (FileChannel channel = FileChannel.open(Paths.get(tokenizer), StandardOpenOption.READ)) {
			ByteBuffer bb = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
			bb.order(ByteOrder.LITTLE_ENDIAN);

			int maxTokenLength = bb.getInt();
			for (int i = 0; i < config.vocabSize; i++) {
				vocabScores[i] = bb.getFloat();
				int len = bb.getInt();
				byte[] bytes = new byte[len];
				bb.get(bytes);
				vocab[i] = new String(bytes);
			}
		}

		profile = new OperationProfile();
		model = model(profile);
	}

	/**
	 * Returns the profiling data collected during inference.
	 */
	public OperationProfile getProfile() { return profile; }

	/**
	 * Sets the sampling temperature.
	 *
	 * @param temperature 0.0 for greedy decoding, higher values for more randomness
	 */
	public void setTemperature(double temperature) {
		model.setTemperature(temperature);
	}

	/**
	 * Constructs the autoregressive model from the loaded weights.
	 *
	 * @param profile      the operation profile to record timing data
	 * @param requirements optional compute requirements (e.g., GPU)
	 * @return the compiled autoregressive model
	 */
	protected AutoregressiveModel model(OperationProfile profile, ComputeRequirement... requirements) {
		Model transformer = new Model(shape(1, config.dim));

		PackedCollection position = new PackedCollection(1);

		int dim = config.dim;

		for (int i = 0; i < config.layerCount; i++) {
			transformer.add(transformer(config.headCount,
					weights.rmsAttWeights.range(shape(config.dim), i * dim),
					weights.wk.range(shape(dim, dim), dim * dim * i),
					weights.wv.range(shape(dim, dim), dim * dim * i),
					weights.wq.range(shape(dim, dim), dim * dim * i),
					weights.wo.range(shape(dim, dim), dim * dim * i),
					weights.freqCis,
					weights.rmsFfn.range(shape(config.dim), i * config.dim),
					weights.w1.range(shape(config.hiddenDim, dim), dim * config.hiddenDim * i),
					weights.w2.range(shape(dim, config.hiddenDim), dim * config.hiddenDim * i),
					weights.w3.range(shape(config.hiddenDim, dim), dim * config.hiddenDim * i),
					p(position), requirements));
		}

		transformer.add(rmsnorm(shape(1, dim), weights.rmsFinalWeight));
		transformer.add(dense(weights.wcls));

		return AutoregressiveModel.of(transformer.compile(false, profile),
				step -> position.setMem((double) step),
				t -> weights.tokenEmbeddings.range(shape(config.dim), t * config.dim));
	}

	/**
	 * Runs autoregressive generation.
	 *
	 * @param steps  maximum number of tokens to generate
	 * @param prompt optional text prompt (null for unconditional generation)
	 * @param output callback for each generated token string
	 * @return generation duration in milliseconds (excluding first token)
	 */
	public long run(int steps, String prompt, Consumer<String> output) {
		if (steps <= 0 || steps > config.seqLen) {
			steps = config.seqLen;
		}

		// Build prompt with BOS as the first token so that the model
		// always processes BOS at position 0 (matching llama2.c behavior
		// and the AutoregressiveModel contract used by Qwen3).
		int[] allTokens = new int[config.seqLen];
		allTokens[0] = 1; // BOS
		int tokenCount = 1;
		if (prompt != null) {
			int[] textTokens = new int[config.seqLen];
			int textCount = BPE.encode(prompt, vocab, vocabScores, config.vocabSize, textTokens);
			System.arraycopy(textTokens, 0, allTokens, 1, textCount);
			tokenCount += textCount;
		}

		long start = 0;
		int next;
		int token = 1;

		model.setCurrentStep(0);
		model.setCurrentToken(1);
		model.setPrompt(allTokens, tokenCount);

		output.accept("<s>\n");
		while (model.getCurrentStep() < steps) {
			next = model.next();

			// Only output tokens generated after the prompt phase
			if (model.getCurrentStep() > tokenCount) {
				String tokenStr = (token == 1 && vocab[next].charAt(0) == ' ')
						? vocab[next].substring(1) : vocab[next];
				output.accept(tokenStr);
			}

			token = next;

			if (start == 0 && model.getCurrentStep() > tokenCount) {
				start = System.currentTimeMillis();
			}
		}

		return start == 0 ? 0 : System.currentTimeMillis() - start;
	}
}
