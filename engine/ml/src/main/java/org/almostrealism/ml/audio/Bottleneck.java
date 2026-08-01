/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.audio;

import org.almostrealism.model.Block;

/**
 * The latent-boundary transform that an autoencoder applies to its encoder output to produce the
 * latent representation consumed by the decoder. A bottleneck is the general "encoder-output to
 * latent" stage shared by every autoencoder architecture &mdash; a VAE mean/log-variance split, a
 * learned per-channel affine projection, a finite-scalar quantizer, and so on &mdash; even though
 * each realises it with a different transform.
 *
 * <p>Factoring this contract into an interface lets the autoencoder assembly depend on a single
 * type rather than on any one concrete bottleneck, so a bottleneck can be swapped without changing
 * the surrounding encoder/decoder wiring.</p>
 *
 * <p>The transform is expressed as a {@link Block} so that it participates in the compiled
 * computation graph rather than being evaluated on the host. Implementations build that block on
 * demand for a particular batch size and latent sequence length via {@link #bottleneck(int, int)};
 * the batch and length are supplied at build time because the resulting graph is shape-specific.
 * {@link #getInputDim()} and {@link #getOutputDim()} report the channel dimensionality on either
 * side of the transform.</p>
 *
 * @author  Michael Murray
 * @see VAEBottleneck
 * @see SoftNormBottleneck
 */
public interface Bottleneck {

	/**
	 * Builds the bottleneck transform as a {@link Block} mapping the encoder output to the latent.
	 *
	 * <p>The returned block maps an input of shape {@code (batchSize, getInputDim(), seqLength)} to
	 * an output of shape {@code (batchSize, getOutputDim(), seqLength)}.</p>
	 *
	 * @param batchSize  the number of examples processed together
	 * @param seqLength  the latent sequence length (number of frames)
	 * @return the bottleneck transform as a {@link Block}
	 */
	Block bottleneck(int batchSize, int seqLength);

	/**
	 * Returns the number of channels in the encoder output consumed by this bottleneck.
	 *
	 * @return the input (encoder-output) channel count
	 */
	int getInputDim();

	/**
	 * Returns the number of channels in the latent produced by this bottleneck.
	 *
	 * @return the output (latent) channel count
	 */
	int getOutputDim();
}
