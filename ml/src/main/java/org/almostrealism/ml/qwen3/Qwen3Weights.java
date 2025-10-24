package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;

import java.nio.FloatBuffer;

/**
 * Weights for Qwen3 models.
 *
 * @deprecated This class is deprecated. Use {@link StateDictionary} directly instead.
 * This wrapper class adds unnecessary indirection and storage duplication.
 * Access weights directly from StateDictionary using HuggingFace key names
 * (e.g., "model.layers.0.self_attn.q_proj.weight").
 *
 * <p>This class is retained for backward compatibility with existing tests
 * but will be removed in a future version. New code should use StateDictionary directly.</p>
 *
 * <p>Key differences from Llama2:
 * - Adds QK-Norm weights for query and key normalization
 * - Larger vocabulary size (151,669 tokens)
 * - No biases in QKV projections
 * - 36 layers with GQA (32 query heads / 8 KV heads)</p>
 */
@Deprecated
public class Qwen3Weights implements CodeFeatures {
	// Token embedding table
	public final PackedCollection<?> tokenEmbeddings; // (vocabSize, dim)

	// Weights for RMS norms
	public final PackedCollection<?> rmsAttWeights; // (layerCount, dim)
	public final PackedCollection<?> rmsFfn; // (layerCount, dim)
	public final PackedCollection<?> rmsFinalWeight; // (dim)

	// Attention projection weights (no biases in Qwen3)
	public final PackedCollection<?> wq; // (layerCount, dim, dim)
	public final PackedCollection<?> wk; // (layerCount, dim, kvDim)
	public final PackedCollection<?> wv; // (layerCount, dim, kvDim)
	public final PackedCollection<?> wo; // (layerCount, dim, dim)

	// QK-Norm weights (key difference from Llama2)
	public final PackedCollection<?> qkNormQ; // (layerCount, headCount, headSize)
	public final PackedCollection<?> qkNormK; // (layerCount, kvHeadCount, headSize)

	// FFN weights
	public final PackedCollection<?> w1; // (layerCount, hiddenDim, dim) - gate projection
	public final PackedCollection<?> w2; // (layerCount, dim, hiddenDim) - down projection
	public final PackedCollection<?> w3; // (layerCount, hiddenDim, dim) - up projection

	// RoPE frequency embeddings
	public final PackedCollection<?> freqCis; // (seqLen, headSize/2, 2)

	// Classifier weights for output logits
	public final PackedCollection<?> wcls; // (vocabSize, dim) or shared with tokenEmbeddings

	/**
	 * Load weights from StateDictionary (protobuf format from Hugging Face).
	 * This constructor maps Hugging Face Qwen3 weight keys to AR framework format.
	 *
	 * @param config Qwen3 configuration specifying model dimensions
	 * @param stateDict StateDictionary containing model weights
	 */
	public Qwen3Weights(Qwen3Config config, StateDictionary stateDict) {
		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		// Token embeddings
		this.tokenEmbeddings = getWeight(stateDict, "model.embed_tokens.weight",
				config.vocabSize, config.dim);

		// Collect layer weights
		this.rmsAttWeights = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".input_layernorm.weight",
				config.dim);

