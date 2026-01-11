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

import ai.djl.sentencepiece.SpTokenizer;
import ai.djl.sentencepiece.SpVocabulary;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Tokenizer implementation that wraps the SentencePiece tokenizer from DJL.
 * <p>
 * SentencePiece is a language-independent subword tokenizer commonly used
 * in models like T5, BERT, and various audio generation models.
 */
public class SentencePieceTokenizer implements Tokenizer {

	private final SpTokenizer tokenizer;
	private final SpVocabulary vocabulary;

	/**
	 * Creates a SentencePieceTokenizer from a model input stream.
	 *
	 * @param modelStream input stream for the SentencePiece model file (.model)
	 * @throws IOException if the model cannot be loaded
	 */
	public SentencePieceTokenizer(InputStream modelStream) throws IOException {
		this.tokenizer = new SpTokenizer(modelStream);
		this.vocabulary = SpVocabulary.from(tokenizer);
	}

	/**
	 * Creates a SentencePieceTokenizer from the default classpath resource.
	 * <p>
	 * Looks for "spiece.model" on the classpath.
	 *
	 * @throws IOException if the model cannot be loaded
	 */
	public SentencePieceTokenizer() throws IOException {
		this(SentencePieceTokenizer.class.getClassLoader().getResourceAsStream("spiece.model"));
	}

	/**
	 * Returns the underlying SpTokenizer.
	 */
	public SpTokenizer getSpTokenizer() {
		return tokenizer;
	}

	/**
	 * Returns the vocabulary used for token ID lookups.
	 */
	public SpVocabulary getVocabulary() {
		return vocabulary;
	}

	@Override
	public long[] encodeAsLong(String text) {
		List<String> tokens = tokenizer.tokenize(text);
		long[] ids = new long[tokens.size()];
		for (int i = 0; i < tokens.size(); i++) {
			ids[i] = vocabulary.getIndex(tokens.get(i));
		}
		return ids;
	}

	@Override
	public String decodeAsLong(long[] tokens) {
		StringBuilder sb = new StringBuilder();
		for (long token : tokens) {
			String piece = vocabulary.getToken(token);
			if (piece != null) {
				sb.append(piece);
			}
		}
		// SentencePiece uses ▁ (U+2581) as word boundary marker, replace with space
		return sb.toString().replace("▁", " ").trim();
	}
}
