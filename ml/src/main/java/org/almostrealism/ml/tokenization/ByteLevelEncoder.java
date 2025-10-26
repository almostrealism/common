package org.almostrealism.ml.tokenization;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GPT-2 style byte-level encoding.
 *
 * Maps bytes (0-255) to Unicode characters in a printable range.
 * This allows BPE to work directly on Unicode strings while preserving
 * all possible byte values.
 *
 * Reference: https://github.com/openai/gpt-2/blob/master/src/encoder.py
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
