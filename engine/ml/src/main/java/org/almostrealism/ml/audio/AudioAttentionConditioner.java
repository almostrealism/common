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

package org.almostrealism.ml.audio;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.collect.PackedCollection;

/**
 * Abstraction for text-to-audio conditioning that produces attention inputs
 * for diffusion-based audio generation models.
 * <p>
 * This interface hides the underlying inference engine (ONNX, etc.) and provides
 * a clean API for generating conditioning tensors from pre-tokenized text.
 * <p>
 * For tokenization, use a separate {@link org.almostrealism.ml.Tokenizer} implementation
 * such as {@code SentencePieceTokenizer}.
 * <p>
 * Implementations may use different backends:
 * <ul>
 *   <li>{@code OnnxAudioConditioner} - ONNX Runtime based implementation</li>
 * </ul>
 *
 * @see org.almostrealism.ml.Tokenizer
 */
public interface AudioAttentionConditioner extends Destroyable {

	/**
	 * Runs the conditioning model to produce attention inputs for the diffusion model.
	 * <p>
	 * The returned {@link ConditionerOutput} contains:
	 * <ul>
	 *   <li>Cross-attention input tensor for text conditioning</li>
	 *   <li>Cross-attention mask tensor</li>
	 *   <li>Global conditioning vector</li>
	 * </ul>
	 *
	 * @param tokenIds the token IDs from a {@link org.almostrealism.ml.Tokenizer}
	 * @param durationSeconds the target audio duration in seconds
	 * @return conditioning outputs for the diffusion model
	 */
	ConditionerOutput runConditioners(long[] tokenIds, double durationSeconds);

	/**
	 * Output container for conditioning tensors.
	 * <p>
	 * Encapsulates the three tensors needed for conditional audio generation:
	 * cross-attention input, cross-attention mask, and global conditioning.
	 */
	class ConditionerOutput {
		private final PackedCollection crossAttentionInput;
		private final PackedCollection crossAttentionMask;
		private final PackedCollection globalCond;

		public ConditionerOutput(PackedCollection crossAttentionInput,
								 PackedCollection crossAttentionMask,
								 PackedCollection globalCond) {
			this.crossAttentionInput = crossAttentionInput;
			this.crossAttentionMask = crossAttentionMask;
			this.globalCond = globalCond;
		}

		/**
		 * Returns the cross-attention input tensor for text conditioning.
		 * This is typically the output of a text encoder (e.g., T5).
		 */
		public PackedCollection getCrossAttentionInput() {
			return crossAttentionInput;
		}

		/**
		 * Returns the cross-attention mask tensor.
		 * Used to mask padding tokens in the attention computation.
		 */
		public PackedCollection getCrossAttentionMask() {
			return crossAttentionMask;
		}

		/**
		 * Returns the global conditioning vector.
		 * Provides global context for the generation (e.g., duration encoding).
		 */
		public PackedCollection getGlobalCond() {
			return globalCond;
		}
	}
}
