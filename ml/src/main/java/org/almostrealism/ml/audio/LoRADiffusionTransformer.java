/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.LoRACapable;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.ModelBundle;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.SequentialBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A DiffusionTransformer with LoRA (Low-Rank Adaptation) support for fine-tuning.
 *
 * <p>This class extends {@link DiffusionTransformer} to add LoRA adapters to the
 * attention projections, enabling parameter-efficient fine-tuning. Only the LoRA
 * weights are trainable; the base model weights remain frozen.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create with default LoRA config for audio diffusion
 * LoRADiffusionTransformer model = LoRADiffusionTransformer.create(
 *     64, 768, 24, 12, 1, 768, 768,
 *     "v_prediction", stateDictionary,
 *     AdapterConfig.forAudioDiffusion()
 * );
 *
 * // Get trainable parameters for optimizer
 * List<PackedCollection> trainable = model.getTrainableParameters();
 *
 * // Fine-tune with your data...
 *
 * // Save adapters
 * model.saveAdapters(Path.of("adapters/my_finetuned"));
 *
 * // Later, load adapters into a fresh model
 * LoRADiffusionTransformer loaded = LoRADiffusionTransformer.create(...);
 * loaded.loadAdapters(Path.of("adapters/my_finetuned"));
 * }</pre>
 *
 * <h2>Parameter Efficiency</h2>
 * <p>With default settings (rank=8, attention projections only):</p>
 * <ul>
 *   <li>Base model: ~100M parameters (frozen)</li>
 *   <li>LoRA adapters: ~0.5M parameters (trainable)</li>
 *   <li>Parameter reduction: ~99.5%</li>
 * </ul>
 *
 * <h2>Important</h2>
 * <p>Use the static factory method {@link #create} instead of the constructor
 * to ensure proper initialization of LoRA layers.</p>
 *
 * @see DiffusionTransformer
 * @see AdapterConfig
 * @see LoRALinear
 * @author Michael Murray
 */
public class LoRADiffusionTransformer extends DiffusionTransformer implements AttentionFeatures, LoRACapable {

	/**
	 * Thread-local holder for LoRA layers during construction.
	 * This is needed because buildModel() is called from the parent constructor
	 * before the subclass fields can be initialized.
	 */
	private static final ThreadLocal<List<LoRALinear>> LORA_LAYERS_HOLDER = new ThreadLocal<>();
	private static final ThreadLocal<AdapterConfig> ADAPTER_CONFIG_HOLDER = new ThreadLocal<>();

	private AdapterConfig adapterConfig;
	private List<LoRALinear> loraLayers;

	/**
	 * Private constructor - use {@link #create} factory method instead.
	 */
	private LoRADiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
									 int patchSize, int condTokenDim, int globalCondDim,
									 String diffusionObjective, StateDictionary stateDictionary,
									 boolean captureAttentionScores) {
		super(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				stateDictionary, captureAttentionScores);
		// Fields are set from thread-locals in getters during buildModel() call from super
		// Now finalize the assignment
		this.adapterConfig = ADAPTER_CONFIG_HOLDER.get();
		this.loraLayers = LORA_LAYERS_HOLDER.get();
	}

	/**
	 * Private constructor with explicit sequence lengths.
	 */
	private LoRADiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
									 int patchSize, int condTokenDim, int globalCondDim,
									 String diffusionObjective, int audioSeqLen, int condSeqLen,
									 StateDictionary stateDictionary, boolean captureAttentionScores) {
		super(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				audioSeqLen, condSeqLen, stateDictionary, captureAttentionScores);
		// Fields are set from thread-locals in getters during buildModel() call from super
		// Now finalize the assignment
		this.adapterConfig = ADAPTER_CONFIG_HOLDER.get();
		this.loraLayers = LORA_LAYERS_HOLDER.get();
	}

	@Override
	public AdapterConfig getAdapterConfig() {
		// During construction, get from thread-local
		if (adapterConfig == null) {
			adapterConfig = ADAPTER_CONFIG_HOLDER.get();
		}
		return adapterConfig;
	}

	@Override
	public List<LoRALinear> getLoraLayers() {
		// During construction, get from thread-local
		if (loraLayers == null) {
			loraLayers = LORA_LAYERS_HOLDER.get();
		}
		return loraLayers;
	}

	/**
	 * Override to use LoRA-aware transformer blocks.
	 */
	@Override
	protected void addTransformerBlocks(SequentialBlock main,
										Block timestepEmbed,
										Block condEmbed,
										Block globalEmbed,
										int dim, int seqLen) {
		int dimHead = dim / getNumHeads();
		int depth = getDepth();
		int batchSize = getBatchSize();

		PackedCollection transformerProjectInWeight =
				createWeight("model.model.transformer.project_in.weight", dim, getIoChannels() * getPatchSize());
		PackedCollection transformerProjectOutWeight =
				createWeight("model.model.transformer.project_out.weight", getIoChannels() * getPatchSize(), dim);
		PackedCollection invFreq =
				createWeight("model.model.transformer.rotary_pos_emb.inv_freq", dimHead / 4);

		// Input projection
		main.add(dense(transformerProjectInWeight));

		boolean hasCrossAttention = getCondTokenDim() > 0 && condEmbed != null;

		if (getGlobalCondDim() > 0) {
			main.add(prependConditioning(timestepEmbed, globalEmbed));
		}

		// Capture state before transformer blocks for test validation
		setPreTransformerState(new PackedCollection(main.getOutputShape()));
		main.branch().andThen(into(getPreTransformerState()));

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

			if (getAttentionScores() != null) {
				PackedCollection scores = new PackedCollection(shape(batchSize, getNumHeads(), seqLen, getCondSeqLen()));
				getAttentionScores().put(i, scores);
				attentionCapture = into(scores);
			}

			// Add LoRA-enabled transformer block using ProjectionFactory from LoRACapable
			main.add(transformerBlock(
					batchSize, dim, seqLen, getNumHeads(),
					hasCrossAttention, getCondSeqLen(), condEmbed,
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
		setPostTransformerState(new PackedCollection(main.getOutputShape()));
		main.branch().andThen(into(getPostTransformerState()));
	}

	/**
	 * Saves all LoRA adapter weights to a single protobuf bundle file.
	 *
	 * <p>This is the preferred method for saving adapters. It creates a single
	 * binary file containing all weights and metadata, making it easy to
	 * distribute, verify, and manage fine-tuned models.</p>
	 *
	 * @param outputPath Path to write the bundle file (e.g., "my_adapter.pb")
	 * @param baseModelId Identifier of the base model (for provenance tracking)
	 * @param metrics Optional training metrics (e.g., final loss, epochs)
	 * @throws IOException If saving fails
	 */
	public void saveAdaptersBundle(Path outputPath, String baseModelId, Map<String, Double> metrics) throws IOException {
		saveAdaptersBundle(outputPath, baseModelId, metrics, null);
	}

	/**
	 * Saves all LoRA adapter weights to a single protobuf bundle file with description.
	 *
	 * @param outputPath Path to write the bundle file
	 * @param baseModelId Identifier of the base model
	 * @param metrics Optional training metrics
	 * @param description Optional human-readable description
	 * @throws IOException If saving fails
	 */
	public void saveAdaptersBundle(Path outputPath, String baseModelId,
								   Map<String, Double> metrics, String description) throws IOException {
		Map<String, PackedCollection> weights = new LinkedHashMap<>();

		for (int i = 0; i < loraLayers.size(); i++) {
			LoRALinear lora = loraLayers.get(i);
			weights.put("lora." + i + ".A", lora.getLoraA());
			weights.put("lora." + i + ".B", lora.getLoraB());
		}

		ModelBundle.Builder builder = ModelBundle.forAdapter(weights, adapterConfig, baseModelId)
				.withMetrics(metrics);
		if (description != null) {
			builder.withDescription(description);
		}
		builder.save(outputPath);
		log("Saved " + loraLayers.size() + " LoRA adapters to bundle: " + outputPath);
	}

	/**
	 * Loads LoRA adapter weights from a protobuf bundle file.
	 *
	 * <p>This is the preferred method for loading adapters. The model must have
	 * been created with the same architecture and adapter config.</p>
	 *
	 * @param bundlePath Path to the bundle file
	 * @return The loaded bundle (for accessing metadata)
	 * @throws IOException If loading fails or bundle is incompatible
	 */
	public ModelBundle loadAdaptersBundle(Path bundlePath) throws IOException {
		ModelBundle bundle = ModelBundle.load(bundlePath);

		if (!bundle.isAdapterBundle()) {
			throw new IOException("Bundle is not an adapter bundle: " + bundle.getModelType());
		}

		StateDictionary weights = bundle.getWeights();

		for (int i = 0; i < loraLayers.size(); i++) {
			LoRALinear lora = loraLayers.get(i);

			PackedCollection loraA = weights.get("lora." + i + ".A");
			PackedCollection loraB = weights.get("lora." + i + ".B");

			if (loraA == null || loraB == null) {
				throw new IOException("Missing LoRA weights for layer " + i + " in bundle");
			}

			// Copy weights into existing collections
			copyWeights(loraA, lora.getLoraA());
			copyWeights(loraB, lora.getLoraB());
		}

		log("Loaded " + loraLayers.size() + " LoRA adapters from bundle: " + bundlePath);
		return bundle;
	}

	private void copyWeights(PackedCollection source, PackedCollection target) {
		int size = (int) source.getShape().getTotalSize();
		for (int i = 0; i < size; i++) {
			target.setMem(i, source.toDouble(i));
		}
	}

	/**
	 * Saves all LoRA adapter weights to a directory.
	 *
	 * <p>The directory structure will be:</p>
	 * <pre>
	 * outputDir/
	 *   adapter_config.json
	 *   lora_0_A.bin
	 *   lora_0_B.bin
	 *   lora_1_A.bin
	 *   lora_1_B.bin
	 *   ...
	 * </pre>
	 *
	 * @param outputDir Directory to save adapters to
	 * @throws IOException If saving fails
	 * @deprecated Use {@link #saveAdaptersBundle(Path, String, Map)} instead for a single-file format
	 */
	@Deprecated
	public void saveAdapters(Path outputDir) throws IOException {
		Files.createDirectories(outputDir);

		// Save adapter config as JSON
		String configJson = String.format(
				"{\"rank\": %d, \"alpha\": %.1f, \"targets\": %s, \"loraCount\": %d}",
				adapterConfig.getRank(),
				adapterConfig.getAlpha(),
				adapterConfig.getTargets().toString(),
				loraLayers.size()
		);
		Files.writeString(outputDir.resolve("adapter_config.json"), configJson);

		// Save each LoRA layer's weights
		for (int i = 0; i < loraLayers.size(); i++) {
			LoRALinear lora = loraLayers.get(i);
			PackedCollection loraA = lora.getLoraA();
			PackedCollection loraB = lora.getLoraB();

			// Save as raw binary (float array)
			savePackedCollection(loraA, outputDir.resolve("lora_" + i + "_A.bin"));
			savePackedCollection(loraB, outputDir.resolve("lora_" + i + "_B.bin"));
		}

		log("Saved " + loraLayers.size() + " LoRA adapters to " + outputDir);
	}

	/**
	 * Loads LoRA adapter weights from a directory.
	 *
	 * <p>The model must have been created with the same architecture and adapter config.</p>
	 *
	 * @param inputDir Directory containing saved adapters
	 * @throws IOException If loading fails
	 * @deprecated Use {@link #loadAdaptersBundle(Path)} instead for single-file format
	 */
	@Deprecated
	public void loadAdapters(Path inputDir) throws IOException {
		if (!Files.exists(inputDir.resolve("adapter_config.json"))) {
			throw new IOException("adapter_config.json not found in " + inputDir);
		}

		// Load each LoRA layer's weights
		for (int i = 0; i < loraLayers.size(); i++) {
			LoRALinear lora = loraLayers.get(i);

			Path loraAPath = inputDir.resolve("lora_" + i + "_A.bin");
			Path loraBPath = inputDir.resolve("lora_" + i + "_B.bin");

			if (!Files.exists(loraAPath) || !Files.exists(loraBPath)) {
				throw new IOException("Missing LoRA weights for layer " + i);
			}

			loadPackedCollection(lora.getLoraA(), loraAPath);
			loadPackedCollection(lora.getLoraB(), loraBPath);
		}

		log("Loaded " + loraLayers.size() + " LoRA adapters from " + inputDir);
	}

	private void savePackedCollection(PackedCollection collection, Path path) throws IOException {
		int size = (int) collection.getShape().getTotalSize();
		byte[] bytes = new byte[size * 4]; // 4 bytes per float

		for (int i = 0; i < size; i++) {
			float val = (float) collection.toDouble(i);
			int bits = Float.floatToIntBits(val);
			bytes[i * 4] = (byte) (bits >> 24);
			bytes[i * 4 + 1] = (byte) (bits >> 16);
			bytes[i * 4 + 2] = (byte) (bits >> 8);
			bytes[i * 4 + 3] = (byte) bits;
		}

		Files.write(path, bytes);
	}

	private void loadPackedCollection(PackedCollection collection, Path path) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		int size = (int) collection.getShape().getTotalSize();

		if (bytes.length != size * 4) {
			throw new IOException("Weight file size mismatch: expected " + (size * 4) +
					" bytes but got " + bytes.length);
		}

		for (int i = 0; i < size; i++) {
			int bits = ((bytes[i * 4] & 0xFF) << 24) |
					((bytes[i * 4 + 1] & 0xFF) << 16) |
					((bytes[i * 4 + 2] & 0xFF) << 8) |
					(bytes[i * 4 + 3] & 0xFF);
			float val = Float.intBitsToFloat(bits);
			collection.setMem(i, val);
		}
	}

	/**
	 * Factory method to create a LoRA-enabled DiffusionTransformer.
	 *
	 * <p>Use this instead of constructors to ensure proper initialization.</p>
	 *
	 * @param ioChannels Number of input/output channels
	 * @param embedDim Embedding dimension
	 * @param depth Number of transformer layers
	 * @param numHeads Number of attention heads
	 * @param patchSize Patch size for input processing
	 * @param condTokenDim Conditioning token dimension (0 for no cross-attention)
	 * @param globalCondDim Global conditioning dimension (0 for none)
	 * @param diffusionObjective Diffusion objective ("epsilon", "v_prediction")
	 * @param stateDictionary Pre-trained weights
	 * @param adapterConfig LoRA adapter configuration
	 * @return A properly initialized LoRADiffusionTransformer
	 */
	public static LoRADiffusionTransformer create(int ioChannels, int embedDim, int depth, int numHeads,
												  int patchSize, int condTokenDim, int globalCondDim,
												  String diffusionObjective, StateDictionary stateDictionary,
												  AdapterConfig adapterConfig) {
		return create(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				stateDictionary, adapterConfig, false);
	}

	/**
	 * Factory method to create a LoRA-enabled DiffusionTransformer with attention capture.
	 *
	 * @param ioChannels Number of input/output channels
	 * @param embedDim Embedding dimension
	 * @param depth Number of transformer layers
	 * @param numHeads Number of attention heads
	 * @param patchSize Patch size for input processing
	 * @param condTokenDim Conditioning token dimension (0 for no cross-attention)
	 * @param globalCondDim Global conditioning dimension (0 for none)
	 * @param diffusionObjective Diffusion objective
	 * @param stateDictionary Pre-trained weights
	 * @param adapterConfig LoRA adapter configuration
	 * @param captureAttentionScores Whether to capture attention scores
	 * @return A properly initialized LoRADiffusionTransformer
	 */
	public static LoRADiffusionTransformer create(int ioChannels, int embedDim, int depth, int numHeads,
												  int patchSize, int condTokenDim, int globalCondDim,
												  String diffusionObjective, StateDictionary stateDictionary,
												  AdapterConfig adapterConfig, boolean captureAttentionScores) {
		// Set up thread-locals for use during construction
		LORA_LAYERS_HOLDER.set(new ArrayList<>());
		ADAPTER_CONFIG_HOLDER.set(adapterConfig);

		try {
			return new LoRADiffusionTransformer(
					ioChannels, embedDim, depth, numHeads, patchSize,
					condTokenDim, globalCondDim, diffusionObjective,
					stateDictionary, captureAttentionScores
			);
		} finally {
			// Clean up thread-locals
			LORA_LAYERS_HOLDER.remove();
			ADAPTER_CONFIG_HOLDER.remove();
		}
	}

	/**
	 * Factory method with explicit sequence lengths.
	 *
	 * @param ioChannels Number of input/output channels
	 * @param embedDim Embedding dimension
	 * @param depth Number of transformer layers
	 * @param numHeads Number of attention heads
	 * @param patchSize Patch size for input processing
	 * @param condTokenDim Conditioning token dimension (0 for no cross-attention)
	 * @param globalCondDim Global conditioning dimension (0 for none)
	 * @param diffusionObjective Diffusion objective
	 * @param audioSeqLen Audio sequence length
	 * @param condSeqLen Conditioning sequence length
	 * @param stateDictionary Pre-trained weights
	 * @param adapterConfig LoRA adapter configuration
	 * @param captureAttentionScores Whether to capture attention scores
	 * @return A properly initialized LoRADiffusionTransformer
	 */
	public static LoRADiffusionTransformer create(int ioChannels, int embedDim, int depth, int numHeads,
												  int patchSize, int condTokenDim, int globalCondDim,
												  String diffusionObjective, int audioSeqLen, int condSeqLen,
												  StateDictionary stateDictionary, AdapterConfig adapterConfig,
												  boolean captureAttentionScores) {
		// Set up thread-locals for use during construction
		LORA_LAYERS_HOLDER.set(new ArrayList<>());
		ADAPTER_CONFIG_HOLDER.set(adapterConfig);

		try {
			return new LoRADiffusionTransformer(
					ioChannels, embedDim, depth, numHeads, patchSize,
					condTokenDim, globalCondDim, diffusionObjective,
					audioSeqLen, condSeqLen,
					stateDictionary, captureAttentionScores
			);
		} finally {
			// Clean up thread-locals
			LORA_LAYERS_HOLDER.remove();
			ADAPTER_CONFIG_HOLDER.remove();
		}
	}

	/**
	 * Compiles the model for training with backward pass enabled.
	 *
	 * @return CompiledModel ready for training
	 */
	public CompiledModel compileForTraining() {
		return getModel().compile(true);
	}

	/**
	 * Gets the input shape for this model.
	 *
	 * @return Input shape (batch, channels, sequenceLength)
	 */
	public io.almostrealism.collect.TraversalPolicy getInputShape() {
		return getModel().getInputShape();
	}

	/**
	 * Gets the audio sequence length this model was configured for.
	 *
	 * @return Audio sequence length
	 */
	public int getAudioSequenceLength() {
		return getAudioSeqLen();
	}
}
