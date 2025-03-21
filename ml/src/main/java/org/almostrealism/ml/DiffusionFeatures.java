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

public interface DiffusionFeatures extends LayerFeatures {

	default Factor<PackedCollection<?>> timesteps(int inputCount, boolean flip, double downscaleFreqShift) {
		return timesteps(inputCount, flip, downscaleFreqShift, 1.0);
	}

	default Factor<PackedCollection<?>> timesteps(int inputCount, boolean flip, double downscaleFreqShift, double scale) {
		return timesteps(inputCount, flip, downscaleFreqShift, scale, 10000);
	}

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

	default Block timestepEmbeddings(int inputCount, int timeLen) {
		return timestepEmbeddings(inputCount, timeLen, timeLen);
	}

	default Block timestepEmbeddings(int inputCount, int timeLen, int outLen) {
		SequentialBlock block = new SequentialBlock(shape(inputCount));
		block.add(dense(inputCount, timeLen));
		block.add(silu(shape(timeLen)));
		block.add(dense(timeLen, outLen));
		return block;
	}

	default Function<TraversalPolicy, Block> upsample(int dim) {
		return upsample(dim, dim);
	}

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
									.reshape(batchSize, inputChannels, h * 2, w * 2)));
			upsample.add(convolution2d(dim, dimOut, 3, 1));
			return upsample;
		};
	}

	default Function<TraversalPolicy, Block> downsample(int dim) {
		return downsample(dim, dim);
	}

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
