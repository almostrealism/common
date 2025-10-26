package org.almostrealism.ml.qwen3;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Byte-level BPE tokenizer for Qwen3 models.
 *
 * Qwen3 uses a byte-level BPE tokenizer with 151,669 tokens.
 * This implementation supports:
 * - Byte-level encoding (all bytes 0-255 are represented)
 * - BPE merges for subword tokenization
 * - Special tokens (BOS, EOS, PAD, etc.)
 *
 * Binary format:
 * - int32: vocab_size
 * - int32: num_merges
 * - For each token (vocab_size entries):
 *   - float32: score
 *   - int32: token_length
 *   - byte[]: token_bytes (UTF-8 encoded)
 * - For each merge (num_merges entries):
 *   - int32: token1_id
 *   - int32: token2_id
 *   - int32: merged_id
 */
public class Qwen3Tokenizer {
	// Special token IDs (Qwen3 defaults)
	public static final int BOS_TOKEN = 151643;  // <|im_start|>
	public static final int EOS_TOKEN = 151645;  // <|im_end|>
	public static final int PAD_TOKEN = 151643;  // Same as BOS

	private final int vocabSize;
	private final String[] vocab;
	private final float[] vocabScores;
	private final Map<String, Integer> vocabMap;

	// BPE merges: maps (token1_id, token2_id) -> merged_id
	private final Map<Long, Integer> merges;

	/**
	 * Load tokenizer from binary file.
	 *
	 * @param tokenizerPath Path to tokenizer.bin file
	 * @throws IOException If file cannot be read
	 */
	public Qwen3Tokenizer(String tokenizerPath) throws IOException {
		Path path = Paths.get(tokenizerPath);

		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			ByteBuffer bb = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
			bb.order(ByteOrder.LITTLE_ENDIAN);

			// Read vocab size
			this.vocabSize = bb.getInt();
			this.vocab = new String[vocabSize];
			this.vocabScores = new float[vocabSize];
			this.vocabMap = new HashMap<>(vocabSize);

			// Read vocabulary
			for (int i = 0; i < vocabSize; i++) {
				vocabScores[i] = bb.getFloat();
				int len = bb.getInt();
				byte[] bytes = new byte[len];
				bb.get(bytes);
				vocab[i] = new String(bytes, StandardCharsets.UTF_8);
				vocabMap.put(vocab[i], i);
			}

			// Read merges if available in .bin file
			this.merges = new HashMap<>();
			if (bb.remaining() >= 4) {
				int numMerges = bb.getInt();
				for (int i = 0; i < numMerges; i++) {
					int token1 = bb.getInt();
					int token2 = bb.getInt();
					int merged = bb.getInt();
					merges.put(packPair(token1, token2), merged);
				}
				System.out.println("Loaded " + numMerges + " BPE merges from .bin file");
			}
		}

		// If no merges in .bin file, try to load from merges.txt
		if (merges.isEmpty()) {
			Path parentDir = path.getParent();
			if (parentDir != null) {
				Path mergesFile = parentDir.resolve("merges.txt");
				if (java.nio.file.Files.exists(mergesFile)) {
					loadMergesFromFile(mergesFile);
				}
			}
		}

