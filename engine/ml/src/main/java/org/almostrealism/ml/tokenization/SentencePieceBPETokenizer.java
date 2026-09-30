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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>Added tokens -- the control tokens and any other entries of the source tokenizer's added
 * vocabulary -- are carried in the binary and matched atomically before BPE runs, so text that
 * contains one encodes to its single id, as the reference does.</p>
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
	private static final int VERSION = 2;

	/**
	 * The largest vocabulary or merge count an exported tokenizer may declare. A serialized count
	 * beyond this is treated as a corrupt file rather than allocated, since every entry consumes at
	 * least four bytes on disk and the largest tokenizers this reader targets have a few hundred
	 * thousand entries.
	 */
	private static final int MAX_ENTRIES = 1 << 24;

	/**
	 * The largest length, in bytes, a single serialized string may declare. Vocabulary tokens are
	 * short even when they are runs of newlines or boundary markers; a length beyond this is treated
	 * as a corrupt file rather than allocated.
	 */
	private static final int MAX_STRING_BYTES = 1 << 20;

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
	 * Ids of added tokens the source marks special. The reference decoder drops every special token
	 * from clean text, so these are skipped on decode alongside the four standard control ids, even
	 * when their id lies outside {@code {bos, eos, pad, unk}}.
	 */
	private final Set<Integer> specialTokens = new HashSet<>();

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

			this.bosToken = readControlId("beginning-of-sequence", in);
			this.eosToken = readControlId("end-of-sequence", in);
			this.padToken = readControlId("padding", in);
			this.unkToken = readControlId("unknown", in);

			readAddedTokens(in);
		}
	}

	/**
	 * Reads the vocabulary, which is stored in token-id order.
	 *
	 * @param in the stream positioned at the vocabulary
	 * @throws IOException if the stream ends early
	 */
	protected void readVocabulary(DataInputStream in) throws IOException {
		int size = readCount("vocabulary size", in, MAX_ENTRIES);

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
		int count = readCount("merge count", in, MAX_ENTRIES);

		for (int i = 0; i < count; i++) {
			String left = readString(in);
			String right = readString(in);
			String pair = left + " " + right;

			bpeMerges.put(pair, left + right);
			mergePriority.putIfAbsent(pair, i);
		}
	}

	/**
	 * Reads one control token id, which must be {@code -1} (the tokenizer has none) or an index into
	 * the vocabulary. An out-of-range id would otherwise load successfully and make
	 * {@link #encode(String, boolean)} emit an index no embedding row exists for, while decode
	 * silently skipped it.
	 *
	 * @param description which control token the id names, for the error message
	 * @param in          the stream positioned at the id
	 * @return the id, or {@code -1} when the tokenizer has none
	 * @throws IOException if the stream ends early or the id is outside the vocabulary
	 */
	protected int readControlId(String description, DataInputStream in) throws IOException {
		int id = in.readInt();
		if (id < -1 || id >= vocab.length) {
			throw new IOException("Invalid " + description + " token id " + id
					+ " in exported tokenizer; expected -1 or 0.." + (vocab.length - 1));
		}
		return id;
	}

	/**
	 * Reads the added tokens, each an id, whether it is matched after normalization, whether it is
	 * special, and its content. An added token is matched atomically in the input before BPE runs, so
	 * an occurrence of its content -- a control token such as {@code <pad>} included -- encodes to its
	 * one id as it does in the source tokenizer, rather than being split into BPE pieces. A special
	 * added token is additionally recorded so {@link #isSpecialToken(int)} skips it on decode.
	 *
	 * <p>The exported format requires an added token's content to equal the vocabulary string at its
	 * id, which {@code export_tokenizer.py} guarantees when it places both in one id-indexed table. A
	 * record that breaks this would encode the content to an id that decodes as a different string,
	 * so it is rejected.</p>
	 *
	 * @param in the stream positioned at the added tokens
	 * @throws IOException if the stream ends early, or an added token has empty content, an id
	 *                     outside the vocabulary, or content differing from the vocabulary at its id
	 */
	protected void readAddedTokens(DataInputStream in) throws IOException {
		int count = readCount("added token count", in, MAX_ENTRIES);

		for (int i = 0; i < count; i++) {
			int id = in.readInt();
			boolean normalized = in.readBoolean();
			boolean special = in.readBoolean();
			String content = readString(in);

			if (id < 0 || id >= vocab.length) {
				throw new IOException("Added token id " + id + " is outside the vocabulary of "
						+ vocab.length + " tokens");
			}

			if (content.isEmpty()) {
				throw new IOException("Added token " + id + " has empty content");
			}

			if (!content.equals(vocab[id])) {
				throw new IOException("Added token " + id + " has content \"" + content
						+ "\" but the vocabulary holds \"" + vocab[id] + "\" at that id");
			}

			(normalized ? normalizedAddedTokens : addedTokens).put(content, id);
			if (special) specialTokens.add(id);
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
		byte[] bytes = new byte[readCount("string length", in, MAX_STRING_BYTES)];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * Reads one non-negative count or length and checks it against a format-appropriate bound before
	 * it is used to size an allocation. A file with the correct magic but a corrupt body can declare
	 * a negative or enormous count; rejecting it here yields a controlled {@link IOException} rather
	 * than a {@link NegativeArraySizeException} or {@link OutOfMemoryError}.
	 *
	 * @param description what the count measures, for the error message
	 * @param in          the stream positioned at the count
	 * @param limit       the largest value the format allows
	 * @return the count
	 * @throws IOException if the value is negative or exceeds {@code limit}
	 */
	protected static int readCount(String description, DataInputStream in, int limit)
			throws IOException {
		int value = in.readInt();
		if (value < 0 || value > limit) {
			throw new IOException("Invalid " + description + " " + value
					+ " in exported tokenizer; expected 0.." + limit);
		}
		return value;
	}

	/**
	 * Marks word boundaries and expands anything outside the vocabulary into byte tokens.
	 *
	 * <p>Byte fallback is only valid when every {@code <0xNN>} token it emits exists in the
	 * vocabulary. HuggingFace BPE enables byte fallback only when all 256 byte atoms are present, so
	 * a complete vocabulary never reaches a missing one, and {@code export_tokenizer.py} rejects an
	 * incomplete byte-fallback vocabulary before it is written. A missing byte token therefore means
	 * a corrupt or incomplete binary; rather than substitute the unknown token per byte -- a
	 * different fallback than the source tokenizer's one unknown token per unknown character -- this
	 * rejects the byte, since reproducing the source ids is impossible either way.</p>
	 *
	 * @param segment one segment produced by the pre-tokenizer
	 * @return the initial symbols, in order
	 * @throws IllegalArgumentException if a character falls back to a byte token the vocabulary does
	 *         not contain
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
					String token = byteToken(value);
					if (!vocabMap.containsKey(token)) {
						throw new IllegalArgumentException(String.format(
								"Cannot encode U+%04X: byte token %s is not in the vocabulary; "
										+ "a byte-fallback tokenizer must contain all 256 <0xNN> "
										+ "tokens to reproduce the source tokenizer",
								codePoint, token));
					}
					symbols.add(token);
				}
			}
		}

		return symbols;
	}

	/**
	 * Restores spaces and folds runs of byte tokens back into the characters they encode.
	 *
	 * <p>The reference decoder ({@code Replace(boundary -> space)}, then {@code ByteFallback}, then
	 * {@code Fuse}) runs before the tokens are concatenated, so a byte is recovered only when a
	 * whole token is exactly {@code <0xNN>}. Decoding token by token here matches that: an ordinary
	 * token that merely contains that substring, or two neighbours that only form it once joined,
	 * is left untouched, and its boundary marker is turned into a space in isolation.</p>
	 *
	 * @param tokens the vocabulary strings of the token sequence, in order
	 * @return the decoded text
	 */
	@Override
	protected String fromSymbols(List<String> tokens) {
		StringBuilder text = new StringBuilder();
		List<Byte> pending = new ArrayList<>();

		for (String token : tokens) {
			int value = byteValue(token);

			if (value >= 0) {
				pending.add((byte) value);
			} else {
				flushBytes(pending, text);
				text.append(token.replace(BOUNDARY, ' '));
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
	 * The byte a {@code <0xNN>} token encodes, or {@code -1} when the token is not a byte token.
	 *
	 * <p>A byte is recognized only when the whole token is exactly {@code <0xNN>}, matching the
	 * reference {@code ByteFallback} decoder, so an ordinary token that merely contains that
	 * substring is not misread as a byte.</p>
	 *
	 * @param token one vocabulary string
	 * @return the byte value, or {@code -1} if the token is not a byte token
	 */
	protected int byteValue(String token) {
		if (token.length() != 6) return -1;
		if (token.charAt(0) != '<' || token.charAt(1) != '0'
				|| token.charAt(2) != 'x' || token.charAt(5) != '>') {
			return -1;
		}

		int high = Character.digit(token.charAt(3), 16);
		int low = Character.digit(token.charAt(4), 16);
		if (high < 0 || low < 0) return -1;

		return (high << 4) | low;
	}

	/**
	 * Appends any accumulated bytes as UTF-8 text and clears them.
	 *
	 * <p>When the run is not valid UTF-8 the reference {@code ByteFallback} decoder emits one
	 * replacement character per byte token, not one per malformed subsequence, so a truncated
	 * sequence such as {@code <0xE5>, <0x8F>} produces two replacement characters. This matches
	 * that by decoding the run strictly and, on failure, appending one {@code U+FFFD} per pending
	 * byte -- rather than delegating to {@link String#String(byte[], java.nio.charset.Charset)},
	 * whose substitution collapses a malformed run into a single replacement character.</p>
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

		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			text.append(decoder.decode(ByteBuffer.wrap(bytes)));
		} catch (CharacterCodingException invalid) {
			for (int i = 0; i < bytes.length; i++) {
				text.append('�');
			}
		}

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

	/**
	 * Decodes {@code long} token ids. An id outside the {@code int} range cannot name a vocabulary
	 * entry, so it is mapped to {@code -1} and ignored by {@link #decode(int[])} like any other
	 * invalid id, rather than narrowed by a cast that could wrap it onto a real token.
	 *
	 * @param tokens the token ids to decode
	 * @return the decoded text
	 */
	@Override
	public String decodeAsLong(long[] tokens) {
		int[] ids = new int[tokens.length];
		for (int i = 0; i < tokens.length; i++) {
			long id = tokens[i];
			ids[i] = id < Integer.MIN_VALUE || id > Integer.MAX_VALUE ? -1 : (int) id;
		}

		return decode(ids);
	}

	/**
	 * Whether {@code tokenId} is skipped on decode. Beyond the four standard control ids the
	 * superclass recognizes, this includes every added token the source marks special, so a clean
	 * decode drops a control token such as {@code <start_of_turn>} the same way the reference does.
	 *
	 * @param tokenId the token id to check
	 * @return true if the token is skipped on decode
	 */
	@Override
	protected boolean isSpecialToken(int tokenId) {
		return super.isSpecialToken(tokenId) || specialTokens.contains(tokenId);
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
