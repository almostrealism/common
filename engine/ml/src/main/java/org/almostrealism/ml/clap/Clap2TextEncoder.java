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
 * encoding; the positions beyond it are filled with {@link Clap2Config#PAD_TOKEN} and masked out
 * of every attention and of the pool, so the compiled length does not change the embedding. The
 * tower is the {@code clap2_text_tower} layer of {@code /pdsl/clap2.pdsl}; this class fills the
 * prompt mask, binds the checkpoint and the rotary frequencies, and compiles it once.</p>
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

	/** The compiled tower, built on the first {@link #encode(PackedCollection)}. */
	private CompiledModel compiled;

	/**
	 * Creates a text encoder over the given weights.
	 *
	 * @param config  the architecture
	 * @param weights the weights
	 */
	public Clap2TextEncoder(Clap2Config config, StateDictionary weights) {
		this.config = config;
		this.weights = weights;
		this.tokenIds = new PackedCollection(shape(BATCH, config.getMaxTextLength()));
		this.promptMask = new PackedCollection(shape(BATCH, config.getMaxTextLength()));
		this.promptLength = new PackedCollection(shape(1));
		this.tokenIds.clear();
		this.promptMask.clear();
		this.captionInputs = new HashMap<>();
		this.captionCopies = new HashMap<>();
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
		PackedCollection ids = loadCaption(tokens);
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
	 * compiled per length and reused.
	 *
	 * @param tokens the caption's token ids
	 * @return the token id input, shape {@code [batch, maxTextLength]}
	 */
	private PackedCollection loadCaption(PackedCollection tokens) {
		int length = config.getMaxTextLength();
		int count = tokens.getShape().getTotalSize();
		// TODO(review): an empty caption (count 0) is not rejected; the gather then reads index -1.
		if (count > length) {
			throw new IllegalArgumentException("Caption of " + count
					+ " tokens exceeds the compiled length " + length);
		}

		promptLength.fill(count);
		captionCopies.computeIfAbsent(count, n -> {
			CollectionProducer position = integers(0, length);
			CollectionProducer gathered = c(shape(length), p(captionInput(n)),
					min(position, c((double) (n - 1))));
			return a("clap2TokenIds", p(tokenIds), greaterThan(c((double) n), position, gathered,
					c((double) Clap2Config.PAD_TOKEN)).reshape(shape(BATCH, length))).get();
		});
		captionInput(count).setFrom(0, tokens);
		captionCopies.get(count).run();

		if (promptMaskUpdate == null) {
			TraversalPolicy maskShape = shape(BATCH, length);
			CollectionProducer limit = cp(promptLength).repeat(BATCH * length).reshape(maskShape);
			promptMaskUpdate = a("clap2PromptMask", p(promptMask),
					lessThan(integers(0, length).reshape(maskShape), limit)).get();
		}

		promptMaskUpdate.run();
		return tokenIds;
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
		captionInputs.values().forEach(PackedCollection::destroy);
		captionInputs.clear();
		captionCopies.clear();
	}
}
