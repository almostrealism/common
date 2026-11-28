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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;

import java.util.HashMap;
import java.util.Map;

/**
 * The text tower of a CLAP2 model: a tokenized caption becomes one embedding in the space the
 * {@link Clap2AudioEncoder audio tower} maps clips into.
 *
 * <p>A caption is the token ids {@code [BOS, ..., EOS]} of its GPT-2 (r50k) byte-level BPE
 * encoding, which an encoder created with a {@link Clap2Tokenizer} produces itself from the
 * caption's text ({@link #encode(String)}); the positions beyond it are filled with
 * {@link Clap2Config#PAD_TOKEN} and masked out of every attention and of the pool, so the
 * compiled length does not change the embedding. The tower is the {@code clap2_text_tower} layer
 * of {@code /pdsl/clap2.pdsl}; this class fills the prompt mask, binds the checkpoint and the
 * rotary frequencies, and compiles it once.</p>
 *
 * <p>Tag embeddings depend only on the vocabulary, so an application encodes its tag captions
 * once per vocabulary version and compares every clip against the stored embeddings.</p>
 *
 * @see Clap2Config
 */
public class Clap2TextEncoder implements AttentionFeatures, Destroyable {

	/** The asset defining both CLAP2 towers. */
	private static final String ASSET = "/pdsl/clap2.pdsl";

	/** Every encoder is compiled for one caption at a time. */
	private static final int BATCH = 1;

	/** The architecture. */
	private final Clap2Config config;

	/** The weights, keyed as the extraction script writes them. */
	private final StateDictionary weights;

	/** The caption tokenizer, or null for an encoder that is only given token ids. */
	private final Clap2Tokenizer tokenizer;

	/** Token ids of the current caption, shape {@code [batch, maxTextLength]}; a leaf of the graph. */
	private final PackedCollection tokenIds;

	/** Validity of each position (one for a caption token, zero for padding); a leaf of the graph. */
	private final PackedCollection promptMask;

	/** The number of caption tokens, held on the device so that the mask is written by a kernel. */
	private final PackedCollection promptLength;

	/** The compiled assignment that writes {@link #promptMask} from {@link #promptLength}. */
	private Runnable promptMaskUpdate;

	/** Per caption length, the leaf a caption is copied into before it is padded. */
	private final Map<Integer, PackedCollection> captionInputs;

	/** Per caption length, the compiled assignment that pads a caption into {@link #tokenIds}. */
	private final Map<Integer, Runnable> captionCopies;

	/** The one token id {@link #loadToken} copies into {@link #tokenIds}. */
	private final PackedCollection tokenValue;

	/** The compiled assignment that fills {@link #tokenIds} with {@link Clap2Config#PAD_TOKEN}. */
	private Runnable tokenReset;

	/** The compiled tower, built on the first {@link #encode(PackedCollection)}. */
	private CompiledModel compiled;

	/**
	 * Creates a text encoder over the given weights, without a tokenizer: it embeds token ids,
	 * but cannot {@link #encode(String) encode} a caption given as text.
	 *
	 * @param config  the architecture
	 * @param weights the weights
	 */
	public Clap2TextEncoder(Clap2Config config, StateDictionary weights) {
		this(config, weights, null);
	}

	/**
	 * Creates a text encoder over the given weights and tokenizer.
	 *
	 * @param config    the architecture
	 * @param weights   the weights
	 * @param tokenizer the caption tokenizer, or null for an encoder that is only given token ids
	 */
	public Clap2TextEncoder(Clap2Config config, StateDictionary weights, Clap2Tokenizer tokenizer) {
		this.config = config;
		this.weights = weights;
		this.tokenizer = tokenizer;
		this.tokenIds = new PackedCollection(shape(BATCH, config.getMaxTextLength()));
		this.promptMask = new PackedCollection(shape(BATCH, config.getMaxTextLength()));
		this.promptLength = new PackedCollection(shape(1));
		this.tokenIds.clear();
		this.promptMask.clear();
		this.captionInputs = new HashMap<>();
		this.captionCopies = new HashMap<>();
		this.tokenValue = new PackedCollection(shape(1));
	}

	/**
	 * The leaf captions of the given length are copied into, created on first use.
	 *
	 * @param count the caption length
	 * @return the leaf, shape {@code [count]}
	 */
	private PackedCollection captionInput(int count) {
		return captionInputs.computeIfAbsent(count, n -> new PackedCollection(shape(n)));
	}

