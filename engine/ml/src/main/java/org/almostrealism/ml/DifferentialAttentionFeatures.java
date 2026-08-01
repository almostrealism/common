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
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;

/**
 * Adds differential attention as a selectable {@link AttentionVariant} on top of the shared
 * {@link AttentionFeatures} attention/transformer stack.
 *
 * <p>Differential attention (from the <em>Differential Transformer</em> line of work) computes two
 * scaled-dot-product attention maps and subtracts the second, scaled by a learned per-head lambda,
 * from the first. A class gains the variant simply by implementing this interface instead of
 * {@link AttentionFeatures}: the {@link #selfAttention} override is invoked virtually from the shared
 * {@code transformerBlock} builder, so no block-assembly code is forked. The
 * {@link AttentionVariant#STANDARD} path is untouched and continues to flow through
 * {@link AttentionFeatures#sequenceAttention}.</p>
 *
 * <p>The projection used by the differential variant is a fused {@code to_qkv} of width
 * {@code dim * 5} whose five equal sections are, in order, {@code [Q1, K1, K2, V, Q2]}. The two
 * attention maps are {@code map1 = softmax(Q1·K1ᵀ/√d)} and {@code map2 = softmax(Q2·K2ᵀ/√d)} and the
 * differential output is {@code (map1 − λ·map2)·V}. Because matrix multiplication is linear this is
 * evaluated as {@code map1·V − λ·(map2·V)}, which reuses
 * {@link AttentionFeatures#scaledDotProductAttention} for both maps and combines their outputs; the
 * existing output projection is then applied. With {@code λ = 0} the result is exactly the standard
 * scaled-dot-product attention over {@code [Q1, K1, V]}.</p>
 *
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
 *
 * @author  Michael Murray
 * @see AttentionVariant
 * @see AttentionFeatures#selfAttention
 */
public interface DifferentialAttentionFeatures extends AttentionFeatures {

	/**
	 * {@inheritDoc}
	 *
	 * <p>Routes {@link AttentionVariant#DIFFERENTIAL} to {@link #differentialSequenceAttention} and
	 * defers every other variant (including {@link AttentionVariant#STANDARD}) to the base
	 * implementation, so the default path remains unchanged.</p>
	 */
	@Override
	default Block selfAttention(int batchSize, int seqLen, int dim, int heads,
								AttentionVariant variant,
								PackedCollection toQkvWeight, PackedCollection toOutWeight,
								PackedCollection qNormWeight, PackedCollection qNormBias,
								PackedCollection kNormWeight, PackedCollection kNormBias,
								PackedCollection invFreq,
								Producer<PackedCollection> diffLambda,
								ProjectionFactory projectionFactory) {
		if (variant == AttentionVariant.DIFFERENTIAL) {
			return differentialSequenceAttention(batchSize, seqLen, dim, heads,
					toQkvWeight, toOutWeight,
					qNormWeight, qNormBias, kNormWeight, kNormBias,
					invFreq, diffLambda, projectionFactory);
		}

		return AttentionFeatures.super.selfAttention(batchSize, seqLen, dim, heads, variant,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, diffLambda, projectionFactory);
	}

	/**
	 * Builds a differential self-attention block using the default dense projection factory.
	 *
	 * @param batchSize   batch dimension
	 * @param seqLen      sequence length
	 * @param dim         model dimension
	 * @param heads       number of attention heads
	 * @param toQkvWeight fused {@code dim*5} projection weights ({@code [Q1, K1, K2, V, Q2]})
	 * @param toOutWeight output projection weights
	 * @param qNormWeight query normalization weights
	 * @param qNormBias   query normalization biases
	 * @param kNormWeight key normalization weights
	 * @param kNormBias   key normalization biases
	 * @param invFreq     RoPE inverse frequencies
	 * @param diffLambda  learned per-head lambda (shape {@code [heads]})
	 * @return the differential self-attention block
	 */
	default Block differentialSequenceAttention(int batchSize, int seqLen, int dim, int heads,
												PackedCollection toQkvWeight, PackedCollection toOutWeight,
												PackedCollection qNormWeight, PackedCollection qNormBias,
												PackedCollection kNormWeight, PackedCollection kNormBias,
												PackedCollection invFreq,
												Producer<PackedCollection> diffLambda) {
		return differentialSequenceAttention(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, diffLambda, ProjectionFactory.dense());
	}

