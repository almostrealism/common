/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.layers;

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;

public interface LayerFeatures extends CollectionFeatures {
	default KernelLayer convolution2d(int inputWidth, int inputHeight, int filterCount, int size) {
		int pad = size - 1;
		int outputWidth = inputWidth - pad;
		int outputHeight = inputHeight - pad;
		TraversalPolicy filterShape = shape(filterCount, size, size);
		PackedCollection<?> filters = new PackedCollection<>(filterShape);
		return new KernelLayer(a(filters.getShape().getTotalSize(), p(filters),
					_divide(randn(filterShape).traverseEach(), c(9).traverse(0))),
				TraversableKernelExpression.withShape(shape(outputHeight, outputWidth, filterCount),
					(args, pos) -> args[1].get(shape(1, size, size), pos[2])
						.multiply(args[2].get(shape(size, size), pos[0], pos[1])).sum()),
				filters);
	}
}
