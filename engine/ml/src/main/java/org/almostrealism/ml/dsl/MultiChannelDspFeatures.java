/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.SequentialBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Default methods for building multi-channel DSP {@link Block} computation graphs.
 *
 * <p>Implements the core block factories used by {@link PdslInterpreter} to compile
 * {@code for each channel}, {@code route()}, {@code sum_channels()}, and {@code fan_out()}
 * PDSL constructs into {@link DefaultBlock} instances backed by {@link Cell} computation graphs.</p>
 *
 * <p>All methods follow the AlmostRealism producer pattern: no Java-side arithmetic,
 * all computation expressed as {@link CollectionProducer} compositions.</p>
 */
public interface MultiChannelDspFeatures extends CollectionFeatures {

	/**
	 * Builds a parallel per-channel dispatch block.
	 *
	 * <p>The input shape is {@code [channels, signalSize]}. Each channel block receives
	 * a {@code [1, signalSize]} slice of the input; all channel outputs are concatenated
	 * back to {@code [channels, signalSize]}.</p>
	 *
	 * @param channelBlocks one block per channel, in channel order
	 * @param channels      number of channels
	 * @param signalSize    samples per channel
	 * @return a Block with shape {@code [channels, signalSize] → [channels, signalSize]}
	 */
	default Block perChannelBlock(List<Block> channelBlocks, int channels, int signalSize) {
		TraversalPolicy multiShape = shape(channels, signalSize);
		TraversalPolicy singleShape = shape(1, signalSize);
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					OperationList allOps = new OperationList("per-channel");
					List<CollectionProducer> channelOutputs = new ArrayList<>();
					for (int i = 0; i < channels; i++) {
						final int ch = i;
						AtomicReference<CollectionProducer> captured = new AtomicReference<>();
						Receptor<PackedCollection> channelReceptor = protein -> {
							captured.set(c(protein).reshape(singleShape));
							return new OperationList();
						};
						CollectionProducer channelInput = subset(singleShape, c(in), ch * signalSize);
						Cell<PackedCollection> channelCell = channelBlocks.get(ch).getForward();
						channelCell.setReceptor(channelReceptor);
						allOps.add(channelCell.push(channelInput));
						channelOutputs.add(captured.get());
					}
					CollectionProducer combined = channelOutputs.get(0);
					for (int i = 1; i < channels; i++) {
						combined = (CollectionProducer) concat(combined, channelOutputs.get(i));
					}
					allOps.add(next.push(combined));
					return allOps;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("per-channel-backward"));
		return new DefaultBlock(multiShape, multiShape, forward, backward);
	}

	/**
	 * Builds a cross-channel routing block using a transmission matrix.
	 *
	 * <p>For each output channel {@code i}: {@code out[i] = sum_j(matrix[i,j] * in[j])}.
	 * Matrix elements are read via {@code subset} producers so the matrix can be
	 * genome-driven (updated between forward passes without recompilation).</p>
	 *
	 * @param matrix     routing matrix ({@link PackedCollection} of shape {@code [channels, channels]})
	 * @param channels   number of channels
	 * @param signalSize samples per channel
	 * @return a Block with shape {@code [channels, signalSize] → [channels, signalSize]}
	 */
	default Block routeBlock(PackedCollection matrix, int channels, int signalSize) {
		TraversalPolicy multiShape = shape(channels, signalSize);
		TraversalPolicy sigShape = shape(1, signalSize);
		TraversalPolicy elemShape = shape(1, 1);
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					CollectionProducer allOuts = null;
					for (int i = 0; i < channels; i++) {
						CollectionProducer channelOut = null;
						for (int j = 0; j < channels; j++) {
							CollectionProducer matElem = subset(elemShape, cp(matrix), i * channels + j);
							CollectionProducer inCh = subset(sigShape, c(in), j * signalSize);
							CollectionProducer contribution = matElem.multiply(inCh);
							channelOut = channelOut == null ? contribution : channelOut.add(contribution);
						}
						allOuts = allOuts == null ? channelOut
								: (CollectionProducer) concat(allOuts, channelOut);
					}
					OperationList ops = new OperationList("route");
					ops.add(next.push(allOuts));
					return ops;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("route-backward"));
		return new DefaultBlock(multiShape, multiShape, forward, backward);
	}

	/**
	 * Builds a block that collapses {@code channels} channels to 1 by element-wise addition.
	 *
	 * @param channels   number of input channels
	 * @param signalSize samples per channel
	 * @return a Block with shape {@code [channels, signalSize] → [1, signalSize]}
	 */
	default Block sumChannelsBlock(int channels, int signalSize) {
		TraversalPolicy multiShape = shape(channels, signalSize);
		TraversalPolicy singleShape = shape(1, signalSize);
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					CollectionProducer sum = subset(singleShape, c(in), 0);
					for (int i = 1; i < channels; i++) {
						sum = sum.add(subset(singleShape, c(in), i * signalSize));
					}
					OperationList ops = new OperationList("sum_channels");
					ops.add(next.push(sum));
					return ops;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("sum_channels-backward"));
		return new DefaultBlock(multiShape, singleShape, forward, backward);
	}

	/**
	 * Builds a fan-out block that replicates a single-channel signal to {@code n} channels.
	 *
	 * @param n          number of output channels
	 * @param signalSize samples per channel
	 * @return a Block with shape {@code [1, signalSize] → [n, signalSize]}
	 */
	default Block fanOutBlock(int n, int signalSize) {
		TraversalPolicy singleShape = shape(1, signalSize);
		TraversalPolicy multiShape = shape(n, signalSize);
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					CollectionProducer combined = c(in).reshape(singleShape);
					for (int i = 1; i < n; i++) {
						combined = (CollectionProducer) concat(combined, c(in).reshape(singleShape));
					}
					OperationList ops = new OperationList("fan_out");
					ops.add(next.push(combined));
					return ops;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("fan_out-backward"));
		return new DefaultBlock(singleShape, multiShape, forward, backward);
	}
}
