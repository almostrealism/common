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
 * A {@link ProjectionFactory} that creates standard dense (linear) projection
 * layers, ignoring the {@link AdapterConfig.TargetLayer} parameter.
 *
 * <p>Because {@link ProjectionFactory} extends {@link LayerFeatures}, this class
 * reaches the dense layer builder through its own inherited
 * {@link LayerFeatures#dense(TraversalPolicy, PackedCollection, PackedCollection, boolean, io.almostrealism.compute.ComputeRequirement...)}
 * method rather than constructing a throwaway anonymous {@link LayerFeatures}.</p>
 *
 * <p>The class is stateless, so a single instance may be shared freely.</p>
 *
 * @see ProjectionFactory#dense()
 * @see LoRAProjectionFactory
 * @author Michael Murray
 */
public class DenseProjectionFactory implements ProjectionFactory {

	@Override
	public CellularLayer create(TraversalPolicy inputShape,
								PackedCollection weights,
								PackedCollection bias,
								AdapterConfig.TargetLayer targetLayer) {
		return dense(inputShape, weights, bias, false);
	}
}
