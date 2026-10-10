package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.AutoregressiveModel;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

/**
 * Checks that the whole-model PDSL files, {@code /pdsl/qwen2.pdsl} and {@code /pdsl/qwen3.pdsl},
 * compute exactly what the per-layer Java assembly they replaced computed. The reference below
 * is that assembly: a Java loop that adds one {@link AttentionFeatures#transformer} block per
 * layer, then the final RMSNorm and the output projection. {@link Qwen3} builds the same model
 * from its PDSL file. Both run the same synthetic checkpoint over several token positions, so
 * the KV caches and the rotary embedding are exercised, and their logits must agree.
 *
 * <p>The comparison is not independent of PDSL below the model level:
 * {@link AttentionFeatures#transformer} itself builds each layer from {@code transformer.pdsl}.
 * What it checks is everything the model files took over from Java — iterating the layers,
 * binding each layer's weights by checkpoint path, giving each layer its own KV caches, the
 * rotary table built from {@code rope_theta}, the final RMSNorm and the tied output
 * projection. The numerics of the layer itself are the subject of the attention and
 * transformer layer tests.</p>
 */
public class QwenModelFileTest extends TestSuiteBase implements AttentionFeatures {

	/** Number of token positions each model is run for. */
	private static final int STEPS = 4;

	/**
	 * A small configuration with grouped-query attention (four query heads share each of two
	 * KV heads) so the cache layout of both paths is tested where it differs from plain
	 * multi-head attention.
	 */
	private static Qwen3Config config() {
		return new Qwen3Config(64, 192, 2, 4, 2, 100, 16, true, 1000000.0);
	}

	/** qwen2.pdsl (query, key and value biases; no QK-norm) matches the Java assembly. */
	@Test(timeout = 600000)
	public void qwen2MatchesLayerAssembly() {
		assertMatchesLayerAssembly(true);
	}

	/** qwen3.pdsl (QK-norm; no biases) matches the Java assembly. */
	@Test(timeout = 600000)
	public void qwen3MatchesLayerAssembly() {
		assertMatchesLayerAssembly(false);
	}

	/**
	 * A configuration with a separate output projection ({@code lm_head.weight}) is refused
	 * with a message naming it, since the model files read the projection from the embedding
	 * table.
	 */
	@Test(timeout = 60000)
	public void untiedOutputWeightsAreRejected() {
		Qwen3Config config = new Qwen3Config(64, 192, 2, 4, 2, 100, 16, false, 1000000.0);
		StateDictionary weights = Qwen3InferenceProfileTest.createRandomWeights(config, 7L, false);
		try {
			new Qwen3(config, weights, Qwen3Tokenizer.createTestTokenizer());
			Assert.fail("A checkpoint with untied output weights should be rejected");
		} catch (UnsupportedOperationException e) {
			Assert.assertTrue(e.getMessage(), e.getMessage().contains("lm_head.weight"));
		}
	}

	/**
	 * Destroying a {@link Qwen3} releases the position and compiled transformer its generator
	 * was built with, but not the checkpoint: a second destroy is harmless, and a new instance
	 * over the same weights generates the same greedy tokens.
	 */
	@Test(timeout = 600000)
	public void destroyReleasesGeneratorButNotCheckpoint() {
		Qwen3Config config = config();
		Integer[] prompt = { 3, 41 };

		try (StateDictionary weights = Qwen3InferenceProfileTest.createRandomWeights(config, 7L, false)) {
			Qwen3 first = new Qwen3(config, weights, Qwen3Tokenizer.createTestTokenizer());
			PackedCollection position = first.getPosition();
			int[] before;
			try {
				before = greedyTokens(first, prompt, STEPS);
			} finally {
				first.destroy();
			}

			Assert.assertTrue("generator position was not released", position.isDestroyed());
			Assert.assertFalse("checkpoint weights were released",
					weights.get("model.embed_tokens.weight").isDestroyed());
			first.destroy();

			try (Qwen3 second = new Qwen3(config, weights, Qwen3Tokenizer.createTestTokenizer())) {
				Assert.assertArrayEquals(before, greedyTokens(second, prompt, STEPS));
				Assert.assertEquals(STEPS, second.getAutoregressiveModel().getCurrentStep());
			}
		}
	}

	/** The settings a model file reads hold every dimension of the configuration and its RoPE base. */
	@Test(timeout = 60000)
	public void settingsHoldConfiguration() {
		Map<String, Object> settings = config().toPdslSettings();
		Assert.assertEquals(64, settings.get("dim"));
		Assert.assertEquals(192, settings.get("hidden_dim"));
		Assert.assertEquals(2, settings.get("layers"));
		Assert.assertEquals(4, settings.get("heads"));
		Assert.assertEquals(2, settings.get("kv_heads"));
		Assert.assertEquals(100, settings.get("vocab_size"));
		Assert.assertEquals(16, settings.get("seq_len"));
		Assert.assertEquals(1000000.0, (Double) settings.get("rope_theta"), 0.0);
		Assert.assertEquals(8, settings.size());
	}

