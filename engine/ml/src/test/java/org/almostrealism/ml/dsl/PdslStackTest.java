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
 * weights as {@code weights.blocks.0.weight}, and {@code stack weights.blocks as block { ... }}
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
