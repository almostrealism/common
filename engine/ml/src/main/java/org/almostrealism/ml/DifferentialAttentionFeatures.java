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

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.model.Block;

import java.util.Map;

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
 * evaluated as {@code map1·V − λ·(map2·V)}: each map is scaled dot-product attention, and the
 * existing output projection is applied to their lambda-weighted difference. With {@code λ = 0} the
 * result is exactly the standard scaled-dot-product attention over {@code [Q1, K1, V]}. The structure
 * is defined by the layers of {@link #DIFFERENTIAL_ATTENTION_ASSET}; the methods here bind it.</p>
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
	 * Classpath location of the asset describing differential self-attention: the
	 * {@code differential_attention} layers {@link #differentialSequenceAttention} builds, one for each
	 * query/key normalization, and the {@code attention_map} layers each of them runs twice. It imports
	 * {@link #SEQUENCE_ATTENTION_ASSET} for the per-head layout of the projected sections and the merge
	 * of the heads.
	 */
	String DIFFERENTIAL_ATTENTION_ASSET = "/pdsl/differential_attention.pdsl";

	/**
	 * {@inheritDoc}
	 *
	 * <p>Routes {@link AttentionVariant#DIFFERENTIAL} to {@link #differentialSequenceAttention} and
	 * defers every other variant (including {@link AttentionVariant#STANDARD}) to the base
	 * implementation, so the default path remains unchanged.</p>
	 *
	 * <p>The differential variant does not support causal masking, so requesting it with
	 * {@code causal = true} is rejected rather than silently building non-causal attention.</p>
	 *
	 * @throws UnsupportedOperationException if {@code variant} is
	 *         {@link AttentionVariant#DIFFERENTIAL} and {@code causal} is set
	 */
	@Override
	default Block selfAttention(int batchSize, int seqLen, int dim, int heads, AttentionVariant variant,
								PackedCollection toQkvWeight, PackedCollection toOutWeight,
								PackedCollection qNormWeight, PackedCollection qNormBias,
								PackedCollection kNormWeight, PackedCollection kNormBias, PackedCollection invFreq,
								Producer<PackedCollection> diffLambda, ProjectionFactory projectionFactory,
								NormalizationType qkNorm, Producer<PackedCollection> paddingMask,
								boolean causal) {
		if (variant == AttentionVariant.DIFFERENTIAL) {
			if (causal) {
				throw new UnsupportedOperationException(
						"Causal masking is not supported by differential attention");
			}

			return differentialSequenceAttention(batchSize, seqLen, dim, heads,
					toQkvWeight, toOutWeight,
					qNormWeight, qNormBias, kNormWeight, kNormBias,
					invFreq, diffLambda, projectionFactory, qkNorm, paddingMask);
		}

		return AttentionFeatures.super.selfAttention(batchSize, seqLen, dim, heads, variant,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, diffLambda, projectionFactory, qkNorm, paddingMask, causal);
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
	 * Builds a differential self-attention block with LayerNorm query/key normalization (when its
	 * weights are given) and no padding mask.
	 *
	 * <p>The block maps {@code [batch, seqLen, dim]} to {@code [batch, seqLen, dim]}. The fused
	 * projection produces five equal sections {@code [Q1, K1, K2, V, Q2]}; the second attention map
	 * ({@code Q2} over {@code K2}) is split off as a branch and weighted by {@code -λ}, and the main
	 * path forms the first map ({@code Q1} over {@code K1}) and adds the weighted second map to it.
	 * RoPE and QK-normalization are applied to the queries and keys exactly as in
	 * {@link AttentionFeatures#sequenceAttention}.</p>
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
		return differentialSequenceAttention(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, diffLambda, projectionFactory, NormalizationType.LAYER, null);
	}

	/**
	 * Builds a differential self-attention block with a selectable query/key normalization family
	 * and an optional padding mask; this is the fully specified overload the others route to.
	 * The mask zeroes the shared value vectors at padded positions, and the query/key normalization
	 * is skipped when neither of its weights is supplied, exactly as
	 * {@link AttentionFeatures#sequenceAttention} does for standard attention.
	 *
	 * <p>The structure is not assembled here: it is the {@code differential_attention} layer of
	 * {@link #DIFFERENTIAL_ATTENTION_ASSET} for the requested query/key normalization. This method
	 * binds the arguments every self-attention layer takes (see {@link #sequenceAttentionArguments},
	 * which builds the two projections with {@code projectionFactory}) and the lambda, and builds that
	 * layer. Every section of the fused projection reaches the output through the graph — the keys and
	 * values of each map and the second map itself are branches that the products and the sum read as
	 * operands — so a backward pass delivers gradients to all five sections.</p>
	 *
	 * <p>The lambda given here is applied as a fixed factor: whatever {@code diffLambda} is built from
	 * receives no gradient. To train a learned lambda, pass the four vectors it is built from to the
	 * overload that takes {@code lambdaQ1}, {@code lambdaK1}, {@code lambdaQ2}, {@code lambdaK2} and
	 * {@code lambdaInit}.</p>
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param toQkvWeight       fused {@code dim*5} projection weights ({@code [Q1, K1, K2, V, Q2]})
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights ({@code null} for no query/key normalization)
	 * @param qNormBias         query normalization biases ({@code null} for none)
	 * @param kNormWeight       key normalization weights ({@code null} exactly when {@code qNormWeight} is)
	 * @param kNormBias         key normalization biases ({@code null} for none)
	 * @param invFreq           RoPE inverse frequencies
	 * @param diffLambda        per-head lambda, applied as a fixed factor (shape {@code [heads]})
	 * @param projectionFactory factory for creating projection layers
	 * @param qkNorm            family of the query/key normalization
	 * @param paddingMask       per-position validity, shape {@code (batch, seqLen)}, or
	 *                          {@code null} for no masking
	 * @return the differential self-attention block
	 * @throws IllegalArgumentException if {@code diffLambda} is {@code null} or does not hold one value
	 *                                  per head, or only one of {@code qNormWeight} and
	 *                                  {@code kNormWeight} is supplied
	 * @throws UnsupportedOperationException if {@code batchSize} is not 1
	 */
	default Block differentialSequenceAttention(int batchSize, int seqLen, int dim, int heads,
												PackedCollection toQkvWeight, PackedCollection toOutWeight,
												PackedCollection qNormWeight, PackedCollection qNormBias,
												PackedCollection kNormWeight, PackedCollection kNormBias,
												PackedCollection invFreq,
												Producer<PackedCollection> diffLambda,
												ProjectionFactory projectionFactory,
												NormalizationType qkNorm,
												Producer<PackedCollection> paddingMask) {
		return differentialSequenceAttention(batchSize, seqLen, dim, heads, toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias, invFreq, diffLambda,
				null, null, null, null, projectionFactory, qkNorm, paddingMask);
	}

	/**
	 * Builds a differential self-attention block whose per-head lambda is a learned
	 * re-parameterization and <em>trains</em>: the four vectors it is built from receive the gradient
	 * a backward pass implies, so a model fine-tuning a differential-attention block learns how much
	 * of each head's second map to subtract.
	 *
	 * <p>The lambda is {@link #differentialLambda(PackedCollection, PackedCollection, PackedCollection,
	 * PackedCollection, double) differentialLambda}{@code (λ_q1, λ_k1, λ_q2, λ_k2, λ_init)}; the four
	 * vectors are registered as the weights of the lambda-scaling stage, which is what carries the
	 * gradient back to them. The forward pass is identical to passing the same lambda as a fixed
	 * producer to the {@link #differentialSequenceAttention(int, int, int, int, PackedCollection,
	 * PackedCollection, PackedCollection, PackedCollection, PackedCollection, PackedCollection,
	 * PackedCollection, Producer, ProjectionFactory, NormalizationType, Producer) producer overload};
	 * only the backward pass differs, delivering a gradient to {@code λ_q1}, {@code λ_k1}, {@code λ_q2}
	 * and {@code λ_k2} instead of treating them as constants. With a lambda of zero the block still
	 * reduces to standard scaled-dot-product attention over {@code [Q1, K1, V]}.</p>
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param toQkvWeight       fused {@code dim*5} projection weights ({@code [Q1, K1, K2, V, Q2]})
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights ({@code null} for no query/key normalization)
	 * @param qNormBias         query normalization biases ({@code null} for none)
	 * @param kNormWeight       key normalization weights ({@code null} exactly when {@code qNormWeight} is)
	 * @param kNormBias         key normalization biases ({@code null} for none)
	 * @param invFreq           RoPE inverse frequencies
	 * @param lambdaQ1          first query lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaK1          first key lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaQ2          second query lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaK2          second key lambda vectors, shape {@code [heads, lambdaDim]}
	 * @param lambdaInit        the depth-dependent initialization constant
	 * @param projectionFactory factory for creating projection layers
	 * @param qkNorm            family of the query/key normalization
	 * @param paddingMask       per-position validity, shape {@code (batch, seqLen)}, or
	 *                          {@code null} for no masking
	 * @return the differential self-attention block
	 * @throws IllegalArgumentException if the lambda vectors do not hold one lambda per head, or only
	 *                                  one of {@code qNormWeight} and {@code kNormWeight} is supplied
	 * @throws UnsupportedOperationException if {@code batchSize} is not 1
	 */
	default Block differentialSequenceAttention(int batchSize, int seqLen, int dim, int heads,
												PackedCollection toQkvWeight, PackedCollection toOutWeight,
												PackedCollection qNormWeight, PackedCollection qNormBias,
												PackedCollection kNormWeight, PackedCollection kNormBias,
												PackedCollection invFreq,
												PackedCollection lambdaQ1, PackedCollection lambdaK1,
												PackedCollection lambdaQ2, PackedCollection lambdaK2,
												double lambdaInit,
												ProjectionFactory projectionFactory,
												NormalizationType qkNorm,
												Producer<PackedCollection> paddingMask) {
		Producer<PackedCollection> diffLambda =
				differentialLambda(lambdaQ1, lambdaK1, lambdaQ2, lambdaK2, lambdaInit);
		return differentialSequenceAttention(batchSize, seqLen, dim, heads, toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias, invFreq, diffLambda,
				lambdaQ1, lambdaK1, lambdaQ2, lambdaK2, projectionFactory, qkNorm, paddingMask);
	}

	/**
	 * The single build path both lambda forms route to: it binds every self-attention argument, the
	 * forward lambda producer, and the four vectors the lambda is built from (any of which may be
	 * {@code null}), and builds the {@code differential_attention} layer for the requested query/key
	 * normalization. When the vectors are supplied they are bound as the weights of the lambda-scaling
	 * stage, so the stage differentiates the lambda with respect to them; when they are {@code null}
	 * the stage scales by the lambda without registering a weight, which is how a caller applies a
	 * fixed, untrained lambda. The forward pass is the same either way.
	 *
	 * @param batchSize         batch dimension
	 * @param seqLen            sequence length
	 * @param dim               model dimension
	 * @param heads             number of attention heads
	 * @param toQkvWeight       fused {@code dim*5} projection weights ({@code [Q1, K1, K2, V, Q2]})
	 * @param toOutWeight       output projection weights
	 * @param qNormWeight       query normalization weights ({@code null} for no query/key normalization)
	 * @param qNormBias         query normalization biases ({@code null} for none)
	 * @param kNormWeight       key normalization weights ({@code null} exactly when {@code qNormWeight} is)
	 * @param kNormBias         key normalization biases ({@code null} for none)
	 * @param invFreq           RoPE inverse frequencies
	 * @param diffLambda        the per-head lambda forward producer (shape {@code [heads]})
	 * @param lambdaQ1          first query lambda vectors to register as weights, or {@code null}
	 * @param lambdaK1          first key lambda vectors to register as weights, or {@code null}
	 * @param lambdaQ2          second query lambda vectors to register as weights, or {@code null}
	 * @param lambdaK2          second key lambda vectors to register as weights, or {@code null}
	 * @param projectionFactory factory for creating projection layers
	 * @param qkNorm            family of the query/key normalization
	 * @param paddingMask       per-position validity, or {@code null} for no masking
	 * @return the differential self-attention block
	 * @throws IllegalArgumentException if {@code diffLambda} is {@code null} or does not hold one value
	 *                                  per head, or only one of {@code qNormWeight} and
	 *                                  {@code kNormWeight} is supplied
	 * @throws UnsupportedOperationException if {@code batchSize} is not 1
	 */
	private Block differentialSequenceAttention(int batchSize, int seqLen, int dim, int heads,
												PackedCollection toQkvWeight, PackedCollection toOutWeight,
												PackedCollection qNormWeight, PackedCollection qNormBias,
												PackedCollection kNormWeight, PackedCollection kNormBias,
												PackedCollection invFreq,
												Producer<PackedCollection> diffLambda,
												PackedCollection lambdaQ1, PackedCollection lambdaK1,
												PackedCollection lambdaQ2, PackedCollection lambdaK2,
												ProjectionFactory projectionFactory,
												NormalizationType qkNorm,
												Producer<PackedCollection> paddingMask) {
		if (diffLambda == null) {
			throw new IllegalArgumentException("Differential attention requires a lambda producer");
		}
		if (shape(diffLambda).getTotalSize() != heads) {
			throw new IllegalArgumentException("Differential attention requires one lambda per head ("
					+ heads + "), got " + shape(diffLambda));
		}

		Map<String, Object> args = sequenceAttentionArguments(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight, qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, projectionFactory, paddingMask, null);
		args.put("lambda", diffLambda);
		args.put("lambda_q1", lambdaQ1);
		args.put("lambda_k1", lambdaK1);
		args.put("lambda_q2", lambdaQ2);
		args.put("lambda_k2", lambdaK2);

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(DIFFERENTIAL_ATTENTION_ASSET),
				"differential_attention" + queryKeyNormalization(qNormWeight, qkNorm),
				shape(batchSize, seqLen, dim), args);
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
