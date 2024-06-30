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
	default Model convolution2dModel(int r, int c, int convSize, int convFilters, int convLayers, int denseSize) {
		return convolution2dModel(r, c, convSize, convFilters, convLayers, denseSize, false);
	}

	default Model convolution2dModel(int r, int c, int convSize, int convFilters, int convLayers,
									 int denseSize, boolean logSoftmax) {
		Model model = new Model(shape(r, c));

		for (int i = 0; i < convLayers; i++) {
			model.addLayer(convolution2d(convSize, convFilters));
			model.addLayer(pool2d(2));
		}

		model.addBlock(flatten());
		model.addLayer(dense(denseSize));
		model.addLayer(logSoftmax ? logSoftmax() : softmax());
		return model;
	}
}