		this.rmsFfn = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".post_attention_layernorm.weight",
				config.dim);

		// Attention projection weights
		this.wq = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".self_attn.q_proj.weight",
				config.dim, config.dim);

		this.wk = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".self_attn.k_proj.weight",
				kvDim, config.dim);

		this.wv = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".self_attn.v_proj.weight",
				kvDim, config.dim);

		this.wo = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".self_attn.o_proj.weight",
				config.dim, config.dim);

		// QK-Norm weights
		this.qkNormQ = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".self_attn.q_norm.weight",
				config.headCount, config.headSize);

		this.qkNormK = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".self_attn.k_norm.weight",
				config.kvHeadCount, config.headSize);

		// FFN weights
		this.w1 = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".mlp.gate_proj.weight",
				config.hiddenDim, config.dim);

		this.w2 = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".mlp.down_proj.weight",
				config.dim, config.hiddenDim);

		this.w3 = collectLayerWeights(stateDict, config.layerCount,
				i -> "model.layers." + i + ".mlp.up_proj.weight",
				config.hiddenDim, config.dim);

		// Final RMS norm
		this.rmsFinalWeight = getWeight(stateDict, "model.norm.weight", config.dim);

		// RoPE frequency embeddings - compute from config if not in state dict
		this.freqCis = computeRopeFreqs(config);

		// Classifier weights
		if (config.sharedWeights) {
			this.wcls = tokenEmbeddings;
		} else {
			this.wcls = getWeight(stateDict, "lm_head.weight",
					config.vocabSize, config.dim);
		}
	}

	/**
	 * Load weights from a binary checkpoint file.
	 *
	 * @deprecated Binary checkpoint format is deprecated in favor of StateDictionary.
	 * Use the StateDictionary constructor instead. This constructor has known bugs
	 * (transposed K/V weights) and will be removed in a future version.
	 *
	 * <p>The FloatBuffer should be positioned after the config header.</p>
	 *
	 * @param config Qwen3 configuration specifying model dimensions
	 * @param buffer FloatBuffer containing the weight data
	 */
	@Deprecated
	public Qwen3Weights(Qwen3Config config, FloatBuffer buffer) {
		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		// Token embeddings
		this.tokenEmbeddings =
				pack(take(buffer, config.vocabSize, config.dim))
				.reshape(shape(config.vocabSize, config.dim));

		// Attention RMS norm weights (pre-attention normalization)
		this.rmsAttWeights =
				pack(take(buffer, config.layerCount, config.dim))
				.reshape(shape(config.layerCount, config.dim));

		// Attention projection weights
		// Query: full dimension for all query heads
		this.wq = pack(take(buffer, config.layerCount, config.dim, config.dim))
				.reshape(shape(config.layerCount, config.dim, config.dim));

		// Key/Value: reduced dimension for GQA (8 KV heads vs 32 query heads)
		this.wk = pack(take(buffer, config.layerCount, config.dim, kvDim))
				.reshape(shape(config.layerCount, config.dim, kvDim));
		this.wv = pack(take(buffer, config.layerCount, config.dim, kvDim))
				.reshape(shape(config.layerCount, config.dim, kvDim));

		// Output projection: back to full dimension
		this.wo = pack(take(buffer, config.layerCount, config.dim, config.dim))
				.reshape(shape(config.layerCount, config.dim, config.dim));

		// QK-Norm weights (RMSNorm parameters for Q and K normalization)
		// One set of norm weights per head per layer
		this.qkNormQ = pack(take(buffer, config.layerCount, config.headCount, config.headSize))
				.reshape(shape(config.layerCount, config.headCount, config.headSize));
		this.qkNormK = pack(take(buffer, config.layerCount, config.kvHeadCount, config.headSize))
				.reshape(shape(config.layerCount, config.kvHeadCount, config.headSize));

		// FFN RMS norm weights (pre-FFN normalization)
		this.rmsFfn = pack(take(buffer, config.layerCount, config.dim))
				.reshape(shape(config.layerCount, config.dim));

		// FFN weights (SwiGLU: w1 is gate, w3 is up, w2 is down)
		this.w1 = pack(take(buffer, config.layerCount, config.hiddenDim, config.dim))
				.reshape(shape(config.layerCount, config.hiddenDim, config.dim));
		this.w2 = pack(take(buffer, config.layerCount, config.dim, config.hiddenDim))
				.reshape(shape(config.layerCount, config.dim, config.hiddenDim));
		this.w3 = pack(take(buffer, config.layerCount, config.hiddenDim, config.dim))
				.reshape(shape(config.layerCount, config.hiddenDim, config.dim));

		// Final RMS norm (post-transformer normalization)
		this.rmsFinalWeight =
				pack(take(buffer, config.dim))
				.reshape(shape(config.dim));

		// RoPE frequency embeddings (complex numbers: real and imaginary)
		this.freqCis = packComplex(
				take(buffer, config.seqLen, config.headSize / 2),
				take(buffer, config.seqLen, config.headSize / 2),
				shape(config.seqLen, config.headSize / 2, 2));

		// Classifier weights: shared with token embeddings if config specifies
		this.wcls = config.sharedWeights ? tokenEmbeddings :
				pack(take(buffer, config.vocabSize, config.dim))
				.reshape(shape(config.vocabSize, config.dim));
	}

	/**
	 * Read a sequence of floats from the buffer and return as array.
	 * The total number of floats is the product of all dimensions.
	 */
	static float[] take(FloatBuffer buffer, int... dims) {
		TraversalPolicy shape = new TraversalPolicy(dims);
		float[] floats = new float[shape.getTotalSize()];
		buffer.get(floats);
		return floats;
	}

	/**
	 * Pack complex numbers (real and imaginary components) into a PackedCollection.
	 * Used for RoPE frequency embeddings.
	 *
	 * @param real Real components
	 * @param imag Imaginary components
	 * @param shape Shape of the result, last dimension must be 2 for (real, imag) pairs
	 * @return PackedCollection containing interleaved real and imaginary values
	 */
	static PackedCollection<?> packComplex(float real[], float imag[], TraversalPolicy shape) {
		if (shape.length(shape.getDimensions() - 1) != 2)
			throw new IllegalArgumentException("Last dimension must be 2 for complex numbers");

		double data[] = new double[shape.getTotalSize()];
		for (int i = 0; i < data.length; i += 2) {
			data[i] = real[i / 2];
			data[i + 1] = imag[i / 2];
		}

		PackedCollection<?> c = new PackedCollection<>(shape);
		c.setMem(0, data, 0, shape.getTotalSize());
		return c;
	}

	/**
	 * Get memory footprint estimate in bytes.
	 */
	public long getMemoryFootprint(Qwen3Config config) {
		// Each float is 4 bytes
		return config.estimateTotalParams() * 4;
	}

	/**
	 * Validate that all weights are non-null and have expected shapes.
	 * Useful for debugging weight loading issues.
	 */
	public void validate(Qwen3Config config) {
		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		validateShape(tokenEmbeddings, "tokenEmbeddings", config.vocabSize, config.dim);
		validateShape(rmsAttWeights, "rmsAttWeights", config.layerCount, config.dim);
		validateShape(wq, "wq", config.layerCount, config.dim, config.dim);
		// Note: wk and wv are in dense layer format (output, input) = (kvDim, dim)
		validateShape(wk, "wk", config.layerCount, kvDim, config.dim);
		validateShape(wv, "wv", config.layerCount, kvDim, config.dim);
		validateShape(wo, "wo", config.layerCount, config.dim, config.dim);
		validateShape(qkNormQ, "qkNormQ", config.layerCount, config.headCount, config.headSize);
		validateShape(qkNormK, "qkNormK", config.layerCount, config.kvHeadCount, config.headSize);
		validateShape(rmsFfn, "rmsFfn", config.layerCount, config.dim);
		validateShape(w1, "w1", config.layerCount, config.hiddenDim, config.dim);
		validateShape(w2, "w2", config.layerCount, config.dim, config.hiddenDim);
		validateShape(w3, "w3", config.layerCount, config.hiddenDim, config.dim);
		validateShape(rmsFinalWeight, "rmsFinalWeight", config.dim);
		validateShape(freqCis, "freqCis", config.seqLen, config.headSize / 2, 2);

		if (!config.sharedWeights && wcls != null) {
			validateShape(wcls, "wcls", config.vocabSize, config.dim);
		}
	}

	private void validateShape(PackedCollection<?> weights, String name, int... expectedDims) {
		if (weights == null) {
			throw new IllegalStateException(name + " is null");
		}

		TraversalPolicy shape = weights.getShape();
		if (shape.getDimensions() != expectedDims.length) {
			throw new IllegalStateException(
				String.format("%s has %d dimensions, expected %d",
					name, shape.getDimensions(), expectedDims.length));
		}

		for (int i = 0; i < expectedDims.length; i++) {
			if (shape.length(i) != expectedDims[i]) {
				throw new IllegalStateException(
					String.format("%s dimension %d is %d, expected %d",
						name, i, shape.length(i), expectedDims[i]));
			}
		}
	}

	/**
	 * Package-private constructor for testing with synthetic weights.
	 * Allows direct construction with pre-created PackedCollections.
	 */
	Qwen3Weights(PackedCollection<?> tokenEmbeddings,
				 PackedCollection<?> rmsAttWeights,
				 PackedCollection<?> wq, PackedCollection<?> wk,
				 PackedCollection<?> wv, PackedCollection<?> wo,
				 PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,
				 PackedCollection<?> rmsFfn,
				 PackedCollection<?> w1, PackedCollection<?> w2, PackedCollection<?> w3,
				 PackedCollection<?> rmsFinalWeight,
				 PackedCollection<?> freqCis,
				 PackedCollection<?> wcls) {
		this.tokenEmbeddings = tokenEmbeddings;
		this.rmsAttWeights = rmsAttWeights;
		this.wq = wq;
		this.wk = wk;
		this.wv = wv;
		this.wo = wo;
		this.qkNormQ = qkNormQ;
		this.qkNormK = qkNormK;
		this.rmsFfn = rmsFfn;
		this.w1 = w1;
		this.w2 = w2;
		this.w3 = w3;
		this.rmsFinalWeight = rmsFinalWeight;
		this.freqCis = freqCis;
		this.wcls = wcls;
	}

	/**
	 * Helper: Get a weight from StateDictionary with shape validation.
	 */
	private static PackedCollection<?> getWeight(StateDictionary stateDict, String key, int... expectedDims) {
		if (!stateDict.containsKey(key)) {
			throw new IllegalArgumentException("Weight not found in StateDictionary: " + key);
		}

		PackedCollection<?> weight = stateDict.get(key);
		TraversalPolicy expectedShape = new TraversalPolicy(expectedDims);

		// Validate shape
		if (!weight.getShape().equalsIgnoreAxis(expectedShape)) {
			// Try to reshape if total size matches
			if (weight.getShape().getTotalSizeLong() == expectedShape.getTotalSizeLong()) {
				System.out.println("Warning: Reshaping " + key + " from " +
						weight.getShape() + " to " + expectedShape);
				return weight.reshape(expectedShape);
			} else {
				throw new IllegalArgumentException(
						String.format("Shape mismatch for %s: expected %s, got %s",
								key, expectedShape, weight.getShape()));
			}
		}

		return weight;
	}

	/**
	 * Helper: Collect weights from multiple layers into a single PackedCollection.
	 * Uses a lambda to generate the key for each layer.
	 */
	private static PackedCollection<?> collectLayerWeights(
			StateDictionary stateDict,
			int layerCount,
			java.util.function.IntFunction<String> keyGenerator,
			int... dimsPerLayer) {
		TraversalPolicy layerShape = new TraversalPolicy(dimsPerLayer);
		int totalSize = layerShape.getTotalSize();

		// Create combined shape: (layerCount, ...dimsPerLayer)
		int[] combinedDims = new int[dimsPerLayer.length + 1];
		combinedDims[0] = layerCount;
		System.arraycopy(dimsPerLayer, 0, combinedDims, 1, dimsPerLayer.length);
		TraversalPolicy combinedShape = new TraversalPolicy(combinedDims);

		// Allocate combined collection
		PackedCollection<?> combined = new PackedCollection<>(combinedShape);

		// Load each layer's weights
		for (int i = 0; i < layerCount; i++) {
			String key = keyGenerator.apply(i);
			PackedCollection<?> layerWeight = getWeight(stateDict, key, dimsPerLayer);

			// Copy into combined collection at the appropriate offset
			int offset = i * totalSize;
			combined.setMem(offset, layerWeight.toArray(0, totalSize), 0, totalSize);
		}

		return combined;
	}

	/**
	 * Helper: Compute RoPE frequency embeddings from config.
	 * RoPE freqs: freq_i = theta^(-2i/d) for i in [0, d/2)
	 */
	private static PackedCollection<?> computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = config.seqLen;
		double theta = config.ropeTheta;

		// Compute base frequencies
		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
		}

		// Create freq_cis for all positions: e^(i * m * freq)
		// Shape: (seqLen, headSize/2, 2) where last dim is (real, imag)
		TraversalPolicy shape = new TraversalPolicy(seqLen, freqDim, 2);
		PackedCollection<?> freqCis = new PackedCollection<>(shape);

		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));      // real
				freqCis.setMem(idx + 1, Math.sin(angle));  // imaginary
			}
		}

		return freqCis;
	}
}
