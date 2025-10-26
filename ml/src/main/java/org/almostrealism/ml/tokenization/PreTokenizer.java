package org.almostrealism.ml.tokenization;

import java.util.List;

/**
 * Strategy interface for pre-tokenization.
 *
 * Pre-tokenization splits raw text into segments before byte-level encoding
 * and BPE merging. Different models use different pre-tokenization strategies:
 * - GPT-2/Qwen: Regex-based splitting on word boundaries, punctuation, etc.
 * - Simple: Split on whitespace only
 * - Character-level: Each character is a segment
 *
 * Example (GPT-2 style):
 *   Input: "Hello world!"
 *   Output: ["Hello", " world", "!"]
 */
public interface PreTokenizer {

    /**
     * Split text into pre-tokenization segments.
     *
     * @param text Input text to split
     * @return List of text segments to be independently byte-level encoded and merged
     */
    List<String> preTokenize(String text);

    /**
     * Get a description of this pre-tokenizer for debugging.
     */
    String getDescription();
}
