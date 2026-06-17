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

/**
 * Selects the attention sub-computation used when assembling a transformer block.
 *
 * <p>The variant is threaded through the shared {@code transformerBlock} builder so that
 * alternative attention implementations can be chosen without forking the block-assembly
 * code. {@link #STANDARD} is the default and preserves the existing scaled-dot-product
 * self-attention exactly; additional variants are supplied by sub-interfaces of
 * {@link AttentionFeatures} (for example {@link DifferentialAttentionFeatures}).</p>
 *
 * @author  Michael Murray
 * @see AttentionFeatures#selfAttention
 * @see DifferentialAttentionFeatures
 */
public enum AttentionVariant {
	/**
	 * Standard multi-head scaled-dot-product self-attention: a single fused {@code to_qkv}
	 * projection of width {@code dim * 3} and one softmax attention map.
	 */
	STANDARD,

	/**
	 * Differential attention: a doubled query/key projection (width {@code dim * 5}) producing
	 * two softmax attention maps whose difference, scaled by a learned per-head lambda, forms
	 * the attention weights.
	 */
	DIFFERENTIAL
}