	/**
	 * Builds a differential self-attention block.
	 *
	 * <p>The block maps {@code [batch, seqLen, dim]} to {@code [batch, seqLen, dim]}. The fused
	 * projection produces five equal sections {@code [Q1, K1, K2, V, Q2]}; the {@code Q1} section
	 * stays in the main path while {@code K1}, {@code K2}, {@code V} and {@code Q2} are evaluated as
	 * parallel branches. The branches store their results, and (because branch forwards run before
	 * the main path continues) the second attention map is fully computed before the main path
	 * subtracts {@code λ} times it from the first map. RoPE and QK-normalization are applied to the
	 * queries and keys exactly as in {@link AttentionFeatures#sequenceAttention}.</p>
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param toQkvWeight       fused {@code dim*5} projection weights ({@code [Q1, K1, K2, V, Q2]})
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights
	 * @param qNormBias         query normalization biases
	 * @param kNormWeight       key normalization weights
	 * @param kNormBias         key normalization biases
	 * @param invFreq           RoPE inverse frequencies
	 * @param diffLambda        learned per-head lambda (shape {@code [heads]})
	 * @param projectionFactory factory for creating projection layers
	 * @return the differential self-attention block
	 */
	default Block differentialSequenceAttention(int batchSize, int seqLen, int dim, int heads,
												PackedCollection toQkvWeight, PackedCollection toOutWeight,
												PackedCollection qNormWeight, PackedCollection qNormBias,
												PackedCollection kNormWeight, PackedCollection kNormBias,
												PackedCollection invFreq,
												Producer<PackedCollection> diffLambda,
												ProjectionFactory projectionFactory) {
		if (diffLambda == null) {
			throw new IllegalArgumentException("Differential attention requires a lambda producer");
		}
		// TODO(review): Consider validating that diffLambda produces shape [heads]; a wrong shape
		// causes a silent broadcast mismatch in the lambda expansion rather than a clear error.

		int dimHead = dim / heads;
		TraversalPolicy inputShape = shape(batchSize, seqLen, dim);
		TraversalPolicy headShape = shape(batchSize, heads, seqLen, dimHead);

		SequentialBlock attention = new SequentialBlock(inputShape);

		// 1. Fused doubled-QK projection: dim -> 5*dim, sections [Q1, K1, K2, V, Q2]
		attention.add(projectionFactory.create(inputShape, toQkvWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));

		// 2. Split into the five equal sections; Q1 stays in the main path (mainIndex 0)
		attention.reshape(batchSize, seqLen, 5, dim);
		List<Block> sections = attention.split(shape(batchSize, seqLen, 1, dim), 0);
		SequentialBlock q1 = (SequentialBlock) sections.get(0).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock k1 = (SequentialBlock) sections.get(1).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock k2 = (SequentialBlock) sections.get(2).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock v  = (SequentialBlock) sections.get(3).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock q2 = (SequentialBlock) sections.get(4).reshape(batchSize, seqLen, heads, dimHead);

		// 3. Permute each section to (batch, heads, seqLen, dimHead)
		q1.permute(0, 2, 1, 3);
		k1.permute(0, 2, 1, 3);
		k2.permute(0, 2, 1, 3);
		v.permute(0, 2, 1, 3);
		q2.permute(0, 2, 1, 3);

		// 4. QK normalization (both queries share q_norm, both keys share k_norm)
		q1.add(norm(qNormWeight, qNormBias, 1e-6));
		q2.add(norm(qNormWeight, qNormBias, 1e-6));
		k1.add(norm(kNormWeight, kNormBias, 1e-6));
		k2.add(norm(kNormWeight, kNormBias, 1e-6));

		// 5. Rotary position embeddings on queries and keys
		q1.add(applyRotaryPositionEmbedding(headShape, invFreq));
		q2.add(applyRotaryPositionEmbedding(headShape, invFreq));
		k1.add(applyRotaryPositionEmbedding(headShape, invFreq));
		k2.add(applyRotaryPositionEmbedding(headShape, invFreq));

		// 6. Store K1, K2 and V; the Q2 branch computes the second attention map (map2 @ V)
		PackedCollection k1Tensor = new PackedCollection(headShape);
		PackedCollection k2Tensor = new PackedCollection(headShape);
		PackedCollection vTensor = new PackedCollection(headShape);
		PackedCollection map2Tensor = new PackedCollection(headShape);

		k1.andThen(into(k1Tensor));
		k2.andThen(into(k2Tensor));
		v.andThen(into(vTensor));

		// The Q2 branch is the last branch appended by split(...), so K2 and V have already been
		// stored when it runs (BranchBlock evaluates branch forwards in order, then the main path).
		q2.add(scaledDotProductAttention(batchSize, seqLen, heads, dimHead, k2Tensor, vTensor));
		q2.andThen(into(map2Tensor));

		// 7. Main path Q1: first attention map, then subtract the lambda-scaled second map
		q1.add(scaledDotProductAttention(batchSize, seqLen, heads, dimHead, k1Tensor, vTensor));
		q1.add(layer("differentialCombine", headShape, headShape, map1 -> {
			CollectionProducer lambda = c(diffLambda).reshape(shape(heads))
					.repeat(1, seqLen)
					.repeat(2, dimHead)
					.repeat(0, batchSize);
			return c(map1).subtract(lambda.multiply(cp(map2Tensor)));
		}));

		// 8. Rearrange back to (batch, seqLen, dim) and apply the output projection
		q1.permute(0, 2, 1, 3)
				.reshape(batchSize, seqLen, dim);
		q1.add(projectionFactory.create(shape(batchSize, seqLen, dim), toOutWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));

		return attention;
	}

