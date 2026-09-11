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

/**
 * How a diffusion transformer turns the scalar timestep into the Fourier feature vector that feeds
 * its timestep-embedding MLP.
 *
 * @see DiffusionTransformerFeatures#fourierFeatures
 * @see DiffusionTransformerFeatures#expoFourierFeatures
 */
public enum TimestepFeatures {
	/**
	 * Random Fourier features with a learned (checkpointed) frequency matrix, read from the
	 * {@code timestep_features.weight} parameter.
	 */
	LEARNED,

	/**
	 * Deterministic Fourier features whose frequencies are spaced geometrically between fixed
	 * minimum and maximum values; no parameter is read from the checkpoint.
	 */
	EXPO
}
