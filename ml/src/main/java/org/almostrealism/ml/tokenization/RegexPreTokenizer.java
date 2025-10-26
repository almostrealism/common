package org.almostrealism.ml.tokenization;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based pre-tokenizer for GPT-2/Qwen style tokenization.
 *
 * Splits text using a regex pattern that handles:
 * - Contractions ('s, 't, 're, 've, 'm, 'll, 'd)
 * - Words (sequences of letters)
 * - Numbers
 * - Punctuation and special characters
 * - Whitespace
 *
 * Pattern based on HuggingFace tokenizers:
 * https://github.com/huggingface/tokenizers
 */
public class RegexPreTokenizer implements PreTokenizer {

    private final Pattern pattern;
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
     */
    private static Pattern createGPT2Pattern() {
        String patternStr = "'s|'t|'re|'ve|'m|'ll|'d" +  // Contractions
                            "|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+" +  // Letters with optional prefix
                            "|\\p{N}" +  // Numbers
                            "| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*" +  // Punctuation
                            "|\\s*[\\r\\n]+" +  // Newlines
                            "|\\s+(?!\\S)" +  // Trailing space
                            "|\\s+";  // Other space

        return Pattern.compile(patternStr);
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
