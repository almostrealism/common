/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Factor;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

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
		SequentialBlock block = new SequentialBlock(shape(inputCount, timeLen));
		block.add(dense(inputCount, timeLen));
		block.add(silu(shape(timeLen)));
		block.add(dense(timeLen, outLen));
		return block;
	}
}
