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

/**
 * Factory for creating projection layers (dense/linear transformations).
 *
 * <p>This interface allows attention and transformer methods to use different
 * projection implementations without code duplication. The default implementation
 * creates standard dense layers, but it can be customized to create LoRA-wrapped
 * layers or other variants.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Default: regular dense layers
 * ProjectionFactory defaultFactory = ProjectionFactory.dense();
 *
 * // LoRA: create adapters based on config
 * ProjectionFactory loraFactory = (shape, weights, bias, target) -> {
 *     if (config.isTargeted(target)) {
 *         return new LoRALinear(shape, weights, bias, config.getRank(), config.getAlpha());
 *     }
 *     return dense(shape, weights, bias, false);
 * };
 *
 * // Use with attention methods
 * Block attn = sequenceAttention(..., loraFactory);
 * }</pre>
 *
 * @see LoRALinear
 * @see AdapterConfig
 * @author Michael Murray
 */
@FunctionalInterface
public interface ProjectionFactory extends LayerFeatures {

	/**
	 * Creates a projection layer with the given parameters.
	 *
	 * @param inputShape Shape of the input to this layer
	 * @param weights Weight matrix [outputSize, inputSize]
	 * @param bias Bias vector [outputSize], may be null
	 * @param targetLayer Which layer type this represents (for adapter targeting)
	 * @return A CellularLayer implementing the projection
	 */
	CellularLayer create(TraversalPolicy inputShape,
						 PackedCollection weights,
						 PackedCollection bias,
						 AdapterConfig.TargetLayer targetLayer);

	/**
	 * Creates a projection layer without bias.
	 *
	 * @param inputShape Shape of the input to this layer
	 * @param weights Weight matrix [outputSize, inputSize]
	 * @param targetLayer Which layer type this represents
	 * @return A CellularLayer implementing the projection
	 */
	default CellularLayer create(TraversalPolicy inputShape,
								 PackedCollection weights,
								 AdapterConfig.TargetLayer targetLayer) {
		return create(inputShape, weights, null, targetLayer);
	}

	/**
	 * Returns the default factory that creates standard dense layers.
	 * The targetLayer parameter is ignored.
	 *
	 * @return A factory that creates dense layers
	 */
	static ProjectionFactory dense() {
		return (shape, weights, bias, target) -> {
			LayerFeatures lf = new LayerFeatures() {};
			return lf.dense(shape, weights, bias, false);
		};
	}

	/**
	 * Creates a LoRA-enabled projection factory.
	 *
	 * <p>Projections matching the targeted layers in the config will be wrapped
	 * with LoRA adapters. Other projections use standard dense layers.</p>
	 *
	 * @param config Adapter configuration specifying which layers to wrap
	 * @param loraLayers List to accumulate created LoRA layers (for later access to trainable params)
	 * @return A factory that conditionally creates LoRA-wrapped layers
	 */
	static ProjectionFactory lora(AdapterConfig config, java.util.List<LoRALinear> loraLayers) {
		return (shape, weights, bias, target) -> {
			LayerFeatures lf = new LayerFeatures() {};
			if (config != null && config.isTargeted(target)) {
				LoRALinear loraLayer = new LoRALinear(shape, weights, bias, config.getRank(), config.getAlpha());
				if (loraLayers != null) {
					loraLayers.add(loraLayer);
				}
				return loraLayer;
			}
			return lf.dense(shape, weights, bias, false);
		};
	}
}
