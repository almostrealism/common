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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the PDSL language reference, {@code docs/internals/pdsl-language.md}, from drifting away
 * from the lexer, parser, interpreter and built-in library it documents.
 *
 * <p>The examples are read from the page itself, not copied into this class: every fenced block
 * marked {@code pdsl} is a complete program and must parse, and the layers and models the page's
 * "Worked examples" section describes are built for the input it states and must have the output
 * shape it states. The page must also name every keyword the lexer reserves, every
 * {@link PdslNode} type, and only built-ins that the built-in library dispatches, as many as it
 * says there are. Finally, the parse-level known limits the page records must still hold, so that
 * removing one forces the page to be updated.</p>
 */
public class PdslLanguageReferenceExamplesTest extends TestSuiteBase {

	/** Repository-relative path of the language reference. */
	private static final String REFERENCE = "docs/internals/pdsl-language.md";

	/** A fenced block of complete PDSL source on the page. */
	private static final Pattern PDSL_BLOCK = Pattern.compile("```pdsl\\n(.*?)```", Pattern.DOTALL);

	/** A row of a catalog table whose first cell names a built-in. */
	private static final Pattern CATALOG_ROW = Pattern.compile("(?m)^\\| `([a-z_0-9]+)` \\|");

	/** The sentence of the catalog that states how many built-ins there are. */
	private static final Pattern CATALOG_COUNT = Pattern.compile("The (\\d+) built-ins dispatched by `PdslBuiltins`");

	/** Every complete example on the page parses. */
	@Test(timeout = 60000)
	public void everyCompleteExampleParses() {
		List<String> examples = examples();
		Assert.assertTrue("the reference shows only " + examples.size() + " complete examples",
				examples.size() >= 9);

		PdslLoader loader = new PdslLoader();
		for (String example : examples) {
			try {
				Assert.assertNotNull(loader.parse(example));
			} catch (PdslParseException e) {
				Assert.fail("a complete example of the reference does not parse (" + e.getMessage()
						+ "):\n" + example);
			}
		}
	}

	/** The residual perceptron keeps its input shape. */
	@Test(timeout = 60000)
	public void residualMlpBuildsToItsDocumentedShape() {
		Map<String, Object> args = new HashMap<>();
		args.put("w1", weights(8, 4));
		args.put("w2", weights(4, 8));
		assertOutput("residual_mlp", args, 1, 4);
	}

	/** The gated feed-forward block projects back to its input width. */
	@Test(timeout = 60000)
	public void gatedFeedForwardBuildsToItsDocumentedShape() {
		Map<String, Object> args = new HashMap<>();
		args.put("w_gate", weights(8, 4));
		args.put("w_up", weights(8, 4));
		args.put("w_down", weights(4, 8));
		assertOutput("gated_ffn", args, 1, 4);
	}

	/** {@code concat_blocks} concatenates the two projections into one flat vector. */
	@Test(timeout = 60000)
	public void concatenatedHeadsBuildToTheirDocumentedShape() {
		Map<String, Object> args = new HashMap<>();
		args.put("wa", weights(3, 4));
		args.put("wb", weights(5, 4));
		assertOutput("two_heads", args, 8);
	}

	/**
	 * The config evaluates its later entries from its earlier ones, and the data block's two views
	 * feed the two summed projections.
	 */
	@Test(timeout = 60000)
	public void configAndDataBlockExampleMatchesTheReference() {
		PdslLoader loader = new PdslLoader();
		Map<String, Object> config = loader.evaluateConfig(program("layer sum_of_halves("), "tiny");
		Assert.assertEquals(16.0, ((Number) config.get("head_size")).doubleValue(), 0.0);

		Map<String, Object> args = new HashMap<>();
		args.put("stacked", weights(12, 4));
		args.put("rows", 6);
		args.put("cols", 4);
		assertOutput("sum_of_halves", args, 1, 6);
	}

	/** The called, looped and value-driven layers keep the shape of their input. */
	@Test(timeout = 60000)
	public void calledLoopedAndProducerLayersKeepTheirShape() {
		assertOutput("scaled_twice", Map.<String, Object>of("size", 4), 1, 4);
		assertOutput("halved", Map.<String, Object>of("depth", 3), 1, 4);
		assertOutput("gain", Map.<String, Object>of("volume", 2.0), 1, 4);
	}

	/** The self-attention scores have one row of key scores per query. */
	@Test(timeout = 60000)
	public void branchedScoresBuildToTheirDocumentedShape() {
		Block block = new PdslLoader().buildLayer(program("layer self_scores("), "self_scores",
				new TraversalPolicy(1, 2, 3, 4), Map.<String, Object>of("factor", 0.5));
		Assert.assertArrayEquals(new int[] { 1, 2, 3, 3 }, block.getOutputShape().extent());
	}

	/** The checkpoint model stacks one residual layer per member and ends in a norm. */
	@Test(timeout = 60000)
	public void checkpointModelBuildsOneBlockPerStackMember() {
		Map<String, PackedCollection> tensors = new HashMap<>();
		for (int layer = 0; layer < 2; layer++) {
			tensors.put("model.layers." + layer + ".norm.weight", weights(4));
			tensors.put("model.layers." + layer + ".proj.weight", weights(4, 4));
		}
		tensors.put("model.norm.weight", weights(4));

		Model model = new PdslLoader().buildModel(program("model tiny_decoder("), "tiny_decoder",
				new TraversalPolicy(1, 4), new StateDictionary(tensors), new HashMap<>());
		Assert.assertEquals(3, model.getBlocks().size());
		Assert.assertArrayEquals(new int[] { 1, 4 }, model.getOutputShape().extent());
	}

