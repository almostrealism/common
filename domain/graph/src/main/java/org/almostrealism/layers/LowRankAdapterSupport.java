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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * Interface for building models with LoRA (Low-Rank Adaptation) support.
 *
 * <p>This interface provides methods for conditionally creating LoRA-wrapped or
 * standard dense layers based on an {@link AdapterConfig}. Classes implementing
 * this interface can build models where specific layers are wrapped with LoRA
 * adapters for parameter-efficient fine-tuning.</p>
 *
 * <h2>Usage Pattern</h2>
 * <p>Implementations should:</p>
 * <ol>
 *   <li>Store an {@link AdapterConfig} specifying which layers to adapt</li>
 *   <li>Maintain a list of created {@link LoRALinear} instances</li>
 *   <li>Use {@link #getProjectionFactory()} with AttentionFeatures methods</li>
 * </ol>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * public class MyLoRAModel implements LowRankAdapterSupport, AttentionFeatures {
 *     private final AdapterConfig config;
 *     private final List<LoRALinear> loraLayers = new ArrayList<>();
 *
 *     public AdapterConfig getAdapterConfig() { return config; }
 *     public List<LoRALinear> getLoraLayers() { return loraLayers; }
 *     public void addLoraLayer(LoRALinear layer) { loraLayers.add(layer); }
 *
 *     private void buildModel() {
 *         // Use the projection factory to enable LoRA in attention layers
 *         Block attention = sequenceAttention(
 *             batchSize, seqLen, dim, heads,
 *             qkvWeight, outWeight,
 *             qNormWeight, qNormBias, kNormWeight, kNormBias,
 *             invFreq, getProjectionFactory()
 *         );
 *         model.add(attention);
 *     }
 * }
 * }</pre>
 *
 * @see AdapterConfig
 * @see LoRALinear
 * @author Michael Murray
 */
public interface LowRankAdapterSupport extends LayerFeatures {

	/**
	 * Returns the adapter configuration for this model.
	 *
	 * @return The adapter configuration
	 */
	AdapterConfig getAdapterConfig();

	/**
	 * Returns all LoRA layers created by this model.
	 * These layers contain the trainable parameters for fine-tuning.
	 *
	 * @return List of LoRA layers
	 */
	List<LoRALinear> getLoraLayers();

	/**
	 * Registers a newly created LoRA layer with this model.
	 *
	 * <p>This method is called by {@link #getProjectionFactory()} when creating
	 * LoRA-wrapped projection layers. Implementations typically add the layer
	 * to an internal list.</p>
	 *
	 * @param layer The LoRA layer to register
	 */
	void addLoraLayer(LoRALinear layer);

	/**
	 * Returns a {@link ProjectionFactory} configured for this model's LoRA settings.
	 *
	 * <p>This factory can be passed to AttentionFeatures methods like
	 * {@code sequenceAttention}, {@code sequenceCrossAttention}, and {@code transformerBlock}
	 * to enable LoRA on the appropriate projection layers.</p>
	 *
	 * <p>Created LoRA layers are automatically registered via {@link #addLoraLayer(LoRALinear)}.</p>
	 *
	 * @return A ProjectionFactory that creates LoRA-wrapped layers for targeted projections
	 */
	default ProjectionFactory getProjectionFactory() {
		return loraProjectionFactory();
	}

	/**
	 * Builds the {@link LoRAProjectionFactory} shared by {@link #getProjectionFactory()}
	 * and {@link #loraOrDense(TraversalPolicy, PackedCollection, PackedCollection, AdapterConfig.TargetLayer)}.
	 *
	 * <p>The factory registers each adapter through {@link #addLoraLayer(LoRALinear)} and
	 * falls back, for untargeted projections, to this implementor's own
	 * {@link LayerFeatures#dense(TraversalPolicy, PackedCollection, PackedCollection, boolean, io.almostrealism.compute.ComputeRequirement...)}.
	 * The fallback is a lambda with no {@code this} of its own, so the unqualified
	 * {@code dense(...)} resolves to this implementor and an overriding {@code dense(...)}
	 * is honored.</p>
	 *
	 * <p>This method is private so an implementor cannot override the shared construction.
	 * {@link #loraOrDense(TraversalPolicy, PackedCollection, PackedCollection, AdapterConfig.TargetLayer)}
	 * stays independent of an overridden {@link #getProjectionFactory()} by building its own
	 * factory through this helper rather than calling {@link #getProjectionFactory()}.</p>
	 *
	 * @return A freshly built LoRA projection factory
	 */
	private LoRAProjectionFactory loraProjectionFactory() {
		return new LoRAProjectionFactory(getAdapterConfig(), this::addLoraLayer,
				(shape, weights, bias, target) -> dense(shape, weights, bias, false));
	}

	/**
	 * Creates either a LoRA-wrapped or standard dense layer based on the adapter config.
	 *
	 * <p>If the target layer type is enabled in the config, creates a {@link LoRALinear}
	 * and registers it via {@link #addLoraLayer(LoRALinear)}. Otherwise, creates a
	 * standard dense layer.</p>
	 *
	 * @param inputShape Shape of the input to this layer
	 * @param weights Weight matrix [outputSize, inputSize]
	 * @param bias Bias vector [outputSize], may be null
	 * @param targetLayer Which layer type this represents
	 * @return A CellularLayer (either LoRALinear or standard dense)
	 */
	default CellularLayer loraOrDense(TraversalPolicy inputShape,
									  PackedCollection weights,
									  PackedCollection bias,
									  AdapterConfig.TargetLayer targetLayer) {
		return loraProjectionFactory().create(inputShape, weights, bias, targetLayer);
	}

	/**
	 * Creates either a LoRA-wrapped or standard dense layer (no bias version).
	 *
	 * @param inputShape Shape of the input to this layer
	 * @param weights Weight matrix [outputSize, inputSize]
	 * @param targetLayer Which layer type this represents
	 * @return A CellularLayer (either LoRALinear or standard dense)
	 */
	default CellularLayer loraOrDense(TraversalPolicy inputShape,
									  PackedCollection weights,
									  AdapterConfig.TargetLayer targetLayer) {
		return loraOrDense(inputShape, weights, null, targetLayer);
	}

	/**
	 * Returns all trainable parameters (only LoRA weights).
	 * This is a convenience method that collects weights from all LoRA layers.
	 *
	 * @return List of trainable PackedCollections [loraA1, loraB1, loraA2, loraB2, ...]
	 */
	default List<PackedCollection> getTrainableParameters() {
		return getLoraLayers().stream()
				.flatMap(lora -> lora.getWeights().stream())
				.toList();
	}

	/**
	 * Returns the total number of trainable parameters.
	 *
	 * @return Total count of trainable parameters across all LoRA layers
	 */
	default long getTrainableParameterCount() {
		return getLoraLayers().stream()
				.flatMap(lora -> lora.getWeights().stream())
				.mapToLong(w -> w.getShape().getTotalSize())
				.sum();
	}

	/**
	 * Merges all LoRA weights into the base weights.
	 * After calling this, the model will use merged weights with no LoRA overhead.
	 *
	 * <p><b>Note:</b> This operation modifies the base weights in place.
	 * The LoRA layers will still exist but their contribution will be "baked in".</p>
	 *
	 * @return List of merged weight matrices (one per LoRA layer)
	 */
	default List<PackedCollection> mergeAllLoraWeights() {
		return getLoraLayers().stream()
				.map(LoRALinear::mergeWeights)
				.toList();
	}
}
