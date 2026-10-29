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
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.layers.LayerRoutingFeatures;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author  Michael Murray
 */
public interface FeedForwardFeatures extends LayerFeatures, LayerRoutingFeatures, AdaptiveLayerNormFeatures {
	/**
	 * Classpath location of the asset describing the SwiGLU feed-forward structure. Its
	 * {@code swiglu_ffn} layer is what the {@link #feedForward} methods build; the asset is the
	 * single definition of how the residual-stream vector flows through the pre-FFN
	 * normalization, the gate and up projections, the SiLU gate, the element-wise product and
	 * the down projection. The same asset's {@code glu_feed_forward} layer (see
	 * {@link #GLU_FEED_FORWARD_LAYER}) defines the fused-projection gated-linear feed-forward body
	 * that {@link #gatedLinearFeedForward} builds.
	 */
	String FEED_FORWARD_ASSET = "/pdsl/feed_forward.pdsl";

	/**
	 * Name of the {@link #FEED_FORWARD_ASSET} layer describing the gated-linear feed-forward body:
	 * the fused gate+linear projection, the split of its output into two halves, the gate half's
	 * activation, the element-wise product of the halves and the down projection. It is the single
	 * definition of the gating {@link #gatedLinearFeedForward} composes the pre-normalization (and,
	 * for the adaptive-layernorm variant, the modulation) around.
	 */
	String GLU_FEED_FORWARD_LAYER = "glu_feed_forward";

	/**
	 * Binds the arguments the {@code swiglu_ffn} layer of {@link #FEED_FORWARD_ASSET} takes: the
	 * pre-FFN RMSNorm weights, the gate/up/down projection weights and the RMSNorm epsilon.
	 *
	 * @param rms pre-FFN RMSNorm weights, whose length is the model dimension
	 * @param w1 gate projection weights, shape {@code [hidden_dim, dim]}
	 * @param w2 down projection weights, shape {@code [dim, hidden_dim]}
	 * @param w3 up projection weights, shape {@code [hidden_dim, dim]}
	 * @param epsilon RMSNorm epsilon (e.g. 1e-5 for Llama, 1e-6 for Qwen3)
	 * @return the argument bindings for the {@code swiglu_ffn} layer
	 */
	default Map<String, Object> feedForwardArguments(
			PackedCollection rms,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			double epsilon) {
		Map<String, Object> args = new HashMap<>();
		args.put("norm_weights", rms);
		args.put("w1", w1);
		args.put("w2", w2);
		args.put("w3", w3);
		args.put("epsilon", epsilon);
		return args;
	}

	/**
	 * Creates a SwiGLU feed-forward block with the default RMSNorm epsilon (1e-5). Delegates to
	 * {@link #feedForward(PackedCollection, PackedCollection, PackedCollection, PackedCollection,
	 * double, ComputeRequirement...)}.
	 *
	 * @param rms RMSNorm weights
	 * @param w1 gate projection weights
	 * @param w2 down projection weights
	 * @param w3 up projection weights
	 * @param requirements compute requirements applied to every layer the asset builds
	 * @return feed-forward block
	 */
	default Block feedForward(
			PackedCollection rms,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			ComputeRequirement... requirements) {
		return feedForward(rms, w1, w2, w3, 1e-5, requirements);
	}

	/**
	 * Creates a SwiGLU feed-forward block from the {@code swiglu_ffn} layer of
	 * {@link #FEED_FORWARD_ASSET}.
	 *
	 * <p>The structure is not assembled here: the asset composes the pre-FFN RMSNorm, the gate
	 * and up projections, the SiLU gate, the element-wise product and the down projection. This
	 * method only binds the weights and epsilon, loads the asset and builds the layer for a
	 * {@code (1, dim)} input, where {@code dim} is the down projection's output dimension.</p>
	 *
	 * @param rms RMSNorm weights
	 * @param w1 gate projection weights, shape {@code [hidden_dim, dim]}
	 * @param w2 down projection weights, shape {@code [dim, hidden_dim]}
	 * @param w3 up projection weights, shape {@code [hidden_dim, dim]}
	 * @param epsilon RMSNorm epsilon (e.g. 1e-5 for Llama, 1e-6 for Qwen3)
	 * @param requirements compute requirements applied to every layer the asset builds
	 * @return feed-forward block
	 */
	default Block feedForward(
			PackedCollection rms,
			PackedCollection w1, PackedCollection w2, PackedCollection w3,
			double epsilon,
			ComputeRequirement... requirements) {
		int dim = w2.getShape().length(0);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(FEED_FORWARD_ASSET), "swiglu_ffn",
				shape(1, dim), feedForwardArguments(rms, w1, w2, w3, epsilon), requirements);
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
	 * @throws IllegalArgumentException if exactly one of {@code modScale}/{@code modShift} is supplied
	 */
	default Block gatedLinearFeedForward(TraversalPolicy inputShape,
										 PackedCollection normWeights, PackedCollection normBiases,
										 PackedCollection weightIn, PackedCollection biasIn,
										 PackedCollection weightOut, PackedCollection biasOut,
										 Producer<PackedCollection> modScale, Producer<PackedCollection> modShift,
										 ProjectionFactory projectionFactory,
										 ComputeRequirement... requirements) {
		return gatedLinearFeedForward(inputShape, NormalizationType.LAYER, normWeights, normBiases,
				weightIn, biasIn, weightOut, biasOut, modScale, modShift, silu(), projectionFactory, requirements);
	}

