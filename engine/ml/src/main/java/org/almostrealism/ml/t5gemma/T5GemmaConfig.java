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

package org.almostrealism.ml.t5gemma;

/**
 * Architecture of a T5Gemma text encoder: a bidirectional stack of Gemma-style transformer
 * layers (RMS-normalized before and after both the attention and the gated feed-forward,
 * soft-capped attention logits, rotary positions) over a scaled token embedding, closed by a
 * final RMS norm.
 *
 * <p>Instances are immutable. {@link #baseUl2()} is the configuration of the released base
 * encoder used as the prompt conditioner of Stable Audio 3.</p>
 *
 * @see T5GemmaEncoder
 */
public final class T5GemmaConfig {

	/** Width of the hidden state. */
	private final int hiddenSize;
	/** Number of transformer layers. */
	private final int layers;
	/** Number of attention heads (queries, keys and values alike). */
	private final int heads;
	/** Width of one attention head. */
	private final int headDim;
	/** Inner width of the gated feed-forward. */
	private final int intermediateSize;
	/** Epsilon of every RMS norm. */
	private final double normEpsilon;
	/** Soft-cap applied to the scaled attention logits ({@code 0} disables it). */
	private final double attentionSoftcap;
	/** Base of the rotary position frequencies. */
	private final double ropeTheta;
	/** Size of the token vocabulary. */
	private final int vocabularySize;
	/** Number of token positions the encoder is compiled for; shorter prompts are padded to it. */
	private final int maxLength;

	/**
	 * Creates a configuration.
	 *
	 * @param hiddenSize       width of the hidden state
	 * @param layers           number of transformer layers
	 * @param heads            number of attention heads
	 * @param headDim          width of one attention head
	 * @param intermediateSize inner width of the gated feed-forward
	 * @param normEpsilon      epsilon of every RMS norm
	 * @param attentionSoftcap soft-cap on the scaled attention logits ({@code 0} for none)
	 * @param ropeTheta        base of the rotary position frequencies
	 * @param vocabularySize   size of the token vocabulary
	 * @param maxLength        number of token positions the encoder is compiled for
	 */
	public T5GemmaConfig(int hiddenSize, int layers, int heads, int headDim, int intermediateSize,
						 double normEpsilon, double attentionSoftcap, double ropeTheta,
						 int vocabularySize, int maxLength) {
		if (hiddenSize <= 0 || layers <= 0 || heads <= 0 || headDim <= 0 || intermediateSize <= 0
				|| vocabularySize <= 0 || maxLength <= 0) {
			throw new IllegalArgumentException("Every dimension of a T5GemmaConfig must be positive");
		}

		if (heads * headDim != hiddenSize) {
			throw new IllegalArgumentException("heads * headDim must equal hiddenSize");
		}

		if (headDim % 2 != 0) {
			throw new IllegalArgumentException("headDim must be even for rotary positions");
		}

		this.hiddenSize = hiddenSize;
		this.layers = layers;
		this.heads = heads;
		this.headDim = headDim;
		this.intermediateSize = intermediateSize;
		this.normEpsilon = normEpsilon;
		this.attentionSoftcap = attentionSoftcap;
		this.ropeTheta = ropeTheta;
		this.vocabularySize = vocabularySize;
		this.maxLength = maxLength;
	}

	/**
	 * The released base encoder: hidden width 768, 12 layers of 12 heads of width 64, feed-forward
	 * width 2048, norm epsilon {@code 1e-6}, attention soft-cap 50, rotary base 10000, a 256000
	 * token vocabulary, compiled for the 256-token prompts of Stable Audio 3.
	 *
	 * @return the configuration
	 */
	public static T5GemmaConfig baseUl2() {
		return new T5GemmaConfig(768, 12, 12, 64, 2048, 1e-6, 50.0, 10000.0, 256000, 256);
	}

	/**
	 * Returns a copy compiled for the given number of token positions.
	 *
	 * @param length number of token positions
	 * @return the modified configuration
	 */
	public T5GemmaConfig withMaxLength(int length) {
		return new T5GemmaConfig(hiddenSize, layers, heads, headDim, intermediateSize,
				normEpsilon, attentionSoftcap, ropeTheta, vocabularySize, length);
	}

	/** Returns the width of the hidden state. */
	public int getHiddenSize() { return hiddenSize; }

	/** Returns the number of transformer layers. */
	public int getLayers() { return layers; }

	/** Returns the number of attention heads. */
	public int getHeads() { return heads; }

	/** Returns the width of one attention head. */
	public int getHeadDim() { return headDim; }

	/** Returns the inner width of the gated feed-forward. */
	public int getIntermediateSize() { return intermediateSize; }

	/** Returns the epsilon of every RMS norm. */
	public double getNormEpsilon() { return normEpsilon; }

	/** Returns the soft-cap on the scaled attention logits ({@code 0} for none). */
	public double getAttentionSoftcap() { return attentionSoftcap; }

	/** Returns the base of the rotary position frequencies. */
	public double getRopeTheta() { return ropeTheta; }

	/** Returns the size of the token vocabulary. */
	public int getVocabularySize() { return vocabularySize; }

	/** Returns the number of token positions the encoder is compiled for. */
	public int getMaxLength() { return maxLength; }
}
