/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.Tokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scoring system for evaluating alignment between audio and text prompts
 * using conditional audio generation models.
 */
public class ConditionalAudioScoring extends ConditionalAudioSystem {

//	double[] timesteps = {0.25, 0.5, 0.75};
//	double[] timestepWeights = {0.2, 0.6, 0.2};
	double[] timesteps = {0.0, 0.25, 0.5, 0.75, 1.0};
	double[] timestepWeights = {0.1, 0.25, 0.3, 0.25, 0.1};

	/**
	 * Creates a ConditionalAudioScoring with the provided components.
	 *
	 * @param tokenizer the tokenizer for text processing
	 * @param conditioner the conditioner for generating attention inputs
	 * @param autoencoder the audio encoder/decoder for latent space operations
	 * @param ditStates state dictionary for the diffusion transformer weights
	 */
	public ConditionalAudioScoring(Tokenizer tokenizer,
								   AudioAttentionConditioner conditioner,
								   AutoEncoder autoencoder,
								   StateDictionary ditStates) {
		super(tokenizer, conditioner, autoencoder, ditStates, true);
	}

	public double computeScore(String prompt, WaveData audio) {
		long[] tokens = getTokenizer().encodeAsLong(prompt);
		log("\t\"" + prompt + "\" (" + tokens.length + " tokens)");
		return computeScore(tokens, audio);
	}

	public double computeScore(long[] promptTokenIds, WaveData audio) {
		// return computeScore(promptTokenIds, audio.getData(), audio.getDuration());
		// return computeDenoisingScore(promptTokenIds, audio.getData(), audio.getDuration());
		return computeReconstructionScore(promptTokenIds, audio.getData(), audio.getDuration());
	}

	/**
	 * Compute score using conditional denoising loss.
	 * Lower loss = better alignment between audio and text.
	 */
	public double computeDenoisingScore(long[] promptTokenIds, PackedCollection audio, double duration) {
		AudioAttentionConditioner.ConditionerOutput conditionerOutputs =
				getConditioner().runConditioners(promptTokenIds, duration);
		PackedCollection audioLatent = getAutoencoder().encode(cp(audio)).evaluate();

		double totalScore = 0.0;
		double[] noiseLevels = {0.1, 0.3, 0.5, 0.7, 0.9};

		for (double sigma : noiseLevels) {
			// Add noise to the latent
			PackedCollection noise = new PackedCollection(audioLatent.getShape()).randnFill();
			PackedCollection noisyLatent = cp(audioLatent).add(cp(noise).multiply(sigma)).evaluate();

			// Predict the noise with conditioning
			PackedCollection predictedNoise = getDitModel().forward(
					noisyLatent,
					pack(sigma),
					conditionerOutputs.getCrossAttentionInput(),
					conditionerOutputs.getGlobalCond()
			);

			// Compute MSE between predicted and actual noise
			double mse = cp(predictedNoise).subtract(cp(noise)).sq().mean().evaluateOptimized().toDouble();

			// Lower MSE = better denoising = better alignment
			totalScore += Math.exp(-mse);
		}

		return totalScore / noiseLevels.length;
	}

	/**
	 * Compute score using reconstruction similarity.
	 * Generate audio from text, then compare latents.
	 */
	public double computeReconstructionScore(long[] promptTokenIds, PackedCollection audio, double duration) {
		// This would require full generation capability
		// For now, we'll compute a proxy using partial diffusion

		AudioAttentionConditioner.ConditionerOutput conditionerOutputs =
				getConditioner().runConditioners(promptTokenIds, duration);
		PackedCollection audioLatent = getAutoencoder().encode(cp(audio)).evaluate();

		// Start from noise and denoise partially
		PackedCollection noise = new PackedCollection(audioLatent.getShape()).randnFill();
		PackedCollection current = noise;

		// Run a few denoising steps
		double[] sigmas = {1.0, 0.7, 0.5, 0.3};
		for (double sigma : sigmas) {
			current = getDitModel().forward(
					current,
					pack(sigma),
					conditionerOutputs.getCrossAttentionInput(),
					conditionerOutputs.getGlobalCond()
			);
		}

		// Compare with original latent
		double similarity = computeCosineSimilarity(current, audioLatent);
		return similarity;
	}

	private double computeCosineSimilarity(PackedCollection a, PackedCollection b) {
		double dotProduct = cp(a).multiply(cp(b)).sum(0).evaluateOptimized().valueAt(0);
		double normA = cp(a).each().sq().sum(0).sqrt().evaluateOptimized().toDouble();
		double normB = cp(b).each().sq().sum(0).sqrt().evaluateOptimized().toDouble();
		return dotProduct / (normA * normB + 1e-6);
	}

