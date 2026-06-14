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
import org.almostrealism.ml.dsl.PdslChannelBank;
import org.almostrealism.ml.dsl.PdslInterpreter;
import org.almostrealism.ml.dsl.PdslParseException;
import org.almostrealism.ml.dsl.PdslPrimitiveContext;
import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toDouble;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.ForwardOnlyBlock;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Audio-domain PDSL primitives. {@link #registerWith(PdslInterpreter)} attaches the
 * audio-specific set ({@code fir}, {@code lowpass}, {@code highpass}, {@code biquad},
 * {@code delay}, {@code lfo}, {@code route}, {@code delay_network}) and the
 * multi-channel dispatcher used by {@code for each channel} to a
 * {@link PdslInterpreter} that was built fresh from a parsed PDSL program.
 *
 * <p>Domain-agnostic primitives ({@code identity}, {@code scale}, {@code repeat},
 * {@code sum_channels}) are supplied by the interpreter core itself — they are
 * pure tensor operations (pass-through, scalar multiplication, axis repetition,
 * axis summation) and do not embed any audio-domain assumption (sample rate,
 * cutoff frequency, recursive feedback). The audio module deliberately does not
 * register them so the same kernels are available to non-audio PDSL programs.</p>
 *
 * <p>This class lives in the audio domain (studio/compose) so the PDSL interpreter
 * core in {@code engine/ml} stays free of audio-specific dispatch. Callers that want
 * audio primitives use either {@link PdslInterpreter#registerPrimitive} directly or,
 * more typically, construct a {@code PdslLoader} with this class's
 * {@link #registerWith(PdslInterpreter)} method as the registrar:</p>
 *
 * <pre>{@code
 * PdslLoader loader = new PdslLoader(AudioDspPrimitives::registerWith);
 * }</pre>
 *
 * <p>The dispatchers normalise every shaped argument through
 * {@link PdslPrimitiveContext#toProducer} once at the registration boundary, so the
 * per-primitive code never has to discriminate between {@code Number},
 * {@code PackedCollection}, and {@code Producer} forms — the entire cross-form
 * dispatch lives in one place.</p>
 */
public class AudioDspPrimitives implements MultiChannelDspFeatures, TemporalFeatures, LayerFeatures {

	/**
	 * Single shared instance — safe because this class holds no per-instance state and
	 * exists solely to expose default-method block factories from
	 * {@link MultiChannelDspFeatures}, {@link TemporalFeatures}, and
	 * {@link LayerFeatures}.
	 */
	private static final AudioDspPrimitives INSTANCE = new AudioDspPrimitives();

	/**
	 * Register every audio primitive and the multi-channel dispatcher with
	 * {@code interpreter}. Idempotent: re-registering replaces any previous
	 * binding for the same primitive name.
	 *
	 * @param interpreter the freshly-built interpreter to configure
	 */
	public static void registerWith(PdslInterpreter interpreter) {
		AudioDspPrimitives self = INSTANCE;
		interpreter.registerPrimitive("fir", self::dispatchFir);
		interpreter.registerPrimitive("lowpass", self::dispatchLowpass);
		interpreter.registerPrimitive("highpass", self::dispatchHighpass);
		interpreter.registerPrimitive("biquad", self::dispatchBiquad);
		interpreter.registerPrimitive("clip", self::dispatchClip);
		interpreter.registerPrimitive("delay", self::dispatchDelay);
		interpreter.registerPrimitive("lfo", self::dispatchLfo);
		interpreter.registerPrimitive("route", self::dispatchRoute);
		interpreter.registerPrimitive("delay_network", self::dispatchDelayNetwork);
		interpreter.registerPrimitive("feedback", self::dispatchFeedback);
		interpreter.setMultiChannelDispatcher(self::perChannelBlock);
	}

	/** Wraps {@link MultiOrderFilter} as a fixed-shape FIR block factory. */
	private Function<TraversalPolicy, Block> firFilterBlock(String name,
															CollectionProducer coefficients) {
		return shape -> new ForwardOnlyBlock(layer(name, shape, shape,
				input -> MultiOrderFilter.create(input, coefficients)));
	}

	// ---- Single-channel DSP primitives ------------------------------------

	/** {@code fir(coefficients)} — accepts a per-channel coefficient bank when vectorized. */
	private Function<TraversalPolicy, Block> dispatchFir(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"fir() expects 1 coefficients argument, got " + args.size());
		}

		if (args.get(0) instanceof PdslChannelBank) {
			// Vectorized for-each: the [channels, taps] bank is applied to every channel
			// of the [channels, signalSize] input in a single kernel.
			PdslChannelBank bank = (PdslChannelBank) args.get(0);
			CollectionProducer coefficients =
					ctx.toProducer(bank.getSource(), null, "fir() coefficients");
			int channels = bank.getChannels();
			return shape -> new ForwardOnlyBlock(layer("fir", shape, shape,
					input -> MultiOrderFilter.createMultiChannel(input, coefficients, channels)));
		}

		CollectionProducer coefficients = ctx.toProducer(args.get(0), null, "fir() coefficients");
		return firFilterBlock("fir", coefficients);
	}

	/** {@code lowpass(cutoff, sampleRate, filterOrder)}. */
	private Function<TraversalPolicy, Block> dispatchLowpass(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"lowpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
		}
		Producer<PackedCollection> cutoff = ctx.toProducer(args.get(0), shape(1), "lowpass() cutoff");
		int sampleRate = PdslPrimitiveContext.toInt(args.get(1));
		int order = PdslPrimitiveContext.toInt(args.get(2));
		return firFilterBlock("lowpass", lowPassCoefficients(cutoff, sampleRate, order));
	}

	/** {@code highpass(cutoff, sampleRate, filterOrder)}. */
	private Function<TraversalPolicy, Block> dispatchHighpass(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"highpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
		}
		Producer<PackedCollection> cutoff = ctx.toProducer(args.get(0), shape(1), "highpass() cutoff");
		int sampleRate = PdslPrimitiveContext.toInt(args.get(1));
		int order = PdslPrimitiveContext.toInt(args.get(2));
		return firFilterBlock("highpass", highPassCoefficients(cutoff, sampleRate, order));
	}

	/**
	 * {@code clip(lo, hi)} — element-wise hard clamp of the signal into {@code [lo, hi]}.
	 * This is the PDSL counterpart of the two hard bounds in the Java mixdown chain:
	 * {@link org.almostrealism.audio.filter.AudioPassFilter} clamps its input to
	 * {@code [-MAX_INPUT, MAX_INPUT]} every sample before filtering, and the master bus
	 * applies {@code bound(in * gain, -1, 1)} as its limiter. Stateless and fully
	 * vectorised, so it composes with any buffer shape.
	 *
	 * @param args the two bound arguments (lo, hi)
	 * @param ctx  the primitive context
	 * @return a clamp block factory
	 */
	private Function<TraversalPolicy, Block> dispatchClip(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 2) {
			throw new PdslParseException(
					"clip() expects 2 arguments (lo, hi), got " + args.size());
		}
		double lo = toDouble(args.get(0));
		double hi = toDouble(args.get(1));
		if (lo >= hi) {
			throw new PdslParseException(
					"clip() requires lo < hi, got lo=" + lo + " hi=" + hi);
		}
		// bound() delegates to min()/max(), which collapse to a scalar shape when the two
		// operand sizes differ — so the bounds must be expanded to the block shape for the
		// clamp to stay element-wise over the whole buffer.
		return shape -> new ForwardOnlyBlock(layer("clip", shape, shape,
				input -> min(max(input, constant(shape, lo)), constant(shape, hi))));
	}

	/** {@code biquad(b0, b1, b2, a1, a2, history)} — stateful biquad IIR. */
	private Function<TraversalPolicy, Block> dispatchBiquad(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 6) {
			throw new PdslParseException(
					"biquad() expects 6 arguments (b0, b1, b2, a1, a2, history), got " + args.size());
		}
		CollectionProducer b0p = ctx.toProducer(args.get(0), shape(1), "biquad() b0");
		CollectionProducer b1p = ctx.toProducer(args.get(1), shape(1), "biquad() b1");
		CollectionProducer b2p = ctx.toProducer(args.get(2), shape(1), "biquad() b2");
		CollectionProducer a1p = ctx.toProducer(args.get(3), shape(1), "biquad() a1");
		CollectionProducer a2p = ctx.toProducer(args.get(4), shape(1), "biquad() a2");
		CollectionProducer history = ctx.toProducer(args.get(5), shape(4), "biquad() history");
		return shape -> {
			int n = shape.getSize();
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						CollectionProducer h0 = subset(shape(1), history, 0);
						CollectionProducer h1 = subset(shape(1), history, 1);
						CollectionProducer h2 = subset(shape(1), history, 2);
						CollectionProducer h3 = subset(shape(1), history, 3);
						CollectionProducer output = b0p.multiply(in)
								.add(b1p.multiply(h0))
								.add(b2p.multiply(h1))
								.subtract(a1p.multiply(h2))
								.subtract(a2p.multiply(h3));
						CollectionProducer flatIn = c(in).reshape(shape(n));
						CollectionProducer flatOut = output.reshape(shape(n));
						CollectionProducer newH0 = subset(shape(1), flatIn, n - 1);
						CollectionProducer newH1 = n >= 2
								? subset(shape(1), flatIn, n - 2)
								: subset(shape(1), history, 0);
						CollectionProducer newH2 = subset(shape(1), flatOut, n - 1);
						CollectionProducer newH3 = n >= 2
								? subset(shape(1), flatOut, n - 2)
								: subset(shape(1), history, 2);
						OperationList ops = new OperationList("biquad");
						ops.add(next.push(output));
						ops.add(into("biquad-history",
								concat(newH0, newH1, newH2, newH3),
								history, false));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("biquad-backward"));
			return new DefaultBlock(shape, shape, forward, backward);
		};
	}

	/** {@code delay(delaySamples, buffer, head)} — stateful integer-sample delay. */
	private Function<TraversalPolicy, Block> dispatchDelay(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"delay() expects 3 arguments (delaySamples, buffer, head), got " + args.size());
		}

		if (args.get(1) instanceof PdslChannelBank || args.get(2) instanceof PdslChannelBank) {
			// Vectorized for-each: every channel's ring state arrives as one bank, and
			// the whole multi-channel delay runs as one read and one write per pass.
			if (!(args.get(1) instanceof PdslChannelBank)
					|| !(args.get(2) instanceof PdslChannelBank)) {
				throw new PdslParseException("delay() requires the buffer and head"
						+ " to both be per-channel banks when vectorized");
			}
			PdslChannelBank bufferBank = (PdslChannelBank) args.get(1);
			PdslChannelBank headBank = (PdslChannelBank) args.get(2);
			Object delayArg = args.get(0) instanceof PdslChannelBank
					? ((PdslChannelBank) args.get(0)).getSource() : args.get(0);
			CollectionProducer delaySamples =
					ctx.toProducer(delayArg, null, "delay() delaySamples");
			CollectionProducer bankBuffer =
					ctx.toProducer(bufferBank.getSource(), null, "delay() buffer");
			CollectionProducer bankHeads =
					ctx.toProducer(headBank.getSource(), null, "delay() head");
			int channels = bufferBank.getChannels();
			return shape -> multiChannelDelayBlock(delaySamples, bankBuffer, bankHeads,
					channels, shape.getTotalSize() / channels);
		}

		CollectionProducer delaySamplesP = ctx.toProducer(args.get(0), shape(1),
				"delay() delaySamples");
		CollectionProducer buffer = ctx.toProducer(args.get(1), null, "delay() buffer");
		CollectionProducer head = ctx.toProducer(args.get(2), shape(1), "delay() head");
		int bufSize = shape(buffer).getSize();
		return shape -> {
			int n = shape.getSize();
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						CollectionProducer readPositions = mod(
								head.add(integers(0, n))
										.subtract(delaySamplesP)
										.add(c(bufSize)),
								c(bufSize));
						CollectionProducer output =
								c(buffer, readPositions).reshape(shape);
						CollectionProducer flatInput = c(in).reshape(shape(bufSize));
						CollectionProducer newHead = head
								.add(c((double) n))
								.mod(c((double) bufSize));
						OperationList ops = new OperationList("delay");
						ops.add(next.push(output));
						ops.add(into("delay-buffer-write", flatInput,
								buffer, false));
						ops.add(into("delay-head-write", newHead,
								head, false));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("delay-backward"));
			return new DefaultBlock(shape, shape, forward, backward);
		};
	}

	/** {@code lfo(freqHz, sampleRate, phase)} — stateful sinusoidal LFO. */
	private Function<TraversalPolicy, Block> dispatchLfo(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"lfo() expects 3 arguments (freqHz, sampleRate, phase), got " + args.size());
		}
		CollectionProducer freq = ctx.toProducer(args.get(0), shape(1), "lfo() freqHz");
		double sampleRate = toDouble(args.get(1));
		CollectionProducer phase = ctx.toProducer(args.get(2), shape(1), "lfo() phase");
		CollectionProducer phaseIncrement = freq.multiply(c(2.0 * Math.PI / sampleRate));
		return shape -> {
			int n = shape.getSize();
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						CollectionProducer phases = phase
								.add(integers(0, n).multiply(phaseIncrement));
						CollectionProducer output = sin(phases).reshape(shape);
						CollectionProducer newPhase = phase
								.add(c((double) n).multiply(phaseIncrement))
								.mod(c(2.0 * Math.PI));
						OperationList ops = new OperationList("lfo");
						ops.add(next.push(output));
						ops.add(into("lfo-phase-update", newPhase,
								phase, false));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("lfo-backward"));
			return new DefaultBlock(shape, shape, forward, backward);
		};
	}

	// ---- Multi-channel primitives -----------------------------------------

	/** {@code route(transmissionMatrix)} — supports rectangular routing. */
	private Block dispatchRoute(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"route() expects 1 transmission matrix argument, got " + args.size());
		}
		int inputChannels = ctx.channels();
		int signalSize = ctx.signalSize();
		// Normalise without an expected shape so the matrix's actual shape can be
		// inspected for output-channel inference; then validate explicitly.
		CollectionProducer matrix = ctx.toProducer(args.get(0), null, "route() matrix");
		TraversalPolicy ms = shape(matrix);
		if (ms.getDimensions() != 2) {
			throw new PdslParseException(
					"route() matrix must be 2D, got shape with "
							+ ms.getDimensions() + " dimensions");
		}
		int matIn = ms.length(0);
		int matOut = ms.length(1);
		if (matIn <= 0 || matOut <= 0) {
			throw new PdslParseException(
					"route() matrix must have positive dimensions, got ["
							+ matIn + ", " + matOut + "]");
		}
		if (matIn != inputChannels) {
			throw new PdslParseException(
					"route() matrix's first axis (" + matIn
							+ ") must match upstream channel count (" + inputChannels + ")");
		}
		Block block = routeBlock(matrix, inputChannels, matOut, signalSize);
		// Rectangular routing changes the effective channel count for downstream
		// constructs — propagate it via the context.
		if (matOut != inputChannels) {
			ctx.setChannels(matOut);
		}
		return block;
	}

	/** {@code delay_network(delay_samples, feedback_matrix, buffer, heads)}. */
	private Block dispatchDelayNetwork(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 4) {
			throw new PdslParseException(
					"delay_network() expects 4 arguments (delay_samples, feedback_matrix, "
							+ "buffer, heads), got " + args.size());
		}
		int channels = ctx.channels();
		int signalSize = ctx.signalSize();
		CollectionProducer delays = ctx.toProducer(args.get(0), shape(channels),
				"delay_network() delay_samples");
		CollectionProducer feedback = ctx.toProducer(args.get(1), shape(channels, channels),
				"delay_network() feedback_matrix");
		CollectionProducer buffer = ctx.toProducer(args.get(2), null,
				"delay_network() buffer");
		CollectionProducer heads = ctx.toProducer(args.get(3), shape(channels),
				"delay_network() heads");
		return delayNetworkBlock(delays, feedback, buffer, heads, channels, signalSize);
	}

	/**
	 * {@code feedback(delay_samples, transmission_matrix, passthrough_matrix, buffer, heads)}
	 * — the block-parallel feedback delay network, the PDSL analogue of {@code CellList.mself}.
	 *
	 * <p>The delayed output is routed back into the ring through {@code transmission_matrix}
	 * (added to the incoming signal) and emitted to the next layer through
	 * {@code passthrough_matrix}. For the single-channel feedback comb both matrices are
	 * {@code [1, 1]}: {@code transmission = [[g]]} (the feedback gain) and {@code passthrough}
	 * the output level. {@code mself(input_level, T, P)} is {@code scale(input_level)} followed
	 * by {@code feedback(T, ..., P)}.</p>
	 */
	private Block dispatchFeedback(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 5) {
			throw new PdslParseException(
					"feedback() expects 5 arguments (delay_samples, transmission_matrix, "
							+ "passthrough_matrix, buffer, heads), got " + args.size());
		}
		int channels = ctx.channels();
		int signalSize = ctx.signalSize();
		CollectionProducer delays = ctx.toProducer(args.get(0), shape(channels),
				"feedback() delay_samples");
		CollectionProducer transmission = ctx.toProducer(args.get(1),
				shape(channels, channels), "feedback() transmission_matrix");
		CollectionProducer passthrough = ctx.toProducer(args.get(2),
				shape(channels, channels), "feedback() passthrough_matrix");
		CollectionProducer buffer = ctx.toProducer(args.get(3), null, "feedback() buffer");
		CollectionProducer heads = ctx.toProducer(args.get(4), shape(channels),
				"feedback() heads");
		return feedbackNetworkBlock(delays, transmission, passthrough,
				buffer, heads, channels, signalSize);
	}
}
