package org.almostrealism.ml.qwen3;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Tests for Qwen3Tokenizer.
 */
public class Qwen3TokenizerTest {

	@Test
	public void testBasicEncoding() {
		// Create test tokenizer with simple vocab
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		// Test encoding
		String text = "Hello World!";
		int[] tokens = tokenizer.encode(text, false, false);

		assertNotNull(tokens);
		assertTrue("Should have at least one token", tokens.length > 0);

		System.out.println("Encoded '" + text + "' to " + tokens.length + " tokens");
		System.out.println("Tokens: " + Arrays.toString(tokens));
	}

	@Test
	public void testEncodeDecode() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		String text = "Hello World!";
		int[] tokens = tokenizer.encode(text, false, false);
		String decoded = tokenizer.decode(tokens);

		System.out.println("Original: " + text);
		System.out.println("Decoded:  " + decoded);

		// The decoded text should be similar (may not be exact due to byte-level encoding)
		assertNotNull(decoded);
		assertTrue("Decoded text should not be empty", decoded.length() > 0);
	}

	@Test
	public void testSpecialTokens() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		String text = "Test";

		// Without special tokens
		int[] tokensNoSpecial = tokenizer.encode(text, false, false);

		// With BOS only
		int[] tokensWithBos = tokenizer.encode(text, true, false);

		// With both BOS and EOS
		int[] tokensWithBoth = tokenizer.encode(text, true, true);

		System.out.println("No special tokens: " + Arrays.toString(tokensNoSpecial));
		System.out.println("With BOS: " + Arrays.toString(tokensWithBos));
		System.out.println("With BOS+EOS: " + Arrays.toString(tokensWithBoth));

		// Verify BOS is added
		assertEquals("First token should be BOS",
				Qwen3Tokenizer.BOS_TOKEN, tokensWithBos[0]);

		// Verify EOS is added
		assertEquals("Last token should be EOS",
				Qwen3Tokenizer.EOS_TOKEN, tokensWithBoth[tokensWithBoth.length - 1]);
	}

	@Test
	public void testEmptyString() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		int[] tokens = tokenizer.encode("", false, false);
		assertNotNull(tokens);
		assertEquals("Empty string should produce 0 tokens", 0, tokens.length);
	}

	@Test
	public void testMultiByteCharacters() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		// Test with multi-byte UTF-8 characters (Chinese: "world")
		String text = "Hello \u4e16\u754c";
		int[] tokens = tokenizer.encode(text, false, false);

		assertNotNull(tokens);
		assertTrue("Should encode multi-byte characters", tokens.length > 0);

		System.out.println("Multi-byte text: " + text);
		System.out.println("Tokens: " + Arrays.toString(tokens));
	}

	@Test
	public void testVocabSize() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		int vocabSize = tokenizer.getVocabSize();
		assertTrue("Vocab size should be positive", vocabSize > 0);
		assertTrue("Vocab size should include byte tokens (256+)", vocabSize >= 256);

		System.out.println("Vocabulary size: " + vocabSize);
	}

	@Test
	public void testDecode() {
		Qwen3Tokenizer tokenizer = Qwen3Tokenizer.createTestTokenizer();

		// Test decoding specific tokens
		int[] tokens = new int[]{
				Qwen3Tokenizer.BOS_TOKEN,
				72, 101, 108, 108, 111,  // "Hello" in ASCII bytes
				Qwen3Tokenizer.EOS_TOKEN
		};

		String decoded = tokenizer.decode(tokens);
		System.out.println("Decoded: " + decoded);

		// Should not contain special tokens in output
		assertFalse("Should not contain <|im_start|>", decoded.contains("<|im_start|>"));
		assertFalse("Should not contain <|im_end|>", decoded.contains("<|im_end|>"));
	}

	/**
	 * Main method for manual testing without JUnit.
	 */
	public static void main(String[] args) {
		System.out.println("=== Qwen3Tokenizer Manual Test ===\n");

		Qwen3TokenizerTest test = new Qwen3TokenizerTest();

		try {
			System.out.println("1. Basic Encoding Test:");
			test.testBasicEncoding();
			System.out.println();

			System.out.println("2. Encode/Decode Test:");
			test.testEncodeDecode();
			System.out.println();

			System.out.println("3. Special Tokens Test:");
			test.testSpecialTokens();
			System.out.println();

			System.out.println("4. Empty String Test:");
			test.testEmptyString();
			System.out.println();

			System.out.println("5. Multi-byte Characters Test:");
			test.testMultiByteCharacters();
			System.out.println();

			System.out.println("6. Vocab Size Test:");
			test.testVocabSize();
			System.out.println();

			System.out.println("7. Decode Test:");
			test.testDecode();
			System.out.println();

			System.out.println("=== All tests completed ===");
		} catch (Exception e) {
			System.err.println("Test failed: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