	/** The architecture of this encoder. */
	public Clap2Config getConfig() { return config; }

	/** The caption tokenizer, or null for an encoder created without one. */
	public Clap2Tokenizer getTokenizer() { return tokenizer; }

	/**
	 * The token ids this encoder reads for a caption: the caption framed by its tokenizer as
	 * {@code [BOS, ..., EOS]} ({@link Clap2Tokenizer#encodeCaption}). Tokenizing is host work and
	 * touches no device memory, so a caller embedding many captions can tokenize, and so validate,
	 * all of them before it allocates anything.
	 *
	 * @param caption the caption
	 * @return the ids, which {@link #encode(int[])} embeds
	 * @throws IllegalStateException    if this encoder has no tokenizer
	 * @throws IllegalArgumentException if the caption is longer than the compiled text length
	 */
	public int[] tokenize(String caption) {
		if (tokenizer == null) {
			throw new IllegalStateException("A caption given as text requires a tokenizer; load the "
					+ "model with the exported rank file, or encode the caption's token ids");
		}

		int[] ids = tokenizer.encodeCaption(caption);
		if (ids.length > config.getMaxTextLength()) {
			throw new IllegalArgumentException("Caption \"" + caption + "\" is " + ids.length
					+ " tokens, more than the " + config.getMaxTextLength()
					+ " the text tower is compiled for; load the model with a longer text length");
		}
		return ids;
	}

	/**
	 * Embeds a caption given as text: the caption is {@link #tokenize(String) tokenized} by this
	 * encoder's tokenizer and its ids embedded as {@link #encode(int[])} embeds them. The result
	 * is the compiled encoder's output and is overwritten by the next caption.
	 *
	 * @param caption the caption
	 * @return the embedding, shape {@code [1, embedDim]}
	 * @throws IllegalStateException    if this encoder has no tokenizer
	 * @throws IllegalArgumentException if the caption is longer than the compiled text length
	 */
	public PackedCollection encode(String caption) {
		return encode(tokenize(caption));
	}

	/**
	 * Embeds a tokenized caption. The embedding is not normalized. The result is the compiled
	 * encoder's output and is overwritten by the next caption, so a caller that keeps several
	 * embeddings copies each one.
	 *
	 * @param tokens the caption's token ids, {@code [BOS, ..., EOS]}, at most
	 *               {@link Clap2Config#getMaxTextLength()} of them, each within the vocabulary
	 * @return the embedding, shape {@code [1, embedDim]}
	 * @throws IllegalArgumentException if the caption is longer than the compiled length
	 */
	public PackedCollection encode(PackedCollection tokens) {
		return forward(loadCaption(tokens));
	}

	/**
	 * Embeds a caption given as the token ids a {@link Clap2Tokenizer} produces. The input is reset
	 * to {@link Clap2Config#PAD_TOKEN} and the ids are loaded into it one at a time, as an
	 * autoregressive model loads its prompt, so no kernel depends on the caption's length. The
	 * ids are checked against the vocabulary before any is loaded. The result is the compiled
	 * encoder's output and is overwritten by the next caption, as for
	 * {@link #encode(PackedCollection)}.
	 *
	 * @param tokens the caption's token ids, {@code [BOS, ..., EOS]}, at least one and at most
	 *               {@link Clap2Config#getMaxTextLength()} of them
	 * @return the embedding, shape {@code [1, embedDim]}
	 * @throws IllegalArgumentException if the caption is empty, longer than the compiled length, or
	 *                                  holds an id outside the vocabulary
	 */
	public PackedCollection encode(int[] tokens) {
		int length = config.getMaxTextLength();
		if (tokens.length == 0 || tokens.length > length) {
			throw new IllegalArgumentException("Caption of " + tokens.length
					+ " tokens must hold at least one and at most the compiled length " + length);
		}

		for (int i = 0; i < tokens.length; i++) {
			if (tokens[i] < 0 || tokens[i] >= config.getVocabularySize()) {
				throw new IllegalArgumentException("Token id " + tokens[i] + " at position " + i
						+ " is outside the vocabulary [0, " + config.getVocabularySize() + ")");
			}
		}

		if (tokenReset == null) {
			tokenReset = a("clap2TokenReset", p(tokenIds), c((double) Clap2Config.PAD_TOKEN)
					.repeat(BATCH * length).reshape(shape(BATCH, length))).get();
		}

		tokenReset.run();
		for (int i = 0; i < tokens.length; i++) {
			loadToken(i, tokens[i]);
		}

		updatePromptMask(tokens.length);
		return forward(tokenIds);
	}

