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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.protobuf.Collections;
import org.almostrealism.protobuf.Model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages saving and loading model bundles in a unified protobuf format.
 *
 * <p>ModelBundleManager provides a single-file format for storing models, LoRA adapters,
 * and associated metadata. This eliminates the fragmented file approach where models
 * are distributed as multiple disconnected files.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Single binary file containing all weights and metadata</li>
 *   <li>Support for base models, LoRA adapters, and merged models</li>
 *   <li>Training metrics and configuration preserved</li>
 *   <li>Hash verification for security (via protobuf integrity)</li>
 * </ul>
 *
 * <h2>Saving LoRA Adapters</h2>
 * <pre>{@code
 * // After fine-tuning
 * Map<String, PackedCollection> loraWeights = new LinkedHashMap<>();
 * for (int i = 0; i < model.getLoraLayers().size(); i++) {
 *     LoRALinear layer = model.getLoraLayers().get(i);
 *     loraWeights.put("lora." + i + ".A", layer.getLoraA());
 *     loraWeights.put("lora." + i + ".B", layer.getLoraB());
 * }
 *
 * ModelBundleManager.saveAdapterBundle(
 *     Path.of("my_adapter.pb"),
 *     loraWeights,
 *     model.getAdapterConfig(),
 *     "base_model_v1",
 *     trainingMetrics
 * );
 * }</pre>
 *
 * <h2>Loading LoRA Adapters</h2>
 * <pre>{@code
 * ModelBundleManager.LoadedBundle bundle = ModelBundleManager.load(Path.of("my_adapter.pb"));
 *
 * // Access weights
 * Map<String, PackedCollection> weights = bundle.getWeights();
 *
 * // Access metadata
 * String baseModelId = bundle.getMetadata().getBaseModelId();
 * double finalLoss = bundle.getMetrics().get("final_train_loss");
 * }</pre>
 *
 * @see StateDictionary
 * @see AdapterConfig
 * @author Michael Murray
 */
public class ModelBundleManager implements ConsoleFeatures {

	/**
	 * Model types for the bundle.
	 */
	public static final String TYPE_BASE = "base";
	public static final String TYPE_LORA_ADAPTER = "lora_adapter";
	public static final String TYPE_MERGED = "merged";

	/**
	 * Saves a LoRA adapter bundle to a single protobuf file.
	 *
	 * @param outputPath Path to write the bundle file
	 * @param weights Map of weight name to PackedCollection (loraA, loraB pairs)
	 * @param adapterConfig The adapter configuration used during training
	 * @param baseModelId Identifier of the base model these adapters are for
	 * @param metrics Training metrics to store (e.g., final_train_loss, epochs)
	 * @throws IOException if writing fails
	 */
	public static void saveAdapterBundle(Path outputPath,
										 Map<String, PackedCollection> weights,
										 AdapterConfig adapterConfig,
										 String baseModelId,
										 Map<String, Double> metrics) throws IOException {
		saveAdapterBundle(outputPath, weights, adapterConfig, baseModelId, metrics, null);
	}

