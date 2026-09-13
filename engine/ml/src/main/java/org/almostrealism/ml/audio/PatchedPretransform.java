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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;

/**
 * A parameter-free pretransform that folds runs of {@code patchSize} consecutive samples of each
 * channel into the channel axis, so that an encoder sees {@code channels * patchSize} channels at
 * {@code 1 / patchSize} of the sample rate; the decode direction unfolds them again.
 *
 * <p>Encoding maps {@code (batch, channels, length)} to
 * {@code (batch, channels * patchSize, length / patchSize)} where output channel
 * {@code c * patchSize + h} at frame {@code l} holds input channel {@code c} at sample
 * {@code l * patchSize + h}. The input length must be a multiple of {@code patchSize}; callers pad
 * with zeros to reach that length (see {@link #paddedLength(int)}). Decoding is the exact inverse.</p>
 *
 * <p>This is the "patched" pretransform used in front of transformer audio autoencoders, where the
 * patch size contributes a factor of {@code patchSize} to the overall downsampling ratio.</p>
 */
public class PatchedPretransform implements LayerFeatures {

	/** Number of audio channels. */
	private final int channels;

	/** Number of consecutive samples folded into the channel axis. */
	private final int patchSize;

	/**
	 * Creates a patched pretransform.
	 *
	 * @param channels  number of audio channels (2 for stereo)
	 * @param patchSize number of consecutive samples folded into the channel axis
	 */
	public PatchedPretransform(int channels, int patchSize) {
		if (channels <= 0 || patchSize <= 0) {
			throw new IllegalArgumentException("channels and patchSize must be positive");
		}

		this.channels = channels;
		this.patchSize = patchSize;
	}

	/**
	 * Returns the number of audio channels.
	 *
	 * @return the channel count
	 */
	public int getChannels() { return channels; }

	/**
	 * Returns the number of consecutive samples folded into the channel axis.
	 *
	 * @return the patch size
	 */
	public int getPatchSize() { return patchSize; }

	/**
	 * Returns the number of channels after encoding, {@code channels * patchSize}.
	 *
	 * @return the encoded channel count
	 */
	public int getEncodedChannels() { return channels * patchSize; }

	/**
	 * Returns the factor by which encoding shortens the sequence, which is the patch size.
	 *
	 * @return the downsampling ratio
	 */
	public int getDownsamplingRatio() { return patchSize; }

	/**
	 * The smallest multiple of {@code patchSize} that is at least {@code length}.
	 *
	 * @param length an audio length in samples
	 * @return the length after zero-padding to a whole number of patches
	 */
	public int paddedLength(int length) {
		return (length + patchSize - 1) / patchSize * patchSize;
	}

	/**
	 * Folds patches of samples into the channel axis.
	 *
	 * @param batchSize batch size
	 * @param length    audio length in samples, a whole number of patches
	 * @return a block mapping {@code (batchSize, channels, length)} to
	 *         {@code (batchSize, channels * patchSize, length / patchSize)}
	 */
	public Block encode(int batchSize, int length) {
		if (length % patchSize != 0) {
			throw new IllegalArgumentException("length " + length +
					" is not a multiple of the patch size " + patchSize);
		}

		int frames = length / patchSize;
		TraversalPolicy inShape = shape(batchSize, channels, length);
		TraversalPolicy outShape = shape(batchSize, channels * patchSize, frames);

		return layer("patchEncode", inShape, outShape, input -> {
			CollectionProducer x = c(input).reshape(batchSize, channels, frames, patchSize);
			return x.permute(0, 1, 3, 2).reshape(batchSize, channels * patchSize, frames);
		});
	}

	/**
	 * Unfolds the channel axis back into consecutive samples; the inverse of {@link #encode}.
	 *
	 * @param batchSize batch size
	 * @param frames    number of encoded frames
	 * @return a block mapping {@code (batchSize, channels * patchSize, frames)} to
	 *         {@code (batchSize, channels, frames * patchSize)}
	 */
	public Block decode(int batchSize, int frames) {
		TraversalPolicy inShape = shape(batchSize, channels * patchSize, frames);
		TraversalPolicy outShape = shape(batchSize, channels, frames * patchSize);

		return layer("patchDecode", inShape, outShape, input -> {
			CollectionProducer x = c(input).reshape(batchSize, channels, patchSize, frames);
			return x.permute(0, 1, 3, 2).reshape(batchSize, channels, frames * patchSize);
		});
	}
}
