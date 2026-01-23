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

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.persistence.CollectionEncoder;
import org.almostrealism.protobuf.Collections;
import org.almostrealism.protobuf.Model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A model bundle that combines a {@link StateDictionary} with metadata.
 *
 * <p>This is a thin wrapper providing:</p>
 * <ul>
 *   <li>Weight storage via {@link StateDictionary}</li>
 *   <li>Metadata (model type, base model ID, timestamps, description)</li>
 *   <li>Training metrics</li>
 *   <li>Adapter configuration for LoRA bundles</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create and save a LoRA adapter bundle
 * ModelBundle bundle = ModelBundle.forAdapter(weights, adapterConfig, "base-model-v1")
 *     .withMetrics(trainingMetrics)
 *     .withDescription("Fine-tuned on bass loops");
 * bundle.save(Path.of("my_adapter.pb"));
 *
 * // Load a bundle
 * ModelBundle loaded = ModelBundle.load(Path.of("my_adapter.pb"));
 * StateDictionary weights = loaded.getWeights();
 * AdapterConfig config = loaded.toAdapterConfig();
 * }</pre>
 *
 * @see StateDictionary
 * @see AdapterConfig
 * @author Michael Murray
 */
public class ModelBundle implements Destroyable {

	public static final String TYPE_BASE = "base";
	public static final String TYPE_LORA_ADAPTER = "lora_adapter";
	public static final String TYPE_MERGED = "merged";

	private final StateDictionary weights;
	private final String modelType;
	private final String baseModelId;
	private final long createdTimestamp;
	private final Map<String, Double> metrics;
	private final Map<String, String> config;
	private final String description;
	private final AdapterConfig adapterConfig;

	private ModelBundle(StateDictionary weights, String modelType, String baseModelId,
						long createdTimestamp, Map<String, Double> metrics,
						Map<String, String> config, String description,
						AdapterConfig adapterConfig) {
		this.weights = weights;
		this.modelType = modelType;
		this.baseModelId = baseModelId;
		this.createdTimestamp = createdTimestamp;
		this.metrics = metrics != null ? new HashMap<>(metrics) : new HashMap<>();
		this.config = config != null ? new HashMap<>(config) : new HashMap<>();
		this.description = description;
		this.adapterConfig = adapterConfig;
	}

	public StateDictionary getWeights() {
		return weights;
	}

	public String getModelType() {
		return modelType;
	}

	public String getBaseModelId() {
		return baseModelId;
	}

	public long getCreatedTimestamp() {
		return createdTimestamp;
	}

	public Map<String, Double> getMetrics() {
		return metrics;
	}

	public Map<String, String> getConfig() {
		return config;
	}

	public String getDescription() {
		return description;
	}

	public boolean isAdapterBundle() {
		return TYPE_LORA_ADAPTER.equals(modelType);
	}

	/**
	 * Returns the adapter configuration if this is an adapter bundle.
	 *
	 * @return AdapterConfig or null if not an adapter bundle
	 */
	public AdapterConfig toAdapterConfig() {
		return adapterConfig;
	}

	/**
	 * Saves this bundle to a protobuf file.
	 *
	 * @param outputPath Path to write the bundle
	 * @throws IOException if writing fails
	 */
	public void save(Path outputPath) throws IOException {
		Model.ModelBundle.Builder bundleBuilder = Model.ModelBundle.newBuilder();

		// Build metadata
		Model.ModelMetadata.Builder metadataBuilder = Model.ModelMetadata.newBuilder()
				.setModelType(modelType)
				.setCreatedTimestamp(createdTimestamp);

		if (baseModelId != null) {
			metadataBuilder.setBaseModelId(baseModelId);
		}
		if (description != null) {
			metadataBuilder.setDescription(description);
		}
		if (!metrics.isEmpty()) {
			metadataBuilder.putAllMetrics(metrics);
		}
		if (!config.isEmpty()) {
			metadataBuilder.putAllConfig(config);
		}

		bundleBuilder.setMetadata(metadataBuilder.build());

		// Encode weights using StateDictionary
		bundleBuilder.setWeights(StateDictionary.encode(weights.getAllWeights()));

		// Add adapter config if present
		if (adapterConfig != null) {
			Model.AdapterConfigData.Builder adapterBuilder = Model.AdapterConfigData.newBuilder()
					.setRank(adapterConfig.getRank())
					.setAlpha(adapterConfig.getAlpha());

			for (AdapterConfig.TargetLayer target : adapterConfig.getTargets()) {
				adapterBuilder.addTargets(target.name());
			}

			bundleBuilder.setAdapterConfig(adapterBuilder.build());
		}

		try (OutputStream out = Files.newOutputStream(outputPath)) {
			bundleBuilder.build().writeTo(out);
		}
	}

	@Override
	public void destroy() {
		if (weights != null) {
			weights.destroy();
		}
	}

