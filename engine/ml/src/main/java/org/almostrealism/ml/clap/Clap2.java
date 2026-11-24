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

package org.almostrealism.ml.clap;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;

import java.io.IOException;

/**
 * A CLAP2 contrastive audio-text model: its {@link Clap2AudioEncoder audio tower}, its
 * {@link Clap2TextEncoder text tower}, and the scoring that compares their embeddings.
 *
 * <p>Zero-shot tagging scores a clip against a vocabulary of captions: each caption is embedded
 * once by the text tower, the clip by the audio tower, and the score of a caption is the cosine
 * similarity of the two embeddings ({@link #similarity}), or that similarity times the model's
 * learned temperature ({@link #logits}), whose softmax over the vocabulary is the model's
 * distribution over the captions.</p>
 *
 * <p>The weights are the dictionary the CLAP2 extraction script writes from a released
 * checkpoint ({@code engine/ml/scripts/extract_clap2_weights.py}); the architecture is read from
 * them by {@link Clap2Config#of(StateDictionary, int)}, so every size of the family loads the same
 * way.</p>
 */
public class Clap2 implements MatrixFeatures, Destroyable {

	/** The key of the learned temperature, stored as its natural logarithm. */
	public static final String LOGIT_SCALE_KEY = "logit_scale";

	/** The architecture. */
	private final Clap2Config config;

	/** The weights of both towers. */
	private final StateDictionary weights;

	/** The audio tower. */
	private final Clap2AudioEncoder audio;

	/** The text tower. */
	private final Clap2TextEncoder text;

	/**
	 * Loads a model from the directory the extraction script writes.
	 *
	 * @param weightsDirectory the extracted weights
	 * @param maxTextLength    the number of token positions to compile the text tower for
	 * @throws IOException if the weights cannot be read
	 */
	public Clap2(String weightsDirectory, int maxTextLength) throws IOException {
		this(new StateDictionary(weightsDirectory), maxTextLength);
	}

	/**
	 * Creates a model over the given weights.
	 *
	 * @param weights       the weights of both towers
	 * @param maxTextLength the number of token positions to compile the text tower for
	 */
	public Clap2(StateDictionary weights, int maxTextLength) {
		this.weights = weights;
		this.config = Clap2Config.of(weights, maxTextLength);
		this.audio = new Clap2AudioEncoder(config, weights);
		this.text = new Clap2TextEncoder(config, weights);
	}

	/** The architecture. */
	public Clap2Config getConfig() { return config; }

	/** The audio tower. */
	public Clap2AudioEncoder getAudioEncoder() { return audio; }

	/** The text tower. */
	public Clap2TextEncoder getTextEncoder() { return text; }

	/**
	 * The cosine similarity of one clip embedding with each of a set of caption embeddings.
	 *
	 * @param audioEmbedding the clip's embedding, {@code embedDim} values
	 * @param textEmbeddings the captions' embeddings, shape {@code [captions, embedDim]}
	 * @return the similarities, shape {@code [captions]}
	 */
	public CollectionProducer similarity(Producer<PackedCollection> audioEmbedding,
										 Producer<PackedCollection> textEmbeddings) {
		int width = config.getEmbedDim();
		TraversalPolicy textShape = shape(textEmbeddings);
		int captions = textShape.getTotalSize() / width;

		CollectionProducer clip = c(audioEmbedding).reshape(shape(width, 1));
		CollectionProducer captionRows = c(textEmbeddings).reshape(shape(captions, width));
		CollectionProducer dots = matmul(captionRows, clip).reshape(shape(captions));
		CollectionProducer captionNorms = sqrt(sq(captionRows).sum(1)).reshape(shape(captions));
		CollectionProducer clipNorm = sqrt(sq(clip).sum()).repeat(captions).reshape(shape(captions));
		return dots.divide(captionNorms.multiply(clipNorm));
	}

	/**
	 * The model's logits for one clip over a set of captions: the {@link #similarity cosine
	 * similarities} times the learned temperature {@code exp(logit_scale)}.
	 *
	 * @param audioEmbedding the clip's embedding, {@code embedDim} values
	 * @param textEmbeddings the captions' embeddings, shape {@code [captions, embedDim]}
	 * @return the logits, shape {@code [captions]}
	 */
	public CollectionProducer logits(Producer<PackedCollection> audioEmbedding,
									 Producer<PackedCollection> textEmbeddings) {
		CollectionProducer similarity = similarity(audioEmbedding, textEmbeddings);
		int captions = similarity.getShape().getTotalSize();
		CollectionProducer temperature = exp(cp(weights.require(LOGIT_SCALE_KEY)).reshape(shape(1)))
				.repeat(captions).reshape(shape(captions));
		return similarity.multiply(temperature);
	}

	@Override
	public void destroy() {
		audio.destroy();
		text.destroy();
		weights.destroy();
	}
}
