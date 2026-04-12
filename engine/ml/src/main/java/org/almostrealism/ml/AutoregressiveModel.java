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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.Ops;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.stats.DistributionFeatures;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Manages autoregressive token generation for language models, parameterized
 * by the token type {@code T}.
 *
 * <p>This class wraps a compiled transformer model and provides the autoregressive
 * inference loop needed for generation. It handles:</p>
 * <ul>
 *   <li><strong>Token-by-token generation:</strong> Produces one token at a time via a caller-supplied sample function</li>
 *   <li><strong>Prompt handling:</strong> Pre-fills the model with prompt tokens before generation</li>
 *   <li><strong>Temperature sampling:</strong> Controls randomness of token selection</li>
 *   <li><strong>Position tracking:</strong> Maintains the current step in the sequence</li>
 * </ul>
 *
 * <p>For standard integer-token text models use the {@link #of(CompiledModel, IntConsumer, IntFunction)}
 * factory, which returns {@code AutoregressiveModel<Integer>} and handles all
 * sampling infrastructure internally. For compound or structured token types, use the
 * generic constructor directly.</p>
 *
 * <h2>Generation Modes</h2>
 * <ul>
 *   <li><strong>Greedy (temperature=0):</strong> Always selects the highest-probability token</li>
 *   <li><strong>Sampling (temperature&gt;0):</strong> Samples from the softmax probability distribution</li>
 * </ul>
 *
 * <h2>Usage Example (Integer tokens)</h2>
 * <pre>{@code
 * // Create from compiled transformer model
 * AutoregressiveModel<Integer> model = AutoregressiveModel.of(
 *     compiledTransformer,
 *     step -> position.setMem((double) step),
 *     tokenId -> embeddings.range(shape(dim), tokenId * dim)
 * );
 *
 * // Configure sampling
 * model.setTemperature(0.7);
 *
 * // Set prompt tokens
 * Integer[] promptTokens = Arrays.stream(tokenizer.encode("Once upon a time"))
 *                                 .boxed().toArray(Integer[]::new);
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
 *   <li>Otherwise: apply the sample function to the cached model output</li>
 * </ol>
 *
 * @param <T> the token type (e.g. {@code Integer} for text, {@code MidiCompoundToken} for MIDI)
 * @author Michael Murray
 * @see CompiledModel
 */
public class AutoregressiveModel<T> {

	/** Singleton for accessing {@link DistributionFeatures#softmax} from static context. */
	private static final DistributionFeatures DIST = new DistributionFeatures() {};
	/** Consumer invoked with the current position index before each forward pass. */
	private final IntConsumer step;

	/**
	 * Consumer invoked with the current token before each forward pass.
	 * Responsible for loading the token's representation into the model's input buffer.
	 */
	private final Consumer<T> token;

	/** Supplier that executes the model forward pass and returns the model's output. */
	private final Supplier<PackedCollection> forward;

	/**
	 * Function that converts the cached model output to the next token.
	 * For text models this applies temperature scaling, softmax, and sampling.
	 * For structured token types (e.g. MIDI compound tokens) this may invoke
	 * a specialized decoder such as a GRU.
	 */
	private final Function<PackedCollection, T> sample;

	/** The current generation step index; incremented after each call to {@link #next()}. */
	private int currentStep;

	/** The most recently generated or input token. */
	private T currentToken;

	/** A single-element collection holding the current sampling temperature value. */
	private PackedCollection temperature;

	/** The prompt token array set by {@link #setPrompt(Object[], int)}. */
	private T[] prompt;

	/** Number of valid tokens in {@link #prompt}. */
	private int promptLength;

	/** The model output produced by the most recent forward pass; used to sample the next token. */
	private PackedCollection cachedOutput;

	/**
	 * Creates a new autoregressive model with the specified components.
	 *
	 * @param step        consumer to update the current position in the sequence (called before each forward pass)
	 * @param token       consumer to load the current input token before each forward pass
	 * @param forward     supplier that executes the model forward pass and returns output
	 * @param sample      function that converts the cached model output to the next token
	 * @param temperature shared temperature collection (may be updated via {@link #setTemperature(double)})
	 */
	public AutoregressiveModel(IntConsumer step, Consumer<T> token, Supplier<PackedCollection> forward,
							   Function<PackedCollection, T> sample, PackedCollection temperature) {
		this.step = step;
		this.token = token;
		this.forward = forward;
		this.sample = sample;
		this.temperature = temperature;
	}

	/**
	 * Returns the current step (position) in the generation sequence.
	 *
	 * @return the current step index, starting from 0
	 */
	public int getCurrentStep() { return currentStep; }

	/**
	 * Sets the current step (position) in the generation sequence.
	 *
	 * @param currentStep the step index to set
	 */
	public void setCurrentStep(int currentStep) {
		this.currentStep = currentStep;
		this.cachedOutput = null;
	}

	/**
	 * Returns the most recently generated (or input) token.
	 *
	 * @return the current token
	 */
	public T getCurrentToken() { return currentToken; }

	/**
	 * Sets the current token. Use this to initialize with a BOS or SOS token before generation.
	 *
	 * @param currentToken the token to set
	 */
	public void setCurrentToken(T currentToken) { this.currentToken = currentToken; }

	/**
	 * Returns the current sampling temperature.
	 *
	 * @return the temperature value (0.0 for greedy, higher values for more randomness)
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
	 * @param temperature the temperature value (0.0 or higher)
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
	 * @param promptTokens array containing the prompt tokens
	 * @param length       number of tokens to use from the array
	 */
	public void setPrompt(T[] promptTokens, int length) {
		this.prompt = promptTokens;
		this.promptLength = length;
	}

	/**
	 * Generates and returns the next token in the sequence.
	 *
	 * <p>This method performs one step of autoregressive generation:</p>
	 * <ol>
	 *   <li>Updates the position callback with the current step</li>
	 *   <li>Updates the token callback with the current token</li>
	 *   <li>Executes the model forward pass to get the model output</li>
	 *   <li>Selects the next token (from prompt or by applying the sample function)</li>
	 *   <li>Increments the step counter</li>
	 * </ol>
	 *
	 * @return the selected token for this step
	 */
	public T next() {
		if (currentStep < promptLength) {
			// Prompt phase: feed each prompt token at its correct position
			// to build the KV cache for the entire prompt
			step.accept(currentStep);
			token.accept(prompt[currentStep]);
			cachedOutput = forward.get();
			currentToken = prompt[currentStep];
		} else {
			// Generation phase:
			// 1. Sample from PREVIOUS step's output (what comes next?)
			// 2. Feed the sampled token at current position
			// 3. Cache current output for next step
			currentToken = sample.apply(cachedOutput);
			step.accept(currentStep);
			token.accept(currentToken);
			cachedOutput = forward.get();
		}

		currentStep++;
		return currentToken;
	}

	/**
	 * Sample a token from logits using temperature scaling and nucleus (top-p) filtering.
	 *
	 * <p>When temperature is 0 or random is null, uses greedy argmax.
	 * Otherwise applies temperature scaling, optional nucleus filtering, and categorical sampling.</p>
	 *
	 * @param logits      logit collection of shape (vocabSize)
	 * @param vocabSize   number of entries to consider
	 * @param temperature scaling factor (0 = greedy argmax)
	 * @param topP        nucleus sampling threshold (1.0 = no filtering)
	 * @param random      random number generator (null = greedy)
	 * @return selected token index
	 */
	public static int sampleToken(PackedCollection logits, int vocabSize,
								  double temperature, double topP, Random random) {
		if (temperature <= 0.0 || random == null) {
			int maxIdx = 0;
			double maxVal = logits.toDouble(0);
			for (int i = 1; i < vocabSize; i++) {
				double val = logits.toDouble(i);
				if (val > maxVal) {
					maxVal = val;
					maxIdx = i;
				}
			}
			return maxIdx;
		}

		double[] probs = new double[vocabSize];
		double maxLogit = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < vocabSize; i++) {
			probs[i] = logits.toDouble(i) / temperature;
			if (probs[i] > maxLogit) maxLogit = probs[i];
		}

		double sum = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			probs[i] = Math.exp(probs[i] - maxLogit);
			sum += probs[i];
		}
		for (int i = 0; i < vocabSize; i++) {
			probs[i] /= sum;
		}

		if (topP < 1.0) {
			Integer[] indices = new Integer[vocabSize];
			for (int i = 0; i < vocabSize; i++) indices[i] = i;
			Arrays.sort(indices, (a, b) -> Double.compare(probs[b], probs[a]));

			double cumProb = 0.0;
			double topSum = 0.0;
			int cutoff = vocabSize;
			for (int i = 0; i < vocabSize; i++) {
				cumProb += probs[indices[i]];
				topSum += probs[indices[i]];
				if (cumProb >= topP) {
					cutoff = i + 1;
					break;
				}
			}
			for (int i = cutoff; i < vocabSize; i++) {
				probs[indices[i]] = 0.0;
			}
			for (int i = 0; i < vocabSize; i++) {
				probs[i] /= topSum;
			}
		}

		double r = random.nextDouble();
		double cumulative = 0.0;
		for (int i = 0; i < vocabSize; i++) {
			cumulative += probs[i];
			if (r < cumulative) return i;
		}
		return vocabSize - 1;
	}

	/**
	 * Creates an {@code AutoregressiveModel<Integer>} from a compiled transformer model.
	 *
	 * <p>This factory method creates an autoregressive model that:</p>
	 * <ul>
	 *   <li>Updates position via the provided step consumer</li>
	 *   <li>Copies token embeddings into a reusable input buffer</li>
	 *   <li>Executes the compiled model's forward pass for logits</li>
	 *   <li>Samples the next token using temperature scaling and softmax</li>
	 * </ul>
	 *
	 * @param model      the compiled transformer model
	 * @param step       consumer to update the position for each generation step
	 * @param tokenEmbed function that returns the embedding for a given token ID
	 * @return a new {@code AutoregressiveModel<Integer>} ready for generation
	 */
	public static AutoregressiveModel<Integer> of(CompiledModel model, IntConsumer step, IntFunction<PackedCollection> tokenEmbed) {
		PackedCollection in = new PackedCollection(model.getInputShape());
		int vocabSize = model.getOutputShape().getTotalSize();

		PackedCollection temperature = new PackedCollection(1);

		Evaluable<PackedCollection> indexOfMax = Ops.o().indexOfMax(Ops.o().x(vocabSize)).get();
		Evaluable<PackedCollection> rescale = Ops.o().x(vocabSize).divide(Ops.o().cp(temperature)).get();
		Evaluable<? extends PackedCollection> softmax = Process.optimized(DIST.softmax(Ops.o().x(vocabSize))).get();

		Random random = new Random();

		Function<PackedCollection, Integer> sample = logits -> {
			if (temperature.toDouble(0) == 0.0) {
				return (int) indexOfMax.evaluate(logits).toDouble(0);
			} else {
				rescale.into(logits).evaluate(logits);
				softmax.into(logits).evaluate(logits);
				return sampleToken(logits, vocabSize, 1.0, 1.0, random);
			}
		};

		return new AutoregressiveModel<>(
				step,
				t -> in.setMem(0, tokenEmbed.apply(t), 0, model.getInputShape().getTotalSize()),
				() -> model.forward(in),
				sample,
				temperature);
	}

}