	/** Every reserved word has a row in the keyword table. */
	@Test(timeout = 60000)
	public void everyKeywordIsInTheKeywordTable() {
		String reference = reference();
		Assert.assertFalse(PdslToken.Type.keywords().isEmpty());
		for (String keyword : PdslToken.Type.keywords()) {
			Assert.assertTrue("the keyword table has no row for '" + keyword + "'",
					reference.contains("| `" + keyword + "` |"));
		}
		for (String reserved : List.of("if", "else")) {
			Assert.assertTrue("the keyword table does not say '" + reserved + "' is reserved with no meaning",
					reference.contains("| `" + reserved + "` | **Reserved, no meaning.**"));
		}
	}

	/** Every node type of the syntax tree is named on the page. */
	@Test(timeout = 60000)
	public void everyNodeTypeIsDocumented() {
		String reference = reference();
		Class<?>[] nodeTypes = PdslNode.class.getDeclaredClasses();
		Assert.assertTrue(nodeTypes.length > 0);
		for (Class<?> type : nodeTypes) {
			Assert.assertTrue("the reference does not name the node type " + type.getSimpleName(),
					reference.contains("`" + type.getSimpleName() + "`"));
		}
	}

	/**
	 * Every name in the built-in catalog is dispatched by the built-in library, and the catalog
	 * holds as many rows as the count it states. A built-in either returns a value or rejects the
	 * empty argument list; a name the library does not know returns {@code null}.
	 */
	@Test(timeout = 60000)
	public void everyCatalogedBuiltinIsDispatched() {
		String catalog = section("## Builtin catalog", "## Extending PDSL");
		Set<String> names = new LinkedHashSet<>();
		Matcher row = CATALOG_ROW.matcher(catalog);
		while (row.find()) names.add(row.group(1));

		Matcher count = CATALOG_COUNT.matcher(catalog);
		Assert.assertTrue("the catalog does not state how many built-ins there are", count.find());
		Assert.assertEquals("built-ins named in the catalog", Integer.parseInt(count.group(1)), names.size());

		Assert.assertFalse("an unknown name must not dispatch", dispatches("not_a_builtin"));
		for (String name : names) {
			Assert.assertTrue("the catalog lists '" + name + "', which PdslBuiltins does not dispatch",
					dispatches(name));
		}
	}

	/** The parse-level known limits the page records still hold. */
	@Test(timeout = 60000)
	public void recordedParseLimitsStillHold() {
		assertRejected("layer conditional(n: int) {\n    if n {\n        relu()\n    }\n}\n");
		assertRejected("config compare {\n    flag = 1 == 1\n}\n");
		assertRejected("config separated {\n    a = 1;\n    b = 2\n}\n");
	}

	/**
	 * Builds a layer of the page's examples for a {@code [1, 4]} input and checks its output shape.
	 *
	 * @param name  the layer
	 * @param args  its arguments
	 * @param shape the output extent the page states
	 */
	private void assertOutput(String name, Map<String, Object> args, int... shape) {
		Block block = new PdslLoader().buildLayer(program("layer " + name + "("), name,
				new TraversalPolicy(1, 4), args);
		Assert.assertArrayEquals(name + " output shape", shape, block.getOutputShape().extent());
	}

	/**
	 * Asserts that the parser rejects {@code source}.
	 *
	 * @param source PDSL source using a construct the page records as unsupported
	 */
	private void assertRejected(String source) {
		try {
			new PdslLoader().parse(source);
			Assert.fail("the reference records this as a parse error, but it parsed:\n" + source);
		} catch (PdslParseException expected) {
			// recorded limit still holds
		}
	}

	/**
	 * Returns whether the built-in library dispatches {@code name}.
	 *
	 * @param name the function name
	 * @return true if the name is a built-in
	 */
	private static boolean dispatches(String name) {
		try {
			return PdslBuiltins.call(name, new ArrayList<>(), new HashSet<>()) != null;
		} catch (RuntimeException rejectedArguments) {
			return true;
		}
	}

	/**
	 * Parses the example of the page that contains {@code declaration}.
	 *
	 * @param declaration text that starts the definition, such as {@code "layer gain("}
	 * @return the parsed example
	 */
	private static PdslNode.Program program(String declaration) {
		for (String example : examples()) {
			if (example.contains(declaration)) return new PdslLoader().parse(example);
		}
		throw new AssertionError("the reference has no complete example containing '" + declaration + "'");
	}

	/**
	 * Returns every complete example of the page, in order.
	 *
	 * @return the source of each fenced {@code pdsl} block
	 */
	private static List<String> examples() {
		List<String> examples = new ArrayList<>();
		Matcher block = PDSL_BLOCK.matcher(reference());
		while (block.find()) examples.add(block.group(1));
		return examples;
	}

	/**
	 * Returns the part of the page from one heading up to the next named heading.
	 *
	 * @param start the heading that opens the section
	 * @param end   the heading that follows it
	 * @return the section text
	 */
	private static String section(String start, String end) {
		String reference = reference();
		int from = reference.indexOf(start);
		int to = reference.indexOf(end, from);
		Assert.assertTrue("the reference has no section " + start, from >= 0 && to > from);
		return reference.substring(from, to);
	}

	/**
	 * Reads the language reference, searching upward from the working directory for the
	 * repository root that holds it.
	 *
	 * @return the page's text
	 */
	private static String reference() {
		Path dir = Paths.get("").toAbsolutePath();
		while (dir != null && !Files.exists(dir.resolve(REFERENCE))) {
			dir = dir.getParent();
		}
		if (dir == null) {
			throw new AssertionError(REFERENCE + " not found above " + Paths.get("").toAbsolutePath());
		}
		try {
			return Files.readString(dir.resolve(REFERENCE), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("cannot read " + REFERENCE, e);
		}
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
