/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Factor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.function.Function;

/**
 * Provides building blocks for diffusion model architectures.
 *
 * <p>This interface contains methods for constructing components commonly used in
 * diffusion models such as Stable Diffusion, DALL-E, and similar image generation
 * architectures. Key components include:</p>
 *
 * <ul>
 *   <li><strong>Timestep embeddings:</strong> Sinusoidal embeddings to condition on diffusion timestep</li>
 *   <li><strong>Upsampling:</strong> 2x spatial upsampling with convolution</li>
 *   <li><strong>Downsampling:</strong> 2x spatial downsampling with convolution</li>
 * </ul>
 *
 * <h2>Timestep Conditioning</h2>
 * <p>Diffusion models require conditioning on the current noise level (timestep).
 * The {@link #timesteps} method provides sinusoidal embeddings similar to those
 * used in transformers, enabling the model to distinguish between different
 * noise levels during denoising.</p>
 *
 * <h2>U-Net Architecture Support</h2>
 * <p>The {@link #upsample} and {@link #downsample} methods support building U-Net
 * architectures commonly used in diffusion models. These handle spatial resolution
 * changes while maintaining channel information.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create timestep embedding layer
 * Block timestepMLP = timestepEmbeddings(inputDim, hiddenDim, outputDim);
 *
 * // Build U-Net encoder block
 * SequentialBlock encoder = new SequentialBlock(inputShape);
 * encoder.add(convBlock(...));
 * encoder.add(downsample(channels).apply(encoder.getOutputShape()));
 *
 * // Build U-Net decoder block
 * SequentialBlock decoder = new SequentialBlock(encoderShape);
 * decoder.add(upsample(channels, outChannels).apply(decoder.getOutputShape()));
 * decoder.add(convBlock(...));
 * }</pre>
 *
 * @see org.almostrealism.layers.LayerFeatures
 */
public interface DiffusionFeatures extends LayerFeatures {

	/**
	 * Creates sinusoidal timestep embeddings with default scale.
	 *
	 * @param inputCount Output embedding dimension (will use inputCount/2 frequencies)
	 * @param flip Whether to flip the frequency order
	 * @param downscaleFreqShift Shift applied to frequency downscaling
	 * @return Factor that computes timestep embeddings from input timesteps
	 */
	default Factor<PackedCollection<?>> timesteps(int inputCount, boolean flip, double downscaleFreqShift) {
		return timesteps(inputCount, flip, downscaleFreqShift, 1.0);
	}

	/**
	 * Creates sinusoidal timestep embeddings with custom scale.
	 *
	 * @param inputCount Output embedding dimension
	 * @param flip Whether to flip the frequency order
	 * @param downscaleFreqShift Shift applied to frequency downscaling
	 * @param scale Scaling factor for the embeddings
	 * @return Factor that computes timestep embeddings from input timesteps
	 */
	default Factor<PackedCollection<?>> timesteps(int inputCount, boolean flip, double downscaleFreqShift, double scale) {
		return timesteps(inputCount, flip, downscaleFreqShift, scale, 10000);
	}

	/**
	 * Creates sinusoidal timestep embeddings for diffusion model conditioning.
	 *
	 * <p>Similar to positional embeddings in transformers, these embeddings encode
	 * the diffusion timestep (noise level) as a continuous vector that the model
	 * can use to condition its predictions. The embeddings use exponentially-spaced
	 * frequencies for a smooth representation across all timesteps.</p>
	 *
	 * @param inputCount Output embedding dimension (uses inputCount/2 frequencies)
	 * @param flip Whether to flip the frequency order
	 * @param downscaleFreqShift Shift applied when computing frequency spacing
	 * @param scale Scaling factor for the final embeddings
	 * @param maxPeriod Maximum period for the sinusoidal functions (default 10000)
	 * @return Factor that computes timestep embeddings from input timesteps
	 */
	default Factor<PackedCollection<?>> timesteps(int inputCount, boolean flip, double downscaleFreqShift, double scale, int maxPeriod) {
		int hDim = inputCount / 2;

		PackedCollection<?> exp = integers(0, hDim)
				.multiply(-Math.log(maxPeriod) / (hDim - downscaleFreqShift)).exp()
				.get().evaluate();

		return input -> {
			CollectionProducer in = multiply(input, p(exp));
			// TODO
			return in;
		};
	}

	/**
	 * Creates a timestep embedding MLP with matching input and output dimensions.
	 *
	 * @param inputCount Dimension of input timestep features
	 * @param timeLen Hidden layer dimension (and output dimension)
	 * @return MLP block for timestep embedding projection
	 */
	default Block timestepEmbeddings(int inputCount, int timeLen) {
		return timestepEmbeddings(inputCount, timeLen, timeLen);
	}

