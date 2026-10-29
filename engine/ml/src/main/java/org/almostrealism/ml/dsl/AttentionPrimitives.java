/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslExtension;
import org.almostrealism.dsl.PdslInterpreter;
import org.almostrealism.dsl.PdslParseException;
import org.almostrealism.dsl.PdslPrimitiveContext;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.Block;

import java.util.List;
import java.util.function.Function;

import static org.almostrealism.dsl.PdslPrimitiveContext.toDouble;
import static org.almostrealism.dsl.PdslPrimitiveContext.toInt;

/**
 * The attention and rotary position primitives of PDSL, contributed by the machine-learning engine
 * to every program that can see it.
 *
 * <p>The language core in {@code ar-graph} holds only domain-agnostic built-ins. These primitives
 * build the stages of attention ({@code attention_scores}, {@code causal_mask},
 * {@code weighted_values}, {@code sequence_causal_mask}, {@code key_mask}) and of rotary position
 * embedding ({@code rope_rotation}, {@code sequence_rope}, {@code mra_rope_rotation},
 * {@code split_half_rope}, {@code merge_half_rope}, {@code rope_freqs}), lowering each to the
 * {@link AttentionFeatures} and {@link RotationFeatures} method of the same purpose. This class is
 * a {@link PdslExtension} named in {@code META-INF/services}, so every {@link PdslInterpreter}
 * registers these primitives whenever this module is on the classpath.</p>
 *
 * <p>Every shaped argument is normalized through {@link PdslPrimitiveContext#toProducer}, so the
 * primitives accept a number, a collection or a producer uniformly.</p>
 */
public class AttentionPrimitives implements PdslExtension, AttentionFeatures {

	/** Creates the extension; {@link java.util.ServiceLoader} requires a public constructor. */
	public AttentionPrimitives() { }

	@Override
	public void registerWith(PdslInterpreter interpreter) {
		interpreter.registerPrimitive("attention_scores", this::attentionScores);
		interpreter.registerPrimitive("causal_mask", this::causalMask);
		interpreter.registerPrimitive("weighted_values", this::weightedValues);
		interpreter.registerPrimitive("sequence_causal_mask", this::sequenceCausalMask);
		interpreter.registerPrimitive("key_mask", this::keyMask);
		interpreter.registerPrimitive("rope_rotation", this::ropeRotation);
		interpreter.registerPrimitive("sequence_rope", this::sequenceRope);
		interpreter.registerPrimitive("mra_rope_rotation", this::mraRopeRotation);
		interpreter.registerPrimitive("split_half_rope", this::splitHalfRope);
		interpreter.registerPrimitive("merge_half_rope", this::mergeHalfRope);
		interpreter.registerPrimitive("rope_freqs", this::ropeFreqs);
	}

	/**
	 * Requires a primitive to have been given exactly {@code expected} arguments.
	 *
	 * @param name     the primitive name, for the error message
	 * @param args     the arguments given
	 * @param expected the number of arguments the primitive takes
	 * @param names    the argument names, for the error message
	 * @throws PdslParseException if the count differs
	 */
	private static void requireArguments(String name, List<Object> args, int expected, String names) {
		if (args.size() != expected) {
			throw new PdslParseException(name + "() expects " + expected + " argument"
					+ (expected == 1 ? "" : "s") + (names.isEmpty() ? "" : " (" + names + ")")
					+ ", got " + args.size());
		}
	}

	/**
	 * {@code attention_scores(keys)}: the {@code [heads, head_size]} input holds one query per head,
	 * and the result {@code [heads, seq_len]} is every query's unscaled dot product with each row of
	 * the {@code [seq_len, heads * head_size]} key cache.
	 *
	 * @param args one argument: the key cache, one {@code head_size} slice per head in every row
	 * @param ctx  the call context
	 * @return a factory that creates the score layer for the query shape
	 * @see AttentionFeatures#attentionScores
	 */
	private Function<TraversalPolicy, Block> attentionScores(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("attention_scores", args, 1, "");
		CollectionProducer keys = ctx.toProducer(args.get(0), null, "attention_scores() argument 0");
		return inputShape -> attentionScores(inputShape, keys);
	}

