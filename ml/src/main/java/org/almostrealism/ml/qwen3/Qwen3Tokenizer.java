package org.almostrealism.ml.qwen3;

import org.almostrealism.ml.tokenization.ByteLevelBPETokenizer;
import org.almostrealism.ml.tokenization.RegexPreTokenizer;

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
import java.util.HashMap;
import java.util.Map;

/**
 * Qwen3 byte-level BPE tokenizer implementation.
 *
 * Extends {@link ByteLevelBPETokenizer} with Qwen3-specific configuration:
 * - Uses regex pre-tokenization (GPT-2 pattern)
 * - 151,669 vocabulary size
 * - Special tokens: BOS=151643, EOS=151645
 *
 * Binary format:
 * - int32: vocab_size
 * - For each token (vocab_size entries):
 *   - float32: score
 *   - int32: token_length
 *   - byte[]: token_bytes (UTF-8 encoded)
 *
 * BPE merges are loaded from merges.txt in HuggingFace format.
 */
public class Qwen3Tokenizer extends ByteLevelBPETokenizer {
	// Special token IDs (Qwen3 defaults)
	public static final int BOS_TOKEN = 151643;  // <|endoftext|>
	public static final int EOS_TOKEN = 151645;  // <|im_end|>
	public static final int PAD_TOKEN = 151643;  // Same as BOS
	public static final int UNK_TOKEN = 128244;  // <unk>

	private final float[] vocabScores;

	// Merge priority tracking (lower = higher priority)
	private final Map<String, Integer> mergePriorities;

	/**
	 * Load tokenizer from binary file.
	 *
	 * @param tokenizerPath Path to tokenizer.bin file
	 * @throws IOException If file cannot be read
	 */
	public Qwen3Tokenizer(String tokenizerPath) throws IOException {
		super(new RegexPreTokenizer());

		Path path = Paths.get(tokenizerPath);

		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			ByteBuffer bb = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
			bb.order(ByteOrder.LITTLE_ENDIAN);

			// Read vocab size
			int vocabSize = bb.getInt();
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

			System.out.println("Loaded Qwen3 tokenizer: " + vocabSize + " tokens");
		}

		// Load BPE merges from merges.txt
		this.bpeMerges = new HashMap<>();
		this.mergePriorities = new HashMap<>();

		Path parentDir = path.getParent();
		if (parentDir != null) {
			Path mergesFile = parentDir.resolve("merges.txt");
			if (Files.exists(mergesFile)) {
				loadMergesFromFile(mergesFile);
			}
		}
	}

	/**
	 * Load BPE merges from merges.txt file (HuggingFace format).
	 *
	 * Format:
	 * #version: 0.2
	 * token1 token2
	 * ...
	 *
	 * Priority is determined by order in file (earlier = higher priority).
	 */
	private void loadMergesFromFile(Path mergesFile) throws IOException {
		int loadedCount = 0;
		int priority = 0;

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

				String token1 = parts[0];
				String token2 = parts[1];

				// Verify tokens exist in vocabulary
				if (!vocabMap.containsKey(token1) || !vocabMap.containsKey(token2)) {
					priority++;
					continue;
				}

				// The merged token is token1 + token2 concatenated
				String merged = token1 + token2;

				if (!vocabMap.containsKey(merged)) {
					priority++;
					continue;
				}

				// Add merge rule: "token1 token2" -> "merged"
				String pairKey = token1 + " " + token2;
				bpeMerges.put(pairKey, merged);
				mergePriorities.put(pairKey, priority);

				loadedCount++;
				priority++;
			}
		}

		System.out.println("Loaded " + loadedCount + " BPE merges from merges.txt");
	}

	/**
	 * Constructor for testing with explicit vocab.
	 */
	public Qwen3Tokenizer(String[] vocab, float[] vocabScores) {
		super(new RegexPreTokenizer());

		this.vocab = vocab;
		this.vocabScores = vocabScores;
		this.vocabMap = new HashMap<>(vocab.length);

		for (int i = 0; i < vocab.length; i++) {
			vocabMap.put(vocab[i], i);
		}

		this.bpeMerges = new HashMap<>();
		this.mergePriorities = new HashMap<>();
	}

	// Special token methods

	@Override
	protected int getBOSToken() {
		return BOS_TOKEN;
	}

	@Override
	protected int getEOSToken() {
		return EOS_TOKEN;
	}

	@Override
	protected int getPADToken() {
		return PAD_TOKEN;
	}

	@Override
	protected int getUNKToken() {
		return UNK_TOKEN;
	}

	@Override
	protected int getMergePriority(String pair) {
		Integer priority = mergePriorities.get(pair);
		return priority != null ? priority : Integer.MAX_VALUE;
	}

	// Getters

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
		return encode(text, true);
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
		// Use base class encode, then add/remove special tokens as needed
		boolean addSpecialTokens = addBos || addEos;
		int[] baseTokens = super.encode(text, addSpecialTokens);

		if (addSpecialTokens) {
			// Base class adds both BOS and EOS - adjust if needed
			if (!addBos && baseTokens.length > 0 && baseTokens[0] == BOS_TOKEN) {
				// Remove BOS
				int[] result = new int[baseTokens.length - 1];
				System.arraycopy(baseTokens, 1, result, 0, result.length);
				baseTokens = result;
			}
			if (!addEos && baseTokens.length > 0 && baseTokens[baseTokens.length - 1] == EOS_TOKEN) {
				// Remove EOS
				int[] result = new int[baseTokens.length - 1];
				System.arraycopy(baseTokens, 0, result, 0, result.length);
				baseTokens = result;
			}
		}

		return baseTokens;
	}

	/**
	 * Simple encode method compatible with legacy BPE.encode signature.
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
	 * For testing purposes only.
	 */
	public static Qwen3Tokenizer createTestTokenizer() {
		// Create a simple vocab with ASCII characters and some common subwords
		java.util.List<String> vocabList = new java.util.ArrayList<>();
		java.util.List<Float> scoresList = new java.util.ArrayList<>();

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
}
