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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;

/**
 * A {@link Bottleneck} that applies a learned per-channel affine transform to the encoder output.
 *
 * <h2>Transform</h2>
 * <p>For an encoder output {@code x} of shape {@code (batch, dim, length)} the latent is</p>
 * <pre>
 * latent = (x &times; scalingFactor + bias) / runningStd
 * </pre>
 * <p>where {@code scalingFactor} and {@code bias} are learned per-channel parameters (one value per
 * channel, broadcast across batch and length) and {@code runningStd} is an optional learned scalar.
 * The output channel count equals the input channel count ({@code dim}); the transform changes the
 * scale and shift of each channel, not the dimensionality.</p>
 *
 * <p>Despite the name, the inference transform is an affine projection rather than an explicit
 * L2 normalization onto a hypersphere: the "soft" normalization is induced during training by a
 * KL-style regularization loss (encouraging each channel toward zero mean and unit variance) that
 * leaves no inference-time operation beyond the affine and the optional standard-deviation rescale.
 * The {@code runningStd} divisor corresponds to the auto-scale running standard deviation tracked
 * during training; supply it when the trained checkpoint enables auto-scaling and omit it
 * otherwise.</p>
 *
 * <p>Parameters are embedded into the forward graph as constants, so this bottleneck is intended
 * for inference (encoding), mirroring {@link VAEBottleneck}.</p>
 *
 * @author  Michael Murray
 * @see Bottleneck
 * @see VAEBottleneck
 */
public class SoftNormBottleneck implements Bottleneck, LayerFeatures {

	/** Latent channel dimensionality; identical on input and output. */
	private final int dim;

	/** Per-channel multiplicative scale, flattened to shape {@code (dim)}. */
	private final PackedCollection scalingFactor;

	/** Per-channel additive shift, flattened to shape {@code (dim)}. */
	private final PackedCollection bias;

	/** Optional learned scalar standard deviation applied as a final divisor, or {@code null}. */
	private final PackedCollection runningStd;

	/**
	 * Creates a SoftNorm bottleneck with no standard-deviation rescaling (auto-scale disabled).
	 *
	 * @param dim           the latent channel dimensionality (input and output)
	 * @param scalingFactor per-channel multiplicative scale; total size must equal {@code dim}
	 * @param bias          per-channel additive shift; total size must equal {@code dim}
	 */
	public SoftNormBottleneck(int dim, PackedCollection scalingFactor, PackedCollection bias) {
		this(dim, scalingFactor, bias, null);
	}

	/**
	 * Creates a SoftNorm bottleneck.
	 *
	 * @param dim           the latent channel dimensionality (input and output)
	 * @param scalingFactor per-channel multiplicative scale; total size must equal {@code dim}
	 * @param bias          per-channel additive shift; total size must equal {@code dim}
	 * @param runningStd    optional learned scalar divisor (auto-scale running standard deviation);
	 *                      total size must equal {@code 1}, or {@code null} to disable rescaling
	 */
	public SoftNormBottleneck(int dim, PackedCollection scalingFactor, PackedCollection bias,
							  PackedCollection runningStd) {
		if (dim <= 0) {
			throw new IllegalArgumentException("SoftNormBottleneck dim must be positive");
		}

		if (scalingFactor == null || scalingFactor.getShape().getTotalSize() != dim) {
			throw new IllegalArgumentException("SoftNormBottleneck scalingFactor must have dim values");
		}

		if (bias == null || bias.getShape().getTotalSize() != dim) {
			throw new IllegalArgumentException("SoftNormBottleneck bias must have dim values");
		}

		if (runningStd != null && runningStd.getShape().getTotalSize() != 1) {
			throw new IllegalArgumentException("SoftNormBottleneck runningStd must be a scalar");
		}

		this.dim = dim;
		this.scalingFactor = scalingFactor.flatten();
		this.bias = bias.flatten();
		// TODO(review): flatten runningStd for shape consistency with scalingFactor and bias;
		// callers may supply a non-flat shape(1,1) tensor that satisfies the TotalSize==1
		// validation but is not normalized.
		this.runningStd = runningStd;
	}

	/**
	 * Builds the SoftNorm transform as a {@link Block} mapping {@code (batchSize, dim, seqLength)} to
	 * {@code (batchSize, dim, seqLength)}.
	 *
	 * @param batchSize the number of examples processed together
	 * @param seqLength the latent sequence length (number of frames)
	 * @return the bottleneck transform as a {@link Block}
	 */
	@Override
	public Block bottleneck(int batchSize, int seqLength) {
		TraversalPolicy shape = shape(batchSize, dim, seqLength);

		return layer("softNormBottleneck", shape, shape, input -> {
			CollectionProducer in = c(input);

			// Broadcast each per-channel parameter across batch and length. Starting from the (dim)
			// parameter, repeat(1, seqLength) appends a length axis to give (dim, seqLength), then
			// repeat(0, batchSize) prepends a batch axis to give (batchSize, dim, seqLength), so
			// element (b, c, l) takes the parameter value for channel c.
			CollectionProducer scale = cp(scalingFactor).repeat(1, seqLength).repeat(0, batchSize);
			CollectionProducer shift = cp(bias).repeat(1, seqLength).repeat(0, batchSize);

			CollectionProducer out = in.multiply(scale).add(shift);

			if (runningStd != null) {
				// runningStd is a scalar and broadcasts across every element.
				out = out.divide(cp(runningStd));
			}

			return out;
		});
	}

	/**
	 * Returns the latent channel dimensionality consumed from the encoder output.
	 *
	 * @return the input channel count ({@code dim})
	 */
	@Override
	public int getInputDim() {
		return dim;
	}

	/**
	 * Returns the latent channel dimensionality produced. SoftNorm preserves the channel count, so
	 * this equals {@link #getInputDim()}.
	 *
	 * @return the output channel count ({@code dim})
	 */
	@Override
	public int getOutputDim() {
		return dim;
	}
}
