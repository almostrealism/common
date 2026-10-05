package org.almostrealism.ml.dsl;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for the {@code stack} statement and for weight paths into a checkpoint: a model reads
 * weights as {@code weights.norm.weight}, and {@code stack weights.blocks as block { ... }}
 * repeats its body once per block of the checkpoint, in numeric order, with {@code block}
 * naming that block's weights.
 */
public class PdslStackTest extends TestSuiteBase {

	/** Width of the vectors the test models transform. */
	private static final int DIM = 4;

	/** A model that applies one dense layer per block of {@code weights.blocks}. */
	private static final String STACK_SOURCE =
			"model scaled(weights: checkpoint) {\n"
			+ "    stack weights.blocks as block {\n"
			+ "        dense(block.weight)\n"
			+ "    }\n"
			+ "}\n";

	/**
	 * Builds a checkpoint whose {@code blocks.N.weight} is {@code scales[N]} times the identity,
	 * so that a stack over the blocks multiplies its input by the product of the scales.
	 */
	private StateDictionary scaledIdentities(double... scales) {
		Map<String, PackedCollection> weights = new HashMap<>();
		for (int i = 0; i < scales.length; i++) {
			PackedCollection identity = new PackedCollection(shape(DIM, DIM));
			identity.fill(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
			weights.put("blocks." + i + ".weight", cp(identity).multiply(scales[i]).evaluate());
		}
		return new StateDictionary(weights);
	}

	/** The stack repeats its body once per block and each repetition reads its own weights. */
	@Test(timeout = 120000)
	public void stackAppliesEveryBlock() {
		PdslLoader loader = new PdslLoader();
		Model model = loader.buildModel(loader.parse(STACK_SOURCE), "scaled",
				new TraversalPolicy(1, DIM), scaledIdentities(1.0, 2.0, 3.0), new HashMap<>());

		CompiledModel compiled = model.compile(false);
		PackedCollection input = new PackedCollection(shape(1, DIM));
		input.fill(1.0, 2.0, 3.0, 4.0);

		double[] output = compiled.forward(input).toArray();
		for (int i = 0; i < DIM; i++) {
			Assert.assertEquals("element " + i, (1.0 + i) * 6.0, output[i], 1e-6);
		}
		compiled.destroy();
	}

	/** Numbered blocks are visited in numeric order: block 10 comes after block 9, not after 1. */
	@Test(timeout = 60000)
	public void numberedMembersAreInNumericOrder() {
		Map<String, PackedCollection> weights = new HashMap<>();
		for (int i = 0; i < 11; i++) {
			weights.put("model.layers." + i + ".weight", new PackedCollection(1));
		}
		List<String> members = new StateDictionary(weights).group("model.layers").members();
		Assert.assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"), members);
	}

	/**
	 * Names that are not all numbers are in alphabetical order, and a weight named directly is a
	 * member alongside the groups.
	 */
	@Test(timeout = 60000)
	public void namedMembersAreInAlphabeticalOrder() {
		Map<String, PackedCollection> weights = new HashMap<>();
		weights.put("norm", new PackedCollection(1));
		weights.put("layers.10.weight", new PackedCollection(1));
		weights.put("layers.2.weight", new PackedCollection(1));
		weights.put("embed.weight", new PackedCollection(1));
		StateDictionary dict = new StateDictionary(weights);

		Assert.assertEquals(List.of("embed", "layers", "norm"), dict.members());
		Assert.assertEquals(List.of("2", "10"), dict.group("layers").members());
	}

	/**
	 * A group shares the tensors of the dictionary it comes from, drops the group's name from
	 * each key, and is empty for a name nothing is under. A name that merely starts the same
	 * way as a group ({@code layer} for {@code layers}) is not that group.
	 */
	@Test(timeout = 60000)
	public void groupSharesTensorsUnderName() {
		PackedCollection weight = new PackedCollection(1);
		Map<String, PackedCollection> weights = new HashMap<>();
		weights.put("layers.0.weight", weight);
		weights.put("layers_extra", new PackedCollection(1));
		StateDictionary dict = new StateDictionary(weights);

		StateDictionary layers = dict.group("layers");
		Assert.assertEquals(1, layers.size());
		Assert.assertSame(weight, layers.get("0.weight"));
		Assert.assertSame(weight, layers.group("0").get("weight"));
		Assert.assertEquals(0, dict.group("layer").size());
		Assert.assertEquals(0, dict.group("missing").size());
		Assert.assertTrue(dict.group("missing").members().isEmpty());
	}

	/**
	 * {@link StateDictionary#require} returns a weight that exists, and for one that does not,
	 * names the keys sharing the longest leading part with it, at most three of them.
	 */
	@Test(timeout = 60000)
	public void requireNamesClosestKeys() {
		PackedCollection q = new PackedCollection(1);
		Map<String, PackedCollection> weights = new HashMap<>();
		weights.put("layers.0.q_proj.weight", q);
		weights.put("layers.0.k_proj.weight", new PackedCollection(1));
		weights.put("layers.1.q_proj.weight", new PackedCollection(1));
		weights.put("norm.weight", new PackedCollection(1));
		StateDictionary dict = new StateDictionary(weights);

		Assert.assertSame(q, dict.require("layers.0.q_proj.weight"));

		try {
			dict.require("layers.0.v_proj.weight");
			Assert.fail("A missing weight should be rejected");
		} catch (IllegalArgumentException e) {
			String message = e.getMessage();
			Assert.assertTrue(message, message.contains("'layers.0.v_proj.weight'"));
			Assert.assertTrue(message, message.contains("among 4 weights"));
			Assert.assertTrue(message, message.contains("layers.0.k_proj.weight, layers.0.q_proj.weight"));
			Assert.assertFalse(message, message.contains("norm.weight"));
			Assert.assertFalse(message, message.contains("layers.1"));
		}

		try {
			new StateDictionary(new HashMap<>()).require("anything");
			Assert.fail("A weight missing from an empty dictionary should be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertFalse(e.getMessage(), e.getMessage().contains("closest"));
		}
	}

	/** A model given a checkpoint must declare a {@code checkpoint} parameter to receive it. */
	@Test(timeout = 60000)
	public void modelWithoutCheckpointParameterIsRejected() {
		String source = "model plain(w: weight) {\n"
				+ "    dense(w)\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		try {
			loader.buildModel(loader.parse(source), "plain", new TraversalPolicy(1, DIM),
					scaledIdentities(1.0), new HashMap<>());
			Assert.fail("A model without a checkpoint parameter should be rejected");
		} catch (PdslParseException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("no checkpoint parameter"));
		}
	}

