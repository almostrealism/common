package org.almostrealism.ml.tokenization;

import java.util.List;

/**
 * Strategy interface for pre-tokenization in BPE tokenizers.
 *
 * <p>Pre-tokenization is the first step in the tokenization pipeline, splitting raw text
 * into segments before byte-level encoding and BPE merging are applied. The choice of
 * pre-tokenization strategy affects tokenization quality, vocabulary efficiency, and
 * model behavior.</p>
 *
 * <h2>Why Pre-tokenization Matters</h2>
 * <ul>
 *   <li><strong>BPE scope:</strong> BPE merges only occur within segments, never across</li>
 *   <li><strong>Word boundaries:</strong> Prevents merging parts of different words</li>
 *   <li><strong>Whitespace handling:</strong> Controls how spaces are tokenized</li>
 *   <li><strong>Special characters:</strong> Isolates punctuation and symbols</li>
 * </ul>
 *
 * <h2>Common Strategies</h2>
 * <ul>
 *   <li><strong>GPT-2/Qwen/Llama:</strong> Regex-based splitting on word boundaries, contractions, and punctuation</li>
 *   <li><strong>Whitespace:</strong> Simple split on whitespace only</li>
 *   <li><strong>Character-level:</strong> Each character is a segment (no pre-tokenization)</li>
 * </ul>
 *
 * <h2>Example (GPT-2 style)</h2>
 * <pre>{@code
 * PreTokenizer preTokenizer = new RegexPreTokenizer();
 * List<String> segments = preTokenizer.preTokenize("Hello world!");
 * // Result: ["Hello", " world", "!"]
 *
 * // Note: leading space preserved in " world"
 * // This allows the model to learn space-prefixed tokens
 * }</pre>
 *
 * @see RegexPreTokenizer
 * @see ByteLevelBPETokenizer
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
