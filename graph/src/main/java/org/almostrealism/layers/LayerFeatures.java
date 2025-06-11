/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.cycle.Setup;
import io.almostrealism.relation.Composition;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.Random;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellularPropagation;
import org.almostrealism.graph.CollectionReceptor;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.SequentialBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface LayerFeatures extends MatrixFeatures, GeometryFeatures, ConsoleFeatures {

	boolean allowNonComposites = false;
	boolean enableWeightedSum = true;
	boolean enableMonitor = false;

	boolean enableIgnoreZero = true;
	boolean enableLogStability = true;

	Console console = CollectionFeatures.console.child();

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
				DefaultGradientPropagation.create(name, operator, weights.stream().map(this::cp)),
				weights, setup, requirements);
	}

	default CellularLayer layer(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
								Cell<PackedCollection<?>> forward, BackPropagation backward,
								List<PackedCollection<?>> weights, Supplier<Runnable> setup,
								ComputeRequirement... requirements) {
		BackPropagationCell backwardCell = new BackPropagationCell(name, backward);
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape, forward, backwardCell, weights, setup);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, Layer.ioTracking, true);
		if (enableMonitor)
			layer.setMonitor(new MonitorReceptor(
					name, inputShape, outputShape,
					weights.toArray(PackedCollection[]::new)));

		backwardCell.setForwardInput(layer.getInput());
		return layer;
	}

	default Function<TraversalPolicy, CellularLayer> compose(String name,
															 Block aux,
															 Composition<PackedCollection<?>> operator,
															 ComputeRequirement... requirements) {
		return shape -> compose(name, shape, aux.getOutputShape(), aux, operator, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> compose(String name,
															 Block aux,
															 TraversalPolicy outputShape,
															 Composition<PackedCollection<?>> operator,
															 ComputeRequirement... requirements) {
		return shape -> compose(name, shape, aux, outputShape, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy inputShape,
								  Block aux, TraversalPolicy outputShape,
								  Composition<PackedCollection<?>> operator,
								  ComputeRequirement... requirements) {
		return compose(name, inputShape, aux.getOutputShape(), outputShape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy shape,
								  CellularPropagation<PackedCollection<?>> aux,
								  Composition<PackedCollection<?>> operator,
								  ComputeRequirement... requirements) {
		return compose(name, shape, shape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy shape,
								  TraversalPolicy auxShape,
								  CellularPropagation<PackedCollection<?>> aux,
								  Composition<PackedCollection<?>> operator,
								  ComputeRequirement... requirements) {
		return compose(name, shape, auxShape, shape, aux, operator, requirements);
	}

	default CellularLayer compose(String name,
								  TraversalPolicy inputShape,
								  TraversalPolicy auxShape,
								  TraversalPolicy outputShape,
								  CellularPropagation<PackedCollection<?>> aux,
								  Composition<PackedCollection<?>> operator,
								  ComputeRequirement... requirements) {
		PackedCollection<?> auxInput = Layer.ioTracking ? new PackedCollection<>(auxShape) : null;

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
		Cell.CaptureReceptor<PackedCollection<?>> auxReceptor = new Cell.CaptureReceptor<>();
		auxExit.setReceptor(auxReceptor);

		Supplier<Runnable> setup = new OperationList();
		if (aux instanceof Setup) {
			setup = ((Setup) aux).setup();
		}

		// Create a layer that composes its input with whatever was received for aux
		DefaultCellularLayer layer = new DefaultCellularLayer(name, outputShape,
				Cell.of((input, next) -> next == null ? new OperationList() :
						next.push(operator.compose(input, auxReceptor.getReceipt()))),
				null, Collections.emptyList(), setup);
		if (requirements.length > 0) layer.setComputeRequirements(List.of(requirements));

		layer.init(inputShape, Layer.ioTracking, true);

		// Create gradient propagation for the main input
		String mainName = name + " main";
		BackPropagationCell mainBackward = new BackPropagationCell(mainName,
				DefaultGradientPropagation.create(mainName, in -> operator.compose(in, p(auxInput))));
		mainBackward.setForwardInput(layer.getInput());

		// Create gradient propagation for the aux input
		// and direct its output to the aux backward Cell
		String auxName = name + " aux";
		BackPropagationCell auxBackward = new BackPropagationCell(auxName,
				DefaultGradientPropagation.create(auxName, in -> operator.compose(p(layer.getInput()), in)));
		auxBackward.setForwardInput(auxInput);
		auxBackward.setReceptor(aux.getBackward());

		// Combine both backpropagation steps and attach the result to the layer
		layer.setBackward(new LearningCell() {
			@Override
			public void setParameterUpdate(ParameterUpdate<PackedCollection<?>> update) {
				if (aux instanceof Learning) {
					((Learning) aux).setParameterUpdate(update);
				}
			}

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
			int axis = shape.alignCount(Countable.countLong(in)).getTraversalAxis();

			if (shape.equalsIgnoreAxis(shape(out))) {
				op.add(a(name, traverse(axis, (Producer) out), traverse(axis, (Producer) in)));
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
		return flattened(true);
	}

	default Function<TraversalPolicy, Block> flattened(boolean preserveCount) {
		return shape -> {
			TraversalPolicy outputShape = shape.flatten(preserveCount);
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

	/**
	 * Creates a Block that performs subset extraction in the forward pass and padding in the backward pass.
	 * This is commonly used in neural network architectures where you need to crop data in one direction
	 * and pad it back in the reverse direction during backpropagation.
	 *
	 * <p>The forward operation extracts a subset from the input at the specified position,
	 * while the backward operation pads the gradient back to the original input size.</p>
	 *
	 * @param inputShape The shape of the input data
	 * @param subsetShape The shape of the extracted subset
	 * @param pos The position coordinates where to extract the subset from
	 * @return A Block that performs subset extraction forward and padding backward
	 * 
	 * @see #subset(TraversalPolicy, Producer, int...)
	 * @see PackedCollectionSubset
	 */
	default Block subset(TraversalPolicy inputShape, TraversalPolicy subsetShape, int... pos) {
		return new DefaultBlock(inputShape, subsetShape,
				Cell.of((in, next) ->
						next.push(subset(subsetShape, in, pos))),
				Cell.of((in, next) ->
						next.push(pad(inputShape, new TraversalPolicy(true, pos), in))));
	}

	default Block pad(TraversalPolicy inputShape, TraversalPolicy paddedShape, int... pos) {
			return new DefaultBlock(inputShape, paddedShape,
				Cell.of((in, next) ->
						next.push(pad(paddedShape, in, pos))),
				Cell.of((in, next) ->
						next.push(subset(inputShape, in, pos))));
	}

	default Function<TraversalPolicy, Block> convolution2d(int inputChannels, int filterCount, int size, int padding,
																   ComputeRequirement... requirements) {
		return convolution2d(inputChannels, filterCount, size, padding, true, requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int inputChannels, int filterCount, int size, int padding,
														   boolean bias, ComputeRequirement... requirements) {
		if (inputChannels != 1) {
			return shape -> {
				shape = padDimensions(shape, 2, 4);
				int c = shape.getDimensions() > 2 ? shape.length(1) : 1;
				if (c != inputChannels) {
					throw new IllegalArgumentException();
				}

				return convolution2d(shape, filterCount, size, padding, bias, requirements);
			};
		}

		return shape -> convolution2d(shape, filterCount, size, padding, bias, requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int filterCount, int size, ComputeRequirement... requirements) {
		return convolution2d(filterCount, size, 0, requirements);
	}

	default Function<TraversalPolicy, Block> convolution2d(int filterCount, int size, int padding, ComputeRequirement... requirements) {
		return shape -> convolution2d(shape, filterCount, size, padding, true, requirements);
	}

	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, true, requirements);
	}

	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
										int size, boolean bias, ComputeRequirement... requirements) {
		return convolution2d(inputShape, filterCount, size, 0, bias, requirements);
	}

	default Block convolution2d(TraversalPolicy inputShape, int filterCount,
								int size, int padding,
								boolean bias, ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2, 4);

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
		int outHeight = height - diff;
		int outWidth = width - diff;
		TraversalPolicy outputShape = shape(batch, filterCount, outHeight, outWidth);

		TraversalPolicy filterShape = shape(filterCount, channels, size, size);
		PackedCollection<?> filters = new PackedCollection<>(filterShape);

		TraversalPolicy biasShape = shape(filterCount);
		PackedCollection<?> biases = bias ? new PackedCollection<>(biasShape) : null;

		Factor<PackedCollection<?>> operator = input -> {
			CollectionProducer<PackedCollection<?>> in = c(input);
			CollectionProducer<PackedCollection<?>> conv =
					in.reshape(-1, 1, channels, height, width);
			CollectionProducer<PackedCollection<?>> filter =
					cp(filters.reshape(1, filterCount, channels, size, size));

			int bs = conv.getShape().length(0);

			TraversalPolicy resultShape = shape(batch, filterCount, 1, outHeight, outWidth);
			TraversalPolicy inputPositions = resultShape
					.withRate(1, 1, filterCount)
					.withRate(2, channels, 1);
			TraversalPolicy filterPositions = resultShape
					.withRate(0, 1, batch)
					.withRate(2, channels, 1)
					.withRate(3, size, outHeight)
					.withRate(4, size, outWidth);
			TraversalPolicy groupShape =
					shape(1, 1, channels, size, size);
			CollectionProducer<PackedCollection<?>> result =
					weightedSum("convolutionFilter",
							inputPositions, filterPositions,
							groupShape, conv, filter);

			if (biases != null) {
				int t = outHeight * outWidth;
				result = result.reshape(bs, filterCount, t)
						.add(cp(biases).repeat(bs).traverse(2).repeat(t));
			}

			return result
					.reshape(-1, filterCount, outHeight, outWidth)
					.traverseEach();
		};

		OperationList setup = new OperationList();
		Random randn = randn(filterShape);
		setup.add(() -> randn::refresh);
		setup.add(a(p(filters.each()), divide(randn.traverseEach(), c(channels * size * size).traverse(0))));
		if (biases != null) {
			setup.add(a(p(biases.each()), divide(randn.traverseEach(), c(channels * size * size).traverse(0))));
		}

		TraversalPolicy convInputShape = shape(batch, channels, height, width);
		CellularLayer layer = layer("convolution2d",
								convInputShape.traverse(1), outputShape.traverse(1),
								operator,
								biases == null ? List.of(filters) : List.of(filters, biases),
								setup, requirements);

		if (padding > 0) {
			SequentialBlock block = new SequentialBlock(inputShape);
			block.add(pad(inputShape, convInputShape, 0, 0, padding, padding));
			block.add(layer);
			return block;
		} else {
			return layer;
		}
	}

	default Function<TraversalPolicy, CellularLayer> pool2d(int size) {
		return shape -> pool2d(shape, size);
	}

	default CellularLayer pool2d(TraversalPolicy inputShape, int size, ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2, 4);

		if (inputShape.getDimensions() != 4) {
			throw new IllegalArgumentException();
		}

		int n = inputShape.length(0);
		int c = inputShape.length(1);
		int h = inputShape.length(2);
		int w = inputShape.length(3);

		TraversalPolicy outputShape =
					shape(n, c, h / size, w / size).alignCount(inputShape);

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
		return shape -> dense(shape.getSize(), nodes).apply(shape);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes) {
		return dense(size, nodes, true);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes, boolean bias) {
		return dense(size, nodes, bias, true);
	}

	default Function<TraversalPolicy, CellularLayer> dense(int size, int nodes,
														   boolean bias, boolean init,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, new PackedCollection<>(shape(nodes, size)), bias, init, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> dense(PackedCollection<?> weights,
														   ComputeRequirement... requirements) {
		return inputShape -> dense(inputShape, weights, false, false, requirements);
	}

	default CellularLayer dense(TraversalPolicy inputShape,
								PackedCollection<?> weights,
								boolean bias, boolean init,
								ComputeRequirement... requirements) {
		inputShape = padDimensions(inputShape, 2);

		TraversalPolicy weightShape = weights.getShape();
		if (weightShape.getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		int batch = inputShape.length(0);
		int nodes = weightShape.length(0);
		int size = weightShape.length(1);

		if (inputShape.length(1) != size) {
			throw new IllegalArgumentException();
		}

		PackedCollection<?> biases = bias ? new PackedCollection<>(shape(nodes)) : null;

		Factor<PackedCollection<?>> operator = input ->
				bias ? matmul(p(weights), input).add(traverse(1, p(biases))) : matmul(p(weights), input);

		OperationList setup = new OperationList("dense " + size + " init");
		if (init) {
			Random randn = randn(weightShape);
			setup.add(() -> randn::refresh);
			setup.add(a(p(weights.each()), divide(randn.traverseEach(), c(size).all())));
			if (bias) {
				setup.add(a(p(biases.each()), c(0.0)));
			}
		}

		return layer("dense " + size, inputShape.traverseEach(), shape(batch, nodes).traverseEach(),
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

	default Function<TraversalPolicy, CellularLayer> softmax(boolean subtractMax, ComputeRequirement... requirements) {
		return shape -> softmax(shape, subtractMax, requirements);
	}

	default CellularLayer softmax(TraversalPolicy shape, boolean subtractMax, ComputeRequirement... requirements) {
		if (shape.getDimensions() < 2) {
			throw new IllegalArgumentException();
		}

		int axis = shape.getDimensions() - 1;
		int seqLen = shape.length(axis);
		double eps = 1e-5;

		if (enableLogStability) {
			return layer("softmax2d", shape, shape, input -> {
				CollectionProducer<PackedCollection<?>> max = traverse(axis, input).max();
				CollectionProducer<PackedCollection<?>> stable =
						traverse(axis + 1, input).subtract(max.expand(seqLen));
				CollectionProducer<PackedCollection<?>> logSum =
						stable.exp().traverse(axis).sum().log().expand(seqLen);
				return stable.subtract(logSum).exp();
			}, requirements);
		} else {
			return layer("softmax2d", shape, shape, input -> {
				CollectionProducer<PackedCollection<?>> o = traverse(axis, input);

				if (subtractMax) {
					if (enableIgnoreZero) {
						o = o.max();
						o = o.expand(seqLen);
						o = traverse(axis + 1, input).subtractIgnoreZero(o);
					} else {
						o = o.max().add(eps);
						o = o.expand(seqLen);
						o = traverse(axis + 1, input).subtract(o);
					}
				}

				o = o.expIgnoreZero().traverse(axis);

				if (subtractMax && enableIgnoreZero) {
					o = o.divide(o.sum().expand(seqLen));
				} else {
					o = o.divide(o.sum().add(eps).expand(seqLen));
				}

				return o;
			}, requirements);
		}
	}

	default Function<TraversalPolicy, CellularLayer> logSoftmax(ComputeRequirement... requirements) {
		return shape -> logSoftmax(shape, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> logSoftmax(int size, ComputeRequirement... requirements) {
		return shape -> {
			shape = padDimensions(shape, 2);
			if (shape.length(1) != size) {
				throw new IllegalArgumentException();
			}

			return logSoftmax(shape, requirements);
		};
	}

	default CellularLayer logSoftmax(TraversalPolicy shape, ComputeRequirement... requirements) {
		shape = padDimensions(shape, 2).traverse(1);

		return layer("logSoftmax", shape, shape, input ->
				c(input).traverse(2).subtract(
							c(input).traverse(2).exp().traverse(1).sum().log()),
				requirements);
	}

	@Deprecated
	default CellularLayer accum(TraversalPolicy shape, Cell<PackedCollection<?>> value, ComputeRequirement... requirements) {
		if (!allowNonComposites) {
			throw new UnsupportedOperationException("accum will not support backpropagation");
		}

		warn("accum will not support backpropagation");
		return layer("accum", shape, shape, Cell.of((input, next) -> {
			Cell.CaptureReceptor<PackedCollection<?>> r = new Cell.CaptureReceptor<>();
			value.setReceptor(r);

			OperationList ops = new OperationList();
			ops.add(value.push(input));
			if (next != null) ops.add(next.push(add(traverseEach(input), traverseEach(r.getReceipt()))));
			return ops;
		}), null, requirements);
	}

	default CellularLayer accum(TraversalPolicy shape,
								  CellularPropagation<PackedCollection<?>> aux,
								  ComputeRequirement... requirements) {
		return compose("accum", shape, aux,
				(input, auxValue) -> add(traverseEach(input), traverseEach(auxValue)),
				requirements);
	}

	default Function<TraversalPolicy, CellularLayer> concat(int axis, Block aux, ComputeRequirement... requirements) {
		return shape -> {
			TraversalPolicy auxShape = aux.getOutputShape();
			if (auxShape.getDimensions() != shape.getDimensions()) {
				throw new IllegalArgumentException();
			}

			return concat(shape,
					shape.replaceDimension(axis, shape.length(axis) + auxShape.length(axis)),
					aux, requirements);
		};
	}

	default CellularLayer concat(TraversalPolicy inputShape,
								 TraversalPolicy outputShape,
								 Block aux,
								 ComputeRequirement... requirements) {
		return compose("concat", inputShape, aux, outputShape,
				(input, auxValue) -> concat(outputShape, input, auxValue),
				requirements);
	}

	@Deprecated
	default CellularLayer product(Producer<PackedCollection<?>> value, ComputeRequirement... requirements) {
		warn("product will not support backpropagation");
		TraversalPolicy shape = shape(value);
		return layer("product", shape, shape,
				input -> multiply(traverseEach(input), traverseEach(value)),
				requirements);
	}

	@Deprecated
	default CellularLayer product(TraversalPolicy inputShape, TraversalPolicy outputShape,
								  Cell<PackedCollection<?>> a, Cell<PackedCollection<?>> b,
								  ComputeRequirement... requirements) {
		warn("product will not support backpropagation");
		return layer("product", inputShape, outputShape, Cell.of((input, next) -> {
			Cell.CaptureReceptor<PackedCollection<?>> ar = new Cell.CaptureReceptor<>();
			a.setReceptor(ar);

			Cell.CaptureReceptor<PackedCollection<?>> br = new Cell.CaptureReceptor<>();
			b.setReceptor(br);

			OperationList ops = new OperationList();
			ops.add(a.push(input));
			ops.add(b.push(input));
			if (next != null)
				ops.add(next.push(multiply(traverseEach(ar.getReceipt()), traverseEach(br.getReceipt()))));
			return ops;
		}), null, requirements);
	}

	default CellularLayer product(TraversalPolicy shape,
								  CellularPropagation<PackedCollection<?>> aux,
								  ComputeRequirement... requirements) {
		return compose("product", shape, aux,
					(input, auxValue) -> multiply(traverseEach(input), traverseEach(auxValue)),
				requirements);
	}

	default Function<TraversalPolicy, CellularLayer> similarity(
			Block k, int c, int s1, int s2) {
		if (k.getOutputShape().getDimensions() != 4 ||
				k.getOutputShape().length(1) != c ||
				k.getOutputShape().length(3) != s2) {
			throw new IllegalArgumentException();
		}

		int batchSize = k.getOutputShape().length(0);
		int dim = k.getOutputShape().length(2);

		TraversalPolicy outputShape = shape(batchSize, c, s1, s2).traverseEach();

		return compose("similarity", k, outputShape, (a, b) -> {
			TraversalPolicy leftShape = shape(batchSize, c, dim, s1, 1);
			TraversalPolicy rightShape = shape(batchSize, c, dim, 1, s2);

			TraversalPolicy resultShape = shape(batchSize, c, 1, s1, s2);
			TraversalPolicy leftPosition = leftShape.repeat(4, s2);
			TraversalPolicy rightPosition = rightShape.repeat(3, s1);
			TraversalPolicy groupShape = shape(1, 1, dim, 1, 1);

			return weightedSum("similarity",
					resultShape,
					leftPosition, rightPosition,
					groupShape, groupShape,
					reshape(leftShape, c(a)),
					reshape(rightShape, c(b)))
					.reshape(outputShape);
		});
	}

	default Function<TraversalPolicy, CellularLayer> weightedSum(
			Block v, int heads, int dimHead, int size) {
		if (v.getOutputShape().getDimensions() != 4 ||
				v.getOutputShape().length(1) != heads ||
				v.getOutputShape().length(2) != dimHead ||
				v.getOutputShape().length(3) != size) {
			throw new IllegalArgumentException();
		}

		int batchSize = v.getOutputShape().length(0);

		if (enableWeightedSum) {
			return null;
		} else {
			return compose("weightedSum", v, shape(batchSize, heads, size, dimHead),
					(a, b) -> {
						CollectionProducer<PackedCollection<?>> pa = c(a)
								.traverse(4)
								.repeat(dimHead);
						CollectionProducer<PackedCollection<?>> pb = c(b)
								.traverse(2)
								.enumerate(3, 1)
								.traverse(2)
								.repeat(size);
						return multiply(pa, pb)
								.reshape(batchSize, heads, size, size, dimHead)
								.traverse(3)
								.enumerate(4, 1)
								.sum(4);
					});
		}
	}

	default Function<TraversalPolicy, CellularLayer> scale(double scale, ComputeRequirement... requirements) {
		return shape -> scale(shape, scale, requirements);
	}

	default CellularLayer scale(TraversalPolicy shape, double scale, ComputeRequirement... requirements) {
		return layer("scale", shape, shape, input -> multiply(c(input).each(), c(scale)), requirements);
	}

	default CellularLayer relu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("relu", shape, shape, input -> rectify(input), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> silu(ComputeRequirement... requirements) {
		return shape -> silu(shape, requirements);
	}

	default CellularLayer silu(TraversalPolicy shape, ComputeRequirement... requirements) {
		return layer("silu", shape, shape, input -> multiply(traverseEach(input), sigmoid(traverseEach(input))), requirements);
	}

	default Function<TraversalPolicy, CellularLayer> gelu(ComputeRequirement... requirements) {
		return shape -> gelu(shape, requirements);
	}

	default CellularLayer gelu(TraversalPolicy shape, ComputeRequirement... requirements) {
		// 0.5 * x * (1 + math.tanh(sqrt(2 / pi) * (x + 0.044715 * x^3)))
		return layer("gelu", shape, shape, input -> {
			CollectionProducer<PackedCollection<?>> x = c(input).traverseEach();
			CollectionProducer<PackedCollection<?>> x3 = pow(x, c(3));
			CollectionProducer<PackedCollection<?>> tanh =
					tanh(x.add(x3.multiply(c(0.044715)))
						.multiply(c(ROOT_2_BY_PI)));
			return c(0.5).multiply(x).multiply(tanh.add(c(1)));
		}, requirements);
	}

	default Function<TraversalPolicy, CellularLayer> norm(ComputeRequirement... requirements) {
		return norm(1);
	}

	default Function<TraversalPolicy, CellularLayer> norm(int groups, ComputeRequirement... requirements) {
		return shape -> norm(shape, groups, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups, ComputeRequirement... requirements) {
		return norm(shape, groups, true, requirements);
	}

	default CellularLayer norm(TraversalPolicy shape, int groups, boolean trainable, ComputeRequirement... requirements) {
		shape = padDimensions(shape, 1, 3);
		int size = shape.traverse(1).item().getTotalSize();
		return norm(shape, groups,
				trainable ? new PackedCollection<>(size) : null,
				trainable ? new PackedCollection<>(size) : null,
				true, requirements);
	}

	default CellularLayer norm(int groups,
							   PackedCollection<?> weights,
							   PackedCollection<?> biases,
							   ComputeRequirement... requirements) {
		return norm(groups, weights, biases, false, requirements);
	}

	default CellularLayer norm(int groups, PackedCollection<?> weights, PackedCollection<?> biases,
							   boolean init, ComputeRequirement... requirements) {
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
		shape = padDimensions(shape, 1, 3);
		long size = shape.traverse(1).item().getTotalSizeLong();

		if (size % groups != 0) {
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

		return layer("norm", shape.traverse(1), shape.traverse(1), input -> {
			double eps = Hardware.getLocalHardware().epsilon();

			CollectionProducer<?> in = c(input).reshape(-1, groups, Math.toIntExact(size / groups));
			CollectionProducer<?> out = in.subtractMean(2).divide(in.variance(2).add(c(eps)).sqrt());
			out = out.reshape(-1, Math.toIntExact(size)).traverse(1);

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

	interface LearningCell extends Cell<PackedCollection<?>>, Learning { }
}
