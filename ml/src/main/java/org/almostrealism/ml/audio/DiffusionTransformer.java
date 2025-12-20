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
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DiffusionTransformer implements DitModel, DiffusionTransformerFeatures {
	private static final int SAMPLE_SIZE = 524288;
	private static final int DOWNSAMPLING_RATIO = 2048;

	public static boolean enableProfile = false;
	public static int batchSize = 1;

	private final int ioChannels;
	private final int embedDim;
	private final int depth;
	private final int numHeads;
	private final int patchSize;
	private final int condTokenDim;
	private final int globalCondDim;

	private final int audioSeqLen;
	private final int condSeqLen;

	private final StateDictionary stateDictionary;
	private final Set<String> unusedWeights;
	private final Model model;

	private OperationProfile profile;
	private CompiledModel compiled;

	private PackedCollection preTransformerState, postTransformerState;
	private Map<Integer, PackedCollection> attentionScores;

	public DiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
								int patchSize, int condTokenDim, int globalCondDim,
								String diffusionObjective, StateDictionary stateDictionary) {
		this(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				stateDictionary, false);
	}

	public DiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
								int patchSize, int condTokenDim, int globalCondDim,
								String diffusionObjective, StateDictionary stateDictionary,
								boolean captureAttentionScores) {
		this(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				SAMPLE_SIZE / DOWNSAMPLING_RATIO, 65,
				stateDictionary, captureAttentionScores);
	}

	public DiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
								int patchSize, int condTokenDim, int globalCondDim,
								String diffusionObjective, int audioSeqLen, int condSeqLen,
								StateDictionary stateDictionary, boolean captureAttentionScores) {
		this.ioChannels = ioChannels;
		this.embedDim = embedDim;
		this.depth = depth;
		this.numHeads = numHeads;
		this.patchSize = patchSize;
		this.condTokenDim = condTokenDim;
		this.globalCondDim = globalCondDim;
		this.audioSeqLen = audioSeqLen;
		this.condSeqLen = condSeqLen;
		this.stateDictionary = stateDictionary;
		this.unusedWeights = new HashSet<>();

		if (stateDictionary != null) {
			this.unusedWeights.addAll(stateDictionary.keySet());
		}

		if (captureAttentionScores) {
			attentionScores = new HashMap<>();
		}

		this.model = buildModel();
	}

	protected Model buildModel() {
		// Create model with input shape - [batch, channels, sequence_length]
		Model model = new Model(shape(batchSize, ioChannels, audioSeqLen));

		// Add timestep embedding input
		Block timestampEmbed = timestampEmbedding();
		model.addInput(timestampEmbed);

		// Add cross-attention condition input if needed
		SequentialBlock condEmbed = null;
		if (condTokenDim > 0) {
			PackedCollection condProjWeight1 = createWeight("model.model.to_cond_embed.0.weight", embedDim, condTokenDim);
			PackedCollection condProjWeight2 = createWeight("model.model.to_cond_embed.2.weight", embedDim, embedDim);

			condEmbed = new SequentialBlock(shape(condSeqLen, condTokenDim));
			condEmbed.add(dense(condProjWeight1));
			condEmbed.add(silu());
			condEmbed.add(dense(condProjWeight2));
			condEmbed.reshape(batchSize, condSeqLen, embedDim);
			model.addInput(condEmbed);
		}

		// Add global condition input if needed
		SequentialBlock globalEmbed = null;
		if (globalCondDim > 0) {
			PackedCollection globalProjInWeight =
					createWeight("model.model.to_global_embed.0.weight", embedDim, globalCondDim);
			PackedCollection globalProjOutWeight =
					createWeight("model.model.to_global_embed.2.weight", embedDim, embedDim);
			
			globalEmbed = new SequentialBlock(shape(globalCondDim));
			globalEmbed.add(dense(globalProjInWeight));
			globalEmbed.add(silu());
			globalEmbed.add(dense(globalProjOutWeight));
			globalEmbed.reshape(batchSize, embedDim);
			model.addInput(globalEmbed);
		}

		// Main model pipeline
		SequentialBlock main = model.sequential();

		// Input projection
		PackedCollection inputProjWeight =
				createWeight("model.model.preprocess_conv.weight", ioChannels, ioChannels);
		main.add(residual(convolution1d(batchSize, ioChannels, ioChannels, audioSeqLen,
				1, 0, inputProjWeight, null)));

		// Reshape from [batch, channels, seq_len] to [batch, seq_len, channels]
		if (patchSize > 1) {
			main.add(layer("patchify",
					shape(1, ioChannels, audioSeqLen),
					shape(1, audioSeqLen / patchSize, ioChannels * patchSize),
					in -> reshape(shape(1, audioSeqLen / patchSize, ioChannels * patchSize), in)));
		} else {
			main.reshape(batchSize, ioChannels, audioSeqLen)
					.enumerate(1, 2, 1)
					.reshape(batchSize, audioSeqLen, ioChannels);
		}

		int seqLen = globalCondDim > 0 ? audioSeqLen + 1 : audioSeqLen;

		// Add the transformer blocks
		addTransformerBlocks(main, timestampEmbed,
				condEmbed, globalEmbed, embedDim, seqLen);

		// Remove any prepended conditioning tokens before output
		if (seqLen > audioSeqLen) {
			int prependedLength = seqLen - audioSeqLen;
			main.reshape(batchSize, seqLen, ioChannels)
					.subset(shape(batchSize, audioSeqLen, ioChannels), 0, prependedLength, 0);
		}

		// Reshape back to channels-first format
		if (patchSize > 1) {
			main.add(layer("unpatchify",
					shape(1, audioSeqLen / patchSize, ioChannels * patchSize),
					shape(1, embedDim, audioSeqLen),
					in -> reshape(shape(1, ioChannels, audioSeqLen), in)));
		} else {
			main.reshape(batchSize, audioSeqLen, ioChannels)
					.enumerate(1, 2, 1)
					.reshape(batchSize, ioChannels, audioSeqLen);
		}

		// Output projection
		PackedCollection outputProjWeight =
				createWeight("model.model.postprocess_conv.weight", ioChannels, ioChannels);
		main.add(residual(convolution1d(
				batchSize, ioChannels, ioChannels, audioSeqLen,
				1, 0, outputProjWeight, null)));

		return model;
	}

	protected Block timestampEmbedding() {
		PackedCollection timestepFeaturesWeight = createWeight("model.model.timestep_features.weight", 128, 1);
		PackedCollection timestampEmbeddingInWeight = createWeight("model.model.to_timestep_embed.0.weight", embedDim, 256);
		PackedCollection timestampEmbeddingInBias = createWeight("model.model.to_timestep_embed.0.bias", embedDim);
		PackedCollection timestampEmbeddingOutWeight = createWeight("model.model.to_timestep_embed.2.weight", embedDim, embedDim);
		PackedCollection timestampEmbeddingOutBias = createWeight("model.model.to_timestep_embed.2.bias", embedDim);

		return timestepEmbedding(batchSize, embedDim,
				timestepFeaturesWeight,
				timestampEmbeddingInWeight, timestampEmbeddingInBias,
				timestampEmbeddingOutWeight, timestampEmbeddingOutBias);
	}

	protected Block prependConditioning(Block timestampEmbed, Block globalEmbed) {
		PackedCollection timestep = new PackedCollection(timestampEmbed.getOutputShape());
		PackedCollection globalCond = new PackedCollection(globalEmbed.getOutputShape());

		timestampEmbed.andThen(into(timestep));
		globalEmbed.andThen(into(globalCond));

		return layer("prependConditioning",
				shape(batchSize, audioSeqLen, embedDim),
				shape(batchSize, audioSeqLen + 1, embedDim),
				in ->
						concat(1, add(cp(globalCond), cp(timestep)).reshape(batchSize, 1, embedDim), c(in)));
	}

	protected void addTransformerBlocks(SequentialBlock main,
										Block timestepEmbed,
										Block condEmbed,
										Block globalEmbed,
										int dim, int seqLen) {
		int dimHead = dim / numHeads;

		PackedCollection transformerProjectInWeight =
				createWeight("model.model.transformer.project_in.weight", dim, ioChannels * patchSize);
		PackedCollection transformerProjectOutWeight =
				createWeight("model.model.transformer.project_out.weight", ioChannels * patchSize, dim);
		PackedCollection invFreq =
				createWeight("model.model.transformer.rotary_pos_emb.inv_freq", dimHead / 4);

		// Input projection
		main.add(dense(transformerProjectInWeight));

		boolean hasCrossAttention = condTokenDim > 0 && condEmbed != null;

		if (globalCondDim > 0) {
			main.add(prependConditioning(timestepEmbed, globalEmbed));
		}

		// Capture state before transformer blocks for test validation
		preTransformerState = new PackedCollection(main.getOutputShape());
		main.branch().andThen(into(preTransformerState));

		for (int i = 0; i < depth; i++) {
			// Create and track all weights for this transformer block
			PackedCollection preNormWeight = createWeight("model.model.transformer.layers." + i + ".pre_norm.gamma", dim);
			PackedCollection preNormBias = createWeight("model.model.transformer.layers." + i + ".pre_norm.beta", dim);
			PackedCollection qkv = createWeight("model.model.transformer.layers." + i + ".self_attn.to_qkv.weight", dim * 3, dim);
			PackedCollection wo = createWeight("model.model.transformer.layers." + i + ".self_attn.to_out.weight", dim, dim);
			PackedCollection selfAttQNormWeight = createWeight("model.model.transformer.layers." + i + ".self_attn.q_norm.weight", dimHead);
			PackedCollection selfAttQNormBias = createWeight("model.model.transformer.layers." + i + ".self_attn.q_norm.bias", dimHead);
			PackedCollection selfAttKNormWeight = createWeight("model.model.transformer.layers." + i + ".self_attn.k_norm.weight", dimHead);
			PackedCollection selfAttKNormBias = createWeight("model.model.transformer.layers." + i + ".self_attn.k_norm.bias", dimHead);

			// Cross-attention weights (if needed)
			PackedCollection crossAttPreNormWeight = null;
			PackedCollection crossAttPreNormBias = null;
			PackedCollection crossWq = null;
			PackedCollection crossKv = null;
			PackedCollection crossWo = null;
			PackedCollection crossAttQNormWeight = null;
			PackedCollection crossAttQNormBias = null;
			PackedCollection crossAttKNormWeight = null;
			PackedCollection crossAttKNormBias = null;

			if (hasCrossAttention) {
				crossAttPreNormWeight = createWeight("model.model.transformer.layers." + i + ".cross_attend_norm.gamma", dim);
				crossAttPreNormBias = createWeight("model.model.transformer.layers." + i + ".cross_attend_norm.beta", dim);
				crossWq = createWeight("model.model.transformer.layers." + i + ".cross_attn.to_q.weight", dim, dim);
				crossKv = createWeight("model.model.transformer.layers." + i + ".cross_attn.to_kv.weight", 2 * dim, dim);
				crossWo = createWeight("model.model.transformer.layers." + i + ".cross_attn.to_out.weight", dim, dim);
				crossAttQNormWeight = createWeight("model.model.transformer.layers." + i + ".cross_attn.q_norm.weight", dimHead);
				crossAttQNormBias = createWeight("model.model.transformer.layers." + i + ".cross_attn.q_norm.bias", dimHead);
				crossAttKNormWeight = createWeight("model.model.transformer.layers." + i + ".cross_attn.k_norm.weight", dimHead);
				crossAttKNormBias = createWeight("model.model.transformer.layers." + i + ".cross_attn.k_norm.bias", dimHead);
			}

			int hiddenDim = dim * 4;
			PackedCollection ffnPreNormWeight = createWeight("model.model.transformer.layers." + i + ".ff_norm.gamma", dim);
			PackedCollection ffnPreNormBias = createWeight("model.model.transformer.layers." + i + ".ff_norm.beta", dim);
			PackedCollection w1 = createWeight("model.model.transformer.layers." + i + ".ff.ff.0.proj.weight", 2 * hiddenDim, dim);
			PackedCollection ffW1Bias = createWeight("model.model.transformer.layers." + i + ".ff.ff.0.proj.bias", 2 * hiddenDim);
			PackedCollection w2 = createWeight("model.model.transformer.layers." + i + ".ff.ff.2.weight", dim, hiddenDim);
			PackedCollection ffW2Bias = createWeight("model.model.transformer.layers." + i + ".ff.ff.2.bias", dim);

			Receptor<PackedCollection> attentionCapture = null;

			if (attentionScores != null) {
				PackedCollection scores = new PackedCollection(shape(batchSize, numHeads, seqLen, condSeqLen));
				attentionScores.put(i, scores);
				attentionCapture = into(scores);
			}

			// Add transformer block with updated sequence length
			main.add(transformerBlock(
					batchSize, dim, seqLen, numHeads,
					hasCrossAttention, condSeqLen, condEmbed,
					// Self-attention weights
					preNormWeight, preNormBias,
					qkv, wo,
					selfAttQNormWeight, selfAttQNormBias,
					selfAttKNormWeight, selfAttKNormBias,
					invFreq,
					// Cross-attention weights
					crossAttPreNormWeight, crossAttPreNormBias,
					crossWq, crossKv, crossWo,
					crossAttQNormWeight, crossAttQNormBias,
					crossAttKNormWeight, crossAttKNormBias,
					// Feed-forward weights
					ffnPreNormWeight, ffnPreNormBias,
					w1, w2, ffW1Bias, ffW2Bias,
					attentionCapture
			));
		}

		// Output projection
		main.add(dense(transformerProjectOutWeight));

		// Capture state after transformer blocks for test validation
		postTransformerState = new PackedCollection(main.getOutputShape());
		main.branch().andThen(into(postTransformerState));
	}

	@Override
	public PackedCollection forward(PackedCollection x, PackedCollection t,
									   PackedCollection crossAttnCond,
									   PackedCollection globalCond) {
		if (compiled == null) {
			validateWeights();

			if (enableProfile) {
				profile = new OperationProfileNode("dit");
				Hardware.getLocalHardware().assignProfile(profile);
			}

			long start = System.currentTimeMillis();
			compiled = model.compile(false, profile);
			log("Compiled DiffusionTransformer in " + (System.currentTimeMillis() - start) + "ms");
		}

		// Run the model with appropriate inputs
		if (condTokenDim > 0 && globalCondDim > 0) {
			return compiled.forward(x, t, crossAttnCond, globalCond);
		} else if (condTokenDim > 0) {
			return compiled.forward(x, t, crossAttnCond);
		} else if (globalCondDim > 0) {
			return compiled.forward(x, t, globalCond);
		} else {
			return compiled.forward(x, t);
		}
	}

	@Override
	public OperationProfile getProfile() { return profile; }

	@Override
	public Map<Integer, PackedCollection> getAttentionActivations() { return attentionScores; }

	public PackedCollection getPreTransformerState() { return preTransformerState; }
	public PackedCollection getPostTransformerState() { return postTransformerState; }

	@Override
	public void destroy() {
		if (stateDictionary != null) {
			stateDictionary.destroy();
		}

		if (model != null) {
			model.destroy();
		}

		if (compiled != null) {
			compiled.destroy();
		}
	}

	protected PackedCollection createWeight(String key, int... dims) {
		return createWeight(key, shape(dims));
	}

	protected PackedCollection createWeight(String key, TraversalPolicy expectedShape) {
		if (stateDictionary == null) {
			return new PackedCollection(expectedShape);
		} else if (!stateDictionary.containsKey(key)) {
			throw new IllegalArgumentException(key + " not found in StateDictionary");
		}

		PackedCollection weight = stateDictionary.get(key);

		// Verify shape compatibility
		if (!weight.getShape().trim().equalsIgnoreAxis(expectedShape.trim())) {
			if (weight.getShape().getTotalSizeLong() != expectedShape.getTotalSizeLong()) {
				throw new IllegalArgumentException("Expected " + expectedShape +
						" for key " + key + " while " + weight.getShape() + " was provided");
			} else {
				warn("Expected " + expectedShape + " for key " + key +
						" while " + weight.getShape() + " was provided");
			}
		}

		unusedWeights.remove(key);
		return weight.range(expectedShape);
	}

	protected void validateWeights() {
		unusedWeights.stream().findFirst().ifPresent(unusedWeight -> {
			throw new IllegalArgumentException(unusedWeight + " weights were not used by the model");
		});
	}
}