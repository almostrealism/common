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
import org.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.KernelOperation;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface LayerFeatures extends CollectionFeatures {

	default KernelLayer layer(TraversalPolicy shape, KernelExpression kernel, Cell<PackedCollection<?>> backwards) {
		return new KernelLayer(TraversableKernelExpression.withShape(shape, kernel), backwards);
	}

	default KernelLayer layer(TraversalPolicy shape, KernelExpression kernel,
							  Cell<PackedCollection<?>> backwards, List<PackedCollection<?>> weights) {
		return new KernelLayer(TraversableKernelExpression.withShape(shape, kernel), backwards, weights);
	}

	default KernelLayer layer(TraversalPolicy shape, KernelExpression kernel, Cell<PackedCollection<?>> backwards,
							  List<PackedCollection<?>> weights, Supplier<Runnable> setup) {
		return new KernelLayer(TraversableKernelExpression.withShape(shape, kernel), backwards, weights, setup);
	}

	default Function<TraversalPolicy, KernelLayer> convolution2d(int size, int filterCount) {
		return shape -> convolution2d(shape, size, filterCount);
	}

	default KernelLayer convolution2d(TraversalPolicy inputShape, int size, int filterCount) {
		int pad = size - 1;
		TraversalPolicy outputShape = shape(inputShape.length(0) - pad, inputShape.length(1) - pad, filterCount);
		TraversalPolicy filterShape = shape(filterCount, size, size);

		KernelExpression kernel = (args, pos) ->
				args[2].get(shape(1, size, size), pos[2])
					.multiply(args[1].get(shape(size, size), pos[0], pos[1])).sum();

		PackedCollection<?> filters = new PackedCollection<>(filterShape);
		Supplier<Runnable> init = new KernelOperation<>(
				_divide(randn(filterShape).traverseEach(), c(9).traverse(0)), filters.traverseEach());

		return layer(outputShape, kernel, null, List.of(filters), init);
	}

	default Function<TraversalPolicy, KernelLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default KernelLayer pool2d(TraversalPolicy inputShape, int size) {
		TraversalPolicy outputShape = shape(inputShape.length(0) / size, inputShape.length(1) / size, inputShape.length(2));
		KernelExpression kernel = (args, pos) -> args[1].get(shape(size, size, 1), pos[0].multiply(size), pos[1].multiply(size), pos[2]).max();
		return layer(outputShape, kernel, null);
	}

	default Function<TraversalPolicy, KernelLayer> dense(int nodes) {
		return shape -> dense(shape.getTotalSize(), nodes);
	}

	default KernelLayer dense(int size, int nodes) {
		TraversalPolicy outputShape = shape(nodes);
		KernelExpression kernel = (args, pos) -> args[1].multiply(args[2].get(shape(size, 1), e(0), pos[0])).sum().add(args[3].get(pos[0]));

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		PackedCollection<?> biases = new PackedCollection<>(shape(nodes));
		Supplier<Runnable> init = new KernelOperation<>(_divide(randn(shape(size, nodes)).traverseEach(), c(size).traverse(0)), weights.traverseEach());
		return layer(outputShape, kernel, null, List.of(weights, biases), init);
	}

	default Function<TraversalPolicy, KernelLayer> softmax() {
		return shape -> softmax(shape.getTotalSize());
	}

	default KernelLayer softmax(int size) {
		TraversalPolicy outputShape = shape(size);
		KernelExpression kernel = (args, pos) -> exp(args[1].get(pos[0])).divide(args[1].exp().sum());
		return layer(outputShape, kernel, null);
	}
}
