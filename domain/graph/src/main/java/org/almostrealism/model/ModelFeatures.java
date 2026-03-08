/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.model;

import org.almostrealism.CodeFeatures;

public interface ModelFeatures extends CodeFeatures {
	default Model convolution2dModel(int h, int w, int convSize, int convFilters, int convLayers,
									 int denseSize) {
		return convolution2dModel(h, w, convSize, convFilters, convLayers,
				-1, denseSize);
	}

	default Model convolution2dModel(int h, int w, int convSize, int convFilters, int convLayers,
									 int groups, int denseSize) {
		return convolution2dModel(1, 1, h, w, convSize, convFilters, convLayers,
				groups, denseSize, false);
	}

	default Model convolution2dModel(int batchSize, int channels, int h, int w,
									 int convSize, int convFilters, int convLayers,
									 int groups, int denseSize, boolean logSoftmax) {
		Model model = new Model(shape(batchSize, channels, h, w));

		for (int i = 0; i < convLayers; i++) {
			model.add(convolution2d(convFilters, convSize));
			if (groups > 0 && i > 0)
				model.add(norm(groups));
			model.add(pool2d(2));
		}

		model.add(flattened());
		model.add(dense(denseSize));
		model.add(logSoftmax ? logSoftmax() : softmax());
		return model;
	}
}
