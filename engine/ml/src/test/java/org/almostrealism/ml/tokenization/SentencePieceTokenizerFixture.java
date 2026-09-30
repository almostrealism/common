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

	/** A character absent from the vocabulary, encoded through byte fallback. */
	public static final String UNKNOWN = "z";

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
			"a", "b", "▁", "ab", "▁ab", "<0x7A>"
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
		try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
			out.writeInt(magic);
			out.writeInt(version);

			out.writeInt(VOCAB.length);
			for (String token : VOCAB) {
				writeString(out, token);
			}

			out.writeInt(MERGES.length);
			for (String[] merge : MERGES) {
				writeString(out, merge[0]);
				writeString(out, merge[1]);
			}

			out.writeInt(BOS);
			out.writeInt(EOS);
			out.writeInt(PAD);
			out.writeInt(UNK);
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
	 * The fixture tokenizer, read from a freshly written file.
	 *
	 * @return the tokenizer
	 * @throws IOException if the file cannot be written or read
	 */
	public SentencePieceBPETokenizer tokenizer() throws IOException {
		return new SentencePieceBPETokenizer(write().getPath());
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
