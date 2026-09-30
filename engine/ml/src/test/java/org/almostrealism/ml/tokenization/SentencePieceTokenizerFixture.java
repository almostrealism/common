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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a tiny tokenizer in the format {@code engine/ml/scripts/export_tokenizer.py} produces, so
 * the reader and the merge algorithm can be exercised without a released vocabulary.
 *
 * <p>The vocabulary is small enough to reason about by hand, and deliberately covers the three
 * behaviours that distinguish this tokenizer family: a word boundary written as
 * {@link SentencePieceBPETokenizer#BOUNDARY} rather than a byte remapping, merges applied in
 * priority order, and a character outside the vocabulary expanded into one token per UTF-8 byte.
 * With {@link #PROMPT} it produces {@link #EXPECTED}: the two single characters merge, and the
 * result merges again with the boundary that precedes it.</p>
 *
 * <p>This writer is the only other place the exported format is spelled out, which is deliberate:
 * a reader tested only against a file its own writer produced would agree with itself about a
 * format neither implements correctly. The gated parity test covers the released vocabulary.</p>
 */
public class SentencePieceTokenizerFixture {

	/** Text whose tokenization exercises both merges and the boundary marker. */
	public static final String PROMPT = "ab ab";

	/** The ids {@link #PROMPT} encodes to: {@code "ab"} then {@code "▁ab"}. */
	public static final long[] EXPECTED = {7, 8};

	/**
	 * A character absent from the vocabulary, encoded through byte fallback. It is deliberately
	 * multi-byte in UTF-8 ({@code U+20AC} is three bytes) so encoding exercises the expansion into
	 * several {@code <0xNN>} tokens and decoding exercises accumulating those bytes back into one
	 * character in {@link SentencePieceBPETokenizer#flushBytes}.
	 */
	public static final String UNKNOWN = "€";

	/** Padding token id. */
	public static final int PAD = 0;

	/** End-of-sequence token id. */
	public static final int EOS = 1;

	/** Beginning-of-sequence token id. */
	public static final int BOS = 2;

	/** Unknown token id. */
	public static final int UNK = 3;

	/** The vocabulary, in token-id order. */
	private static final String[] VOCAB = {
			"<pad>", "<eos>", "<bos>", "<unk>",
			"a", "b", "▁", "ab", "▁ab", "<0xE2>", "<0x82>", "<0xAC>"
	};

	/** The merges, in priority order: {@code a + b}, then the boundary with the result. */
	private static final String[][] MERGES = {
			{"a", "b"},
			{"▁", "ab"}
	};

	/**
	 * Writes the fixture tokenizer to a temporary file, deleted when the JVM exits.
	 *
	 * @return the written file
	 * @throws IOException if the file cannot be written
	 */
	public File write() throws IOException {
		File file = File.createTempFile("ar-tokenizer-fixture", ".bin");
		file.deleteOnExit();
		write(file, 0x4152544B, 1);
		return file;
	}

	/**
	 * Writes the fixture tokenizer with the given header, so a reader's rejection of a file that is
	 * not an exported tokenizer, or is of another version, can be exercised.
	 *
	 * @param file    where to write
	 * @param magic   the magic value to write
	 * @param version the version to write
	 * @throws IOException if the file cannot be written
	 */
	public void write(File file, int magic, int version) throws IOException {
		write(file, magic, version, VOCAB, MERGES, new int[] {BOS, EOS, PAD, UNK});
	}

	/**
	 * Writes a tokenizer over the given vocabulary, merges and special ids, so a decode test can
	 * craft the exact token strings it needs without disturbing the parity vocabulary.
	 *
	 * @param file     where to write
	 * @param magic    the magic value to write
	 * @param version  the version to write
	 * @param vocab    the vocabulary, in token-id order
	 * @param merges   the merges, in priority order
	 * @param specials the special ids in {@code {bos, eos, pad, unk}} order, each {@code -1} for none
	 * @throws IOException if the file cannot be written
	 */
	protected void write(File file, int magic, int version, String[] vocab, String[][] merges,
			int[] specials) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
			out.writeInt(magic);
			out.writeInt(version);

			out.writeInt(vocab.length);
			for (String token : vocab) {
				writeString(out, token);
			}

			out.writeInt(merges.length);
			for (String[] merge : merges) {
				writeString(out, merge[0]);
				writeString(out, merge[1]);
			}

			for (int special : specials) {
				out.writeInt(special);
			}
		}
	}

	/**
	 * Writes a file that is a valid tokenizer except for the header, at a temporary location.
	 *
	 * @param magic   the magic value to write
	 * @param version the version to write
	 * @return the written file
	 * @throws IOException if the file cannot be written
	 */
	public File writeWithHeader(int magic, int version) throws IOException {
		File file = File.createTempFile("ar-tokenizer-fixture", ".bin");
		file.deleteOnExit();
		write(file, magic, version);
		return file;
	}

	/**
	 * Writes a file with a valid header but the given raw vocabulary count, so a reader's rejection
	 * of a corrupt count -- negative, which would otherwise be a {@link NegativeArraySizeException},
	 * or implausibly large, which would otherwise be an {@link OutOfMemoryError} -- can be exercised.
	 * Nothing follows the count, because a reader that validates it rejects the file before reading
	 * any vocabulary entry.
	 *
	 * @param vocabCount the vocabulary count to declare
	 * @return the written file
	 * @throws IOException if the file cannot be written
	 */
	public File writeWithVocabCount(int vocabCount) throws IOException {
		File file = File.createTempFile("ar-tokenizer-fixture", ".bin");
		file.deleteOnExit();

		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
			out.writeInt(0x4152544B);
			out.writeInt(1);
			out.writeInt(vocabCount);
		}

		return file;
	}

	/**
	 * The fixture tokenizer, read from a freshly written file.
	 *
	 * @return the tokenizer
	 * @throws IOException if the file cannot be written or read
	 */
	public SentencePieceBPETokenizer tokenizer() throws IOException {
		return new SentencePieceBPETokenizer(write().getPath());
	}

	/**
	 * A tokenizer over an arbitrary vocabulary with no merges, for exercising decode behaviours that
	 * depend on the exact token strings -- byte-token boundaries and invalid UTF-8 folding -- rather
	 * than on the merge algorithm.
	 *
	 * @param vocab the vocabulary, in token-id order
	 * @return the tokenizer
	 * @throws IOException if the file cannot be written or read
	 */
	public SentencePieceBPETokenizer tokenizerFor(String[] vocab) throws IOException {
		return tokenizerFor(vocab, new int[] {-1, -1, -1, -1});
	}

	/**
	 * A tokenizer over an arbitrary vocabulary with no merges and the given special ids, for
	 * exercising the byte-fallback contract: a byte token the vocabulary does not contain is either
	 * substituted by the unknown token or, when there is none, rejected during encoding.
	 *
	 * @param vocab    the vocabulary, in token-id order
	 * @param specials the special ids in {@code {bos, eos, pad, unk}} order, each {@code -1} for none
	 * @return the tokenizer
	 * @throws IOException if the file cannot be written or read
	 */
	public SentencePieceBPETokenizer tokenizerFor(String[] vocab, int[] specials) throws IOException {
		File file = File.createTempFile("ar-tokenizer-fixture", ".bin");
		file.deleteOnExit();
		write(file, 0x4152544B, 1, vocab, new String[0][], specials);
		return new SentencePieceBPETokenizer(file.getPath());
	}

	/**
	 * The byte-fallback tokens {@link #UNKNOWN} expands to.
	 *
	 * @return the token ids
	 */
	public List<Integer> unknownBytes() {
		List<Integer> ids = new ArrayList<>();

		for (byte value : UNKNOWN.getBytes(StandardCharsets.UTF_8)) {
			String token = String.format("<0x%02X>", value & 0xFF);

			for (int i = 0; i < VOCAB.length; i++) {
				if (VOCAB[i].equals(token)) ids.add(i);
			}
		}

		return ids;
	}

	/**
	 * Writes one length-prefixed UTF-8 string.
	 *
	 * @param out   the destination
	 * @param value the string
	 * @throws IOException if writing fails
	 */
	protected void writeString(DataOutputStream out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}
}
