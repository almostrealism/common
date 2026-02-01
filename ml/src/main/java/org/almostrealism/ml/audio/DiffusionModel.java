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

package org.almostrealism.ml.audio;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import org.almostrealism.collect.PackedCollection;

import java.util.Map;

/**
 * Interface for diffusion models used in audio generation.
 *
 * <p>This interface defines the forward pass for diffusion models that
 * take a noisy input, timestep, and conditioning inputs to predict
 * noise or velocity.</p>
 *
 * @see DiffusionSampler
 * @see DiffusionTransformer
 * @author Michael Murray
 */
public interface DiffusionModel extends Destroyable {

	/**
	 * Runs the model forward pass.
	 *
	 * @param x Current noisy sample
	 * @param t Timestep tensor
	 * @param crossAttnCond Cross-attention conditioning (e.g., text embeddings)
	 * @param globalCond Global conditioning (e.g., timing, style)
	 * @return Model prediction (noise or velocity)
	 */
	PackedCollection forward(PackedCollection x, PackedCollection t,
							 PackedCollection crossAttnCond,
							 PackedCollection globalCond);

	/**
	 * Returns attention activations for visualization/analysis.
	 *
	 * @return Map of layer index to attention activations
	 */
	default Map<Integer, PackedCollection> getAttentionActivations() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the operation profile for performance analysis.
	 *
	 * @return Operation profile, or null if profiling is not enabled
	 */
	default OperationProfile getProfile() { return null; }
}
