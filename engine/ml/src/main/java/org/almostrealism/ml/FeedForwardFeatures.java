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
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;
import java.util.function.Function;

/**
 * Provides the feed-forward network builders shared by the transformer stacks in
 * {@link AttentionFeatures}.
 *
 * <p>These methods construct the position-wise feed-forward sub-layer of a transformer block in
 * AR's two prevailing idioms: the SwiGLU {@code feedForward(...)} family used by the
 * autoregressive language-model path, and the gated-linear {@code gatedLinear(...)} /
 * {@code gatedLinearFeedForward(...)} family used by the diffusion-transformer path. They are
 * factored into their own mixin so the feed-forward concept is organized independently of the
 * attention machinery; {@link AttentionFeatures} extends this interface, so every existing
 * consumer continues to see these methods unchanged.</p>
 *
 * @author  Michael Murray
 */
public interface FeedForwardFeatures extends LayerFeatures, LayerRoutingFeatures, AdaptiveLayerNormFeatures {
	/**
	 * Creates a SwiGLU feed-forward block with RMSNorm (simplified version without biases).
	 * Delegates to the full feedForward method with null biases.
	 *
	 * @param rms RMSNorm weights
	 * @param w1 Gate projection weights
	 * @param w2 Down projection weights
	 * @param w3 Up projection weights
	 * @param requirements Compute requirements
	 * @return Feed-forward block
	 */
	default Block feedForward(
			PackedCollection rms,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		return feedForward(shape(1, dim), rms, null,
				w1, w2, w3, null, null, null,
				requirements);
	}

	/**
	 * Creates a SwiGLU feed-forward block with configurable RMSNorm epsilon.
	 *
	 * @param rms RMSNorm weights
	 * @param w1 Gate projection weights
	 * @param w2 Down projection weights
	 * @param w3 Up projection weights
	 * @param epsilon RMSNorm epsilon (e.g., 1e-5 for Llama, 1e-6 for Qwen3)
	 * @param requirements Compute requirements
	 * @return Feed-forward block
	 */
	default Block feedForward(
			PackedCollection rms,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			double epsilon,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		return feedForward(shape(1, dim), rms, null,
				w1, w2, w3, null, null, null, epsilon,
				requirements);
	}

	/**
	 * Creates a SwiGLU feed-forward block with optional biases.
	 *
	 * <p>Implements the SwiGLU activation: FFN(x) = (SiLU(x @ W1 + b1) * (x @ W3 + b3)) @ W2 + b2
	 * This is the standard feed-forward layer used in modern transformers.</p>
	 *
	 * @param shape Input/output shape
	 * @param normWeights Normalization weights (RMSNorm or LayerNorm)
	 * @param normBiases Normalization biases (null for RMSNorm)
	 * @param w1 Gate projection weights
	 * @param w2 Down projection weights
	 * @param w3 Up projection weights
	 * @param w1Bias Gate projection bias (null if not used)
	 * @param w2Bias Down projection bias (null if not used)
	 * @param w3Bias Up projection bias (null if not used)
	 * @param requirements Compute requirements
	 * @return Feed-forward block
	 */
	default Block feedForward(
			TraversalPolicy shape,
			PackedCollection normWeights, PackedCollection normBiases,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			PackedCollection w1Bias, PackedCollection w2Bias, PackedCollection w3Bias,
			ComputeRequirement... requirements) {
		return feedForward(shape, normWeights, normBiases, w1, w2, w3,
				w1Bias, w2Bias, w3Bias, 1e-5, requirements);
	}

	/**
	 * Creates a SwiGLU feed-forward block with configurable RMSNorm epsilon.
	 *
	 * @param shape Input/output shape
	 * @param normWeights Normalization weights (RMSNorm or LayerNorm)
	 * @param normBiases Normalization biases (null for RMSNorm)
	 * @param w1 Gate projection weights
	 * @param w2 Down projection weights
	 * @param w3 Up projection weights
	 * @param w1Bias Gate projection bias (null if not used)
	 * @param w2Bias Down projection bias (null if not used)
	 * @param w3Bias Up projection bias (null if not used)
	 * @param epsilon RMSNorm epsilon (e.g., 1e-5 for Llama, 1e-6 for Qwen3)
	 * @param requirements Compute requirements
	 * @return Feed-forward block
	 */
	default Block feedForward(
			TraversalPolicy shape,
			PackedCollection normWeights, PackedCollection normBiases,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			PackedCollection w1Bias, PackedCollection w2Bias, PackedCollection w3Bias,
			double epsilon,
			ComputeRequirement... requirements) {
		SequentialBlock feedForward = new SequentialBlock(shape);
		feedForward.add(rmsnorm(shape, normWeights, normBiases, epsilon, requirements));

		SequentialBlock hidden = new SequentialBlock(shape);
		hidden.add(dense(w1, w1Bias));
		hidden.add(silu());

		feedForward.product(dense(w3, w3Bias), hidden);
		feedForward.add(dense(w2, w2Bias));
		return feedForward;
	}

	/**
	 * Creates a gated linear unit (GLU) function that can be applied to different input shapes.
	 *
	 * @param weight Linear projection weights (projects to 2x output dimension)
	 * @param bias Linear projection bias
	 * @return A function that creates a GLU block for a given input shape
	 */
	default Function<TraversalPolicy, Block> gatedLinear(PackedCollection weight,
														 PackedCollection bias) {
		return inputShape -> gatedLinear(inputShape, weight, bias);
	}

