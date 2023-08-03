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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionOperationList;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import io.almostrealism.collect.Func;
import io.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableKernelExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.hardware.KernelOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface LayerFeatures extends CollectionFeatures {
	boolean enableAssignment = true;

	default CellularLayer layer(TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, Cell<PackedCollection<?>> backward) {
		return new DefaultCellularLayer(outputShape, forward, backward, Collections.emptyList(), new OperationList());
	}

	default CellularLayer layer(TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, Cell<PackedCollection<?>> backward,
								List<PackedCollection<?>> weights, Supplier<Runnable> setup) {
		return new DefaultCellularLayer(outputShape, forward, backward, weights, setup);
	}

	default CellularLayer layer(TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, Propagation backward) {
		return layer(inputShape, outputShape, forward, backward, Collections.emptyList(), new OperationList());
	}

	default CellularLayer layer(TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, Propagation backward,
								List<PackedCollection<?>> weights, Supplier<Runnable> setup) {
		PropagationCell backwardCell = new PropagationCell(backward);
		DefaultCellularLayer layer = new DefaultCellularLayer(outputShape, forward, backwardCell, weights, setup);
		layer.init(inputShape);
		backwardCell.setForwardInput(layer.getInput());
		return layer;
	}

	default Function<TraversalPolicy, Block> flatten() {
		return shape -> {
			TraversalPolicy outputShape = shape.flatten();
			return new DefaultBlock(shape, outputShape,
					Cell.of((in, next) -> next.push(reshape(outputShape, in))),
					Cell.of((in, next) -> next.push(reshape(shape, in))));
		};
	}

	default Function<TraversalPolicy, CellularLayer> convolution2d(int size, int filterCount) {
		return shape -> convolution2d(shape, size, filterCount);
	}

	default CellularLayer convolution2d(TraversalPolicy inputShape, int size, int filterCount) {
		int pad = size - 1;
		TraversalPolicy outputShape = shape(inputShape.length(0) - pad, inputShape.length(1) - pad, filterCount);
		TraversalPolicy filterShape = shape(filterCount, size, size);
		PackedCollection<?> filters = new PackedCollection<>(filterShape);

		Supplier<Runnable> init = new KernelOperation<>(
				divide(randn(filterShape).traverseEach(), c(9).traverse(0)), filters.traverseEach());

		return layer(inputShape, outputShape,
				Cell.of((input, next) -> {
					PackedCollection<?> output = new PackedCollection<>(outputShape);

					OperationList ops = new OperationList();
					Producer<PackedCollection<?>> conv =
							c(input).enumerate(1, size, 1)
									.enumerate(1, size, 1)
									.traverse(2)
									.expand(filterCount, v -> v.repeat(filterCount).multiply(p(filters)))
									.traverse()
									.reduce(v -> v.sum());

					if (enableAssignment) {
						ops.add(1, conv, p(output.traverseEach()));
					} else {
						ops.add(conv, output.traverseEach());
					}

					if (next != null) ops.add(next.push(p(output)));
					return ops;
				}),
				(lr, gradient, input, next) -> {
					CollectionOperationList ops = new CollectionOperationList();
					PackedCollection<?> filterGrad = new PackedCollection<>(filterShape);

					ops.kernel((_gradient, _input, f, fx, fy) ->
							shape(outputShape.length(0), outputShape.length(1)).stream()
									.map(pos -> _gradient.get(e(pos[0]), e(pos[1]), f).multiply(_input.get(e(pos[0]).add(fx), e(pos[1]).add(fy))))
									.collect(ExpressionList.collector())
									.sum(), filterGrad.traverseEach(), gradient, input);
					ops.kernel((_filters, _gradient, _lr, x, y, z) ->
									_filters.get(x, y, z).subtract(_gradient.get(x, y, z).multiply(_lr.valueAt(0))),
							filters.traverseEach(), p(filters), p(filterGrad), lr);
					if (next != null) {
						System.out.println("WARN: convolution2d does not support backpropagation");
					}
					return ops;
				}, List.of(filters), init);
	}

	default Function<TraversalPolicy, CellularLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default CellularLayer pool2d(TraversalPolicy inputShape, int size) {
		TraversalPolicy outputShape = shape(inputShape.length(0) / size, inputShape.length(1) / size, inputShape.length(2));

		KernelExpression backward = (i, p) -> {
			Expression x = p.l(0).divide(size);
			Expression y = p.l(1).divide(size);
			Expression max = i.v(0).get(shape(size, size, 1), x.multiply(2), y.multiply(2), p.l(2)).max();
			return conditional(max.eq(i.v(0).getValue(p)), i.v(1).get(x, y, p.l(2)), e(0));
		};

		Propagation propagation = (lr, gradient, input, next) -> {
			PackedCollection<?> out = new PackedCollection<>(inputShape);

			OperationList ops = new OperationList();
			ops.add(kernel(ops.kernelIndex(), inputShape, backward, input, gradient), out.traverseEach());
			if (next != null) ops.add(next.push(p(out)));
			return ops;
		};

		return layer(inputShape, outputShape,
				Cell.of((input, next) -> {
					PackedCollection<?> output = new PackedCollection<>(outputShape);
					int d = outputShape.length(2);

					OperationList ops = new OperationList();
					Producer<PackedCollection<?>> pool = c(input).enumerate(1, size)
									.enumerate(1, size)
									.traverse(2)
									.map(shape(d, 1), v ->
											enumerate(shape(1, 1, size, size, 1), v)
													.traverse(1).reduce(slice -> max(slice)));

					if (enableAssignment) {
						ops.add(output.traverse(2).getShape().getSize(),
								pool, p(output.traverse(2)));
					} else {
						ops.add(pool, output.traverse(2));
					}

					if (next != null) ops.add(next.push(p(output)));
					return ops;
				}), propagation);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int nodes) {
		return shape -> dense(shape.getTotalSize(), nodes);
	}

	default CellularLayer dense(int size, int nodes) {
		TraversalPolicy outputShape = shape(nodes);

		PackedCollection<?> weights = new PackedCollection<>(shape(size, nodes));
		PackedCollection<?> biases = new PackedCollection<>(shape(nodes));

		KernelExpression outputGradient = (i, p) -> i.v(0).get(shape(1, nodes), p.l(0)).multiply(i.v(1)).sum();
		KernelExpression weightGradient = (i, p) -> i.v(0).getRelative(p.l(0)).multiply(i.v(1).getRelative(p.l(1)));
		KernelExpression adjustWeights = (i, p) -> i.v(0).get(p.l(0), p.l(1)).subtract(i.v(1).get(p.l(0), p.l(1)).multiply(i.v(2).valueAt(0)));
		KernelExpression adjustBiases = (i, p) -> i.v(0).getRelative(p.l(0)).subtract(i.v(1).getRelative(p.l(0)).multiply(i.v(2).valueAt(0)));

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

		return layer(shape(size), outputShape,
				Cell.of((input, next) -> {
					PackedCollection<?> output = new PackedCollection<>(outputShape);

					OperationList ops = new OperationList();
					Producer<PackedCollection<?>> dense =
							c(input).repeat(nodes).traverseEach()
									.multiply(c(p(weights))
											.enumerate(1, 1))
									.traverse(1).sum()
									.add(p(biases));

					if (enableAssignment) {
						ops.add(output.traverse(1).getShape().getSize(), dense, p(output.traverse(1)));
					} else {
						ops.add(dense, output.traverse(1));
					}

					if (next != null) ops.add(next.push(p(output)));
					return ops;
				}), backwards, List.of(weights, biases), init);
	}

	default Function<TraversalPolicy, CellularLayer> softmax() {
		return shape -> softmax(shape.getTotalSize());
	}

	default CellularLayer softmax(int size) {
		TraversalPolicy shape = shape(size);

		KernelExpression backward = (i, p) -> {
			ExpressionList gradient = i.v(0).toList();
			ExpressionList in = i.v(1).toList().exp();

			Expression t = i.v(1).getRelative(p.l(0)).exp();
			Expression s = in.sum();
			Expression gr = i.v(0).getRelative(p.l(0));

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

		return layer(shape, shape, Cell.of((input, next) -> {
			PackedCollection<?> output = new PackedCollection<>(shape);

			OperationList ops = new OperationList();
			Producer<PackedCollection<?>> softmax =
					c(input).traverse(1).exp()
							.divide(c(input).traverse(1).exp().traverse(0).sum());

			if (enableAssignment) {
				ops.add(output.traverse(1).getShape().getSize(), softmax, p(output.traverse(1)));
			} else {
				ops.add(softmax, output.traverse(1));
			}

			if (next != null) ops.add(next.push(p(output)));
			return ops;
		}), propagation);
	}
}
