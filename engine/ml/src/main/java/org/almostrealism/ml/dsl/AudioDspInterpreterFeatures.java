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
	 * Resolves an argument into a {@link CollectionProducer} matching the requested
	 * {@code expectedShape}. This is the canonical conversion used for every audio
	 * primitive argument that may be supplied as a numeric literal, a
	 * {@link PackedCollection}, or a {@link Producer}. The same logic backs
	 * {@code PdslInterpreter#bindProducerParameter}, which delegates here so that
	 * {@code producer([shape])} parameter binding and per-primitive scalar acceptance
	 * share one definition rather than diverging.
	 *
	 * <p>Conversion rules:</p>
	 * <ul>
	 *   <li>{@link Number} — folded into the kernel as a constant via {@code c(value)}.
	 *       Only valid when {@code expectedShape} has total size 1.</li>
	 *   <li>{@link PackedCollection} — wrapped via {@code cp(coll)} so the slot can be
	 *       mutated between renders. Shape must match {@code expectedShape}.</li>
	 *   <li>{@link Producer} — converted via {@code c(producer)}. The producer's
	 *       shape (resolved via {@code shape(producer)}) is validated against
	 *       {@code expectedShape}.</li>
	 * </ul>
	 *
	 * @param arg           the argument from the PDSL argument list
	 * @param expectedShape the required shape (typically {@code shape(1)})
	 * @param contextName   prefix used in error messages identifying the call site
	 * @return a {@link CollectionProducer} of the requested shape
	 * @throws PdslParseException if the argument is unsupported or has the wrong shape
	 */
	default CollectionProducer bindProducerArg(Object arg, TraversalPolicy expectedShape,
											   String contextName) {
		if (arg instanceof Number) {
			if (expectedShape.getTotalSize() != 1) {
				throw new PdslParseException(contextName + " expects shape "
						+ expectedShape + " but a Number literal can only be supplied for shape [1]");
			}
			return c(((Number) arg).doubleValue());
		}
		if (arg instanceof PackedCollection) {
			PackedCollection coll = (PackedCollection) arg;
			if (coll.getShape().getTotalSize() != expectedShape.getTotalSize()) {
				throw new PdslParseException(contextName + " expects shape "
						+ expectedShape + " but PackedCollection has shape " + coll.getShape());
			}
			return cp(coll);
		}
		if (arg instanceof Producer) {
			Producer<PackedCollection> producer = (Producer<PackedCollection>) arg;
			TraversalPolicy actual = shape(producer);
			if (actual.getTotalSize() != expectedShape.getTotalSize()) {
				throw new PdslParseException(contextName + " expects shape "
						+ expectedShape + " but Producer has shape " + actual);
			}
			return c(producer);
		}
		throw new PdslParseException(contextName + " expects Number, PackedCollection, or Producer; got "
				+ (arg == null ? "null" : arg.getClass().getSimpleName()));
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
				return scale(toDouble(arg));
			}
			CollectionProducer factor = bindProducerArg(arg, shape(1), "scale() factor");
			return (Function<TraversalPolicy, Block>)
					(shape -> layer("scale", shape, shape,
							input -> multiply(c(input).each(), factor)));
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
	 * <p>The {@code cutoff} argument may be supplied as either a numeric literal
	 * (folded into the FIR coefficient table at build time) or a {@link Producer}
	 * of a shape-{@code [1]} {@link PackedCollection}. When a producer is supplied
	 * the FIR coefficients become a producer expression that is re-evaluated each
	 * time the filter kernel runs — so a render-time mutable cutoff slot or a
	 * clock-driven cutoff envelope flows through the coefficients without the
	 * filter having to be rebuilt. {@code sampleRate} and {@code filterOrder}
	 * remain numeric: they participate in build-time shape arithmetic on the
	 * coefficient index array.</p>
	 *
	 * @param args three arguments: cutoff frequency (Hz) as either a {@link Number}
	 *             or a shape-{@code [1]} {@link Producer}, sample rate (numeric, Hz),
	 *             filter order (numeric)
	 * @return a factory that creates a low-pass FIR filter block for any input shape
	 */
	default Object callLowpass(List<Object> args) {
		if (args.size() == 3) {
			Producer<PackedCollection> cutoff = bindProducerArg(args.get(0), shape(1), "lowpass() cutoff");
			int sampleRate = toInt(args.get(1));
			int order = toInt(args.get(2));
			return firFilterBlock("lowpass",
					lowPassCoefficients(cutoff, sampleRate, order));
		}
		throw new PdslParseException(
				"lowpass() expects 3 arguments (cutoff, sampleRate, filterOrder), got " + args.size());
	}

	/**
	 * Builds a high-pass FIR filter block using spectral inversion of the Hamming-windowed sinc.
	 *
	 * <p>The {@code cutoff} argument may be supplied as either a numeric literal
	 * or a {@link Producer} of a shape-{@code [1]} {@link PackedCollection}; see
	 * {@link #callLowpass(List)} for the full discussion of how the producer form
	 * propagates through the coefficient table. {@code sampleRate} and
	 * {@code filterOrder} remain numeric.</p>
	 *
	 * @param args three arguments: cutoff frequency (Hz) as either a {@link Number}
	 *             or a shape-{@code [1]} {@link Producer}, sample rate (numeric, Hz),
	 *             filter order (numeric)
	 * @return a factory that creates a high-pass FIR filter block for any input shape
	 */
	default Object callHighpass(List<Object> args) {
		if (args.size() == 3) {
			Producer<PackedCollection> cutoff = bindProducerArg(args.get(0), shape(1), "highpass() cutoff");
			int sampleRate = toInt(args.get(1));
			int order = toInt(args.get(2));
			return firFilterBlock("highpass",
					highPassCoefficients(cutoff, sampleRate, order));
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
	 * <p>Each of the five coefficient arguments ({@code b0}, {@code b1}, {@code b2},
	 * {@code a1}, {@code a2}) may be supplied as either a numeric literal (folded
	 * into the compiled kernel as a constant) or a {@link Producer} of a
	 * shape-{@code [1]} {@link PackedCollection} (read inside the kernel via
	 * {@code subset(shape(1), producer, 0)}). The {@code history} argument is
	 * always a 4-element {@link PackedCollection} that the kernel writes back
	 * into between forward passes.</p>
	 *
	 * @param args six arguments: b0, b1, b2, a1, a2 (each {@link Number} or
	 *             shape-{@code [1]} {@link Producer}), history ({@link PackedCollection}, size 4)
	 * @return a factory that creates a stateful biquad filter block for any input shape
	 */
	default Object callBiquad(List<Object> args) {
		if (args.size() == 6) {
			CollectionProducer b0p = bindProducerArg(args.get(0), shape(1), "biquad() b0");
			CollectionProducer b1p = bindProducerArg(args.get(1), shape(1), "biquad() b1");
			CollectionProducer b2p = bindProducerArg(args.get(2), shape(1), "biquad() b2");
			CollectionProducer a1p = bindProducerArg(args.get(3), shape(1), "biquad() a1");
			CollectionProducer a2p = bindProducerArg(args.get(4), shape(1), "biquad() a2");
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
		throw new PdslParseException(
				"biquad() expects 6 arguments (b0, b1, b2, a1, a2, history), got " + args.size());
	}

	/**
	 * Builds a stateful integer-sample delay line block.
	 *
	 * <p>Reads delayed samples from the circular {@code buffer}, then writes the input
	 * into the buffer and advances {@code head} so state persists across successive calls.</p>
	 *
	 * <p>The {@code delaySamples} argument may be supplied as either a numeric
	 * literal (folded into the read-pointer arithmetic as a constant) or a
	 * {@link Producer} of a shape-{@code [1]} {@link PackedCollection} (read inside
	 * the kernel via {@code subset(shape(1), producer, 0)}). The producer form lets
	 * a render-time mutable delay-time slot or a clock-driven delay envelope flow
	 * into the read-position calculation. The buffer-size arithmetic still uses
	 * the {@code buffer} argument's shape for its bound, so the maximum delay is
	 * fixed by buffer allocation; a producer cannot exceed it.</p>
	 *
	 * @param args three arguments: delaySamples (either {@link Number} or
	 *             shape-{@code [1]} {@link Producer}), buffer ({@link PackedCollection}),
	 *             head ({@link PackedCollection}, size 1)
	 * @return a factory that creates a stateful delay-line block for any input shape
	 */
	default Object callDelay(List<Object> args) {
		if (args.size() == 3) {
			CollectionProducer delaySamplesP = bindProducerArg(args.get(0), shape(1),
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
		throw new PdslParseException(
				"delay() expects 3 arguments (delaySamples, buffer, head), got " + args.size());
	}

	/**
	 * Builds a stateful sinusoidal LFO block.
	 *
	 * <p>Produces sin values for the current phase window and advances {@code phase}
	 * by {@code n * phaseIncrement} (mod 2π) so subsequent calls continue seamlessly.</p>
	 *
	 * <p>The {@code freqHz} argument may be supplied as either a numeric literal
	 * (in which case {@code phaseIncrement = 2π * freqHz / sampleRate} is folded
	 * into the kernel as a constant) or a {@link Producer} of a shape-{@code [1]}
	 * {@link PackedCollection}. In the producer case {@code phaseIncrement}
	 * becomes a producer expression {@code subset(shape(1), freq, 0) * (2π / sampleRate)}
	 * that the kernel evaluates once per forward pass, so a frequency envelope
	 * driving the LFO updates the increment used both inside the kernel and in
	 * the post-pass phase update. {@code sampleRate} stays numeric: it is a
	 * fixed property of the audio pipeline, not a per-sample value.</p>
	 *
	 * @param args three arguments: freqHz (either {@link Number} or shape-{@code [1]}
	 *             {@link Producer}), sampleRate (numeric), phase ({@link PackedCollection}, size 1)
	 * @return a factory that creates a stateful LFO block for any input shape
	 */
	default Object callLfo(List<Object> args) {
		if (args.size() == 3) {
			double sampleRate = toDouble(args.get(1));
			PackedCollection phase = (PackedCollection) args.get(2);
			CollectionProducer freq = bindProducerArg(args.get(0), shape(1), "lfo() freqHz");
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
		throw new PdslParseException(
				"lfo() expects 3 arguments (freqHz, sampleRate, phase), got " + args.size());
	}

	/**
	 * Validates and delegates to {@link MultiChannelDspFeatures#routeBlock}.
	 *
	 * <p>The transmission matrix's first axis must match the upstream channel count
	 * ({@code inputChannels}); its second axis determines the number of output channels
	 * the routing block produces. A square matrix is the degenerate case
	 * ({@code inputChannels == outputChannels}); a rectangular matrix routes
	 * {@code N → M} channels.</p>
	 *
	 * @param args            one weight argument: the transmission matrix
	 *                        (shape {@code [inputChannels, outputChannels]})
	 * @param inputChannels   number of upstream channels (from PDSL environment)
	 * @param signalSize      samples per channel (from PDSL environment)
	 * @return the cross-channel routing {@link Block}
	 * @throws PdslParseException if the matrix is not 2D, has a zero axis, or its first
	 *                            axis does not match {@code inputChannels}
	 */
	default Block callRoute(List<Object> args, int inputChannels, int signalSize) {
		if (args.size() != 1 || !(args.get(0) instanceof PackedCollection)) {
			throw new PdslParseException(
					"route() expects 1 weight argument (transmission matrix), got " + args.size());
		}
		PackedCollection matrix = (PackedCollection) args.get(0);
		TraversalPolicy ms = matrix.getShape();
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
		return routeBlock(matrix, inputChannels, matOut, signalSize);
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
