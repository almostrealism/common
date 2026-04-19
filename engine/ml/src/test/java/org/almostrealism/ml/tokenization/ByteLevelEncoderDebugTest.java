package org.almostrealism.ml.tokenization;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Debug test for ByteLevelEncoder.
 */
public class ByteLevelEncoderDebugTest extends TestSuiteBase {

	@Test(timeout = 5000)
	public void testByteEncoding() {
		log("\n=== ByteLevelEncoder Debug ===\n");

		// Test space character
		String space = " ";
		String encoded = ByteLevelEncoder.encode(space);
		log("Space character:");
		log("  Input: '" + space + "' (byte 0x20)");
		log("  Encoded: '" + encoded + "'");
		log("  Expected: '\\u0120' (U+0120)");
		if (encoded.length() > 0) {
			char c = encoded.charAt(0);
			log("  Got: U+" + String.format("%04X", (int) c));
			log("  Match: " + (c == '\u0120'));
		}

		// Test " world"
		String text = " world";
		encoded = ByteLevelEncoder.encode(text);
		log("\n' world':");
		log("  Input: '" + text + "'");
		log("  Encoded: '" + encoded + "'");
		log("  Expected: '\\u0120world' (\\u0120 = U+0120)");
		log("  Bytes:");
		for (int i = 0; i < encoded.length(); i++) {
			char c = encoded.charAt(i);
			log("    [" + i + "] U+" + String.format("%04X", (int) c) + " = '" + c + "'");
		}

		// Test decode
		String decoded = ByteLevelEncoder.decode(encoded);
		log("  Decoded: '" + decoded + "'");
		log("  Round-trip: " + decoded.equals(text));

		// Test exclamation
		String excl = "!";
		encoded = ByteLevelEncoder.encode(excl);
		log("\n'!':");
		log("  Input: '" + excl + "' (byte 0x21)");
		log("  Encoded: '" + encoded + "'");
		if (encoded.length() > 0) {
			char c = encoded.charAt(0);
			log("  Got: U+" + String.format("%04X", (int) c));
		}
	}
}
