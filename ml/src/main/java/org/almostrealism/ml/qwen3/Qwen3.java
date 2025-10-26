package org.almostrealism.ml.qwen3;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Qwen3 model implementation using the Almost Realism framework.
 *
 * This implementation supports Qwen3-Instruct-2507 4B with:
 * - 36 transformer layers
 * - Grouped Query Attention (32 query heads / 8 KV heads)
 * - QK-Norm for training stability
 * - SwiGLU activation in FFN
 * - RoPE positional embeddings (1M base frequency)
 * - 151,669 vocabulary (byte-level BPE)
 * - 128K context window
 *
 * Usage:
 * <pre>
 * Qwen3 model = new Qwen3("qwen3-4B.bin", "tokenizer.bin");
 * model.setTemperature(0.7);
 * model.run(256, "Once upon a time", token -> System.out.print(token));
 * </pre>
 */
public class Qwen3 implements AttentionFeatures {
	static {
		// Disable off-heap memory allocation for simplicity
		System.setProperty("AR_HARDWARE_OFF_HEAP_SIZE", "0");
		// Suppress warnings for cleaner output
		System.setProperty("AR_EXPRESSION_WARNINGS", "disabled");
		System.setProperty("AR_GRAPH_PROPAGATION_WARNINGS", "disabled");
	}

	private Qwen3Config config;
	private StateDictionary stateDict;
	private Qwen3Tokenizer tokenizer;

	private AutoregressiveModel model;
	private OperationProfile profile;

	/**
	 * Main entry point for running Qwen3 from command line.
	 *
	 * Usage: java Qwen3 [checkpoint_path] [prompt]
	 */
	public static void main(String[] args) throws IOException {
		int steps = 256;

		String checkpoint = args.length > 0 ? args[0] : "qwen3-4B.bin";
		String tokenizer = args.length > 1 ? args[1] : "tokenizer.bin";
		String prompt = args.length > 2 ? args[2] : null;

		System.out.println("Loading Qwen3 model from: " + checkpoint);
		Qwen3 qwen = new Qwen3(checkpoint, tokenizer);
		qwen.setTemperature(0.7);

		System.out.println("Generating " + steps + " tokens...");
		long duration = qwen.run(steps, prompt,
				token -> {
					System.out.printf("%s", token);
					System.out.flush();
				});

		System.out.printf("\n\nTokens per second: %.2f\n", (steps - 1) / (double) duration * 1000);
		qwen.getProfile().print();

		System.out.println("Done");
	}

	/**
	 * Load Qwen3 model from StateDictionary (protobuf format from Hugging Face).
	 *
	 * This constructor is for loading weights extracted using extract_qwen3_weights.py.
	 * The configuration is inferred from the weight shapes if not provided.
	 *
	 * @param weightsDirectory Directory containing protobuf weight files
	 * @param tokenizerPath Path to tokenizer files (or null to skip)
	 * @throws IOException If weight or tokenizer loading fails
	 */
	public Qwen3(String weightsDirectory, String tokenizerPath) throws IOException {
		this(weightsDirectory, tokenizerPath, null);
	}

	/**
	 * Load Qwen3 model from StateDictionary with explicit config.
	 *
	 * @param weightsDirectory Directory containing protobuf weight files
	 * @param tokenizerPath Path to tokenizer files (or null to skip)
	 * @param config Explicit configuration (or null to infer from weights)
	 * @throws IOException If weight or tokenizer loading fails
	 */
	public Qwen3(String weightsDirectory, String tokenizerPath, Qwen3Config config) throws IOException {
		long start = System.currentTimeMillis();

		// Load state dictionary
		System.out.println("Loading weights from: " + weightsDirectory);
		this.stateDict = new StateDictionary(weightsDirectory);

		// Infer or validate config
		if (config == null) {
			System.out.println("Inferring configuration from weight shapes...");
			config = inferConfigFromWeights(stateDict);
		}
		this.config = config;
		System.out.println("Config: " + config);
		config.validate();

		System.out.println("Loaded weights in " + (System.currentTimeMillis() - start) + "ms");

		// Load tokenizer if provided
		if (tokenizerPath != null) {
			this.tokenizer = new Qwen3Tokenizer(tokenizerPath);
			System.out.println("Loaded tokenizer with " + tokenizer.getVocabSize() + " tokens");
		} else {
			System.out.println("Warning: No tokenizer provided. Model can only run with token IDs.");
			this.tokenizer = null;
		}

		// Create the model with profiling
		profile = new OperationProfile();
		model = model(profile);
		System.out.println("Model initialized");
	}