	/**
	 * Computes the differential attention lambda from its learned re-parameterization.
	 *
	 * <p>Following the <em>Differential Transformer</em> formulation, the per-head lambda is
	 * {@code λ = exp(λ_q1 · λ_k1) − exp(λ_q2 · λ_k2) + λ_init}, where each dot product is taken over
	 * the trailing dimension of the corresponding learned vector. The result is suitable for use as
	 * the {@code diffLambda} argument of {@link #differentialSequenceAttention}.</p>
	 *
	 * @param lambdaQ1   first query lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaK1   first key lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaQ2   second query lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaK2   second key lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaInit the depth-dependent initialization constant
	 * @return a producer of the per-head lambda, shape {@code [heads]}
	 */
	default Producer<PackedCollection> differentialLambda(
			PackedCollection lambdaQ1, PackedCollection lambdaK1,
			PackedCollection lambdaQ2, PackedCollection lambdaK2,
			double lambdaInit) {
		int heads = lambdaQ1.getShape().length(0);
		int lambdaDim = lambdaQ1.getShape().getTotalSize() / heads;

		CollectionProducer dot1 =
				multiply(traverseEach(cp(lambdaQ1)), traverseEach(cp(lambdaK1)))
						.reshape(heads, lambdaDim).traverse(1).sum();
		CollectionProducer dot2 =
				multiply(traverseEach(cp(lambdaQ2)), traverseEach(cp(lambdaK2)))
						.reshape(heads, lambdaDim).traverse(1).sum();

		return exp(dot1).subtract(exp(dot2)).add(c(lambdaInit)).reshape(shape(heads));
	}
}