	/**
	 * {@code causal_mask(position)}: on a {@code [heads, seq_len]} score matrix, every column after
	 * {@code position} receives a penalty large enough that its softmax weight vanishes.
	 *
	 * @param args one argument: the current position (shape {@code [1]})
	 * @param ctx  the call context
	 * @return a factory that creates the mask layer for the score shape
	 * @see AttentionFeatures#causalMask
	 */
	private Function<TraversalPolicy, Block> causalMask(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("causal_mask", args, 1, "position");
		CollectionProducer position = ctx.toProducer(args.get(0), shape(1), "causal_mask() position");
		return scores -> causalMask(scores, position);
	}

	/**
	 * {@code weighted_values(values)}: the {@code [heads, seq_len]} input holds one attention
	 * distribution per head, and the result {@code [1, heads * head_size]} is each head's weighted
	 * sum of its slice of the {@code [seq_len, heads * head_size]} value cache.
	 *
	 * @param args one argument: the value cache, one {@code head_size} slice per head in every row
	 * @param ctx  the call context
	 * @return a factory that creates the weighted-sum layer for the weight shape
	 * @see AttentionFeatures#weightedValues
	 */
	private Function<TraversalPolicy, Block> weightedValues(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("weighted_values", args, 1, "");
		CollectionProducer values = ctx.toProducer(args.get(0), null, "weighted_values() argument 0");
		return inputShape -> weightedValues(inputShape, values);
	}

	/**
	 * {@code sequence_causal_mask()}: the causal mask of parallel (full-sequence) attention. On a
	 * {@code [batch, heads, queries, keys]} score tensor, every key after the query's own position
	 * (key {@code j > i} for query {@code i}) receives a large negative bias so its softmax weight
	 * underflows to zero, and every other score is left unchanged, so each position attends only to
	 * itself and the positions before it. It is the full-sequence counterpart of
	 * {@code causal_mask(position)}, which masks the keys after the single query of one
	 * autoregressive step, as {@code sequence_rope} is of {@code rope_rotation}.
	 *
	 * @param args must be empty
	 * @param ctx  the call context
	 * @return a factory that creates the mask layer for a {@code [batch, heads, queries, keys]}
	 *         score shape
	 * @see org.almostrealism.ml.SequenceAttentionFeatures#causalLogitMask
	 */
	private Function<TraversalPolicy, Block> sequenceCausalMask(List<Object> args, PdslPrimitiveContext ctx) {
		if (!args.isEmpty()) {
			throw new PdslParseException(
					"sequence_causal_mask() expects no arguments, got " + args.size());
		}
		return scoresShape -> {
			if (scoresShape.getDimensions() != 4) {
				throw new PdslParseException("sequence_causal_mask() expects a [batch, heads, queries, keys]"
						+ " score shape, got " + scoresShape);
			}
			return layer("causalMask", scoresShape, scoresShape,
					logits -> add(c(logits), causalLogitMask(scoresShape)));
		};
	}

