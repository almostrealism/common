/*
 * Copyright 2026 Michael Murray
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
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A transformer-based diffusion model designed for audio processing.
 *
 * <p>{@code DiffusionTransformer} implements a conditional diffusion architecture that
 * combines self-attention transformer blocks with optional cross-attention for
 * conditioning. The model uses rotary position embeddings (RoPE) and supports
 * configurable depth, attention heads, and patch sizes.
 *
 * <h2>Architecture Overview</h2>
 * <ol>
 *   <li><b>Input Preprocessing</b>: 1D convolution projection with optional patchification</li>
 *   <li><b>Conditioning</b>: Timestep embeddings (Fourier features), optional cross-attention
 *       conditioning, and optional global conditioning - combined via prepending</li>
 *   <li><b>Transformer Blocks</b>: {@code depth} layers of self-attention + optional
 *       cross-attention + feed-forward networks with QK-normalization</li>
 *   <li><b>Output Postprocessing</b>: Unpatchification and projection back to audio channels</li>
 * </ol>
 *
 * <h2>Key Parameters</h2>
 * <ul>
 *   <li>{@code ioChannels} - Number of input/output audio channels</li>
 *   <li>{@code embedDim} - Embedding dimension for transformer blocks</li>
 *   <li>{@code depth} - Number of transformer layers</li>
 *   <li>{@code numHeads} - Number of attention heads</li>
 *   <li>{@code patchSize} - Patch size for patchify/unpatchify (1 = no patching)</li>
 *   <li>{@code condTokenDim} - Conditioning token dimension (0 = no cross-attention)</li>
 *   <li>{@code globalCondDim} - Global condition dimension (0 = no global conditioning)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * StateDictionary weights = new StateDictionary(weightsDir);
 * DiffusionTransformer model = new DiffusionTransformer(
 *     64,    // ioChannels
 *     1536,  // embedDim
 *     24,    // depth
 *     24,    // numHeads
 *     1,     // patchSize
 *     768,   // condTokenDim
 *     1536,  // globalCondDim
 *     "predict_noise",
 *     weights
 * );
 *
 * // Forward pass
 * PackedCollection output = model.forward(audioInput, timestep, crossAttnCond, globalCond);
 * }</pre>
 *
 * <h2>Conditioning Approach</h2>
 * This model uses <b>prepended conditioning</b> rather than adaptive layer normalization
 * (AdaLayerNorm). Timestep and global conditioning are projected and prepended as
 * extra tokens to the sequence before the transformer blocks. See
 * {@link #prependConditioning(Block, Block)} for implementation details.
 *
 * <h2>LoRA Fine-Tuning</h2>
 * For parameter-efficient fine-tuning, use {@link LoRADiffusionTransformer} which
 * extends this class with {@link org.almostrealism.layers.LowRankAdapterSupport}.
 *
 * @see DiffusionModel
 * @see DiffusionTransformerFeatures
 * @see LoRADiffusionTransformer
 * @see org.almostrealism.layers.LowRankAdapterSupport
 */
public class DiffusionTransformer implements DiffusionModel, DiffusionTransformerFeatures {
	/** Default audio sample size (524288 samples, ~11.9 s at 44.1 kHz). */
	private static final int SAMPLE_SIZE = 524288;

	/** Default downsampling ratio from audio samples to latent patches. */
	private static final int DOWNSAMPLING_RATIO = 2048;

	/** Whether to collect operation profiling data during compilation. */
	public static boolean enableProfile = false;

	/** Batch size used for all model input/output shapes. */
	public static int batchSize = 1;

	/** Number of input and output audio channels (e.g., 64 for latent stereo). */
	private final int ioChannels;

	/** Transformer embedding dimension. */
	private final int embedDim;

	/** Number of transformer blocks. */
	private final int depth;

	/** Number of self-attention heads. */
	private final int numHeads;

	/** Patch size for splitting the audio sequence before embedding. */
	private final int patchSize;

	/** Dimension of cross-attention conditioning tokens (0 disables cross-attention). */
	private final int condTokenDim;

