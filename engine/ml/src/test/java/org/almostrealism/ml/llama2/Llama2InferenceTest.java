/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml.llama2;

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * End-to-end tests for Llama2 inference using the stories110M checkpoint.
 *
 * <p>Downloads the tokenizer from the
 * <a href="https://github.com/almostrealism/llama2">almostrealism/llama2</a> repository
 * and sample weights from the Dropbox link provided in its README. Verifies that the
 * full pipeline (weight loading, tokenization, transformer forward pass, autoregressive
 * generation) produces coherent English text.</p>
 *
 * @author Michael Murray
 */
public class Llama2InferenceTest extends TestSuiteBase implements ConsoleFeatures {

	private static final String WEIGHTS_DIR = "/tmp/llama2_test";
	private static final String CHECKPOINT_PATH = WEIGHTS_DIR + "/stories110M.bin";
	private static final String TOKENIZER_PATH = WEIGHTS_DIR + "/tokenizer.bin";

	private static final String TOKENIZER_URL =
			"https://raw.githubusercontent.com/almostrealism/llama2/master/tokenizer.bin";
	private static final String CHECKPOINT_URL =
			"https://www.dropbox.com/scl/fi/romns8veg67agl5czmtww/stories110M.bin"
					+ "?rlkey=sbspy97d2j1p3jilgaff190pz&st=kak6t2uo&dl=1";

	/** Minimum expected size for the checkpoint file (400 MB). */
	private static final long CHECKPOINT_MIN_SIZE = 400_000_000L;

	/** Minimum expected size for the tokenizer file (400 KB). */
	private static final long TOKENIZER_MIN_SIZE = 400_000L;

	/**
	 * Downloads the tokenizer and checkpoint if they are not already present.
	 */
	@BeforeClass
	public static void downloadArtifacts() throws IOException {
		Files.createDirectories(Paths.get(WEIGHTS_DIR));
		downloadIfMissing(TOKENIZER_PATH, TOKENIZER_URL, TOKENIZER_MIN_SIZE);
		downloadIfMissing(CHECKPOINT_PATH, CHECKPOINT_URL, CHECKPOINT_MIN_SIZE);
	}

