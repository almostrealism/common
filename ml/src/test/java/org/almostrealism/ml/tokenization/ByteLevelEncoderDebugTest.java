package org.almostrealism.ml.tokenization;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Debug test for ByteLevelEncoder.
 */
public class ByteLevelEncoderDebugTest extends TestSuiteBase {

	@Test
	public void testByteEncoding() {
		System.out.println("\n=== ByteLevelEncoder Debug ===\n");

		// Test space character
		String space = " ";
		String encoded = ByteLevelEncoder.encode(space);
		System.out.println("Space character:");
		System.out.println("  Input: '" + space + "' (byte 0x20)");
		System.out.println("  Encoded: '" + encoded + "'");
		System.out.println("  Expected: '\\u0120' (U+0120)");
		if (encoded.length() > 0) {
			char c = encoded.charAt(0);
			System.out.println("  Got: U+" + String.format("%04X", (int) c));
			System.out.println("  Match: " + (c == '\u0120'));
		}

		// Test " world"
		String text = " world";
		encoded = ByteLevelEncoder.encode(text);
		System.out.println("\n' world':");
		System.out.println("  Input: '" + text + "'");
		System.out.println("  Encoded: '" + encoded + "'");
		System.out.println("  Expected: '\\u0120world' (\\u0120 = U+0120)");
		System.out.println("  Bytes:");
		for (int i = 0; i < encoded.length(); i++) {
			char c = encoded.charAt(i);
			System.out.println("    [" + i + "] U+" + String.format("%04X", (int) c) + " = '" + c + "'");
		}

		// Test decode
		String decoded = ByteLevelEncoder.decode(encoded);
		System.out.println("  Decoded: '" + decoded + "'");
		System.out.println("  Round-trip: " + decoded.equals(text));

		// Test exclamation
		String excl = "!";
		encoded = ByteLevelEncoder.encode(excl);
		System.out.println("\n'!':");
		System.out.println("  Input: '" + excl + "' (byte 0x21)");
		System.out.println("  Encoded: '" + encoded + "'");
		if (encoded.length() > 0) {
			char c = encoded.charAt(0);
			System.out.println("  Got: U+" + String.format("%04X", (int) c));
		}
	}
}
