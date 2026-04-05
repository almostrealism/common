/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.midi.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslInterpreter;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Milestone 3 tests: verify that the SkyTNT PDSL files parse correctly and
 * produce blocks with the expected input shapes.
 *
 * <p>All tests use synthetic (random) weights — no real model checkpoint is
 * required.  Tests are CI-safe and complete in seconds.</p>
 *
 * @see org.almostrealism.ml.midi.SkyTntMidi
 */
public class SkyTntPdslTest extends TestSuiteBase {

	/** Hidden size (reduced from 1024 for test speed). */
	private static final int DIM = 64;

	/** FFN intermediate size (4x ratio as in the real net, reduced for tests). */
	private static final int HIDDEN_DIM = 256;

	/** FFN intermediate size for net_token (1:1 ratio). */
	private static final int HIDDEN_DIM_TOKEN = 64;

	/** Number of attention heads for net. */
	private static final int HEADS = 4;

	/** Number of attention heads for net_token. */
	private static final int HEADS_TOKEN = 2;

	/** Head size for net: DIM / HEADS. */
	private static final int HEAD_SIZE = DIM / HEADS;

	/** Head size for net_token: DIM / HEADS_TOKEN. */
	private static final int HEAD_SIZE_TOKEN = DIM / HEADS_TOKEN;

	/** Sequence length for RoPE frequency table. */
	private static final int SEQ_LEN = 32;

	/** RMSNorm epsilon. */
	private static final double EPSILON = 1e-5;

	/** Vocabulary size (small value for test speed). */
	private static final int VOCAB_SIZE = 64;

	/**
	 * Verify that skytnt_block.pdsl parses and contains the expected layer definitions.
	 */
	@Test
	public void testSkytntBlockParsing() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");

		Assert.assertNotNull("Program should not be null", program);
		Assert.assertFalse("Program should have definitions",
				program.getDefinitions().isEmpty());

		PdslInterpreter interpreter = new PdslInterpreter(program);
		Assert.assertTrue("Should have 'skytnt_block' layer",
				interpreter.getLayerNames().contains("skytnt_block"));
		Assert.assertTrue("Should have 'skytnt_ffn' layer",
				interpreter.getLayerNames().contains("skytnt_ffn"));
	}

	/**
	 * Verify that skytnt_lm_head.pdsl parses and contains the expected layer definitions.
	 */
	@Test
	public void testSkytntLmHeadParsing() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		Assert.assertNotNull("Program should not be null", program);

		PdslInterpreter interpreter = new PdslInterpreter(program);
		Assert.assertTrue("Should have 'skytnt_norm' layer",
				interpreter.getLayerNames().contains("skytnt_norm"));
		Assert.assertTrue("Should have 'skytnt_lm_head' layer",
				interpreter.getLayerNames().contains("skytnt_lm_head"));
	}

	/**
	 * Verify that skytnt_ffn can be loaded as a block with the correct input shape.
	 */
	@Test
	public void testSkytntFfnBlock() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("norm_weights", new PackedCollection(new TraversalPolicy(DIM)));
		args.put("gate_proj", new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM)));
		args.put("up_proj", new PackedCollection(new TraversalPolicy(HIDDEN_DIM, DIM)));
		args.put("down_proj", new PackedCollection(new TraversalPolicy(DIM, HIDDEN_DIM)));
		args.put("epsilon", EPSILON);

		Block block = loader.buildLayer(program, "skytnt_ffn",
				new TraversalPolicy(1, DIM), args);

		Assert.assertNotNull("skytnt_ffn block should not be null", block);
		Assert.assertNotNull("Block should have input shape", block.getInputShape());
	}

	/**
	 * Verify that a skytnt_block (full LLaMA block) can be loaded for net hyperparameters.
	 */
	@Test
	public void testSkytntBlockMainNet() {
		Block block = buildSkytntBlock(HEADS, HEAD_SIZE, HIDDEN_DIM);
		Assert.assertNotNull("skytnt_block should not be null", block);
		Assert.assertNotNull("Block should have input shape", block.getInputShape());
		Assert.assertEquals("Input dim[0] should be 1", 1, block.getInputShape().length(0));
		Assert.assertEquals("Input dim[1] should be DIM", DIM, block.getInputShape().length(1));
	}

	/**
	 * Verify that a skytnt_block can be loaded for net_token hyperparameters
	 * (fewer heads, larger head size).
	 */
	@Test
	public void testSkytntBlockTokenNet() {
		Block block = buildSkytntBlock(HEADS_TOKEN, HEAD_SIZE_TOKEN, HIDDEN_DIM_TOKEN);
		Assert.assertNotNull("skytnt_block (token net) should not be null", block);
		Assert.assertNotNull("Block should have input shape", block.getInputShape());
	}

	/**
	 * Verify that skytnt_norm (final norm layer) can be loaded.
	 */
	@Test
	public void testSkytntNormBlock() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("norm_weights", new PackedCollection(new TraversalPolicy(DIM)));
		args.put("epsilon", EPSILON);

		Block block = loader.buildLayer(program, "skytnt_norm",
				new TraversalPolicy(1, DIM), args);

		Assert.assertNotNull("skytnt_norm block should not be null", block);
	}

	/**
	 * Verify that skytnt_lm_head (final norm + vocabulary projection) can be loaded.
	 */
	@Test
	public void testSkytntLmHeadBlock() {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/midi/skytnt_lm_head.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("norm_weights", new PackedCollection(new TraversalPolicy(DIM)));
		args.put("lm_head_weight", new PackedCollection(new TraversalPolicy(VOCAB_SIZE, DIM)));
		args.put("epsilon", EPSILON);

		Block block = loader.buildLayer(program, "skytnt_lm_head",
				new TraversalPolicy(1, DIM), args);

		Assert.assertNotNull("skytnt_lm_head block should not be null", block);
	}

	// -----------------------------------------------------------------------
	//  Helpers
	// -----------------------------------------------------------------------

	/**
	 * Build a {@code skytnt_block} with the given head configuration and synthetic weights.
	 *
	 * @param heads    number of attention heads
	 * @param headSize attention head dimension
	 * @param ffnDim   FFN intermediate dimension
	 * @return the constructed block
	 */
	private Block buildSkytntBlock(int heads, int headSize, int ffnDim) {
		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/midi/skytnt_block.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("heads", heads);
		args.put("rms_att_weight", new PackedCollection(new TraversalPolicy(DIM)));
		args.put("wq", new PackedCollection(new TraversalPolicy(DIM, DIM)));
		args.put("wk", new PackedCollection(new TraversalPolicy(DIM, DIM)));
		args.put("wv", new PackedCollection(new TraversalPolicy(DIM, DIM)));
		args.put("wo", new PackedCollection(new TraversalPolicy(DIM, DIM)));
		args.put("freq_cis", new PackedCollection(new TraversalPolicy(SEQ_LEN, headSize / 2, 2)));
		args.put("position", new PackedCollection(1));
		args.put("rms_ffn_weight", new PackedCollection(new TraversalPolicy(DIM)));
		args.put("gate_proj", new PackedCollection(new TraversalPolicy(ffnDim, DIM)));
		args.put("up_proj", new PackedCollection(new TraversalPolicy(ffnDim, DIM)));
		args.put("down_proj", new PackedCollection(new TraversalPolicy(DIM, ffnDim)));
		args.put("epsilon", EPSILON);

		return loader.buildLayer(program, "skytnt_block",
				new TraversalPolicy(1, DIM), args);
	}

}
