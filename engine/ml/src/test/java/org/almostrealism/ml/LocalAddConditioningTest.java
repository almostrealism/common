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

package org.almostrealism.ml;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.ml.audio.ConditioningMode;
import org.almostrealism.ml.audio.DiffusionTransformer;
import org.almostrealism.ml.audio.DiffusionTransformerConfig;
import org.almostrealism.ml.audio.DiffusionTransformerFeatures;
import org.almostrealism.ml.audio.TimestepFeatures;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Standalone tests for the local additive conditioning path of a transformer block: a per-position
 * control tensor is projected by a per-block {@code linear . silu . linear} MLP, left-padded with
 * zeros across any prepended tokens, and added to the hidden state between the attention and
 * feed-forward sub-layers. Proven with synthetic weights only.
 */
public class LocalAddConditioningTest extends TestSuiteBase implements DiffusionTransformerFeatures {

	/** Batch dimension; scaled-dot-product attention currently asserts a batch size of 1. */
	private static final int BATCH = 1;
	/** Conditioned (audio) positions. */
	private static final int POSITIONS = 4;
	/** Prepended tokens ahead of the audio positions. */
	private static final int PREPENDED = 2;
	/** Model dimension. */
	private static final int DIM = 16;
	/** Number of attention heads. */
	private static final int HEADS = 2;
	/** Local conditioning channels. */
	private static final int COND_DIM = 5;

	/**
	 * {@link DiffusionTransformerFeatures#localConditioningEmbedding} applies the MLP per position and
	 * left-pads the prepended positions with zeros, matching a host-side reference.
	 */
	@Test(timeout = 120000)
	public void embeddingMatchesReferenceAndPads() {
		PackedCollection cond = new PackedCollection(shape(BATCH, POSITIONS, COND_DIM)).randnFill();
		PackedCollection w0 = new PackedCollection(shape(DIM, COND_DIM)).randnFill();
		PackedCollection b0 = new PackedCollection(shape(DIM)).randnFill();
		PackedCollection w2 = new PackedCollection(shape(DIM, DIM)).randnFill();
		PackedCollection b2 = new PackedCollection(shape(DIM)).randnFill();

		PackedCollection out = evaluate(localConditioningEmbedding(cp(cond), w0, b0, w2, b2,
				BATCH, POSITIONS, PREPENDED, DIM)).reshape(shape(BATCH, PREPENDED + POSITIONS, DIM));

		for (int p = 0; p < PREPENDED; p++) {
			for (int d = 0; d < DIM; d++) {
				assertEquals(0.0, out.valueAt(0, p, d), 1e-6);
			}
		}

		for (int p = 0; p < POSITIONS; p++) {
			double[] hidden = new double[DIM];
			for (int i = 0; i < DIM; i++) {
				double h = b0.valueAt(i);
				for (int j = 0; j < COND_DIM; j++) {
					h += cond.valueAt(0, p, j) * w0.valueAt(i, j);
				}
				hidden[i] = h / (1.0 + Math.exp(-h));
			}

			for (int o = 0; o < DIM; o++) {
				double expected = b2.valueAt(o);
				for (int i = 0; i < DIM; i++) {
					expected += hidden[i] * w2.valueAt(o, i);
				}
				assertEquals(expected, out.valueAt(0, PREPENDED + p, o), 1e-3);
			}
		}
	}

	/**
	 * With both residual branches closed by adaLN gates, a transformer block reduces to
	 * {@code x + localAddition}, proving the addition lands between the sub-layers and nowhere else;
	 * with a null addition the same block is the identity.
	 */
	@Test(timeout = 240000)
	public void additionEntersHiddenState() {
		int seqLen = PREPENDED + POSITIONS;
		BlockWeights w = new BlockWeights(seqLen);
		PackedCollection input = new PackedCollection(shape(BATCH, seqLen, DIM)).randnFill();
		PackedCollection addition = new PackedCollection(shape(BATCH, seqLen, DIM)).randnFill();

		PackedCollection closedGates = new PackedCollection(
				shape(AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, DIM));
		cp(pack(0.0, 0.0, 40.0, 0.0, 0.0, 40.0))
				.reshape(shape(AdaptiveLayerNormFeatures.MODULATION_COMPONENTS, 1)).repeat(1, DIM)
				.into(closedGates.traverseEach()).evaluate();
		Producer<PackedCollection> modulation = adaptiveModulationParameters(
				cp(new PackedCollection(shape(BATCH, DIM))), closedGates, BATCH, DIM);

		PackedCollection identity = run(w.block(modulation, null), seqLen, input);
		PackedCollection shifted = run(w.block(modulation, cp(addition)), seqLen, input);

		for (int s = 0; s < seqLen; s++) {
			for (int d = 0; d < DIM; d++) {
				assertEquals(input.valueAt(0, s, d), identity.valueAt(0, s, d), 1e-4);
				assertEquals(input.valueAt(0, s, d) + addition.valueAt(0, s, d), shifted.valueAt(0, s, d), 1e-4);
			}
		}
	}

