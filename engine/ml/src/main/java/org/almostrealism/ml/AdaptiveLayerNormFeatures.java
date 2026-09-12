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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.Block;

/**
 * Adaptive layer-normalization (adaLN-Zero) modulation primitives shared by the transformer stack.
 *
 * <p>adaLN-Zero is the dominant conditioning scheme for modern diffusion transformers: instead of
 * prepending the conditioning vector as an extra sequence token, a global conditioning vector
 * produces, per block, a set of <em>scale</em>, <em>shift</em> and <em>gate</em> modulation vectors
 * that reshape each sub-layer. For a normalized activation {@code n = norm(x)} and a sub-layer
 * {@code f} (self-attention or feed-forward) the modulated residual is</p>
 * <pre>
 * x = x + sigmoid(1 - gate) &odot; f((1 + scale) &odot; n + shift)
 * </pre>
 * <p>where {@code scale}, {@code shift} and {@code gate} are {@code [batch, dim]} vectors broadcast
 * across the sequence. The raw {@code scale} and {@code gate} components are therefore never applied
 * directly: {@link #residualScale} turns a raw scale into the multiplier {@code 1 + scale} and
 * {@link #residualGate} turns a raw gate into the multiplier {@code sigmoid(1 - gate)}, so that a
 * zero-initialised parameter leaves the normalized activation unscaled and passes about
 * {@code 73%} of the sub-layer output. With {@code scale = 0}, {@code shift = 0} and a very negative
 * {@code gate} the expression reduces to the standard pre-norm residual block {@code x + f(norm(x))};
 * with a very positive {@code gate} the sub-layer contributes nothing and the block is the identity.
 * These two limits are the algebraic guardrails the standalone tests assert against.</p>
 *
 * <p>The raw modulation components are produced by combining a conditioning tensor with a learned
 * per-block {@code to_scale_shift_gate} parameter of {@code 6 * dim} values (six {@code dim}-wide
 * components: scale/shift/gate for self-attention followed by scale/shift/gate for the
 * feed-forward). The conditioning tensor is normally the output of a shared
 * {@link #globalConditioningEmbedding global conditioning embedder}, a small MLP mapping the
 * {@code [batch, dim]} global vector to {@code [batch, 6 * dim]}, combined by
 * {@link #packedModulation}. {@link #adaptiveModulationParameters} is the simpler variant that
 * broadcasts a {@code [batch, dim]} conditioning vector to every component. {@link #modulationComponent}
 * selects an individual component, and {@link #adaptiveModulate} / {@link #adaptiveGate} apply an
 * affine modulation or a multiplicative gate to activations.</p>
 *
 * <p>This interface holds only the modulation algebra; it does not assemble attention or
 * feed-forward sub-layers. The transformer-block builder threads the modulation through behind an
 * optional argument so the unmodulated (prepend) path is unchanged. The primitives compose entirely
 * from existing {@link LayerFeatures} operations and therefore introduce no new compute primitive.</p>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author  Michael Murray
 * @see AttentionFeatures#transformerBlock
 */
public interface AdaptiveLayerNormFeatures extends LayerFeatures {

	/** Number of {@code dim}-wide modulation components packed into a {@code to_scale_shift_gate}. */
	int MODULATION_COMPONENTS = 6;

	/**
	 * The shared global conditioning embedder that maps a {@code [batch, dim]} global conditioning
	 * vector to the {@code [batch, 6 * dim]} tensor of raw modulation components, as a two-layer MLP
	 * {@code linear(dim, dim) . silu . linear(dim, 6 * dim)}.
	 *
	 * <p>The embedder is shared by every block; each block then adds its own
	 * {@code to_scale_shift_gate} parameter (see {@link #packedModulation}).</p>
	 *
	 * @param conditioning the global conditioning vector, shape {@code [batch, dim]}
	 * @param weightIn     first linear weight, shape {@code [dim, dim]}
	 * @param biasIn       first linear bias, shape {@code [dim]}
	 * @param weightOut    second linear weight, shape {@code [6 * dim, dim]}
	 * @param biasOut      second linear bias, shape {@code [6 * dim]}
	 * @param batchSize    batch size
	 * @param dim          transformer embedding dimension
	 * @return a producer of the embedded conditioning, shape {@code [batch, 6 * dim]}
	 */
	default CollectionProducer globalConditioningEmbedding(Producer<PackedCollection> conditioning,
															PackedCollection weightIn, PackedCollection biasIn,
															PackedCollection weightOut, PackedCollection biasOut,
															int batchSize, int dim) {
		return siluMlp(c(conditioning).reshape(batchSize, dim), weightIn, biasIn, weightOut, biasOut)
				.reshape(batchSize, MODULATION_COMPONENTS * dim);
	}

	/**
	 * Combines an embedded conditioning tensor with a learned per-block {@code to_scale_shift_gate}
	 * parameter to produce the packed modulation tensor consumed by a transformer block.
	 *
	 * <p>Both operands carry {@code 6 * dim} values per batch element; the parameter is broadcast
	 * across the batch and added, yielding a {@code [batch, 6, dim]} tensor whose six
	 * {@code [batch, dim]} slices are, in order, {@code scale_self}, {@code shift_self},
	 * {@code gate_self}, {@code scale_ff}, {@code shift_ff} and {@code gate_ff}. This mirrors the
	 * adaLN-Zero combination {@code (to_scale_shift_gate + global_cond).chunk(6)}.</p>
	 *
	 * @param conditioning   the embedded conditioning, total size {@code batch * 6 * dim}
	 * @param scaleShiftGate the learned per-block parameter, total size {@code 6 * dim}
	 * @param batchSize      batch size
	 * @param dim            transformer embedding dimension
	 * @return a producer of the packed modulation, shape {@code [batch, 6, dim]}
	 */
	default Producer<PackedCollection> packedModulation(Producer<PackedCollection> conditioning,
														PackedCollection scaleShiftGate,
														int batchSize, int dim) {
		CollectionProducer cond = c(conditioning).reshape(batchSize, MODULATION_COMPONENTS, dim);
		CollectionProducer param = cp(scaleShiftGate).reshape(MODULATION_COMPONENTS, dim).repeat(0, batchSize);
		return add(cond, param);
	}

