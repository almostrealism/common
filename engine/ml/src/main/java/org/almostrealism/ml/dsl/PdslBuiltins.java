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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.Block;
import org.almostrealism.model.DefaultBlock;

import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toDouble;
import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toInt;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The PDSL language's BUILT-IN FUNCTION LIBRARY: the standard, domain-agnostic
 * layer constructors every PDSL program can call without registering a primitive
 * (dense, conv1d, conv_transpose1d, rmsnorm, softmax, the activations including snake, slice, lerp,
 * reshape, identity,
 * scale, repeat, repeat_each, sum_channels, capture, cache_write, cache_read, rope_rotation,
 * mra_rope_rotation, split_half_rope, merge_half_rope, attention_scores,
 * causal_mask, key_mask, weighted_values, scaled_dot_product, sqrt, attention,
 * shape, range, zeros, rope_freqs). {@link PdslInterpreter} evaluates a call's
 * arguments and routes the call here via {@link #call(String, List)}; domain
 * libraries (e.g. audio DSP) register additional primitives through
 * {@link PdslInterpreter#registerPrimitive} instead of extending this class.
 *
 * <p>Every method is stateless: built-ins consume already-evaluated argument
 * values and return a {@link Block} or a block factory
 * ({@code Function<TraversalPolicy, Block>}) for the interpreter to attach.</p>
 */
final class PdslBuiltins {

	/** Mixin instance providing the framework feature default methods. */
	private static final PdslFeatures FEATURES = PdslFeatures.INSTANCE;

	/** Built-ins are accessed only through {@link #call(String, List)}. */
	private PdslBuiltins() { }


	/**
	 * Resolves and executes a built-in function by name.
	 *
	 * @param name Name of the function
	 * @param args Evaluated arguments
	 * @return The result of the built-in, or {@code null} if the name is not a built-in
	 */
	static Object call(String name, List<Object> args) {
		switch (name) {
			case "dense": return callDense(args);
			case "conv1d": return callConv1d(args);
			case "conv_transpose1d": return callConvTranspose1d(args);
			case "rmsnorm": return callRmsnorm(args);
			case "softmax": return callSoftmax(args);
			case "silu": return callActivation("silu");
			case "relu": return callActivation("relu");
			case "gelu": return callActivation("gelu");
			case "sigmoid": return callActivation("sigmoid");
			case "tanh_act": return callActivation("tanh_act");
			case "snake": return callSnake(args);
			case "slice": return callSlice(args);
			case "lerp": return callLerp(args);
			case "reshape": return callReshape(args);
			case "identity": return callIdentity(args);
			case "scale": return callScale(args);
			case "repeat": return callRepeat(args);
			case "repeat_each": return callRepeatEach(args);
			case "sum_channels": return callSumChannels(args);
			case "capture": return callCapture(producerArg(args, 0, 1, "capture"));
			case "cache_write": return callCacheRow("cache_write", args, FEATURES::cacheWrite);
			case "cache_read": return callCacheRow("cache_read", args, FEATURES::cacheRead);
			case "rope_rotation": return callRopeRotation(args);
			case "mra_rope_rotation": return callMraRopeRotation(args);
			case "split_half_rope": return callSplitHalfRope(args);
			case "merge_half_rope": return callMergeHalfRope(args);
			case "attention_scores": return callAttentionScores(producerArg(args, 0, 1, "attention_scores"));
			case "causal_mask": return callCausalMask(args);
			case "key_mask": return callKeyMask(args);
			case "weighted_values": return callWeightedValues(producerArg(args, 0, 1, "weighted_values"));
			case "scaled_dot_product": return callScaledDotProduct(args);
			case "sqrt": return callSqrt(args);
			case "attention": return callAttention(args);
			case "shape": return callShape(args);
			case "range": return callRange(args);
			case "zeros": return callZeros(args);
			case "rope_freqs": return callRopeFreqs(args);
			default: return null;
		}
	}

	/**
	 * Normalizes the built-in argument at the given index to a
	 * {@link CollectionProducer}, enforcing the built-in's arity. Builtin
	 * implementations should accept producers rather than {@code List<Object>};
	 * this is the dispatch-side normalization that makes that possible.
	 *
	 * @param args     the raw evaluated arguments
	 * @param index    the argument index to normalize
	 * @param expected the number of arguments the built-in requires
	 * @param name     the built-in name, for error messages
	 * @return the normalized producer
	 * @throws PdslParseException if the arity does not match
	 */
	private static CollectionProducer producerArg(List<Object> args, int index,
												  int expected, String name) {
		if (args.size() != expected) {
			throw new PdslParseException(name + "() expects " + expected
					+ " argument" + (expected == 1 ? "" : "s")
					+ ", got " + args.size());
		}
		return PdslInterpreter.normalizeToProducer(args.get(index), null,
				name + "() argument " + index);
	}

	/**
	 * Builds a pass-through (identity) block factory.
	 *
	 * @param args must be empty
	 * @return a factory that creates a
	 *         {@link org.almostrealism.layers.LayerFeatures#passThrough(TraversalPolicy)
	 *         pass-through} block for any input shape
	 */
	private static Function<TraversalPolicy, Block> callIdentity(List<Object> args) {
		if (!args.isEmpty()) {
			throw new PdslParseException(
					"identity() expects no arguments, got " + args.size());
		}
		return FEATURES::passThrough;
	}

	/**
	 * Builds a scalar-scaling block factory that multiplies every element of the input
	 * by a factor. The factor argument is normalised to a shape-{@code [1]}
	 * producer so a numeric literal, a {@link PackedCollection}, or a
	 * {@link Producer} are all accepted uniformly.
	 *
	 * @param args one argument: the multiplicative factor
	 * @return a factory that creates the scale layer for any input shape
	 */
	private static Function<TraversalPolicy, Block> callScale(List<Object> args) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"scale() expects 1 argument (factor), got " + args.size());
		}

		if (args.get(0) instanceof PdslChannelBank) {
			// Vectorized for-each: one factor per channel, applied to every row of the
			// [channels, signalSize] input in a single computation.
			PdslChannelBank bank = (PdslChannelBank) args.get(0);
			int channels = bank.getChannels();
			CollectionProducer factors = PdslInterpreter.normalizeToProducer(bank.getSource(),
					FEATURES.shape(channels), "scale() factor bank");
			return (inputShape -> {
				long rowSize = inputShape.getTotalSizeLong() / channels;
				return FEATURES.layer("scale", inputShape, inputShape,
						input -> new DefaultTraversableExpressionComputation("scaleBank",
								inputShape,
								(Function<TraversableExpression[], CollectionExpression>)
										exprArgs -> DefaultCollectionExpression.create(
												inputShape, idx ->
														exprArgs[1].getValueAt(idx).multiply(
																exprArgs[2].getValueAt(
																		idx.divide(rowSize)))),
								(Producer) input, (Producer) factors));
			});
		}

		CollectionProducer factor = PdslInterpreter.normalizeToProducer(args.get(0),
				FEATURES.shape(1), "scale() factor");
		return (inputShape ->
				FEATURES.layer("scale", inputShape, inputShape,
						input -> FEATURES.multiply(FEATURES.c(input).each(), factor)));
	}

	/**
	 * Builds a block factory that replicates the input along axis 0.
	 *
	 * <p>Given a {@code [C, S]} input, the result has shape {@code [C * n, S]}.
	 * In the typical multi-channel use ({@code C == 1}) this turns a mono signal
	 * into an {@code n}-channel parallel fan-out. The kernel is a sequence of
	 * {@code n} concat operations on the input — equivalent to
	 * {@link org.almostrealism.collect.CollectionProducer#repeat(int, int)
	 * CollectionProducer.repeat(0, n)} but built explicitly so the resulting
	 * graph matches the existing per-channel layout.</p>
	 *
	 * @param args one argument: the integer repetition count {@code n}
	 * @return a factory that creates the repeat layer for any 2-D input shape
	 */
	private static Function<TraversalPolicy, Block> callRepeat(List<Object> args) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"repeat() expects 1 argument (n), got " + args.size());
		}
		int n = toInt(args.get(0));
		return (inputShape -> {
			if (inputShape.getDimensions() != 2) {
				throw new PdslParseException(
						"repeat() expects a 2-D [C, S] input shape, got " + inputShape);
			}
			int channels = inputShape.length(0);
			int signalSize = inputShape.length(1);
			TraversalPolicy singleShape = FEATURES.shape(channels, signalSize);
			TraversalPolicy outputShape = FEATURES.shape(channels * n, signalSize);
			return FEATURES.layer("repeat", inputShape, outputShape, input -> {
				CollectionProducer combined = FEATURES.c(input).reshape(singleShape);
				for (int i = 1; i < n; i++) {
					combined = (CollectionProducer)
							FEATURES.concat(combined, FEATURES.c(input).reshape(singleShape));
				}
				return combined;
			});
		});
	}

	/**
	 * Builds a block factory that collapses a {@code [C, S]} input to {@code [1, S]}
	 * by element-wise summation along axis 0.
	 *
	 * <p>This is the <em>within-tensor</em> channel-axis reduction: it operates on a
	 * single upstream block whose output already has shape {@code [C, S]} and reduces
	 * along axis 0 (the channel axis) to produce a {@code [1, S]} output. No new
	 * branches are introduced — the reduction axis is internal to the single source's
	 * output tensor.</p>
	 *
	 * <p>For summation <em>across multiple {@link org.almostrealism.model.Block} sources</em>
	 * — i.e. running N independent sub-blocks against the same input and summing their
	 * separate outputs — see
	 * {@link org.almostrealism.layers.LayerRoutingFeatures#accumBlocks(io.almostrealism.collect.TraversalPolicy, java.util.List, io.almostrealism.compute.ComputeRequirement...)
	 * accumBlocks}. The two operations both produce element-wise sums but along
	 * different conceptual axes (within a tensor vs. across sibling blocks) and are
	 * not substitutable.</p>
	 *
	 * @param args must be empty
	 * @return a factory that creates the sum-channels layer for any 2-D input shape
	 */
	private static Function<TraversalPolicy, Block> callSumChannels(List<Object> args) {
		if (!args.isEmpty()) {
			throw new PdslParseException(
					"sum_channels() expects no arguments, got " + args.size());
		}
		return (inputShape -> {
			if (inputShape.getDimensions() != 2) {
				throw new PdslParseException(
						"sum_channels() expects a 2-D [C, S] input shape, got " + inputShape);
			}
			int channels = inputShape.length(0);
			int signalSize = inputShape.length(1);
			TraversalPolicy singleShape = FEATURES.shape(1, signalSize);
			return FEATURES.layer("sum_channels", inputShape, singleShape, input -> {
				// subset() positions are per-dimension coordinates — (i, 0) selects row i.
				// A flat offset here resolved every term to row 0 (channels * row0).
				CollectionProducer sum = FEATURES.subset(singleShape, FEATURES.c(input), 0, 0);
				for (int i = 1; i < channels; i++) {
					sum = sum.add(FEATURES.subset(singleShape, FEATURES.c(input), i, 0));
				}
				return sum;
			});
		});
	}

	/**
	 * Builds a signal-capture block factory: the incoming signal is copied into the
	 * given slot collection at this stage's position in the ops order, then passed
	 * through unchanged. This is the language's observability primitive — a consumer
	 * outside the compiled model (e.g. a streaming runner exporting bus stems) reads
	 * the slot after each forward pass, and the capture never alters the graph it
	 * sits inside.
	 *
	 * @param slot the destination slot, whose total size must match the input shape
	 * @return a factory that creates the capture block for any input shape
	 */
	private static Function<TraversalPolicy, Block> callCapture(CollectionProducer slot) {
		TraversalPolicy slotShape = FEATURES.shape(slot);
		return (inputShape -> {
			if (slotShape.getTotalSize() != inputShape.getTotalSize()) {
				throw new PdslParseException("capture() slot size "
						+ slotShape.getTotalSize()
						+ " does not match the input shape " + inputShape);
			}
			Cell<PackedCollection> forward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) -> {
						OperationList ops = new OperationList("capture");
						ops.add(FEATURES.into("capture-" + slotShape.getTotalSize(),
								FEATURES.c(in).reshape(slotShape), slot, false));
						ops.add(next.push(in));
						return ops;
					});
			Cell<PackedCollection> backward = Cell.of(
					(BiFunction<Producer<PackedCollection>, Receptor<PackedCollection>,
							Supplier<Runnable>>) (in, next) ->
							new OperationList("capture-backward"));
			return new DefaultBlock(inputShape, inputShape, forward, backward);
		});
	}

	/**
	 * Builds a dense (fully-connected) layer block from weight and optional bias arguments.
	 *
	 * @param args Evaluated arguments: weight tensor, and optionally bias tensor
	 * @return A dense {@link Block}
	 */
	private static Function<TraversalPolicy, CellularLayer> callDense(List<Object> args) {
		if (args.size() == 1) {
			return FEATURES.dense((PackedCollection) args.get(0));
		} else if (args.size() == 2) {
			return FEATURES.dense(
					(PackedCollection) args.get(0),
					(PackedCollection) args.get(1));
		}
		throw new PdslParseException(
				"dense() expects 1 or 2 arguments, got " + args.size());
	}

	/**
	 * Builds a 1-D convolution block factory from a weight tensor, an optional bias, and the
	 * stride and zero-padding of the convolution. The output-channel count and kernel size are
	 * read from the weight's {@code [out_channels, in_channels, kernel]} shape, and the batch,
	 * input-channel count and sequence length are read from the stage's
	 * {@code [batch, in_channels, length]} input shape, so the call names only what the shapes
	 * cannot: how far the kernel steps and how much zero padding the input is given.
	 *
	 * <p>The weight is bound already in whatever form the model uses it — a weight-normalized
	 * codec, for example, resolves its {@code g} and {@code v} parameters into the effective
	 * weight when it binds the argument, exactly as it resolves any other stored weight, so this
	 * primitive is the plain convolution and carries no normalization decision.</p>
	 *
	 * @param args {@code (weight, bias, stride, padding)} or {@code (weight, stride, padding)}
	 *             when the convolution has no bias
	 * @return a factory that creates the convolution for a 3-D {@code [batch, channels, length]}
	 *         input shape
	 * @see org.almostrealism.layers.ConvolutionLayerFeatures#convolution1d
	 */
	private static Function<TraversalPolicy, Block> callConv1d(List<Object> args) {
		ConvolutionArguments conv = new ConvolutionArguments("conv1d", args,
				"[out_channels, in_channels, kernel]", "stride", "padding");
		int outChannels = conv.weightLength(0);
		int inChannels = conv.weightLength(1);
		int kernelSize = conv.weightLength(2);
		return inputShape -> {
			conv.checkInput(inputShape, inChannels);
			return FEATURES.convolution1d(inputShape.length(0), inChannels, outChannels,
					inputShape.length(2), kernelSize, conv.setting(0), conv.setting(1),
					conv.weights, conv.bias);
		};
	}

	/**
	 * Builds a 1-D transposed convolution block factory — the upsampling counterpart of
	 * {@code conv1d} — from a weight tensor, an optional bias, and the stride, padding and output
	 * padding of the convolution. Every input sample adds its value times the kernel into a
	 * {@code kernel}-long window of the output, the windows {@code stride} apart; {@code padding}
	 * samples are then trimmed from each end and {@code output_padding} samples added to the
	 * right, so the output is {@code (length - 1) * stride - 2 * padding + kernel + output_padding}
	 * long. The channel counts and kernel size are read from the weight's
	 * {@code [in_channels, out_channels, kernel]} shape (the layout of a PyTorch
	 * {@code ConvTranspose1d} weight) and the batch and length from the stage's
	 * {@code [batch, in_channels, length]} input shape, so the call names only what the shapes
	 * cannot.
	 *
	 * @param args {@code (weight, bias, stride, padding, output_padding)} or
	 *             {@code (weight, stride, padding, output_padding)} when the convolution has no bias
	 * @return a factory that creates the transposed convolution for a 3-D
	 *         {@code [batch, channels, length]} input shape
	 * @see org.almostrealism.layers.ConvolutionLayerFeatures#convTranspose1d
	 */
	private static Function<TraversalPolicy, Block> callConvTranspose1d(List<Object> args) {
		ConvolutionArguments conv = new ConvolutionArguments("conv_transpose1d", args,
				"[in_channels, out_channels, kernel]", "stride", "padding", "output_padding");
		int inChannels = conv.weightLength(0);
		int outChannels = conv.weightLength(1);
		int kernelSize = conv.weightLength(2);
		return inputShape -> {
			conv.checkInput(inputShape, inChannels);
			return FEATURES.convTranspose1d(inputShape.length(0), inChannels, outChannels,
					inputShape.length(2), kernelSize, conv.setting(0), conv.setting(1),
					conv.setting(2), conv.weights, conv.bias);
		};
	}

	/**
	 * The evaluated arguments of a 1-D convolution built-in: the three-axis weight, the bias,
	 * which a call may leave out, and the integer settings that follow them — the stride and
	 * padding, and for a transposed convolution its output padding. A call carries the bias
	 * exactly when it has one argument more than the weight and the settings alone.
	 */
	private static final class ConvolutionArguments {
		/** The built-in name, for error messages. */
		private final String name;

		/** The weight, whose three axes give the channel counts and the kernel size. */
		private final PackedCollection weights;

		/** The bias, or {@code null} for a convolution without one. */
		private final PackedCollection bias;

		/** The integer settings, in call order. */
		private final int[] settings;

		/**
		 * Reads a convolution call's arguments.
		 *
		 * @param name         the built-in name, for error messages
		 * @param args         the evaluated arguments
		 * @param weightLayout the weight's axes, for error messages
		 * @param settingNames the integer settings that follow the weight and the bias, in order
		 * @throws PdslParseException if the argument count fits neither form, or the weight does
		 *                            not have three axes
		 */
		private ConvolutionArguments(String name, List<Object> args, String weightLayout,
									 String... settingNames) {
			String settingList = String.join(", ", settingNames);
			if (args.size() != settingNames.length + 1 && args.size() != settingNames.length + 2) {
				throw new PdslParseException(name + "() expects (weight, bias, " + settingList
						+ ") or (weight, " + settingList + "), got " + args.size());
			}

			this.name = name;
			this.weights = (PackedCollection) args.get(0);
			this.bias = args.size() == settingNames.length + 2 ? (PackedCollection) args.get(1) : null;
			this.settings = new int[settingNames.length];
			int first = args.size() - settingNames.length;
			for (int i = 0; i < settings.length; i++) {
				settings[i] = toInt(args.get(first + i));
			}

			if (weights.getShape().getDimensions() != 3) {
				throw new PdslParseException(name + "() weight must be " + weightLayout
						+ ", got " + weights.getShape());
			}
		}

		/**
		 * Returns the weight's length along one axis.
		 *
		 * @param axis the axis
		 * @return the weight's length along {@code axis}
		 */
		private int weightLength(int axis) {
			return weights.getShape().length(axis);
		}

		/**
		 * Returns one of the integer settings.
		 *
		 * @param index the setting's position among the settings, in call order
		 * @return the setting
		 */
		private int setting(int index) {
			return settings[index];
		}

		/**
		 * Checks a stage's input shape: {@code [batch, channels, length]}, with as many channels as
		 * the weight takes.
		 *
		 * @param inputShape the stage's input shape
		 * @param channels   the number of input channels the weight takes
		 * @throws PdslParseException if the shape does not have three axes or its channel count differs
		 */
		private void checkInput(TraversalPolicy inputShape, int channels) {
			if (inputShape.getDimensions() != 3) {
				throw new PdslParseException(name + "() expects a [batch, channels, length] input "
						+ "shape, got " + inputShape);
			}
			if (inputShape.length(1) != channels) {
				throw new PdslParseException(name + "() weight expects " + channels
						+ " input channels but the input shape " + inputShape + " has " + inputShape.length(1));
			}
		}
	}

	/**
	 * Builds an RMSNorm layer from weight and epsilon arguments.
	 *
	 * @param args Evaluated arguments: weights tensor and epsilon value
	 * @return A shape-dependent {@link CellularLayer} factory
	 */
	private static Function<TraversalPolicy, CellularLayer> callRmsnorm(List<Object> args) {
		if (args.size() == 2) {
			PackedCollection weights = (PackedCollection) args.get(0);
			double epsilon = toDouble(args.get(1));
			return 
					(shape -> FEATURES.rmsnorm(shape, weights, epsilon));
		}
		throw new PdslParseException(
				"rmsnorm() expects 2 arguments (weights, epsilon), got " + args.size());
	}

	/**
	 * Builds a softmax activation block: one distribution per row of a {@code [rows, size]}
	 * input, computed over the last axis with the per-row maximum subtracted first so large
	 * logits (attention scores, vocabulary logits) do not overflow.
	 *
	 * @param args Must be empty
	 * @return A factory that creates the row-wise softmax layer for any input of at least two dimensions
	 */
	private static Function<TraversalPolicy, CellularLayer> callSoftmax(List<Object> args) {
		if (!args.isEmpty()) {
			throw new PdslParseException(
					"softmax() expects 0 arguments, got " + args.size());
		}
		return shape -> {
			if (shape.getDimensions() < 2) {
				throw new PdslParseException(
						"softmax() expects a [rows, size] input shape, got " + shape);
			}
			return FEATURES.softmax(shape, true);
		};
	}

	/**
	 * Builds a block factory that duplicates every row of a {@code [rows, size]} input
	 * {@code n} consecutive times, producing {@code [rows * n, size]}. With {@code n == 1}
	 * the stage is a pass-through and adds no computation.
	 *
	 * @param args one argument: the integer copy count {@code n}
	 * @return a factory that creates the duplication for any 2-D input shape
	 * @see org.almostrealism.layers.LayerFeatures#repeatEach
	 */
	private static Function<TraversalPolicy, Block> callRepeatEach(List<Object> args) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"repeat_each() expects 1 argument (n), got " + args.size());
		}
		int n = toInt(args.get(0));
		if (n == 1) {
			return inputShape -> {
				if (inputShape.getDimensions() != 2) {
					throw new PdslParseException(
							"repeat_each() expects a [rows, size] input shape, got " + inputShape);
				}
				return FEATURES.passThrough(inputShape);
			};
		}
		return inputShape -> FEATURES.repeatEach(inputShape, n);
	}

	/**
	 * Builds a block factory for one of the two row operations on a caller-owned
	 * {@code [rows, size]} cache, from their shared arguments: the cache, which is state declared
	 * in a {@code state} block and persists across forward passes, and the row position
	 * (shape {@code [1]}).
	 * <ul>
	 *   <li>{@code cache_write(cache, position)} writes the stage input into row
	 *       {@code position} and passes the input through unchanged, which is how a key/value
	 *       cache is filled one token at a time.</li>
	 *   <li>{@code cache_read(cache, position)} outputs row {@code position} as a
	 *       {@code [size]} vector without reading the stage input, which is how a recurrent
	 *       layer reads back the hidden state an earlier forward pass wrote.</li>
	 * </ul>
	 *
	 * @param name      the built-in name, for error messages
	 * @param args      two arguments: the cache collection and the row position
	 * @param operation the row operation to build for the stage's input shape
	 * @return a factory that creates the row operation for the stage's input shape
	 * @see org.almostrealism.layers.LayerFeatures#cacheWrite
	 * @see org.almostrealism.layers.LayerFeatures#cacheRead
	 */
	private static Function<TraversalPolicy, Block> callCacheRow(String name, List<Object> args,
																 CacheRowOperation operation) {
		if (args.size() != 2) {
			throw new PdslParseException(
					name + "() expects 2 arguments (cache, position), got " + args.size());
		}
		CollectionProducer cache = PdslInterpreter.normalizeToProducer(args.get(0), null,
				name + "() cache");
		CollectionProducer position = PdslInterpreter.normalizeToProducer(args.get(1),
				FEATURES.shape(1), name + "() position");
		return inputShape -> operation.create(inputShape, cache, position);
	}

	/** A block over one row of a caller-owned cache: a cache write or a cache read. */
	@FunctionalInterface
	private interface CacheRowOperation {
		/**
		 * Builds the block.
		 *
		 * @param inputShape the stage's input shape
		 * @param cache      the {@code [rows, size]} cache
		 * @param position   the row position, shape {@code [1]}
		 * @return the block operating on row {@code position} of {@code cache}
		 */
		Block create(TraversalPolicy inputShape, CollectionProducer cache, CollectionProducer position);
	}

	/**
	 * Builds the split-half rotary layout: {@code [1, heads * head_size]} becomes
	 * {@code [heads, head_size / 2, 2]} where element {@code i} of a head is paired with
	 * element {@code i + head_size / 2}, the pairing {@code rope_rotation} rotates.
	 *
	 * @param args two integer arguments: heads, head_size
	 * @return a factory that creates the permutation for a flat input of {@code heads * head_size}
	 */
	private static Function<TraversalPolicy, Block> callSplitHalfRope(List<Object> args) {
		if (args.size() != 2) {
			throw new PdslParseException(
					"split_half_rope() expects 2 arguments (heads, head_size), got " + args.size());
		}
		int heads = toInt(args.get(0));
		int headSize = toInt(args.get(1));
		return inputShape -> {
			if (inputShape.getTotalSize() != heads * headSize) {
				throw new PdslParseException("split_half_rope() input " + inputShape
						+ " does not hold " + heads + " heads of " + headSize);
			}
			return FEATURES.reshapeToSplitHalfRope(heads * headSize, heads, headSize);
		};
	}

	/**
	 * Builds the inverse of {@code split_half_rope}: {@code [heads, head_size / 2, 2]}
	 * back to the per-head layout {@code [heads, head_size]}.
	 *
	 * @param args two integer arguments: heads, head_size
	 * @return the merging permutation {@link Block}
	 */
	private static Block callMergeHalfRope(List<Object> args) {
		if (args.size() != 2) {
			throw new PdslParseException(
					"merge_half_rope() expects 2 arguments (heads, head_size), got " + args.size());
		}
		return FEATURES.reshapeFromSplitHalfRope(toInt(args.get(0)), toInt(args.get(1)));
	}

	/**
	 * Builds a rotary position embedding block with one frequency table and one position per
	 * head group (multidimensional relative attention). The groups partition the heads of the
	 * {@code [heads, head_size / 2, 2]} input in order.
	 *
	 * @param args two arguments: the input shape and the head groups
	 *             ({@link HeadGroupConfig HeadGroupConfig[]}, each naming its head count,
	 *             frequency table and position producer)
	 * @return the rotation {@link Block}
	 * @see org.almostrealism.ml.RotationFeatures#mraRopeRotation
	 */
	private static Block callMraRopeRotation(List<Object> args) {
		if (args.size() != 2 || !(args.get(0) instanceof TraversalPolicy)
				|| !(args.get(1) instanceof HeadGroupConfig[])) {
			throw new PdslParseException(
					"mra_rope_rotation() expects 2 arguments (shape, head_groups), got " + args);
		}
		TraversalPolicy shape = (TraversalPolicy) args.get(0);
		if (shape.getDimensions() != 3 || shape.length(2) != 2) {
			throw new PdslParseException(
					"mra_rope_rotation() expects a [heads, head_size / 2, 2] shape, got " + shape);
		}
		HeadGroupConfig[] groups = (HeadGroupConfig[]) args.get(1);
		int[] headsInGroup = new int[groups.length];
		for (int g = 0; g < groups.length; g++) {
			headsInGroup[g] = groups[g].headCount;
		}
		return FEATURES.mraRopeRotation(shape.length(0), shape.length(1) * 2, headsInGroup, groups);
	}

	/**
	 * Builds the attention-score stage: the {@code [heads, head_size]} input holds one query
	 * per head, and the result {@code [heads, seq_len]} is every query's unscaled dot product
	 * with each row of the {@code [seq_len, heads * head_size]} key cache.
	 *
	 * @param keys the key cache, one {@code head_size} slice per head in every row
	 * @return a factory that creates the score layer for the query shape
	 * @see org.almostrealism.ml.AttentionFeatures#attentionScores
	 */
	private static Function<TraversalPolicy, Block> callAttentionScores(CollectionProducer keys) {
		return inputShape -> FEATURES.attentionScores(inputShape, keys);
	}

	/**
	 * Builds the causal-mask stage: on a {@code [heads, seq_len]} score matrix, every column
	 * after {@code position} receives a penalty large enough that its softmax weight vanishes.
	 *
	 * @param args one argument: the current position (shape {@code [1]})
	 * @return a factory that creates the mask layer for the score shape
	 * @see org.almostrealism.ml.AttentionFeatures#causalMask
	 */
	private static Function<TraversalPolicy, Block> callCausalMask(List<Object> args) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"causal_mask() expects 1 argument (position), got " + args.size());
		}
		CollectionProducer position = PdslInterpreter.normalizeToProducer(args.get(0),
				FEATURES.shape(1), "causal_mask() position");
		return shape -> FEATURES.causalMask(shape, position);
	}

	/**
	 * Builds the weighted-sum stage: the {@code [heads, seq_len]} input holds one attention
	 * distribution per head, and the result {@code [1, heads * head_size]} is each head's
	 * weighted sum of its slice of the {@code [seq_len, heads * head_size]} value cache.
	 *
	 * @param values the value cache, one {@code head_size} slice per head in every row
	 * @return a factory that creates the weighted-sum layer for the weight shape
	 * @see org.almostrealism.ml.AttentionFeatures#weightedValues
	 */
	private static Function<TraversalPolicy, Block> callWeightedValues(CollectionProducer values) {
		return inputShape -> FEATURES.weightedValues(inputShape, values);
	}

	/**
	 * Builds the additive key-mask stage of parallel (full-sequence) attention: on a
	 * {@code [batch, heads, queries, keys]} score tensor, every key position marked invalid by the
	 * mask receives a large negative bias so its softmax weight underflows to zero, and every valid
	 * key is left unchanged. The mask is one validity value per key, {@code [batch, keys]}, with one
	 * for a key that may be attended and zero for one that may not; it is broadcast across the head
	 * and query axes. An all-ones mask leaves every score unchanged (adds zero), which is how a
	 * caller with no padding disables the stage.
	 *
	 * <p>This is the full-sequence counterpart of {@code causal_mask}, which masks by position for
	 * single-query autoregressive attention; here the masked positions are named by a data-driven
	 * validity mask instead.</p>
	 *
	 * <p>The mask's shape is checked against the score shape when the layer is built: it must be
	 * {@code [batch, keys]} with batch and key extents equal to the score shape's axes 0 and 3, so a
	 * mismatched mask is rejected rather than silently reshaped by the broadcast.</p>
	 *
	 * @param args one argument: the {@code [batch, keys]} validity mask (a bound tensor or a producer)
	 * @return a factory that creates the key-mask layer for a {@code [batch, heads, queries, keys]}
	 *         score shape
	 * @see org.almostrealism.ml.AttentionFeatures#MASKED_LOGIT_PENALTY
	 */
	private static Function<TraversalPolicy, Block> callKeyMask(List<Object> args) {
		if (args.size() != 1) {
			throw new PdslParseException(
					"key_mask() expects 1 argument (mask), got " + args.size());
		}
		CollectionProducer mask = PdslInterpreter.normalizeToProducer(args.get(0), null, "key_mask() mask");
		TraversalPolicy maskShape = FEATURES.shape(mask);
		// bias = (mask - 1) * penalty: zero where a key is valid (mask == 1), -penalty where masked.
		CollectionProducer bias = mask.add(-1.0).multiply(AttentionFeatures.MASKED_LOGIT_PENALTY);
		return scoresShape -> {
			if (scoresShape.getDimensions() != 4) {
				throw new PdslParseException(
						"key_mask() expects a [batch, heads, queries, keys] score shape, got " + scoresShape);
			}
			// Reject a mask the broadcast below would otherwise silently reshape.
			if (maskShape.getDimensions() != 2
					|| maskShape.length(0) != scoresShape.length(0)
					|| maskShape.length(1) != scoresShape.length(3)) {
				throw new PdslParseException("key_mask() expects a [batch, keys] mask matching the "
						+ "[batch, heads, queries, keys] score shape " + scoresShape + ", got " + maskShape);
			}
			return FEATURES.layer("keyMask", scoresShape, scoresShape,
					logits -> FEATURES.add(FEATURES.c(logits), FEATURES.broadcast(scoresShape, 3, bias)));
		};
	}

	/**
	 * Builds a batched matrix product of the stage's {@code [batch, heads, queries, dim]} input with
	 * a second {@code [batch, heads, keys, dim]} operand, the two multiplications parallel (full-
	 * sequence) attention is built from: the query-key scores {@code Q Kᵀ} (with {@code transpose}
	 * true, contracting the shared {@code dim} axis to give {@code [batch, heads, queries, keys]})
	 * and the context {@code A V} (with {@code transpose} false, the input being the attention
	 * weights {@code [batch, heads, queries, keys]} and the operand the values, contracting the key
	 * axis to give {@code [batch, heads, queries, dim]}).
	 *
	 * @param args two arguments: the second operand (a bound tensor or producer) and the boolean
	 *             {@code transpose}, true to transpose the operand's last two axes before the product
	 * @return a factory that creates the batched-product layer for a 4-D input shape
	 * @see org.almostrealism.algebra.MatrixFeatures#scaledDotProduct
	 */
	private static Function<TraversalPolicy, Block> callScaledDotProduct(List<Object> args) {
		if (args.size() != 2) {
			throw new PdslParseException(
					"scaled_dot_product() expects 2 arguments (other, transpose), got " + args.size());
		}
		if (!(args.get(1) instanceof Boolean)) {
			throw new PdslParseException("scaled_dot_product() transpose must be a boolean, got "
					+ (args.get(1) == null ? "null" : args.get(1).getClass().getSimpleName()));
		}
		CollectionProducer other =
				PdslInterpreter.normalizeToProducer(args.get(0), null, "scaled_dot_product() other");
		boolean transpose = (Boolean) args.get(1);
		TraversalPolicy otherShape = FEATURES.shape(other);
		if (otherShape.getDimensions() != 4) {
			throw new PdslParseException(
					"scaled_dot_product() other must be [batch, heads, seq, dim], got " + otherShape);
		}
		int cols = transpose ? otherShape.length(2) : otherShape.length(3);
		// The input's last axis is the extent contracted against other: dim for a Q Kᵀ product
		// (transpose true) and keys for an A V product (transpose false).
		String expectedInput = transpose
				? "[batch, heads, queries, dim]"
				: "[batch, heads, queries, keys]";
		return inputShape -> {
			if (inputShape.getDimensions() != 4) {
				throw new PdslParseException("scaled_dot_product() expects a " + expectedInput
						+ " input shape, got " + inputShape);
			}
			// Batch/head axes and the contracted axis must agree rather than being reinterpreted.
			int contracted = transpose ? otherShape.length(3) : otherShape.length(2);
			if (inputShape.length(0) != otherShape.length(0)
					|| inputShape.length(1) != otherShape.length(1)
					|| inputShape.length(3) != contracted) {
				throw new PdslParseException("scaled_dot_product() input " + inputShape
						+ " is incompatible with other " + otherShape + " for transpose=" + transpose);
			}
			TraversalPolicy outputShape = FEATURES.shape(inputShape.length(0), inputShape.length(1),
					inputShape.length(2), cols);
			return FEATURES.layer("scaledDotProduct", inputShape, outputShape,
					input -> FEATURES.scaledDotProduct(FEATURES.c(input), other, transpose));
		};
	}

	/**
	 * Evaluates a square root in configuration arithmetic, for expressions such as
	 * {@code scale(1 / sqrt(head_size))}.
	 *
	 * @param args one numeric argument
	 * @return the square root as a {@link Double}
	 */
	private static Double callSqrt(List<Object> args) {
		if (args.size() != 1) {
			throw new PdslParseException("sqrt() expects 1 argument, got " + args.size());
		}
		return Math.sqrt(toDouble(args.get(0)));
	}

	/**
	 * Builds an activation block for the given activation type name.
	 *
	 * @param type One of {@code "silu"}, {@code "relu"}, or {@code "gelu"}
	 * @return The corresponding activation {@link Block}
	 */
	private static Function<TraversalPolicy, CellularLayer> callActivation(String type) {
		switch (type) {
			case "silu": return FEATURES.silu();
			case "relu": return FEATURES.relu();
			case "gelu": return FEATURES.gelu();
			case "sigmoid": return FEATURES.sigmoid();
			case "tanh_act": return FEATURES.tanh();
			default:
				throw new PdslParseException("Unknown activation: " + type);
		}
	}

	/**
	 * Builds a learnable Snake activation block factory with per-channel parameters:
	 * {@code f(x) = x + (1 / beta) * sin^2(alpha * x)}, applied element-wise with each channel's
	 * own {@code alpha} and {@code beta}. Snake is the periodic activation the neural audio codecs
	 * (Descript, Stable Audio) use in place of a rectifier, so its harmonics track the signal's
	 * pitch. {@code alpha} and {@code beta} are {@code [channels]} vectors indexed by axis 1 of the
	 * {@code [batch, channels, length]} input.
	 *
	 * @param args two arguments: the per-channel {@code alpha} and {@code beta} tensors
	 * @return a factory that creates the Snake activation for any {@code [batch, channels, length]}
	 *         input shape
	 * @see org.almostrealism.layers.ActivationFeatures#snake(PackedCollection, PackedCollection,
	 *      io.almostrealism.compute.ComputeRequirement...)
	 */
	private static Function<TraversalPolicy, CellularLayer> callSnake(List<Object> args) {
		if (args.size() != 2) {
			throw new PdslParseException(
					"snake() expects 2 arguments (alpha, beta), got " + args.size());
		}
		return FEATURES.snake((PackedCollection) args.get(0), (PackedCollection) args.get(1));
	}

	/**
	 * Builds a subset (slice) block from offset and size arguments.
	 *
	 * @param args two integer arguments: offset, size
	 * @return a factory that creates a slice block for any input shape
	 */
	private static Function<TraversalPolicy, Block> callSlice(List<Object> args) {
		if (args.size() == 2) {
			int offset = toInt(args.get(0));
			int size = toInt(args.get(1));
			return 
					(inputShape -> FEATURES.subset(inputShape, FEATURES.shape(size), offset));
		}
		throw new PdslParseException(
				"slice() expects 2 arguments (offset, size), got " + args.size());
	}

	/**
	 * Builds a lerp (linear interpolation) layer from a hidden-size argument.
	 *
	 * @param args one integer argument: hidden_size
	 * @return a factory that creates the lerp layer for any (3 * hidden_size) input shape
	 */
	private static Function<TraversalPolicy, Block> callLerp(List<Object> args) {
		if (args.size() == 1) {
			int hiddenSize = toInt(args.get(0));
			return 
					(inputShape -> FEATURES.lerpLayer(inputShape, hiddenSize));
		}
		throw new PdslParseException(
				"lerp() expects 1 argument (hidden_size), got " + args.size());
	}

	/**
	 * Builds a reshape block from one or two shape arguments.
	 *
	 * @param args Shape arguments: output shape only, or input shape then output shape
	 * @return A reshape {@link Block}, or a {@link Function} factory of one when only the
	 *         output shape is given and the input shape is supplied later
	 */
	// Returns Object because the two forms genuinely differ: the one-argument form defers to a
	// Function<TraversalPolicy, Block> (the input shape is supplied later) while the two-argument
	// form already has both shapes and returns a Block directly. Both are valid dispatch results.
	private static Object callReshape(List<Object> args) {
		if (args.size() == 1 && args.get(0) instanceof TraversalPolicy) {
			TraversalPolicy outputShape = (TraversalPolicy) args.get(0);
			return (Function<TraversalPolicy, Block>)
					(inputShape -> FEATURES.reshape(inputShape, outputShape));
		} else if (args.size() == 2
				&& args.get(0) instanceof TraversalPolicy
				&& args.get(1) instanceof TraversalPolicy) {
			TraversalPolicy inputShape = (TraversalPolicy) args.get(0);
			TraversalPolicy outputShape = (TraversalPolicy) args.get(1);
			return FEATURES.reshape(inputShape, outputShape);
		}
		throw new PdslParseException(
				"reshape() expects 1 or 2 shape arguments, got " + args.size());
	}

	/**
	 * Builds a RoPE rotary position embedding block.
	 *
	 * @param args Evaluated arguments: shape, frequency tensor, and position producer
	 * @return A RoPE rotation {@link Block}
	 */
	private static Block callRopeRotation(List<Object> args) {
		if (args.size() == 3) {
			TraversalPolicy shape = (TraversalPolicy) args.get(0);
			CollectionProducer freqCis = toCollectionProducer(args.get(1));
			Producer<PackedCollection> position = toProducer(args.get(2));
			return FEATURES.ropeRotation(shape, freqCis, position);
		}
		throw new PdslParseException(
				"rope_rotation() expects 3 arguments (shape, freq_cis, position), got "
						+ args.size());
	}

	/**
	 * Builds an attention block from 8, 14, or 15 evaluated arguments.
	 *
	 * <p>The structure is not assembled here: {@link org.almostrealism.ml.AttentionFeatures#attention}
	 * allocates the key/value caches and builds the {@code attention} or
	 * {@code attention_qk_norm} layer of {@code /pdsl/attention.pdsl}, where the stages are
	 * written out. This built-in remains for assets that take the attention block as one
	 * stage (the asset's own caches cannot be declared from their parameter lists).</p>
	 *
	 * @param args Evaluated arguments matching one of the supported
	 *             {@link org.almostrealism.ml.AttentionFeatures#attention} overloads
	 * @return An attention {@link Block}
	 */
	private static Block callAttention(List<Object> args) {
		if (args.size() == 8) {
			// attention(heads, rms_weight, wk, wv, wq, wo, freq_cis, position)
			return FEATURES.attention(
					toInt(args.get(0)),
					(PackedCollection) args.get(1),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					(PackedCollection) args.get(4),
					(PackedCollection) args.get(5),
					toCollectionProducer(args.get(6)),
					toProducer(args.get(7)));
		} else if (args.size() == 14) {
			// attention(heads, kv_heads, rms_weight, wk, wv, wq, wo,
			//           bk, bv, bq, qk_norm_q, qk_norm_k, freq_cis, position)
			return FEATURES.attention(
					toInt(args.get(0)),
					toInt(args.get(1)),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					(PackedCollection) args.get(4),
					(PackedCollection) args.get(5),
					(PackedCollection) args.get(6),
					(PackedCollection) args.get(7),
					(PackedCollection) args.get(8),
					(PackedCollection) args.get(9),
					(PackedCollection) args.get(10),
					(PackedCollection) args.get(11),
					toCollectionProducer(args.get(12)),
					toProducer(args.get(13)));
		} else if (args.size() == 15) {
			// attention(heads, kv_heads, rms_weight, wk, wv, wq, wo,
			//           bk, bv, bq, qk_norm_q, qk_norm_k, freq_cis, position, epsilon)
			return FEATURES.attention(
					toInt(args.get(0)),
					toInt(args.get(1)),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					(PackedCollection) args.get(4),
					(PackedCollection) args.get(5),
					(PackedCollection) args.get(6),
					(PackedCollection) args.get(7),
					(PackedCollection) args.get(8),
					(PackedCollection) args.get(9),
					(PackedCollection) args.get(10),
					(PackedCollection) args.get(11),
					toCollectionProducer(args.get(12)),
					toProducer(args.get(13)),
					toDouble(args.get(14)));
		}
		throw new PdslParseException(
				"attention() expects 8, 14, or 15 arguments, got " + args.size());
	}

	/**
	 * Constructs a {@link TraversalPolicy} from a variable number of integer dimension arguments.
	 *
	 * @param args Evaluated integer dimension values
	 * @return The corresponding traversal policy
	 */
	private static TraversalPolicy callShape(List<Object> args) {
		int[] dims = new int[args.size()];
		for (int i = 0; i < args.size(); i++) {
			dims[i] = toInt(args.get(i));
		}
		return FEATURES.shape(dims);
	}

	/**
	 * Creates a zero-copy sub-view of a {@link PackedCollection} using
	 * {@link PackedCollection#range(TraversalPolicy, int)}.
	 *
	 * @param args [source: PackedCollection, shape: TraversalPolicy, offset: int]
	 * @return a zero-copy {@link PackedCollection} view of the requested sub-region
	 */
	private static PackedCollection callRange(List<Object> args) {
		if (args.size() == 3) {
			PackedCollection source = (PackedCollection) args.get(0);
			TraversalPolicy shape = (TraversalPolicy) args.get(1);
			int offset = toInt(args.get(2));
			return source.range(shape, offset);
		}
		throw new PdslParseException(
				"range() expects 3 arguments (source, shape, offset), got " + args.size());
	}

	/**
	 * Allocates a new collection of the given shape with every element zero: the storage a
	 * stateful layer writes on one forward pass and reads on later ones, such as the key and
	 * value caches of autoregressive attention. Each call allocates a distinct collection, so a
	 * model that calls {@code zeros} once per layer gives every layer its own state.
	 *
	 * @param args [shape: TraversalPolicy]
	 * @return the zero-filled collection
	 */
	private static PackedCollection callZeros(List<Object> args) {
		if (args.size() != 1 || !(args.get(0) instanceof TraversalPolicy)) {
			throw new PdslParseException("zeros() expects one shape argument, such as zeros([rows, size])");
		}
		PackedCollection storage = new PackedCollection((TraversalPolicy) args.get(0));
		storage.clear();
		return storage;
	}

	/**
	 * Builds the rotary position embedding table: for each position up to {@code seq_len}, the
	 * rotation angle of each of the {@code head_size / 2} frequency pairs, as
	 * {@link RotationFeatures#computeRopeFreqs} defines it. The result is
	 * the {@code freq_cis} table that {@code rope_rotation} reads.
	 *
	 * @param args [theta: number, head_size: int, seq_len: int]
	 * @return the frequency table producer, shape {@code [seq_len, head_size / 2, 2]}
	 */
	private static CollectionProducer callRopeFreqs(List<Object> args) {
		if (args.size() != 3) {
			throw new PdslParseException(
					"rope_freqs() expects 3 arguments (theta, head_size, seq_len), got " + args.size());
		}
		return RotationFeatures.computeRopeFreqs(
				toDouble(args.get(0)), toInt(args.get(1)), toInt(args.get(2)));
	}

	// ---- Type conversion helpers ----


	/**
	 * Converts an object to a {@link Producer} of {@link PackedCollection}, wrapping
	 * a raw {@link PackedCollection} with {@code p()} if needed.
	 *
	 * @param value PackedCollection or already-wrapped Producer
	 * @return The producer
	 */
	private static Producer<PackedCollection> toProducer(Object value) {
		if (value instanceof PackedCollection) {
			return FEATURES.p((PackedCollection) value);
		}
		if (value instanceof Producer) {
			return (Producer) value;
		}
		throw new PdslParseException("Expected PackedCollection or Producer but got " + value);
	}

	/**
	 * Converts a value to a {@link CollectionProducer}, wrapping a raw
	 * {@link PackedCollection} with {@code cp()} if needed.
	 *
	 * @param value PackedCollection or already-wrapped CollectionProducer
	 * @return a CollectionProducer wrapping the value
	 */
	private static CollectionProducer toCollectionProducer(Object value) {
		if (value instanceof PackedCollection) {
			return FEATURES.cp((PackedCollection) value);
		}
		if (value instanceof CollectionProducer) {
			return (CollectionProducer) value;
		}
		throw new PdslParseException("Expected PackedCollection or CollectionProducer but got " + value);
	}

}
