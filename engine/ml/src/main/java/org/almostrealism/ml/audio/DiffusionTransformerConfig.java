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

/**
 * The complete architectural configuration of a {@link DiffusionTransformer}: the tensor
 * dimensions of the model plus the optional features (conditioning mode, memory tokens, local
 * additive conditioning, timestep feature type) that distinguish one released checkpoint family
 * from another.
 *
 * <p>Instances are immutable; the {@code with...} methods return modified copies so a caller can
 * start from a base configuration and enable features one at a time:</p>
 * <pre>
 * DiffusionTransformerConfig config =
 *     new DiffusionTransformerConfig(256, 1024, 20, 16, 1, 768, 768, "rf_denoiser", latentLen, 257)
 *         .withConditioningMode(ConditioningMode.ADALN)
 *         .withMemoryTokens(64)
 *         .withLocalAddCondDim(257)
 *         .withTimestepFeatures(TimestepFeatures.EXPO);
 * </pre>
 */
public final class DiffusionTransformerConfig {

	/** Number of latent channels in and out. */
	private final int ioChannels;
	/** Transformer embedding dimension. */
	private final int embedDim;
	/** Number of transformer blocks. */
	private final int depth;
	/** Number of attention heads. */
	private final int numHeads;
	/** Patch size for patchify/unpatchify ({@code 1} = none). */
	private final int patchSize;
	/** Cross-attention conditioning token dimension ({@code 0} = no cross-attention). */
	private final int condTokenDim;
	/** Global conditioning vector dimension ({@code 0} = none). */
	private final int globalCondDim;
	/** Diffusion objective name (for example {@code rf_denoiser}). */
	private final String diffusionObjective;
	/** Latent sequence length. */
	private final int audioSeqLen;
	/** Cross-attention conditioning sequence length. */
	private final int condSeqLen;
	/** How timestep and global conditioning are injected. */
	private final ConditioningMode conditioningMode;
	/** Number of learned memory/register tokens ({@code 0} = none). */
	private final int numMemoryTokens;
	/** Channel count of the local additive conditioning input ({@code 0} = path absent). */
	private final int localAddCondDim;
	/** How the timestep is turned into Fourier features. */
	private final TimestepFeatures timestepFeatures;

	/**
	 * Creates a configuration with the default features: prepended conditioning, no memory tokens,
	 * no local additive conditioning and learned timestep features.
	 *
	 * @param ioChannels         number of latent channels in and out
	 * @param embedDim           transformer embedding dimension
	 * @param depth              number of transformer blocks
	 * @param numHeads           number of attention heads
	 * @param patchSize          patch size ({@code 1} = none)
	 * @param condTokenDim       cross-attention token dimension ({@code 0} = none)
	 * @param globalCondDim      global conditioning dimension ({@code 0} = none)
	 * @param diffusionObjective diffusion objective name
	 * @param audioSeqLen        latent sequence length
	 * @param condSeqLen         cross-attention conditioning sequence length
	 */
	public DiffusionTransformerConfig(int ioChannels, int embedDim, int depth, int numHeads,
									  int patchSize, int condTokenDim, int globalCondDim,
									  String diffusionObjective, int audioSeqLen, int condSeqLen) {
		this(ioChannels, embedDim, depth, numHeads, patchSize, condTokenDim, globalCondDim,
				diffusionObjective, audioSeqLen, condSeqLen,
				ConditioningMode.PREPEND, 0, 0, TimestepFeatures.LEARNED);
	}

	/**
	 * Creates a fully specified configuration; the {@code with...} methods route through here.
	 *
	 * @param ioChannels         number of latent channels in and out
	 * @param embedDim           transformer embedding dimension
	 * @param depth              number of transformer blocks
	 * @param numHeads           number of attention heads
	 * @param patchSize          patch size ({@code 1} = none)
	 * @param condTokenDim       cross-attention token dimension ({@code 0} = none)
	 * @param globalCondDim      global conditioning dimension ({@code 0} = none)
	 * @param diffusionObjective diffusion objective name
	 * @param audioSeqLen        latent sequence length
	 * @param condSeqLen         cross-attention conditioning sequence length
	 * @param conditioningMode   how timestep and global conditioning are injected
	 * @param numMemoryTokens    number of learned memory tokens
	 * @param localAddCondDim    channels of the local additive conditioning input
	 * @param timestepFeatures   how the timestep becomes Fourier features
	 */
	private DiffusionTransformerConfig(int ioChannels, int embedDim, int depth, int numHeads,
									   int patchSize, int condTokenDim, int globalCondDim,
									   String diffusionObjective, int audioSeqLen, int condSeqLen,
									   ConditioningMode conditioningMode, int numMemoryTokens,
									   int localAddCondDim, TimestepFeatures timestepFeatures) {
		if (ioChannels <= 0 || embedDim <= 0 || depth <= 0 || numHeads <= 0 || patchSize <= 0) {
			throw new IllegalArgumentException("ioChannels, embedDim, depth, numHeads and patchSize must be positive");
		}

		if (embedDim % numHeads != 0) {
			throw new IllegalArgumentException("embedDim " + embedDim + " is not divisible by numHeads " + numHeads);
		}

		if (numMemoryTokens < 0 || localAddCondDim < 0 || condTokenDim < 0 || globalCondDim < 0) {
			throw new IllegalArgumentException("Dimensions and token counts must not be negative");
		}

		this.ioChannels = ioChannels;
		this.embedDim = embedDim;
		this.depth = depth;
		this.numHeads = numHeads;
		this.patchSize = patchSize;
		this.condTokenDim = condTokenDim;
		this.globalCondDim = globalCondDim;
		this.diffusionObjective = diffusionObjective;
		this.audioSeqLen = audioSeqLen;
		this.condSeqLen = condSeqLen;
		this.conditioningMode = conditioningMode == null ? ConditioningMode.PREPEND : conditioningMode;
		this.numMemoryTokens = numMemoryTokens;
		this.localAddCondDim = localAddCondDim;
		this.timestepFeatures = timestepFeatures == null ? TimestepFeatures.LEARNED : timestepFeatures;
	}

