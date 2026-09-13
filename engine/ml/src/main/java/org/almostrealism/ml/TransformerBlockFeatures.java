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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

/**
 * Assembles complete pre-norm transformer blocks (self-attention, optional cross-attention and a
 * gated feed-forward, each in a residual branch) from the attention and feed-forward primitives of
 * {@link AttentionFeatures}.
 *
 * <p>Every overload delegates to the fully specified
 * {@link #transformerBlock} that accepts an attention variant, adaLN modulation, a per-position
 * additive conditioning and the normalization type, so all callers share one block assembly.</p>
 */
public interface TransformerBlockFeatures extends AttentionFeatures {
	/**
	 * Creates a complete transformer block with self-attention, optional cross-attention, and feed-forward.
	 * Simplified version without attention score capturing - delegates to the full transformerBlock method.
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases
	 * @param selfQkv Self-attention QKV projection weights
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias) {
		return transformerBlock(batchSize, dim, seqLen, heads, crossAttend,
				contextSeqLen, context,
				preNormWeight, preNormBias,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq,
				crossAttPreNormWeight, crossAttPreNormBias,
				crossWq, crossKv, crossWo,
				crossQNormWeight, crossQNormBias,
				crossKNormWeight, crossKNormBias,
				ffnNormWeight, ffnNormBias,
				w1, w2, w1Bias, w2Bias,
				null, ProjectionFactory.dense());
	}

	/**
	 * Creates a complete transformer block with self-attention, optional cross-attention, and feed-forward.
	 * This is the full version that supports capturing attention scores via a Receptor.
	 *
	 * <p>The transformer block structure:
	 * <pre>
	 * x = x + self_attn(norm(x))
	 * x = x + cross_attn(norm(x), context)  [optional]
	 * x = x + ffn(norm(x))
	 * </pre></p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases
	 * @param selfQkv Self-attention QKV projection weights
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @param attentionScores Optional receptor to capture cross-attention scores
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores) {
		return transformerBlock(batchSize, dim, seqLen, heads, crossAttend,
				contextSeqLen, context,
				preNormWeight, preNormBias,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq,
				crossAttPreNormWeight, crossAttPreNormBias,
				crossWq, crossKv, crossWo,
				crossQNormWeight, crossQNormBias,
				crossKNormWeight, crossKNormBias,
				ffnNormWeight, ffnNormBias,
				w1, w2, w1Bias, w2Bias,
				attentionScores, ProjectionFactory.dense());
	}

	/**
	 * Creates a complete transformer block with self-attention, optional cross-attention, and feed-forward.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection layers
	 * (QKV, output, FFN) are created. This enables LoRA (Low-Rank Adaptation) support
	 * without code duplication.</p>
	 *
	 * <p>The transformer block structure:
	 * <pre>
	 * x = x + self_attn(norm(x))
	 * x = x + cross_attn(norm(x), context)  [optional]
	 * x = x + ffn(norm(x))
	 * </pre></p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases
	 * @param selfQkv Self-attention QKV projection weights
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @param attentionScores Optional receptor to capture cross-attention scores
	 * @param projectionFactory Factory for creating projection layers (enables LoRA support)
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores,
								   ProjectionFactory projectionFactory) {
		return transformerBlock(batchSize, dim, seqLen, heads, crossAttend,
				contextSeqLen, context,
				preNormWeight, preNormBias,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq,
				crossAttPreNormWeight, crossAttPreNormBias,
				crossWq, crossKv, crossWo,
				crossQNormWeight, crossQNormBias,
				crossKNormWeight, crossKNormBias,
				ffnNormWeight, ffnNormBias,
				w1, w2, w1Bias, w2Bias,
				attentionScores, projectionFactory,
				AttentionVariant.STANDARD, null, null, null);
	}

	/**
	 * Creates a complete transformer block, selecting the self-attention implementation via an
	 * {@link AttentionVariant}.
	 *
	 * <p>This is the variant-aware base overload. All other {@code transformerBlock} overloads
	 * delegate here with {@link AttentionVariant#STANDARD} and a {@code null} lambda, so the
	 * standard scaled-dot-product path is byte-for-byte identical to the previous behaviour. The
	 * only difference from the standard path is that the self-attention sub-block is built through
	 * {@link #selfAttention} rather than {@link #sequenceAttention} directly, which routes the
	 * construction to the selected variant.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases
	 * @param selfQkv Self-attention fused projection weights (width depends on {@code variant})
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @param attentionScores Optional receptor to capture cross-attention scores
	 * @param projectionFactory Factory for creating projection layers (enables LoRA support)
	 * @param variant Attention variant for the self-attention sub-block
	 * @param diffLambda Learned lambda supplied to variants that require it (may be {@code null})
	 * @param modulation Optional packed adaLN modulation, shape {@code [batch, 6, dim]}, whose six
	 *                   {@code [batch, dim]} components are the raw scale/shift/gate for self-attention
	 *                   followed by the raw scale/shift/gate for the feed-forward; each sub-layer is
	 *                   applied as {@code x + sigmoid(1 - gate) * f((1 + scale) * norm(x) + shift)}.
	 *                   When {@code null} no modulation is applied and the block is the standard
	 *                   pre-norm residual block (the prepend path).
	 * @param localAddition Optional per-position additive conditioning, shape {@code [batch, seqLen, dim]},
	 *                      added to the hidden state after the attention sub-layers and before the
	 *                      feed-forward; {@code null} when absent.
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores,
								   ProjectionFactory projectionFactory,
								   AttentionVariant variant,
								   Producer<PackedCollection> diffLambda,
								   Producer<PackedCollection> modulation,
								   Producer<PackedCollection> localAddition) {
		return transformerBlock(batchSize, dim, seqLen, heads, crossAttend,
				contextSeqLen, context,
				preNormWeight, preNormBias,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq,
				crossAttPreNormWeight, crossAttPreNormBias,
				crossWq, crossKv, crossWo,
				crossQNormWeight, crossQNormBias,
				crossKNormWeight, crossKNormBias,
				ffnNormWeight, ffnNormBias,
				w1, w2, w1Bias, w2Bias,
				attentionScores, projectionFactory,
				variant, diffLambda, modulation, localAddition,
				NormalizationType.LAYER, null);
	}

	/**
	 * Creates a complete transformer block with every option: attention variant, adaLN modulation,
	 * per-position additive conditioning, the normalization family of the block's norms, and a
	 * padding mask.
	 *
	 * <p>This is the fully specified overload; every other {@code transformerBlock} routes here.
	 * The normalization family applies to the pre-attention, cross-attention and feed-forward
	 * norms and to the query/key norms inside self-attention (with {@link NormalizationType#RMS}
	 * the bias parameters may be {@code null}). The padding mask zeroes the self-attention value
	 * vectors at padded positions, which is the value-masking form of attention padding.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention layer
	 * @param contextSeqLen Context sequence length (for cross-attention)
	 * @param context Context input block (for cross-attention)
	 * @param preNormWeight Self-attention pre-normalization weights
	 * @param preNormBias Self-attention pre-normalization biases ({@code null} for none)
	 * @param selfQkv Self-attention fused projection weights (width depends on {@code variant})
	 * @param selfWo Self-attention output projection weights
	 * @param selfQNormWeight Self-attention Q normalization weights
	 * @param selfQNormBias Self-attention Q normalization biases ({@code null} for none)
	 * @param selfKNormWeight Self-attention K normalization weights
	 * @param selfKNormBias Self-attention K normalization biases ({@code null} for none)
	 * @param invFreq RoPE inverse frequencies
	 * @param crossAttPreNormWeight Cross-attention pre-normalization weights
	 * @param crossAttPreNormBias Cross-attention pre-normalization biases ({@code null} for none)
	 * @param crossWq Cross-attention Q projection weights
	 * @param crossKv Cross-attention KV projection weights
	 * @param crossWo Cross-attention output projection weights
	 * @param crossQNormWeight Cross-attention Q normalization weights
	 * @param crossQNormBias Cross-attention Q normalization biases
	 * @param crossKNormWeight Cross-attention K normalization weights
	 * @param crossKNormBias Cross-attention K normalization biases
	 * @param ffnNormWeight Feed-forward pre-normalization weights
	 * @param ffnNormBias Feed-forward pre-normalization biases ({@code null} for none)
	 * @param w1 Feed-forward gate projection weights
	 * @param w2 Feed-forward output projection weights
	 * @param w1Bias Feed-forward gate projection bias
	 * @param w2Bias Feed-forward output projection bias
	 * @param attentionScores Optional receptor to capture cross-attention scores
	 * @param projectionFactory Factory for creating projection layers (enables LoRA support)
	 * @param variant Attention variant for the self-attention sub-block
	 * @param diffLambda Learned lambda supplied to variants that require it (may be {@code null})
	 * @param modulation Optional packed adaLN modulation, shape {@code [batch, 6, dim]}, applied as
	 *                   {@code x + sigmoid(1 - gate) * f((1 + scale) * norm(x) + shift)} per sub-layer;
	 *                   {@code null} for the unmodulated pre-norm block
	 * @param localAddition Optional per-position additive conditioning, shape {@code [batch, seqLen, dim]},
	 *                      added after the attention sub-layers and before the feed-forward; {@code null} when absent
	 * @param normType Family of the block's normalization layers
	 * @param paddingMask Per-position validity for self-attention, shape {@code (batch, seqLen)} with
	 *                    one for a valid position and zero for padding, or {@code null} for no masking
	 * @return Complete transformer block
	 */
	default Block transformerBlock(int batchSize, int dim, int seqLen, int heads,
								   boolean crossAttend,
								   int contextSeqLen, Block context,
								   PackedCollection preNormWeight, PackedCollection preNormBias,
								   PackedCollection selfQkv, PackedCollection selfWo,
								   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
								   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
								   PackedCollection invFreq,
								   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
								   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
								   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
								   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
								   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
								   PackedCollection w1, PackedCollection w2,
								   PackedCollection w1Bias, PackedCollection w2Bias,
								   Receptor<PackedCollection> attentionScores,
								   ProjectionFactory projectionFactory,
								   AttentionVariant variant,
								   Producer<PackedCollection> diffLambda,
								   Producer<PackedCollection> modulation,
								   Producer<PackedCollection> localAddition,
								   NormalizationType normType,
								   Producer<PackedCollection> paddingMask) {
		TraversalPolicy blockShape = shape(batchSize, seqLen, dim);
		SequentialBlock block = new SequentialBlock(blockShape);

		Producer<PackedCollection> scaleSelf = modulation == null ? null : residualScale(modulationComponent(modulation, batchSize, dim, 0));
		Producer<PackedCollection> shiftSelf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 1);
		Producer<PackedCollection> gateSelf = modulation == null ? null : residualGate(modulationComponent(modulation, batchSize, dim, 2));
		Producer<PackedCollection> scaleFf = modulation == null ? null : residualScale(modulationComponent(modulation, batchSize, dim, 3));
		Producer<PackedCollection> shiftFf = modulation == null ? null : modulationComponent(modulation, batchSize, dim, 4);
		Producer<PackedCollection> gateFf = modulation == null ? null : residualGate(modulationComponent(modulation, batchSize, dim, 5));