		System.out.println("Loaded Qwen3 tokenizer: " + vocabSize + " tokens");
	}

	/**
	 * Load BPE merges from merges.txt file (HuggingFace format).
	 *
	 * Format:
	 * #version: 0.2
	 * token1 token2
	 * ...
	 */
	private void loadMergesFromFile(Path mergesFile) throws IOException {
		int loadedCount = 0;

		try (BufferedReader reader = Files.newBufferedReader(mergesFile, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				// Skip header and empty lines
				if (line.startsWith("#") || line.trim().isEmpty()) {
					continue;
				}

				// Parse merge: "token1 token2"
				String[] parts = line.split(" ", 2);
				if (parts.length != 2) {
					continue;
				}

				String token1Str = parts[0];
				String token2Str = parts[1];

				// Look up token IDs
				Integer token1Id = vocabMap.get(token1Str);
				Integer token2Id = vocabMap.get(token2Str);

				if (token1Id == null || token2Id == null) {
					// Tokens not in vocabulary - skip this merge
					continue;
				}

				// The merged token is token1 + token2 concatenated
				String mergedStr = token1Str + token2Str;
				Integer mergedId = vocabMap.get(mergedStr);

				if (mergedId == null) {
					// Merged token not in vocabulary - skip
					continue;
				}

				// Add merge rule: (token1_id, token2_id) -> merged_id
				merges.put(packPair(token1Id, token2Id), mergedId);
				loadedCount++;
			}
		}

		System.out.println("Loaded " + loadedCount + " BPE merges");
	}

	/**
	 * Constructor for testing with explicit vocab.
	 */
	public Qwen3Tokenizer(String[] vocab, float[] vocabScores) {
		this.vocabSize = vocab.length;
		this.vocab = vocab;
		this.vocabScores = vocabScores;
		this.vocabMap = new HashMap<>(vocabSize);
		for (int i = 0; i < vocabSize; i++) {
			vocabMap.put(vocab[i], i);
		}
		this.merges = new HashMap<>();
	}

	public int getVocabSize() {
		return vocabSize;
	}

	public String[] getVocab() {
		return vocab;
	}

	public float[] getVocabScores() {
		return vocabScores;
	}

	/**
	 * Encode text to token IDs using byte-level BPE.
	 *
	 * @param text Input text to encode
	 * @return Array of token IDs
	 */
	public int[] encode(String text) {
		return encode(text, true, true);
	}

	/**
	 * Encode text to token IDs with control over special tokens.
	 *
	 * @param text Input text
	 * @param addBos Add BOS token at start
	 * @param addEos Add EOS token at end
	 * @return Array of token IDs
	 */
	public int[] encode(String text, boolean addBos, boolean addEos) {
		List<Integer> tokens = new ArrayList<>();

		if (addBos) {
			tokens.add(BOS_TOKEN);
		}

		// Convert text to GPT-2 byte encoding, then tokenize
		String gpt2Encoded = encodeGPT2Bytes(text);

		// Start with character-level tokens
		List<Integer> charTokens = new ArrayList<>();
		for (int i = 0; i < gpt2Encoded.length(); i++) {
			char c = gpt2Encoded.charAt(i);
			String charStr = String.valueOf(c);

			// Look up single character token
			Integer tokenId = vocabMap.get(charStr);
			if (tokenId == null) {
				// Unknown character - use UNK token
				System.err.println("Warning: Unknown character: " + c + " (U+" +
						Integer.toHexString(c).toUpperCase() + ")");
				tokenId = 0;  // UNK token
			}
			charTokens.add(tokenId);
		}

		// Apply BPE merges
		tokens.addAll(applyBPEMerges(charTokens));

		if (addEos) {
			tokens.add(EOS_TOKEN);
		}

		// Convert to array
		int[] result = new int[tokens.size()];
		for (int i = 0; i < tokens.size(); i++) {
			result[i] = tokens.get(i);
		}

		return result;
	}

	/**
	 * Encode UTF-8 text to GPT-2 byte-level representation.
	 *
	 * Converts spaces to Ġ, newlines to Ċ, etc.
	 */
	private String encodeGPT2Bytes(String text) {
		// Use Unicode escapes to avoid encoding issues
		// space -> U+0120 (Ġ)
		// newline -> U+010A (Ċ)
		// tab -> U+0109 (ĉ)
		return text.replace(" ", "\u0120")
				   .replace("\n", "\u010A")
				   .replace("\t", "\u0109");
	}

	/**
	 * Apply BPE merge rules to a sequence of tokens.
	 */
	private List<Integer> applyBPEMerges(List<Integer> tokens) {
		if (tokens.size() <= 1 || merges.isEmpty()) {
			return tokens;
		}

		// Make a mutable copy
		List<Integer> working = new ArrayList<>(tokens);

		// Iteratively apply merges
		boolean changed = true;
		while (changed) {
			changed = false;
			float bestScore = -1e10f;
			int bestIdx = -1;
			Integer bestMerged = null;

			// Find the best merge
			for (int i = 0; i < working.size() - 1; i++) {
				int token1 = working.get(i);
				int token2 = working.get(i + 1);

				Integer mergedId = merges.get(packPair(token1, token2));
				if (mergedId != null && vocabScores[mergedId] > bestScore) {
					bestScore = vocabScores[mergedId];
					bestIdx = i;
					bestMerged = mergedId;
				}
			}

			// Apply the best merge if found
			if (bestIdx != -1 && bestMerged != null) {
				working.set(bestIdx, bestMerged);
				working.remove(bestIdx + 1);
				changed = true;
			}
		}

		return working;
	}

	/**
	 * Decode token IDs back to text.
	 *
	 * @param tokens Array of token IDs
	 * @return Decoded text
	 */
	public String decode(int[] tokens) {
		StringBuilder result = new StringBuilder();

		for (int tokenId : tokens) {
			// Skip special tokens
			if (tokenId == BOS_TOKEN || tokenId == EOS_TOKEN || tokenId == PAD_TOKEN) {
				continue;
			}

			if (tokenId >= 0 && tokenId < vocabSize) {
				String token = vocab[tokenId];

				// Handle GPT-2 style byte encoding
				// Ġ (U+0120) represents a space at the start of a token
				// Convert GPT-2 byte encoding back to UTF-8
				String decoded = decodeGPT2Bytes(token);
				result.append(decoded);
			}
		}

		return result.toString();
	}

	/**
	 * Decode GPT-2 byte-level BPE encoding back to UTF-8.
	 *
	 * GPT-2 uses a specific byte encoding where certain Unicode characters
	 * represent individual bytes. For example:
	 * - Ġ (U+0120) -> space (0x20)
	 * - Ċ (U+010A) -> newline (0x0A)
	 * - etc.
	 */
	private String decodeGPT2Bytes(String token) {
		// Use Unicode escapes to avoid encoding issues
		// U+0120 (Ġ) -> space
		// U+010A (Ċ) -> newline
		// U+0109 (ĉ) -> tab
		return token.replace("\u0120", " ")
					.replace("\u010A", "\n")
					.replace("\u0109", "\t");
	}

	/**
	 * Pack two token IDs into a long for use as a map key.
	 */
	private static long packPair(int token1, int token2) {
		return ((long) token1 << 32) | (token2 & 0xFFFFFFFFL);
	}

	/**
	 * Simple encode method compatible with BPE.encode signature.
	 */
	public static int encode(String text, String[] vocab, float[] vocabScores,
							 int vocabSize, int[] outputTokens) {
		// Create a temporary tokenizer
		Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(vocab, vocabScores);

		// Encode without special tokens (for compatibility)
		int[] tokens = tokenizer.encode(text, false, false);

		// Copy to output array
		int count = Math.min(tokens.length, outputTokens.length);
		System.arraycopy(tokens, 0, outputTokens, 0, count);

		return count;
	}

	/**
	 * Create a simple test tokenizer with ASCII characters.
	 */
	public static Qwen3Tokenizer createTestTokenizer() {
		// Create a simple vocab with ASCII characters and some common subwords
		List<String> vocabList = new ArrayList<>();
		List<Float> scoresList = new ArrayList<>();

		// Add byte-level tokens (256)
		for (int i = 0; i < 256; i++) {
			vocabList.add(String.format("<0x%02X>", i));
			scoresList.add((float) i);
		}

		// Add common words and subwords
		String[] commonTokens = {
				" ", "the", "a", "an", "and", "or", "is", "in", "to", "of",
				"Hello", "World", "!", "?", ".", ",", "\n",
				"Once", "upon", "time", "there", "was"
		};
		for (int i = 0; i < commonTokens.length; i++) {
			vocabList.add(commonTokens[i]);
			scoresList.add(256.0f + i);
		}

		String[] vocab = vocabList.toArray(new String[0]);
		float[] scores = new float[scoresList.size()];
		for (int i = 0; i < scores.length; i++) {
			scores[i] = scoresList.get(i);
		}

		return new Qwen3Tokenizer(vocab, scores);
	}

	/**
	 * Test the tokenizer with sample text.
	 */
	public static void main(String[] args) {
		System.out.println("Testing Qwen3Tokenizer...");

		// Create test tokenizer
		Qwen3Tokenizer tokenizer = createTestTokenizer();

		// Test encoding
		String text = "Hello World!";
		int[] tokens = tokenizer.encode(text, false, false);

		System.out.println("Input: " + text);
		System.out.println("Tokens: " + Arrays.toString(tokens));

		// Test decoding
		String decoded = tokenizer.decode(tokens);
		System.out.println("Decoded: " + decoded);

		// Test with BOS/EOS
		tokens = tokenizer.encode(text, true, true);
		System.out.println("With special tokens: " + Arrays.toString(tokens));
	}
}
