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

package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the PDSL tutorial, {@code docs/tutorials/10-pdsl-model-definitions.html}, from drifting
 * away from the language the parser accepts and from the shipped assets it reads.
 *
 * <p>The page marks its code in three ways, and each is checked here against the page itself
 * rather than against a copy:</p>
 * <ul>
 *   <li>{@code <code class="language-pdsl">} is a PDSL program the reader writes. Every one must
 *       parse with {@link PdslLoader#parse(String)}. The last one is the complete model the page
 *       builds up; it must build from a synthetic {@link StateDictionary} to the output shape the
 *       page states, and every definition shown in an earlier step must appear in it unchanged.</li>
 *   <li>{@code <code class="language-pdsl-asset" data-asset="/pdsl/...">} is an excerpt of a
 *       shipped asset. Every line of it, apart from a {@code ...} elision line, must occur in that
 *       asset in the same order, so an edit to the asset cannot leave a stale copy on the page.</li>
 *   <li>Anything else ({@code language-text}, {@code language-java}) is a fragment and is not
 *       checked.</li>
 * </ul>
 *
 * <p>The page's code is HTML, so a shape annotation's arrow is written with the {@code gt} entity.
 * Tags are stripped and entities decoded before anything is parsed or compared.</p>
 */
public class PdslTutorialExamplesTest extends TestSuiteBase {

	/** Repository-relative path of the tutorial. */
	private static final String TUTORIAL = "docs/tutorials/10-pdsl-model-definitions.html";

	/** A PDSL program the reader writes. */
	private static final Pattern PROGRAM = Pattern.compile(
			"<pre><code class=\"language-pdsl\">(.*?)</code></pre>", Pattern.DOTALL);

	/** An excerpt of a shipped asset, with the asset's classpath path. */
	private static final Pattern EXCERPT = Pattern.compile(
			"<pre><code class=\"language-pdsl-asset\" data-asset=\"([^\"]+)\">(.*?)</code></pre>",
			Pattern.DOTALL);

	/** A definition inside a program: its doc comment (if any) and its whole body. */
	private static final Pattern DEFINITION = Pattern.compile(
			"(?m)^(?:/\\*\\*.*?\\*/\\n)?(?:layer|model) [a-z_0-9]+\\(.*?^}", Pattern.DOTALL);

	/** Every program the reader writes parses, and there are as many as the page's steps. */
	@Test(timeout = 60000)
	public void everyProgramParses() {
		List<String> programs = programs();
		Assert.assertTrue("the tutorial shows only " + programs.size() + " programs", programs.size() >= 4);

		PdslLoader loader = new PdslLoader();
		for (String program : programs) {
			try {
				Assert.assertNotNull(loader.parse(program));
			} catch (PdslParseException e) {
				Assert.fail("a program of the tutorial does not parse (" + e.getMessage() + "):\n" + program);
			}
		}
	}

	/** Every definition the page writes in a step appears, unchanged, in the final program. */
	@Test(timeout = 60000)
	public void everyStepIsPartOfTheFinalProgram() {
		List<String> programs = programs();
		String last = programs.get(programs.size() - 1);
		int checked = 0;
		for (String step : programs.subList(0, programs.size() - 1)) {
			Matcher definition = DEFINITION.matcher(step);
			while (definition.find()) {
				Assert.assertTrue("the final program does not contain this step's definition:\n"
						+ definition.group(), last.contains(definition.group()));
				checked++;
			}
		}
		Assert.assertTrue("only " + checked + " step definitions were found", checked >= 3);
	}

	/** Every line of every asset excerpt occurs in that asset, in the order the page shows it. */
	@Test(timeout = 60000)
	public void everyAssetExcerptMatchesItsAsset() {
		Matcher excerpt = EXCERPT.matcher(tutorial());
		PdslLoader loader = new PdslLoader();
		int count = 0;
		while (excerpt.find()) {
			String resource = excerpt.group(1);
			String asset = loader.readResource(resource);
			int from = 0;
			for (String line : decode(excerpt.group(2)).split("\n")) {
				String text = line.trim();
				if (text.isEmpty() || text.equals("...")) continue;
				int at = indexOfLine(asset, text, from);
				Assert.assertTrue("the tutorial quotes '" + text + "' from " + resource
						+ ", which does not hold it there", at >= 0);
				from = at + text.length();
			}
			count++;
		}
		Assert.assertTrue("the tutorial quotes only " + count + " asset excerpts", count >= 5);
	}

	/** The first program, the gated feed-forward layer, keeps the width of its input. */
	@Test(timeout = 60000)
	public void gatedMlpBuildsToItsDocumentedShape() {
		Map<String, Object> args = new HashMap<>();
		args.put("norm", weights(4));
		args.put("gate", weights(8, 4));
		args.put("up", weights(8, 4));
		args.put("down", weights(4, 8));
		Block block = new PdslLoader().buildLayer(program("layer gated_mlp("), "gated_mlp",
				new TraversalPolicy(1, 4), args);
		Assert.assertArrayEquals(new int[] { 1, 4 }, block.getOutputShape().extent());
	}

	/** The cached attention layer maps one token vector to one token vector. */
	@Test(timeout = 60000)
	public void cachedAttentionBuildsToItsDocumentedShape() {
		Map<String, Object> args = new HashMap<>();
		args.put("heads", 2);
		args.put("head_size", 2);
		args.put("norm", weights(4));
		args.put("wq", weights(4, 4));
		args.put("wk", weights(4, 4));
		args.put("wv", weights(4, 4));
		args.put("wo", weights(4, 4));
		args.put("position", weights(1));
		args.put("key_cache", weights(8, 4));
		args.put("value_cache", weights(8, 4));
		Block block = new PdslLoader().buildLayer(program("layer cached_attention("), "cached_attention",
				new TraversalPolicy(1, 4), args);
		Assert.assertArrayEquals(new int[] { 1, 4 }, block.getOutputShape().extent());
	}

	/**
	 * The final model builds from a two-layer synthetic checkpoint: one block per stacked layer,
	 * then the norm and the vocabulary projection, ending in one score per vocabulary entry.
	 */
	@Test(timeout = 120000)
	public void finalModelBuildsOneBlockPerLayerAndScoresTheVocabulary() {
		int dim = 4;
		int hidden = 8;
		int vocab = 16;
		Map<String, PackedCollection> tensors = new HashMap<>();
		for (int layer = 0; layer < 2; layer++) {
			String prefix = "layers." + layer + ".";
			tensors.put(prefix + "attn_norm.weight", weights(dim));
			for (String projection : List.of("q", "k", "v", "o")) {
				tensors.put(prefix + "attn." + projection + ".weight", weights(dim, dim));
			}
			tensors.put(prefix + "mlp_norm.weight", weights(dim));
			tensors.put(prefix + "mlp.gate.weight", weights(hidden, dim));
			tensors.put(prefix + "mlp.up.weight", weights(hidden, dim));
			tensors.put(prefix + "mlp.down.weight", weights(dim, hidden));
		}
		tensors.put("norm.weight", weights(dim));
		tensors.put("lm_head.weight", weights(vocab, dim));

		Map<String, Object> settings = new HashMap<>();
		settings.put("dim", dim);
		settings.put("heads", 2);
		settings.put("seq_len", 8);

		Map<String, Object> args = new HashMap<>();
		args.put("settings", settings);
		args.put("position", weights(1));

		List<String> programs = programs();
		PdslLoader loader = new PdslLoader();
		Model model = loader.buildModel(loader.parse(programs.get(programs.size() - 1)), "toy_decoder",
				new TraversalPolicy(1, dim), new StateDictionary(tensors), args);
		Assert.assertEquals(4, model.getBlocks().size());
		Assert.assertArrayEquals(new int[] { 1, vocab }, model.getOutputShape().extent());
	}

	/**
	 * Returns the position of {@code line} in {@code asset} at or after {@code from}, comparing
	 * each asset line with its surrounding whitespace removed.
	 *
	 * @param asset the asset source
	 * @param line  the trimmed line to find
	 * @param from  the offset to search from
	 * @return the offset of the line, or -1
	 */
	private static int indexOfLine(String asset, String line, int from) {
		int at = asset.indexOf(line, from);
		while (at >= 0) {
			int start = asset.lastIndexOf('\n', at - 1) + 1;
			int end = asset.indexOf('\n', at);
			if (end < 0) end = asset.length();
			if (asset.substring(start, end).trim().equals(line)) return at;
			at = asset.indexOf(line, at + 1);
		}
		return -1;
	}

	/**
	 * Parses the program of the page that contains {@code declaration}.
	 *
	 * @param declaration text that starts the definition, such as {@code "layer gated_mlp("}
	 * @return the parsed program
	 */
	private static PdslNode.Program program(String declaration) {
		for (String program : programs()) {
			if (program.contains(declaration)) return new PdslLoader().parse(program);
		}
		throw new AssertionError("the tutorial has no program containing '" + declaration + "'");
	}

	/**
	 * Returns every program of the page, in order, as PDSL source.
	 *
	 * @return the decoded text of each {@code language-pdsl} block
	 */
	private static List<String> programs() {
		List<String> programs = new ArrayList<>();
		Matcher block = PROGRAM.matcher(tutorial());
		while (block.find()) programs.add(decode(block.group(1)));
		if (programs.isEmpty()) {
			throw new AssertionError(TUTORIAL + " marks no block as language-pdsl");
		}
		return programs;
	}

	/**
	 * Turns the HTML text of a code block back into source: strips any markup and decodes the
	 * entities HTML requires for {@code <}, {@code >}, {@code &} and quotes.
	 *
	 * @param html the inner HTML of a {@code <code>} element
	 * @return the source text
	 */
	private static String decode(String html) {
		return html.replaceAll("<[^>]+>", "")
				.replace("&lt;", "<")
				.replace("&gt;", ">")
				.replace("&quot;", "\"")
				.replace("&#39;", "'")
				.replace("&amp;", "&");
	}

	/**
	 * Reads the tutorial from the repository root, found by walking up from the working directory.
	 *
	 * @return the page's HTML
	 */
	private static String tutorial() {
		Path start = Paths.get("").toAbsolutePath();
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			Path page = dir.resolve(TUTORIAL);
			if (!Files.isRegularFile(page)) continue;
			try {
				return Files.readString(page, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new AssertionError("cannot read " + page, e);
			}
		}
		throw new AssertionError(TUTORIAL + " not found above " + start);
	}

	/**
	 * Allocates a zero-filled tensor.
	 *
	 * @param dims its shape
	 * @return the tensor
	 */
	private static PackedCollection weights(int... dims) {
		return new PackedCollection(new TraversalPolicy(dims));
	}
}