	/**
	 * Places one token id at a position of the tower's input, as an autoregressive model loads the
	 * token of each step: the id is written to a one-element collection and copied into place.
	 *
	 * @param position the position, below the compiled length
	 * @param id       the token id
	 */
	private void loadToken(int position, int id) {
		tokenValue.fill((double) id);
		tokenIds.setFrom(position, tokenValue, 0, 1);
	}

	/**
	 * Runs the tower, compiling it on first use.
	 *
	 * @param ids the token id input, shape {@code [batch, maxTextLength]}
	 * @return the embedding, shape {@code [1, embedDim]}
	 */
	private PackedCollection forward(PackedCollection ids) {
		if (compiled == null) {
			Model model = new Model(shape(BATCH, config.getMaxTextLength()));
			model.add(block());
			compiled = model.compile(false);
		}

		return compiled.forward(ids);
	}

	/**
	 * Loads a caption into the tower's inputs: the token ids, padded to the compiled length with
	 * {@link Clap2Config#PAD_TOKEN} by a kernel that reads the caption, and the prompt mask every
	 * attention and the pool read. The padding kernel depends on the caption's length, so one is
	 * compiled per length and reused. A caption always holds at least one token, since a
	 * {@link PackedCollection} cannot have zero size, so the gather's last index is never negative.
	 *
	 * @param tokens the caption's token ids
	 * @return the token id input, shape {@code [batch, maxTextLength]}
	 */
	private PackedCollection loadCaption(PackedCollection tokens) {
		int length = config.getMaxTextLength();
		int count = tokens.getShape().getTotalSize();
		if (count > length) {
			throw new IllegalArgumentException("Caption of " + count
					+ " tokens exceeds the compiled length " + length);
		}

		captionCopies.computeIfAbsent(count, n -> {
			CollectionProducer position = integers(0, length);
			CollectionProducer gathered = c(shape(length), p(captionInput(n)),
					min(position, c((double) (n - 1))));
			return a("clap2TokenIds", p(tokenIds), greaterThan(c((double) n), position, gathered,
					c((double) Clap2Config.PAD_TOKEN)).reshape(shape(BATCH, length))).get();
		});
		captionInput(count).setFrom(0, tokens);
		captionCopies.get(count).run();
		updatePromptMask(count);
		return tokenIds;
	}

	/**
	 * Writes the prompt mask every attention and the pool read: one for the first {@code count}
	 * positions, zero beyond.
	 *
	 * @param count the number of caption tokens
	 */
	private void updatePromptMask(int count) {
		int length = config.getMaxTextLength();
		promptLength.fill(count);
		if (promptMaskUpdate == null) {
			TraversalPolicy maskShape = shape(BATCH, length);
			CollectionProducer limit = cp(promptLength).repeat(BATCH * length).reshape(maskShape);
			promptMaskUpdate = a("clap2PromptMask", p(promptMask),
					lessThan(integers(0, length).reshape(maskShape), limit)).get();
		}

		promptMaskUpdate.run();
	}

	/**
	 * The tower as a block from token ids {@code [batch, maxTextLength]} to the embedding
	 * {@code [batch, embedDim]}; its attention layers read the prompt mask
	 * {@link #encode(PackedCollection)} fills.
	 *
	 * @return the tower
	 */
	public Block block() {
		int length = config.getMaxTextLength();
		Map<String, Object> arguments = config.toTextPdslSettings();
		arguments.put("batch", BATCH);
		arguments.put("weights", weights);
		arguments.put("inv_freq", computeInvFreq(config.getTextHeadDim(), Clap2Config.ROPE_THETA));
		arguments.put("padding_mask", everyPositionValid(BATCH, length));
		arguments.put("key_mask", cp(promptMask));

		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ASSET), "clap2_text_tower",
				shape(BATCH, length), arguments);
	}

	@Override
	public void destroy() {
		if (compiled != null) {
			compiled.destroy();
		}

		tokenIds.destroy();
		promptMask.destroy();
		promptLength.destroy();
		tokenValue.destroy();
		captionInputs.values().forEach(PackedCollection::destroy);
		captionInputs.clear();
		captionCopies.clear();
	}
}
