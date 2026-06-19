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
 * x = x + gate &odot; f(scale &odot; n + shift)
 * </pre>
 * <p>where {@code scale}, {@code shift} and {@code gate} are {@code [batch, dim]} vectors broadcast
 * across the sequence. With {@code scale = 1}, {@code shift = 0} and {@code gate = 1} the expression
 * reduces exactly to the standard pre-norm residual block {@code x + f(norm(x))}; with {@code gate = 0}
 * the sub-layer contributes nothing and the block is the identity. These two degenerate cases are the
 * algebraic guardrails the standalone tests assert against.</p>
 *
 * <p>The modulation vectors are produced by combining the conditioning vector with a learned
 * per-block {@code to_scale_shift_gate} parameter of shape {@code [6, dim]} (six {@code dim}-wide
 * components: scale/shift/gate for self-attention followed by scale/shift/gate for the feed-forward).
 * See {@link #adaptiveModulationParameters} for the combination and {@link #modulationComponent} for
 * selecting an individual component. {@link #adaptiveModulate} and {@link #adaptiveGate} apply the
 * components to activations.</p>
 *
 * <p>This interface holds only the modulation algebra; it does not assemble attention or
 * feed-forward sub-layers. The transformer-block builder threads the modulation through behind an
 * optional argument so the unmodulated (prepend) path is unchanged. The primitives compose entirely
 * from existing {@link LayerFeatures} operations and therefore introduce no new compute primitive.</p>
 *
 * @author  Michael Murray
 * @see AttentionFeatures#transformerBlock
 */
public interface AdaptiveLayerNormFeatures extends LayerFeatures {

	/** Number of {@code dim}-wide modulation components packed into a {@code to_scale_shift_gate}. */
	int MODULATION_COMPONENTS = 6;

	/**
	 * Combines a conditioning vector with a learned per-block {@code to_scale_shift_gate} parameter to
	 * produce the packed modulation tensor consumed by a transformer block.
	 *
	 * <p>The conditioning vector ({@code [batch, dim]}) is broadcast across the six component slots and
	 * added to the per-block parameter ({@code [6, dim]}, broadcast across the batch), yielding a
	 * {@code [batch, 6, dim]} tensor whose six {@code [batch, dim]} slices are, in order,
	 * {@code scale_self}, {@code shift_self}, {@code gate_self}, {@code scale_ff}, {@code shift_ff} and
	 * {@code gate_ff}. This mirrors the adaLN-Zero combination
	 * {@code (to_scale_shift_gate + conditioning).chunk(6)}.</p>
	 *
	 * @param conditioning   the global conditioning vector, shape {@code [batch, dim]}
	 * @param scaleShiftGate the learned per-block parameter, total size {@code 6 * dim}
	 * @param batchSize      the batch dimension
	 * @param dim            the model dimension
	 * @return the packed modulation tensor, shape {@code [batch, 6, dim]}
	 */
	default Producer<PackedCollection> adaptiveModulationParameters(Producer<PackedCollection> conditioning,
																	PackedCollection scaleShiftGate,
																	int batchSize, int dim) {
		CollectionProducer broadcastCond = c(conditioning).reshape(batchSize, dim).repeat(1, MODULATION_COMPONENTS);
		CollectionProducer broadcastParam = cp(scaleShiftGate).reshape(MODULATION_COMPONENTS, dim).repeat(0, batchSize);
		return add(broadcastCond, broadcastParam);
	}

	/**
	 * Selects a single {@code [batch, dim]} modulation component from a packed {@code [batch, 6, dim]}
	 * modulation tensor.
	 *
	 * @param modulation the packed modulation tensor, shape {@code [batch, 6, dim]}
	 * @param batchSize  the batch dimension
	 * @param dim        the model dimension
	 * @param index      the component index in {@code [0, 6)}
	 * @return the selected component, shape {@code [batch, dim]}
	 */
	default Producer<PackedCollection> modulationComponent(Producer<PackedCollection> modulation,
														   int batchSize, int dim, int index) {
		if (index < 0 || index >= MODULATION_COMPONENTS) {
			throw new IllegalArgumentException("Modulation component index out of range: " + index);
		}

		return c(modulation).subset(shape(batchSize, 1, dim), 0, index, 0).reshape(batchSize, dim);
	}

	/**
	 * Builds a layer that applies an affine modulation {@code scale * x + shift} to a
	 * {@code [batch, seqLen, dim]} activation, broadcasting the per-channel {@code scale} and
	 * {@code shift} ({@code [batch, dim]}) across the sequence.
	 *
	 * @param shape the activation shape {@code [batch, seqLen, dim]}
	 * @param scale the per-channel multiplicative modulation, shape {@code [batch, dim]}
	 * @param shift the per-channel additive modulation, shape {@code [batch, dim]}
	 * @return the modulation layer
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
	 * Builds a layer that gates a {@code [batch, seqLen, dim]} activation by a per-channel {@code gate}
	 * ({@code [batch, dim]}) broadcast across the sequence, computing {@code gate * x}.
	 *
	 * @param shape the activation shape {@code [batch, seqLen, dim]}
	 * @param gate  the per-channel gate, shape {@code [batch, dim]}
	 * @return the gating layer
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