	/**
	 * Creates a timestep embedding MLP that projects timestep features through a hidden layer.
	 *
	 * <p>This creates a 2-layer MLP with SiLU activation, commonly used to project
	 * raw timestep embeddings into a form suitable for conditioning U-Net blocks:</p>
	 * <pre>
	 * timestep -> Linear(inputCount, timeLen) -> SiLU -> Linear(timeLen, outLen)
	 * </pre>
	 *
	 * @param inputCount Dimension of input timestep features
	 * @param timeLen Hidden layer dimension
	 * @param outLen Output dimension (typically matches model hidden dimension)
	 * @return MLP block for timestep embedding projection
	 */
	default Block timestepEmbeddings(int inputCount, int timeLen, int outLen) {
		SequentialBlock block = new SequentialBlock(shape(inputCount));
		block.add(dense(inputCount, timeLen));
		block.add(silu(shape(timeLen)));
		block.add(dense(timeLen, outLen));
		return block;
	}

	/**
	 * Creates a 2x upsampling function with same input/output channels.
	 *
	 * @param dim Number of input and output channels
	 * @return Function that creates upsampling block for a given input shape
	 */
	default Function<TraversalPolicy, Block> upsample(int dim) {
		return upsample(dim, dim);
	}

	/**
	 * Creates a 2x spatial upsampling function for image tensors.
	 *
	 * <p>This method doubles the spatial dimensions (height and width) using nearest-neighbor
	 * interpolation, then applies a 3x3 convolution to refine the upsampled features.
	 * This is the standard upsampling approach used in U-Net decoders.</p>
	 *
	 * <p>Shape transformation: (batch, channels, h, w) -> (batch, dimOut, h*2, w*2)</p>
	 *
	 * @param dim Number of input channels
	 * @param dimOut Number of output channels
	 * @return Function that creates upsampling block for a given input shape
	 */
	default Function<TraversalPolicy, Block> upsample(int dim, int dimOut) {
		return shape -> {
			int batchSize = shape.length(0);
			int inputChannels = shape.length(1);
			int h = shape.length(2);
			int w = shape.length(3);

			SequentialBlock upsample = new SequentialBlock(shape(batchSize, inputChannels, h, w));
			upsample.add(layer("repeat2d",
					shape(batchSize, inputChannels, h, w).traverse(2),
					shape(batchSize, inputChannels, h * 2, w * 2).traverse(2),
					(in) ->
							c(in)
									.repeat(4, 2)
									.repeat(3, 2)
									.reshape(batchSize, inputChannels, h * 2, w * 2).traverse(2)));
			upsample.add(convolution2d(dim, dimOut, 3, 1));
			return upsample;
		};
	}

	/**
	 * Creates a 2x downsampling function with same input/output channels.
	 *
	 * @param dim Number of input and output channels
	 * @return Function that creates downsampling block for a given input shape
	 */
	default Function<TraversalPolicy, Block> downsample(int dim) {
		return downsample(dim, dim);
	}

	/**
	 * Creates a 2x spatial downsampling function for image tensors.
	 *
	 * <p>This method halves the spatial dimensions (height and width) using a
	 * pixel-unshuffle operation that rearranges spatial information into channels,
	 * followed by a 1x1 convolution to project back to the desired channel count.
	 * This approach preserves more information than strided convolution or pooling.</p>
	 *
	 * <p>Shape transformation: (batch, channels, h, w) -> (batch, dimOut, h/2, w/2)</p>
	 *
	 * <p>Implementation:</p>
	 * <ol>
	 *   <li>Pixel unshuffle: (batch, c, h, w) -> (batch, c*4, h/2, w/2)</li>
	 *   <li>1x1 convolution: (batch, c*4, h/2, w/2) -> (batch, dimOut, h/2, w/2)</li>
	 * </ol>
	 *
	 * @param dim Number of input channels
	 * @param dimOut Number of output channels
	 * @return Function that creates downsampling block for a given input shape
	 */
	default Function<TraversalPolicy, Block> downsample(int dim, int dimOut) {
		return shape -> {
			int batchSize = shape.length(0);
			int inputChannels = shape.length(1);
			int h = shape.length(2);
			int w = shape.length(3);

			SequentialBlock downsample = new SequentialBlock(shape(batchSize, inputChannels, h, w));
			downsample.add(layer("enumerate",
					shape(batchSize, inputChannels, h, w),
					shape(batchSize, inputChannels * 4, h / 2, w / 2),
					in -> c(in).traverse(2)
							.enumerate(3, 2)
							.enumerate(3, 2)
							.reshape(batchSize, inputChannels, (h * w) / 4, 4)
							.traverse(2)
							.enumerate(3, 1)
							.reshape(batchSize, inputChannels * 4, h / 2, w / 2)));
			downsample.add(convolution2d(dim * 4, dimOut, 1, 0));
			return downsample;
		};
	}
}