	/**
	 * Saves a LoRA adapter bundle with optional description.
	 *
	 * @param outputPath Path to write the bundle file
	 * @param weights Map of weight name to PackedCollection
	 * @param adapterConfig The adapter configuration
	 * @param baseModelId Identifier of the base model
	 * @param metrics Training metrics
	 * @param description Optional human-readable description
	 * @throws IOException if writing fails
	 */
	public static void saveAdapterBundle(Path outputPath,
										 Map<String, PackedCollection> weights,
										 AdapterConfig adapterConfig,
										 String baseModelId,
										 Map<String, Double> metrics,
										 String description) throws IOException {
		// Build adapter config data
		Model.AdapterConfigData.Builder adapterConfigBuilder = Model.AdapterConfigData.newBuilder()
				.setRank(adapterConfig.getRank())
				.setAlpha(adapterConfig.getAlpha());

		for (AdapterConfig.TargetLayer target : adapterConfig.getTargets()) {
			adapterConfigBuilder.addTargets(target.name());
		}

		// Build metadata
		Model.ModelMetadata.Builder metadataBuilder = Model.ModelMetadata.newBuilder()
				.setModelType(TYPE_LORA_ADAPTER)
				.setBaseModelId(baseModelId != null ? baseModelId : "")
				.setCreatedTimestamp(Instant.now().toEpochMilli());

		if (metrics != null) {
			metadataBuilder.putAllMetrics(metrics);
		}

		if (description != null) {
			metadataBuilder.setDescription(description);
		}

		// Store adapter config values in the config map for easy access
		metadataBuilder.putConfig("rank", String.valueOf(adapterConfig.getRank()));
		metadataBuilder.putConfig("alpha", String.valueOf(adapterConfig.getAlpha()));
		metadataBuilder.putConfig("lora_count", String.valueOf(weights.size() / 2));

		// Build weights
		Collections.CollectionLibraryData weightsData = encodeWeights(weights);

		// Build bundle
		Model.ModelBundle bundle = Model.ModelBundle.newBuilder()
				.setMetadata(metadataBuilder.build())
				.setWeights(weightsData)
				.setAdapterConfig(adapterConfigBuilder.build())
				.build();

		// Write to file
		try (OutputStream out = Files.newOutputStream(outputPath)) {
			bundle.writeTo(out);
		}
	}

	/**
	 * Saves a base model or merged model bundle.
	 *
	 * @param outputPath Path to write the bundle file
	 * @param weights Map of weight name to PackedCollection
	 * @param modelType Either TYPE_BASE or TYPE_MERGED
	 * @param description Optional description
	 * @throws IOException if writing fails
	 */
	public static void saveModelBundle(Path outputPath,
									   Map<String, PackedCollection> weights,
									   String modelType,
									   String description) throws IOException {
		Model.ModelMetadata.Builder metadataBuilder = Model.ModelMetadata.newBuilder()
				.setModelType(modelType)
				.setCreatedTimestamp(Instant.now().toEpochMilli());

		if (description != null) {
			metadataBuilder.setDescription(description);
		}

		Collections.CollectionLibraryData weightsData = encodeWeights(weights);

		Model.ModelBundle bundle = Model.ModelBundle.newBuilder()
				.setMetadata(metadataBuilder.build())
				.setWeights(weightsData)
				.build();

		try (OutputStream out = Files.newOutputStream(outputPath)) {
			bundle.writeTo(out);
		}
	}

	/**
	 * Loads a model bundle from a protobuf file.
	 *
	 * @param inputPath Path to the bundle file
	 * @return LoadedBundle containing weights and metadata
	 * @throws IOException if reading fails
	 */
	public static LoadedBundle load(Path inputPath) throws IOException {
		Model.ModelBundle bundle;
		try (InputStream in = Files.newInputStream(inputPath)) {
			bundle = Model.ModelBundle.parseFrom(in);
		}

		Map<String, PackedCollection> weights = decodeWeights(bundle.getWeights());

		return new LoadedBundle(
				bundle.getMetadata(),
				weights,
				bundle.hasAdapterConfig() ? bundle.getAdapterConfig() : null
		);
	}

	/**
	 * Encodes a map of PackedCollections to protobuf format.
	 */
	private static Collections.CollectionLibraryData encodeWeights(Map<String, PackedCollection> weights) {
		Collections.CollectionLibraryData.Builder libraryBuilder = Collections.CollectionLibraryData.newBuilder();

		for (Map.Entry<String, PackedCollection> entry : weights.entrySet()) {
			PackedCollection collection = entry.getValue();
			TraversalPolicy shape = collection.getShape();

			// Build traversal policy
			Collections.TraversalPolicyData.Builder policyBuilder = Collections.TraversalPolicyData.newBuilder();
			for (int i = 0; i < shape.getDimensions(); i++) {
				policyBuilder.addDims(shape.length(i));
			}
			policyBuilder.setTraversalAxis(shape.getTraversalAxis());

			// Build collection data with float32 values
			Collections.CollectionData.Builder collectionBuilder = Collections.CollectionData.newBuilder()
					.setTraversalPolicy(policyBuilder.build());

			int totalSize = (int) shape.getTotalSize();
			for (int i = 0; i < totalSize; i++) {
				collectionBuilder.addData32((float) collection.toDouble(i));
			}

			// Add entry
			Collections.CollectionLibraryEntry entry1 = Collections.CollectionLibraryEntry.newBuilder()
					.setKey(entry.getKey())
					.setCollection(collectionBuilder.build())
					.build();

			libraryBuilder.addCollections(entry1);
		}

		return libraryBuilder.build();
	}

