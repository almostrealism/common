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
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.LoRACapable;
import org.almostrealism.layers.LoRALinear;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.ModelBundle;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;

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
 * LoRADiffusionTransformer model = new LoRADiffusionTransformer(
 *     64, 768, 24, 12, 1, 768, 768,
 *     "v_prediction", stateDictionary,
 *     AdapterConfig.forAudioDiffusion(), false
 * );
 *
 * // Get trainable parameters for optimizer
 * List<PackedCollection> trainable = model.getTrainableParameters();
 *
 * // Fine-tune with your data...
 *
 * // Save adapters
 * model.saveAdaptersBundle(Path.of("adapters/my_finetuned.pb"), "base-model-id", metrics);
 *
 * // Later, load adapters into a fresh model
 * LoRADiffusionTransformer loaded = new LoRADiffusionTransformer(...);
 * loaded.loadAdaptersBundle(Path.of("adapters/my_finetuned.pb"));
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
 * @see DiffusionTransformer
 * @see AdapterConfig
 * @see LoRALinear
 * @author Michael Murray
 */
public class LoRADiffusionTransformer extends DiffusionTransformer implements AttentionFeatures, LoRACapable {

	private final AdapterConfig adapterConfig;
	private final List<LoRALinear> loraLayers;

	/**
	 * Creates a LoRA-enabled DiffusionTransformer.
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
	 * @param captureAttentionScores Whether to capture attention scores
	 */
	public LoRADiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
									int patchSize, int condTokenDim, int globalCondDim,
									String diffusionObjective, StateDictionary stateDictionary,
									AdapterConfig adapterConfig, boolean captureAttentionScores) {
		super(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				stateDictionary, captureAttentionScores);
		this.adapterConfig = adapterConfig;
		this.loraLayers = new ArrayList<>();
	}

	/**
	 * Creates a LoRA-enabled DiffusionTransformer with explicit sequence lengths.
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
	 */
	public LoRADiffusionTransformer(int ioChannels, int embedDim, int depth, int numHeads,
									int patchSize, int condTokenDim, int globalCondDim,
									String diffusionObjective, int audioSeqLen, int condSeqLen,
									StateDictionary stateDictionary, AdapterConfig adapterConfig,
									boolean captureAttentionScores) {
		super(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				audioSeqLen, condSeqLen, stateDictionary, captureAttentionScores);
		this.adapterConfig = adapterConfig;
		this.loraLayers = new ArrayList<>();
	}

	@Override
	public AdapterConfig getAdapterConfig() {
		return adapterConfig;
	}

	@Override
	public List<LoRALinear> getLoraLayers() {
		return loraLayers;
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
		return new LoRADiffusionTransformer(ioChannels, embedDim, depth, numHeads, patchSize,
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
		return new LoRADiffusionTransformer(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				stateDictionary, adapterConfig, captureAttentionScores);
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
		return new LoRADiffusionTransformer(ioChannels, embedDim, depth, numHeads, patchSize,
				condTokenDim, globalCondDim, diffusionObjective,
				audioSeqLen, condSeqLen, stateDictionary, adapterConfig,
				captureAttentionScores);
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