	/**
	 * Infer Qwen3 configuration from StateDictionary weight shapes.
	 */
	private static Qwen3Config inferConfigFromWeights(StateDictionary stateDict) {
		// Get embedding shape to determine dim and vocabSize
		PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
		if (embeddings == null) {
			throw new IllegalArgumentException("Cannot find model.embed_tokens.weight in StateDictionary");
		}

		int vocabSize = embeddings.getShape().length(0);
		int dim = embeddings.getShape().length(1);

		// Count layers
		int layerCount = 0;
		while (stateDict.containsKey("model.layers." + layerCount + ".input_layernorm.weight")) {
			layerCount++;
		}

		// Get head count from q_norm shape
		PackedCollection<?> qNorm = stateDict.get("model.layers.0.self_attn.q_norm.weight");
		if (qNorm == null) {
			throw new IllegalArgumentException("Cannot find QK-Norm weights");
		}
		int headCount = qNorm.getShape().length(0);
		int headSize = qNorm.getShape().length(1);

		// Get KV head count
		PackedCollection<?> kNorm = stateDict.get("model.layers.0.self_attn.k_norm.weight");
		int kvHeadCount = kNorm.getShape().length(0);

		// Get hidden dim from FFN
		PackedCollection<?> w1 = stateDict.get("model.layers.0.mlp.gate_proj.weight");
		int hiddenDim = w1.getShape().length(0);

		// Use default values for seqLen (128K) and sharedWeights
		int seqLen = 131072;  // 128K
		boolean sharedWeights = !stateDict.containsKey("lm_head.weight") ||
				stateDict.get("lm_head.weight") == stateDict.get("model.embed_tokens.weight");
		double ropeTheta = 1000000.0;

		System.out.printf("Inferred config: dim=%d, hiddenDim=%d, layers=%d, heads=%d/%d, vocab=%d\n",
				dim, hiddenDim, layerCount, headCount, kvHeadCount, vocabSize);

		return new Qwen3Config(dim, hiddenDim, layerCount, headCount, kvHeadCount,
				vocabSize, seqLen, sharedWeights, ropeTheta);
	}

	/**
	 * Constructor for testing with custom configuration and weights.
	 *
	 * @param config Model configuration
	 * @param stateDict StateDictionary containing model weights
	 * @param tokenizer Tokenizer instance
	 */
	public Qwen3(Qwen3Config config, StateDictionary stateDict, Qwen3Tokenizer tokenizer) {
		this.config = config;
		this.stateDict = stateDict;
		this.tokenizer = tokenizer;
		this.profile = new OperationProfile();
		this.model = model(profile);
	}

	public Qwen3Tokenizer getTokenizer() {
		return tokenizer;
	}

	public OperationProfile getProfile() {
		return profile;
	}

	public Qwen3Config getConfig() {
		return config;
	}

	public void setTemperature(double temperature) {
		model.setTemperature(temperature);
	}

	/**
	 * Build the Qwen3 transformer model.
	 *
	 * This creates the full transformer stack with:
	 * - Token embeddings
	 * - 36 transformer layers with QK-Norm attention and SwiGLU FFN
	 * - Final RMSNorm
	 * - Output projection (shared with embeddings)
	 *
	 * @param profile Operation profile for performance tracking
	 * @param requirements Compute requirements for hardware acceleration
	 * @return Autoregressive model ready for inference
	 */
	protected AutoregressiveModel model(OperationProfile profile, ComputeRequirement... requirements) {
		Model transformer = new Model(shape(config.dim));

		// Placeholder for the index of the current step (position in sequence)
		PackedCollection<?> position = new PackedCollection<>(1);

		int dim = config.dim;
		int kvDim = config.dim * config.kvHeadCount / config.headCount;

		// Get token embeddings and output weights
		PackedCollection<?> tokenEmbeddings = stateDict.get("model.embed_tokens.weight");
		PackedCollection<?> wcls = config.sharedWeights ? tokenEmbeddings :
				stateDict.get("lm_head.weight");
		PackedCollection<?> rmsFinalWeight = stateDict.get("model.norm.weight");

		// Compute RoPE frequencies (not stored in state dict)
		PackedCollection<?> freqCis = computeRopeFreqs(config);

		// Build transformer stack: 36 layers for Qwen3-4B
		for (int i = 0; i < config.layerCount; i++) {
			// Each layer consists of:
			// 1. RMSNorm + Multi-Head Attention with QK-Norm + Residual
			// 2. RMSNorm + SwiGLU FFN + Residual

			// Load weights directly from StateDictionary
			String prefix = String.format("model.layers.%d", i);

			PackedCollection<?> layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
			PackedCollection<?> layerRmsFfn = stateDict.get(prefix + ".post_attention_layernorm.weight");

			// Attention weights
			PackedCollection<?> layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
			PackedCollection<?> layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
			PackedCollection<?> layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
			PackedCollection<?> layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

			// Attention biases (Qwen2.5 has biases for Q/K/V but not O)
			PackedCollection<?> layerBq = stateDict.get(prefix + ".self_attn.q_proj.bias");
			PackedCollection<?> layerBk = stateDict.get(prefix + ".self_attn.k_proj.bias");
			PackedCollection<?> layerBv = stateDict.get(prefix + ".self_attn.v_proj.bias");

			// QK-Norm weights
			PackedCollection<?> layerQkNormQ = stateDict.get(prefix + ".self_attn.q_norm.weight");
			PackedCollection<?> layerQkNormK = stateDict.get(prefix + ".self_attn.k_norm.weight");

			// FFN weights
			PackedCollection<?> layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
			PackedCollection<?> layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
			PackedCollection<?> layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

			// Add complete transformer layer
			transformer.add(transformer(
					config.headCount,     // 32 query heads
					config.kvHeadCount,   // 8 KV heads (GQA)
					layerRmsAtt,          // Pre-attention norm
					layerWk, layerWv, layerWq, layerWo,  // Attention projections
					layerBk, layerBv, layerBq,  // Attention biases
					layerQkNormQ, layerQkNormK,           // QK-Norm weights
					freqCis,              // RoPE frequencies
					layerRmsFfn,          // Pre-FFN norm
					layerW1, layerW2, layerW3,  // FFN projections (SwiGLU)
					p(position),          // Current position
					requirements));
		}

		// Final RMS Norm
		transformer.add(rmsnorm(rmsFinalWeight));

		// Output logits projection (shared with token embeddings)
		transformer.add(dense(wcls));

		// Wrap in autoregressive model with token embeddings
		return AutoregressiveModel.of(
				transformer.compile(false, profile),
				step -> position.setMem((double) step),
				t -> tokenEmbeddings.range(shape(config.dim), t * config.dim));
	}