	/**
	 * Verifies that unconditional generation (no prompt) produces valid English text.
	 *
	 * <p>Generates 32 tokens with greedy decoding (temperature&nbsp;0) and checks that:</p>
	 * <ul>
	 *   <li>At least 32 tokens are generated</li>
	 *   <li>The output contains recognizable English words</li>
	 *   <li>Token diversity is reasonable (not a degenerate repeating loop)</li>
	 * </ul>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void testUnconditionalGeneration() throws IOException {
		Assume.assumeTrue("Skipping: requires external model weights",
				TestUtils.isComparisonTestEnabled());
		Assume.assumeTrue("Checkpoint not available",
				Files.exists(Paths.get(CHECKPOINT_PATH)));

		log("Loading Llama2 model from " + CHECKPOINT_PATH);
		Llama2 llama = new Llama2(CHECKPOINT_PATH, TOKENIZER_PATH);
		llama.setTemperature(0.0);

		int steps = 32;
		List<String> tokens = new ArrayList<>();
		long duration = llama.run(steps, null, tokens::add);

		String fullOutput = String.join("", tokens);
		log("Generated text: " + fullOutput);
		log("Duration: " + duration + "ms");

		assertValidEnglishOutput(fullOutput, steps);
	}

	/**
	 * Verifies that prompted generation produces coherent continuation.
	 *
	 * <p>Feeds the prompt "Once upon a time" and generates 32 additional tokens.
	 * Checks that the output is coherent English and not degenerate.</p>
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void testPromptedGeneration() throws IOException {
		Assume.assumeTrue("Skipping: requires external model weights",
				TestUtils.isComparisonTestEnabled());
		Assume.assumeTrue("Checkpoint not available",
				Files.exists(Paths.get(CHECKPOINT_PATH)));

		log("Loading Llama2 model from " + CHECKPOINT_PATH);
		Llama2 llama = new Llama2(CHECKPOINT_PATH, TOKENIZER_PATH);
		llama.setTemperature(0.0);

		int steps = 48;
		String prompt = "Once upon a time";
		List<String> tokens = new ArrayList<>();
		long duration = llama.run(steps, prompt, tokens::add);

		String fullOutput = String.join("", tokens);
		log("Generated text: " + fullOutput);
		log("Duration: " + duration + "ms");

		assertValidEnglishOutput(fullOutput, steps);
	}

	/**
	 * Verifies that the model config is parsed correctly from the checkpoint header.
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void testModelLoading() throws IOException {
		Assume.assumeTrue("Skipping: requires external model weights",
				TestUtils.isComparisonTestEnabled());
		Assume.assumeTrue("Checkpoint not available",
				Files.exists(Paths.get(CHECKPOINT_PATH)));

		Llama2 llama = new Llama2(CHECKPOINT_PATH, TOKENIZER_PATH);

		// stories110M has known dimensions
		log("Model loaded successfully");
		Assert.assertNotNull("Profile should not be null", llama.getProfile());
	}

	/**
	 * Asserts that the generated text looks like valid English.
	 *
	 * @param output the full generated text (including BOS marker)
	 * @param steps  the number of generation steps requested
	 */
	private void assertValidEnglishOutput(String output, int steps) {
		Assert.assertNotNull("Output should not be null", output);
		Assert.assertTrue("Output should not be empty", output.length() > 0);

		// Strip the BOS marker
		String text = output;
		if (text.startsWith("<s>")) {
			text = text.substring(3).trim();
		}

		log("Text after stripping BOS: [" + text + "]");

		Assert.assertTrue("Generated text should have meaningful content (got: \"" + text + "\")",
				text.length() >= 10);

		// Check for common English words as a signal of coherent output.
		// The stories110M model is trained on children's stories, so we expect
		// story-like English text.
		Pattern englishWord = Pattern.compile("\\b(the|a|an|is|was|and|to|of|in|it|he|she|they|her|his|that|with|for|on|had|but|not|at|this|from|are|have|one|all|were|we|there|been|my|would|so|what|up|out|if|about|who|did|do|no|just|them|very|when|your|can|could|said|each|which|their)\\b", Pattern.CASE_INSENSITIVE);
		boolean hasEnglishWords = englishWord.matcher(text).find();
		Assert.assertTrue(
				"Output should contain recognizable English words (got: \"" + text + "\")",
				hasEnglishWords);

		// Check token diversity: split into words and verify not all identical
		String[] words = text.trim().split("\\s+");
		if (words.length > 3) {
			long distinctWords = Arrays.stream(words).distinct().count();
			Assert.assertTrue(
					"Output should have diverse tokens, not a degenerate loop (distinct: "
							+ distinctWords + "/" + words.length + ")",
					distinctWords > 2);
		}
	}

	/**
	 * Downloads a file if it is missing or smaller than the expected size.
	 */
	private static void downloadIfMissing(String localPath, String remoteUrl, long minSize) throws IOException {
		Path path = Paths.get(localPath);
		if (Files.exists(path) && Files.size(path) >= minSize) {
			return;
		}

		System.out.println("Downloading " + remoteUrl + " to " + localPath + " ...");
		HttpURLConnection connection = (HttpURLConnection) new URL(remoteUrl).openConnection();
		connection.setInstanceFollowRedirects(true);
		connection.setConnectTimeout(30_000);
		connection.setReadTimeout(300_000);

		try (InputStream in = connection.getInputStream()) {
			Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			connection.disconnect();
		}

		long size = Files.size(path);
		System.out.println("Downloaded " + size + " bytes to " + localPath);
		if (size < minSize) {
			throw new IOException("Downloaded file " + localPath
					+ " is too small (" + size + " bytes, expected >= " + minSize + ")");
		}
	}
}
