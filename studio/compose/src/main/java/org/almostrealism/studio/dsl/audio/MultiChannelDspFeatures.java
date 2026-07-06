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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionSlotUpdateComputation;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
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
import java.util.function.Function;
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
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
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
						// Position arguments are per-dimension coordinates (row, column), not a
						// flat offset: (ch, 0) selects channel ch's row. A flat offset here
						// silently selected row 0 for every channel, collapsing the whole
						// multi-channel dispatch to channel 0's signal.
						CollectionProducer channelInput = subset(singleShape, c(in), ch, 0);
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
	 * fan-routing pattern from {@code MixdownManager.createEfx()}
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
		return feedbackNetworkBlock(delaySamples, feedbackMatrix, null,
				buffer, heads, channels, signalSize);
	}

	/**
	 * Builds a block-parallel feedback delay network — the PDSL analogue of
	 * {@code CellList.mself}. Each frame reads the per-channel delayed output from the
	 * ring, routes it back into the ring through {@code feedbackMatrix} (the transmission
	 * grid) added to the incoming signal, and emits the delayed output routed through
	 * {@code passthroughMatrix} (the next-layer routing). With a {@code null}
	 * {@code passthroughMatrix} the delayed output is emitted unchanged (identity
	 * passthrough), which is the {@code delay_network} behaviour.
	 *
	 * <p>The recurrence is frame-to-frame: a sample at output position {@code i} reads
	 * {@code output[i - delay]}, which (for {@code delay >= signalSize}) lives in an
	 * already-computed prior frame, so the whole frame computes as one parallel kernel.
	 * This requires the feedback delay to be at least one frame; the multi-frame ring
	 * ({@code bufSize = k * signalSize}) lets the delay span several frames so real echo
	 * lengths are sample-accurate. A feedback delay shorter than one frame would create an
	 * intra-frame recurrence that cannot be evaluated as a single parallel kernel, so it is
	 * not supported by this block-parallel construct.</p>
	 *
	 * @param delaySamples      per-channel delay in samples, shape {@code [channels]}
	 * @param feedbackMatrix    transmission grid routing output back to input,
	 *                          shape {@code [channels, channels]}
	 * @param passthroughMatrix output routing to the next layer, shape
	 *                          {@code [channels, channels]}, or {@code null} for identity
	 * @param buffer            flat ring buffer, total {@code channels * bufSize}
	 * @param heads             per-channel write head positions, shape {@code [channels]}
	 * @param channels          number of channels
	 * @param signalSize        samples per frame
	 * @return a feedback delay network {@link Block}
	 */
	default Block feedbackNetworkBlock(CollectionProducer delaySamples,
									   CollectionProducer feedbackMatrix,
									   CollectionProducer passthroughMatrix,
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
		if (bufSize % signalSize != 0) {
			throw new IllegalArgumentException(
					"delay_network requires per-channel buffer size (" + bufSize
							+ ") to be a positive multiple of signal_size (" + signalSize
							+ "); the buffer spans bufSize/signalSize whole frames and the "
							+ "maximum per-channel delay must be < bufSize");
		}
		if (shape(heads).getTotalSize() != channels) {
			throw new IllegalArgumentException(
					"delay_network heads collection size " + shape(heads).getTotalSize()
							+ " must equal channels=" + channels);
		}
		TraversalPolicy multiShape = shape(channels, signalSize);
		CollectionProducer delays1D = delaySamples.reshape(shape(channels));
		CollectionProducer heads1D = heads.reshape(shape(channels));
		CollectionProducer flatBuffer = buffer.reshape(shape(channels * bufSize));
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					// Each stage below is ONE computation over the raw state collections.
					// Composing the same result from per-channel subset/mask/concat trees
					// leaves many small computations for Process.optimize to isolate, and
					// every isolated stage is then dispatched-and-awaited separately on
					// every forward — the cost is the per-dispatch glue, not the math.
					CollectionProducer output = routedRingRead(
							"delay-network-output-" + channels + "x" + signalSize + "x" + bufSize,
							flatBuffer, heads1D, delays1D, false, passthroughMatrix,
							channels, signalSize, bufSize);
					CollectionProducer fbAll = routedRingRead(
							"delay-network-feedback-" + channels + "x" + signalSize + "x" + bufSize,
							flatBuffer, heads1D, delays1D, false, feedbackMatrix,
							channels, signalSize, bufSize);
					CollectionProducer framesAll = c(in).add(fbAll)
							.reshape(shape(channels * signalSize));

					OperationList ops = new OperationList("delay_network");
					ops.add(next.push(output));

					if (bufSize == signalSize) {
						// One-frame ring: the new frame replaces the whole buffer. Every
						// feedback read targets the element being rewritten, so the
						// read-then-write stays within one work item and is safe in place.
						ops.add(into("delay-network-buffer-write-" + channels + "x" + bufSize,
								framesAll, buffer, false));
					} else {
						// Multi-frame ring: the feedback reads OTHER slots than the one
						// being rewritten, so the new frame must be materialized before
						// the in-place buffer update — a kernel that wrote the slot while
						// reading the ring would race against itself across work items.
						PackedCollection frameScratch =
								new PackedCollection(channels * signalSize);
						ops.add(into("delay-network-frames-" + channels + "x" + signalSize,
								framesAll, cp(frameScratch), false));
						ops.add(into("delay-network-buffer-write-" + channels + "x" + bufSize,
								new CollectionSlotUpdateComputation(
										shape(channels * bufSize),
										channels, bufSize, signalSize,
										flatBuffer, cp(frameScratch), heads1D),
								buffer, false));
					}

					ops.add(into("delay-network-head-write-" + channels + "x" + bufSize,
							ringHeadAdvance(heads1D, channels, signalSize, bufSize),
							heads, false));
					return ops;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("delay-network-backward"));
		return new DefaultBlock(multiShape, multiShape, forward, backward);
	}

	/**
	 * Builds a multi-channel delay block as the bank form of the {@code delay}
	 * primitive: every channel's delayed ring value is read in one computation, and the
	 * incoming frame is written back to each channel's ring in one operation. The ring
	 * spans {@code shape(buffer) / channels} samples per channel; a one-frame ring is
	 * overwritten whole, while longer rings update the head-aligned slot via
	 * {@link CollectionSlotUpdateComputation}.
	 *
	 * @param delaySamples delay in samples — shape {@code [channels]} for per-channel
	 *                     delays or {@code [1]} shared across channels
	 * @param buffer       per-channel ring buffers, total {@code channels * bufSize}
	 * @param heads        per-channel write head positions, shape {@code [channels]}
	 * @param channels     number of channels
	 * @param signalSize   samples per channel per forward pass
	 * @return a Block with shape {@code [channels, signalSize] → [channels, signalSize]}
	 */
	default Block multiChannelDelayBlock(CollectionProducer delaySamples,
										 CollectionProducer buffer,
										 CollectionProducer heads,
										 int channels, int signalSize) {
		int totalBuf = shape(buffer).getTotalSize();
		int bufSize = totalBuf / channels;
		if (totalBuf != channels * bufSize || bufSize % signalSize != 0) {
			throw new IllegalArgumentException("delay() bank requires per-channel rings"
					+ " spanning whole frames; buffer size " + totalBuf
					+ " does not divide into " + channels
					+ " channels of whole " + signalSize + "-sample frames");
		}
		if (shape(heads).getTotalSize() != channels) {
			throw new IllegalArgumentException("delay() bank requires one head per channel;"
					+ " got " + shape(heads).getTotalSize() + " for " + channels + " channels");
		}
		boolean scalarDelay = shape(delaySamples).getTotalSize() == 1;

		TraversalPolicy multiShape = shape(channels, signalSize);
		CollectionProducer heads1D = heads.reshape(shape(channels));
		CollectionProducer flatBuffer = buffer.reshape(shape(channels * bufSize));
		Cell<PackedCollection> forward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> {
					CollectionProducer output = routedRingRead(
							"delay-bank-read-" + channels + "x" + signalSize + "x" + bufSize,
							flatBuffer, heads1D, delaySamples, scalarDelay, null,
							channels, signalSize, bufSize);
					CollectionProducer framesAll = c(in)
							.reshape(shape(channels * signalSize));
					CollectionProducer newBuffer = bufSize == signalSize ? framesAll
							: new CollectionSlotUpdateComputation(
									shape(channels * bufSize), channels, bufSize, signalSize,
									flatBuffer, framesAll, heads1D);
					OperationList ops = new OperationList("delay-bank");
					// The ring is written BEFORE the delayed read is emitted, matching the
					// per-channel delay primitive's effective semantics: a delay shorter
					// than the frame reads back into the frame being processed, so the
					// current input must already be in the ring when the read evaluates.
					ops.add(into("delay-bank-buffer-write-" + channels + "x" + bufSize,
							newBuffer, buffer, false));
					ops.add(next.push(output));
					ops.add(into("delay-bank-head-write-" + channels + "x" + bufSize,
							ringHeadAdvance(heads1D, channels, signalSize, bufSize),
							heads, false));
					return ops;
				});
		Cell<PackedCollection> backward = Cell.of(
				(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
						Supplier<Runnable>>) (in, next) -> new OperationList("delay-bank-backward"));
		return new DefaultBlock(multiShape, multiShape, forward, backward);
	}

	/**
	 * Builds a single computation that reads every channel's delayed ring value and
	 * (optionally) routes the channels through an {@code [channels, channels]} matrix:
	 * {@code out[n, i] = sum_m matrix[n, m] * ring_m(i)} where
	 * {@code ring_m(i) = buffer[m * bufSize + (heads[m] + i - delays[m] + bufSize) % bufSize]}.
	 * With a {@code null} matrix the channels pass through unrouted
	 * ({@code out[n, i] = ring_n(i)}).
	 *
	 * <p>The whole read-and-route is one expression over the raw state collections, so
	 * the process tree has nothing to isolate and the stage compiles to one kernel.</p>
	 *
	 * @param name        operation name
	 * @param flatBuffer  flat ring buffer, total {@code channels * bufSize}
	 * @param heads       per-channel write head positions, shape {@code [channels]}
	 * @param delays      delay in samples, shape {@code [channels]} (or {@code [1]} when
	 *                    {@code scalarDelay} is set)
	 * @param scalarDelay whether every channel shares the single delay at index 0
	 * @param matrix      routing matrix, shape {@code [channels, channels]}, or {@code null}
	 * @param channels    number of channels
	 * @param signalSize  samples per channel per pass
	 * @param bufSize     per-channel ring size in samples
	 * @return the routed delayed signal, shape {@code [channels, signalSize]}
	 */
	private CollectionProducer routedRingRead(String name,
											  CollectionProducer flatBuffer,
											  CollectionProducer heads,
											  CollectionProducer delays,
											  boolean scalarDelay,
											  CollectionProducer matrix,
											  int channels, int signalSize, int bufSize) {
		TraversalPolicy outShape = shape(channels, signalSize);
		Producer[] args = matrix == null
				? new Producer[] { flatBuffer, heads, delays }
				: new Producer[] { flatBuffer, heads, delays, matrix };

		// The expression text is identical across channel counts (the count only sets
		// the number of work items), so cross-model instruction reuse would bind one
		// layout's compiled form to another's buffers. A null signature opts these
		// small per-model kernels out of instruction caching entirely.
		return new DefaultTraversableExpressionComputation(name, outShape,
				(Function<TraversableExpression[], CollectionExpression>) exprArgs ->
						DefaultCollectionExpression.create(outShape, idx -> {
							// The imod is a no-op (the channel index is always below the
							// channel count), but it embeds the channel count in the
							// expression structure: two layouts that differ ONLY in channel
							// count otherwise produce structurally identical expressions,
							// and structural identity is what compiled-instruction reuse
							// keys on — reuse across different counts binds one layout's
							// kernel to the other's buffers.
							Expression<?> n = idx.divide((long) signalSize)
									.imod((long) channels);
							Expression<?> i = idx.imod((long) signalSize);

							if (matrix == null) {
								return ringValueAt(exprArgs, n, i, bufSize, scalarDelay);
							}

							Expression<?> result = null;
							for (int m = 0; m < channels; m++) {
								Expression<?> value = ringValueAt(
										exprArgs, e(m), i, bufSize, scalarDelay);
								Expression<?> weight = exprArgs[4].getValueAt(
										n.multiply((long) channels).add(m));
								Expression<?> term = weight.multiply(value);
								result = result == null ? term : result.add(term);
							}
							return result;
						}),
				args) {
			@Override
			public String signature() { return null; }
		};
	}

	/**
	 * Expression for one channel's delayed ring value:
	 * {@code buffer[channel * bufSize + (heads[channel] + i - delays[channel] + bufSize) % bufSize]}.
	 *
	 * @param args        traversable arguments where {@code args[1]} is the flat ring
	 *                    buffer, {@code args[2]} the heads, and {@code args[3]} the delays
	 * @param channel     the channel index expression
	 * @param i           the sample offset expression within the pass
	 * @param bufSize     per-channel ring size in samples
	 * @param scalarDelay whether every channel shares the single delay at index 0
	 * @return the delayed value expression
	 */
	private Expression<?> ringValueAt(TraversableExpression[] args,
									  Expression<?> channel, Expression<?> i, int bufSize,
									  boolean scalarDelay) {
		Expression<Integer> head = args[2].getValueAt(channel).toInt();
		Expression<Integer> delay = args[3]
				.getValueAt(scalarDelay ? e(0) : channel).toInt();
		Expression<?> position = head.add(i).subtract(delay).add(bufSize)
				.imod((long) bufSize);
		return args[1].getValueAt(channel.multiply((long) bufSize).add(position));
	}

	/**
	 * Builds a single computation advancing every ring head by one frame:
	 * {@code (heads[n] + signalSize) % bufSize}.
	 *
	 * @param heads      per-channel write head positions, shape {@code [channels]}
	 * @param channels   number of channels
	 * @param signalSize samples per frame
	 * @param bufSize    per-channel ring size in samples
	 * @return the advanced head positions, shape {@code [channels]}
	 */
	private CollectionProducer ringHeadAdvance(CollectionProducer heads,
											   int channels, int signalSize, int bufSize) {
		TraversalPolicy outShape = shape(channels);
		return new DefaultTraversableExpressionComputation(
				"delay-network-head-advance-" + channels + "x" + signalSize + "x" + bufSize,
				outShape,
				(Function<TraversableExpression[], CollectionExpression>) exprArgs ->
						DefaultCollectionExpression.create(outShape, idx ->
								exprArgs[1].getValueAt(idx.imod((long) channels))
										.add(e((double) signalSize))
										.mod(e((double) bufSize))),
				heads) {
			@Override
			public String signature() { return null; }
		};
	}

}