	/**
	 * A {@link DiffusionTransformer} configured with local additive conditioning, adaLN, memory
	 * tokens and deterministic timestep features builds from a state dictionary that carries exactly
	 * the keys the released checkpoints carry (per-layer {@code to_local_embed} MLP, bare
	 * {@code to_scale_shift_gate}, {@code global_cond_embedder}, no learned timestep frequencies),
	 * consumes every one of them, and runs a forward pass; writing a non-zero local conditioning
	 * into the model's buffer changes the output.
	 */
	@Test(timeout = 240000)
	public void diffusionTransformerConsumesLocalCondWeights() {
		int ioChannels = 2;
		int embedDim = 32;
		int depth = 2;
		int numHeads = 2;
		int globalCondDim = 16;
		int audioSeqLen = 8;
		int memoryTokens = 3;
		int localDim = 3;

		DiffusionTransformerConfig config = new DiffusionTransformerConfig(
				ioChannels, embedDim, depth, numHeads, 1, 0, globalCondDim, "rf_denoiser", audioSeqLen, 4)
				.withConditioningMode(ConditioningMode.ADALN)
				.withMemoryTokens(memoryTokens)
				.withLocalAddCondDim(localDim)
				.withTimestepFeatures(TimestepFeatures.EXPO);

		DiffusionTransformer transformer = new DiffusionTransformer(config,
				new StateDictionary(ditWeights(config)));

		int batchSize = DiffusionTransformer.batchSize;
		PackedCollection input = new PackedCollection(shape(batchSize, ioChannels, audioSeqLen)).randnFill();
		PackedCollection timestep = new PackedCollection(shape(batchSize, 1)).fill(0.5);
		PackedCollection globalCond = new PackedCollection(shape(batchSize, globalCondDim)).randnFill();

		PackedCollection plain = transformer.forward(input, timestep, null, globalCond);
		assertEquals(input.getShape().getTotalSize(), plain.getShape().getTotalSize());
		double[] plainValues = plain.toArray(0, plain.getShape().getTotalSize());

		PackedCollection localCond = transformer.getLocalAddCond();
		assertEquals(batchSize * localDim * audioSeqLen, localCond.getShape().getTotalSize());
		PackedCollection control = new PackedCollection(localCond.getShape()).randnFill();
		cp(control).into(localCond.traverseEach()).evaluate();

		PackedCollection conditioned = transformer.forward(input, timestep, null, globalCond);
		double[] conditionedValues = conditioned.toArray(0, conditioned.getShape().getTotalSize());

		double diff = 0.0;
		for (int i = 0; i < plainValues.length; i++) {
			diff = Math.max(diff, Math.abs(plainValues[i] - conditionedValues[i]));
		}

		transformer.destroy();
		log("local conditioning changed the output by up to " + diff);
		assertTrue("A non-zero local conditioning must change the model output", diff > 1e-6);
	}

	/**
	 * Builds the complete weight set the configured {@link DiffusionTransformer} consumes, with the
	 * key layout of the released checkpoints.
	 *
	 * @param config the transformer configuration
	 * @return the weight map for a {@link StateDictionary}
	 */
	private Map<String, PackedCollection> ditWeights(DiffusionTransformerConfig config) {
		int dim = config.getEmbedDim();
		int io = config.getIoChannels();
		int dimHead = dim / config.getNumHeads();
		int hiddenDim = dim * 4;
		int packed = AdaptiveLayerNormFeatures.MODULATION_COMPONENTS * dim;
		Map<String, PackedCollection> w = new HashMap<>();

		put(w, "model.model.to_timestep_embed.0.weight", dim, 256);
		put(w, "model.model.to_timestep_embed.0.bias", dim);
		put(w, "model.model.to_timestep_embed.2.weight", dim, dim);
		put(w, "model.model.to_timestep_embed.2.bias", dim);
		put(w, "model.model.to_global_embed.0.weight", dim, config.getGlobalCondDim());
		put(w, "model.model.to_global_embed.2.weight", dim, dim);
		put(w, "model.model.preprocess_conv.weight", io, io);
		put(w, "model.model.postprocess_conv.weight", io, io);
		put(w, "model.model.transformer.project_in.weight", dim, io);
		put(w, "model.model.transformer.project_out.weight", io, dim);
		put(w, "model.model.transformer.rotary_pos_emb.inv_freq", dimHead / 4);
		put(w, "model.model.transformer.memory_tokens", config.getNumMemoryTokens(), dim);
		put(w, "model.model.transformer.global_cond_embedder.0.weight", dim, dim);
		put(w, "model.model.transformer.global_cond_embedder.0.bias", dim);
		put(w, "model.model.transformer.global_cond_embedder.2.weight", packed, dim);
		put(w, "model.model.transformer.global_cond_embedder.2.bias", packed);

		for (int i = 0; i < config.getDepth(); i++) {
			String p = "model.model.transformer.layers." + i;
			put(w, p + ".pre_norm.gamma", dim);
			put(w, p + ".pre_norm.beta", dim);
			put(w, p + ".self_attn.to_qkv.weight", dim * 3, dim);
			put(w, p + ".self_attn.to_out.weight", dim, dim);
			put(w, p + ".self_attn.q_norm.weight", dimHead);
			put(w, p + ".self_attn.q_norm.bias", dimHead);
			put(w, p + ".self_attn.k_norm.weight", dimHead);
			put(w, p + ".self_attn.k_norm.bias", dimHead);
			put(w, p + ".ff_norm.gamma", dim);
			put(w, p + ".ff_norm.beta", dim);
			put(w, p + ".ff.ff.0.proj.weight", 2 * hiddenDim, dim);
			put(w, p + ".ff.ff.0.proj.bias", 2 * hiddenDim);
			put(w, p + ".ff.ff.2.weight", dim, hiddenDim);
			put(w, p + ".ff.ff.2.bias", dim);
			put(w, p + ".to_scale_shift_gate", packed);
			put(w, p + ".to_local_embed.0.weight", dim, config.getLocalAddCondDim());
			put(w, p + ".to_local_embed.0.bias", dim);
			put(w, p + ".to_local_embed.2.weight", dim, dim);
			put(w, p + ".to_local_embed.2.bias", dim);
		}

		return w;
	}

