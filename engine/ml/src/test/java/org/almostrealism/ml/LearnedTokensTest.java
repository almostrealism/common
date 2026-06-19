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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Standalone tests for learned memory/register tokens — Block B2 of the Stable Audio 3 component
 * plan — proven independently of any real Stable Audio 3 weights.
 *
 * <p>Memory tokens are a fixed set of learnable embeddings ({@code [numMemoryTokens, dim]}) prepended
 * to the transformer's token sequence. They occupy the leading positions, are attended to by every
 * block, and are stripped before the output projection so the result corresponds only to the real
 * audio tokens. The tests verify:</p>
 * <ul>
 *   <li>the {@link LearnedTokenFeatures#learnedTokens} broadcast of a {@code [K, dim]} parameter to
 *       {@code [batch, K, dim]} against a direct host-side reference, and the
 *       {@link LearnedTokenFeatures#prependLearnedTokens} prepend/strip symmetry for {@code K > 1};</li>
 *   <li>that, in a full {@link DiffusionTransformer}, enabling memory tokens grows the sequence length
 *       by {@code numMemoryTokens} through the blocks (verified on the captured pre/post-transformer
 *       state) and restores the original audio shape at the output, in both
 *       {@link ConditioningMode#PREPEND} and {@link ConditioningMode#ADALN} modes;</li>
 *   <li>that the learned memory-token parameter is actually applied — its values appear at the leading
 *       sequence positions consumed by the blocks;</li>
 *   <li>that {@code numMemoryTokens == 0} reproduces the pre-change path exactly (byte-identical
 *       output) in both conditioning modes.</li>
 * </ul>
 */
public class LearnedTokensTest extends TestSuiteBase implements LearnedTokenFeatures {

	/** Batch dimension; scaled-dot-product attention currently asserts a batch size of 1. */
	private static final int BATCH = 1;
	/** Sequence length used by the primitive (non-DiT) tests. */
	private static final int SEQ_LEN = 5;
	/** Model dimension used by the primitive (non-DiT) tests. */
	private static final int DIM = 16;
	/** Number of learned memory tokens used by the primitive (non-DiT) tests. */
	private static final int NUM_TOKENS = 3;

	/**
	 * {@link LearnedTokenFeatures#learnedTokens} must broadcast a learned {@code [K, dim]} parameter to a
	 * {@code [batch, K, dim]} producer, replicating each token exactly across the batch.
	 */
	@Test(timeout = 120000)
	public void learnedTokensBroadcastMatchesParameter() {
		PackedCollection tokenWeights = new PackedCollection(shape(NUM_TOKENS, DIM)).randnFill();

		PackedCollection out = evaluate(learnedTokens(BATCH, NUM_TOKENS, DIM, tokenWeights));

		assertEquals(BATCH * NUM_TOKENS * DIM, out.getShape().getTotalSize());

		double maxError = 0.0;
		for (int b = 0; b < BATCH; b++) {
			for (int k = 0; k < NUM_TOKENS; k++) {
				for (int d = 0; d < DIM; d++) {
					double expected = tokenWeights.valueAt(k, d);
					maxError = Math.max(maxError, Math.abs(expected - out.valueAt(b, k, d)));
					assertEquals(expected, out.valueAt(b, k, d), 1e-5);
				}
			}
		}
		log("learnedTokens broadcast max error vs reference = " + maxError);
	}

	/**
	 * {@link LearnedTokenFeatures#prependLearnedTokens} must place the learned tokens at the leading
	 * {@code K} positions and leave the original sequence untouched at the trailing positions — so that
	 * stripping the first {@code K} tokens recovers the original sequence exactly (prepend/strip symmetry
	 * for {@code K > 1}).
	 */
	@Test(timeout = 120000)
	public void prependLearnedTokensGrowsAndStripRecovers() {
		PackedCollection tokenWeights = new PackedCollection(shape(NUM_TOKENS, DIM)).randnFill();
		PackedCollection input = new PackedCollection(shape(BATCH, SEQ_LEN, DIM)).randnFill();

		Block prepend = prependLearnedTokens(BATCH, SEQ_LEN, NUM_TOKENS, DIM, tokenWeights);
		PackedCollection out = run(prepend, shape(BATCH, SEQ_LEN, DIM), input);

		assertEquals(BATCH * (NUM_TOKENS + SEQ_LEN) * DIM, out.getShape().getTotalSize());

		// Leading K positions hold the learned tokens.
		for (int b = 0; b < BATCH; b++) {
			for (int k = 0; k < NUM_TOKENS; k++) {
				for (int d = 0; d < DIM; d++) {
					assertEquals(tokenWeights.valueAt(k, d), out.valueAt(b, k, d), 1e-5);
				}
			}
		}

		// Trailing positions recover the original sequence exactly (the strip is dropping the first K).
		double maxError = 0.0;
		for (int b = 0; b < BATCH; b++) {
			for (int s = 0; s < SEQ_LEN; s++) {
				for (int d = 0; d < DIM; d++) {
					double expected = input.valueAt(b, s, d);
					double actual = out.valueAt(b, NUM_TOKENS + s, d);
					maxError = Math.max(maxError, Math.abs(expected - actual));
					assertEquals(expected, actual, 1e-5);
				}
			}
		}
		log("prepend/strip round-trip max error vs original = " + maxError);
	}

	/**
	 * With memory tokens enabled in {@link ConditioningMode#PREPEND} mode, the sequence carried through
	 * the transformer blocks must grow by {@code numMemoryTokens} (on top of the single prepended
	 * conditioning token), and the model output must be restored to the original audio shape — the
	 * memory-token positions are dropped.
	 */
	@Test(timeout = 240000)
	public void memoryTokensGrowSequenceAndRestoreOutputPrepend() {
		int numMemoryTokens = 4;
		DiffusionTransformer transformer = newTransformer(ConditioningMode.PREPEND, numMemoryTokens, null);

		PackedCollection output = forward(transformer);

		// Sequence inside the blocks = audio + memory tokens + one prepended conditioning token.
		int expectedSeqLen = DIT_AUDIO_SEQ_LEN + numMemoryTokens + 1;
		assertSequenceLength(transformer, expectedSeqLen);

		assertEquals("PREPEND output should restore the input shape (memory tokens dropped)",
				BATCH * DIT_IO_CHANNELS * DIT_AUDIO_SEQ_LEN, output.getShape().getTotalSize());
		transformer.destroy();
		log("prepend-mode memory-token forward: blocks saw seqLen=" + expectedSeqLen
				+ ", output shape = " + output.getShape());
	}

	/**
	 * With memory tokens enabled in {@link ConditioningMode#ADALN} mode, the sequence carried through the
	 * blocks must grow by exactly {@code numMemoryTokens} (adaLN does not prepend a conditioning token),
	 * and the output must be restored to the original audio shape.
	 */
	@Test(timeout = 240000)
	public void memoryTokensGrowSequenceAndRestoreOutputAdaLN() {
		int numMemoryTokens = 4;
		DiffusionTransformer transformer = newTransformer(ConditioningMode.ADALN, numMemoryTokens,
				new StateDictionary(ditWeights(true, numMemoryTokens)));

		PackedCollection output = forward(transformer);

		// adaLN does not lengthen the sequence with a conditioning token, only the memory tokens do.
		int expectedSeqLen = DIT_AUDIO_SEQ_LEN + numMemoryTokens;
		assertSequenceLength(transformer, expectedSeqLen);

		assertEquals("ADALN output should restore the input shape (memory tokens dropped)",
				BATCH * DIT_IO_CHANNELS * DIT_AUDIO_SEQ_LEN, output.getShape().getTotalSize());
		transformer.destroy();
		log("adaLN-mode memory-token forward: blocks saw seqLen=" + expectedSeqLen
				+ ", output shape = " + output.getShape());
	}

	/**
	 * The learned memory-token parameter must actually be applied: with a known {@code memory_tokens}
	 * weight loaded from a {@link StateDictionary}, its values must appear verbatim at the leading
	 * sequence positions consumed by the transformer blocks (the captured pre-transformer state). This
	 * proves the parameter is loaded and inserted, not ignored.
	 */
	@Test(timeout = 240000)
	public void memoryTokenParameterIsApplied() {
		int numMemoryTokens = 4;
		Map<String, PackedCollection> weights = ditWeights(false, numMemoryTokens);

		// Distinctive, easily-checked pattern for the memory-token parameter.
		PackedCollection memoryTokens = new PackedCollection(shape(numMemoryTokens, DIT_EMBED_DIM));
		memoryTokens.fill(pos -> pos[0] + pos[1] * 0.01);
		weights.put("model.model.transformer.memory_tokens", memoryTokens);

		DiffusionTransformer transformer =
				newTransformer(ConditioningMode.PREPEND, numMemoryTokens, new StateDictionary(weights));
		forward(transformer);

		PackedCollection preState = transformer.getPreTransformerState();
		assertNotNull("Pre-transformer state must be populated after the forward pass", preState);

		// Order is [memory tokens, conditioning token, audio]; the memory tokens occupy positions [0, K).
		double maxError = 0.0;
		for (int b = 0; b < BATCH; b++) {
			for (int k = 0; k < numMemoryTokens; k++) {
				for (int d = 0; d < DIT_EMBED_DIM; d++) {
					double expected = memoryTokens.valueAt(k, d);
					double actual = preState.valueAt(b, k, d);
					maxError = Math.max(maxError, Math.abs(expected - actual));
					assertEquals(expected, actual, 1e-4);
				}
			}
		}
		transformer.destroy();
		log("memory-token parameter applied; max error at leading positions = " + maxError);
	}

	/**
	 * In {@link ConditioningMode#PREPEND} mode, constructing the model with {@code numMemoryTokens == 0}
	 * must produce output byte-identical to the legacy constructor (which has no memory-token concept),
	 * confirming the default path is behaviour-preserving.
	 */
	@Test(timeout = 240000)
	public void zeroMemoryTokensMatchesLegacyPrepend() {
		int unusedMemoryTokens = 4;
		Map<String, PackedCollection> weights = ditWeights(false, unusedMemoryTokens);

		Map<String, PackedCollection> legacyWeights = new HashMap<>();
		Map<String, PackedCollection> zeroWeights = new HashMap<>();
		weights.forEach((k, v) -> { legacyWeights.put(k, v.clone()); zeroWeights.put(k, v.clone()); });

		DiffusionTransformer legacy = new DiffusionTransformer(
				DIT_IO_CHANNELS, DIT_EMBED_DIM, DIT_DEPTH, DIT_NUM_HEADS, 1,
				0, DIT_GLOBAL_COND_DIM, "rf_denoiser",
				DIT_AUDIO_SEQ_LEN, DIT_COND_SEQ_LEN, ConditioningMode.PREPEND,
				new StateDictionary(legacyWeights), false);
		DiffusionTransformer zeroMemory = new DiffusionTransformer(
				DIT_IO_CHANNELS, DIT_EMBED_DIM, DIT_DEPTH, DIT_NUM_HEADS, 1,
				0, DIT_GLOBAL_COND_DIM, "rf_denoiser",
				DIT_AUDIO_SEQ_LEN, DIT_COND_SEQ_LEN, ConditioningMode.PREPEND, 0,
				new StateDictionary(zeroWeights), false);

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input =
				new PackedCollection(shape(batchSize, DIT_IO_CHANNELS, DIT_AUDIO_SEQ_LEN)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(batchSize, 1)).randnFill();
		PackedCollection globalCond = new PackedCollection(shape(batchSize, DIT_GLOBAL_COND_DIM)).randnFill();

		double diff = compare(
				legacy.forward(input, timestep, null, globalCond),
				zeroMemory.forward(input, timestep, null, globalCond));
		log("prepend-mode legacy vs numMemoryTokens=0 difference = " + diff);
		assertTrue("numMemoryTokens=0 must reproduce the legacy PREPEND path exactly", diff < 1e-6);

		legacy.destroy();
		zeroMemory.destroy();
	}

	/**
	 * In {@link ConditioningMode#ADALN} mode, constructing the model with {@code numMemoryTokens == 0}
	 * must produce output byte-identical to the legacy adaLN constructor, confirming memory tokens are
	 * orthogonal to the conditioning mode and the default path is behaviour-preserving there too.
	 */
	@Test(timeout = 240000)
	public void zeroMemoryTokensMatchesLegacyAdaLN() {
		int unusedMemoryTokens = 4;
		Map<String, PackedCollection> weights = ditWeights(true, unusedMemoryTokens);

		Map<String, PackedCollection> legacyWeights = new HashMap<>();
		Map<String, PackedCollection> zeroWeights = new HashMap<>();
		weights.forEach((k, v) -> { legacyWeights.put(k, v.clone()); zeroWeights.put(k, v.clone()); });

		DiffusionTransformer legacy = new DiffusionTransformer(
				DIT_IO_CHANNELS, DIT_EMBED_DIM, DIT_DEPTH, DIT_NUM_HEADS, 1,
				0, DIT_GLOBAL_COND_DIM, "rf_denoiser",
				DIT_AUDIO_SEQ_LEN, DIT_COND_SEQ_LEN, ConditioningMode.ADALN,
				new StateDictionary(legacyWeights), false);
		DiffusionTransformer zeroMemory = new DiffusionTransformer(
				DIT_IO_CHANNELS, DIT_EMBED_DIM, DIT_DEPTH, DIT_NUM_HEADS, 1,
				0, DIT_GLOBAL_COND_DIM, "rf_denoiser",
				DIT_AUDIO_SEQ_LEN, DIT_COND_SEQ_LEN, ConditioningMode.ADALN, 0,
				new StateDictionary(zeroWeights), false);

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input =
				new PackedCollection(shape(batchSize, DIT_IO_CHANNELS, DIT_AUDIO_SEQ_LEN)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(batchSize, 1)).randnFill();
		PackedCollection globalCond = new PackedCollection(shape(batchSize, DIT_GLOBAL_COND_DIM)).randnFill();

		double diff = compare(
				legacy.forward(input, timestep, null, globalCond),
				zeroMemory.forward(input, timestep, null, globalCond));
		log("adaLN-mode legacy vs numMemoryTokens=0 difference = " + diff);
		assertTrue("numMemoryTokens=0 must reproduce the legacy ADALN path exactly", diff < 1e-6);

		legacy.destroy();
		zeroMemory.destroy();
	}

	/** Synthetic DiT configuration shared by the model-level tests: input/output channel count. */
	private static final int DIT_IO_CHANNELS = 2;
	/** Synthetic DiT configuration: transformer embedding dimension. */
	private static final int DIT_EMBED_DIM = 32;
	/** Synthetic DiT configuration: number of transformer blocks. */
	private static final int DIT_DEPTH = 1;
	/** Synthetic DiT configuration: number of self-attention heads. */
	private static final int DIT_NUM_HEADS = 2;
	/** Synthetic DiT configuration: global conditioning vector dimension. */
	private static final int DIT_GLOBAL_COND_DIM = 16;
	/** Synthetic DiT configuration: audio sequence length (patches). */
	private static final int DIT_AUDIO_SEQ_LEN = 8;
	/** Synthetic DiT configuration: conditioning sequence length. */
	private static final int DIT_COND_SEQ_LEN = 4;

	/**
	 * Builds a {@link DiffusionTransformer} for the synthetic configuration with the given conditioning
	 * mode, number of memory tokens, and state dictionary.
	 *
	 * @param mode            the conditioning mode
	 * @param numMemoryTokens the number of learned memory tokens
	 * @param stateDict       the state dictionary, or {@code null} for zero-initialized weights
	 * @return the constructed transformer
	 */
	private DiffusionTransformer newTransformer(ConditioningMode mode, int numMemoryTokens,
											   StateDictionary stateDict) {
		return new DiffusionTransformer(
				DIT_IO_CHANNELS, DIT_EMBED_DIM, DIT_DEPTH, DIT_NUM_HEADS, 1,
				0, DIT_GLOBAL_COND_DIM, "rf_denoiser",
				DIT_AUDIO_SEQ_LEN, DIT_COND_SEQ_LEN, mode, numMemoryTokens, stateDict, false);
	}

	/**
	 * Runs a forward pass over the synthetic configuration with random inputs and global conditioning.
	 *
	 * @param transformer the transformer to run
	 * @return the forward-pass output
	 */
	private PackedCollection forward(DiffusionTransformer transformer) {
		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input =
				new PackedCollection(shape(batchSize, DIT_IO_CHANNELS, DIT_AUDIO_SEQ_LEN)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(batchSize, 1)).randnFill();
		PackedCollection globalCond = new PackedCollection(shape(batchSize, DIT_GLOBAL_COND_DIM)).randnFill();
		return transformer.forward(input, timestep, null, globalCond);
	}

	/**
	 * Asserts that both the captured pre-transformer and post-transformer states carry a sequence of the
	 * expected length (axis 1), proving the memory tokens lengthen the sequence through the whole block
	 * stack.
	 *
	 * @param transformer     the transformer (after a forward pass)
	 * @param expectedSeqLen  the expected sequence length inside the blocks
	 */
	private void assertSequenceLength(DiffusionTransformer transformer, int expectedSeqLen) {
		PackedCollection preState = transformer.getPreTransformerState();
		PackedCollection postState = transformer.getPostTransformerState();
		assertNotNull("Pre-transformer state must be populated after the forward pass", preState);
		assertNotNull("Post-transformer state must be populated after the forward pass", postState);
		assertEquals("Pre-transformer sequence length must include the memory tokens",
				expectedSeqLen, preState.getShape().length(1));
		assertEquals("Post-transformer sequence length must include the memory tokens",
				expectedSeqLen, postState.getShape().length(1));
	}

	/**
	 * Builds the complete set of weights a {@link DiffusionTransformer} consumes for the synthetic
	 * configuration (no cross-attention, with global conditioning), including the {@code memory_tokens}
	 * parameter and, optionally, the per-layer {@code to_scale_shift_gate.weight} adaLN parameter.
	 *
	 * @param includeScaleShiftGate whether to include the per-layer {@code to_scale_shift_gate.weight}
	 * @param numMemoryTokens       number of memory tokens (the {@code memory_tokens} weight rows)
	 * @return the weight map for a {@link StateDictionary}
	 */
	private Map<String, PackedCollection> ditWeights(boolean includeScaleShiftGate, int numMemoryTokens) {
		int dimHead = DIT_EMBED_DIM / DIT_NUM_HEADS;
		int hiddenDim = DIT_EMBED_DIM * 4;
		Map<String, PackedCollection> w = new HashMap<>();

		// Timestep embedding
		put(w, "model.model.timestep_features.weight", 128, 1);
		put(w, "model.model.to_timestep_embed.0.weight", DIT_EMBED_DIM, 256);
		put(w, "model.model.to_timestep_embed.0.bias", DIT_EMBED_DIM);
		put(w, "model.model.to_timestep_embed.2.weight", DIT_EMBED_DIM, DIT_EMBED_DIM);
		put(w, "model.model.to_timestep_embed.2.bias", DIT_EMBED_DIM);

		// Global conditioning embedding
		put(w, "model.model.to_global_embed.0.weight", DIT_EMBED_DIM, DIT_GLOBAL_COND_DIM);
		put(w, "model.model.to_global_embed.2.weight", DIT_EMBED_DIM, DIT_EMBED_DIM);

		// Input/output projections
		put(w, "model.model.preprocess_conv.weight", DIT_IO_CHANNELS, DIT_IO_CHANNELS);
		put(w, "model.model.postprocess_conv.weight", DIT_IO_CHANNELS, DIT_IO_CHANNELS);
		put(w, "model.model.transformer.project_in.weight", DIT_EMBED_DIM, DIT_IO_CHANNELS);
		put(w, "model.model.transformer.project_out.weight", DIT_IO_CHANNELS, DIT_EMBED_DIM);
		put(w, "model.model.transformer.rotary_pos_emb.inv_freq", dimHead / 4);

		// Memory / register tokens
		put(w, "model.model.transformer.memory_tokens", numMemoryTokens, DIT_EMBED_DIM);

		for (int i = 0; i < DIT_DEPTH; i++) {
			String p = "model.model.transformer.layers." + i;
			put(w, p + ".pre_norm.gamma", DIT_EMBED_DIM);
			put(w, p + ".pre_norm.beta", DIT_EMBED_DIM);
			put(w, p + ".self_attn.to_qkv.weight", DIT_EMBED_DIM * 3, DIT_EMBED_DIM);
			put(w, p + ".self_attn.to_out.weight", DIT_EMBED_DIM, DIT_EMBED_DIM);
			put(w, p + ".self_attn.q_norm.weight", dimHead);
			put(w, p + ".self_attn.q_norm.bias", dimHead);
			put(w, p + ".self_attn.k_norm.weight", dimHead);
			put(w, p + ".self_attn.k_norm.bias", dimHead);
			put(w, p + ".ff_norm.gamma", DIT_EMBED_DIM);
			put(w, p + ".ff_norm.beta", DIT_EMBED_DIM);
			put(w, p + ".ff.ff.0.proj.weight", 2 * hiddenDim, DIT_EMBED_DIM);
			put(w, p + ".ff.ff.0.proj.bias", 2 * hiddenDim);
			put(w, p + ".ff.ff.2.weight", DIT_EMBED_DIM, hiddenDim);
			put(w, p + ".ff.ff.2.bias", DIT_EMBED_DIM);
			if (includeScaleShiftGate) {
				put(w, p + ".to_scale_shift_gate.weight",
						AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, DIT_EMBED_DIM);
			}
		}
		return w;
	}

	/**
	 * Adds a randomly-initialized weight of the given shape to the weight map.
	 *
	 * @param weights the weight map
	 * @param key     the weight key
	 * @param dims    the weight dimensions
	 */
	private void put(Map<String, PackedCollection> weights, String key, int... dims) {
		weights.put(key, new PackedCollection(shape(dims)).randnFill());
	}

	/**
	 * Compiles a single block into a model and runs one forward pass over the given input.
	 *
	 * @param block      the block under test
	 * @param inputShape the model input shape
	 * @param input      the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, TraversalPolicy inputShape, PackedCollection input) {
		Model model = new Model(inputShape);
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * Evaluates a collection producer at the top of the call stack (test boundary), using the
	 * optimization pass the framework requires for standalone producer evaluation.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}
}
