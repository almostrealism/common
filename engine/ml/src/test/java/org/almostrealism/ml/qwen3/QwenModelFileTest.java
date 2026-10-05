package org.almostrealism.ml.qwen3;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Checks that the whole-model PDSL files, {@code /pdsl/qwen2.pdsl} and {@code /pdsl/qwen3.pdsl},
 * compute exactly what the per-layer Java assembly they replaced computed. The reference below
 * is that assembly: a Java loop that adds one {@link AttentionFeatures#transformer} block per
 * layer, then the final RMSNorm and the output projection. {@link Qwen3} builds the same model
 * from its PDSL file. Both run the same synthetic checkpoint over several token positions, so
 * the KV caches and the rotary embedding are exercised, and their logits must agree.
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
