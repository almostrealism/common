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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

public class ComposableAudioFeatures implements Factor<PackedCollection>, Destroyable, CodeFeatures {
	private Producer<PackedCollection> features;
	private Producer<PackedCollection> weights;

	/**
	 * Create {@link ComposableAudioFeatures} with the provided features and weights.
	 * <p>
	 * For time (T), bin (B), and decision vector length (L).
	 * </p>
	 *
	 * @param features  (T, B)
	 * @param weights  (T, B, L)
	 */
	public ComposableAudioFeatures(Producer<PackedCollection> features,
								   Producer<PackedCollection> weights) {
		this.features = features;
		this.weights = weights;
	}

	public TraversalPolicy getFeatureShape() { return shape(features); }

	/**
	 * Compute the dot product of each weight vector with the provided value vector
	 * and use the resulting values to scale the feature data.
	 *
	 * @param value  the vector to use to determine the scaling factors
	 * @return  scaled feature data
	 */
	@Override
	public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
		CollectionProducer scale = c(weights).traverse(2).multiply(c(value)).sum();
		scale = scale.reshape(scale.getShape().trim());
		return c(features).multiply(max(scale, c(0)));
	}

	@Override
	public void destroy() {
		Destroyable.destroy(features);
		Destroyable.destroy(weights);
		features = null;
		weights = null;
	}
}
