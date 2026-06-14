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
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.Block;

import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toDouble;
import static org.almostrealism.ml.dsl.PdslPrimitiveContext.toInt;

import java.util.List;
import java.util.function.Function;

/**
 * The PDSL language's BUILT-IN FUNCTION LIBRARY: the standard, domain-agnostic
 * layer constructors every PDSL program can call without registering a primitive
 * (dense, rmsnorm, softmax, the activations, slice, lerp, reshape, identity,
 * scale, repeat, sum_channels, rope_rotation, attention, transformer,
 * feed_forward, shape, range). {@link PdslInterpreter} evaluates a call's
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
			case "rmsnorm": return callRmsnorm(args);
			case "softmax": return callSoftmax(args);
			case "silu": return callActivation("silu");
			case "relu": return callActivation("relu");
			case "gelu": return callActivation("gelu");
			case "sigmoid": return callActivation("sigmoid");
			case "tanh_act": return callActivation("tanh_act");
			case "slice": return callSlice(args);
			case "lerp": return callLerp(args);
			case "reshape": return callReshape(args);
			case "identity": return callIdentity(args);
			case "scale": return callScale(args);
			case "repeat": return callRepeat(args);
			case "sum_channels": return callSumChannels(args);
			case "rope_rotation": return callRopeRotation(args);
			case "attention": return callAttention(args);
			case "transformer": return callTransformer(args);
			case "feed_forward": return callFeedForward(args);
			case "shape": return callShape(args);
			case "range": return callRange(args);
			default: return null;
		}
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
	 * Builds a softmax activation block.
	 *
	 * @param args Must be empty
	 * @return A softmax {@link Block}
	 */
	private static Function<TraversalPolicy, CellularLayer> callSoftmax(List<Object> args) {
		if (args.isEmpty()) {
			return FEATURES.softmax();
		}
		throw new PdslParseException(
				"softmax() expects 0 arguments, got " + args.size());
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
	 * Builds a full transformer block from evaluated arguments.
	 *
	 * @param args Evaluated arguments matching the
	 *             {@link org.almostrealism.ml.AttentionFeatures#transformer} signature
	 * @return A transformer {@link Block}
	 */
	private static Block callTransformer(List<Object> args) {
		if (args.size() == 19) {
			return FEATURES.transformer(
					toInt(args.get(0)),       // heads
					toInt(args.get(1)),       // kv_heads
					(PackedCollection) args.get(2),  // rms_att_weight
					(PackedCollection) args.get(3),  // wk
					(PackedCollection) args.get(4),  // wv
					(PackedCollection) args.get(5),  // wq
					(PackedCollection) args.get(6),  // wo
					(PackedCollection) args.get(7),  // bk
					(PackedCollection) args.get(8),  // bv
					(PackedCollection) args.get(9),  // bq
					(PackedCollection) args.get(10), // qk_norm_q
					(PackedCollection) args.get(11), // qk_norm_k
					toCollectionProducer(args.get(12)), // freq_cis
					(PackedCollection) args.get(13), // rms_ffn_weight
					(PackedCollection) args.get(14), // w1
					(PackedCollection) args.get(15), // w2
					(PackedCollection) args.get(16), // w3
					toProducer(args.get(17)),         // position
					toDouble(args.get(18)));          // epsilon
		}
		throw new PdslParseException(
				"transformer() expects 19 arguments, got " + args.size());
	}

	/**
	 * Builds a feed-forward (MLP) block from 4 or 5 evaluated weight arguments.
	 *
	 * @param args Evaluated arguments: RMSNorm weight, w1, w2, w3, and optionally epsilon
	 * @return A feed-forward {@link Block}
	 */
	private static Block callFeedForward(List<Object> args) {
		if (args.size() == 4) {
			// feed_forward(rms, w1, w2, w3)
			return FEATURES.feedForward(
					(PackedCollection) args.get(0),
					(PackedCollection) args.get(1),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3));
		} else if (args.size() == 5) {
			// feed_forward(rms, w1, w2, w3, epsilon)
			return FEATURES.feedForward(
					(PackedCollection) args.get(0),
					(PackedCollection) args.get(1),
					(PackedCollection) args.get(2),
					(PackedCollection) args.get(3),
					toDouble(args.get(4)));
		}
		throw new PdslParseException(
				"feed_forward() expects 4 or 5 arguments, got " + args.size());
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