	/**
	 * {@code key_mask(mask)}: the additive key mask of parallel (full-sequence) attention. On a
	 * {@code [batch, heads, queries, keys]} score tensor, every key position marked invalid by the
	 * mask receives a large negative bias, {@code (mask - 1) * MASKED_LOGIT_PENALTY}, so its softmax
	 * weight underflows to zero, and every valid key is left unchanged. The mask is one validity
	 * value per key, {@code [batch, keys]}, with one for a key that may be attended and zero for one
	 * that may not; it is broadcast across the head and query axes. An all-ones mask leaves every
	 * score unchanged, which is how a caller with no padding disables the stage.
	 *
	 * <p>The mask's shape is checked against the score shape when the layer is built: it must be
	 * {@code [batch, keys]} with batch and key extents equal to the score shape's axes 0 and 3, so a
	 * mismatched mask is rejected rather than silently reshaped by the broadcast.</p>
	 *
	 * @param args one argument: the {@code [batch, keys]} validity mask (a bound tensor or a producer)
	 * @param ctx  the call context
	 * @return a factory that creates the key-mask layer for a {@code [batch, heads, queries, keys]}
	 *         score shape
	 * @see AttentionFeatures#MASKED_LOGIT_PENALTY
	 */
	private Function<TraversalPolicy, Block> keyMask(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("key_mask", args, 1, "mask");
		CollectionProducer mask = ctx.toProducer(args.get(0), null, "key_mask() mask");
		TraversalPolicy maskShape = shape(mask);
		CollectionProducer bias = mask.add(-1.0).multiply(MASKED_LOGIT_PENALTY);
		return scoresShape -> {
			if (scoresShape.getDimensions() != 4) {
				throw new PdslParseException(
						"key_mask() expects a [batch, heads, queries, keys] score shape, got " + scoresShape);
			}
			if (maskShape.getDimensions() != 2
					|| maskShape.length(0) != scoresShape.length(0)
					|| maskShape.length(1) != scoresShape.length(3)) {
				throw new PdslParseException("key_mask() expects a [batch, keys] mask matching the "
						+ "[batch, heads, queries, keys] score shape " + scoresShape + ", got " + maskShape);
			}
			return layer("keyMask", scoresShape, scoresShape,
					logits -> add(c(logits), broadcast(scoresShape, 3, bias)));
		};
	}

	/**
	 * {@code rope_rotation(shape, freq_cis, position)}: the rotary position embedding of the single
	 * token at {@code position}.
	 *
	 * @param args the {@code [heads, head_size / 2, 2]} shape, the frequency table and the position
	 * @param ctx  the call context
	 * @return the rotation {@link Block}
	 * @see RotationFeatures#ropeRotation
	 */
	private Block ropeRotation(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("rope_rotation", args, 3, "shape, freq_cis, position");
		TraversalPolicy shape = (TraversalPolicy) args.get(0);
		CollectionProducer freqCis = ctx.toProducer(args.get(1), null, "rope_rotation() freq_cis");
		CollectionProducer position = ctx.toProducer(args.get(2), null, "rope_rotation() position");
		return ropeRotation(shape, freqCis, position);
	}

	/**
	 * {@code sequence_rope(inv_freq)}: the rotary position embedding of a whole sequence. On a
	 * {@code [batch, heads, seq_len, head_dim]} input, the row at position {@code p} has its leading
	 * {@code 2 * n} features ({@code n} being the number of inverse frequencies) rotated pairwise,
	 * feature {@code i} with feature {@code i + n}, by the angles {@code p * inv_freq}; the features
	 * beyond those pass through unchanged. It is the full-sequence counterpart of
	 * {@code rope_rotation}: every position of the sequence is rotated by its own index in the same
	 * pass.
	 *
	 * <p>The rotation is defined by the extents of its input alone, so the layer is built for the
	 * plain {@code [batch, heads, seq_len, head_dim]} shape whatever traversal the previous stage
	 * reports.</p>
	 *
	 * @param args one argument: the inverse frequencies, one per rotated feature pair
	 * @param ctx  the call context
	 * @return a factory that creates the rotation for a {@code [batch, heads, seq_len, head_dim]} input
	 * @see RotationFeatures#applyRotaryPositionEmbedding(TraversalPolicy, PackedCollection)
	 */
	private Function<TraversalPolicy, Block> sequenceRope(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("sequence_rope", args, 1, "inv_freq");
		PackedCollection invFreq = (PackedCollection) args.get(0);
		int rotated = 2 * invFreq.getShape().getTotalSize();
		return inputShape -> {
			if (inputShape.getDimensions() != 4) {
				throw new PdslParseException("sequence_rope() expects a [batch, heads, seq_len, head_dim]"
						+ " input shape, got " + inputShape);
			}
			if (rotated > inputShape.length(3)) {
				throw new PdslParseException("sequence_rope() rotates " + rotated
						+ " features per row but the rows of " + inputShape + " hold " + inputShape.length(3));
			}
			return applyRotaryPositionEmbedding(shape(inputShape.extent()), invFreq);
		};
	}

