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
 *   <li>Track all created {@link LoRALinear} instances</li>
 *   <li>Use {@link #getProjectionFactory()} with AttentionFeatures methods</li>
 *   <li>Provide {@link #getLoraLayers()} for accessing trainable parameters</li>
 * </ol>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * public class MyLoRAModel implements LoRACapable, AttentionFeatures {
 *     private final AdapterConfig config;
 *     private final List<LoRALinear> loraLayers = new ArrayList<>();
 *
 *     public AdapterConfig getAdapterConfig() { return config; }
 *     public List<LoRALinear> getLoraLayers() { return loraLayers; }
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
public interface LoRACapable extends LayerFeatures {

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
	 * @return List of LoRA layers (mutable - implementations should add to this list)
	 */
	List<LoRALinear> getLoraLayers();

	/**
	 * Returns a {@link ProjectionFactory} configured for this model's LoRA settings.
	 *
	 * <p>This factory can be passed to AttentionFeatures methods like
	 * {@code sequenceAttention}, {@code sequenceCrossAttention}, and {@code transformerBlock}
	 * to enable LoRA on the appropriate projection layers.</p>
	 *
	 * <p>Created LoRA layers are automatically tracked in {@link #getLoraLayers()}.</p>
	 *
	 * @return A ProjectionFactory that creates LoRA-wrapped layers for targeted projections
	 */
	default ProjectionFactory getProjectionFactory() {
		return ProjectionFactory.lora(getAdapterConfig(), getLoraLayers());
	}

	/**
	 * Creates either a LoRA-wrapped or standard dense layer based on the adapter config.
	 *
	 * <p>If the target layer type is enabled in the config, creates a {@link LoRALinear}
	 * and registers it. Otherwise, creates a standard dense layer.</p>
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
		AdapterConfig config = getAdapterConfig();

		if (config != null && config.isTargeted(targetLayer)) {
			LoRALinear loraLayer = new LoRALinear(
					inputShape,
					weights,
					bias,
					config.getRank(),
					config.getAlpha()
			);
			getLoraLayers().add(loraLayer);
			return loraLayer;
		} else {
			return dense(inputShape, weights, bias, false);
		}
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