	public double computeScore(long[] promptTokenIds, PackedCollection audio, double duration) {
		// 1. Process tokens through conditioners
		AudioAttentionConditioner.ConditionerOutput conditionerOutputs =
				getConditioner().runConditioners(promptTokenIds, duration);

		// 2. Get audio latent
		PackedCollection audioLatent = getAutoencoder().encode(cp(audio)).evaluate();

		// 3. Run forward passes at multiple noise levels
		List<Map<Integer, PackedCollection>> allAttentions = new ArrayList<>();

		for (double t : timesteps) {
			PackedCollection output = getDitModel().forward(
					audioLatent, pack(t),
					conditionerOutputs.getCrossAttentionInput(),
					conditionerOutputs.getGlobalCond());

			allAttentions.add(getDitModel().getAttentionActivations().entrySet().stream()
					.collect(Collectors.toMap(
							Map.Entry::getKey, ent -> new PackedCollection(ent.getValue()))));
		}

		// 4. Extract attention mask from conditioner outputs
		PackedCollection attentionMask = conditionerOutputs.getCrossAttentionMask();

		// 5. Compute scores with mask-aware aggregation
		int audioSeqLen = (int) Math.ceil(getAutoencoder().getLatentSampleRate() * duration);
		return computeMultiTimestepScore(allAttentions, attentionMask, audioSeqLen);
	}

	private double computeMultiTimestepScore(List<Map<Integer, PackedCollection>> allAttentions,
											 PackedCollection attentionMask, int audioSeqLen) {
		// Weight later layers more heavily
		double[] layerWeights = computeLayerWeights(allAttentions.get(0).size());

		// Aggregate across timesteps
		double totalScore = 0.0;
		for (int t = 0; t < allAttentions.size(); t++) {
			double timestepScore = 0.0;
			Map<Integer, PackedCollection> attentions = allAttentions.get(t);

			for (Map.Entry<Integer, PackedCollection> entry : attentions.entrySet()) {
				int layer = entry.getKey();
				PackedCollection attention = entry.getValue();
				double layerScore = computeMaskedAttentionScore(attention, attentionMask, audioSeqLen);
				timestepScore += layerScore * layerWeights[layer];
			}

			totalScore += timestepScore * timestepWeights[t];
		}

		return totalScore;
	}

	private double[] computeLayerWeights(int numLayers) {
		// Exponentially increasing weights for later layers
		double[] weights = new double[numLayers];
		double sum = 0.0;
		for (int i = 0; i < numLayers; i++) {
			weights[i] = Math.pow(2, i);
			sum += weights[i];
		}
		// Normalize
		for (int i = 0; i < numLayers; i++) {
			weights[i] /= sum;
		}
		return weights;
	}

	private double computeMaskedAttentionScore(PackedCollection attention,
											   PackedCollection mask,
											   int audioSeqLen) {
		TraversalPolicy shape = attention.getShape();
		int batch = shape.length(0);
		int numHeads = shape.length(1);
		int seqLenText = shape.length(3);

		// Compute entropy-based score for attention distribution
		double totalScore = 0.0;
		int validPositions = 0;

		for (int b = 0; b < batch; b++) {
			for (int h = 0; h < numHeads; h++) {
				for (int a = 1; a < (1 + audioSeqLen); a++) { // Skip first position (global conditioning)
					double rowSum = 0.0;
					double entropy = 0.0;

					// First pass: compute sum for normalization
					for (int t = 0; t < seqLenText; t++) {
						if (mask == null || mask.valueAt(b, t) > 0.5) {
							rowSum += attention.valueAt(b, h, a, t);
						}
					}

					if (rowSum > 0) {
						// Second pass: compute entropy
						for (int t = 0; t < seqLenText; t++) {
							if (mask == null || mask.valueAt(b, t) > 0.5) {
								double p = attention.valueAt(b, h, a, t) / rowSum;
								if (p > 0) {
									entropy -= p * Math.log(p);
								}
							}
						}

						// Lower entropy = more focused attention = better alignment
						// Convert to score where higher is better
						double focusScore = Math.exp(-entropy);
						totalScore += focusScore;
						validPositions++;
					}
				}
			}
		}

		return validPositions > 0 ? totalScore / validPositions : 0.0;
	}

}
