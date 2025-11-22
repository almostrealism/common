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

import io.almostrealism.cycle.Setup;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.SystemUtils;

import java.util.List;

/**
 * Represents a trainable component in a neural network that has learnable weights.
 * Layer extends both {@link Component} (for shape information) and {@link Setup}
 * (for initialization) to provide a complete interface for neural network layers.
 *
 * <p>Layers are the building blocks of neural networks. Each layer typically has:</p>
 * <ul>
 *   <li>An input/output shape defined by the {@link Component} interface</li>
 *   <li>Learnable weights accessible via {@link #getWeights()}</li>
 *   <li>Setup/initialization logic from the {@link Setup} interface</li>
 * </ul>
 *
 * <h2>Configuration Flags</h2>
 * <p>Layer behavior can be configured through system properties:</p>
 * <ul>
 *   <li>{@code AR_GRAPH_IO_TRACKING} - Enables tracking of input/output values (default: true)</li>
 *   <li>{@code AR_GRAPH_PROPAGATION_WARNINGS} - Enables warnings for propagation issues (default: true)</li>
 *   <li>{@code AR_GRAPH_SHAPE_WARNINGS} - Enables warnings for shape mismatches (default: true)</li>
 * </ul>
 *
 * <h2>Weight Management</h2>
 * <p>Layers expose their learnable parameters through {@link #getWeights()}.
 * These weights are used during:</p>
 * <ul>
 *   <li>Forward propagation - To transform input data</li>
 *   <li>Backpropagation - To compute gradients</li>
 *   <li>Parameter updates - To modify weights based on gradients</li>
 *   <li>Model serialization - To save/load trained models</li>
 * </ul>
 *
 * @see Component
 * @see CellularLayer
 * @see org.almostrealism.model.Block
 * @author Michael Murray
 */
public interface Layer extends Component, Setup {

	/**
	 * Flag to enable tracking of input/output values for debugging and analysis.
	 * Controlled by the {@code AR_GRAPH_IO_TRACKING} system property.
	 */
	boolean ioTracking = SystemUtils.isEnabled("AR_GRAPH_IO_TRACKING").orElse(true);

	/**
	 * Flag to enable warnings when operations may not support backpropagation.
	 * Controlled by the {@code AR_GRAPH_PROPAGATION_WARNINGS} system property.
	 */
	boolean propagationWarnings = SystemUtils.isEnabled("AR_GRAPH_PROPAGATION_WARNINGS").orElse(true);

	/**
	 * Flag to enable warnings for shape mismatches during layer operations.
	 * Controlled by the {@code AR_GRAPH_SHAPE_WARNINGS} system property.
	 */
	boolean shapeWarnings = SystemUtils.isEnabled("AR_GRAPH_SHAPE_WARNINGS").orElse(true);

	/**
	 * Returns the learnable weights of this layer.
	 * Weights are the parameters that are updated during training to improve
	 * the model's performance.
	 *
	 * <p>The returned list may include:</p>
	 * <ul>
	 *   <li>Weight matrices for linear transformations</li>
	 *   <li>Bias vectors</li>
	 *   <li>Normalization parameters (scale, shift)</li>
	 *   <li>Any other learnable parameters specific to the layer type</li>
	 * </ul>
	 *
	 * @return a list of packed collections representing the layer's weights,
	 *         or an empty list if the layer has no learnable parameters
	 */
	List<PackedCollection<?>> getWeights();
}
