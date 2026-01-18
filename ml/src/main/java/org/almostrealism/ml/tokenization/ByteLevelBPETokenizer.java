package org.almostrealism.ml.tokenization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for byte-level BPE (Byte Pair Encoding) tokenizers.
 *
 * <p>This class implements the complete tokenization pipeline used by modern language models
 * like GPT-2, Llama, Qwen, and others. Byte-level BPE operates on UTF-8 byte sequences
 * rather than characters, allowing it to handle any text including rare Unicode characters.</p>
 *
 * <h2>Tokenization Pipeline</h2>
 * <ol>
 *   <li><strong>Pre-tokenization:</strong> Split text into segments (words, punctuation, etc.)</li>
 *   <li><strong>Byte-level encoding:</strong> Convert each segment's UTF-8 bytes to Unicode characters</li>
 *   <li><strong>BPE merging:</strong> Iteratively merge character pairs according to learned rules</li>
 *   <li><strong>Token ID lookup:</strong> Map final tokens to vocabulary IDs</li>
 * </ol>
 *
 * <h2>Subclass Requirements</h2>
 * <p>Subclasses must provide:</p>
 * <ul>
 *   <li><strong>Vocabulary:</strong> Token string to ID mapping via {@link #vocabMap} and {@link #vocab}</li>
 *   <li><strong>BPE merges:</strong> Pair to merged token mapping via {@link #bpeMerges}</li>
 *   <li><strong>Special tokens:</strong> Override {@link #getBOSToken()}, {@link #getEOSToken()}, etc.</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * class Qwen3Tokenizer extends ByteLevelBPETokenizer {
 *     public Qwen3Tokenizer(String tokenizerPath) throws IOException {
 *         super(new RegexPreTokenizer());
 *         loadVocabulary(tokenizerPath);
 *         loadMerges(mergesPath);
 *     }
 *
 *     protected int getBOSToken() { return 151643; }
 *     protected int getEOSToken() { return 151645; }
 *     // ...
 * }
 *
 * // Use the tokenizer
 * int[] tokens = tokenizer.encode("Hello, world!", true);
 * String decoded = tokenizer.decode(tokens);
 * }</pre>
 *
 * @see PreTokenizer
 * @see ByteLevelEncoder
 * @see RegexPreTokenizer
 * @author Michael Murray
 */
public abstract class ByteLevelBPETokenizer {

    /** The pre-tokenization strategy used to split input text into segments. */
    protected final PreTokenizer preTokenizer;

    /** Maps token strings to their vocabulary IDs. */
    protected Map<String, Integer> vocabMap;

    /** Array of token strings indexed by vocabulary ID. */
    protected String[] vocab;

    /** BPE merge rules mapping "token1 token2" to "merged_token". */
    protected Map<String, String> bpeMerges;

    /**
     * Creates a byte-level BPE tokenizer with the given pre-tokenization strategy.
     *
     * <p>The tokenizer is initialized with empty vocabulary and merge maps.
     * Subclasses must populate these during construction.</p>
     *
     * @param preTokenizer Pre-tokenization strategy for splitting input text
     */
    public ByteLevelBPETokenizer(PreTokenizer preTokenizer) {
        this.preTokenizer = preTokenizer;
        this.vocabMap = new HashMap<>();
        this.vocab = new String[0];
        this.bpeMerges = new HashMap<>();
    }

    /**
     * Encode text to token IDs.
     *
     * @param text Input text
     * @param addSpecialTokens Whether to add BOS/EOS tokens
     * @return Array of token IDs
     */
    public int[] encode(String text, boolean addSpecialTokens) {
        List<Integer> tokenIds = new ArrayList<>();

        if (addSpecialTokens) {
            int bosToken = getBOSToken();
            if (bosToken >= 0) {
                tokenIds.add(bosToken);
            }
        }

        // Step 1: Pre-tokenize
        List<String> segments = preTokenizer.preTokenize(text);

        // Step 2 & 3: For each segment, byte-level encode and apply BPE
        for (String segment : segments) {
            // Byte-level encode the segment
            String encoded = ByteLevelEncoder.encode(segment);

            // Convert to list of character tokens
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < encoded.length(); i++) {
                tokens.add(String.valueOf(encoded.charAt(i)));
            }

            // Apply BPE merges
            tokens = applyBPEMerges(tokens);

            // Convert to token IDs
            for (String token : tokens) {
                Integer tokenId = vocabMap.get(token);
                if (tokenId == null) {
                    // Unknown token - use UNK or first token as fallback
                    tokenId = getUNKToken();
                }
                tokenIds.add(tokenId);
            }
        }

        if (addSpecialTokens) {
            int eosToken = getEOSToken();
            if (eosToken >= 0) {
                tokenIds.add(eosToken);
            }
        }

        // Convert to array
        int[] result = new int[tokenIds.size()];
        for (int i = 0; i < tokenIds.size(); i++) {
            result[i] = tokenIds.get(i);
        }

        return result;
    }

    /**
     * Applies BPE merges to a list of tokens.
     *
     * <p>This method iteratively merges adjacent token pairs according to the learned
     * merge rules. At each step, it finds the highest-priority merge (earliest in the
     * merge file) and applies it. This continues until no more merges are possible.</p>
     *
     * @param tokens List of single-character or previously-merged tokens
     * @return List of tokens after all applicable merges have been applied
     */
    protected List<String> applyBPEMerges(List<String> tokens) {
        if (tokens.size() <= 1 || bpeMerges.isEmpty()) {
            return tokens;
        }

        while (true) {
            // Find the highest priority merge
            int bestIdx = -1;
            String bestMerge = null;
            int bestPriority = Integer.MAX_VALUE;

            for (int i = 0; i < tokens.size() - 1; i++) {
                String pair = tokens.get(i) + " " + tokens.get(i + 1);
                String merged = bpeMerges.get(pair);

                if (merged != null) {
                    // Priority is the order in which merges were learned
                    // Earlier merges (lower index in merge file) have higher priority
                    int priority = getMergePriority(pair);
                    if (priority < bestPriority) {
                        bestPriority = priority;
                        bestIdx = i;
                        bestMerge = merged;
                    }
                }
            }

            // No more merges possible
            if (bestIdx == -1) {
                break;
            }

            // Apply the best merge
            List<String> newTokens = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                if (i == bestIdx) {
                    newTokens.add(bestMerge);
                } else if (i == bestIdx + 1) {
                    // Skip - already merged
                } else {
                    newTokens.add(tokens.get(i));
                }
            }
            tokens = newTokens;
        }

        return tokens;
    }

    /**
     * Decode token IDs to text.
     *
     * @param tokenIds Array of token IDs
     * @return Decoded text
     */
    public String decode(int[] tokenIds) {
        StringBuilder encoded = new StringBuilder();

        for (int tokenId : tokenIds) {
            // Skip special tokens
            if (isSpecialToken(tokenId)) {
                continue;
            }

            if (tokenId >= 0 && tokenId < vocab.length) {
                encoded.append(vocab[tokenId]);
            }
        }

        // Byte-level decode
        return ByteLevelEncoder.decode(encoded.toString());
    }

    /**
     * Returns the priority of a merge rule.
     *
     * <p>Lower values indicate higher priority. Merges learned earlier during
     * BPE training have lower indices and should be applied first. Subclasses
     * should override this to return the actual priority from the merges file.</p>
     *
     * @param pair The merge pair in format "token1 token2"
     * @return The priority (lower = higher priority), or 0 if using default priority
     */
    protected int getMergePriority(String pair) {
        // Default: all merges have equal priority
        return 0;
    }

    /**
     * Checks if a token ID is a special token (BOS, EOS, PAD, UNK).
     *
     * <p>Special tokens are typically skipped during decoding to produce clean text output.</p>
     *
     * @param tokenId The token ID to check
     * @return true if the token is a special token, false otherwise
     */
    protected boolean isSpecialToken(int tokenId) {
        return tokenId == getBOSToken() ||
               tokenId == getEOSToken() ||
               tokenId == getPADToken() ||
               tokenId == getUNKToken();
    }

    // Abstract methods for subclasses to implement

    /**
     * Returns the BOS (beginning of sequence) token ID.
     *
     * @return The BOS token ID, or -1 if this tokenizer doesn't use a BOS token
     */
    protected abstract int getBOSToken();

    /**
     * Returns the EOS (end of sequence) token ID.
     *
     * @return The EOS token ID, or -1 if this tokenizer doesn't use an EOS token
     */
    protected abstract int getEOSToken();

    /**
     * Returns the PAD (padding) token ID.
     *
     * @return The PAD token ID, or -1 if this tokenizer doesn't use padding
     */
    protected abstract int getPADToken();

    /**
     * Returns the UNK (unknown) token ID for handling out-of-vocabulary tokens.
     *
     * @return The UNK token ID, typically 0 or a designated special token
     */
    protected abstract int getUNKToken();

    // Getters

    /**
     * Returns the vocabulary as an array of token strings.
     *
     * @return Array where index i contains the string for token ID i
     */
    public String[] getVocab() {
        return vocab;
    }

    /**
     * Returns the size of the vocabulary.
     *
     * @return Number of tokens in the vocabulary
     */
    public int getVocabSize() {
        return vocab.length;
    }

    /**
     * Returns the pre-tokenizer used by this tokenizer.
     *
     * @return The pre-tokenization strategy
     */
    public PreTokenizer getPreTokenizer() {
        return preTokenizer;
    }
}