	/** Dimension of the global conditioning vector (0 disables global conditioning). */
	private final int globalCondDim;

	/** Number of patches in the audio sequence (audio length divided by patch size). */
	private final int audioSeqLen;

	/** Number of conditioning tokens in the cross-attention sequence. */
	private final int condSeqLen;

	/** Pre-trained weights accessed by key. */
	private final StateDictionary stateDictionary;

	/** Weight keys that have not been accessed; populated at construction for diagnostics. */
	private final Set<String> unusedWeights;

	/** Lazily-built transformer {@link Model}. */
	private Model model;

	/** Optional operation profile for timing compiled kernels. */
	private OperationProfile profile;

	/** Compiled inference model; may also serve as the training model when backward pass is needed. */
	protected CompiledModel compiled;

	/** Captures the transformer input state for debugging; null when capture is disabled. */
	private PackedCollection preTransformerState, postTransformerState;

	/** Per-layer attention score tensors captured during forward pass; null when capture is disabled. */
	private Map<Integer, PackedCollection> attentionScores;

	/**
	 * Constructs a DiffusionTransformer with default sequence lengths and attention capture disabled.
	 *
	 * @param ioChannels          Number of input/output channels
	 * @param embedDim            Transformer embedding dimension
	 * @param depth               Number of transformer blocks
	 * @param numHeads            Number of self-attention heads
	 * @param patchSize           Patch size for the audio sequence
	 * @param condTokenDim        Cross-attention conditioning token dimension (0 to disable)
	 * @param globalCondDim       Global conditioning vector dimension (0 to disable)
	 * @param diffusionObjective  Training objective, e.g. {@code "v_prediction"}
	 * @param stateDictionary     Pre-trained weight store
	 */
	public DiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
								int patchSize, int condTokenDim, int globalCondDim,
								String diffusionObjective, StateDictionary stateDictionary) {
		this(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				stateDictionary, false);
	}

	/**
	 * Constructs a DiffusionTransformer with default sequence lengths.
	 *
	 * @param ioChannels            Number of input/output channels
	 * @param embedDim              Transformer embedding dimension
	 * @param depth                 Number of transformer blocks
	 * @param numHeads              Number of self-attention heads
	 * @param patchSize             Patch size for the audio sequence
	 * @param condTokenDim          Cross-attention conditioning token dimension (0 to disable)
	 * @param globalCondDim         Global conditioning vector dimension (0 to disable)
	 * @param diffusionObjective    Training objective
	 * @param stateDictionary       Pre-trained weight store
	 * @param captureAttentionScores Whether to capture per-layer attention weights for debugging
	 */
	public DiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
								int patchSize, int condTokenDim, int globalCondDim,
								String diffusionObjective, StateDictionary stateDictionary,
								boolean captureAttentionScores) {
		this(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				SAMPLE_SIZE / DOWNSAMPLING_RATIO, 65,
				stateDictionary, captureAttentionScores);
	}

	/**
	 * Full constructor for a DiffusionTransformer with explicit sequence lengths.
	 *
	 * @param ioChannels            Number of input/output channels
	 * @param embedDim              Transformer embedding dimension
	 * @param depth                 Number of transformer blocks
	 * @param numHeads              Number of self-attention heads
	 * @param patchSize             Patch size for the audio sequence
	 * @param condTokenDim          Cross-attention conditioning token dimension (0 to disable)
	 * @param globalCondDim         Global conditioning vector dimension (0 to disable)
	 * @param diffusionObjective    Training objective (e.g., {@code "v_prediction"})
	 * @param audioSeqLen           Number of patches in the audio sequence
	 * @param condSeqLen            Number of tokens in the conditioning sequence
	 * @param stateDictionary       Pre-trained weight store
	 * @param captureAttentionScores Whether to capture per-layer attention weights for debugging
	 */
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
		// Model is built lazily in getModel() to allow subclass fields to initialize first
	}

	/**
	 * Constructs the full AR {@link Model} for this diffusion transformer.
	 *
	 * <p>This method is called lazily from {@link #getModel()} to allow subclass fields
	 * (e.g., LoRA layers) to be initialized before model construction begins.</p>
	 *
	 * @return The assembled transformer model
	 */
	protected Model buildModel() {
		// Create model with input shape - [batch, channels, sequence_length]
		Model model = new Model(shape(batchSize, ioChannels, audioSeqLen));

		// Add timestep embedding input
		Block timestampEmbed = timestampEmbedding();
		model.addInput(timestampEmbed);

		// Add cross-attention condition input if needed
		SequentialBlock condEmbed = null;
		if (condTokenDim > 0 && condSeqLen > 0) {
			PackedCollection condProjWeight1 = createWeight("model.model.to_cond_embed.0.weight", embedDim, condTokenDim);
			PackedCollection condProjWeight2 = createWeight("model.model.to_cond_embed.2.weight", embedDim, embedDim);

			condEmbed = new SequentialBlock(shape(condSeqLen, condTokenDim));
			condEmbed.add(dense(condProjWeight1));
			condEmbed.add(silu());
			condEmbed.add(dense(condProjWeight2));
			condEmbed.reshape(batchSize, condSeqLen, embedDim);
			model.addInput(condEmbed);
		} else if (condTokenDim > 0) {
			// Cross-attention conditioning weights exist in the StateDictionary but
			// are not used when condSeqLen is 0 - mark them as expected-unused
			unusedWeights.remove("model.model.to_cond_embed.0.weight");
			unusedWeights.remove("model.model.to_cond_embed.2.weight");
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

	/**
	 * Builds the timestep embedding input block that maps a scalar timestep to an embedding vector.
	 *
	 * @return A block that takes a scalar diffusion timestep and returns an embedding of size {@link #embedDim}
	 */
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

	/**
	 * Builds a block that prepends a combined timestep+global conditioning token to the sequence.
	 *
	 * @param timestampEmbed Block producing the timestep embedding
	 * @param globalEmbed    Block producing the global conditioning embedding
	 * @return Block that prepends the summed conditioning token to the audio sequence
	 */
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

	/**
	 * Appends the full stack of transformer blocks to the given sequential block.
	 *
	 * @param main          The sequential block to append layers to
	 * @param timestepEmbed Block providing the timestep embedding input
	 * @param condEmbed     Block providing cross-attention conditioning (may be null)
	 * @param globalEmbed   Block providing global conditioning (may be null)
	 * @param dim           Embedding dimension
	 * @param seqLen        Full sequence length including any prepended conditioning tokens
	 */
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

		// When cross-attention is disabled, mark those weight keys as expected-unused
		// so that validateWeights() does not reject them
		if (!hasCrossAttention) {
			for (int i = 0; i < depth; i++) {
				String prefix = "model.model.transformer.layers." + i;
				unusedWeights.remove(prefix + ".cross_attend_norm.gamma");
				unusedWeights.remove(prefix + ".cross_attend_norm.beta");
				unusedWeights.remove(prefix + ".cross_attn.to_q.weight");
				unusedWeights.remove(prefix + ".cross_attn.to_kv.weight");
				unusedWeights.remove(prefix + ".cross_attn.to_out.weight");
				unusedWeights.remove(prefix + ".cross_attn.q_norm.weight");
				unusedWeights.remove(prefix + ".cross_attn.q_norm.bias");
				unusedWeights.remove(prefix + ".cross_attn.k_norm.weight");
				unusedWeights.remove(prefix + ".cross_attn.k_norm.bias");
			}
		}

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

			if (attentionScores != null && hasCrossAttention) {
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
					attentionCapture, getProjectionFactory()
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
			if (enableProfile) {
				profile = new OperationProfileNode("dit");
				Hardware.getLocalHardware().assignProfile(profile);
			}

			long start = System.currentTimeMillis();
			// getModel() triggers buildModel() which consumes weights via createWeight(),
			// so validateWeights() must come after getModel()
			Model m = getModel();
			validateWeights();
			compiled = m.compile(false, profile);
			log("Compiled DiffusionTransformer in " + (System.currentTimeMillis() - start) + "ms");
		}

		// The argument order must match what buildModel() added as model inputs:
		// cross-attention is only present when condSeqLen > 0 (not just condTokenDim > 0)
		boolean hasCrossAttn = condTokenDim > 0 && condSeqLen > 0;

		// Provide a zero tensor for null conditioning when the model expects the input
		if (globalCondDim > 0 && globalCond == null) {
			globalCond = new PackedCollection(globalCondDim);
		}

		if (hasCrossAttn && globalCondDim > 0) {
			return compiled.forward(x, t, crossAttnCond, globalCond);
		} else if (hasCrossAttn) {
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

	/**
	 * Returns the captured pre-transformer (post input-projection) state tensor.
	 * Only populated when state capture is enabled.
	 *
	 * @return Pre-transformer state, or {@code null} if capture is disabled
	 */
	public PackedCollection getPreTransformerState() { return preTransformerState; }

	/**
	 * Returns the captured post-transformer (pre output-projection) state tensor.
	 * Only populated when state capture is enabled.
	 *
	 * @return Post-transformer state, or {@code null} if capture is disabled
	 */
	public PackedCollection getPostTransformerState() { return postTransformerState; }

	/**
	 * Sets the pre-transformer state tensor (for use by subclasses or debugging hooks).
	 *
	 * @param state The state tensor to store
	 */
	protected void setPreTransformerState(PackedCollection state) { this.preTransformerState = state; }

	/**
	 * Sets the post-transformer state tensor (for use by subclasses or debugging hooks).
	 *
	 * @param state The state tensor to store
	 */
	protected void setPostTransformerState(PackedCollection state) { this.postTransformerState = state; }

	// Protected getters for subclass access
	protected int getIoChannels() { return ioChannels; }
	protected int getEmbedDim() { return embedDim; }
	protected int getDepth() { return depth; }
	protected int getNumHeads() { return numHeads; }
	protected int getPatchSize() { return patchSize; }
	protected int getCondTokenDim() { return condTokenDim; }
	protected int getGlobalCondDim() { return globalCondDim; }
	protected int getAudioSeqLen() { return audioSeqLen; }
	protected int getCondSeqLen() { return condSeqLen; }
	protected int getBatchSize() { return batchSize; }
	protected Map<Integer, PackedCollection> getAttentionScores() { return attentionScores; }

	/**
	 * Returns the model, building it lazily if not yet built.
	 *
	 * <p>The model is built lazily to allow subclass fields to be initialized
	 * before buildModel() is called. This enables subclasses to customize
	 * model building without resorting to workarounds like ThreadLocals.</p>
	 *
	 * @return The built model
	 */
	protected Model getModel() {
		if (model == null) {
			model = buildModel();
		}
		return model;
	}

	/**
	 * Returns the projection factory to use when building transformer blocks.
	 *
	 * <p>Override this method to customize how projection layers are created.
	 * For example, subclasses can return a LoRA-enabled factory to add adapters
	 * to attention projections.</p>
	 *
	 * @return The projection factory (default is standard dense layers)
	 */
	public ProjectionFactory getProjectionFactory() {
		return ProjectionFactory.dense();
	}

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

	/**
	 * Retrieves a weight tensor from the state dictionary with shape validation.
	 *
	 * @param key  Weight key in the state dictionary
	 * @param dims Expected tensor dimensions
	 * @return The weight tensor, wrapped in a range view matching the expected shape
	 */
	protected PackedCollection createWeight(String key, int... dims) {
		return createWeight(key, shape(dims));
	}

	/**
	 * Retrieves a weight tensor from the state dictionary with shape validation.
	 *
	 * @param key           Weight key in the state dictionary
	 * @param expectedShape Expected tensor shape
	 * @return The weight tensor, wrapped in a range view matching the expected shape
	 */
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

	/**
	 * Validates that all weights from the state dictionary were actually consumed during model construction.
	 *
	 * @throws IllegalArgumentException if any weight key was loaded but not referenced during model building
	 */
	protected void validateWeights() {
		unusedWeights.stream().findFirst().ifPresent(unusedWeight -> {
			throw new IllegalArgumentException(unusedWeight + " weights were not used by the model");
		});
	}
}