	/**
	 * Creates a gated linear unit (GLU) block with SiLU activation.
	 *
	 * <p>Implements GLU(x) = Linear(x)_left * SiLU(Linear(x)_right)
	 * The linear projection outputs 2x the input dimension, which is then split
	 * into two equal parts for gating.</p>
	 *
	 * @param inputShape Input shape
	 * @param weight Linear projection weights
	 * @param bias Linear projection bias
	 * @return Gated linear block
	 */
	default Block gatedLinear(TraversalPolicy inputShape,
							  PackedCollection weight,
							  PackedCollection bias) {
		SequentialBlock glu = new SequentialBlock(inputShape);
		glu.add(dense(weight, bias));

		// Split the output into two parts, one for
		// the linear transform and one for the gate
		List<Block> split = glu.split(2, glu.getOutputShape().getDimensions() - 1, 0);
		Block gate = split.get(1).andThen(silu());

		// Apply activation to the gate and multiply
		// it with the linear output
		glu.add(product(gate));
		return glu;
	}

	/**
	 * Creates a gated linear feed-forward function with normalization.
	 *
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param requirements Compute requirements
	 * @return A function that creates a gated linear FFN block for a given input shape
	 */
	default Function<TraversalPolicy, Block> gatedLinearFeedForward(PackedCollection normWeights, PackedCollection normBiases,
																	 PackedCollection weightIn, PackedCollection biasIn,
																	 PackedCollection weightOut, PackedCollection biasOut,
																	ComputeRequirement... requirements) {
		return inputShape ->
				gatedLinearFeedForward(inputShape, normWeights, normBiases,
										weightIn, biasIn, weightOut, biasOut,
										requirements);
	}

	/**
	 * Creates a gated linear feed-forward block with normalization.
	 *
	 * <p>Combines normalization, gated linear unit, and output projection:
	 * FFN(x) = Linear_out(GLU(Norm(x)))</p>
	 *
	 * @param inputShape Input/output shape
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param requirements Compute requirements
	 * @return Gated linear feed-forward block
	 */
	default Block gatedLinearFeedForward(TraversalPolicy inputShape,
										 PackedCollection normWeights, PackedCollection normBiases,
										 PackedCollection weightIn, PackedCollection biasIn,
										 PackedCollection weightOut, PackedCollection biasOut,
										 ComputeRequirement... requirements) {
		return gatedLinearFeedForward(inputShape, normWeights, normBiases,
				weightIn, biasIn, weightOut, biasOut,
				ProjectionFactory.dense(), requirements);
	}

	/**
	 * Creates a gated linear feed-forward block with customizable projection layers.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection
	 * layers are created, enabling LoRA or other adapter patterns without code duplication.</p>
	 *
	 * @param inputShape Input/output shape
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param projectionFactory Factory for creating projection layers
	 * @param requirements Compute requirements
	 * @return Gated linear feed-forward block
	 */
	default Block gatedLinearFeedForward(TraversalPolicy inputShape,
										 PackedCollection normWeights, PackedCollection normBiases,
										 PackedCollection weightIn, PackedCollection biasIn,
										 PackedCollection weightOut, PackedCollection biasOut,
										 ProjectionFactory projectionFactory,
										 ComputeRequirement... requirements) {
		return gatedLinearFeedForward(inputShape, normWeights, normBiases,
				weightIn, biasIn, weightOut, biasOut,
				null, null, projectionFactory, requirements);
	}

	/**
	 * Creates a gated linear feed-forward block with optional adaLN modulation of the normalized
	 * activations.
	 *
	 * <p>When {@code modScale} and {@code modShift} are supplied, the affine modulation
	 * {@code modScale * norm(x) + modShift} is applied immediately after the internal
	 * normalization (and before the gate projection), implementing the adaptive layer-normalization
	 * (adaLN-Zero) modulation of the feed-forward sub-layer. When both are {@code null} the block is
	 * identical to the unmodulated {@code gatedLinearFeedForward}, so the default path is unchanged.</p>
	 *
	 * @param inputShape Input/output shape
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param modScale adaLN multiplicative modulation applied after norm ({@code null} to disable)
	 * @param modShift adaLN additive modulation applied after norm ({@code null} to disable)
	 * @param projectionFactory Factory for creating projection layers
	 * @param requirements Compute requirements
	 * @return Gated linear feed-forward block
	 */
	default Block gatedLinearFeedForward(TraversalPolicy inputShape,
										 PackedCollection normWeights, PackedCollection normBiases,
										 PackedCollection weightIn, PackedCollection biasIn,
										 PackedCollection weightOut, PackedCollection biasOut,
										 Producer<PackedCollection> modScale, Producer<PackedCollection> modShift,
										 ProjectionFactory projectionFactory,
										 ComputeRequirement... requirements) {
		SequentialBlock feedForward = new SequentialBlock(inputShape);
		feedForward.add(norm(normWeights, normBiases, requirements));

		// adaLN modulation of the normalized activations (identity when no modulation supplied)
		if (modScale != null && modShift != null) {
			feedForward.add(adaptiveModulate(feedForward.getOutputShape(), modScale, modShift));
		}

		// Gate projection with factory
		feedForward.add(projectionFactory.create(feedForward.getOutputShape(), weightIn, biasIn,
				AdapterConfig.TargetLayer.FFN_GATE));

		// Split into gate and up projections, apply SwiGLU gating
		List<Block> split = feedForward.split(2, feedForward.getOutputShape().getDimensions() - 1, 0);
		Block gate = split.get(1).andThen(silu());

		// Multiply linear output with gated branch
		feedForward.add(product(gate));

		// Output projection with factory
		feedForward.add(projectionFactory.create(feedForward.getOutputShape(), weightOut, biasOut,
				AdapterConfig.TargetLayer.FFN_OUT));

		return feedForward;
	}
}