	/**
	 * Builds a synthetic checkpoint, runs it through {@link Qwen3} (the PDSL model file) and
	 * through the Java layer assembly, and compares the logits at every step.
	 *
	 * @param qwen2 whether the checkpoint is Qwen2-style rather than Qwen3-style
	 */
	private void assertMatchesLayerAssembly(boolean qwen2) {
		Qwen3Config config = config();
		StateDictionary weights = Qwen3InferenceProfileTest.createRandomWeights(config, 7L, qwen2);

		Qwen3 fromFile = new Qwen3(config, weights, Qwen3Tokenizer.createTestTokenizer());
		PackedCollection referencePosition = new PackedCollection(1);
		CompiledModel reference = layerAssembly(config, weights, referencePosition);

		PackedCollection embeddings = weights.get("model.embed_tokens.weight");
		int[] tokens = { 3, 41, 17, 88 };

		for (int step = 0; step < STEPS; step++) {
			setPosition(fromFile.getPosition(), step);
			setPosition(referencePosition, step);

			PackedCollection input = new PackedCollection(shape(1, config.dim));
			a(cp(input), cp(embeddings.range(shape(config.dim), tokens[step] * config.dim))).get().run();

			double[] expected = reference.forward(input).toArray();
			double[] actual = fromFile.getCompiledModel().forward(input).toArray();

			Assert.assertEquals(config.vocabSize, actual.length);
			for (int i = 0; i < expected.length; i++) {
				Assert.assertEquals("logit " + i + " at step " + step, expected[i], actual[i], 1e-5);
			}
		}

		reference.destroy();
		fromFile.getCompiledModel().destroy();
	}

	/**
	 * Runs the generator of a {@link Qwen3} greedily from the start of a sequence.
	 *
	 * @param qwen   the model
	 * @param prompt the prompt tokens, the first of which starts the sequence
	 * @param steps  the number of steps to run
	 * @return the token returned by each step
	 */
	private static int[] greedyTokens(Qwen3 qwen, Integer[] prompt, int steps) {
		AutoregressiveModel<Integer> generator = qwen.getAutoregressiveModel();
		generator.setTemperature(0.0);
		generator.reset();
		generator.setCurrentToken(prompt[0]);
		generator.setPrompt(prompt, prompt.length);

		int[] tokens = new int[steps];
		for (int i = 0; i < steps; i++) {
			tokens[i] = generator.next();
		}

		return tokens;
	}

	/**
	 * Assembles and compiles the model the way {@code Qwen3} did before its structure moved to
	 * a PDSL file: one {@link AttentionFeatures#transformer} block per layer, with biases or
	 * QK-norm weights passed as {@code null} when the checkpoint has none, then the final
	 * RMSNorm and the output projection through the embedding table.
	 */
	private CompiledModel layerAssembly(Qwen3Config config, StateDictionary weights,
										PackedCollection position) {
		Model model = new Model(shape(1, config.dim));
		CollectionProducer freqCis = RotationFeatures.computeRopeFreqs(
				config.ropeTheta, config.headSize, config.seqLen);

		for (int i = 0; i < config.layerCount; i++) {
			String prefix = "model.layers." + i;
			model.add(transformer(config.headCount, config.kvHeadCount,
					weights.get(prefix + ".input_layernorm.weight"),
					weights.get(prefix + ".self_attn.k_proj.weight"),
					weights.get(prefix + ".self_attn.v_proj.weight"),
					weights.get(prefix + ".self_attn.q_proj.weight"),
					weights.get(prefix + ".self_attn.o_proj.weight"),
					weights.get(prefix + ".self_attn.k_proj.bias"),
					weights.get(prefix + ".self_attn.v_proj.bias"),
					weights.get(prefix + ".self_attn.q_proj.bias"),
					weights.get(prefix + ".self_attn.q_norm.weight"),
					weights.get(prefix + ".self_attn.k_norm.weight"),
					freqCis,
					weights.get(prefix + ".post_attention_layernorm.weight"),
					weights.get(prefix + ".mlp.gate_proj.weight"),
					weights.get(prefix + ".mlp.down_proj.weight"),
					weights.get(prefix + ".mlp.up_proj.weight"),
					p(position), 1e-6));
		}

		model.add(rmsnorm(shape(1, config.dim), weights.get("model.norm.weight"), 1e-6));
		model.add(dense(weights.get("model.embed_tokens.weight")));
		return model.compile(false);
	}

	/** Sets a single-element position collection to {@code step}. */
	private void setPosition(PackedCollection position, int step) {
		a(cp(position), c((double) step)).get().run();
	}
}
