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
import java.util.LinkedHashMap;
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

	/**
	 * The number of vocabularies whose tag embeddings are kept: embedding one more releases the
	 * embeddings of the vocabulary used least recently.
	 */
	public static final int TAG_EMBEDDING_CACHE_SIZE = 8;

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

	/**
	 * The tag embeddings of the {@link #TAG_EMBEDDING_CACHE_SIZE} vocabularies
	 * {@link #embed(TagVocabulary)} has used most recently, least recently used first.
	 */
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
		this((Clap2Tokenizer) null, weightsDirectory, maxTextLength);
	}

	/**
	 * Loads a model and its tokenizer: the weights from the directory the extraction script writes,
	 * the tokenizer from the rank file the tokenizer export script writes. The tokenizer is read
	 * first, so a missing or malformed rank file fails before any weight is loaded.
	 *
	 * @param weightsDirectory the extracted weights
	 * @param rankFile         the exported {@code r50k_base} rank file
	 * @param maxTextLength    the number of token positions to compile the text tower for
	 * @throws IOException if the weights or the rank file cannot be read
	 */
	public Clap2(String weightsDirectory, String rankFile, int maxTextLength) throws IOException {
		this(new Clap2Tokenizer(Path.of(rankFile)), weightsDirectory, maxTextLength);
	}

	/**
	 * Loads the weights of a model whose tokenizer is already read; the loaded weights are
	 * released if the model cannot be built over them.
	 *
	 * @param tokenizer        the caption tokenizer, or null
	 * @param weightsDirectory the extracted weights
	 * @param maxTextLength    the number of token positions to compile the text tower for
	 * @throws IOException if the weights cannot be read
	 */
	private Clap2(Clap2Tokenizer tokenizer, String weightsDirectory, int maxTextLength) throws IOException {
		this(new StateDictionary(weightsDirectory), tokenizer, maxTextLength,
				Clap2Config.DEFAULT_WINDOW_SECONDS * Clap2Config.SAMPLE_RATE, true);
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
	 * Creates a model over the given weights and tokenizer. If the model cannot be built, whatever
	 * it had built is released and the weights are left to the caller.
	 *
	 * @param weights       the weights of both towers
	 * @param tokenizer     the caption tokenizer, or null for a model that only compares embeddings
	 * @param maxTextLength the number of token positions to compile the text tower for
	 * @param windowSamples the number of samples of the fixed window the audio tower is compiled for
	 * @throws IllegalArgumentException if the weights or the sizes do not describe a valid model
	 */
	public Clap2(StateDictionary weights, Clap2Tokenizer tokenizer, int maxTextLength, int windowSamples) {
		this(weights, tokenizer, maxTextLength, windowSamples, false);
	}

	/**
	 * Creates a model over the given weights and tokenizer, releasing the towers already built,
	 * and the weights if this model loaded them, when the model cannot be built.
	 *
	 * @param weights                 the weights of both towers
	 * @param tokenizer               the caption tokenizer, or null
	 * @param maxTextLength           the number of token positions to compile the text tower for
	 * @param windowSamples           the number of samples of the audio tower's fixed window
	 * @param releaseWeightsOnFailure whether the weights are released if construction fails
	 */
	private Clap2(StateDictionary weights, Clap2Tokenizer tokenizer, int maxTextLength,
				  int windowSamples, boolean releaseWeightsOnFailure) {
		Clap2AudioEncoder audioTower = null;
		try {
			this.config = Clap2Config.of(weights, maxTextLength, windowSamples);
			audioTower = new Clap2AudioEncoder(config, weights);
			this.text = new Clap2TextEncoder(config, weights);
		} catch (RuntimeException e) {
			if (audioTower != null) audioTower.destroy();
			if (releaseWeightsOnFailure) weights.destroy();
			throw e;
		}

		this.weights = weights;
		this.audio = audioTower;
		this.tokenizer = tokenizer;
		this.tagEmbeddings = new LinkedHashMap<>(16, 0.75f, true);
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
	 * The text embeddings of the tags of a vocabulary, computed on first use and kept while the
	 * vocabulary is among the {@link #TAG_EMBEDDING_CACHE_SIZE} this model has embedded most
	 * recently: the embedding of a tag is the normalized mean of the normalized embeddings of its
	 * captions, each tokenized by this model's tokenizer as {@code [BOS, ..., EOS]}.
	 *
	 * <p>The text tower's output buffer is overwritten by every caption, so each caption is added
	 * to its tag's {@link MeanDirection} on the device before the next caption is encoded, and the
	 * tag's direction is copied into its row; no embedding is read back to the host. Every caption
	 * is tokenized, and so validated, before any device memory is allocated.</p>
	 *
	 * @param vocabulary the vocabulary
	 * @return the unit-length tag embeddings, shape {@code [tags, embedDim]}, in vocabulary order;
	 *         owned by this model, and released when {@link #TAG_EMBEDDING_CACHE_SIZE} other
	 *         vocabularies have been embedded since it was last used, or by {@link #destroy()}
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
		int[][][] captionIds = new int[tags][][];
		for (int t = 0; t < tags; t++) {
			List<String> captions = vocabulary.getTags().get(t).getCaptions();
			captionIds[t] = new int[captions.size()][];
			for (int c = 0; c < captions.size(); c++) {
				captionIds[t][c] = tokenize(captions.get(c));
			}
		}

		int width = config.getEmbedDim();
		embeddings = new PackedCollection(shape(tags, width));
		try (MeanDirection mean = new MeanDirection(width)) {
			for (int t = 0; t < tags; t++) {
				mean.clear();
				for (int[] ids : captionIds[t]) {
					mean.add(text.encode(ids));
				}
				embeddings.setFrom(t * width, mean.direction(), 0, width);
			}
		} catch (RuntimeException e) {
			embeddings.destroy();
			throw e;
		}

		cacheTagEmbeddings(vocabulary, embeddings);
		return embeddings;
	}

	/**
	 * Keeps the tag embeddings of a vocabulary, releasing those of the vocabulary used least
	 * recently when more than {@link #TAG_EMBEDDING_CACHE_SIZE} would otherwise be kept.
	 *
	 * @param vocabulary the vocabulary
	 * @param embeddings its tag embeddings
	 */
	private void cacheTagEmbeddings(TagVocabulary vocabulary, PackedCollection embeddings) {
		tagEmbeddings.put(vocabulary, embeddings);
		if (tagEmbeddings.size() > TAG_EMBEDDING_CACHE_SIZE) {
			Map.Entry<TagVocabulary, PackedCollection> eldest = tagEmbeddings.entrySet().iterator().next();
			tagEmbeddings.remove(eldest.getKey());
			eldest.getValue().destroy();
		}
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
	 * {@link Clap2Config#MIN_CLIP_SAMPLES}; the tag embeddings are computed once per vocabulary
	 * and kept as {@link #embed(TagVocabulary)} describes.
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