	/**
	 * Returns a copy with the given conditioning mode.
	 *
	 * @param mode how timestep and global conditioning are injected
	 * @return the modified configuration
	 */
	public DiffusionTransformerConfig withConditioningMode(ConditioningMode mode) {
		return new DiffusionTransformerConfig(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective, audioSeqLen, condSeqLen,
				mode, numMemoryTokens, localAddCondDim, timestepFeatures);
	}

	/**
	 * Returns a copy with the given number of learned memory/register tokens.
	 *
	 * @param count number of memory tokens ({@code 0} disables them)
	 * @return the modified configuration
	 */
	public DiffusionTransformerConfig withMemoryTokens(int count) {
		return new DiffusionTransformerConfig(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective, audioSeqLen, condSeqLen,
				conditioningMode, count, localAddCondDim, timestepFeatures);
	}

	/**
	 * Returns a copy with a local additive conditioning input of the given channel count.
	 *
	 * @param dim channels of the per-position conditioning tensor ({@code 0} removes the path)
	 * @return the modified configuration
	 */
	public DiffusionTransformerConfig withLocalAddCondDim(int dim) {
		return new DiffusionTransformerConfig(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective, audioSeqLen, condSeqLen,
				conditioningMode, numMemoryTokens, dim, timestepFeatures);
	}

	/**
	 * Returns a copy with the given timestep feature type.
	 *
	 * @param features how the timestep becomes Fourier features
	 * @return the modified configuration
	 */
	public DiffusionTransformerConfig withTimestepFeatures(TimestepFeatures features) {
		return new DiffusionTransformerConfig(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective, audioSeqLen, condSeqLen,
				conditioningMode, numMemoryTokens, localAddCondDim, features);
	}

	/**
	 * Returns a copy with the given latent and conditioning sequence lengths.
	 *
	 * @param audioSeqLen latent sequence length
	 * @param condSeqLen  cross-attention conditioning sequence length
	 * @return the modified configuration
	 */
	public DiffusionTransformerConfig withSequenceLengths(int audioSeqLen, int condSeqLen) {
		return new DiffusionTransformerConfig(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective, audioSeqLen, condSeqLen,
				conditioningMode, numMemoryTokens, localAddCondDim, timestepFeatures);
	}

	/**
	 * Returns the number of latent channels in and out.
	 *
	 * @return the channel count
	 */
	public int getIoChannels() { return ioChannels; }

	/**
	 * Returns the transformer embedding dimension.
	 *
	 * @return the embedding dimension
	 */
	public int getEmbedDim() { return embedDim; }

	/**
	 * Returns the number of transformer blocks.
	 *
	 * @return the depth
	 */
	public int getDepth() { return depth; }

	/**
	 * Returns the number of attention heads.
	 *
	 * @return the head count
	 */
	public int getNumHeads() { return numHeads; }

	/**
	 * Returns the patch size ({@code 1} = none).
	 *
	 * @return the patch size
	 */
	public int getPatchSize() { return patchSize; }

	/**
	 * Returns the cross-attention token dimension ({@code 0} = none).
	 *
	 * @return the conditioning token dimension
	 */
	public int getCondTokenDim() { return condTokenDim; }

	/**
	 * Returns the global conditioning dimension ({@code 0} = none).
	 *
	 * @return the global conditioning dimension
	 */
	public int getGlobalCondDim() { return globalCondDim; }

	/**
	 * Returns the diffusion objective name.
	 *
	 * @return the objective
	 */
	public String getDiffusionObjective() { return diffusionObjective; }

	/**
	 * Returns the latent sequence length.
	 *
	 * @return the audio sequence length
	 */
	public int getAudioSeqLen() { return audioSeqLen; }

	/**
	 * Returns the cross-attention conditioning sequence length.
	 *
	 * @return the conditioning sequence length
	 */
	public int getCondSeqLen() { return condSeqLen; }

	/**
	 * Returns how timestep and global conditioning are injected.
	 *
	 * @return the conditioning mode
	 */
	public ConditioningMode getConditioningMode() { return conditioningMode; }

	/**
	 * Returns the number of learned memory tokens.
	 *
	 * @return the memory token count
	 */
	public int getNumMemoryTokens() { return numMemoryTokens; }

	/**
	 * Returns the channel count of the local additive conditioning input ({@code 0} = absent).
	 *
	 * @return the local conditioning dimension
	 */
	public int getLocalAddCondDim() { return localAddCondDim; }

	/**
	 * Returns how the timestep becomes Fourier features.
	 *
	 * @return the timestep feature type
	 */
	public TimestepFeatures getTimestepFeatures() { return timestepFeatures; }
}