	/**
	 * Adds a small random weight of the given shape to the weight map.
	 *
	 * @param weights the weight map
	 * @param key     the weight key
	 * @param dims    the weight dimensions
	 */
	private void put(Map<String, PackedCollection> weights, String key, int... dims) {
		PackedCollection value = new PackedCollection(shape(dims)).randnFill();
		weights.put(key, cp(value).multiply(0.1).into(value.traverseEach()).evaluate());
	}

	/**
	 * Compiles a block into a model and runs one forward pass.
	 *
	 * @param block  the block under test
	 * @param seqLen sequence length
	 * @param input  the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, int seqLen, PackedCollection input) {
		Model model = new Model(shape(BATCH, seqLen, DIM));
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * Evaluates a producer at the test boundary.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}

	/**
	 * Random weights for a single transformer block without cross-attention.
	 */
	private class BlockWeights {
		/** Sequence length the block is built for. */
		private final int seqLen;
		/** Self-attention pre-norm weight. */
		private final PackedCollection preNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Self-attention pre-norm bias. */
		private final PackedCollection preNormBias = new PackedCollection(shape(DIM));
		/** Fused query/key/value projection. */
		private final PackedCollection qkv = new PackedCollection(shape(DIM * 3, DIM)).randnFill();
		/** Attention output projection. */
		private final PackedCollection wo = new PackedCollection(shape(DIM, DIM)).randnFill();
		/** Query normalization weight. */
		private final PackedCollection qNormWeight = new PackedCollection(shape(DIM / HEADS)).fill(1.0);
		/** Query normalization bias. */
		private final PackedCollection qNormBias = new PackedCollection(shape(DIM / HEADS));
		/** Key normalization weight. */
		private final PackedCollection kNormWeight = new PackedCollection(shape(DIM / HEADS)).fill(1.0);
		/** Key normalization bias. */
		private final PackedCollection kNormBias = new PackedCollection(shape(DIM / HEADS));
		/** Rotary inverse frequencies. */
		private final PackedCollection invFreq = new PackedCollection(shape(DIM / HEADS / 4)).randnFill();
		/** Feed-forward pre-norm weight. */
		private final PackedCollection ffnNormWeight = new PackedCollection(shape(DIM)).fill(1.0);
		/** Feed-forward pre-norm bias. */
		private final PackedCollection ffnNormBias = new PackedCollection(shape(DIM));
		/** Feed-forward gate/up projection. */
		private final PackedCollection w1 = new PackedCollection(shape(2 * DIM * 4, DIM)).randnFill();
		/** Feed-forward gate/up bias. */
		private final PackedCollection w1Bias = new PackedCollection(shape(2 * DIM * 4));
		/** Feed-forward down projection. */
		private final PackedCollection w2 = new PackedCollection(shape(DIM, DIM * 4)).randnFill();
		/** Feed-forward down bias. */
		private final PackedCollection w2Bias = new PackedCollection(shape(DIM));

		/**
		 * Creates weights for a block over the given sequence length.
		 *
		 * @param seqLen sequence length
		 */
		private BlockWeights(int seqLen) {
			this.seqLen = seqLen;
		}

		/**
		 * Builds the block with an optional modulation and local addition.
		 *
		 * @param modulation    packed adaLN modulation, or {@code null}
		 * @param localAddition per-position addition, or {@code null}
		 * @return the block
		 */
		private Block block(Producer<PackedCollection> modulation, Producer<PackedCollection> localAddition) {
			return transformerBlock(
					BATCH, DIM, seqLen, HEADS,
					false, 0, null,
					preNormWeight, preNormBias,
					qkv, wo,
					qNormWeight, qNormBias,
					kNormWeight, kNormBias,
					invFreq,
					null, null,
					null, null, null,
					null, null,
					null, null,
					ffnNormWeight, ffnNormBias,
					w1, w2, w1Bias, w2Bias,
					null, ProjectionFactory.dense(),
					AttentionVariant.STANDARD, null, modulation, localAddition);
		}
	}
}
