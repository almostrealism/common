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
import org.almostrealism.graph.Receptor;
import org.almostrealism.layers.AdapterConfig;
import org.almostrealism.layers.NormalizationType;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.SequentialBlock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-sequence multi-head attention: self-attention over a whole sequence with a fused QKV
 * projection, cross-attention over an external context, and the scaled dot-product attention
 * core they share, including its additive logit masks (per-key masking and causal masking).
 *
 * <p>Unlike the single-position attention in {@link AttentionFeatures}, which reads keys and
 * values from a KV cache, these blocks process every position of the sequence in one pass, which
 * is what training and non-autoregressive inference need.</p>
 *
 * @see AttentionFeatures
 */
public interface SequenceAttentionFeatures extends RotationFeatures, FeedForwardFeatures {

	/**
	 * Magnitude of the negative bias added to the attention logit of a masked key, large enough
	 * that the key's softmax weight underflows to zero in single precision.
	 */
	double MASKED_LOGIT_PENALTY = 1e9;

	/**
	 * Classpath location of the asset describing parallel (full-sequence) scaled dot-product
	 * attention: the score layers ({@code sdpa_scores}, with soft-capped and causal forms) and the
	 * {@code sdpa_context} layer that the {@link #scaledDotProductAttention} overloads build and the
	 * layers of {@link #SEQUENCE_ATTENTION_ASSET} call. It is the full-sequence counterpart of
	 * {@link AttentionFeatures#ATTENTION_ASSET}'s autoregressive {@code attend} layer.
	 */
	String SDPA_ASSET = "/pdsl/sdpa.pdsl";

	/**
	 * Classpath location of the asset describing parallel (full-sequence) self-attention: the
	 * {@code sequence_attention} layers {@link #sequenceAttention} builds, one for each combination
	 * of query/key normalization, logit soft-capping and causal masking. It imports
	 * {@link #SDPA_ASSET} for the score and context halves.
	 */
	String SEQUENCE_ATTENTION_ASSET = "/pdsl/sequence_attention.pdsl";

	/**
	 * Creates a sequence-based multi-head attention block with fused QKV projection.
	 *
	 * <p>This implements full-sequence attention (not autoregressive) with:
	 * <ul>
	 *   <li>Fused QKV projection for efficiency</li>
	 *   <li>QK normalization for stability</li>
	 *   <li>Rotary positional embeddings (RoPE)</li>
	 *   <li>Scaled dot-product attention</li>
	 * </ul></p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQkvWeight Fused QKV projection weights
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @return Sequence attention block
	 */
	default Block sequenceAttention(int batchSize, int seqLen, int dim, int heads,
									PackedCollection toQkvWeight, PackedCollection toOutWeight,
									PackedCollection qNormWeight, PackedCollection qNormBias,
									PackedCollection kNormWeight, PackedCollection kNormBias,
									PackedCollection invFreq) {
		return sequenceAttention(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, ProjectionFactory.dense());
	}

	/**
	 * Creates a sequence-based multi-head attention block with fused QKV projection
	 * and customizable projection layers.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection
	 * layers are created, enabling LoRA or other adapter patterns without code duplication.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQkvWeight Fused QKV projection weights
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param invFreq RoPE inverse frequencies
	 * @param projectionFactory Factory for creating projection layers
	 * @return Sequence attention block
	 */
	default Block sequenceAttention(int batchSize, int seqLen, int dim, int heads,
									PackedCollection toQkvWeight, PackedCollection toOutWeight,
									PackedCollection qNormWeight, PackedCollection qNormBias,
									PackedCollection kNormWeight, PackedCollection kNormBias,
									PackedCollection invFreq,
									ProjectionFactory projectionFactory) {
		return sequenceAttention(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, projectionFactory, NormalizationType.LAYER, null, null, 0.0);
	}