	/**
	 * Combines a {@code [batch, dim]} conditioning vector, broadcast to every component slot, with a
	 * learned per-block {@code to_scale_shift_gate} parameter. This is the variant for models whose
	 * blocks share one {@code dim}-wide conditioning vector directly; models with a
	 * {@link #globalConditioningEmbedding global conditioning embedder} use {@link #packedModulation}
	 * on the embedder output instead.
	 *
	 * @param conditioning   the global conditioning vector, shape {@code [batch, dim]}
	 * @param scaleShiftGate the learned per-block parameter, total size {@code 6 * dim}
	 * @param batchSize      batch size
	 * @param dim            transformer embedding dimension
	 * @return a producer of the packed modulation, shape {@code [batch, 6, dim]}
	 */
	default Producer<PackedCollection> adaptiveModulationParameters(Producer<PackedCollection> conditioning,
																	PackedCollection scaleShiftGate,
																	int batchSize, int dim) {
		CollectionProducer broadcastCond = c(conditioning).reshape(batchSize, dim).repeat(1, MODULATION_COMPONENTS);
		return packedModulation(broadcastCond, scaleShiftGate, batchSize, dim);
	}

	/**
	 * Selects one {@code [batch, dim]} component from a packed {@code [batch, 6, dim]} modulation.
	 *
	 * @param modulation the packed modulation from {@link #packedModulation}
	 * @param batchSize  batch size
	 * @param dim        transformer embedding dimension
	 * @param index      component index in {@code [0, 6)}: scale/shift/gate for self-attention then
	 *                   scale/shift/gate for the feed-forward
	 * @return a producer of the selected component, shape {@code [batch, dim]}
	 */
	default Producer<PackedCollection> modulationComponent(Producer<PackedCollection> modulation,
														   int batchSize, int dim, int index) {
		if (index < 0 || index >= MODULATION_COMPONENTS) {
			throw new IllegalArgumentException("Modulation component index out of range: " + index);
		}

		return c(modulation).subset(shape(batchSize, 1, dim), 0, index, 0).reshape(batchSize, dim);
	}

	/**
	 * The multiplier applied to a normalized activation for a raw scale component: {@code 1 + scale},
	 * so that a zero parameter leaves the activation unscaled.
	 *
	 * @param scale the raw scale component, shape {@code [batch, dim]}
	 * @return a producer of {@code 1 + scale}
	 */
	default Producer<PackedCollection> residualScale(Producer<PackedCollection> scale) {
		return c(scale).add(1.0);
	}

	/**
	 * The multiplier applied to a sub-layer output for a raw gate component:
	 * {@code sigmoid(1 - gate)}, so that a zero parameter passes roughly {@code 73%} of the output,
	 * a very negative gate passes all of it and a very positive gate suppresses it entirely.
	 *
	 * @param gate the raw gate component, shape {@code [batch, dim]}
	 * @return a producer of {@code sigmoid(1 - gate)}
	 */
	default Producer<PackedCollection> residualGate(Producer<PackedCollection> gate) {
		return sigmoid(c(gate).multiply(-1.0).add(1.0));
	}

	/**
	 * Applies an affine modulation {@code scale * x + shift} to a {@code [batch, seqLen, dim]}
	 * activation, with per-channel {@code [batch, dim]} scale and shift broadcast across the sequence.
	 *
	 * @param shape the activation shape {@code [batch, seqLen, dim]}
	 * @param scale the multiplier, shape {@code [batch, dim]}
	 * @param shift the additive term, shape {@code [batch, dim]}
	 * @return a block applying the modulation
	 */
	default Block adaptiveModulate(TraversalPolicy shape,
								   Producer<PackedCollection> scale, Producer<PackedCollection> shift) {
		int batchSize = shape.length(0);
		int seqLen = shape.length(1);
		int dim = shape.length(2);

		return layer("adaLNModulate", shape, shape, in -> {
			CollectionProducer scaleB = c(scale).reshape(batchSize, dim).repeat(1, seqLen);
			CollectionProducer shiftB = c(shift).reshape(batchSize, dim).repeat(1, seqLen);
			return c(in).multiply(scaleB).add(shiftB);
		});
	}

	/**
	 * Applies a multiplicative gate {@code gate * x} to a {@code [batch, seqLen, dim]} activation,
	 * with a per-channel {@code [batch, dim]} gate broadcast across the sequence.
	 *
	 * @param shape the activation shape {@code [batch, seqLen, dim]}
	 * @param gate  the multiplier, shape {@code [batch, dim]}
	 * @return a block applying the gate
	 */
	default Block adaptiveGate(TraversalPolicy shape, Producer<PackedCollection> gate) {
		int batchSize = shape.length(0);
		int seqLen = shape.length(1);
		int dim = shape.length(2);

		return layer("adaLNGate", shape, shape, in -> {
			CollectionProducer gateB = c(gate).reshape(batchSize, dim).repeat(1, seqLen);
			return c(in).multiply(gateB);
		});
	}
}
