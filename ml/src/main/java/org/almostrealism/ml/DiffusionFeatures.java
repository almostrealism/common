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

import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

public interface DiffusionFeatures extends LayerFeatures {

	default Block timestampEmbeddings(int inputCount, int timeLen) {
		return timestampEmbeddings(inputCount, timeLen, timeLen);
	}

	default Block timestampEmbeddings(int inChannels, int timeLen, int outLen) {
		SequentialBlock block = new SequentialBlock(shape(inChannels, timeLen));
		block.add(dense(inChannels, timeLen));
		block.add(silu(shape(timeLen)));
		block.add(dense(timeLen, outLen));
		return block;
	}
}