	/**
	 * Compute RoPE frequency embeddings.
	 */
	private static PackedCollection<?> computeRopeFreqs(Qwen3Config config) {
		int headSize = config.headSize;
		int seqLen = config.seqLen;
		double theta = config.ropeTheta;

		int freqDim = headSize / 2;
		double[] freqs = new double[freqDim];
		for (int i = 0; i < freqDim; i++) {
			freqs[i] = 1.0 / Math.pow(theta, (2.0 * i) / headSize);
		}

		TraversalPolicy shape = new TraversalPolicy(seqLen, freqDim, 2);
		PackedCollection<?> freqCis = new PackedCollection<>(shape);

		for (int pos = 0; pos < seqLen; pos++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = pos * freqs[i];
				int idx = (pos * freqDim + i) * 2;
				freqCis.setMem(idx, Math.cos(angle));
				freqCis.setMem(idx + 1, Math.sin(angle));
			}
		}

		return freqCis;
	}

	/**
	 * Run autoregressive generation for the specified number of steps.
	 *
	 * @param steps Maximum number of tokens to generate
	 * @param prompt Initial prompt text (null for unconditional generation)
	 * @param output Callback to receive generated tokens
	 * @return Generation time in milliseconds
	 */
	public long run(int steps, String prompt, Consumer<String> output) {
		if (steps <= 0 || steps > config.seqLen) {
			steps = config.seqLen;
		}

		if (tokenizer == null) {
			throw new IllegalStateException("No tokenizer loaded. Cannot process text input.");
		}

		// Encode prompt if provided
		int[] promptTokens = null;
		int promptTokenCount = 0;
		if (prompt != null) {
			promptTokens = tokenizer.encode(prompt, true, false);  // Add BOS, no EOS
			promptTokenCount = promptTokens.length;
			System.out.println("Encoded prompt: " + promptTokenCount + " tokens");
		}

		long start = 0;
		int next;
		int token = Qwen3Tokenizer.BOS_TOKEN;

		model.setCurrentToken(Qwen3Tokenizer.BOS_TOKEN);
		model.setPrompt(promptTokens, promptTokenCount);

		output.accept("<|im_start|>\n");

		// Collect tokens for decoding
		List<Integer> generatedTokens = new ArrayList<>();

		while (model.getCurrentStep() < steps) {
			next = model.next();
			generatedTokens.add(next);

			// Decode token
			String tokenStr = tokenizer.decode(new int[]{next});
			output.accept(tokenStr);
			token = next;

			// Check for EOS
			if (next == Qwen3Tokenizer.EOS_TOKEN) {
				break;
			}

			// Start timing after first token (excludes prompt processing)
			if (start == 0) {
				start = System.currentTimeMillis();
			}
		}

		output.accept("<|im_end|>");
		return System.currentTimeMillis() - start;
	}
}
