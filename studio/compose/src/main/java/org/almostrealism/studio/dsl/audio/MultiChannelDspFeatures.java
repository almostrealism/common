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

package org.almostrealism.studio.dsl.audio;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Default methods for building multi-channel DSP {@link Block} computation graphs.
 *
 * <p>Implements the audio-domain block factories used by the PDSL audio integration in
 * {@link AudioDspPrimitives} to compile {@code for each channel}, {@code route()},
 * and {@code delay_network()} PDSL constructs into {@link DefaultBlock} instances
 * backed by {@link Cell} computation graphs. Domain-agnostic primitives such as
 * {@code repeat} (axis-0 replication, formerly {@code fan_out}) and
 * {@code sum_channels} (axis-0 summation) are kernel-level tensor operations and
 * are supplied by the PDSL interpreter core, not by this audio-side mixin.</p>
 *
 * <p>All methods follow the AlmostRealism producer pattern: no Java-side arithmetic,
 * all computation expressed as {@link CollectionProducer} compositions.</p>
 */
public interface MultiChannelDspFeatures extends LayerFeatures {

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
	 * Builds a cross-channel routing block using a (possibly rectangular) transmission matrix.
	 *
	 * <p>The matrix has shape {@code [inputChannels, outputChannels]}. The contraction is
	 * {@code out[m, t] = sum_n(matrix[n, m] * in[n, t])} where {@code n} ranges over input
	 * channels and {@code m} over output channels. Matrix elements are read via
	 * {@code subset} producers so the matrix can be genome-driven (updated between
	 * forward passes without recompilation).</p>
	 *
	 * <p>When {@code inputChannels == outputChannels} this is the square cross-channel
	 * feedback case. When they differ, it implements the {@code N efx → M delay layers}
	 * fan-routing pattern from {@code MixdownManager.createEfx()} line 660-664
	 * ({@code efx.m(fi(), delays, transmissionGene)}).</p>
	 *
	 * @param matrix          routing matrix producer (shape
	 *                        {@code [inputChannels, outputChannels]})
	 * @param inputChannels   number of input channels (matches matrix axis 0)
	 * @param outputChannels  number of output channels (matches matrix axis 1)
	 * @param signalSize      samples per channel
	 * @return a Block with shape {@code [inputChannels, signalSize] → [outputChannels, signalSize]}
	 */
	default Block routeBlock(CollectionProducer matrix, int inputChannels,
							 int outputChannels, int signalSize) {
		TraversalPolicy inShape = shape(inputChannels, signalSize);
		TraversalPolicy outShape = shape(outputChannels, signalSize);
		TraversalPolicy sigShape = shape(1, signalSize);
		TraversalPolicy elemShape = shape(1, 1);
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					CollectionProducer allOuts = null;
					for (int m = 0; m < outputChannels; m++) {
						CollectionProducer channelOut = null;
						for (int n = 0; n < inputChannels; n++) {
							CollectionProducer matElem = subset(elemShape, matrix, n, m);
							CollectionProducer inCh = subset(sigShape, c(in), n, 0);
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
		return new DefaultBlock(inShape, outShape, forward, backward);
	}

	/**
	 * Builds a closed-loop multi-tap feedback delay-network block.
	 *
	 * <p>This is the PDSL/Block-side equivalent of
	 * {@code org.almostrealism.audio.filter.DelayNetwork}: {@code channels} parallel
	 * delay lines whose outputs are mixed back to inputs through a {@code [channels,
	 * channels]} feedback matrix.</p>
	 *
	 * @param delaySamples    per-channel delay length producer (shape {@code [channels]})
	 * @param feedbackMatrix  feedback mixing matrix producer (shape
	 *                        {@code [channels, channels]})
	 * @param buffer          per-line ring buffers (total size
	 *                        {@code channels * signalSize})
	 * @param heads           per-line write head positions (size {@code channels})
	 * @param channels        number of delay lines
	 * @param signalSize      samples per channel per forward pass
	 * @return a Block with shape {@code [channels, signalSize] → [channels, signalSize]}
	 */
	default Block delayNetworkBlock(CollectionProducer delaySamples,
									CollectionProducer feedbackMatrix,
									CollectionProducer buffer,
									CollectionProducer heads,
									int channels, int signalSize) {
		int totalBuf = shape(buffer).getTotalSize();
		int bufSize = totalBuf / channels;
		if (totalBuf != channels * bufSize) {
			throw new IllegalArgumentException(
					"delay_network buffer size " + totalBuf
							+ " is not divisible by channels=" + channels);
		}
		if (bufSize != signalSize) {
			throw new IllegalArgumentException(
					"delay_network requires per-channel buffer size (" + bufSize
							+ ") to equal signal_size (" + signalSize + ")");
		}
		if (shape(heads).getTotalSize() != channels) {
			throw new IllegalArgumentException(
					"delay_network heads collection size " + shape(heads).getTotalSize()
							+ " must equal channels=" + channels);
		}
		TraversalPolicy multiShape = shape(channels, signalSize);
		TraversalPolicy sigShape = shape(1, signalSize);
		TraversalPolicy oneShape = shape(1);
		TraversalPolicy elemShape = shape(1, 1);
		CollectionProducer delays1D = delaySamples.reshape(shape(channels));
		CollectionProducer heads1D = heads.reshape(shape(channels));
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					CollectionProducer yAll = null;
					for (int n = 0; n < channels; n++) {
						CollectionProducer headN = subset(oneShape, heads1D, n);
						CollectionProducer delayN = subset(oneShape, delays1D, n);
						CollectionProducer readPositions = mod(
								headN.add(integers(0, signalSize))
										.subtract(delayN)
										.add(c(bufSize)),
								c(bufSize)).add(c(n * bufSize));
						CollectionProducer yN = c(shape(signalSize), buffer, readPositions)
							.reshape(sigShape);
						yAll = yAll == null ? yN : (CollectionProducer) concat(yAll, yN);
					}
					CollectionProducer fbAll = null;
					for (int n = 0; n < channels; n++) {
						CollectionProducer fbN = null;
						for (int m = 0; m < channels; m++) {
							CollectionProducer matNm =
									subset(elemShape, feedbackMatrix, n, m);
							CollectionProducer yM = subset(sigShape, yAll, m, 0);
							CollectionProducer contribution = matNm.multiply(yM);
							fbN = fbN == null ? contribution : fbN.add(contribution);
						}
						fbAll = fbAll == null ? fbN : (CollectionProducer) concat(fbAll, fbN);
					}
					CollectionProducer newBuffer = c(in).add(fbAll)
							.reshape(shape(channels * signalSize));
					CollectionProducer newHeads = heads1D.add(c((double) signalSize))
							.mod(c((double) bufSize));
					OperationList ops = new OperationList("delay_network");
					ops.add(next.push(yAll));
					ops.add(into("delay-network-buffer-write", newBuffer,
							buffer, false));
					ops.add(into("delay-network-head-write", newHeads,
							heads, false));
					return ops;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("delay-network-backward"));
		return new DefaultBlock(multiShape, multiShape, forward, backward);
	}

}
