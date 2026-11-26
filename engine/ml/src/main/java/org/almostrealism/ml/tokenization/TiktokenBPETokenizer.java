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

package org.almostrealism.ml.tokenization;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A byte-level BPE tokenizer over a tiktoken encoding: a vocabulary of byte sequences whose ranks
 * are both their token ids and their merge order.
 *
 * <p>The vocabulary is read from a tiktoken rank file: one line per token, the base64 of the
 * token's bytes, a space, and its rank. A tiktoken encoding has no separate merge list. Two
 * adjacent tokens merge when their concatenation is a token, the pair whose concatenation has the
 * lowest rank first (the leftmost on a tie), which {@link #getMergePriority(String, String)}
 * reads from the ranks. A pre-tokenized piece that is itself a token is emitted whole, before any
 * merging, as tiktoken looks a piece up first. Ranks need not be dense: an id no line names
 * decodes to nothing.</p>
 *
 * <p>The encoding's split pattern is the {@link PreTokenizer} a subclass passes, and its control
 * tokens are the subclass's {@link #getBOSToken()} and friends: tiktoken itself encodes text as
 * ordinary bytes ({@code encode_ordinary}), so no added token is matched in the input.</p>
 *
 * @see RegexPreTokenizer#r50k()
 */
public abstract class TiktokenBPETokenizer extends ByteLevelBPETokenizer {

	/**
	 * Reads a tokenizer from a tiktoken rank file.
	 *
	 * @param preTokenizer the encoding's split pattern
	 * @param rankFile     the rank file
	 * @throws IOException if the file cannot be read, a line is not a base64 token and a
	 *                     non-negative rank, or a token or rank appears twice
	 */
	protected TiktokenBPETokenizer(PreTokenizer preTokenizer, Path rankFile) throws IOException {
		super(preTokenizer);

		List<String> tokens = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(rankFile, StandardCharsets.US_ASCII)) {
			String line;
			int number = 0;
			while ((line = reader.readLine()) != null) {
				number++;
				if (!line.trim().isEmpty()) {
					readRank(line, number, tokens);
				}
			}
		}

		this.vocab = tokens.toArray(new String[0]);
	}

	/**
	 * Reads one line of a rank file into {@link #vocabMap}.
	 *
	 * @param line   the line, {@code <base64 token> <rank>}
	 * @param number the line number, for the exception's message
	 * @param tokens the tokens by id, extended to cover this rank; ids no line names stay null
	 * @throws IOException if the line is malformed or repeats a token or a rank
	 */
	private void readRank(String line, int number, List<String> tokens) throws IOException {
		String[] parts = line.trim().split(" ");
		if (parts.length != 2) {
			throw new IOException("Line " + number + " of the rank file is not a token and a rank");
		}

		String token;
		int rank;
		try {
			token = ByteLevelEncoder.encode(Base64.getDecoder().decode(parts[0]));
			rank = Integer.parseInt(parts[1]);
		} catch (IllegalArgumentException e) {
			throw new IOException("Line " + number + " of the rank file is malformed: " + e.getMessage(), e);
		}

		if (rank < 0) {
			throw new IOException("Line " + number + " of the rank file has the negative rank " + rank);
		}
		if (vocabMap.put(token, rank) != null) {
			throw new IOException("The token on line " + number + " of the rank file appears twice");
		}

		while (tokens.size() <= rank) tokens.add(null);
		if (tokens.set(rank, token) != null) {
			throw new IOException("Rank " + rank + " on line " + number + " of the rank file is assigned twice");
		}
	}

	/**
	 * The symbols a pre-tokenized piece starts merging from: the whole piece when it is itself a
	 * token, and otherwise one symbol per byte.
	 *
	 * @param segment one piece produced by the pre-tokenizer
	 * @return the initial symbols
	 */
	@Override
	protected List<String> toSymbols(String segment) {
		String encoded = ByteLevelEncoder.encode(segment);
		return vocabMap.containsKey(encoded) ? List.of(encoded) : super.toSymbols(segment);
	}

	/**
	 * The rank of the concatenation of two tokens, which merge when it is a token.
	 *
	 * @param left  the left token
	 * @param right the right token
	 * @return the rank of {@code left + right}, or {@link Integer#MAX_VALUE} if it is not a token
	 */
	@Override
	protected int getMergePriority(String left, String right) {
		return vocabMap.getOrDefault(left + right, Integer.MAX_VALUE);
	}
}