	/**
	 * The checkpoint is bound even when {@code extraArgs} already holds an entry under the
	 * checkpoint parameter's own name: binding is recognised by a parameter being found, not by
	 * the argument map growing, so a colliding entry does not make the model look like it declares
	 * no checkpoint parameter.
	 */
	@Test(timeout = 120000)
	public void checkpointBoundOverCollidingExtraArg() {
		Map<String, Object> extraArgs = new HashMap<>();
		extraArgs.put("weights", "ignored");

		PdslLoader loader = new PdslLoader();
		Model model = loader.buildModel(loader.parse(STACK_SOURCE), "scaled",
				new TraversalPolicy(1, DIM), scaledIdentities(1.0, 2.0, 3.0), extraArgs);

		CompiledModel compiled = model.compile(false);
		PackedCollection input = new PackedCollection(shape(1, DIM));
		input.fill(1.0, 2.0, 3.0, 4.0);

		double[] output = compiled.forward(input).toArray();
		for (int i = 0; i < DIM; i++) {
			Assert.assertEquals("element " + i, (1.0 + i) * 6.0, output[i], 1e-6);
		}
		compiled.destroy();
	}

	/** A stack over a group that holds no weights is rejected. */
	@Test(timeout = 60000)
	public void stackRejectsEmptyGroup() {
		String source = "model broken(weights: checkpoint) {\n"
				+ "    stack weights as block {\n"
				+ "        dense(block.weight)\n"
				+ "    }\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		try {
			loader.buildModel(loader.parse(source), "broken", new TraversalPolicy(1, DIM),
					new StateDictionary(new HashMap<>()), new HashMap<>());
			Assert.fail("A stack over an empty group should be rejected");
		} catch (PdslParseException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("no weights"));
		}
	}

	/** {@code zeros} allocates a zero-filled collection of the shape it is given, and nothing else. */
	@Test(timeout = 120000)
	public void zerosAllocatesStorage() {
		String source = "model shifted(weights: checkpoint) {\n"
				+ "    stack weights.blocks as block {\n"
				+ "        dense(block.weight, zeros([4]))\n"
				+ "    }\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		Model model = loader.buildModel(loader.parse(source), "shifted",
				new TraversalPolicy(1, DIM), scaledIdentities(2.0), new HashMap<>());
		CompiledModel compiled = model.compile(false);
		PackedCollection input = new PackedCollection(shape(1, DIM));
		input.fill(1.0, 2.0, 3.0, 4.0);
		Assert.assertArrayEquals(new double[] { 2.0, 4.0, 6.0, 8.0 }, compiled.forward(input).toArray(), 1e-6);
		compiled.destroy();

		String invalid = "model broken(weights: checkpoint) {\n"
				+ "    stack weights.blocks as block {\n"
				+ "        dense(block.weight, zeros(4))\n"
				+ "    }\n"
				+ "}\n";
		try {
			loader.buildModel(loader.parse(invalid), "broken", new TraversalPolicy(1, DIM),
					scaledIdentities(1.0), new HashMap<>());
			Assert.fail("zeros without a shape should be rejected");
		} catch (PdslParseException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("zeros() expects one shape"));
		}
	}

	/** A path to a weight the checkpoint does not have names the closest weights it does have. */
	@Test(timeout = 60000)
	public void missingWeightPathNamesClosestWeights() {
		String source = "model broken(weights: checkpoint) {\n"
				+ "    stack weights.blocks as block {\n"
				+ "        dense(block.wieght)\n"
				+ "    }\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		try {
			loader.buildModel(loader.parse(source), "broken", new TraversalPolicy(1, DIM),
					scaledIdentities(1.0), new HashMap<>());
			Assert.fail("A misspelled weight path should be rejected");
		} catch (PdslParseException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("wieght"));
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("weight"));
		}
	}

	/** A stack over a single weight, rather than a group of weights, is rejected. */
	@Test(timeout = 60000)
	public void stackRejectsNonGroup() {
		String source = "model broken(weights: checkpoint) {\n"
				+ "    stack weights.norm as block {\n"
				+ "        dense(block)\n"
				+ "    }\n"
				+ "}\n";

		StateDictionary weights = scaledIdentities(1.0);
		weights.put("norm", new PackedCollection(DIM));

		PdslLoader loader = new PdslLoader();
		try {
			loader.buildModel(loader.parse(source), "broken", new TraversalPolicy(1, DIM),
					weights, new HashMap<>());
			Assert.fail("A stack over a single weight should be rejected");
		} catch (PdslParseException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("group of weights"));
		}
	}
}