	/**
	 * Creates a gated linear feed-forward block whose pre-normalization is of the given family.
	 *
	 * <p>This is the fully specified overload; every other {@code gatedLinearFeedForward} routes
	 * here with {@link NormalizationType#LAYER}, which keeps their normalization unchanged.</p>
	 *
	 * <p>The gating composition — the fused gate+linear projection, the split into two halves, the
	 * gate half's activation, their element-wise product and the down projection — is not assembled
	 * here: it is the {@link #GLU_FEED_FORWARD_LAYER} layer of {@link #FEED_FORWARD_ASSET}, and this
	 * method binds the two projections (built through {@code projectionFactory}, so a dense model and
	 * an adapter-wrapped one share the one gating body) and the gate activation, then loads it. Only
	 * the pre-normalization and the optional adaLN modulation are composed in Java, around the loaded
	 * body: the modulation ({@code modScale * x + modShift}) is driven by a runtime conditioning
	 * vector the language cannot yet express as a stage, and it must sit between the norm and the gate
	 * projection, so keeping both out of the asset lets the plain and modulated feed-forwards share a
	 * single gating definition. The input is a {@code [batch, seq_len, dim]} activation.</p>
	 *
	 * @param inputShape Input/output shape
	 * @param normType Family of the pre-normalization
	 * @param normWeights Normalization weights
	 * @param normBiases Normalization biases ({@code null} for none)
	 * @param weightIn Input projection weights (GLU)
	 * @param biasIn Input projection bias
	 * @param weightOut Output projection weights
	 * @param biasOut Output projection bias
	 * @param modScale adaLN multiplicative modulation applied after norm ({@code null} to disable)
	 * @param modShift adaLN additive modulation applied after norm ({@code null} to disable)
	 * @param gateActivation Activation applied to the gate half of the projection before it
	 *                       multiplies the linear half (SiLU for a SwiGLU, GELU for a GeGLU)
	 * @param projectionFactory Factory for creating projection layers
	 * @param requirements Compute requirements
	 * @return Gated linear feed-forward block
	 * @throws IllegalArgumentException if exactly one of {@code modScale}/{@code modShift} is supplied,
	 *                                  if {@code inputShape} is not rank 3, or if the fused projection
	 *                                  produces an odd number of features
	 */
	default Block gatedLinearFeedForward(TraversalPolicy inputShape, NormalizationType normType,
										 PackedCollection normWeights, PackedCollection normBiases,
										 PackedCollection weightIn, PackedCollection biasIn,
										 PackedCollection weightOut, PackedCollection biasOut,
										 Producer<PackedCollection> modScale, Producer<PackedCollection> modShift,
										 Function<TraversalPolicy, CellularLayer> gateActivation,
										 ProjectionFactory projectionFactory,
										 ComputeRequirement... requirements) {
		// adaLN modulation is enabled only when both modScale and modShift are supplied; exactly one
		// is a misconfiguration.
		if ((modScale == null) != (modShift == null)) {
			throw new IllegalArgumentException(
					"modScale and modShift must both be supplied to enable adaLN modulation, " +
					"or both be null to disable it");
		}
		if (inputShape.getDimensions() != 3) {
			throw new IllegalArgumentException("gatedLinearFeedForward requires a [batch, seq_len, dim] " +
					"input, but was given " + inputShape);
		}

		SequentialBlock feedForward = new SequentialBlock(inputShape);
		feedForward.add(norm(normType, normWeights, normBiases, requirements));
		if (modScale != null) {
			feedForward.add(adaptiveModulate(feedForward.getOutputShape(), modScale, modShift));
		}

		TraversalPolicy normedShape = feedForward.getOutputShape();
		CellularLayer inProjection = projectionFactory.create(normedShape, weightIn, biasIn,
				AdapterConfig.TargetLayer.FFN_GATE);
		int fusedDim = inProjection.getOutputShape().length(
				inProjection.getOutputShape().getDimensions() - 1);
		if (fusedDim % 2 != 0) {
			throw new IllegalArgumentException("The fused gate+linear projection must produce an even " +
					"number of features to split into two halves, but produces " + fusedDim);
		}
		int innerDim = fusedDim / 2;
		TraversalPolicy gatedShape = shape(normedShape.length(0), normedShape.length(1), innerDim);
		CellularLayer outProjection = projectionFactory.create(gatedShape, weightOut, biasOut,
				AdapterConfig.TargetLayer.FFN_OUT);

		Map<String, Object> gatingArguments = new HashMap<>();
		gatingArguments.put("in_projection", inProjection);
		gatingArguments.put("out_projection", outProjection);
		gatingArguments.put("gate_activation", gateActivation.apply(gatedShape));
		gatingArguments.put("batch", normedShape.length(0));
		gatingArguments.put("seq_len", normedShape.length(1));
		gatingArguments.put("inner_dim", innerDim);

		PdslLoader loader = new PdslLoader();
		feedForward.add(loader.buildLayer(loader.parseResource(FEED_FORWARD_ASSET),
				GLU_FEED_FORWARD_LAYER, normedShape, gatingArguments, requirements));

		return feedForward;
	}
}
