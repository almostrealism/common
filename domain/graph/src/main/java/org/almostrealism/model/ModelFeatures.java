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

/**
 * A mixin that provides factory methods for building standard CNN-based {@link Model} instances.
 *
 * <p>Implementors gain access to {@link #convolution2dModel} variants that assemble a
 * sequence of 2D convolution, optional group normalization, 2×2 max-pooling, flattening,
 * and dense classification layers in one call.</p>
 *
 * @see Model
 * @see org.almostrealism.layers.LayerFeatures
 * @author Michael Murray
 */
public interface ModelFeatures extends CodeFeatures {
	/**
	 * Builds a 2-D convolutional model with a single image channel and batch size 1.
	 *
	 * @param h           the height of the input images in pixels
	 * @param w           the width of the input images in pixels
	 * @param convSize    the spatial size of each convolutional filter
	 * @param convFilters the number of convolutional filters per layer
	 * @param convLayers  the number of consecutive convolution+pool blocks
	 * @param denseSize   the number of output classes for the final dense layer
	 * @return a fully assembled {@link Model}
	 */
	default Model convolution2dModel(int h, int w, int convSize, int convFilters, int convLayers,
									 int denseSize) {
		return convolution2dModel(h, w, convSize, convFilters, convLayers,
				-1, denseSize);
	}

	/**
	 * Builds a 2-D convolutional model with optional group normalization.
	 *
	 * @param h           the height of the input images in pixels
	 * @param w           the width of the input images in pixels
	 * @param convSize    the spatial size of each convolutional filter
	 * @param convFilters the number of convolutional filters per layer
	 * @param convLayers  the number of consecutive convolution+pool blocks
	 * @param groups      the number of groups for group normalization, or {@code -1} to skip normalization
	 * @param denseSize   the number of output classes for the final dense layer
	 * @return a fully assembled {@link Model}
	 */
	default Model convolution2dModel(int h, int w, int convSize, int convFilters, int convLayers,
									 int groups, int denseSize) {
		return convolution2dModel(1, 1, h, w, convSize, convFilters, convLayers,
				groups, denseSize, false);
	}

	/**
	 * Builds a fully parameterized 2-D convolutional model.
	 *
	 * <p>The assembled pipeline is:</p>
	 * <ol>
	 *   <li>{@code convLayers} repetitions of: convolution2d → optional group norm → pool2d(2)</li>
	 *   <li>flattened()</li>
	 *   <li>dense({@code denseSize})</li>
	 *   <li>logSoftmax() or softmax() depending on {@code logSoftmax}</li>
	 * </ol>
	 *
	 * @param batchSize   the number of examples per batch
	 * @param channels    the number of input channels (e.g., 1 for grayscale, 3 for RGB)
	 * @param h           the height of the input images in pixels
	 * @param w           the width of the input images in pixels
	 * @param convSize    the spatial size of each convolutional filter
	 * @param convFilters the number of convolutional filters per layer
	 * @param convLayers  the number of consecutive convolution+pool blocks
	 * @param groups      the number of groups for group normalization, or {@code -1} to skip normalization
	 * @param denseSize   the number of output classes for the final dense layer
	 * @param logSoftmax  when {@code true} applies log-softmax; when {@code false} applies softmax
	 * @return a fully assembled {@link Model}
	 */
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
