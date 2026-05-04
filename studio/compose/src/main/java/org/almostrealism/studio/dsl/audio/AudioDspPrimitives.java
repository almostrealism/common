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
import org.almostrealism.ml.dsl.PdslInterpreter;
import org.almostrealism.ml.dsl.PdslParseException;
import org.almostrealism.ml.dsl.PdslPrimitive;
import org.almostrealism.ml.dsl.PdslPrimitiveContext;
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
 * full audio set ({@code fir}, {@code scale}, {@code identity}, {@code lowpass},
 * {@code highpass}, {@code biquad}, {@code delay}, {@code lfo}, {@code route},
 * {@code sum_channels}, {@code fan_out}, {@code delay_network}) and the multi-channel
 * dispatcher used by {@code for each channel} to a {@link PdslInterpreter} that was
 * built fresh from a parsed PDSL program.
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
		interpreter.registerPrimitive("scale", self::dispatchScale);
		interpreter.registerPrimitive("identity", self::dispatchIdentity);
		interpreter.registerPrimitive("lowpass", self::dispatchLowpass);
		interpreter.registerPrimitive("highpass", self::dispatchHighpass);
		interpreter.registerPrimitive("biquad", self::dispatchBiquad);
		interpreter.registerPrimitive("delay", self::dispatchDelay);
		interpreter.registerPrimitive("lfo", self::dispatchLfo);
		interpreter.registerPrimitive("route", self::dispatchRoute);
		interpreter.registerPrimitive("sum_channels", self::dispatchSumChannels);
		interpreter.registerPrimitive("fan_out", self::dispatchFanOut);
		interpreter.registerPrimitive("delay_network", self::dispatchDelayNetwork);
		interpreter.setMultiChannelDispatcher(self::perChannelBlock);
	}

	/** Wraps {@link MultiOrderFilter} as a fixed-shape FIR block factory. */
	private Function<TraversalPolicy, Block> firFilterBlock(String name,
															CollectionProducer coefficients) {
		return shape -> new ForwardOnlyBlock(layer(name, shape, shape,
				input -> MultiOrderFilter.create(input, coefficients)));
	}

	// ---- Single-channel DSP primitives ------------------------------------

	/** {@code fir(coefficients)}. */
	private Object dispatchFir(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"fir() expects 1 coefficients argument, got " + args.size());
		}
		CollectionProducer coefficients = ctx.toProducer(args.get(0), null, "fir() coefficients");
		return firFilterBlock("fir", coefficients);
	}

	/** {@code scale(factor)}. */
	private Object dispatchScale(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 1) {
			throw new PdslParseException("scale() expects 1 argument (factor), got " + args.size());
		}
		CollectionProducer factor = ctx.toProducer(args.get(0), shape(1), "scale() factor");
		return (Function<TraversalPolicy, Block>)
				(shape -> layer("scale", shape, shape,
						input -> multiply(c(input).each(), factor)));
	}

	/** {@code identity()} — pass-through block. */
	private Object dispatchIdentity(List<Object> args, PdslPrimitiveContext ctx) {
		if (!args.isEmpty()) {
			throw new PdslParseException(
					"identity() expects no arguments, got " + args.size());
		}
		return (Function<TraversalPolicy, Block>) (shape -> {
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("identity-backward"));
			return new DefaultBlock(shape, shape, null, backward);
		});
	}

	/** {@code lowpass(cutoff, sampleRate, filterOrder)}. */
	private Object dispatchLowpass(List<Object> args, PdslPrimitiveContext ctx) {
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
	private Object dispatchHighpass(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"highpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
		}
		Producer<PackedCollection> cutoff = ctx.toProducer(args.get(0), shape(1), "highpass() cutoff");
		int sampleRate = PdslPrimitiveContext.toInt(args.get(1));
		int order = PdslPrimitiveContext.toInt(args.get(2));
		return firFilterBlock("highpass", highPassCoefficients(cutoff, sampleRate, order));
	}

	/** {@code biquad(b0, b1, b2, a1, a2, history)} — stateful biquad IIR. */
	private Object dispatchBiquad(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 6) {
			throw new PdslParseException(
					"biquad() expects 6 arguments (b0, b1, b2, a1, a2, history), got " + args.size());
		}
		CollectionProducer b0p = ctx.toProducer(args.get(0), shape(1), "biquad() b0");
		CollectionProducer b1p = ctx.toProducer(args.get(1), shape(1), "biquad() b1");
		CollectionProducer b2p = ctx.toProducer(args.get(2), shape(1), "biquad() b2");
		CollectionProducer a1p = ctx.toProducer(args.get(3), shape(1), "biquad() a1");
		CollectionProducer a2p = ctx.toProducer(args.get(4), shape(1), "biquad() a2");
		PackedCollection history = (PackedCollection) args.get(5);
		return (Function<TraversalPolicy, Block>) (shape -> {
			int n = shape.getSize();
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						CollectionProducer hist = cp(history);
						CollectionProducer h0 = subset(shape(1), hist, 0);
						CollectionProducer h1 = subset(shape(1), hist, 1);
						CollectionProducer h2 = subset(shape(1), hist, 2);
						CollectionProducer h3 = subset(shape(1), hist, 3);
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
								: subset(shape(1), cp(history), 0);
						CollectionProducer newH2 = subset(shape(1), flatOut, n - 1);
						CollectionProducer newH3 = n >= 2
								? subset(shape(1), flatOut, n - 2)
								: subset(shape(1), cp(history), 2);
						OperationList ops = new OperationList("biquad");
						ops.add(next.push(output));
						ops.add(into("biquad-history",
								concat(newH0, newH1, newH2, newH3),
								cp(history), false));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("biquad-backward"));
			return new DefaultBlock(shape, shape, forward, backward);
		});
	}

	/** {@code delay(delaySamples, buffer, head)} — stateful integer-sample delay. */
	private Object dispatchDelay(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"delay() expects 3 arguments (delaySamples, buffer, head), got " + args.size());
		}
		CollectionProducer delaySamplesP = ctx.toProducer(args.get(0), shape(1),
				"delay() delaySamples");
		PackedCollection buffer = (PackedCollection) args.get(1);
		PackedCollection head = (PackedCollection) args.get(2);
		int bufSize = buffer.getShape().getSize();
		return (Function<TraversalPolicy, Block>) (shape -> {
			int n = shape.getSize();
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						CollectionProducer readPositions = mod(
								cp(head).add(integers(0, n))
										.subtract(delaySamplesP)
										.add(c(bufSize)),
								c(bufSize));
						CollectionProducer output =
								c(cp(buffer), readPositions).reshape(shape);
						CollectionProducer flatInput = c(in).reshape(shape(bufSize));
						CollectionProducer newHead = cp(head)
								.add(c((double) n))
								.mod(c((double) bufSize));
						OperationList ops = new OperationList("delay");
						ops.add(next.push(output));
						ops.add(into("delay-buffer-write", flatInput,
								cp(buffer), false));
						ops.add(into("delay-head-write", newHead,
								cp(head), false));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("delay-backward"));
			return new DefaultBlock(shape, shape, forward, backward);
		});
	}

	/** {@code lfo(freqHz, sampleRate, phase)} — stateful sinusoidal LFO. */
	private Object dispatchLfo(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"lfo() expects 3 arguments (freqHz, sampleRate, phase), got " + args.size());
		}
		CollectionProducer freq = ctx.toProducer(args.get(0), shape(1), "lfo() freqHz");
		double sampleRate = PdslPrimitiveContext.toDouble(args.get(1));
		PackedCollection phase = (PackedCollection) args.get(2);
		CollectionProducer phaseIncrement = freq.multiply(c(2.0 * Math.PI / sampleRate));
		return (Function<TraversalPolicy, Block>) (shape -> {
			int n = shape.getSize();
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						CollectionProducer phases = cp(phase)
								.add(integers(0, n).multiply(phaseIncrement));
						CollectionProducer output = sin(phases).reshape(shape);
						CollectionProducer newPhase = cp(phase)
								.add(c((double) n).multiply(phaseIncrement))
								.mod(c(2.0 * Math.PI));
						OperationList ops = new OperationList("lfo");
						ops.add(next.push(output));
						ops.add(into("lfo-phase-update", newPhase,
								cp(phase), false));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> new OperationList("lfo-backward"));
			return new DefaultBlock(shape, shape, forward, backward);
		});
	}

	// ---- Multi-channel primitives -----------------------------------------

	/** {@code route(transmissionMatrix)} — supports rectangular routing. */
	private Object dispatchRoute(List<Object> args, PdslPrimitiveContext ctx) {
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

	/** {@code sum_channels()} — collapse channel axis by summation. */
	private Object dispatchSumChannels(List<Object> args, PdslPrimitiveContext ctx) {
		if (!args.isEmpty()) {
			throw new PdslParseException("sum_channels() expects no arguments, got " + args.size());
		}
		return sumChannelsBlock(ctx.channels(), ctx.signalSize());
	}

	/** {@code fan_out(n)} — replicate single-channel input to {@code n} channels. */
	private Object dispatchFanOut(List<Object> args, PdslPrimitiveContext ctx) {
		if (args.size() != 1) {
			throw new PdslParseException("fan_out() expects 1 argument (n channels), got " + args.size());
		}
		return fanOutBlock(PdslPrimitiveContext.toInt(args.get(0)), ctx.signalSize());
	}

	/** {@code delay_network(delay_samples, feedback_matrix, buffer, heads)}. */
	private Object dispatchDelayNetwork(List<Object> args, PdslPrimitiveContext ctx) {
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
		PackedCollection buffer = (PackedCollection) args.get(2);
		PackedCollection heads = (PackedCollection) args.get(3);
		return delayNetworkBlock(delays, feedback, buffer, heads, channels, signalSize);
	}
}
