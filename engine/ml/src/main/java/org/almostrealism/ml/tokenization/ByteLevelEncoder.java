package org.almostrealism.ml.tokenization;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GPT-2 style byte-level encoding for BPE tokenization.
 *
 * <p>This class implements the byte-to-Unicode mapping used by GPT-2, Llama, Qwen, and other
 * models that use byte-level BPE. It maps each possible byte value (0-255) to a Unicode
 * character, allowing BPE to operate on Unicode strings while maintaining the ability to
 * represent any byte sequence (including invalid UTF-8).</p>
 *
 * <h2>Why Byte-Level Encoding?</h2>
 * <ul>
 *   <li><strong>Universal coverage:</strong> Can represent any text, including rare Unicode</li>
 *   <li><strong>No UNK tokens:</strong> Every input can be encoded at the byte level</li>
 *   <li><strong>Smaller base vocabulary:</strong> Only 256 base tokens needed</li>
 *   <li><strong>BPE compatibility:</strong> Standard BPE can operate on the encoded strings</li>
 * </ul>
 *
 * <h2>Mapping Strategy</h2>
 * <p>Bytes are mapped to Unicode characters as follows:</p>
 * <ul>
 *   <li><strong>Printable ASCII (33-126):</strong> Direct mapping (e.g., 'A' -> 'A')</li>
 *   <li><strong>Extended Latin (161-172, 174-255):</strong> Direct mapping</li>
 *   <li><strong>Non-printable (0-32, 127-160, 173):</strong> Mapped to U+0100+ range</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Encode text to byte-level string
 * String encoded = ByteLevelEncoder.encode("Hello!");
 * // encoded: "Hello!" (printable chars pass through)
 *
 * // Encode text with non-printable chars
 * String encoded2 = ByteLevelEncoder.encode("Hello\nWorld");
 * // '\n' (byte 10) maps to U+010A
 *
 * // Decode back to original
 * String decoded = ByteLevelEncoder.decode(encoded);
 * // decoded: "Hello!"
 * }</pre>
 *
 * @see ByteLevelBPETokenizer
 * @see <a href="https://github.com/openai/gpt-2/blob/master/src/encoder.py">GPT-2 encoder.py</a>
 */
public class ByteLevelEncoder {

    // Byte to Unicode character mapping (lazy initialized)
    private static Map<Integer, Character> byteToChar = null;
    private static Map<Character, Integer> charToByte = null;

    /**
     * Build the GPT-2 byte-to-unicode mapping.
     *
     * Maps bytes to Unicode in ranges:
     * - Printable ASCII (33-126): Direct mapping
     * - Extended (161-172, 174-255): Direct mapping
     * - Others (0-32, 127-160, 173): Mapped to U+0100+
     */
    private static synchronized void buildMapping() {
        if (byteToChar != null) return;

        byteToChar = new HashMap<>();
        charToByte = new HashMap<>();

        // Start with printable ASCII and common extended chars
        int n = 0;
        for (int b = 0; b < 256; b++) {
            // Printable ASCII (excluding space 32)
            if ((b >= '!' && b <= '~') ||
                (b >= 161 && b <= 172) ||
                (b >= 174 && b <= 255)) {
                byteToChar.put(b, (char) b);
                charToByte.put((char) b, b);
            } else {
                // Map to Unicode starting at U+0100
                char mapped = (char) (256 + n);
                byteToChar.put(b, mapped);
                charToByte.put(mapped, b);
                n++;
            }
        }
    }

    /**
     * Encode text to byte-level Unicode string.
     *
     * @param text Input text
     * @return Byte-level encoded string
     */
    public static String encode(String text) {
        buildMapping();

        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length);

        for (byte b : bytes) {
            int unsigned = b & 0xFF;
            result.append(byteToChar.get(unsigned));
        }

        return result.toString();
    }

    /**
     * Decode byte-level Unicode string back to text.
     *
     * @param encoded Byte-level encoded string
     * @return Decoded text
     */
    public static String decode(String encoded) {
        buildMapping();

        byte[] bytes = new byte[encoded.length()];
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            Integer byteVal = charToByte.get(c);
            if (byteVal == null) {
                throw new IllegalArgumentException("Invalid byte-level character: " + c + " (U+" +
                        Integer.toHexString(c).toUpperCase() + ")");
            }
            bytes[i] = byteVal.byteValue();
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Get the byte-to-char mapping (for debugging).
     */
    public static Map<Integer, Character> getByteToCharMapping() {
        buildMapping();
        return new HashMap<>(byteToChar);
    }

    /**
     * Get the char-to-byte mapping (for debugging).
     */
    public static Map<Character, Integer> getCharToByteMapping() {
        buildMapping();
        return new HashMap<>(charToByte);
    }
}
