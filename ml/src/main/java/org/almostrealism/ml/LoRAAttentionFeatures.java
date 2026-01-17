/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.LoRAFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.List;

/**
 * Provides LoRA-aware versions of attention mechanisms.
 *
 * <p>This interface extends both {@link AttentionFeatures} and {@link LoRAFeatures}
 * to provide attention blocks where the linear projections (Q, K, V, output) can be
 * optionally wrapped with LoRA adapters based on the {@link AdapterConfig}.</p>
 *
 * <h2>Which Layers Are Wrapped</h2>
 * <p>Based on the {@link AdapterConfig}, the following projections can have LoRA:</p>
 * <ul>
 *   <li><b>SELF_ATTENTION_QKV</b>: The fused Q/K/V projection in self-attention</li>
 *   <li><b>SELF_ATTENTION_OUT</b>: The output projection in self-attention</li>
 *   <li><b>CROSS_ATTENTION_Q</b>: The Q projection in cross-attention</li>
 *   <li><b>CROSS_ATTENTION_KV</b>: The fused K/V projection in cross-attention</li>
 *   <li><b>CROSS_ATTENTION_OUT</b>: The output projection in cross-attention</li>
 *   <li><b>FFN_GATE</b>: The gate/up projection in feed-forward layers</li>
 *   <li><b>FFN_OUT</b>: The output projection in feed-forward layers</li>
 * </ul>
 *
 * @see AttentionFeatures
 * @see LoRAFeatures
 * @see AdapterConfig
 * @author Michael Murray
 */
public interface LoRAAttentionFeatures extends AttentionFeatures, LoRAFeatures {

