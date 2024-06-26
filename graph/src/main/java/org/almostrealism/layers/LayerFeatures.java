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

import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface LayerFeatures extends MatrixFeatures {
	boolean ioTracking = SystemUtils.isEnabled("AR_GRAPH_IO_TRACKING").orElse(true);

	@Deprecated
	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, Propagation backward,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, forward, backward,
				Collections.emptyList(), new OperationList(), requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection<?>> operator,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, operator, Collections.emptyList(), requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection<?>> operator,
								List<PackedCollection<?>> weights,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, operator, weights, new OperationList(), requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Factor<PackedCollection<?>> operator,
								List<PackedCollection<?>> weights,
								Supplier<Runnable> setup,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, Cell.of(operator),
				new GradientPropagation(operator, weights.stream().map(this::cp)),
				weights, setup, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, Propagation backward,
								List<PackedCollection<?>> weights, Supplier<Runnable> setup,
								ComputeRequirement... requirements) {
		PropagationCell backwardCell = new PropagationCell(name, backward);
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape, forward, backwardCell, weights, setup);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, ioTracking, true);
		backwardCell.setForwardInput(layer.getInput());
		return layer;
	}

	default CollectionReceptor into(PackedCollection<?> dest) {
		return new CollectionReceptor(dest);
	}

	default CollectionReceptor into(PackedCollection<?> dest, Producer<PackedCollection<?>> pos) {
		return new CollectionReceptor(dest, pos);
	}

	default Function<TraversalPolicy, Block> flatten() {
		return shape -> {
			TraversalPolicy outputShape = shape.flatten();
			return new DefaultBlock(shape, outputShape,
					Cell.of((in, next) -> next.push(reshape(outputShape, in))),
					Cell.of((in, next) -> next.push(reshape(shape, in))));
		};
	}

	default Block reshape(TraversalPolicy inputShape, TraversalPolicy outputShape) {
		if (inputShape.getTotalSize() != outputShape.getTotalSize()) {
			throw new IllegalArgumentException("Cannot reshape " + inputShape + " to " + outputShape);
		}

		return new DefaultBlock(inputShape, outputShape,
				Cell.of((in, next) -> next.push(reshape(outputShape, in))),
				Cell.of((in, next) -> next.push(reshape(inputShape, in))));
	}

	default Function<TraversalPolicy, CellularLayer> convolution2d(int size, int filterCount, ComputeRequirement... requirements) {
		return shape -> convolution2d(shape, size, filterCount, requirements);
	}

	default CellularLayer convolution2d(TraversalPolicy inputShape, int size, int filterCount, ComputeRequirement... requirements) {
		int pad = size - 1;
		TraversalPolicy outputShape = shape(inputShape.length(0) - pad, inputShape.length(1) - pad, filterCount);
		TraversalPolicy filterShape = shape(filterCount, size, size);
		PackedCollection<?> filters = new PackedCollection<>(filterShape);

		Factor<PackedCollection<?>> operator = input ->
				c(input).enumerate(1, size, 1)
						.enumerate(1, size, 1)
						.traverse(2)
						.repeat(filterCount)
						.traverse(2)
						.multiply(cp(filters)
								.repeat(outputShape.length(1)).traverse(0)
								.repeat(outputShape.length(0)).traverse(2))
						.traverse()
						.sum();
		return layer("convolution2d", inputShape, outputShape,
				operator, List.of(filters),
				a(p(filters.each()), divide(randn(filterShape).traverseEach(), c(size * size).traverse(0))),
				requirements);
	}

	default Function<TraversalPolicy, CellularLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default CellularLayer pool2d(TraversalPolicy inputShape, int size, ComputeRequirement... requirements) {
		TraversalPolicy outputShape = shape(inputShape.length(0) / size, inputShape.length(1) / size, inputShape.length(2));
		int d = outputShape.length(2);

		Factor<PackedCollection<?>> operator = input ->
				c(input)
						.enumerate(2, 1)
						.enumerate(2, size)
						.enumerate(2, size)
						.traverse(3)
						.max();
		return layer("pool2d", inputShape, outputShape, operator, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int nodes) {
		return shape -> dense(shape.getTotalSize(), nodes);
	}

	default CellularLayer dense(int size, int nodes) {
		return dense(size, nodes, true);
	}

	default CellularLayer dense(int size, int nodes, boolean bias) {
		return dense(size, nodes, bias, true);
	}

	default CellularLayer dense(int size, int nodes, boolean bias, boolean init, ComputeRequirement... requirements) {
		return dense(new PackedCollection<>(shape(nodes, size)), bias, init, requirements);
	}

	default CellularLayer dense(PackedCollection<?> weights, ComputeRequirement... requirements) {
		return dense(weights, false, false, requirements);
	}

	default CellularLayer dense(PackedCollection<?> weights, boolean bias, boolean init, ComputeRequirement... requirements) {
		TraversalPolicy weightShape = weights.getShape();
		if (weightShape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		int nodes = weightShape.length(0);
		int size = weightShape.length(1);

		PackedCollection<?> biases = bias ? new PackedCollection<>(shape(nodes)) : null;

		Factor<PackedCollection<?>> operator = input ->
				bias ? matmul(p(weights), input).add(traverse(1, p(biases))) : matmul(p(weights), input);

		return layer("dense " + size, shape(size), shape(nodes),
				operator, bias ? List.of(weights, biases) : List.of(weights),
				init ?
						a(p(weights.each()), divide(randn(shape(size, nodes)).each(), c(size).all()))
						: new OperationList(),
				requirements);
	}

	default Function<TraversalPolicy, CellularLayer> softmax() {
		return shape -> softmax(shape.getTotalSize());
	}

	default CellularLayer softmax(int size) {
		TraversalPolicy shape = shape(size);

		return layer("softmax", shape, shape,
				input -> c(input).traverse(1).exp().divide(c(input).traverse(1).exp().traverse(0).sum()));
	}

	default CellularLayer softmax2d(TraversalPolicy shape, boolean subtractMax, ComputeRequirement... requirements) {
		if (shape.getDimensions() != 2)
			throw new IllegalArgumentException();

		int heads = shape.length(0);
		int seqLen = shape.length(1);

		return layer("softmax2d", shape, shape, input -> {
			CollectionProducer<PackedCollection<?>> o = traverse(1, input);

			if (subtractMax) {
				o = o.max();
				o = o.expand(seqLen);
				o = traverse(2, input).subtractIgnoreZero(o);
			}

			o = o.expIgnoreZero().traverse(1);
			o = o.divide(o.sum().expand(seqLen));

			return o;
		}, requirements);
	}

	default CellularLayer accum(Producer<PackedCollection<?>> value) {
		TraversalPolicy shape = shape(value);
//		return layer("accum", shape, shape, Cell.of((input, next) -> {
//			return next == null ? new OperationList() : next.push(add(traverseEach(input), traverseEach(value)));
//		}), null);
		throw new UnsupportedOperationException();
	}

	default CellularLayer accum(TraversalPolicy shape, Cell<PackedCollection<?>> value, ComputeRequirement... requirements) {
		return layer("accum", shape, shape, Cell.of((input, next) -> {
			CaptureReceptor r = new CaptureReceptor();
			value.setReceptor(r);

			OperationList ops = new OperationList();
			ops.add(value.push(input));
			if (next != null) ops.add(next.push(add(traverseEach(input), traverseEach(r.getReceipt()))));
			return ops;
		}), null, requirements);
	}

	default CellularLayer product(Producer<PackedCollection<?>> value, ComputeRequirement... requirements) {
		TraversalPolicy shape = shape(value);
		return layer("product", shape, shape, input -> {
			return multiply(traverseEach(input), traverseEach(value));
		}, requirements);
	}

	default CellularLayer product(TraversalPolicy shape, Cell<PackedCollection<?>> value, ComputeRequirement... requirements) {
//		return layer("product", shape, shape, Cell.of((input, next) -> {
//			CaptureReceptor r = new CaptureReceptor();
//			value.setReceptor(r);
//
//			OperationList ops = new OperationList();
//			ops.add(value.push(input));
//			if (next != null) ops.add(next.push(multiply(traverseEach(input), traverseEach(r.getReceipt()))));
//			return ops;
//		}), null, requirements);
		throw new UnsupportedOperationException();
	}

	default CellularLayer product(TraversalPolicy inputShape, TraversalPolicy outputShape,
								  Cell<PackedCollection<?>> a, Cell<PackedCollection<?>> b,
								  ComputeRequirement... requirements) {
		return layer("product", inputShape, outputShape, Cell.of((input, next) -> {
			CaptureReceptor ar = new CaptureReceptor();
			a.setReceptor(ar);

			CaptureReceptor br = new CaptureReceptor();
			b.setReceptor(br);

			OperationList ops = new OperationList();
			ops.add(a.push(input));
			ops.add(b.push(input));
			if (next != null)
				ops.add(next.push(multiply(traverseEach(ar.getReceipt()), traverseEach(br.getReceipt()))));
			return ops;
		}), null, requirements);
	}

	default CellularLayer silu(TraversalPolicy shape, ComputeRequirement... requirements) {
		if (shape.getDimensions() != 1)
			throw new IllegalArgumentException();

		return layer("silu", shape, shape, input -> multiply(traverseEach(input), sigmoid(traverseEach(input))), requirements);
	}

	default CellularLayer rmsnorm(int size) {
		return rmsnorm(new PackedCollection<>(shape(size)));
	}

	default CellularLayer rmsnorm(PackedCollection<?> weights, ComputeRequirement... requirements) {
		TraversalPolicy shape = weights.getShape();
		if (shape.getDimensions() != 1)
			throw new IllegalArgumentException();

		int size = shape.getTotalSize();

//		return layer("rmsnorm", shape, shape, Cell.of((input, next) -> {
//			CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(input), c(2.0)).traverse(0).sum();
//			ss = ss.divide(c(size)).add(c(1e-5));
//			ss = c(1.0).divide(ss.pow(c(0.5)));
//			return next == null ? new OperationList() : next.push(multiply(traverseEach(p(weights)), traverseEach(input)).multiply(ss));
//		}), null, List.of(weights), requirements);
		return layer("rmsnorm", shape, shape, input -> {
			CollectionProducer<PackedCollection<?>> ss = pow(traverseEach(input), c(2.0)).traverse(0).sum();
			ss = ss.divide(c(size)).add(c(1e-5));
			ss = c(1.0).divide(ss.pow(c(0.5)));
			return multiply(traverseEach(p(weights)), traverseEach(input)).multiply(ss);
		}, List.of(weights), requirements);
	}

	class CaptureReceptor implements Receptor<PackedCollection<?>> {
		private Producer<PackedCollection<?>> receipt;

		public Producer<PackedCollection<?>> getReceipt() { return receipt; }

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection<?>> in) {
			receipt = in;
			return new OperationList();
		}
	}
}
