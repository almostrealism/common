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
import org.almostrealism.model.Block;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for the Producer DSL parser, interpreter, and loader.
 * Validates that .pdsl files can be parsed into ASTs, interpreted
 * into Block objects, and that the resulting blocks have correct shapes.
 */
public class PdslLoaderTest extends TestSuiteBase {

	private static final int DIM = 64;
	private static final int KV_DIM = 16;
	private static final int HIDDEN_DIM = 128;
	private static final int HEADS = 4;
	private static final int KV_HEADS = 1;
	private static final int HEAD_SIZE = DIM / HEADS;
	private static final int SEQ_LEN = 32;
	private static final double EPSILON = 1e-6;

	/**
	 * Test that the lexer and parser can handle basic PDSL syntax
	 * including layer definitions, function calls, and composition.
	 */
	@Test
	public void testParseTransformerBlock() {
		String source = loadPdslSource();
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(source);

		Assert.assertNotNull("Program should not be null", program);
		Assert.assertFalse("Program should have definitions",
				program.getDefinitions().isEmpty());

		PdslInterpreter interpreter = new PdslInterpreter(program);
		Assert.assertTrue("Should have 'swiglu_ffn' layer",
				interpreter.getLayerNames().contains("swiglu_ffn"));
		Assert.assertTrue("Should have 'transformer_block' layer",
				interpreter.getLayerNames().contains("transformer_block"));
		Assert.assertTrue("Should have 'dense_relu' layer",
				interpreter.getLayerNames().contains("dense_relu"));
		Assert.assertTrue("Should have 'normed_projection' layer",
				interpreter.getLayerNames().contains("normed_projection"));
	}

	/**
	 * Test that a dense_relu layer can be loaded and has correct shapes.
	 */
	@Test
	public void testDenseReluLayer() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(loadPdslSource());

		int inDim = 32;
		int outDim = 64;
		PackedCollection weights = new PackedCollection(new TraversalPolicy(outDim, inDim));
		PackedCollection biases = new PackedCollection(new TraversalPolicy(1, outDim));

		Map<String, Object> args = new HashMap<>();
		args.put("weights", weights);
		args.put("biases", biases);

		Block block = loader.buildLayer(program, "dense_relu",
				new TraversalPolicy(1, inDim), args);