	/**
	 * Creates a sequence self-attention block with optional LoRA on projections.
	 *
	 * <p>This is a LoRA-aware version of {@link #sequenceAttention}. The QKV and
	 * output projections will be wrapped with LoRA if configured.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQkvWeight Fused QKV projection weights [dim*3, dim]
	 * @param toOutWeight Output projection weights [dim, dim]
	 * @param qNormWeight Q normalization weights
	 * @param qNormBias Q normalization bias
	 * @param kNormWeight K normalization weights
	 * @param kNormBias K normalization bias
	 * @param invFreq RoPE inverse frequencies
	 * @return Self-attention block with optional LoRA adapters
	 */
	default Block loraSequenceAttention(int batchSize, int seqLen, int dim, int heads,
										PackedCollection toQkvWeight, PackedCollection toOutWeight,
										PackedCollection qNormWeight, PackedCollection qNormBias,
										PackedCollection kNormWeight, PackedCollection kNormBias,
										PackedCollection invFreq) {
		int dimHead = dim / heads;

		SequentialBlock attention = new SequentialBlock(shape(batchSize, seqLen, dim));

		// 1. Fused QKV projection - potentially with LoRA
		CellularLayer qkvLayer = loraOrDense(
				shape(batchSize, seqLen, dim),
				toQkvWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_QKV
		);
		attention.add(qkvLayer);

		// 2. Split QKV and reshape to multi-head format
		attention.reshape(batchSize, seqLen, 3, dim);
		List<Block> qkv = attention.split(shape(batchSize, seqLen, 1, dim), 0);
		SequentialBlock q = (SequentialBlock) qkv.get(0).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock k = (SequentialBlock) qkv.get(1).reshape(batchSize, seqLen, heads, dimHead);
		SequentialBlock v = (SequentialBlock) qkv.get(2).reshape(batchSize, seqLen, heads, dimHead);

		// 3. Permute to (batch, heads, seqLen, dimHead)
		q.permute(0, 2, 1, 3);
		k.permute(0, 2, 1, 3);
		v.permute(0, 2, 1, 3);

		// 4. Apply QK normalization
		q.add(norm(qNormWeight, qNormBias, 1e-6));
		k.add(norm(kNormWeight, kNormBias, 1e-6));

		// 5. Apply rotary embeddings to Q and K
		q.add(applyRotaryPositionEmbedding(shape(batchSize, heads, seqLen, dimHead), invFreq));
		k.add(applyRotaryPositionEmbedding(shape(batchSize, heads, seqLen, dimHead), invFreq));

		// 6. Store K and V tensors for use in attention computation
		PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, seqLen, dimHead));
		PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, seqLen, dimHead));

		k.andThen(into(kTensor));
		v.andThen(into(vTensor));

		// 7. Compute scaled dot-product attention using stored tensors
		q.add(scaledDotProductAttention(batchSize, seqLen, heads, dimHead, kTensor, vTensor));

		// Rearrange back to (batch, seqLen, dim)
		q.permute(0, 2, 1, 3)
				.reshape(batchSize, seqLen, dim);

		// 8. Output projection - potentially with LoRA
		CellularLayer outLayer = loraOrDense(
				shape(batchSize, seqLen, dim),
				toOutWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_OUT
		);
		q.add(outLayer);

		return attention;
	}

	/**
	 * Creates a cross-attention block with optional LoRA on projections.
	 *
	 * <p>This is a LoRA-aware version of cross-attention. The Q, KV, and
	 * output projections will be wrapped with LoRA if configured.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Query sequence length
	 * @param contextSeqLen Context (key/value) sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQWeight Q projection weights [dim, dim]
	 * @param toKvWeight Fused KV projection weights [dim*2, dim]
	 * @param toOutWeight Output projection weights [dim, dim]
	 * @param qNormWeight Q normalization weights
	 * @param qNormBias Q normalization bias
	 * @param kNormWeight K normalization weights
	 * @param kNormBias K normalization bias
	 * @param context Context input block
	 * @param attentionScores Optional receptor to capture attention scores
	 * @return Cross-attention block with optional LoRA adapters
	 */
	default Block loraSequenceCrossAttention(int batchSize, int seqLen, int contextSeqLen,
											 int dim, int heads,
											 PackedCollection toQWeight,
											 PackedCollection toKvWeight,
											 PackedCollection toOutWeight,
											 PackedCollection qNormWeight, PackedCollection qNormBias,
											 PackedCollection kNormWeight, PackedCollection kNormBias,
											 Block context,
											 Receptor<PackedCollection> attentionScores) {
		int dimHead = dim / heads;

		SequentialBlock attention = new SequentialBlock(shape(batchSize, seqLen, dim));

		// Q projection from main input - potentially with LoRA
		CellularLayer qLayer = loraOrDense(
				shape(batchSize, seqLen, dim),
				toQWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_Q
		);
		attention.add(qLayer);

		// Reshape Q for multi-head attention
		attention.reshape(batchSize, seqLen, heads, dimHead)
				.permute(0, 2, 1, 3);

		// Q normalization
		attention.add(norm(qNormWeight, qNormBias, 1e-6));

		// Process context for K, V
		SequentialBlock contextBlock = new SequentialBlock(context.getOutputShape());

		// KV projection from context - potentially with LoRA
		CellularLayer kvLayer = loraOrDense(
				context.getOutputShape(),
				toKvWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_KV
		);
		contextBlock.add(kvLayer);

		// Split KV
		contextBlock.reshape(batchSize, contextSeqLen, 2, dim);
		List<Block> kv = contextBlock.split(shape(batchSize, contextSeqLen, 1, dim), 0);
		SequentialBlock k = (SequentialBlock) kv.get(0).reshape(batchSize, contextSeqLen, heads, dimHead);
		SequentialBlock v = (SequentialBlock) kv.get(1).reshape(batchSize, contextSeqLen, heads, dimHead);

		// Permute K, V
		k.permute(0, 2, 1, 3);
		v.permute(0, 2, 1, 3);

		// K normalization
		k.add(norm(kNormWeight, kNormBias, 1e-6));

		// Store K, V for attention computation
		PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
		PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));

		// Connect context to K, V processing
		context.andThen(contextBlock.getForward());
		k.andThen(into(kTensor));
		v.andThen(into(vTensor));

		// Scaled dot-product cross-attention (no causal mask)
		attention.add(scaledDotProductAttention(
				batchSize, seqLen, contextSeqLen, heads, dimHead,
				kTensor, vTensor, attentionScores));

		// Rearrange back
		attention.permute(0, 2, 1, 3)
				.reshape(batchSize, seqLen, dim);

		// Output projection - potentially with LoRA
		CellularLayer outLayer = loraOrDense(
				shape(batchSize, seqLen, dim),
				toOutWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_OUT
		);
		attention.add(outLayer);

		return attention;
	}

	/**
	 * Creates a gated linear feed-forward block with optional LoRA.
	 *
	 * <p>This is a LoRA-aware version of the feed-forward network.
	 * The gate and output projections can be wrapped with LoRA if configured.</p>
	 *
	 * @param inputShape Input shape
	 * @param normWeight Normalization weights
	 * @param normBias Normalization bias
	 * @param w1 Gate projection weights [hiddenDim*2, dim] (for SwiGLU-style gating)
	 * @param w1Bias Gate projection bias
	 * @param w2 Output projection weights [dim, hiddenDim]
	 * @param w2Bias Output projection bias
	 * @return Feed-forward block with optional LoRA adapters
	 */
	default Block loraGatedLinearFeedForward(io.almostrealism.collect.TraversalPolicy inputShape,
											 PackedCollection normWeight, PackedCollection normBias,
											 PackedCollection w1, PackedCollection w1Bias,
											 PackedCollection w2, PackedCollection w2Bias) {
		SequentialBlock ffn = new SequentialBlock(inputShape);

		// Pre-normalization
		ffn.add(norm(normWeight, normBias));

		// Gate projection - potentially with LoRA
		CellularLayer gateLayer = loraOrDense(
				ffn.getOutputShape(),
				w1,
				w1Bias,
				AdapterConfig.TargetLayer.FFN_GATE
		);
		ffn.add(gateLayer);

		// Split into gate and up projections, apply SwiGLU gating
		// Following the same pattern as gatedLinear in AttentionFeatures
		List<Block> split = ffn.split(2, ffn.getOutputShape().getDimensions() - 1, 0);
		Block gate = split.get(1).andThen(silu());

		// Multiply linear output with gated branch
		ffn.add(product(gate));

		// Output projection - potentially with LoRA
		CellularLayer outLayer = loraOrDense(
				ffn.getOutputShape(),
				w2,
				w2Bias,
				AdapterConfig.TargetLayer.FFN_OUT
		);
		ffn.add(outLayer);

		return ffn;
	}

	/**
	 * Creates a complete transformer block with optional LoRA on all projections.
	 *
	 * <p>This combines self-attention (with optional cross-attention) and feed-forward,
	 * with LoRA adapters applied according to the configuration.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param dim Model dimension
	 * @param seqLen Sequence length
	 * @param heads Number of attention heads
	 * @param crossAttend Whether to include cross-attention
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
	 * @return Complete transformer block with optional LoRA adapters
	 */
	default Block loraTransformerBlock(int batchSize, int dim, int seqLen, int heads,
									   boolean crossAttend,
									   int contextSeqLen, Block context,
									   // Self-attention weights
									   PackedCollection preNormWeight, PackedCollection preNormBias,
									   PackedCollection selfQkv, PackedCollection selfWo,
									   PackedCollection selfQNormWeight, PackedCollection selfQNormBias,
									   PackedCollection selfKNormWeight, PackedCollection selfKNormBias,
									   PackedCollection invFreq,
									   // Cross-attention weights
									   PackedCollection crossAttPreNormWeight, PackedCollection crossAttPreNormBias,
									   PackedCollection crossWq, PackedCollection crossKv, PackedCollection crossWo,
									   PackedCollection crossQNormWeight, PackedCollection crossQNormBias,
									   PackedCollection crossKNormWeight, PackedCollection crossKNormBias,
									   // Feed-forward weights
									   PackedCollection ffnNormWeight, PackedCollection ffnNormBias,
									   PackedCollection w1, PackedCollection w2,
									   PackedCollection w1Bias, PackedCollection w2Bias,
									   Receptor<PackedCollection> attentionScores) {
		SequentialBlock block = new SequentialBlock(shape(batchSize, seqLen, dim));

		// Self-attention with pre-normalization inside residual branch
		SequentialBlock selfAttentionWithNorm = new SequentialBlock(shape(batchSize, seqLen, dim));
		selfAttentionWithNorm.add(norm(preNormWeight, preNormBias));
		selfAttentionWithNorm.add(loraSequenceAttention(
				batchSize, seqLen, dim, heads,
				selfQkv, selfWo,
				selfQNormWeight, selfQNormBias,
				selfKNormWeight, selfKNormBias,
				invFreq));
		block.add(residual(selfAttentionWithNorm));

		// Cross-attention with pre-normalization inside residual branch (if needed)
		if (crossAttend) {
			if (context == null) {
				throw new IllegalArgumentException("Context block cannot be null for cross-attention");
			}

			SequentialBlock crossAttentionWithNorm = new SequentialBlock(shape(batchSize, seqLen, dim));
			crossAttentionWithNorm.add(norm(crossAttPreNormWeight, crossAttPreNormBias));
			crossAttentionWithNorm.add(loraSequenceCrossAttention(
					batchSize, seqLen, contextSeqLen, dim, heads,
					crossWq, crossKv, crossWo,
					crossQNormWeight, crossQNormBias,
					crossKNormWeight, crossKNormBias,
					context, attentionScores));
			block.add(residual(crossAttentionWithNorm));
		}

		// Feed-forward with normalization inside residual branch
		block.add(residual(loraGatedLinearFeedForward(block.getOutputShape(),
				ffnNormWeight, ffnNormBias, w1, w1Bias, w2, w2Bias)));

		return block;
	}
}
