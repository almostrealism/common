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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;

import java.util.function.Supplier;

/**
 * Strategy interface for back-propagating gradients through a neural network layer.
 *
 * <p>Implementations compute both the gradient with respect to the layer's input
 * (for propagating further upstream) and the gradient with respect to the layer's
 * learnable parameters (for weight updates).</p>
 *
 * <p>The primary implementation is {@link DefaultGradientPropagation}, which uses
 * automatic differentiation to compute gradients. Custom implementations can be
 * provided for layers that require specialized gradient computation.</p>
 *
 * @see DefaultGradientPropagation
 * @see BackPropagationCell
 * @author Michael Murray
 */
public interface BackPropagation {
	/**
	 * Computes and propagates gradients for a layer backward pass.
	 *
	 * @param gradient the gradient of the loss with respect to this layer's output
	 * @param input    the input that was passed to the layer during the forward pass
	 * @param next     the receptor to receive the gradient with respect to the layer input,
	 *                 or null if the input gradient is not needed
	 * @return a supplier of operations that perform the gradient computation and parameter updates
	 */
	Supplier<Runnable> propagate(Producer<PackedCollection> gradient,
								 Producer<PackedCollection> input,
								 Receptor<PackedCollection> next);
}
