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

import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.OperationList;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class Convolution2D implements Layer, Setup, CollectionFeatures {
	private int inputWidth;
	private int inputHeight;

	private int size = 3;
	private int filterCount;
	private TraversalPolicy filterShape;
	private PackedCollection<?> filters;
	private PackedCollection<?> lastInput;

	public Convolution2D(int inputWidth, int inputHeight, int filters) {
		this.filterCount = filters;
		this.filterShape = shape(filterCount, size, size);
		this.filters = new PackedCollection<>(filterShape);
		this.lastInput = new PackedCollection<>(shape(inputHeight, inputWidth));
	}

	public PackedCollection<?> output() {
		int pad = size - 1;
		int outputWidth = inputWidth - pad;
		int outputHeight = inputHeight - pad;
		return new PackedCollection<>(shape(outputHeight, outputWidth, filterCount));
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList();
		setup.add(a(filters.getShape().getTotalSize(), p(filters),
				_divide(randn(filterShape).traverseEach(), c(9).traverse(0))));
		return setup;
	}

	public void iterateRegions(CollectionProducer<?> image, RegionConsumer results) {
		if (inputWidth != image.getShape().length(1) || inputHeight != image.getShape().length(0)) {
			throw new IllegalArgumentException("Image dimensions do not match input dimensions");
		}

		int pad = size - 1;

		for (int i = 0; i < inputHeight - pad; i++) {
			for (int j = 0; j < inputWidth - pad; j++) {
				results.accept(subset(shape(size, size), image, i, j), i, j);
			}
		}
	}

	@Override
	public Supplier<Runnable> forward(CollectionProducer<PackedCollection<?>> input, PackedCollection<?> output) {
		int pad = size - 1;
		int outputWidth = inputWidth - pad;
		int outputHeight = inputHeight - pad;
		if (outputHeight != output.getShape().length(0) ||
				outputWidth != output.getShape().length(1) ||
				filterCount != output.getShape().length(2)) {
			throw new IllegalArgumentException("Output dimensions do not match layer dimensions");
		}

		OperationList forward = new OperationList();
		forward.add(a(inputWidth * inputHeight, p(lastInput), input));

		iterateRegions(input, (region, i, j) -> {
			forward.add(a(filterCount,
					p(output.range(shape(filterCount), output.getShape().index(i, j, 0))),
					_multiply(region, p(filters))));
		});

//		for im_region, i, j in self.iterate_regions(input):
//			output[i, j] = np.sum(im_region * self.filters, axis=(1, 2))

		return forward;
	}

	protected interface RegionConsumer {
		void accept(Producer<PackedCollection<?>> region, int i, int j);
	}
}
