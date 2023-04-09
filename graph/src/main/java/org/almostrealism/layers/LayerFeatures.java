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

import io.almostrealism.code.ExpressionList;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.KernelOperation;
import org.almostrealism.hardware.OperationList;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface LayerFeatures extends CollectionFeatures {

	default KernelLayer layer(TraversalPolicy inputShape, TraversalPolicy outputShape,
							  KernelExpression kernel, Propagation backwards) {
		return new KernelLayer(inputShape, TraversableKernelExpression.withShape(outputShape, kernel), backwards);
	}

	default KernelLayer layer(TraversalPolicy inputShape, TraversalPolicy outputShape, KernelExpression kernel,
							  Propagation backwards, List<PackedCollection<?>> weights) {
		return new KernelLayer(inputShape, TraversableKernelExpression.withShape(outputShape, kernel), backwards, weights);
	}

	default KernelLayer layer(TraversalPolicy inputShape, TraversalPolicy outputShape, KernelExpression kernel,
							  Propagation backwards, List<PackedCollection<?>> weights, Supplier<Runnable> setup) {
		return new KernelLayer(inputShape, TraversableKernelExpression.withShape(outputShape, kernel), backwards, weights, setup);
	}

	default Function<TraversalPolicy, KernelLayer> convolution2d(int size, int filterCount) {
		return shape -> convolution2d(shape, size, filterCount);
	}

	default KernelLayer convolution2d(TraversalPolicy inputShape, int size, int filterCount) {
		int pad = size - 1;
		TraversalPolicy outputShape = shape(inputShape.length(0) - pad, inputShape.length(1) - pad, filterCount);
		TraversalPolicy filterShape = shape(filterCount, size, size);

		KernelExpression kernel = (i, p) ->
				i.v(1).get(shape(1, size, size), p.l(2))
					.multiply(i.v(0).get(shape(size, size), p.l(0), p.l(1))).sum();

		PackedCollection<?> filters = new PackedCollection<>(filterShape);
		Supplier<Runnable> init = new KernelOperation<>(
				divide(randn(filterShape).traverseEach(), c(9).traverse(0)), filters.traverseEach());

		return layer(inputShape, outputShape, kernel, null, List.of(filters), init);
	}

	default Function<TraversalPolicy, KernelLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default KernelLayer pool2d(TraversalPolicy inputShape, int size) {
		TraversalPolicy outputShape = shape(inputShape.length(0) / size, inputShape.length(1) / size, inputShape.length(2));
		KernelExpression kernel = (i, p) -> i.v(0).get(shape(size, size, 1),
																p.l(0).multiply(size),
																p.l(1).multiply(size),
																p.l(2)).max();
		KernelExpression backward = (i, p) -> {
			Expression x = p.l(0).divide(size);
			Expression y = p.l(1).divide(size);
			Expression max = i.v(0).get(shape(size, size, 1), x.multiply(2), y.multiply(2), p.l(2)).max();
			return conditional(max.eq(i.v(0).get(p)), i.v(1).get(x, y, p.l(2)), e(0));
		};

		Propagation propagation = (lr, gradient, input, next) -> {
			PackedCollection<?> out = new PackedCollection<>(inputShape);

			OperationList ops = new OperationList();
			ops.add(kernel(ops.kernelIndex(), inputShape, backward, input, gradient), out.traverseEach());
			if (next != null) ops.add(next.push(p(out)));
			return ops;
		};

		return layer(inputShape, outputShape, kernel, propagation);
	}

	default Function<TraversalPolicy, KernelLayer> dense(int nodes) {
		return shape -> dense(shape.getTotalSize(), nodes);
	}

	default KernelLayer dense(int size, int nodes) {
		TraversalPolicy outputShape = shape(nodes);
		KernelExpression kernel = (i, p) -> i.v(0).multiply(i.v(1)
				.get(shape(size, 1), e(0), p.l(0)))
				.sum().add(i.v(2).get(p.l(0)));

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		PackedCollection<?> biases = new PackedCollection<>(shape(nodes));

		KernelExpression outputGradient = (i, p) -> i.v(0).get(shape(1, nodes), p.l(0)).multiply(i.v(1)).sum();
		KernelExpression weightGradient = (i, p) -> i.v(0).get(p.l(0)).multiply(i.v(1).get(p.l(1)));
		KernelExpression adjustWeights = (i, p) -> i.v(0).get(p.l(0), p.l(1)).subtract(i.v(1).get(p.l(0), p.l(1)).multiply(i.v(2).valueAt(0)));
		KernelExpression adjustBiases = (i, p) -> i.v(0).get(p.l(0)).subtract(i.v(1).get(p.l(0)).multiply(i.v(2).valueAt(0)));

		Propagation backwards = (lr, gradient, input, next) -> {
			OperationList ops = new OperationList();
			PackedCollection<?> out = new PackedCollection<>(shape(size));
			PackedCollection<?> wGrad = new PackedCollection<>(shape(size, nodes));
			CollectionProducerComputation<PackedCollection<?>> output = kernel(ops.kernelIndex(), shape(size), outputGradient, p(weights), gradient);
			CollectionProducerComputation<PackedCollection<?>> wgr = kernel(ops.kernelIndex(), shape(size, nodes), weightGradient, input, gradient);
			CollectionProducerComputation<PackedCollection<?>> dw = kernel(ops.kernelIndex(), shape(size, nodes), adjustWeights, p(weights), p(wGrad), lr);
			CollectionProducerComputation<PackedCollection<?>> bw = kernel(ops.kernelIndex(), shape(nodes), adjustBiases, p(biases), gradient, lr);

			ops.add(output, out.traverseEach());
			ops.add(wgr, wGrad.traverseEach());
			ops.add(dw, weights.traverseEach());
			ops.add(bw, biases.traverseEach());
			if (next != null) ops.add(next.push(p(out)));
			return ops;
		};

		Supplier<Runnable> init = new KernelOperation<>(divide(randn(shape(size, nodes)).traverseEach(), c(size).traverse(0)), weights.traverseEach());
		return layer(shape(size), outputShape, kernel, backwards, List.of(weights, biases), init);
	}

	default Function<TraversalPolicy, KernelLayer> softmax() {
		return shape -> softmax(shape.getTotalSize());
	}

	default KernelLayer softmax(int size) {
		TraversalPolicy shape = shape(size);
		KernelExpression forward = (i, p) -> exp(i.v(0).get(p.l(0))).divide(i.v(0).exp().sum());
		KernelExpression backward = (i, p) -> {
			ExpressionList gradient = i.v(0).toList();
			ExpressionList in = i.v(1).toList().exp();

			Expression t = i.v(1).get(p.l(0)).exp();
			Expression s = in.sum();
			Expression gr = i.v(0).get(p.l(0));

			return gradient.sum().multiply(
					in.minus().multiply(gradient).sum().multiply(t)
					.add(gr.multiply(t).multiply(s.subtract(t)))
					.divide(s.pow(e(2))));
		};

		Propagation propagation = (lr, gradient, input, next) -> {
			OperationList ops = new OperationList();
			PackedCollection<?> output = new PackedCollection<>(shape);
			CollectionProducerComputation<PackedCollection<?>> gr = kernel(ops.kernelIndex(), shape, backward, gradient, input);

			ops.add(new KernelOperation(gr, output.traverseEach()));
			if (next != null) ops.add(next.push(p(output)));
			return ops;
		};

		return layer(shape, shape, forward, propagation);
	}
}
