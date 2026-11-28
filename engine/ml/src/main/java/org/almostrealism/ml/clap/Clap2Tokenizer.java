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

import org.almostrealism.ml.tokenization.RegexPreTokenizer;
import org.almostrealism.ml.tokenization.TiktokenBPETokenizer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The caption tokenizer of a CLAP2 text tower: the GPT-2 {@code r50k_base} tiktoken encoding, with
 * the tower's own control tokens {@link Clap2Config#BOS_TOKEN BOS}, {@link Clap2Config#EOS_TOKEN
 * EOS} and {@link Clap2Config#PAD_TOKEN PAD} reserved above the encoding's vocabulary.
 *
 * <p>The vocabulary is the rank file a CLAP2 checkpoint ships beside its weights
 * ({@code open_clip_tiktoken/r50k_base.tiktoken}), which
 * {@code engine/ml/scripts/export_clap2_tokenizer.py} validates and exports. Captions are encoded
 * verbatim, with no lowercasing or punctuation cleanup, as the reference tokenizer encodes them.</p>
 *
 * <p>{@link #encodeCaption(String)} gives a caption the framing the text tower was trained with:
 * {@code [BOS, ..., EOS]}, at most {@link Clap2Config#CONTEXT_LENGTH} ids. That is the form
 * {@link Clap2TextEncoder#encode(int[])} reads; the positions beyond the caption are filled with
 * {@link Clap2Config#PAD_TOKEN} and masked by the tower itself, which knows the length it is
 * compiled for, so the framed ids carry no padding.</p>
 *
 * @see Clap2TextEncoder
 */
public class Clap2Tokenizer extends TiktokenBPETokenizer {

	/**
	 * Reads the tokenizer from an {@code r50k_base} rank file.
	 *
	 * @param rankFile the rank file
	 * @throws IOException if the file cannot be read or is malformed, or a rank reaches the
	 *                     tower's control tokens
	 */
	public Clap2Tokenizer(Path rankFile) throws IOException {
		super(RegexPreTokenizer.r50k(), rankFile);
		if (getVocabSize() > Clap2Config.EOS_TOKEN) {
			throw new IOException("The rank file assigns rank " + (getVocabSize() - 1) + ", but the "
					+ "tower's control tokens begin at " + Clap2Config.EOS_TOKEN);
		}
	}

	/**
	 * Frames a caption as the text tower reads it: {@link Clap2Config#BOS_TOKEN}, the ids of the
	 * caption's {@code r50k_base} encoding, {@link Clap2Config#EOS_TOKEN}. A caption that does not
	 * fit the context is rejected rather than cut, since a cut caption describes something else.
	 *
	 * @param caption the caption, encoded verbatim
	 * @return the ids, {@code [BOS, ..., EOS]}, without padding
	 * @throws IllegalArgumentException if the framed caption is longer than
	 *                                  {@link Clap2Config#CONTEXT_LENGTH}
	 */
	public int[] encodeCaption(String caption) {
		int[] ids = encode(caption, true);
		if (ids.length > Clap2Config.CONTEXT_LENGTH) {
			throw new IllegalArgumentException("Caption of " + ids.length + " tokens, BOS and EOS "
					+ "included, exceeds the context of " + Clap2Config.CONTEXT_LENGTH);
		}
		return ids;
	}

	@Override
	protected int getBOSToken() { return Clap2Config.BOS_TOKEN; }

	@Override
	protected int getEOSToken() { return Clap2Config.EOS_TOKEN; }

	@Override
	protected int getPADToken() { return Clap2Config.PAD_TOKEN; }

	/**
	 * The encoding covers every byte, so no piece is ever unknown; there is no unknown token.
	 *
	 * @return {@code -1}
	 */
	@Override
	protected int getUNKToken() { return -1; }
}