		Assert.assertNotNull("Block should not be null", block);
		Assert.assertNotNull("Block should have input shape",
				block.getInputShape());
	}

	/**
	 * Test that a normed_projection layer can be loaded correctly.
	 */
	@Test
	public void testNormedProjectionLayer() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(loadPdslSource());

		PackedCollection normWeights = new PackedCollection(new TraversalPolicy(DIM));
		PackedCollection projWeights = new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM));

		Map<String, Object> args = new HashMap<>();
		args.put("norm_weights", normWeights);
		args.put("proj_weights", projWeights);
		args.put("epsilon", EPSILON);

		Block block = loader.buildLayer(program, "normed_projection",
				new TraversalPolicy(1, DIM), args);

		Assert.assertNotNull("Block should not be null", block);
	}

	/**
	 * Test that a SwiGLU FFN layer can be loaded from primitives.
	 * This validates the product() composition construct.
	 */
	@Test
	public void testSwigluFfnLayer() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(loadPdslSource());

		PackedCollection normWeights = new PackedCollection(new TraversalPolicy(DIM));
		PackedCollection w1 = new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM));
		PackedCollection w2 = new PackedCollection(new TraversalPolicy(DIM, HIDDEN_DIM));
		PackedCollection w3 = new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM));

		Map<String, Object> args = new HashMap<>();
		args.put("norm_weights", normWeights);
		args.put("w1", w1);
		args.put("w2", w2);
		args.put("w3", w3);
		args.put("epsilon", EPSILON);

		Block block = loader.buildLayer(program, "swiglu_ffn",
				new TraversalPolicy(1, DIM), args);

		Assert.assertNotNull("SwiGLU FFN block should not be null", block);
		Assert.assertNotNull("Block should have input shape",
				block.getInputShape());
	}

	/**
	 * Test that a complete transformer block can be loaded.
	 * The block composes attention (built-in) + SwiGLU FFN (user-defined)
	 * with residual connections.
	 */
	@Test
	public void testTransformerBlockLayer() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(loadPdslSource());

		PackedCollection rmsAttWeight = new PackedCollection(new TraversalPolicy(DIM));
		PackedCollection wq = new PackedCollection(new TraversalPolicy(DIM, DIM));
		PackedCollection wk = new PackedCollection(new TraversalPolicy(DIM, DIM));
		PackedCollection wv = new PackedCollection(new TraversalPolicy(DIM, DIM));
		PackedCollection wo = new PackedCollection(new TraversalPolicy(DIM, DIM));
		PackedCollection freqCis = new PackedCollection(
				new TraversalPolicy(SEQ_LEN, HEAD_SIZE / 2, 2));
		PackedCollection position = new PackedCollection(1);
		PackedCollection rmsFfnWeight = new PackedCollection(new TraversalPolicy(DIM));
		PackedCollection w1 = new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM));
		PackedCollection w2 = new PackedCollection(new TraversalPolicy(DIM, HIDDEN_DIM));
		PackedCollection w3 = new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM));

		Map<String, Object> args = new HashMap<>();
		args.put("heads", HEADS);
		args.put("kv_heads", KV_HEADS);
		args.put("rms_att_weight", rmsAttWeight);
		args.put("wq", wq);
		args.put("wk", wk);
		args.put("wv", wv);
		args.put("wo", wo);
		args.put("freq_cis", freqCis);
		args.put("position", position);
		args.put("rms_ffn_weight", rmsFfnWeight);
		args.put("w1", w1);
		args.put("w2", w2);
		args.put("w3", w3);
		args.put("epsilon", EPSILON);

		Block block = loader.buildLayer(program, "transformer_block",
				new TraversalPolicy(1, DIM), args);

		Assert.assertNotNull("Transformer block should not be null", block);
		Assert.assertNotNull("Block should have input shape",
				block.getInputShape());
	}

	/**
	 * Test parsing of inline PDSL source with config blocks.
	 */
	@Test
	public void testConfigParsing() {
		String source = "config test_config {\n"
				+ "    dim = 512\n"
				+ "    heads = 8\n"
				+ "    epsilon = 1e-6\n"
				+ "    hidden_dim = 512 * 4\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(source);
		Map<String, Object> config = loader.evaluateConfig(program, "test_config");

		Assert.assertEquals(512.0, (Double) config.get("dim"), 0.001);
		Assert.assertEquals(8.0, (Double) config.get("heads"), 0.001);
		Assert.assertEquals(1e-6, (Double) config.get("epsilon"), 1e-10);
		Assert.assertEquals(2048.0, (Double) config.get("hidden_dim"), 0.001);
	}

	/**
	 * Test parsing of various expression types.
	 */
	@Test
	public void testExpressionParsing() {
		String source = "config math_test {\n"
				+ "    a = 10 + 5\n"
				+ "    b = 10 - 3\n"
				+ "    c = 4 * 8\n"
				+ "    d = 100 / 4\n"
				+ "    e = (2 + 3) * 4\n"
				+ "    f = -1\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(source);
		Map<String, Object> config = loader.evaluateConfig(program, "math_test");

		Assert.assertEquals(15.0, (Double) config.get("a"), 0.001);
		Assert.assertEquals(7.0, (Double) config.get("b"), 0.001);
		Assert.assertEquals(32.0, (Double) config.get("c"), 0.001);
		Assert.assertEquals(25.0, (Double) config.get("d"), 0.001);
		Assert.assertEquals(20.0, (Double) config.get("e"), 0.001);
		Assert.assertEquals(-1.0, (Double) config.get("f"), 0.001);
	}

	/**
	 * Test that comments are properly skipped during parsing.
	 */
	@Test
	public void testCommentHandling() {
		String source = "// This is a line comment\n"
				+ "/* This is a block comment */\n"
				+ "config commented {\n"
				+ "    x = 42 // inline comment\n"
				+ "    /* multi\n"
				+ "       line */\n"
				+ "    y = 7\n"
				+ "}\n";

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parse(source);
		Map<String, Object> config = loader.evaluateConfig(program, "commented");

		Assert.assertEquals(42.0, (Double) config.get("x"), 0.001);
		Assert.assertEquals(7.0, (Double) config.get("y"), 0.001);
	}

	private String loadPdslSource() {
		return "// SwiGLU Feed-Forward Network\n"
				+ "layer swiglu_ffn(\n"
				+ "    norm_weights: weight,\n"
				+ "    w1: weight,\n"
				+ "    w2: weight,\n"
				+ "    w3: weight,\n"
				+ "    epsilon: float\n"
				+ ") -> [1, " + DIM + "] {\n"
				+ "    rmsnorm(norm_weights, epsilon)\n"
				+ "    product(\n"
				+ "        { dense(w1); silu() },\n"
				+ "        { dense(w3) }\n"
				+ "    )\n"
				+ "    dense(w2)\n"
				+ "}\n"
				+ "\n"
				+ "// Transformer Block with residual connections\n"
				+ "layer transformer_block(\n"
				+ "    heads: int,\n"
				+ "    kv_heads: int,\n"
				+ "    rms_att_weight: weight,\n"
				+ "    wq: weight,\n"
				+ "    wk: weight,\n"
				+ "    wv: weight,\n"
				+ "    wo: weight,\n"
				+ "    freq_cis: weight,\n"
				+ "    position: scalar,\n"
				+ "    rms_ffn_weight: weight,\n"
				+ "    w1: weight,\n"
				+ "    w2: weight,\n"
				+ "    w3: weight,\n"
				+ "    epsilon: float\n"
				+ ") -> [1, " + DIM + "] {\n"
				+ "    accum {\n"
				+ "        attention(heads, rms_att_weight,\n"
				+ "                  wk, wv, wq, wo,\n"
				+ "                  freq_cis, position)\n"
				+ "    }\n"
				+ "    accum {\n"
				+ "        swiglu_ffn(rms_ffn_weight, w1, w2, w3, epsilon)\n"
				+ "    }\n"
				+ "}\n"
				+ "\n"
				+ "// Simple dense + activation\n"
				+ "layer dense_relu(\n"
				+ "    weights: weight,\n"
				+ "    biases: weight\n"
				+ ") -> [1, " + DIM + "] {\n"
				+ "    dense(weights, biases)\n"
				+ "    relu()\n"
				+ "}\n"
				+ "\n"
				+ "// Norm + projection\n"
				+ "layer normed_projection(\n"
				+ "    norm_weights: weight,\n"
				+ "    proj_weights: weight,\n"
				+ "    epsilon: float\n"
				+ ") -> [1, " + DIM + "] {\n"
				+ "    rmsnorm(norm_weights, epsilon)\n"
				+ "    dense(proj_weights)\n"
				+ "}\n";
	}
}