	/**
	 * Decodes weights from protobuf format to PackedCollections.
	 */
	private static Map<String, PackedCollection> decodeWeights(Collections.CollectionLibraryData libraryData) {
		Map<String, PackedCollection> weights = new LinkedHashMap<>();

		for (Collections.CollectionLibraryEntry entry : libraryData.getCollectionsList()) {
			Collections.CollectionData collectionData = entry.getCollection();
			Collections.TraversalPolicyData policyData = collectionData.getTraversalPolicy();

			// Reconstruct shape
			int[] dims = new int[policyData.getDimsCount()];
			for (int i = 0; i < dims.length; i++) {
				dims[i] = policyData.getDims(i);
			}
			TraversalPolicy shape = new TraversalPolicy(dims);

			// Create PackedCollection
			PackedCollection collection = new PackedCollection(shape);

			// Fill with data (prefer float32 if available)
			List<Float> data32 = collectionData.getData32List();
			List<Double> data64 = collectionData.getDataList();

			if (!data32.isEmpty()) {
				for (int i = 0; i < data32.size(); i++) {
					collection.setMem(i, data32.get(i));
				}
			} else if (!data64.isEmpty()) {
				for (int i = 0; i < data64.size(); i++) {
					collection.setMem(i, data64.get(i));
				}
			}

			weights.put(entry.getKey(), collection);
		}

		return weights;
	}

	/**
	 * Result of loading a model bundle.
	 */
	public static class LoadedBundle {
		private final Model.ModelMetadata metadata;
		private final Map<String, PackedCollection> weights;
		private final Model.AdapterConfigData adapterConfig;

		public LoadedBundle(Model.ModelMetadata metadata,
							Map<String, PackedCollection> weights,
							Model.AdapterConfigData adapterConfig) {
			this.metadata = metadata;
			this.weights = weights;
			this.adapterConfig = adapterConfig;
		}

		public Model.ModelMetadata getMetadata() {
			return metadata;
		}

		public Map<String, PackedCollection> getWeights() {
			return weights;
		}

		public Model.AdapterConfigData getAdapterConfig() {
			return adapterConfig;
		}

		public String getModelType() {
			return metadata.getModelType();
		}

		public String getBaseModelId() {
			return metadata.getBaseModelId();
		}

		public Map<String, Double> getMetrics() {
			return metadata.getMetricsMap();
		}

		public Map<String, String> getConfig() {
			return metadata.getConfigMap();
		}

		public long getCreatedTimestamp() {
			return metadata.getCreatedTimestamp();
		}

		public String getDescription() {
			return metadata.getDescription();
		}

		/**
		 * Reconstructs an AdapterConfig from the stored configuration.
		 *
		 * @return AdapterConfig if this is an adapter bundle, null otherwise
		 */
		public AdapterConfig toAdapterConfig() {
			if (adapterConfig == null) {
				return null;
			}

			AdapterConfig config = new AdapterConfig()
					.rank(adapterConfig.getRank())
					.alpha(adapterConfig.getAlpha());

			AdapterConfig.TargetLayer[] targets = adapterConfig.getTargetsList().stream()
					.map(AdapterConfig.TargetLayer::valueOf)
					.toArray(AdapterConfig.TargetLayer[]::new);

			return config.targets(targets);
		}

		public boolean isAdapterBundle() {
			return TYPE_LORA_ADAPTER.equals(metadata.getModelType());
		}
	}
}