	/**
	 * Loads a model bundle from a protobuf file.
	 *
	 * @param inputPath Path to the bundle file
	 * @return Loaded ModelBundle
	 * @throws IOException if reading fails
	 */
	public static ModelBundle load(Path inputPath) throws IOException {
		Model.ModelBundle bundle;
		try (InputStream in = Files.newInputStream(inputPath)) {
			bundle = Model.ModelBundle.parseFrom(in);
		}

		Model.ModelMetadata metadata = bundle.getMetadata();

		// Decode weights
		Map<String, PackedCollection> weightsMap = new HashMap<>();
		for (Collections.CollectionLibraryEntry entry : bundle.getWeights().getCollectionsList()) {
			PackedCollection collection = CollectionEncoder.decode(entry.getCollection());
			if (collection != null) {
				weightsMap.put(entry.getKey(), collection);
			}
		}

		StateDictionary weights = new StateDictionary(weightsMap);

		// Parse adapter config if present
		AdapterConfig adapterConfig = null;
		if (bundle.hasAdapterConfig()) {
			Model.AdapterConfigData adapterData = bundle.getAdapterConfig();
			adapterConfig = new AdapterConfig()
					.rank(adapterData.getRank())
					.alpha(adapterData.getAlpha());

			AdapterConfig.TargetLayer[] targets = adapterData.getTargetsList().stream()
					.map(AdapterConfig.TargetLayer::valueOf)
					.toArray(AdapterConfig.TargetLayer[]::new);

			adapterConfig = adapterConfig.targets(targets);
		}

		return new ModelBundle(
				weights,
				metadata.getModelType(),
				metadata.getBaseModelId(),
				metadata.getCreatedTimestamp(),
				metadata.getMetricsMap(),
				metadata.getConfigMap(),
				metadata.getDescription(),
				adapterConfig
		);
	}

	/**
	 * Creates a builder for a LoRA adapter bundle.
	 *
	 * @param weights Map of weight names to PackedCollections
	 * @param adapterConfig The adapter configuration
	 * @param baseModelId Identifier of the base model
	 * @return Builder for further configuration
	 */
	public static Builder forAdapter(Map<String, PackedCollection> weights,
									 AdapterConfig adapterConfig,
									 String baseModelId) {
		return new Builder(new StateDictionary(weights), TYPE_LORA_ADAPTER)
				.baseModelId(baseModelId)
				.adapterConfig(adapterConfig)
				.config("rank", String.valueOf(adapterConfig.getRank()))
				.config("alpha", String.valueOf(adapterConfig.getAlpha()))
				.config("lora_count", String.valueOf(weights.size() / 2));
	}

	/**
	 * Creates a builder for a base model bundle.
	 *
	 * @param weights StateDictionary containing weights
	 * @return Builder for further configuration
	 */
	public static Builder forBaseModel(StateDictionary weights) {
		return new Builder(weights, TYPE_BASE);
	}

	/**
	 * Creates a builder for a merged model bundle.
	 *
	 * @param weights StateDictionary containing weights
	 * @return Builder for further configuration
	 */
	public static Builder forMergedModel(StateDictionary weights) {
		return new Builder(weights, TYPE_MERGED);
	}

	/**
	 * Builder for creating ModelBundle instances.
	 */
	public static class Builder {
		private final StateDictionary weights;
		private final String modelType;
		private String baseModelId;
		private final Map<String, Double> metrics = new HashMap<>();
		private final Map<String, String> config = new HashMap<>();
		private String description;
		private AdapterConfig adapterConfig;

		private Builder(StateDictionary weights, String modelType) {
			this.weights = weights;
			this.modelType = modelType;
		}

		public Builder baseModelId(String baseModelId) {
			this.baseModelId = baseModelId;
			return this;
		}

		public Builder withMetrics(Map<String, Double> metrics) {
			if (metrics != null) {
				this.metrics.putAll(metrics);
			}
			return this;
		}

		public Builder metric(String key, double value) {
			this.metrics.put(key, value);
			return this;
		}

		public Builder config(String key, String value) {
			this.config.put(key, value);
			return this;
		}

		public Builder withDescription(String description) {
			this.description = description;
			return this;
		}

		Builder adapterConfig(AdapterConfig adapterConfig) {
			this.adapterConfig = adapterConfig;
			return this;
		}

		public ModelBundle build() {
			return new ModelBundle(
					weights,
					modelType,
					baseModelId,
					Instant.now().toEpochMilli(),
					metrics,
					config,
					description,
					adapterConfig
			);
		}

		/**
		 * Builds and saves the bundle to a file.
		 *
		 * @param outputPath Path to write the bundle
		 * @throws IOException if writing fails
		 */
		public void save(Path outputPath) throws IOException {
			build().save(outputPath);
		}
	}
}
