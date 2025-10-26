package org.almostrealism.ml.tokenization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for byte-level BPE tokenizers.
 *
 * Implements the full tokenization pipeline:
 * 1. Pre-tokenization (split text into segments)
 * 2. Byte-level encoding (convert segments to Unicode)
 * 3. BPE merging (apply learned merges to each segment)
 * 4. Token ID lookup
 *
 * Subclasses must provide:
 * - Vocabulary (token string -> ID mapping)
 * - BPE merges (pair -> merged token mapping)
 * - Pre-tokenizer strategy
 *
 * Usage:
 * <pre>
 * class MyTokenizer extends ByteLevelBPETokenizer {
 *     public MyTokenizer(String vocabFile, String mergesFile) {
 *         super(new RegexPreTokenizer());
 *         loadVocabulary(vocabFile);
 *         loadMerges(mergesFile);
 *     }
 * }
 * </pre>
 */
public abstract class ByteLevelBPETokenizer {

    protected final PreTokenizer preTokenizer;
    protected Map<String, Integer> vocabMap;
    protected String[] vocab;
    protected Map<String, String> bpeMerges;  // "token1 token2" -> "merged"

    /**
     * Create a byte-level BPE tokenizer with the given pre-tokenization strategy.
     *
     * @param preTokenizer Pre-tokenization strategy
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
     * Apply BPE merges to a list of tokens.
     *
     * Iteratively merges adjacent token pairs according to the learned merge rules.
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
     * Get merge priority (lower is higher priority).
     * Subclasses can override to provide priority based on merge order.
     */
    protected int getMergePriority(String pair) {
        // Default: all merges have equal priority
        return 0;
    }

    /**
     * Check if a token ID is a special token (BOS, EOS, PAD, etc.)
     */
    protected boolean isSpecialToken(int tokenId) {
        return tokenId == getBOSToken() ||
               tokenId == getEOSToken() ||
               tokenId == getPADToken() ||
               tokenId == getUNKToken();
    }

    // Abstract methods for subclasses to implement

    /**
     * Get the BOS (beginning of sequence) token ID, or -1 if not used.
     */
    protected abstract int getBOSToken();

    /**
     * Get the EOS (end of sequence) token ID, or -1 if not used.
     */
    protected abstract int getEOSToken();

    /**
     * Get the PAD (padding) token ID, or -1 if not used.
     */
    protected abstract int getPADToken();

    /**
     * Get the UNK (unknown) token ID, or 0 as fallback.
     */
    protected abstract int getUNKToken();

    // Getters

    public String[] getVocab() {
        return vocab;
    }

    public int getVocabSize() {
        return vocab.length;
    }

    public PreTokenizer getPreTokenizer() {
        return preTokenizer;
    }
}
