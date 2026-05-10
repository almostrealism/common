package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.TransformerConfig;

import java.nio.ByteBuffer;

/**
 * Configuration class for Qwen3 language models.
 *
 * <p>This class holds all architectural parameters needed to construct a Qwen3 model.
 * Configuration can be loaded from a binary checkpoint header, constructed explicitly,
 * or created using factory methods for standard model sizes.</p>
 *
 * <h2>Qwen3-4B-Instruct Architecture</h2>
 * <table>
 * <caption>Model Configuration Parameters</caption>
 *   <tr><th>Parameter</th><th>Value</th><th>Description</th></tr>
 *   <tr><td>dim</td><td>3584</td><td>Transformer embedding dimension</td></tr>
 *   <tr><td>hiddenDim</td><td>11008</td><td>FFN hidden layer dimension (~3x dim)</td></tr>
 *   <tr><td>layerCount</td><td>36</td><td>Number of transformer blocks</td></tr>
 *   <tr><td>headCount</td><td>32</td><td>Query attention heads</td></tr>
 *   <tr><td>kvHeadCount</td><td>8</td><td>Key/value heads for GQA</td></tr>
 *   <tr><td>vocabSize</td><td>151,669</td><td>Byte-level BPE vocabulary</td></tr>
 *   <tr><td>seqLen</td><td>131,072</td><td>Maximum context length (128K)</td></tr>
 *   <tr><td>ropeTheta</td><td>1,000,000</td><td>RoPE base frequency</td></tr>
 * </table>
 *
 * <h2>Binary Format</h2>
 * <p>When loading from a binary checkpoint, the header format is:</p>
 * <pre>
 * int32: dim
 * int32: hiddenDim
 * int32: layerCount
 * int32: headCount
 * int32: kvHeadCount
 * int32: vocabSize (negative if sharedWeights=false)
 * int32: seqLen
 * double: ropeTheta (optional)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Factory method for standard 4B model
 * Qwen3Config config = Qwen3Config.qwen3_4B();
 *
 * // Explicit construction
 * Qwen3Config custom = new Qwen3Config(
 *     3584, 11008, 36, 32, 8, 151669, 131072, true, 1000000.0
 * );
 *
 * // Test configuration (smaller model)
 * Qwen3Config test = Qwen3Config.qwen3_test();
 *
 * // Validate configuration
 * config.validate();  // Throws if invalid
 * }</pre>
 *
 * @see Qwen3
 * @see TransformerConfig
 * @author Michael Murray
 */
public class Qwen3Config extends TransformerConfig {

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
		super(buffer);
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
		super(dim, hiddenDim, layerCount, headCount, kvHeadCount, vocabSize, seqLen, sharedWeights);
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
