package org.almostrealism.ml.tokenization;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based pre-tokenizer for GPT-2, Qwen, and Llama style tokenization.
 *
 * <p>This pre-tokenizer splits input text into segments using a carefully designed
 * regular expression pattern. It is the standard approach used by most modern
 * language models and ensures consistent tokenization behavior.</p>
 *
 * <h2>Handled Patterns</h2>
 * <ul>
 *   <li><strong>Contractions:</strong> 's, 't, 're, 've, 'm, 'll, 'd (English)</li>
 *   <li><strong>Words:</strong> Sequences of Unicode letters (\\p{L}+)</li>
 *   <li><strong>Numbers:</strong> Individual digits (\\p{N})</li>
 *   <li><strong>Punctuation:</strong> Non-letter/number sequences with optional leading space</li>
 *   <li><strong>Whitespace:</strong> Various whitespace patterns including newlines</li>
 * </ul>
 *
 * <h2>Design Goals</h2>
 * <ul>
 *   <li><strong>Preserve spacing:</strong> Leading spaces attached to words for natural tokenization</li>
 *   <li><strong>Language agnostic:</strong> Unicode properties handle non-English text</li>
 *   <li><strong>BPE friendly:</strong> Segments align with natural token boundaries</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * RegexPreTokenizer preTokenizer = new RegexPreTokenizer();
 *
 * // Basic usage
 * List<String> segments = preTokenizer.preTokenize("Hello, world!");
 * // ["Hello", ",", " world", "!"]
 *
 * // With contractions
 * List<String> segments2 = preTokenizer.preTokenize("I'm happy");
 * // ["I", "'m", " happy"]
 *
 * // Custom pattern
 * Pattern myPattern = Pattern.compile("\\S+|\\s+");
 * RegexPreTokenizer custom = new RegexPreTokenizer(myPattern);
 * }</pre>
 *
 * @see PreTokenizer
 * @see ByteLevelBPETokenizer
 * @see <a href="https://github.com/huggingface/tokenizers">HuggingFace Tokenizers</a>
 */
public class RegexPreTokenizer implements PreTokenizer {

    /** Compiled regex pattern used to split input text into segments. */
    private final Pattern pattern;

    /** Human-readable description of this pre-tokenizer instance, including the pattern. */
    private final String description;

    /**
     * Create a regex pre-tokenizer with the GPT-2/Qwen pattern.
     */
    public RegexPreTokenizer() {
        this(createGPT2Pattern());
    }

    /**
     * Create a regex pre-tokenizer with a custom pattern.
     *
     * @param pattern Regex pattern for splitting
     */
    public RegexPreTokenizer(Pattern pattern) {
        this.pattern = pattern;
        this.description = "RegexPreTokenizer(pattern=" + pattern.pattern() + ")";
    }

    /**
     * Create the GPT-2/Qwen regex pattern.
     *
     * Pattern explanation:
     * - (?i:'s|'t|'re|'ve|'m|'ll|'d) : Contractions (case insensitive)
     * - [^\r\n\p{L}\p{N}]?\p{L}+     : Optional non-letter/number + letters
     * - \p{N}                         : Numbers
     * -  ?[^\s\p{L}\p{N}]+[\r\n]*    : Optional space + punctuation + optional newlines
     * - \s*[\r\n]+                    : Whitespace + newlines
     * - \s+(?!\S)                     : Trailing whitespace
     * - \s+                           : Other whitespace
     *
     * The reference regex engine treats \s as Unicode whitespace, so the pattern is
     * compiled with UNICODE_CHARACTER_CLASS; Java's default \s is ASCII-only and would
     * let characters such as U+00A0 and U+3000 join punctuation runs.
     */
    private static Pattern createGPT2Pattern() {
        String patternStr = "(?i:'s|'t|'re|'ve|'m|'ll|'d)" +  // Contractions
                            "|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+" +  // Letters with optional prefix
                            "|\\p{N}" +  // Numbers
                            "| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*" +  // Punctuation
                            "|\\s*[\\r\\n]+" +  // Newlines
                            "|\\s+(?!\\S)" +  // Trailing space
                            "|\\s+";  // Other space

        return Pattern.compile(patternStr, Pattern.UNICODE_CHARACTER_CLASS);
    }

    /**
     * Creates a pre-tokenizer with the split pattern of the GPT-2 {@code r50k_base} encoding, as
     * the tiktoken encoding definition states it:
     * {@code '(?:[sdmt]|ll|ve|re)| ?\p{L}++| ?\p{N}++| ?[^\s\p{L}\p{N}]++|\s++$|\s+(?!\S)|\s}.
     *
     * <p>This differs from the {@link #RegexPreTokenizer() default pattern} in ways that change the
     * token ids: contractions are case-sensitive, a letter or digit run takes at most one leading
     * space (never another punctuation character), digits are grouped into runs rather than split
     * one by one, and punctuation does not absorb the newlines after it. The pattern's {@code $}
     * is written {@code \z} here, because the reference regex engine's {@code $} matches only at
     * the end of the text, while Java's also matches before a final line terminator; the
     * possessive whitespace run makes the two agree in any case. Like the default, the pattern is
     * compiled with {@link Pattern#UNICODE_CHARACTER_CLASS} so that {@code \s} is Unicode
     * whitespace, as it is in the reference engine.</p>
     *
     * @return the pre-tokenizer
     */
    public static RegexPreTokenizer r50k() {
        String patternStr = "'(?:[sdmt]|ll|ve|re)" +  // Contractions (case-sensitive)
                            "| ?\\p{L}++" +  // Letters with at most one leading space
                            "| ?\\p{N}++" +  // Digit runs with at most one leading space
                            "| ?[^\\s\\p{L}\\p{N}]++" +  // Punctuation runs
                            "|\\s++\\z" +  // Whitespace ending the text
                            "|\\s+(?!\\S)" +  // Whitespace not followed by a non-space
                            "|\\s";  // A single whitespace character

        return new RegexPreTokenizer(Pattern.compile(patternStr, Pattern.UNICODE_CHARACTER_CLASS));
    }

    @Override
    public List<String> preTokenize(String text) {
        List<String> segments = new ArrayList<>();

        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String segment = matcher.group();
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }

        return segments;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /**
     * Get the pattern used by this pre-tokenizer.
     */
    public Pattern getPattern() {
        return pattern;
    }
}