	/**
	 * Creates a sequence-based multi-head attention block with a selectable query/key
	 * normalization family, an optional value padding mask, an optional key mask and optional
	 * logit soft-capping.
	 *
	 * <p>This is the fully specified overload; every other {@code sequenceAttention} routes here
	 * with {@link NormalizationType#LAYER}, no masks and no soft-cap. The two masks serve two
	 * conventions for padded sequences: the padding mask zeroes the value vectors at masked
	 * positions so they contribute nothing to any output while still occupying their softmax
	 * slots (value masking), whereas the key mask removes masked keys from the softmax entirely
	 * (logit masking). Query/key normalization is skipped when {@code qNormWeight} is
	 * {@code null}.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQkvWeight Fused QKV projection weights
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights ({@code null} for no query/key normalization)
	 * @param qNormBias Query normalization biases ({@code null} for none)
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases ({@code null} for none)
	 * @param invFreq RoPE inverse frequencies
	 * @param projectionFactory Factory for creating projection layers
	 * @param qkNorm Family of the query/key normalization
	 * @param paddingMask Per-position validity, shape {@code (batch, seqLen)} with one for a
	 *                    valid position and zero for padding, or {@code null} for no value masking
	 * @param keyMask Per-key validity, shape {@code (batch, seqLen)}, or {@code null} for no
	 *                logit masking
	 * @param logitSoftcap Soft-cap applied to the scaled attention logits, or {@code 0} for none
	 * @return Sequence attention block
	 */
	default Block sequenceAttention(int batchSize, int seqLen, int dim, int heads,
									PackedCollection toQkvWeight, PackedCollection toOutWeight,
									PackedCollection qNormWeight, PackedCollection qNormBias,
									PackedCollection kNormWeight, PackedCollection kNormBias,
									PackedCollection invFreq,
									ProjectionFactory projectionFactory,
									NormalizationType qkNorm,
									Producer<PackedCollection> paddingMask,
									Producer<PackedCollection> keyMask,
									double logitSoftcap) {
		return sequenceAttention(batchSize, seqLen, dim, heads,
				toQkvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				invFreq, projectionFactory, qkNorm, paddingMask, keyMask, logitSoftcap, false);
	}

