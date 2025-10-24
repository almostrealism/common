package org.almostrealism.ml.qwen3;

import java.nio.ByteBuffer;

/**
 * Configuration for Qwen3 models.
 *
 * Qwen3-4B-Instruct-2507 specifications:
 * - Layers: 36 transformer blocks
 * - Attention Heads: 32 query heads / 8 KV heads (GQA)
 * - Model Dimension: ~3584 (estimated from 4B params)
 * - FFN Hidden Dimension: ~11008 (estimated, typically 3x)
 * - Vocabulary Size: 151,669 tokens (byte-level BPE)
 * - Context Length: 128K (starting with 32K for initial implementation)
 * - Shared Embeddings: Yes (input/output embeddings shared in 4B model)
 * - RoPE Base Frequency: 1,000,000 (for extended context)
 */
public class Qwen3Config {
	/** Transformer embedding dimension */
	public final int dim;

	/** Feed-forward network hidden layer dimension */
	public final int hiddenDim;

	/** Number of transformer layers */
	public final int layerCount;

	/** Number of query attention heads */
	public final int headCount;

	/** Number of key/value attention heads (for Grouped Query Attention) */
	public final int kvHeadCount;

	/** Vocabulary size (byte-level BPE: 151,669) */
	public final int vocabSize;

	/** Maximum sequence length (context window) */
	public final int seqLen;

	/** Whether to share weights between input embeddings and output projection */
	public final boolean sharedWeights;

	/** Size of each attention head (computed as dim / headCount) */
	public final int headSize;

	/** RoPE base frequency for positional embeddings (1M for extended context) */
	public final double ropeTheta;

	/**
	 * Create Qwen3Config from a binary buffer.
	 * Reads configuration header from checkpoint file.
	 *
	 * Binary format:
	 * - int32: dim
	 * - int32: hiddenDim
	 * - int32: layerCount
	 * - int32: headCount
	 * - int32: kvHeadCount
	 * - int32: vocabSize (negative if sharedWeights=false)
	 * - int32: seqLen
	 * - (optional) double: ropeTheta
	 */
	public Qwen3Config(ByteBuffer buffer) {
		this.dim = buffer.getInt();
		this.hiddenDim = buffer.getInt();
		this.layerCount = buffer.getInt();
		this.headCount = buffer.getInt();
		this.kvHeadCount = buffer.getInt();

		int vocabSize = buffer.getInt();
		this.vocabSize = Math.abs(vocabSize);
		this.seqLen = buffer.getInt();

		// Shared weights indicated by positive vocabSize
		this.sharedWeights = vocabSize > 0;

		// Compute derived values
		this.headSize = dim / headCount;

		// RoPE theta - default to 1M for Qwen3, can be overridden in binary format
		if (buffer.remaining() >= 8) {
			this.ropeTheta = buffer.getDouble();
		} else {
			this.ropeTheta = 1000000.0;
		}
	}

	/**
	 * Create Qwen3Config with explicit parameters.
	 * Useful for testing or programmatic configuration.
	 */
	public Qwen3Config(int dim, int hiddenDim, int layerCount, int headCount,
	                   int kvHeadCount, int vocabSize, int seqLen) {
		this(dim, hiddenDim, layerCount, headCount, kvHeadCount,
		     vocabSize, seqLen, true, 1000000.0);
	}

	/**
	 * Create Qwen3Config with full control over all parameters.
	 */
	public Qwen3Config(int dim, int hiddenDim, int layerCount, int headCount,
	                   int kvHeadCount, int vocabSize, int seqLen,
	                   boolean sharedWeights, double ropeTheta) {
		this.dim = dim;
		this.hiddenDim = hiddenDim;
		this.layerCount = layerCount;
		this.headCount = headCount;
		this.kvHeadCount = kvHeadCount;
		this.vocabSize = vocabSize;
		this.seqLen = seqLen;
		this.sharedWeights = sharedWeights;
		this.headSize = dim / headCount;
		this.ropeTheta = ropeTheta;
	}

	/**
	 * Factory method to create configuration for Qwen3-4B-Instruct-2507.
	 * These values are based on the official model specifications.
	 *
	 * Note: Some dimensions (dim, hiddenDim) are estimated based on the 4B parameter count
	 * and may need adjustment when actual model weights are available.
	 */
	public static Qwen3Config qwen3_4B() {
		return new Qwen3Config(
			3584,      // dim (estimated)
			11008,     // hiddenDim (estimated ~3x dim)
			36,        // layerCount
			32,        // headCount
			8,         // kvHeadCount
			151669,    // vocabSize
			131072,    // seqLen (128K)
			true,      // sharedWeights
			1000000.0  // ropeTheta
		);
	}

	/**
	 * Factory method to create a smaller configuration for testing.
	 * Uses reduced dimensions but maintains architectural proportions.
	 */
	public static Qwen3Config qwen3_test() {
		// NOTE: Using headCount==kvHeadCount because GQA is not yet fully implemented
		return new Qwen3Config(
			512,       // dim (much smaller for testing)
			1536,      // hiddenDim (3x)
			4,         // layerCount (fewer layers)
			8,         // headCount
			8,         // kvHeadCount (same as headCount - no GQA for now)
			1000,      // vocabSize (smaller vocab)
			1024,      // seqLen (shorter context)
			true,      // sharedWeights
			10000.0    // ropeTheta (standard value)
		);
	}

	@Override
	public String toString() {
		return String.format(
			"Qwen3Config{dim=%d, hiddenDim=%d, layers=%d, heads=%d/%d, vocab=%d, seqLen=%d, " +
			"headSize=%d, shared=%b, ropeTheta=%.0f}",
			dim, hiddenDim, layerCount, headCount, kvHeadCount, vocabSize, seqLen,
			headSize, sharedWeights, ropeTheta
		);
	}

	/**
	 * Validate configuration parameters.
	 * Throws IllegalStateException if configuration is invalid.
	 */
	public void validate() {
		if (dim % headCount != 0) {
			throw new IllegalStateException(
				String.format("dim (%d) must be divisible by headCount (%d)", dim, headCount)
			);
		}
		if (headCount % kvHeadCount != 0) {
			throw new IllegalStateException(
				String.format("headCount (%d) must be divisible by kvHeadCount (%d)",
				              headCount, kvHeadCount)
			);
		}
		if (layerCount <= 0 || headCount <= 0 || kvHeadCount <= 0) {
			throw new IllegalStateException("Layer and head counts must be positive");
		}
		if (vocabSize <= 0 || seqLen <= 0) {
			throw new IllegalStateException("Vocabulary size and sequence length must be positive");
		}
	}

	/**
	 * Get the number of query heads per KV head group (for Grouped Query Attention).
	 */
	public int getHeadsPerKVGroup() {
		return headCount / kvHeadCount;
	}

	/**
	 * Get the total number of attention parameters per layer.
	 */
	public long getAttentionParams() {
		// Q, K, V, O projections
		return (long) dim * dim * 4;
	}

	/**
	 * Get the total number of FFN parameters per layer.
	 */
	public long getFfnParams() {
		// W1, W2, W3 projections
		return (long) dim * hiddenDim * 2 + (long) hiddenDim * dim;
	}

	/**
	 * Estimate total model parameters (approximate).
	 */
	public long estimateTotalParams() {
		long embedParams = (long) vocabSize * dim;
		long layerParams = (getAttentionParams() + getFfnParams() + dim * 3) * layerCount;
		long outputParams = sharedWeights ? 0 : (long) vocabSize * dim;

		return embedParams + layerParams + outputParams;
	}
}