	/**
	 * {@code mra_rope_rotation(shape, head_groups)}: a rotary position embedding with one frequency
	 * table and one position per head group (multidimensional relative attention). The groups
	 * partition the heads of the {@code [heads, head_size / 2, 2]} input in order.
	 *
	 * @param args the input shape and the head groups ({@link HeadGroupConfig HeadGroupConfig[]},
	 *             each naming its head count, frequency table and position producer)
	 * @param ctx  the call context
	 * @return the rotation {@link Block}
	 * @see RotationFeatures#mraRopeRotation
	 */
	private Block mraRopeRotation(List<Object> args, PdslPrimitiveContext ctx) {
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
		return mraRopeRotation(shape.length(0), shape.length(1) * 2, headsInGroup, groups);
	}

	/**
	 * {@code split_half_rope(heads, head_size)}: the split-half rotary layout. The flat
	 * {@code heads * head_size} input becomes {@code [heads, head_size / 2, 2]}, where element
	 * {@code i} of a head is paired with element {@code i + head_size / 2}, the pairing
	 * {@code rope_rotation} rotates.
	 *
	 * @param args two integer arguments: heads, head_size
	 * @param ctx  the call context
	 * @return a factory that creates the permutation for a flat input of {@code heads * head_size}
	 */
	private Function<TraversalPolicy, Block> splitHalfRope(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("split_half_rope", args, 2, "heads, head_size");
		int heads = toInt(args.get(0));
		int headSize = toInt(args.get(1));
		return inputShape -> {
			if (inputShape.getTotalSize() != heads * headSize) {
				throw new PdslParseException("split_half_rope() input " + inputShape
						+ " does not hold " + heads + " heads of " + headSize);
			}
			return reshapeToSplitHalfRope(heads * headSize, heads, headSize);
		};
	}

	/**
	 * {@code merge_half_rope(heads, head_size)}: the inverse of {@code split_half_rope}, from
	 * {@code [heads, head_size / 2, 2]} back to the per-head layout {@code [heads, head_size]}.
	 *
	 * @param args two integer arguments: heads, head_size
	 * @param ctx  the call context
	 * @return the merging permutation {@link Block}
	 */
	private Block mergeHalfRope(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("merge_half_rope", args, 2, "heads, head_size");
		return reshapeFromSplitHalfRope(toInt(args.get(0)), toInt(args.get(1)));
	}

	/**
	 * {@code rope_freqs(theta, head_size, seq_len)}: the rotary position embedding table. For each
	 * position up to {@code seq_len}, the rotation angle of each of the {@code head_size / 2}
	 * frequency pairs, as {@link RotationFeatures#computeRopeFreqs} defines it. The result is the
	 * {@code freq_cis} table that {@code rope_rotation} reads.
	 *
	 * @param args the base {@code theta}, the head size and the sequence length
	 * @param ctx  the call context
	 * @return the frequency table producer, shape {@code [seq_len, head_size / 2, 2]}
	 */
	private CollectionProducer ropeFreqs(List<Object> args, PdslPrimitiveContext ctx) {
		requireArguments("rope_freqs", args, 3, "theta, head_size, seq_len");
		return RotationFeatures.computeRopeFreqs(
				toDouble(args.get(0)), toInt(args.get(1)), toInt(args.get(2)));
	}
}
