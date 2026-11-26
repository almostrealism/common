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
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.MeanDirection;
import org.almostrealism.ml.StateDictionary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public class Clap2 implements MatrixFeatures, VectorFeatures, Destroyable {

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

	/** The caption tokenizer, or null for a model that only compares embeddings. */
	private final Clap2Tokenizer tokenizer;

	/** The tag embeddings of each vocabulary {@link #embed(TagVocabulary)} has embedded. */
	// TODO(review): unbounded; every distinct vocabulary (e.g. each score(waveform, List) tag list) stays resident until destroy().
	private final Map<TagVocabulary, PackedCollection> tagEmbeddings;

	/**
	 * Loads a model from the directory the extraction script writes, without a tokenizer: it
	 * embeds token ids and clips and compares embeddings, but cannot {@link #score} text tags.
	 *
	 * @param weightsDirectory the extracted weights
	 * @param maxTextLength    the number of token positions to compile the text tower for
	 * @throws IOException if the weights cannot be read
	 */
	public Clap2(String weightsDirectory, int maxTextLength) throws IOException {
		this(new StateDictionary(weightsDirectory), maxTextLength);
	}

	/**
	 * Loads a model and its tokenizer: the weights from the directory the extraction script writes,
	 * the tokenizer from the rank file the tokenizer export script writes.
	 *
	 * @param weightsDirectory the extracted weights
	 * @param rankFile         the exported {@code r50k_base} rank file
	 * @param maxTextLength    the number of token positions to compile the text tower for
	 * @throws IOException if the weights or the rank file cannot be read
	 */
	public Clap2(String weightsDirectory, String rankFile, int maxTextLength) throws IOException {
		// TODO(review): the StateDictionary leaks if the Clap2Tokenizer constructor throws (bad rank file).
		this(new StateDictionary(weightsDirectory), new Clap2Tokenizer(Path.of(rankFile)), maxTextLength,
				Clap2Config.DEFAULT_WINDOW_SECONDS * Clap2Config.SAMPLE_RATE);
	}

	/**
	 * Creates a model over the given weights, without a tokenizer.
	 *
	 * @param weights       the weights of both towers
	 * @param maxTextLength the number of token positions to compile the text tower for
	 */
	public Clap2(StateDictionary weights, int maxTextLength) {
		this(weights, null, maxTextLength, Clap2Config.DEFAULT_WINDOW_SECONDS * Clap2Config.SAMPLE_RATE);
	}

	/**
	 * Creates a model over the given weights and tokenizer.
	 *
	 * @param weights       the weights of both towers
	 * @param tokenizer     the caption tokenizer, or null for a model that only compares embeddings
	 * @param maxTextLength the number of token positions to compile the text tower for
	 * @param windowSamples the number of samples of the fixed window the audio tower is compiled for
	 */
	public Clap2(StateDictionary weights, Clap2Tokenizer tokenizer, int maxTextLength, int windowSamples) {
		this.weights = weights;
		this.config = Clap2Config.of(weights, maxTextLength, windowSamples);
		this.audio = new Clap2AudioEncoder(config, weights);
		this.text = new Clap2TextEncoder(config, weights);
		this.tokenizer = tokenizer;
		this.tagEmbeddings = new HashMap<>();
	}

	/** The architecture. */
	public Clap2Config getConfig() { return config; }

	/** The audio tower. */
	public Clap2AudioEncoder getAudioEncoder() { return audio; }

	/** The text tower. */
	public Clap2TextEncoder getTextEncoder() { return text; }

	/** The caption tokenizer, or null for a model loaded without one. */
	public Clap2Tokenizer getTokenizer() { return tokenizer; }

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

	/**
	 * The text embeddings of the tags of a vocabulary, computed on first use and kept until
	 * {@link #destroy()}: the embedding of a tag is the normalized mean of the normalized
	 * embeddings of its captions, each tokenized by this model's tokenizer as {@code [BOS, ..., EOS]}.
	 *
	 * <p>The text tower's output buffer is overwritten by every caption, so each caption is added
	 * to its tag's {@link MeanDirection} on the device before the next caption is encoded, and the
	 * tag's direction is copied into its row; no embedding is read back to the host.</p>
	 *
	 * @param vocabulary the vocabulary
	 * @return the unit-length tag embeddings, shape {@code [tags, embedDim]}, in vocabulary order;
	 *         owned by this model
	 * @throws IllegalStateException    if this model has no tokenizer
	 * @throws IllegalArgumentException if a caption is longer than the compiled text length
	 */
	public PackedCollection embed(TagVocabulary vocabulary) {
		PackedCollection embeddings = tagEmbeddings.get(vocabulary);
		if (embeddings != null) return embeddings;

		if (tokenizer == null) {
			throw new IllegalStateException("Tags given as text require a tokenizer; load the model with "
					+ "the exported rank file, or compare precomputed embeddings with similarity");
		}

		int tags = vocabulary.size();
		int width = config.getEmbedDim();
		embeddings = new PackedCollection(shape(tags, width));
		try (MeanDirection mean = new MeanDirection(width)) {
			for (int t = 0; t < tags; t++) {
				mean.clear();
				for (String caption : vocabulary.getTags().get(t).getCaptions()) {
					mean.add(text.encode(tokenize(caption)));
				}
				embeddings.setFrom(t * width, mean.direction(), 0, width);
			}
		}

		tagEmbeddings.put(vocabulary, embeddings);
		return embeddings;
	}

	/**
	 * The token ids of a caption, {@code [BOS, ..., EOS]}.
	 *
	 * @param caption the caption
	 * @return the ids
	 * @throws IllegalArgumentException if the caption is longer than the compiled text length
	 */
	private int[] tokenize(String caption) {
		int[] ids = tokenizer.encode(caption, true);
		if (ids.length > config.getMaxTextLength()) {
			throw new IllegalArgumentException("Caption \"" + caption + "\" is " + ids.length
					+ " tokens, more than the " + config.getMaxTextLength()
					+ " the text tower is compiled for; load the model with a longer text length");
		}
		return ids;
	}

	/**
	 * Scores a clip against every tag of a vocabulary: the cosine similarity of the clip's
	 * {@link Clap2AudioEncoder#encodeClip fixed-window embedding} with each tag's
	 * {@link #embed(TagVocabulary) embedding}. The clip may be of any length of at least
	 * {@link Clap2Config#MIN_CLIP_SAMPLES}; the tag embeddings are computed once per vocabulary.
	 *
	 * <p>The scores are raw cosines. The model's distribution over the tags is the softmax of
	 * {@link #logits} over the same two embeddings, and a consumer that owns a library usually
	 * centers each tag's score by its mean over the library before thresholding, as the model
	 * card advises.</p>
	 *
	 * <p>The audio tower runs here; the returned producer compares the embeddings and reads the
	 * audio encoder's clip buffer, which the next score overwrites, so it must be evaluated first.</p>
	 *
	 * @param waveform   the mono waveform at {@link Clap2Config#SAMPLE_RATE}
	 * @param vocabulary the tags
	 * @return the scores, shape {@code [tags]}, in vocabulary order
	 * @throws IllegalStateException    if this model has no tokenizer
	 * @throws IllegalArgumentException if the clip is too short or a caption too long
	 */
	public CollectionProducer score(PackedCollection waveform, TagVocabulary vocabulary) {
		PackedCollection tags = embed(vocabulary);
		return similarity(cp(audio.encodeClip(waveform)), cp(tags));
	}

	/**
	 * Scores a clip against tags each described by one caption, the tag itself; see
	 * {@link #score(PackedCollection, TagVocabulary)}.
	 *
	 * @param waveform the mono waveform at {@link Clap2Config#SAMPLE_RATE}
	 * @param tags     the tags
	 * @return the scores, shape {@code [tags]}, in the order of {@code tags}
	 */
	public CollectionProducer score(PackedCollection waveform, List<String> tags) {
		return score(waveform, TagVocabulary.of(tags));
	}

	@Override
	public void destroy() {
		tagEmbeddings.values().forEach(PackedCollection::destroy);
		tagEmbeddings.clear();
		audio.destroy();
		text.destroy();
		weights.destroy();
	}
}
