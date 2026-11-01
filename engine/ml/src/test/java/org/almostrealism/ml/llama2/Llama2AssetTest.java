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

package org.almostrealism.ml.llama2;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.RotationFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pins the whole-model Llama 2 forward pass described by {@code /pdsl/llama2.pdsl}: a stack of
 * pre-norm transformer layers, a final RMSNorm and a vocabulary projection, one token per forward
 * pass.
 *
 * <p>The model used to be assembled in Java by {@code Llama2.model()}, which built a
 * {@link Model} by looping over the layers — adding the {@link AttentionFeatures#transformer}
 * block once per layer over the layer's {@code range(...)} view of each flat checkpoint tensor —
 * and then adding {@code rmsnorm(shape(1, dim), rmsFinalWeight)} and {@code dense(wcls)}. Two
 * independent pins hold the asset to that behavior:</p>
 * <ul>
 *   <li>{@link #javaAssembly} rebuilds that assembly here, statement for statement, over the same
 *       synthetic flat weights, and {@link #assetMatchesJavaAssembly} requires the asset model to
 *       agree with it. The two paths share only the per-layer {@code transformer} layer (which
 *       this round does not touch); the model-level assembly differs — a Java {@code Model.add}
 *       loop with host {@code range(...)} slicing versus the asset's PDSL {@code for} loop with
 *       {@code range(...)} stages — so agreement exercises exactly the migrated surface.</li>
 *   <li>The {@link #GOLDEN} table was captured from {@link #javaAssembly} (the Java assembly as it
 *       stood in {@code Llama2.model()} on master, reproduced here over the same seeded weights)
 *       by running it through this test before the asset existed.
 *       {@link #assetMatchesMasterGoldenValues} requires the asset to reproduce it.</li>
 * </ul>
 *
 * <p>The configuration is multi-layer ({@link #LAYERS} layers) so the asset's {@code for} loop and
 * per-layer {@code range(...)} slicing are exercised, and runs {@link #STEPS} forward passes
 * through one compiled model so the key/value caches written by earlier passes are read by later
 * ones. The transformer layer and its stages are pinned on their own by {@code TransformerAssetTest},
 * {@code AttentionAssetTest} and {@code FeedForwardAssetTest}.</p>
 */
public class Llama2AssetTest extends TestSuiteBase implements AttentionFeatures {

	/** Model dimension. */
	private static final int DIM = 8;

	/** Hidden dimension of the feed-forward network. */
	private static final int HIDDEN_DIM = 16;

	/** Number of attention heads (multi-head: key/value heads equal query heads). */
	private static final int HEADS = 2;

	/** Elements per head. */
	private static final int HEAD_SIZE = DIM / HEADS;

	/** Number of transformer layers. */
	private static final int LAYERS = 2;

	/** Vocabulary size (rows of the classifier projection). */
	private static final int VOCAB = 8;

	/** Rows of the key/value caches and of the rotary table. */
	private static final int SEQ_LEN = 6;

	/** Forward passes per model. */
	private static final int STEPS = 3;

	/** RoPE base frequency of the rotary table. */
	private static final double THETA = 10000.0;

	/** Largest element-wise difference accepted between two outputs. */
	private static final double TOLERANCE = 1e-5;

	/** Classpath location of the Llama 2 model asset. */
	private static final String ASSET = "/pdsl/llama2.pdsl";

	/**
	 * Vocabulary logits of each forward pass, captured from {@link #javaAssembly} — the Java
	 * assembly {@code Llama2.model()} built on master, reproduced over the {@link Weights} drawn
	 * with seed {@code 2026L} — before the asset existed.
	 */
	private static final double[][] GOLDEN = {
		{ 0.2281462, -0.9921128, 0.1452564, -0.6388054, 0.2705344, 0.9708218, 0.2803654, 0.0199100 },
		{ 1.3557806, -0.4429169, -1.0352144, -1.5608280, -0.4124845, 0.5754883, 1.4638606, -0.6192539 },
		{ -0.2417064, 0.5590394, 0.2207054, 0.6527035, 0.6489181, -0.5889317, -0.4683206, 0.4621878 }
	};

	/**
	 * The asset model reproduces, at every position, the logits of the Java assembly it replaced,
	 * over identical synthetic weights.
	 */
	@Test(timeout = 600000)
	public void assetMatchesJavaAssembly() {
		Weights w = new Weights(2026L);
		double[][] java = run(javaAssembly(w), w);
		double[][] asset = run(assetModel(w), w);
		for (int step = 0; step < STEPS; step++) {
			assertClose("asset vs Java assembly, position " + step, java[step], asset[step]);
		}
	}

	/** The asset model reproduces the values the Java assembly produced on master. */
	@Test(timeout = 600000)
	public void assetMatchesMasterGoldenValues() {
		Weights w = new Weights(2026L);
		double[][] asset = run(assetModel(w), w);
		for (int step = 0; step < STEPS; step++) {
			assertClose("golden, position " + step, GOLDEN[step], asset[step]);
		}
	}

	/**
	 * The model as {@code Llama2.model()} assembled it in Java before the asset existed: a
	 * {@code (1, dim)} {@link Model} holding one {@link AttentionFeatures#transformer} block per
	 * layer (each over the layer's {@code range(...)} view of the flat weights), a final RMSNorm
	 * and the classifier projection.
	 *
	 * @param w the synthetic weights
	 * @return the assembled model
	 */
	private Model javaAssembly(Weights w) {
		Model model = new Model(shape(1, DIM));
		for (int i = 0; i < LAYERS; i++) {
			model.add(transformer(HEADS,
					w.rmsAtt.range(shape(DIM), i * DIM),
					w.wk.range(shape(DIM, DIM), i * DIM * DIM),
					w.wv.range(shape(DIM, DIM), i * DIM * DIM),
					w.wq.range(shape(DIM, DIM), i * DIM * DIM),
					w.wo.range(shape(DIM, DIM), i * DIM * DIM),
					cp(w.freqCis),
					w.rmsFfn.range(shape(DIM), i * DIM),
					w.w1.range(shape(HIDDEN_DIM, DIM), i * HIDDEN_DIM * DIM),
					w.w2.range(shape(DIM, HIDDEN_DIM), i * DIM * HIDDEN_DIM),
					w.w3.range(shape(HIDDEN_DIM, DIM), i * HIDDEN_DIM * DIM),
					p(w.position)));
		}
		model.add(rmsnorm(shape(1, DIM), w.rmsFinal));
		model.add(dense(w.wcls));
		return model;
	}

	/**
	 * The model built from {@code /pdsl/llama2.pdsl} over the same flat weights.
	 *
	 * @param w the synthetic weights
	 * @return the asset model
	 */
	private Model assetModel(Weights w) {
		Map<String, Object> settings = new HashMap<>();
		settings.put("dim", DIM);
		settings.put("hidden_dim", HIDDEN_DIM);
		settings.put("layers", LAYERS);
		settings.put("heads", HEADS);
		settings.put("kv_heads", HEADS);
		settings.put("vocab_size", VOCAB);
		settings.put("seq_len", SEQ_LEN);

		Map<String, Object> args = new HashMap<>();
		args.put("settings", settings);
		args.put("rms_att", w.rmsAtt);
		args.put("wq", w.wq);
		args.put("wk", w.wk);
		args.put("wv", w.wv);
		args.put("wo", w.wo);
		args.put("rms_ffn", w.rmsFfn);
		args.put("w1", w.w1);
		args.put("w2", w.w2);
		args.put("w3", w.w3);
		args.put("rms_final", w.rmsFinal);
		args.put("freq_cis", w.freqCis);
		args.put("wcls", w.wcls);
		args.put("position", p(w.position));

		PdslLoader loader = new PdslLoader();
		return loader.buildModel(loader.parseResource(ASSET), "llama2", shape(1, DIM), args);
	}

	/**
	 * Compiles {@code model} and runs {@link #STEPS} forward passes, advancing the position before
	 * each, returning the logits of every pass.
	 *
	 * @param model the model
	 * @param w     the synthetic weights (holds the shared position and the per-step inputs)
	 * @return the logits of each pass
	 */
	private double[][] run(Model model, Weights w) {
		CompiledModel compiled = model.compile(false);
		double[][] outputs = new double[STEPS][];
		for (int step = 0; step < STEPS; step++) {
			w.position.fill(step);
			outputs[step] = compiled.forward(w.input(step)).toArray();
			log("position " + step + " = " + arrayLiteral(outputs[step]));
		}
		return outputs;
	}

	/** Requires {@code actual} to hold {@code expected} element for element, within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + ": length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + ": element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/** Renders {@code values} as the Java array initializer the golden table is written in. */
	private static String arrayLiteral(double[] values) {
		StringBuilder literal = new StringBuilder("{");
		for (int i = 0; i < values.length; i++) {
			literal.append(i == 0 ? " " : ", ").append(String.format("%.7f", values[i]));
		}
		return literal.append(" },").toString();
	}

	/**
	 * The seeded flat weights of the synthetic checkpoint, in the layout {@link Llama2Weights}
	 * reads them: each attention and feed-forward tensor has a leading layer axis, and the rotary
	 * table and classifier are shared across layers. Tensors are drawn on the device from a
	 * {@link Random} with a fixed seed, in declaration order, so every build binds the same values.
	 */
	private final class Weights {
		/** The cache row written and the causal limit of the current pass. */
		private final PackedCollection position = new PackedCollection(shape(1));
		/** Draws every random tensor, in declaration order. */
		private final Random random;
		/** Per-layer pre-attention RMSNorm scales {@code [LAYERS, DIM]}. */
		private final PackedCollection rmsAtt;
		/** Per-layer query projections {@code [LAYERS, DIM, DIM]}. */
		private final PackedCollection wq;
		/** Per-layer key projections {@code [LAYERS, DIM, DIM]}. */
		private final PackedCollection wk;
		/** Per-layer value projections {@code [LAYERS, DIM, DIM]}. */
		private final PackedCollection wv;
		/** Per-layer attention output projections {@code [LAYERS, DIM, DIM]}. */
		private final PackedCollection wo;
		/** Per-layer pre-feed-forward RMSNorm scales {@code [LAYERS, DIM]}. */
		private final PackedCollection rmsFfn;
		/** Per-layer SwiGLU gate projections {@code [LAYERS, HIDDEN_DIM, DIM]}. */
		private final PackedCollection w1;
		/** Per-layer SwiGLU down projections {@code [LAYERS, DIM, HIDDEN_DIM]}. */
		private final PackedCollection w2;
		/** Per-layer SwiGLU up projections {@code [LAYERS, HIDDEN_DIM, DIM]}. */
		private final PackedCollection w3;
		/** Final RMSNorm scale {@code [DIM]}. */
		private final PackedCollection rmsFinal;
		/** Rotary table shared by every layer, {@code [SEQ_LEN, HEAD_SIZE / 2, 2]}. */
		private final PackedCollection freqCis;
		/** Classifier / vocabulary projection {@code [VOCAB, DIM]}. */
		private final PackedCollection wcls;

		/**
		 * Draws the tensors of the synthetic checkpoint.
		 *
		 * @param seed seed of the random tensors
		 */
		private Weights(long seed) {
			this.random = new Random(seed);
			this.rmsAtt = draw(0.6, 1.4, LAYERS, DIM);
			this.wq = draw(-0.4, 0.4, LAYERS, DIM, DIM);
			this.wk = draw(-0.4, 0.4, LAYERS, DIM, DIM);
			this.wv = draw(-0.4, 0.4, LAYERS, DIM, DIM);
			this.wo = draw(-0.4, 0.4, LAYERS, DIM, DIM);
			this.rmsFfn = draw(0.6, 1.4, LAYERS, DIM);
			this.w1 = draw(-0.4, 0.4, LAYERS, HIDDEN_DIM, DIM);
			this.w2 = draw(-0.4, 0.4, LAYERS, DIM, HIDDEN_DIM);
			this.w3 = draw(-0.4, 0.4, LAYERS, HIDDEN_DIM, DIM);
			this.rmsFinal = draw(0.6, 1.4, DIM);
			this.freqCis = RotationFeatures.computeRopeFreqs(THETA, HEAD_SIZE, SEQ_LEN).evaluate();
			this.wcls = draw(-0.4, 0.4, VOCAB, DIM);
		}

		/**
		 * The token embedding of pass {@code step}, produced on the device: element {@code i} is
		 * {@code (1 + i / 4) cos(0.9 i + 0.3 + 1.7 step)}, so later elements carry larger values.
		 *
		 * @param step the pass index
		 * @return the input vector {@code [1, DIM]}
		 */
		private PackedCollection input(int step) {
			CollectionProducer index = integers(0, DIM);
			return cos(index.multiply(0.9).add(0.3 + 1.7 * step))
					.multiply(index.multiply(0.25).add(1.0))
					.reshape(shape(1, DIM)).evaluate();
		}

		/** A tensor of the given dimensions drawn uniformly from {@code [min, max)}. */
		private PackedCollection draw(double min, double max, int... dims) {
			return rand(shape(dims), random).multiply(max - min).add(min).reshape(shape(dims)).evaluate();
		}
	}
}
