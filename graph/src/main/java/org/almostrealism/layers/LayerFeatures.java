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
import io.almostrealism.relation.Composition;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface LayerFeatures extends MatrixFeatures, ConsoleFeatures {
	boolean ioTracking = SystemUtils.isEnabled("AR_GRAPH_IO_TRACKING").orElse(true);

	@Deprecated
	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, BackPropagation backward,
								ComputeRequirement... requirements) {
		return layer(name, inputShape, outputShape, forward, backward,
				Collections.emptyList(), new OperationList(), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> layer(String name,
														   Factor<PackedCollection<?>> operator,
														   ComputeRequirement... requirements) {
		return shape -> layer(name, shape, operator, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy shape,
								Factor<PackedCollection<?>> operator,
								ComputeRequirement... requirements) {
		return layer(name, shape, shape, operator, requirements);
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
				new DefaultGradientPropagation(operator, weights.stream().map(this::cp)),
				weights, setup, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, BackPropagation backward,
								List<PackedCollection<?>> weights, Supplier<Runnable> setup,
								ComputeRequirement... requirements) {
		BackPropagationCell backwardCell = new BackPropagationCell(name, backward);
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape, forward, backwardCell, weights, setup);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, ioTracking, true);
		backwardCell.setForwardInput(layer.getInput());
		return layer;
	}

	default CellularLayer compose(String name,
								  TraversalPolicy shape,
								  CellularPropagation<PackedCollection<?>> aux,
								  Composition<PackedCollection<?>> operator,
								  ComputeRequirement... requirements) {
		return compose(name, shape, shape, shape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy inputShape,
								  TraversalPolicy auxShape,
								  TraversalPolicy outputShape,
								  CellularPropagation<PackedCollection<?>> aux,
								  Composition<PackedCollection<?>> operator,
								  ComputeRequirement... requirements) {
		PackedCollection<?> auxInput = ioTracking ? new PackedCollection<>(auxShape) : null;

		// Capture the input coming in via aux, storing
		// the actual value (if it is necessary)
		Cell<PackedCollection<?>> auxExit = Cell.of((in, next) -> {
			if (auxInput == null) {
				return next.push(in);
			} else {
				OperationList op = new OperationList(name + " composed layer (Entry)");
				op.add(into(name + " composed layer (Input Record)", in,
						p(auxInput), DefaultCellularLayer.enableMemoryDataCopy));
				op.add(next.push(p(auxInput)));
				return op;
			}
		});
		aux.getForward().setReceptor(auxExit);

		// Capture the result intended as input for the composition
		CaptureReceptor auxReceptor = new CaptureReceptor();
		auxExit.setReceptor(auxReceptor);

		// Create a layer that composes its input with whatever was received for aux
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape,
				Cell.of((input, next) -> next == null ? new OperationList() :
						next.push(operator.compose(input, auxReceptor.getReceipt()))),
				null);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, ioTracking, true);

		// Create gradient propagation for the main input
		BackPropagationCell mainBackward = new BackPropagationCell(name,
				new DefaultGradientPropagation(in -> operator.compose(in, p(auxInput))));
		mainBackward.setForwardInput(layer.getInput());

		// Create gradient propagation for the aux input
		// and direct its output to the aux backward Cell
		BackPropagationCell auxBackward = new BackPropagationCell(name,
				new DefaultGradientPropagation(in -> operator.compose(p(layer.getInput()), in)));
		auxBackward.setForwardInput(auxInput);
		auxBackward.setReceptor(aux.getBackward());

		// Combine both backpropagation steps and attach the result to the layer
		layer.setBackward(new Cell<>() {
			@Override
			public Supplier<Runnable> push(Producer<PackedCollection<?>> input) {
				OperationList op = new OperationList(name + " Composed Backward");
				op.add(auxBackward.push(input));
				op.add(mainBackward.push(input));
				return op;
			}

			@Override
			public void setReceptor(Receptor<PackedCollection<?>> r) {
				mainBackward.setReceptor(r);
			}
		});
		return layer;
	}

	default <T extends MemoryData> Supplier<Runnable> into(String name,
														   Producer<T> in, Producer<T> out,
														   boolean copy,
														   ComputeRequirement... requirements) {
		return into(name, in, out, copy, requirements.length > 0 ? List.of(requirements) : null);
	}

	default <T extends MemoryData> Supplier<Runnable> into(String name,
														   Producer<T> in, Producer<T> out,
														   boolean copy,
														   List<ComputeRequirement> requirements) {
		TraversalPolicy shape = shape(in);

		OperationList op = new OperationList(name);
		op.setComputeRequirements(requirements);

		if (!copy || shape.getCountLong() > 1) {
			if (shape.equalsIgnoreAxis(shape(out))) {
				op.add(a(name, traverse(shape.getTraversalAxis(), (Producer) out), in));
			} else {
				op.add(a(name, reshape(shape, out), in));
			}
		} else {
			if (!DefaultCellularLayer.enableMemoryDataCopy)
				warn("Using MemoryDataCopy instead of Assignment for " + name);
			op.add(Ops.o().copy(name, in, out, shape.getTotalSize()));
		}

		return op;
	}

	default CollectionReceptor into(PackedCollection<?> dest) {
		return new CollectionReceptor(dest);
	}

	default CollectionReceptor into(PackedCollection<?> dest, Producer<PackedCollection<?>> pos) {
		return new CollectionReceptor(dest, pos);
	}

	default Function<TraversalPolicy, Block> flattened() {
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


	default Function<TraversalPolicy, CellularLayer> convolution2d(int inputChannels, int filterCount, int size, int padding,
																   ComputeRequirement... requirements) {
		return convolution2d(inputChannels, filterCount, size, padding, true, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> convolution2d(int inputChannels, int filterCount, int size, int padding,
																   boolean bias, ComputeRequirement... requirements) {
		if (inputChannels != 1) {
			return shape -> {
				int c = shape.getDimensions() > 2 ? shape.length(shape.getDimensions() - 2) : 1;
				if (c != inputChannels) {
					throw new IllegalArgumentException();
				}

				return convolution2d(shape, filterCount, size, padding, bias, requirements);
			};
		}

		return shape -> convolution2d(shape, filterCount, size, padding, bias, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> convolution2d(int filterCount, int size, ComputeRequirement... requirements) {
		return convolution2d(filterCount, size, 0, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> convolution2d(int filterCount, int size, int padding, ComputeRequirement... requirements) {
		return shape -> convolution2d(shape, filterCount, size, padding, true, requirements);
	}

	default CellularLayer convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, true, requirements);
	}

	default CellularLayer convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, int padding,
										boolean bias, ComputeRequirement... requirements) {
		if (inputShape.getDimensions() == 2) {
			inputShape = inputShape.prependDimension(1);
		}

		if (inputShape.getDimensions() == 3) {
			inputShape = inputShape.prependDimension(1);
		}

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int h = inputShape.length(2);
		int w = inputShape.length(3);

		int batch = inputShape.length(0);
		int channels = inputShape.length(1);
		int height = h + 2 * padding;
		int width = w + 2 * padding;

		int diff = size - 1;
		TraversalPolicy outputShape = shape(batch, filterCount, height - diff, width - diff);

		TraversalPolicy filterShape = shape(filterCount, channels, size, size);
		PackedCollection<?> filters = new PackedCollection<>(filterShape);

		TraversalPolicy biasShape = shape(filterCount);
		PackedCollection<?> biases = bias ? new PackedCollection<>(biasShape) : null;

		Factor<PackedCollection<?>> operator = input -> {
			CollectionProducer<PackedCollection<?>> in;

			if (padding > 0) {
				in = c(input)
						.reshape(-1, channels, h, w)
						.pad(0, 0, padding, padding);
			} else {
				in = c(input);
			}

			CollectionProducer<PackedCollection<?>> conv =
					in.reshape(-1, channels, height * width)
							.traverse(1).enumerate(2, 1)
							.reshape(-1, height, width, channels);
			conv = conv.traverse(1)
					.enumerate(2, size, 1)
					.enumerate(2, size, 1)
					.traverse(1)
					.repeat(filterCount)
					.each();

			int bs = conv.getShape().length(0);

			CollectionProducer<PackedCollection<?>> filter =
					cp(filters.reshape(-1, channels, size * size))
							.traverse(1).enumerate(2, 1)
							.reshape(-1, size, size, channels);
			filter = filter.traverse(1)
							.repeat(height - diff)
							.repeat(width - diff)
							.traverse(0)
							.repeat(bs)
							.each();

			CollectionProducer<PackedCollection<?>> result =
					conv.multiply(filter).sum(4);

			if (biases != null) {
				int t = (height - diff) * (width - diff);
				result = result.reshape(bs, filterCount, t)
						.add(cp(biases).repeat(bs).traverse(2).repeat(t));
			}

			return result
					.reshape(-1, filterCount, height - diff, width - diff)
					.traverseEach();
		};

		OperationList setup = new OperationList();
		Random randn = randn(filterShape);
		setup.add(() -> randn::refresh);
		setup.add(a(p(filters.each()), divide(randn.traverseEach(), c(size * size).traverse(0))));
		if (biases != null) {
			setup.add(a(p(biases.each()), divide(randn.traverseEach(), c(size * size).traverse(0))));
		}

		return layer("convolution2d", inputShape, outputShape, operator,
				biases == null ? List.of(filters) : List.of(filters, biases),
				setup, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default CellularLayer pool2d(TraversalPolicy inputShape, int size, ComputeRequirement... requirements) {
		if (inputShape.getDimensions() == 2) {
			inputShape = inputShape.prependDimension(1);
		}

		if (inputShape.getDimensions() == 3) {
			inputShape = inputShape.prependDimension(1);
		}

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int n = inputShape.length(0);
		int c = inputShape.length(1);
		int h = inputShape.length(2);
		int w = inputShape.length(3);

		TraversalPolicy outputShape = shape(n, c, h / size, w / size);

		Factor<PackedCollection<?>> operator = input ->
				c(input)
						.reshape(-1, c, h, w)
						.traverse(2)
						.enumerate(3, size)
						.enumerate(3, size)
						.max(4);
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

		OperationList setup = new OperationList();
		if (init) {
			Random randn = randn(weightShape);
			setup.add(() -> randn::refresh);
			setup.add(a(p(weights.each()), divide(randn.traverseEach(), c(size).all())));
			if (bias) {
				setup.add(a(p(biases.each()), c(0.0)));
			}
		}

		return layer("dense " + size, shape(size), shape(nodes),
				operator, bias ? List.of(weights, biases) : List.of(weights),
				setup,
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

	default Function<TraversalPolicy, CellularLayer> logSoftmax(ComputeRequirement... requirements) {
		return shape -> logSoftmax(shape.getTotalSize(), requirements);
	}

	default CellularLayer logSoftmax(int size, ComputeRequirement... requirements) {
		TraversalPolicy shape = shape(size);
		return layer("logSoftmax", shape, shape, input ->
				c(input).traverse(1).subtract(
							c(input).traverse(1).exp().traverse(0).sum().log()),
				requirements);
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
		return layer("product", shape, shape,
					input -> multiply(traverseEach(input), traverseEach(value)),
				requirements);
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

	default CellularLayer relu(TraversalPolicy shape, ComputeRequirement... requirements) {
		if (shape.getDimensions() != 1)
			throw new IllegalArgumentException();

		return layer("relu", shape, shape, input -> rectify(input), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> silu(ComputeRequirement... requirements) {
		return shape -> silu(shape, requirements);
	}

	default CellularLayer silu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("silu", shape, shape, input -> multiply(traverseEach(input), sigmoid(traverseEach(input))), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> norm(int groups, ComputeRequirement... requirements) {
		return shape -> norm(shape, groups, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups, ComputeRequirement... requirements) {
		return norm(shape, groups, true, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups, boolean trainable, ComputeRequirement... requirements) {
		return norm(shape, groups,
				trainable ? new PackedCollection<>(shape.getTotalSize()) : null,
				trainable ? new PackedCollection<>(shape.getTotalSize()) : null,
				true, requirements);
	}

	default CellularLayer norm(int groups,
							   PackedCollection<?> weights,
							   PackedCollection<?> biases,
							   ComputeRequirement... requirements) {
		return norm(groups, weights, biases, false, requirements);
	}

	default CellularLayer norm(int groups, PackedCollection<?> weights, PackedCollection<?> biases, boolean init, ComputeRequirement... requirements) {
		TraversalPolicy shape;

		if (weights != null) {
			shape = shape(weights);
		} else if (biases != null) {
			shape = shape(biases);
		} else {
			throw new IllegalArgumentException();
		}

		return norm(shape, groups, weights, biases, init, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups,
							   PackedCollection<?> weights,
							   PackedCollection<?> biases,
							   boolean init,
							   ComputeRequirement... requirements) {
		int size = shape.getTotalSize();

		if (shape.traverse(1).item().getTotalSizeLong() % groups != 0) {
			if (shape.getTotalSizeLong() % groups == 0) {
				warn("Group normalization may span across batches");
			} else {
				throw new IllegalArgumentException();
			}
		}

		if ((weights != null && shape(weights).getTotalSize() != size) ||
				(biases != null && shape(biases).getTotalSize() != size)) {
			throw new IllegalArgumentException();
		}

		List<PackedCollection<?>> prop = new ArrayList<>();
		if (weights != null) prop.add(weights);
		if (biases != null) prop.add(biases);

		PackedCollection<?> w = weights == null ? null : weights.flatten();
		PackedCollection<?> b = biases == null ? null : biases.flatten();

		OperationList setup = new OperationList();
		if (init) {
			if (w != null) setup.add(a(p(w.each()), c(1)));
			if (b != null) setup.add(a(p(b.each()), c(0.0)));
		}

		return layer("norm", shape, shape, input -> {
			double eps = Hardware.getLocalHardware().getPrecision().epsilon();

			CollectionProducer<?> in = c(input).reshape(-1, groups, size / groups);
			CollectionProducer<?> out = in.subtractMean(2).divide(in.variance(2).add(c(eps)).sqrt());
			out = out.reshape(-1, size);

			if (w != null) out = out.multiply(cp(w));
			if (b != null) out = out.add(cp(b));
			return (CollectionProducer) out;
		}, prop, setup, requirements);
	}

	default CellularLayer rmsnorm(int size) {
		return rmsnorm(new PackedCollection<>(shape(size)));
	}

	default CellularLayer rmsnorm(PackedCollection<?> weights, ComputeRequirement... requirements) {
		TraversalPolicy shape = weights.getShape();
		if (shape.getDimensions() != 1)
			throw new IllegalArgumentException();

		int size = shape.getTotalSize();

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
