/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.ml;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.stats.DistributionFeatures;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Manages autoregressive token generation for language models.
 *
 * <p>This class wraps a compiled transformer model and provides the autoregressive
 * inference loop needed for text generation. It handles:</p>
 * <ul>
 *   <li><strong>Token-by-token generation:</strong> Produces one token at a time based on model logits</li>
 *   <li><strong>Prompt handling:</strong> Pre-fills the model with prompt tokens before generation</li>
 *   <li><strong>Temperature sampling:</strong> Controls randomness of token selection</li>
 *   <li><strong>Position tracking:</strong> Maintains the current step in the sequence</li>
 * </ul>
 *
 * <h2>Generation Modes</h2>
 * <ul>
 *   <li><strong>Greedy (temperature=0):</strong> Always selects the highest-probability token</li>
 *   <li><strong>Sampling (temperature&gt;0):</strong> Samples from the softmax probability distribution</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create from compiled transformer model
 * AutoregressiveModel model = AutoregressiveModel.of(
 *     compiledTransformer,
 *     step -> position.setMem((double) step),
 *     tokenId -> embeddings.range(shape(dim), tokenId * dim)
 * );
 *
 * // Configure sampling
 * model.setTemperature(0.7);
 *
 * // Set prompt tokens
 * int[] promptTokens = tokenizer.encode("Once upon a time");
 * model.setPrompt(promptTokens, promptTokens.length);
 * model.setCurrentToken(BOS_TOKEN);
 *
 * // Generate tokens
 * while (model.getCurrentStep() < maxSteps) {
 *     int nextToken = model.next();
 *     if (nextToken == EOS_TOKEN) break;
 *     System.out.print(tokenizer.decode(nextToken));
 * }
 * }</pre>
 *
 * <h2>Token Selection Process</h2>
 * <p>The {@link #next()} method implements the following logic:</p>
 * <ol>
 *   <li>If within prompt range: return the next prompt token</li>
 *   <li>If temperature == 0: return argmax of logits (greedy)</li>
 *   <li>If temperature &gt; 0: scale logits, apply softmax, sample from distribution</li>
 * </ol>
 *
 * @author Michael Murray
 * @see CompiledModel
 * @see DistributionFeatures#sample(PackedCollection, int)
 */
public class AutoregressiveModel implements DistributionFeatures, CodeFeatures {
	private final IntConsumer step;
	private final IntConsumer token;
	private final Supplier<PackedCollection> logits;
	private final int vocabSize;

	private Evaluable<PackedCollection> indexOfMax;
	private Evaluable<PackedCollection> rescale;
	private Evaluable<? extends PackedCollection> softmax;

	private int currentStep;
	private int currentToken;

	private PackedCollection temperature;
	private int[] prompt;
	private int promptTokens;

	/**
	 * Creates a new autoregressive model with the specified components.
	 *
	 * @param step Consumer to update the current position in the sequence (called before each forward pass)
	 * @param token Consumer to set the current input token embedding (called before each forward pass)
	 * @param logits Supplier that executes the model forward pass and returns output logits
	 * @param vocabSize Size of the vocabulary (number of possible output tokens)
	 */
	public AutoregressiveModel(IntConsumer step, IntConsumer token, Supplier<PackedCollection> logits, int vocabSize) {
		this.step = step;
		this.token = token;
		this.logits = logits;
		this.vocabSize = vocabSize;
		this.temperature = new PackedCollection(1);

		this.indexOfMax = indexOfMax(x(vocabSize)).get();
		this.rescale = (Evaluable) x(vocabSize).divide(cp(temperature)).get();
		this.softmax = Process.optimized(softmax(x(vocabSize))).get();
	}

	/**
	 * Returns the current step (position) in the generation sequence.
	 *
	 * @return The current step index, starting from 0
	 */
	public int getCurrentStep() { return currentStep; }

	/**
	 * Sets the current step (position) in the generation sequence.
	 *
	 * @param currentStep The step index to set
	 */
	public void setCurrentStep(int currentStep) { this.currentStep = currentStep; }

	/**
	 * Returns the most recently generated (or input) token ID.
	 *
	 * @return The current token ID
	 */
	public int getCurrentToken() { return currentToken; }

	/**
	 * Sets the current token ID. Use this to initialize with a BOS token before generation.
	 *
	 * @param currentToken The token ID to set
	 */
	public void setCurrentToken(int currentToken) { this.currentToken = currentToken; }

	/**
	 * Returns the current sampling temperature.
	 *
	 * @return The temperature value (0.0 for greedy, higher values for more randomness)
	 */
	public double getTemperature() {
		return temperature.toDouble(0);
	}

	/**
	 * Sets the sampling temperature for token selection.
	 *
	 * <p>Temperature controls the randomness of token selection:</p>
	 * <ul>
	 *   <li><strong>0.0:</strong> Greedy decoding - always select the most probable token</li>
	 *   <li><strong>0.1-0.7:</strong> Low randomness - mostly coherent, occasionally creative</li>
	 *   <li><strong>0.7-1.0:</strong> Moderate randomness - balanced creativity and coherence</li>
	 *   <li><strong>&gt;1.0:</strong> High randomness - more creative but potentially less coherent</li>
	 * </ul>
	 *
	 * @param temperature The temperature value (0.0 or higher)
	 */
	public void setTemperature(double temperature) {
		this.temperature.set(0, temperature);
	}

	/**
	 * Sets the prompt tokens to pre-fill before generation.
	 *
	 * <p>During generation, the model will return prompt tokens for the first {@code length}
	 * steps instead of sampling from the model output. This allows the model to process
	 * the prompt context while still providing token outputs for each step.</p>
	 *
	 * @param promptTokens Array containing the prompt token IDs
	 * @param length Number of tokens to use from the array (may be less than array length)
	 */
	public void setPrompt(int promptTokens[], int length) {
		this.prompt = promptTokens;
		this.promptTokens = length;
	}

	/**
	 * Generates and returns the next token in the sequence.
	 *
	 * <p>This method performs one step of autoregressive generation:</p>
	 * <ol>
	 *   <li>Updates the position callback with the current step</li>
	 *   <li>Updates the token callback with the current token embedding</li>
	 *   <li>Executes the model forward pass to get logits</li>
	 *   <li>Selects the next token (from prompt, greedy, or sampling)</li>
	 *   <li>Increments the step counter</li>
	 * </ol>
	 *
	 * @return The selected token ID for this step
	 */
	public int next() {
		step.accept(currentStep);
		token.accept(currentToken);

		PackedCollection logit = logits.get();

		if (currentStep < promptTokens) {
			currentToken = prompt[currentStep];
		} else if (temperature.toDouble(0) == 0.0) {
			currentToken = (int) indexOfMax.evaluate(logit).toDouble(0);
		} else {
			rescale.into(logit).evaluate(logit);
			softmax.into(logit).evaluate(logit);
			currentToken = sample(logit, vocabSize);
		}

		currentStep++;
		return currentToken;
	}

	/**
	 * Creates an AutoregressiveModel from a compiled transformer model.
	 *
	 * <p>This factory method creates an AutoregressiveModel that:</p>
	 * <ul>
	 *   <li>Updates position via the provided step consumer</li>
	 *   <li>Copies token embeddings into a reusable input buffer</li>
	 *   <li>Executes the compiled model's forward pass for logits</li>
	 * </ul>
	 *
	 * @param model The compiled transformer model
	 * @param step Consumer to update the position for each generation step
	 * @param tokenEmbed Function that returns the embedding for a given token ID
	 * @return A new AutoregressiveModel ready for generation
	 */
	public static AutoregressiveModel of(CompiledModel model, IntConsumer step, IntFunction<PackedCollection> tokenEmbed) {
		PackedCollection in = new PackedCollection(model.getInputShape());
		return new AutoregressiveModel(
				step,
				t ->
						in.setMem(0, tokenEmbed.apply(t), 0, model.getInputShape().getTotalSize()),
				() -> model.forward(in),
				model.getOutputShape().getTotalSize());
	}
}
