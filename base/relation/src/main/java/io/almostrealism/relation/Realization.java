/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.relation;

/**
 * A functional interface for creating {@link Producer}s from parameters.
 *
 * <p>{@link Realization} represents the pattern of creating computational systems
 * from configuration or input parameters. This is useful for deferred instantiation
 * of producers based on runtime-determined values.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Creating parameterized computations from configuration</li>
 *   <li>Building producers from model weights or hyperparameters</li>
 *   <li>Lazy instantiation of computation graphs</li>
 *   <li>Factory-style producer creation with specific parameters</li>
 * </ul>
 *
 * <h2>Comparison with Related Types</h2>
 * <ul>
 *   <li>{@link Factory} - Creates objects with no parameters</li>
 *   <li>{@link Realization} - Creates producers from parameters</li>
 *   <li>{@link Factor} - Transforms existing producers</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Define a realization for creating neural network layers
 * Realization<Producer<Tensor>, LayerConfig> layerFactory = config -> {
 *     return createLayerProducer(config.getInputSize(), config.getOutputSize());
 * };
 *
 * // Create a producer from configuration
 * Producer<Tensor> layer = layerFactory.realize(new LayerConfig(784, 256));
 * }</pre>
 *
 * @param <O> the type of producer created (must extend Producer)
 * @param <P> the type of parameters used to create the producer
 *
 * @see Producer
 * @see Factory
 * @see Factor
 *
 * @author Michael Murray
 */
@FunctionalInterface
public interface Realization<O extends Producer, P> {
	/**
	 * Creates a {@link Producer} from the given parameters.
	 *
	 * <p>This method instantiates a new producer configured according to
	 * the provided parameters. The exact behavior depends on the
	 * implementation and the parameter type.</p>
	 *
	 * @param params the parameters to use for creating the producer
	 * @return a new producer configured with the given parameters
	 */
	O realize(P params);
}
