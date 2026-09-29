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

import org.almostrealism.ml.Tokenizer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A SentencePiece-style BPE tokenizer: the scheme shared by Gemma, Llama and Mistral, in which
 * a word boundary is marked by {@code U+2581} rather than by a byte-level remapping of the
 * whole input, and a character the vocabulary does not contain is represented by one
 * {@code <0xNN>} token per UTF-8 byte.
 *
 * <p>It differs from its {@link ByteLevelBPETokenizer} superclass only in how text becomes
 * symbols and how symbols become text again; the merge algorithm, the vocabulary lookup and
 * the special-token handling are inherited unchanged.</p>
 *
 * <p>The vocabulary and merge list are read from the binary written by
 * {@code engine/ml/scripts/export_tokenizer.py}, not from {@code tokenizer.json}. A 256k
 * vocabulary is tens of megabytes of JSON and this module has no JSON parser, so the
 * conversion happens once, in Python, into a length-prefixed format {@link DataInputStream}
 * can read directly. Length prefixes rather than delimiters are required because the
 * vocabulary contains tokens that are runs of newlines and runs of {@code U+2581}.</p>
 *
 * <p>Special tokens are not added by {@link #encodeAsLong(String)}. The exported tokenizers
 * carry an empty template post-processor, so the reference implementation adds neither a
 * beginning- nor an end-of-sequence token, and a prompt encoder fed an extra leading token
 * would not reproduce it. A caller that wants them can use
 * {@link #encode(String, boolean)} directly.</p>
 */
public class SentencePieceBPETokenizer extends ByteLevelBPETokenizer implements Tokenizer {

	/** Marks a word boundary in a SentencePiece vocabulary. */
	public static final char BOUNDARY = '▁';

	/** Magic bytes at the head of an exported tokenizer. */
	private static final int MAGIC = 0x4152544B;

	/** The format version this reader understands. */
	private static final int VERSION = 1;

	/** Merge pair ("left right") to its priority, lower being applied first. */
	private final Map<String, Integer> mergePriority;

	/** Beginning-of-sequence token id, or {@code -1} when the tokenizer has none. */
	private final int bosToken;

	/** End-of-sequence token id, or {@code -1} when the tokenizer has none. */
	private final int eosToken;

	/** Padding token id, or {@code -1} when the tokenizer has none. */
	private final int padToken;

	/** Unknown token id, or {@code -1} when the tokenizer has none. */
	private final int unkToken;

	/**
	 * Reads an exported tokenizer.
	 *
	 * @param path the binary written by {@code export_tokenizer.py}
	 * @throws IOException if the file cannot be read or is not an exported tokenizer
	 */
	public SentencePieceBPETokenizer(String path) throws IOException {
		super(PreTokenizer.WHOLE_TEXT);

		this.bpeMerges = new HashMap<>();
		this.mergePriority = new HashMap<>();

		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(new FileInputStream(path)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException(path + " is not an exported tokenizer");
			}

			int version = in.readInt();
			if (version != VERSION) {
				throw new IOException("Unsupported tokenizer format version " + version
						+ " in " + path + "; expected " + VERSION);
			}

			readVocabulary(in);
			readMerges(in);

			this.bosToken = in.readInt();
			this.eosToken = in.readInt();
			this.padToken = in.readInt();
			this.unkToken = in.readInt();
		}
	}

	/**
	 * Reads the vocabulary, which is stored in token-id order.
	 *
	 * @param in the stream positioned at the vocabulary
	 * @throws IOException if the stream ends early
	 */
	protected void readVocabulary(DataInputStream in) throws IOException {
		int size = in.readInt();

		this.vocab = new String[size];
		this.vocabMap = new HashMap<>(size);

		for (int i = 0; i < size; i++) {
			vocab[i] = readString(in);
			vocabMap.put(vocab[i], i);
		}
	}

	/**
	 * Reads the merge list, which is stored best merge first.
	 *
	 * @param in the stream positioned at the merge list
	 * @throws IOException if the stream ends early
	 */
	protected void readMerges(DataInputStream in) throws IOException {
		int count = in.readInt();

		for (int i = 0; i < count; i++) {
			String left = readString(in);
			String right = readString(in);
			String pair = left + " " + right;

			bpeMerges.put(pair, left + right);
			mergePriority.putIfAbsent(pair, i);
		}
	}

	/**
	 * Reads one length-prefixed UTF-8 string.
	 *
	 * @param in the stream positioned at the length
	 * @return the string
	 * @throws IOException if the stream ends early
	 */
	protected String readString(DataInputStream in) throws IOException {
		byte[] bytes = new byte[in.readInt()];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Marks word boundaries and expands anything outside the vocabulary into byte tokens.
	 *
	 * @param segment one segment produced by the pre-tokenizer
	 * @return the initial symbols, in order
	 */
	@Override
	protected List<String> toSymbols(String segment) {
		String marked = segment.replace(' ', BOUNDARY);
		List<String> symbols = new ArrayList<>();

		int i = 0;
		while (i < marked.length()) {
			int codePoint = marked.codePointAt(i);
			String character = new String(Character.toChars(codePoint));
			i += Character.charCount(codePoint);

			if (vocabMap.containsKey(character)) {
				symbols.add(character);
			} else {
				for (byte value : character.getBytes(StandardCharsets.UTF_8)) {
					symbols.add(byteToken(value));
				}
			}
		}

		return symbols;
	}

	/**
	 * Restores spaces and folds runs of byte tokens back into the characters they encode.
	 *
	 * @param symbols the concatenated vocabulary strings
	 * @return the decoded text
	 */
	@Override
	protected String fromSymbols(String symbols) {
		StringBuilder text = new StringBuilder();
		List<Byte> pending = new ArrayList<>();

		int i = 0;
		while (i < symbols.length()) {
			int value = byteTokenAt(symbols, i);

			if (value >= 0) {
				pending.add((byte) value);
				i += 6;
			} else {
				flushBytes(pending, text);
				char c = symbols.charAt(i);
				text.append(c == BOUNDARY ? ' ' : c);
				i++;
			}
		}

		flushBytes(pending, text);
		return text.toString();
	}

	/**
	 * The vocabulary token representing one raw byte.
	 *
	 * @param value the byte
	 * @return the token, of the form {@code <0xNN>}
	 */
	protected String byteToken(byte value) {
		return String.format("<0x%02X>", value & 0xFF);
	}

	/**
	 * Reads a {@code <0xNN>} byte token at the given offset.
	 *
	 * @param symbols the concatenated vocabulary strings
	 * @param offset  where to look
	 * @return the byte value, or {@code -1} if no byte token starts here
	 */
	protected int byteTokenAt(String symbols, int offset) {
		if (offset + 6 > symbols.length()) return -1;
		if (symbols.charAt(offset) != '<' || symbols.charAt(offset + 1) != '0'
				|| symbols.charAt(offset + 2) != 'x' || symbols.charAt(offset + 5) != '>') {
			return -1;
		}

		int high = Character.digit(symbols.charAt(offset + 3), 16);
		int low = Character.digit(symbols.charAt(offset + 4), 16);
		if (high < 0 || low < 0) return -1;

		return (high << 4) | low;
	}

	/**
	 * Appends any accumulated bytes as UTF-8 text and clears them.
	 *
	 * @param pending the accumulated bytes
	 * @param text    the destination
	 */
	protected void flushBytes(List<Byte> pending, StringBuilder text) {
		if (pending.isEmpty()) return;

		byte[] bytes = new byte[pending.size()];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = pending.get(i);
		}

		text.append(new String(bytes, StandardCharsets.UTF_8));
		pending.clear();
	}

	@Override
	protected int getMergePriority(String pair) {
		return mergePriority.getOrDefault(pair, Integer.MAX_VALUE);
	}

	@Override
	public long[] encodeAsLong(String text) {
		int[] tokens = encode(text, false);

		long[] result = new long[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			result[i] = tokens[i];
		}

		return result;
	}

	@Override
	public String decodeAsLong(long[] tokens) {
		int[] ids = new int[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			ids[i] = (int) tokens[i];
		}

		return decode(ids);
	}

	@Override
	protected int getBOSToken() { return bosToken; }

	@Override
	protected int getEOSToken() { return eosToken; }

	@Override
	protected int getPADToken() { return padToken; }

	@Override
	protected int getUNKToken() { return unkToken; }
}
