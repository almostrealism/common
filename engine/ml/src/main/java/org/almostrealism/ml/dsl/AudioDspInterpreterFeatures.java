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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;
import org.almostrealism.model.ForwardOnlyBlock;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.computations.MultiOrderFilter;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Mixin interface providing the audio-domain primitive bindings used by
 * {@link PdslInterpreter} when a PDSL program calls one of the audio DSP
 * primitives ({@code fir}, {@code scale}, {@code identity}, {@code lowpass},
 * {@code highpass}, {@code biquad}, {@code delay}, {@code lfo}) or one of the
 * multi-channel block factories ({@code route}, {@code sum_channels},
 * {@code fan_out}).
 *
 * <p>The methods here all wrap existing {@link TemporalFeatures} and
 * {@link MultiChannelDspFeatures} primitives — there is no audio-specific
 * computation defined in this interface. Its sole purpose is to keep
 * {@link PdslInterpreter} free of audio-domain dispatch so that adding a new
 * audio primitive does not require editing the interpreter; the new method is
 * added here and registered via {@link #registerAudioPrimitives(Map)}.</p>
 */
public interface AudioDspInterpreterFeatures
		extends LayerFeatures, TemporalFeatures, MultiChannelDspFeatures {

	/** Converts a numeric object to an {@code int}, used by argument-list dispatchers. */
	static int toInt(Object value) {
		if (value instanceof Integer) return (Integer) value;
		if (value instanceof Double) return ((Double) value).intValue();
		if (value instanceof Number) return ((Number) value).intValue();
		throw new PdslParseException("Expected int but got " + value);
	}

	/** Converts a numeric object to a {@code double}, used by argument-list dispatchers. */
	static double toDouble(Object value) {
		if (value instanceof Double) return (Double) value;
		if (value instanceof Integer) return (Integer) value;
		if (value instanceof Number) return ((Number) value).doubleValue();
		throw new PdslParseException("Expected number but got " + value);
	}

	/**
	 * Registers each audio primitive name in {@code registry} mapped to its dispatch
	 * method. {@link PdslInterpreter} calls this once at construction time so the
	 * lookup table is built without the interpreter having to enumerate the
	 * primitives itself.
	 *
	 * @param registry destination map (primitive name → handler taking the evaluated
	 *                 argument list and returning a {@link Block} or a
	 *                 {@link Function}{@code <TraversalPolicy, Block>} factory)
	 */
	default void registerAudioPrimitives(Map<String, Function<List<Object>, Object>> registry) {
		registry.put("fir", this::callFir);
		registry.put("scale", this::callScale);
		registry.put("identity", this::callIdentity);
		registry.put("lowpass", this::callLowpass);
		registry.put("highpass", this::callHighpass);
		registry.put("biquad", this::callBiquad);
		registry.put("delay", this::callDelay);
		registry.put("lfo", this::callLfo);
	}

	/**
	 * Returns a block factory that wraps the given FIR coefficients in a {@link MultiOrderFilter}.
	 *
	 * @param name         primitive name used as the layer label
	 * @param coefficients pre-computed FIR coefficient producer
	 * @return a factory that creates a FIR filter block for any input shape
	 */
	default Function<TraversalPolicy, Block> firFilterBlock(String name,
															 CollectionProducer coefficients) {
		return shape -> new ForwardOnlyBlock(layer(name, shape, shape,
				input -> MultiOrderFilter.create(input, coefficients)));
	}

	/**
	 * Builds a FIR (Finite Impulse Response) filter block that convolves the input signal
	 * with the provided coefficient array.
	 *
	 * @param args one weight argument: the FIR coefficient array ({@code filterOrder+1} elements)
	 * @return a factory that creates a FIR filter block for any input shape
	 */
	default Object callFir(List<Object> args) {
		if (args.size() == 1 && args.get(0) instanceof PackedCollection) {
			PackedCollection coefficients = (PackedCollection) args.get(0);
			return firFilterBlock("fir", cp(coefficients));
		}
		throw new PdslParseException(
				"fir() expects 1 weight argument (coefficients), got " + args.size());
	}

	/**
	 * Builds a scalar scaling block that multiplies each input element by the given factor.
	 *
	 * <p>The factor argument may be supplied as either a numeric literal (folded into the
	 * compiled kernel as a constant) or a {@link Producer} of a shape-{@code [1]}
	 * {@link PackedCollection}. The producer form supports render-time mutable scalars
	 * (a {@code cp(slot)} over a 1-element collection mutated between renders) and
	 * clock-driven envelopes (a producer that reads the audio clock and returns a
	 * different value every sample).</p>
	 *
	 * @param args one argument: the scale factor as either a {@link Number} or a
	 *             {@link Producer} of {@link PackedCollection} with shape {@code [1]}
	 * @return a factory that creates a scale block for any input shape
	 */
	default Object callScale(List<Object> args) {
		if (args.size() == 1) {
			Object arg = args.get(0);
			if (arg instanceof Number) {
				double factor = toDouble(arg);
				return scale(factor);
			}
			if (arg instanceof Producer) {
				Producer<PackedCollection> factor = (Producer<PackedCollection>) arg;
				return (Function<TraversalPolicy, Block>)
						(shape -> layer("scale", shape, shape,
								input -> multiply(c(input).each(),
										subset(shape(1), factor, 0))));
			}
			throw new PdslParseException(
					"scale() expects 1 numeric or Producer argument, got "
							+ (arg == null ? "null" : arg.getClass().getSimpleName()));
		}
		throw new PdslParseException(
				"scale() expects 1 argument (factor), got " + args.size());
	}

	/**
	 * Builds an identity (pass-through) block that forwards its input unchanged.
	 *
	 * @param args no arguments
	 * @return a factory that creates a pass-through block for any input shape
	 */
	default Object callIdentity(List<Object> args) {
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

	/**
	 * Builds a low-pass FIR filter block using Hamming-windowed sinc coefficients.
	 *
	 * @param args three numeric arguments: cutoff frequency (Hz), sample rate (Hz), filter order
	 * @return a factory that creates a low-pass FIR filter block for any input shape
	 */
	default Object callLowpass(List<Object> args) {
		if (args.size() == 3) {
			double cutoff = toDouble(args.get(0));
			int sampleRate = toInt(args.get(1));
			int order = toInt(args.get(2));
			return firFilterBlock("lowpass",
					lowPassCoefficients(c(cutoff), sampleRate, order));
		}
		throw new PdslParseException(
				"lowpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
	}

	/**
	 * Builds a high-pass FIR filter block using spectral inversion of the Hamming-windowed sinc.
	 *
	 * @param args three numeric arguments: cutoff frequency (Hz), sample rate (Hz), filter order
	 * @return a factory that creates a high-pass FIR filter block for any input shape
	 */
	default Object callHighpass(List<Object> args) {
		if (args.size() == 3) {
			double cutoff = toDouble(args.get(0));
			int sampleRate = toInt(args.get(1));
			int order = toInt(args.get(2));
			return firFilterBlock("highpass",
					highPassCoefficients(c(cutoff), sampleRate, order));
		}
		throw new PdslParseException(
				"highpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
	}

	/**
	 * Builds a stateful biquad IIR filter block.
	 *
	 * <p>Applies the difference equation and updates the {@code history} collection
	 * after each forward pass so that state persists across successive calls.</p>
	 *
	 * @param args six arguments: b0, b1, b2, a1, a2 (numeric), history (PackedCollection, size 4)
	 * @return a factory that creates a stateful biquad filter block for any input shape
	 */
	default Object callBiquad(List<Object> args) {
		if (args.size() == 6) {
			double b0 = toDouble(args.get(0));
			double b1 = toDouble(args.get(1));
			double b2 = toDouble(args.get(2));
			double a1 = toDouble(args.get(3));
			double a2 = toDouble(args.get(4));
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
							CollectionProducer output = multiply(b0, in)
									.add(c(b1).multiply(h0))
									.add(c(b2).multiply(h1))
									.subtract(c(a1).multiply(h2))
									.subtract(c(a2).multiply(h3));
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
		throw new PdslParseException(
				"biquad() expects 6 arguments (b0, b1, b2, a1, a2, history), got " + args.size());
	}

	/**
	 * Builds a stateful integer-sample delay line block.
	 *
	 * <p>Reads delayed samples from the circular {@code buffer}, then writes the input
	 * into the buffer and advances {@code head} so state persists across successive calls.</p>
	 *
	 * @param args three arguments: delaySamples (int), buffer (PackedCollection), head (PackedCollection, size 1)
	 * @return a factory that creates a stateful delay-line block for any input shape
	 */
	default Object callDelay(List<Object> args) {
		if (args.size() == 3) {
			int delaySamples = toInt(args.get(0));
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
											.subtract(c(delaySamples))
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
		throw new PdslParseException(
				"delay() expects 3 arguments (delaySamples, buffer, head), got " + args.size());
	}

	/**
	 * Builds a stateful sinusoidal LFO block.
	 *
	 * <p>Produces sin values for the current phase window and advances {@code phase}
	 * by {@code n * phaseIncrement} (mod 2π) so subsequent calls continue seamlessly.</p>
	 *
	 * @param args three arguments: freqHz (numeric), sampleRate (numeric), phase (PackedCollection, size 1)
	 * @return a factory that creates a stateful LFO block for any input shape
	 */
	default Object callLfo(List<Object> args) {
		if (args.size() == 3) {
			double freqHz = toDouble(args.get(0));
			double sampleRate = toDouble(args.get(1));
			PackedCollection phase = (PackedCollection) args.get(2);
			double phaseIncrement = 2.0 * Math.PI * freqHz / sampleRate;
			return (Function<TraversalPolicy, Block>) (shape -> {
				int n = shape.getSize();
				Cell<PackedCollection> forward = Cell.of(
						(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
								Supplier<Runnable>>) (in, next) -> {
							CollectionProducer phases = cp(phase)
									.add(integers(0, n).multiply(c(phaseIncrement)));
							CollectionProducer output = sin(phases).reshape(shape);
							CollectionProducer newPhase = cp(phase)
									.add(c((double) n * phaseIncrement))
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
		throw new PdslParseException(
				"lfo() expects 3 arguments (freqHz, sampleRate, phase), got " + args.size());
	}

	/**
	 * Validates and delegates to {@link MultiChannelDspFeatures#routeBlock}.
	 *
	 * @param args       one weight argument: the transmission matrix
	 * @param channels   number of channels (from PDSL environment)
	 * @param signalSize samples per channel (from PDSL environment)
	 * @return the cross-channel routing {@link Block}
	 */
	default Block callRoute(List<Object> args, int channels, int signalSize) {
		if (args.size() != 1 || !(args.get(0) instanceof PackedCollection)) {
			throw new PdslParseException(
					"route() expects 1 weight argument (transmission matrix), got " + args.size());
		}
		return routeBlock((PackedCollection) args.get(0), channels, signalSize);
	}

	/**
	 * Validates and delegates to {@link MultiChannelDspFeatures#sumChannelsBlock}.
	 *
	 * @param args       no arguments
	 * @param channels   number of input channels (from PDSL environment)
	 * @param signalSize samples per channel (from PDSL environment)
	 * @return the channel-collapse {@link Block}
	 */
	default Block callSumChannels(List<Object> args, int channels, int signalSize) {
		if (!args.isEmpty()) {
			throw new PdslParseException("sum_channels() expects no arguments, got " + args.size());
		}
		return sumChannelsBlock(channels, signalSize);
	}

	/**
	 * Validates and delegates to {@link MultiChannelDspFeatures#fanOutBlock}.
	 *
	 * @param args       one numeric argument: the channel count
	 * @param signalSize samples per channel (from PDSL environment)
	 * @return the fan-out {@link Block}
	 */
	default Block callFanOut(List<Object> args, int signalSize) {
		if (args.size() != 1) {
			throw new PdslParseException("fan_out() expects 1 argument (n channels), got " + args.size());
		}
		return fanOutBlock(toInt(args.get(0)), signalSize);
	}
}