		// Python: x = x + sigmoid(1 - gate_self) * self_attn((1 + scale_self) * pre_norm(x) + shift_self)
		SequentialBlock selfAttentionWithNorm = new SequentialBlock(blockShape);
		selfAttentionWithNorm.add(norm(normType, preNormWeight, preNormBias));
		if (modulation != null) {
			selfAttentionWithNorm.add(adaptiveModulate(blockShape, scaleSelf, shiftSelf));
		}
		selfAttentionWithNorm.add(selfAttention(
				batchSize, seqLen, dim, heads, variant,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq, diffLambda, projectionFactory, normType, paddingMask));
		if (modulation != null) {
			selfAttentionWithNorm.add(adaptiveGate(blockShape, gateSelf));
		}
		block.add(residual(selfAttentionWithNorm));

		// Python: x = x + cross_attn(cross_attend_norm(x)), never modulated
		if (crossAttend) {
			if (context == null) {
				throw new IllegalArgumentException("Context block cannot be null for cross-attention");
			}

			SequentialBlock crossAttentionWithNorm = new SequentialBlock(blockShape);
			crossAttentionWithNorm.add(norm(normType, crossAttPreNormWeight, crossAttPreNormBias));
			crossAttentionWithNorm.add(sequenceCrossAttention(
					batchSize, seqLen, contextSeqLen, dim, heads,
					crossWq, crossKv, crossWo,
					crossQNormWeight, crossQNormBias,
					crossKNormWeight, crossKNormBias,
					context, attentionScores, projectionFactory));
			block.add(residual(crossAttentionWithNorm));
		}

		if (localAddition != null) {
			block.add(layer("localAddCond", blockShape, blockShape, in -> add(c(in), c(localAddition))));
		}

		// Python: x = x + sigmoid(1 - gate_ff) * ff((1 + scale_ff) * ff_norm(x) + shift_ff)
		Block feedForward = gatedLinearFeedForward(block.getOutputShape(), normType,
				ffnNormWeight, ffnNormBias, w1, w1Bias, w2, w2Bias,
				scaleFf, shiftFf, silu(), projectionFactory);
		if (modulation == null) {
			block.add(residual(feedForward));
		} else {
			SequentialBlock gatedFeedForward = new SequentialBlock(block.getOutputShape());
			gatedFeedForward.add(feedForward);
			gatedFeedForward.add(adaptiveGate(blockShape, gateFf));
			block.add(residual(gatedFeedForward));
		}

		return block;
	}
}
