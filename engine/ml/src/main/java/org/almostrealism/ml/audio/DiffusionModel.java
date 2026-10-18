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
	 * <p>The returned buffer is owned by this model, not the caller: an implementation
	 * may return the same buffer on every call (overwriting its contents each time) or
	 * replace and release the previous call's buffer before returning the next one.
	 * Either way, the caller ({@link DiffusionSampler}) must never destroy the buffer
	 * this method returns, and must copy any value it needs to keep once a later call
	 * to this method (or to {@link #destroy()}) may invalidate it.</p>
	 *
	 * @param x Current noisy sample
	 * @param t Timestep tensor
	 * @param crossAttnCond Cross-attention conditioning (e.g., text embeddings)
	 * @param globalCond Global conditioning (e.g., timing, style)
	 * @return Model prediction (noise or velocity), owned by this model
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