	/**
	 * Creates a sequence-based multi-head attention block with every option, including causal
	 * masking.
	 *
	 * <p>This is the fully specified overload; every other {@code sequenceAttention} routes here.
	 * The structure is not assembled here: it is a layer of {@link #SEQUENCE_ATTENTION_ASSET}, in
	 * which the fused projection output is split into query, key and value parts, the key and value
	 * parts become branches and the query part continues as the main path, which is permuted to
	 * {@code (batch, heads, seqLen, dimHead)}, normalized (when {@code qNormWeight} is given),
	 * rotated, attended over the key and value branches, permuted back and projected. This method
	 * builds the two projections with {@code projectionFactory}, binds them with the weights and the
	 * masks, and builds the layer for the requested query/key normalization, soft-cap and causal
	 * masking. A missing mask is bound as an all-ones mask, which leaves every value and every logit
	 * unchanged.</p>
	 *
	 * <p>When {@code causal} is set, position {@code i} attends only to keys {@code j <= i}: the
	 * {@link #causalLogitMask lower-triangular logit mask} is added to the attention logits before
	 * the softmax over key positions, alongside the key mask, so a key is attended only when it is
	 * both causally allowed and unmasked.</p>
	 *
	 * <p>The key and value branches are the second operands of the two attention products rather
	 * than host-side copies, so the backward pass delivers gradients to the query, key and value
	 * slices of {@code toQkvWeight} alike.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen Sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQkvWeight Fused QKV projection weights, shape {@code (3 * dim, dim)}
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights ({@code null} for no query/key normalization)
	 * @param qNormBias Query normalization biases ({@code null} for none)
	 * @param kNormWeight Key normalization weights ({@code null} exactly when {@code qNormWeight} is)
	 * @param kNormBias Key normalization biases ({@code null} for none)
	 * @param invFreq RoPE inverse frequencies
	 * @param projectionFactory Factory for creating projection layers
	 * @param qkNorm Family of the query/key normalization
	 * @param paddingMask Per-position validity, shape {@code (batch, seqLen)}, or {@code null}
	 * @param keyMask Per-key validity, shape {@code (batch, seqLen)}, or {@code null}
	 * @param logitSoftcap Soft-cap applied to the scaled attention logits, or {@code 0} for none
	 * @param causal Whether each position may attend only to itself and earlier positions
	 * @return Sequence attention block
	 * @throws IllegalArgumentException if only one of {@code qNormWeight} and {@code kNormWeight}
	 *                                  is supplied
	 * @throws UnsupportedOperationException if {@code batchSize} is not 1
	 */
	default Block sequenceAttention(int batchSize, int seqLen, int dim, int heads,
									PackedCollection toQkvWeight, PackedCollection toOutWeight,
									PackedCollection qNormWeight, PackedCollection qNormBias,
									PackedCollection kNormWeight, PackedCollection kNormBias,
									PackedCollection invFreq,
									ProjectionFactory projectionFactory,
									NormalizationType qkNorm,
									Producer<PackedCollection> paddingMask,
									Producer<PackedCollection> keyMask,
									double logitSoftcap,
									boolean causal) {
		if ((qNormWeight == null) != (kNormWeight == null)) {
			throw new IllegalArgumentException("QK-Norm requires both query and key weights");
		}
		if (batchSize != 1) {
			throw new UnsupportedOperationException("Batches of more than 1 are not currently supported");
		}

		TraversalPolicy inputShape = shape(batchSize, seqLen, dim);
		Map<String, Object> args = new HashMap<>();
		args.put("batch", batchSize);
		args.put("seq_len", seqLen);
		args.put("heads", heads);
		args.put("head_dim", dim / heads);
		args.put("qkv_projection", projectionFactory.create(inputShape, toQkvWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_QKV));
		args.put("out_projection", projectionFactory.create(inputShape, toOutWeight,
				AdapterConfig.TargetLayer.SELF_ATTENTION_OUT));
		args.put("q_norm_weight", qNormWeight);
		args.put("q_norm_bias", qNormBias);
		args.put("k_norm_weight", kNormWeight);
		args.put("k_norm_bias", kNormBias);
		args.put("inv_freq", invFreq);
		args.put("padding_mask", paddingMask != null ? paddingMask : everyPositionValid(batchSize, seqLen));
		args.put("key_mask", keyMask != null ? keyMask : everyPositionValid(batchSize, seqLen));
		args.put("softcap", logitSoftcap);

		String normalization = qNormWeight == null ? ""
				: qkNorm == NormalizationType.RMS ? "_qk_rmsnorm" : "_qk_layernorm";
		String layer = "sequence_attention" + normalization
				+ (logitSoftcap > 0.0 ? "_softcapped" : "") + (causal ? "_causal" : "");

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(SEQUENCE_ATTENTION_ASSET), layer, inputShape, args);
	}

	/**
	 * Creates a cross-attention block for attending over external context.
	 *
	 * <p>Cross-attention allows the model to attend to a different sequence (context) than
	 * the main input. This is used in encoder-decoder architectures where the decoder
	 * attends to encoder outputs.</p>
	 *
	 * <p>Implementation details:
	 * <ul>
	 *   <li>Queries (Q) come from the main input</li>
	 *   <li>Keys (K) and Values (V) come from the context input</li>
	 *   <li>QK normalization for stability</li>
	 *   <li>No rotary embeddings (context positions are independent)</li>
	 * </ul></p>
	 *
	 * @param batchSize Batch dimension
	 * @param querySeqLen Query sequence length
	 * @param contextSeqLen Context sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQWeight Query projection weights
	 * @param toKvWeight Fused KV projection weights for context
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param contextInput Context block to attend over
	 * @param attentionScores Optional receptor to capture attention weights
	 * @return Cross-attention block
	 */
	default Block sequenceCrossAttention(int batchSize, int querySeqLen, int contextSeqLen,
										 int dim, int heads,
										 PackedCollection toQWeight, PackedCollection toKvWeight,
										 PackedCollection toOutWeight,
										 PackedCollection qNormWeight, PackedCollection qNormBias,
										 PackedCollection kNormWeight, PackedCollection kNormBias,
										 Block contextInput, Receptor<PackedCollection> attentionScores) {
		return sequenceCrossAttention(batchSize, querySeqLen, contextSeqLen, dim, heads,
				toQWeight, toKvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				contextInput, attentionScores, ProjectionFactory.dense());
	}

	/**
	 * Creates a cross-attention block with customizable projection layers, using layer
	 * normalization for the query/key norms.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection
	 * layers are created, enabling LoRA or other adapter patterns without code duplication.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param querySeqLen Query sequence length
	 * @param contextSeqLen Context sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQWeight Query projection weights
	 * @param toKvWeight Fused KV projection weights for context
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param contextInput Context block to attend over
	 * @param attentionScores Optional receptor to capture attention weights
	 * @param projectionFactory Factory for creating projection layers
	 * @return Cross-attention block
	 */
	default Block sequenceCrossAttention(int batchSize, int querySeqLen, int contextSeqLen,
										 int dim, int heads,
										 PackedCollection toQWeight, PackedCollection toKvWeight,
										 PackedCollection toOutWeight,
										 PackedCollection qNormWeight, PackedCollection qNormBias,
										 PackedCollection kNormWeight, PackedCollection kNormBias,
										 Block contextInput, Receptor<PackedCollection> attentionScores,
										 ProjectionFactory projectionFactory) {
		return sequenceCrossAttention(batchSize, querySeqLen, contextSeqLen, dim, heads,
				toQWeight, toKvWeight, toOutWeight,
				qNormWeight, qNormBias, kNormWeight, kNormBias,
				contextInput, attentionScores, projectionFactory, NormalizationType.LAYER);
	}

	/**
	 * Creates a cross-attention block with customizable projection layers and query/key
	 * normalization family, so that a block builder can be parameterized by the normalization
	 * its checkpoint was trained with.
	 *
	 * <p>This version accepts a {@link ProjectionFactory} to customize how projection
	 * layers are created, enabling LoRA or other adapter patterns without code duplication.</p>
	 *
	 * @param batchSize Batch dimension
	 * @param querySeqLen Query sequence length
	 * @param contextSeqLen Context sequence length
	 * @param dim Model dimension
	 * @param heads Number of attention heads
	 * @param toQWeight Query projection weights
	 * @param toKvWeight Fused KV projection weights for context
	 * @param toOutWeight Output projection weights
	 * @param qNormWeight Query normalization weights
	 * @param qNormBias Query normalization biases
	 * @param kNormWeight Key normalization weights
	 * @param kNormBias Key normalization biases
	 * @param contextInput Context block to attend over
	 * @param attentionScores Optional receptor to capture attention weights
	 * @param projectionFactory Factory for creating projection layers
	 * @param normType Family of the query/key normalization
	 * @return Cross-attention block
	 */
	default Block sequenceCrossAttention(int batchSize, int querySeqLen, int contextSeqLen,
										 int dim, int heads,
										 PackedCollection toQWeight, PackedCollection toKvWeight,
										 PackedCollection toOutWeight,
										 PackedCollection qNormWeight, PackedCollection qNormBias,
										 PackedCollection kNormWeight, PackedCollection kNormBias,
										 Block contextInput, Receptor<PackedCollection> attentionScores,
										 ProjectionFactory projectionFactory,
										 NormalizationType normType) {
		int dimHead = dim / heads;
		TraversalPolicy queryShape = shape(batchSize, querySeqLen, dim);

		SequentialBlock crossAttention = new SequentialBlock(queryShape);

		// 1. Project main input to queries
		crossAttention.add(projectionFactory.create(queryShape, toQWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_Q));
		crossAttention.reshape(batchSize, querySeqLen, heads, dimHead);
		crossAttention.permute(0, 2, 1, 3); // (batch, heads, querySeqLen, dimHead)

		// 2. Apply Q normalization
		crossAttention.add(norm(normType, qNormWeight, qNormBias, 1e-6));

		// 3. Process context input through separate branch for K and V
		SequentialBlock contextBranch = contextInput.branch();
		contextBranch.add(projectionFactory.create(contextInput.getOutputShape(), toKvWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_KV));
		contextBranch.reshape(batchSize, contextSeqLen, 2, dim);

		List<Block> kv = contextBranch.split(shape(batchSize, contextSeqLen, 1, dim), 0);
		SequentialBlock k = (SequentialBlock) kv.get(0).reshape(batchSize, contextSeqLen, heads, dimHead);
		SequentialBlock v = (SequentialBlock) kv.get(1).reshape(batchSize, contextSeqLen, heads, dimHead);

		// 4. Permute K and V to (batch, heads, contextSeqLen, dimHead)
		k.permute(0, 2, 1, 3);
		v.permute(0, 2, 1, 3);

		// 5. Apply K normalization (no rotary for context keys/values)
		k.add(norm(normType, kNormWeight, kNormBias, 1e-6));

		// 6. Store K and V tensors for use in attention computation
		// TODO(review): K/V buffering drops gradients to toKvWeight; feed K and V to the products as branch operands, as sequence_attention.pdsl does
		PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
		PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));

		k.andThen(into(kTensor));
		v.andThen(into(vTensor));

		// 7. Apply attention to values
		crossAttention.add(scaledDotProductAttention(
				batchSize, querySeqLen, contextSeqLen,
				heads, dimHead, kTensor, vTensor, attentionScores));

		// 8. Rearrange back to (batch, querySeqLen, dim)
		crossAttention.permute(0, 2, 1, 3)
				.reshape(batchSize, querySeqLen, dim);

		// 9. Output projection
		crossAttention.add(projectionFactory.create(queryShape, toOutWeight,
				AdapterConfig.TargetLayer.CROSS_ATTENTION_OUT));

		return crossAttention;
	}

	/**
	 * Builds a scaled dot-product attention block using the same sequence length for queries and context.
	 *
	 * @param batchSize Batch dimension
	 * @param seqLen    Sequence length for both queries and context
	 * @param heads     Number of attention heads
	 * @param dimHead   Dimension per head
	 * @param k         Key tensor (batch, heads, seqLen, dimHead)
	 * @param v         Value tensor (batch, heads, seqLen, dimHead)
	 * @return Block computing softmax(Q @ K^T / sqrt(dimHead)) @ V
	 */
	default Block scaledDotProductAttention(int batchSize, int seqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v) {
		return scaledDotProductAttention(batchSize, seqLen, seqLen, heads, dimHead, k, v, null);
	}

	/**
	 * Builds a scaled dot-product attention block with optional attention score capture.
	 *
	 * @param batchSize       Batch dimension
	 * @param seqLen          Sequence length for both queries and context
	 * @param heads           Number of attention heads
	 * @param dimHead         Dimension per head
	 * @param k               Key tensor (batch, heads, seqLen, dimHead)
	 * @param v               Value tensor (batch, heads, seqLen, dimHead)
	 * @param attentionScores Optional receptor to receive the attention weight matrix; may be null
	 * @return Block computing softmax(Q @ K^T / sqrt(dimHead)) @ V
	 */
	default Block scaledDotProductAttention(int batchSize, int seqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v,
											Receptor<PackedCollection> attentionScores) {
		return scaledDotProductAttention(batchSize, seqLen, seqLen, heads, dimHead, k, v, attentionScores);
	}

	/**
	 * Computes scaled dot-product attention: softmax(Q @ K^T / sqrt(d_k)) @ V
	 * This implementation properly handles K and V as tensor data rather than computational blocks.
	 *
	 * @param batchSize batch dimension
	 * @param querySeqLen sequence length for queries
	 * @param contextSeqLen sequence length for context (keys/values)
	 * @param heads number of attention heads
	 * @param dimHead dimension per head
	 * @param k key tensor data (batch, heads, seqLenK, dimHead)
	 * @param v value tensor data (batch, heads, seqLenV, dimHead)
	 */
	default Block scaledDotProductAttention(int batchSize, int querySeqLen, int contextSeqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v,
											Receptor<PackedCollection> attentionScores) {
		return scaledDotProductAttention(batchSize, querySeqLen, contextSeqLen, heads, dimHead,
				k, v, attentionScores, 0.0, null);
	}

	/**
	 * Builds bidirectional scaled dot-product attention over a whole sequence at once,
	 * {@code softmax(mask(softcap(Q Kᵀ / sqrt(d_k)))) V}, as the overload with a {@code causal} flag
	 * builds it with causal masking off.
	 *
	 * @param batchSize batch dimension (must be 1)
	 * @param querySeqLen sequence length for queries
	 * @param contextSeqLen sequence length for context (keys/values)
	 * @param heads number of attention heads
	 * @param dimHead dimension per head
	 * @param k key tensor data (batch, heads, seqLenK, dimHead)
	 * @param v value tensor data (batch, heads, seqLenV, dimHead)
	 * @param attentionScores Optional receptor to receive the attention weight matrix; may be null
	 * @param logitSoftcap the soft-cap applied to the scaled logits, or {@code 0} for none
	 * @param keyMask per-key validity, shape {@code (batch, contextSeqLen)} with one for a key that
	 *                may be attended and zero for one that may not, or {@code null} for no masking
	 * @return Block computing the attention output
	 */
	default Block scaledDotProductAttention(int batchSize, int querySeqLen, int contextSeqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v,
											Receptor<PackedCollection> attentionScores,
											double logitSoftcap, Producer<PackedCollection> keyMask) {
		return scaledDotProductAttention(batchSize, querySeqLen, contextSeqLen, heads, dimHead,
				k, v, attentionScores, logitSoftcap, keyMask, false);
	}

	/**
	 * Builds scaled dot-product attention over a whole sequence at once from the {@link #SDPA_ASSET}
	 * asset, with every masking option: {@code softmax(mask(softcap(Q Kᵀ / sqrt(d_k)))) V}.
	 * Soft-capping squashes the scaled logits to {@code cap * tanh(logit / cap)}; the key mask adds a
	 * large negative bias to the logits of masked keys, and causal masking to the logits of every key
	 * after the query's own position, so those keys receive no attention.
	 *
	 * <p>The structure is not assembled here: one of the asset's score layers ({@code sdpa_scores}, or
	 * its soft-capped, causal, or soft-capped and causal form) computes the attention weights and its
	 * {@code sdpa_context} layer their weighted sum of values. This method binds the key and value
	 * tensors and the mask, selects the score layer for the requested soft-cap and causal masking,
	 * and chains the two halves. A missing mask is bound as an all-ones mask so the asset's key-mask
	 * stage adds zero, which is how one asset body serves masked and unmasked callers. When a
	 * receptor is supplied the attention weights are tapped to it between the two halves.</p>
	 *
	 * <p>The keys and values enter the computation as constants, so no gradient flows to whatever
	 * produced them. Attention that computes its keys and values in the same graph and trains them,
	 * as {@link #sequenceAttention} does, reads them from branches of its asset layer instead.</p>
	 *
	 * @param batchSize batch dimension (must be 1)
	 * @param querySeqLen sequence length for queries
	 * @param contextSeqLen sequence length for context (keys/values)
	 * @param heads number of attention heads
	 * @param dimHead dimension per head
	 * @param k key tensor data (batch, heads, seqLenK, dimHead)
	 * @param v value tensor data (batch, heads, seqLenV, dimHead)
	 * @param attentionScores Optional receptor to receive the attention weight matrix; may be null
	 * @param logitSoftcap the soft-cap applied to the scaled logits, or {@code 0} for none
	 * @param keyMask per-key validity, shape {@code (batch, contextSeqLen)}, or {@code null}
	 * @param causal whether query {@code i} may attend only to keys {@code j <= i}
	 * @return Block computing the attention output
	 * @throws UnsupportedOperationException if {@code batchSize} is not 1
	 */
	default Block scaledDotProductAttention(int batchSize, int querySeqLen, int contextSeqLen, int heads, int dimHead,
											PackedCollection k, PackedCollection v,
											Receptor<PackedCollection> attentionScores,
											double logitSoftcap, Producer<PackedCollection> keyMask,
											boolean causal) {
		if (batchSize != 1) {
			throw new UnsupportedOperationException("Batches of more than 1 are not currently supported");
		}

		TraversalPolicy inputShape = shape(batchSize, heads, querySeqLen, dimHead);
		Map<String, Object> scoresArgs = new HashMap<>();
		scoresArgs.put("k", k);
		scoresArgs.put("key_mask", keyMask != null ? keyMask : everyPositionValid(batchSize, contextSeqLen));
		scoresArgs.put("dim_head", dimHead);
		scoresArgs.put("softcap", logitSoftcap);
		String scoresLayer = "sdpa_scores" + (logitSoftcap > 0.0 ? "_softcapped" : "")
				+ (causal ? "_causal" : "");

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource(SDPA_ASSET);
		SequentialBlock attention = new SequentialBlock(inputShape);
		attention.add(loader.buildLayer(program, scoresLayer, inputShape, scoresArgs));

		// Tap the attention weights to the receptor between the score and context halves.
		if (attentionScores != null) {
			attention.branch().andThen(attentionScores);
		}

		Map<String, Object> contextArgs = new HashMap<>();
		contextArgs.put("v", v);
		attention.add(loader.buildLayer(program, "sdpa_context",
				attention.getOutputShape(), contextArgs));

		return attention;
	}

	/**
	 * A validity mask that marks every position valid: ones of shape {@code (batch, length)}. Bound
	 * in place of a mask the caller did not give, it leaves the values and the logits a mask stage of
	 * an attention asset applies to unchanged.
	 *
	 * @param batchSize the batch dimension
	 * @param length    the number of positions
	 * @return the all-ones mask
	 */
	private CollectionProducer everyPositionValid(int batchSize, int length) {
		return zeros(shape(batchSize, length)).add(1.0);
	}

	/**
	 * Builds the additive causal mask for attention logits of the given shape: zero where the key
	 * index {@code j} is at most the query index {@code i}, and {@link #MASKED_LOGIT_PENALTY
	 * -MASKED_LOGIT_PENALTY} where {@code j > i}, so a query receives no attention from later
	 * keys. The mask depends on both indices, which is why it cannot be expressed as a per-key mask;
	 * it is computed by comparing two index producers broadcast over the query and key axes, and is
	 * the same for every batch and head. It is the mask the PDSL {@code sequence_causal_mask()} stage
	 * adds to the scores of the causal attention layers of {@link #SDPA_ASSET}.
	 *
	 * @param scoresShape the logit shape, whose last two axes are the query and key positions
	 * @return a producer of the mask with shape {@code scoresShape}
	 */
	default CollectionProducer causalLogitMask(TraversalPolicy scoresShape) {
		int queryAxis = scoresShape.getDimensions() - 2;
		int keyAxis = scoresShape.getDimensions() - 1;
		int batch = scoresShape.length(0);
		CollectionProducer queryIndex = broadcast(scoresShape, queryAxis,
				integers(0, scoresShape.length(queryAxis)).repeat(batch));
		CollectionProducer keyIndex = broadcast(scoresShape, keyAxis,
				integers(0, scoresShape.length(keyAxis)).repeat(batch));
		return greaterThan(keyIndex, queryIndex, c(-MASKED_LOGIT_PENALTY), c(0.0));
	}
}
