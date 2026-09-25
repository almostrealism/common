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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.midi.GRUDecoder;
import org.almostrealism.ml.midi.MoonbeamConfig;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pins the behaviour of the Moonbeam GRU decoder built by {@link GRUDecoder} from the
 * {@code midi/gru_decoder.pdsl} asset against references that do not depend on the asset:
 * <ul>
 *   <li>{@link HostReference}, the decoder's arithmetic (summary projection, every GRU layer's
 *       reset gate, update gate, candidate state and update, the optional fc_out projection and
 *       the vocabulary head) in plain double precision on the host, with its own hidden state
 *       carried from step to step;</li>
 *   <li>the Java assembly that preceded the asset ({@code GRUDecoder.ensureCompiled} on master,
 *       one fused producer layer per GRU layer threading the hidden states through the step
 *       model's input and output), reconstructed by {@link JavaAssembly} from the same
 *       producer arithmetic ({@link LayerFeatures#gruStep}) that it used; and</li>
 *   <li>tokens captured from that Java assembly on master, before the migration, for the seeds
 *       and dimensions used here.</li>
 * </ul>
 *
 * <p>Every configuration uses two GRU layers, so the hidden state of one layer feeding the next
 * is exercised, and a transformer hidden size different from the decoder hidden size, so the
 * summary projection is not square. Each decode runs all seven steps of a note, so the hidden
 * state that persists between the forward passes of a note is exercised, and decoding a second
 * note on the same decoder checks that the hidden state starts again from that note's summary
 * projection.</p>
 */
public class GruDecoderAssetTest extends TestSuiteBase implements LayerFeatures {

	/** Absolute tolerance between the single-precision decoder and the double-precision references. */
	private static final double TOLERANCE = 1e-4;

	/** Number of GRU layers of every configuration. */
	private static final int LAYERS = 2;

	/** Transformer hidden size, the summary projection's input. */
	private static final int TRANSFORMER_SIZE = 12;

	/** Decoder hidden size. */
	private static final int HIDDEN_SIZE = 8;

	/** Vocabulary entries per attribute: the flat decode vocabulary holds 1 + 6 * 3 = 19 tokens. */
	private static final int PER_ATTRIBUTE = 3;

	/** Seed of every configuration's weights. */
	private static final long SEED = 11L;

	/**
	 * Greedy tokens of the Java assembly ({@code GRUDecoder} on master at commit {@code cbfb678f3})
	 * for the configuration without fc_out, for the first and the second transformer hidden state,
	 * with the tensors {@link Weights} draws from {@link #SEED}.
	 */
	private static final int[][] GREEDY_GOLDEN = {
			{ 2, 2, 6, 6, 6, 6, 6 },
			{ 12, 15, 15, 15, 15, 6, 6 } };

	/** Greedy tokens of the Java assembly at commit {@code cbfb678f3} for the configuration with fc_out. */
	private static final int[][] FC_OUT_GREEDY_GOLDEN = {
			{ 7, 7, 7, 7, 7, 7, 7 },
			{ 1, 1, 6, 7, 7, 7, 7 } };

	/**
	 * Tokens the Java assembly at commit {@code cbfb678f3} sampled for the first hidden state at
	 * temperature 0.8 and top-p 0.9 with {@code new Random(5)}, without and with fc_out.
	 */
	private static final int[][] SAMPLED_GOLDEN = {
			{ 15, 2, 8, 8, 8, 13, 5 },
			{ 10, 1, 7, 7, 7, 10, 6 } };

	/** Location of the Moonbeam 309M checkpoint weights, when they are present. */
	private static final String REAL_WEIGHTS = "/Users/Shared/models/moonbeam-weights-protobuf";

	/**
	 * Tokens the Java assembly at commit {@code cbfb678f3} decoded from a transformer hidden state
	 * of all 0.1 with the Moonbeam 309M checkpoint, built through the constructor with every layer
	 * taking inputs of the decoder hidden size and the checkpoint's fc_out projection applied.
	 */
	private static final int[] REAL_CHECKPOINT_GOLDEN = { 2077, 2056, 2208, 2208, 2241, 2339, 2339 };

	/**
	 * The decoder reproduces the greedy and sampled tokens the Java assembly produced on master,
	 * for two notes decoded one after the other on the same decoder, with and without fc_out.
	 */
	@Test(timeout = 300000)
	public void decodeMatchesMasterGoldenTokens() {
		for (boolean fcOut : new boolean[] { false, true }) {
			Weights w = new Weights(fcOut);
			GRUDecoder decoder = w.decoder();
			int[][] greedy = fcOut ? FC_OUT_GREEDY_GOLDEN : GREEDY_GOLDEN;
			Assert.assertArrayEquals("greedy note 0 fcOut=" + fcOut, greedy[0],
					decoder.decode(w.transformerHidden(0)));
			Assert.assertArrayEquals("greedy note 1 fcOut=" + fcOut, greedy[1],
					decoder.decode(w.transformerHidden(1)));
			Assert.assertArrayEquals("sampled fcOut=" + fcOut, SAMPLED_GOLDEN[fcOut ? 1 : 0],
					decoder.decode(w.transformerHidden(0), 0.8, 0.9, new Random(5)));
		}
	}

	/**
	 * The decoder's greedy tokens are those of {@link HostReference}, for two notes decoded one
	 * after the other on the same decoder, with and without fc_out.
	 */
	@Test(timeout = 300000)
	public void decodeMatchesHostReference() {
		for (boolean fcOut : new boolean[] { false, true }) {
			Weights w = new Weights(fcOut);
			GRUDecoder decoder = w.decoder();
			HostReference reference = new HostReference(w);
			for (int note = 0; note < 2; note++) {
				PackedCollection hidden = w.transformerHidden(note);
				Assert.assertArrayEquals("note " + note + " fcOut=" + fcOut,
						reference.decode(hidden.toArray()), decoder.decode(hidden));
			}
		}
	}

	/**
	 * The reconstruction of the Java assembly agrees with {@link HostReference} over three decode
	 * steps, so it is a faithful stand-in for the assembly the asset replaced.
	 */
	@Test(timeout = 300000)
	public void javaAssemblyMatchesHostReference() {
		for (boolean fcOut : new boolean[] { false, true }) {
			Weights w = new Weights(fcOut);
			HostReference reference = new HostReference(w);
			JavaAssembly assembly = new JavaAssembly(w);

			PackedCollection hidden = w.transformerHidden(0);
			reference.start(hidden.toArray());
			assembly.start(hidden);
			for (int step = 0; step < 3; step++) {
				int token = 4 * step;
				assertClose("step " + step + " fcOut=" + fcOut,
						reference.step(w.embeddingRow(token)), assembly.step(w.embedding(token)));
			}
		}
	}

	/**
	 * The asset's start and step models reproduce {@link HostReference}'s logits step by step, for
	 * three steps of two notes decoded one after the other, with and without fc_out. Feeding both
	 * the same tokens keeps the comparison on the logits rather than on the choice of token, and
	 * the second note checks that the start model resets the hidden state the first note advanced.
	 */
	@Test(timeout = 300000)
	public void stepMatchesHostReference() {
		for (boolean fcOut : new boolean[] { false, true }) {
			Weights w = new Weights(fcOut);
			GRUDecoder decoder = w.decoder();
			HostReference reference = new HostReference(w);
			for (int note = 0; note < 2; note++) {
				PackedCollection hidden = w.transformerHidden(note);
				decoder.start(hidden);
				reference.start(hidden.toArray());
				for (int step = 0; step < 3; step++) {
					int token = 3 * step + note;
					assertClose("note " + note + " step " + step + " fcOut=" + fcOut,
							reference.step(w.embeddingRow(token)),
							decoder.step(w.embedding(token)).toArray());
				}
			}
		}
	}

	/**
	 * The asset and the Java assembly it replaced produce the same logits for the same weights and
	 * inputs over three decode steps, with and without fc_out: the decode step is built both ways
	 * and the outputs are compared directly.
	 */
	@Test(timeout = 300000)
	public void stepMatchesJavaAssembly() {
		for (boolean fcOut : new boolean[] { false, true }) {
			Weights w = new Weights(fcOut);
			GRUDecoder decoder = w.decoder();
			JavaAssembly assembly = new JavaAssembly(w);

			PackedCollection hidden = w.transformerHidden(1);
			decoder.start(hidden);
			assembly.start(hidden);
			for (int step = 0; step < 3; step++) {
				int token = 5 * step + 1;
				assertClose("step " + step + " fcOut=" + fcOut,
						assembly.step(w.embedding(token)), decoder.step(w.embedding(token)).toArray());
			}
		}
	}

	/**
	 * The asset's {@code gru_cell} layer on its own, [x | h] to h', matches
	 * {@link HostReference#cell} for an input size different from the hidden size, so the offsets
	 * the gates read their terms from are checked at a shape where the two sizes cannot be
	 * confused.
	 */
	@Test(timeout = 300000)
	public void cellMatchesHostReference() {
		int inputSize = 6;
		int hiddenSize = 4;
		Random random = new Random(3);
		PackedCollection weightIh = rand(shape(3 * hiddenSize, inputSize), random).add(-0.5).evaluate();
		PackedCollection weightHh = rand(shape(3 * hiddenSize, hiddenSize), random).add(-0.5).evaluate();
		PackedCollection biasIh = rand(shape(3 * hiddenSize), random).add(-0.5).evaluate();
		PackedCollection biasHh = rand(shape(3 * hiddenSize), random).add(-0.5).evaluate();

		Map<String, Object> args = new HashMap<>();
		args.put("weight_ih", weightIh);
		args.put("weight_hh", weightHh);
		args.put("bias_ih", biasIh);
		args.put("bias_hh", biasHh);
		args.put("input_size", inputSize);
		args.put("hidden_size", hiddenSize);

		PdslLoader loader = new PdslLoader();
		TraversalPolicy inputShape = shape(inputSize + hiddenSize);
		Model model = new Model(inputShape);
		model.add(loader.buildLayer(loader.parseResource(GRUDecoder.GRU_DECODER_ASSET),
				"gru_cell", inputShape, args));
		CompiledModel cell = model.compile(false);

		PackedCollection xh = sin(integers(0, inputSize + hiddenSize).multiply(0.9).add(0.3)).evaluate();
		double[] values = xh.toArray();
		double[] x = Arrays.copyOfRange(values, 0, inputSize);
		double[] h = Arrays.copyOfRange(values, inputSize, inputSize + hiddenSize);
		assertClose("cell", HostReference.cell(weightIh, weightHh, biasIh, biasHh, x, h),
				cell.forward(xh).toArray());
	}

	/**
	 * A decoder loaded from checkpoint weights decodes exactly as the Java assembly did on master
	 * for the same tensors given to the constructor directly.
	 *
	 * <p>The checkpoint's transformer hidden size (12) differs from its decoder hidden size (8), as
	 * in every Moonbeam configuration. The loader that {@code MoonbeamMidi} used on master declared
	 * the transformer hidden size as the first GRU layer's input size, although that layer's input
	 * is the decoder embedding of the previous token, so the per-gate views of the layer's
	 * {@code [3 * 8, 8]} input weights overran the tensor ({@code "Range exceeds collection size"})
	 * on the first decode.</p>
	 */
	@Test(timeout = 300000)
	public void checkpointLoadedDecoderMatchesMasterGoldenTokens() {
		for (boolean fcOut : new boolean[] { false, true }) {
			Weights w = new Weights(fcOut);
			GRUDecoder decoder = GRUDecoder.load(w.checkpoint(), w.config);
			int[][] greedy = fcOut ? FC_OUT_GREEDY_GOLDEN : GREEDY_GOLDEN;
			Assert.assertArrayEquals("fcOut=" + fcOut, greedy[0],
					decoder.decode(w.transformerHidden(0)));
		}
	}

	/**
	 * A decoder whose declared layer input size does not match its layer weights is rejected
	 * when it is constructed, instead of failing inside the first decode or computing with
	 * mis-sliced weights.
	 */
	@Test(timeout = 60000)
	public void constructionRejectsInputSizeThatDoesNotMatchWeights() {
		Weights w = new Weights(false);
		int[] inputSizes = { TRANSFORMER_SIZE, HIDDEN_SIZE };
		try {
			new GRUDecoder(w.config, inputSizes, w.weightIh, w.weightHh, w.biasIh, w.biasHh,
					w.summaryWeight, w.summaryBias, w.lmHeadWeight, w.lmHeadBias, w.embedding);
			Assert.fail("A layer 0 input size of " + TRANSFORMER_SIZE
					+ " should be rejected for [24, 8] input weights");
		} catch (IllegalArgumentException expected) {
			log("rejected: " + expected.getMessage());
		}
	}

	/**
	 * The real Moonbeam 309M checkpoint loads, and decodes a transformer hidden state of all 0.1
	 * to the tokens the Java assembly produced on master for the same checkpoint tensors given to
	 * the constructor directly (every layer taking inputs of the decoder hidden size, with the
	 * checkpoint's fc_out projection). On master the loader failed on this checkpoint with
	 * {@code "Range exceeds collection size"} before producing a token: its first GRU layer's
	 * input-hidden weights are {@code (3072, 1024)}, while it was declared to take the
	 * transformer hidden size of 1536. Skipped when the weights are not present.
	 */
	@Test(timeout = 600000)
	@TestDepth(2)
	public void realCheckpointLoadedDecoderMatchesMasterGoldenTokens() throws IOException {
		Assume.assumeTrue("Moonbeam weights not found at " + REAL_WEIGHTS,
				new File(REAL_WEIGHTS).isDirectory());
		MoonbeamConfig config = MoonbeamConfig.checkpoint309M();
		GRUDecoder decoder = GRUDecoder.load(new StateDictionary(REAL_WEIGHTS), config);
		PackedCollection hidden = new PackedCollection(shape(config.hiddenSize)).fill(0.1);
		Assert.assertArrayEquals(REAL_CHECKPOINT_GOLDEN, decoder.decode(hidden));
	}

	/** Compares two vectors element-wise within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], TOLERANCE);
		}
	}

	/**
	 * Deterministic weights for one decoder configuration, drawn from {@link #SEED} so the same
	 * tensors reach the decoder and the references.
	 */
	private final class Weights {
		/** Flat decode vocabulary size. */
		private final int vocabSize;
		/** Model configuration carrying the dimensions. */
		private final MoonbeamConfig config;
		/** Seeded source of every random tensor. */
		private final Random random;
		/** Stacked input-hidden weights per layer, {@code [3 * HIDDEN_SIZE, HIDDEN_SIZE]}. */
		private final PackedCollection[] weightIh;
		/** Stacked hidden-hidden weights per layer, {@code [3 * HIDDEN_SIZE, HIDDEN_SIZE]}. */
		private final PackedCollection[] weightHh;
		/** Stacked input-hidden biases per layer, {@code [3 * HIDDEN_SIZE]}. */
		private final PackedCollection[] biasIh;
		/** Stacked hidden-hidden biases per layer, {@code [3 * HIDDEN_SIZE]}. */
		private final PackedCollection[] biasHh;
		/** Summary projection, {@code [HIDDEN_SIZE, TRANSFORMER_SIZE]}. */
		private final PackedCollection summaryWeight;
		/** Summary projection bias, {@code [HIDDEN_SIZE]}. */
		private final PackedCollection summaryBias;
		/** Projection before the vocabulary head, {@code [HIDDEN_SIZE, HIDDEN_SIZE]}, or {@code null}. */
		private final PackedCollection fcOutWeight;
		/** Bias of the projection before the vocabulary head, or {@code null}. */
		private final PackedCollection fcOutBias;
		/** Vocabulary head, {@code [vocabSize, HIDDEN_SIZE]}. */
		private final PackedCollection lmHeadWeight;
		/** Vocabulary head bias, {@code [vocabSize]}. */
		private final PackedCollection lmHeadBias;
		/** Decoder token embedding, {@code [vocabSize, HIDDEN_SIZE]}. */
		private final PackedCollection embedding;

		/**
		 * Draws the tensors of one configuration, every one uniformly in {@code [-0.5, 0.5)}.
		 *
		 * @param fcOut whether the configuration has an fc_out projection before the vocabulary head
		 */
		Weights(boolean fcOut) {
			this.vocabSize = 1 + MoonbeamConfig.NUM_ATTRIBUTES * PER_ATTRIBUTE;
			this.random = new Random(SEED);

			// Decoder dimensions as above; the transformer dimensions are small valid
			// values the decoder does not read.
			int attributes = MoonbeamConfig.NUM_ATTRIBUTES;
			double[] bases = new double[attributes];
			Arrays.fill(bases, 1000.0);
			int[] vocabSizes = new int[attributes];
			Arrays.fill(vocabSizes, PER_ATTRIBUTE);
			this.config = new MoonbeamConfig(TRANSFORMER_SIZE, TRANSFORMER_SIZE * 4, 2, 2, 2,
					TRANSFORMER_SIZE / 2, HIDDEN_SIZE, LAYERS, vocabSize,
					512, 1e-5, bases, null, vocabSizes, bases, 2);

			this.weightIh = new PackedCollection[LAYERS];
			this.weightHh = new PackedCollection[LAYERS];
			this.biasIh = new PackedCollection[LAYERS];
			this.biasHh = new PackedCollection[LAYERS];
			for (int l = 0; l < LAYERS; l++) {
				weightIh[l] = uniform(3 * HIDDEN_SIZE, HIDDEN_SIZE);
				weightHh[l] = uniform(3 * HIDDEN_SIZE, HIDDEN_SIZE);
				biasIh[l] = uniform(3 * HIDDEN_SIZE);
				biasHh[l] = uniform(3 * HIDDEN_SIZE);
			}

			this.summaryWeight = uniform(HIDDEN_SIZE, TRANSFORMER_SIZE);
			this.summaryBias = uniform(HIDDEN_SIZE);
			this.fcOutWeight = fcOut ? uniform(HIDDEN_SIZE, HIDDEN_SIZE) : null;
			this.fcOutBias = fcOut ? uniform(HIDDEN_SIZE) : null;
			this.lmHeadWeight = uniform(vocabSize, HIDDEN_SIZE);
			this.lmHeadBias = uniform(vocabSize);
			this.embedding = uniform(vocabSize, HIDDEN_SIZE);
		}

		/** Builds the decoder under test from these tensors through its constructor. */
		GRUDecoder decoder() {
			int[] inputSizes = new int[LAYERS];
			Arrays.fill(inputSizes, HIDDEN_SIZE);
			if (fcOutWeight == null) {
				return new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
						summaryWeight, summaryBias, lmHeadWeight, lmHeadBias, embedding);
			}

			return new GRUDecoder(config, inputSizes, weightIh, weightHh, biasIh, biasHh,
					summaryWeight, summaryBias, fcOutWeight, fcOutBias,
					lmHeadWeight, lmHeadBias, embedding);
		}

		/** These tensors under the key names of a Moonbeam checkpoint. */
		StateDictionary checkpoint() {
			Map<String, PackedCollection> weights = new HashMap<>();
			for (int l = 0; l < LAYERS; l++) {
				weights.put("decoder.weight_ih_l" + l, weightIh[l]);
				weights.put("decoder.weight_hh_l" + l, weightHh[l]);
				weights.put("decoder.bias_ih_l" + l, biasIh[l]);
				weights.put("decoder.bias_hh_l" + l, biasHh[l]);
			}
			weights.put("summary_projection.weight", summaryWeight);
			weights.put("summary_projection.bias", summaryBias);
			if (fcOutWeight != null) {
				weights.put("decoder.fc_out.weight", fcOutWeight);
				weights.put("decoder.fc_out.bias", fcOutBias);
			}
			weights.put("lm_head.weight", lmHeadWeight);
			weights.put("lm_head.bias", lmHeadBias);
			weights.put("decoder_embedding.weight", embedding);
			return new StateDictionary(weights);
		}

		/**
		 * A transformer hidden state produced on the device: element {@code i} of state
		 * {@code k} is {@code sin(1 + 0.7 i + 1.3 k)}, deterministic with mixed signs.
		 *
		 * @param k which of the states to produce
		 * @return the {@code [TRANSFORMER_SIZE]} hidden state
		 */
		PackedCollection transformerHidden(int k) {
			CollectionProducer index = integers(0, TRANSFORMER_SIZE);
			return sin(index.multiply(0.7).add(1.0 + 1.3 * k)).evaluate();
		}

		/** The decoder embedding of {@code token}, {@code [HIDDEN_SIZE]}, on the device. */
		PackedCollection embedding(int token) {
			return cp(embedding).subset(shape(1, HIDDEN_SIZE), token, 0)
					.reshape(shape(HIDDEN_SIZE)).evaluate();
		}

		/** The decoder embedding of {@code token} read back to the host. */
		double[] embeddingRow(int token) {
			return embedding(token).toArray();
		}

		/**
		 * Draws a tensor of the given shape from the seeded source, uniformly in
		 * {@code [-0.5, 0.5)}, produced on the device.
		 */
		private PackedCollection uniform(int... dims) {
			TraversalPolicy shape = shape(dims);
			return rand(shape, random).add(-0.5).reshape(shape).evaluate();
		}
	}

	/**
	 * The decode step as the Java assembly that preceded the asset built it: the step model's
	 * input is the state vector {@code [x | h_0 .. h_L-1]}; layer {@code l} computes
	 * {@code h_l'} from slot 0 and slot {@code l + 1} and writes it into both, so the next layer
	 * reads it as its input, and the head emits {@code [h_0' .. h_L-1' | logits]} from slot 0.
	 * The per-layer arithmetic is {@link LayerFeatures#gruStep}, the same producer expressions
	 * the assembly's layers were built from.
	 */
	private final class JavaAssembly {
		/** The configuration being reproduced. */
		private final Weights w;
		/** The compiled step model. */
		private final CompiledModel model;
		/** The hidden state of every layer, threaded from one step's output to the next step's input. */
		private final PackedCollection[] hidden;

		/**
		 * Builds and compiles the step model for {@code w}.
		 *
		 * @param w the configuration
		 */
		JavaAssembly(Weights w) {
			this.w = w;
			this.hidden = new PackedCollection[LAYERS];

			int stateSize = (1 + LAYERS) * HIDDEN_SIZE;
			int outputSize = LAYERS * HIDDEN_SIZE + w.vocabSize;
			Model step = new Model(shape(stateSize));
			for (int l = 0; l < LAYERS; l++) {
				int layer = l;
				step.add(layer("gru_layer_" + l, shape(stateSize), shape(stateSize), input -> {
					CollectionProducer x = c(input).subset(shape(HIDDEN_SIZE), 0).reshape(shape(HIDDEN_SIZE));
					CollectionProducer h = c(input).subset(shape(HIDDEN_SIZE), (layer + 1) * HIDDEN_SIZE)
							.reshape(shape(HIDDEN_SIZE));
					CollectionProducer hNew = gruStep(w.weightIh[layer], w.weightHh[layer],
							w.biasIh[layer], w.biasHh[layer], x, h, HIDDEN_SIZE, HIDDEN_SIZE);

					CollectionProducer[] parts = new CollectionProducer[1 + LAYERS];
					parts[0] = hNew;
					for (int s = 1; s <= LAYERS; s++) {
						parts[s] = s == layer + 1 ? hNew
								: c(input).subset(shape(HIDDEN_SIZE), s * HIDDEN_SIZE).reshape(shape(HIDDEN_SIZE));
					}
					return concat(parts).reshape(shape(stateSize));
				}));
			}

			step.add(layer("lm_head", shape(stateSize), shape(outputSize), input -> {
				CollectionProducer last = c(input).subset(shape(HIDDEN_SIZE), 0).reshape(shape(HIDDEN_SIZE));
				if (w.fcOutWeight != null) {
					last = add(matmul(cp(w.fcOutWeight), last), cp(w.fcOutBias)).reshape(shape(HIDDEN_SIZE));
				}
				CollectionProducer logits = add(matmul(cp(w.lmHeadWeight), last), cp(w.lmHeadBias))
						.reshape(shape(w.vocabSize));

				CollectionProducer[] parts = new CollectionProducer[LAYERS + 1];
				for (int l = 0; l < LAYERS; l++) {
					parts[l] = c(input).subset(shape(HIDDEN_SIZE), (l + 1) * HIDDEN_SIZE).reshape(shape(HIDDEN_SIZE));
				}
				parts[LAYERS] = logits;
				return concat(parts).reshape(shape(outputSize));
			}));
			this.model = step.compile(false);
		}

		/** Start of a note: every layer's hidden state becomes the summary projection. */
		void start(PackedCollection transformerHidden) {
			PackedCollection projection = add(matmul(cp(w.summaryWeight), cp(transformerHidden)),
					cp(w.summaryBias)).evaluate();
			Arrays.fill(hidden, projection);
		}

		/**
		 * One decode step for the input embedding {@code x}.
		 *
		 * @param x the embedding of the previous token
		 * @return the logits
		 */
		double[] step(PackedCollection x) {
			CollectionProducer[] parts = new CollectionProducer[1 + LAYERS];
			parts[0] = cp(x).reshape(shape(HIDDEN_SIZE));
			for (int l = 0; l < LAYERS; l++) {
				parts[l + 1] = cp(hidden[l]).reshape(shape(HIDDEN_SIZE));
			}
			PackedCollection output = model.forward(
					concat(parts).reshape(shape((1 + LAYERS) * HIDDEN_SIZE)).evaluate());
			for (int l = 0; l < LAYERS; l++) {
				hidden[l] = cp(output).subset(shape(HIDDEN_SIZE), l * HIDDEN_SIZE).evaluate();
			}
			return cp(output).subset(shape(w.vocabSize), LAYERS * HIDDEN_SIZE).evaluate().toArray();
		}
	}

	/**
	 * The GRU decoder's arithmetic in plain double precision: an oracle for what the start pass
	 * and every decode step must produce, independent of the framework layers, of the asset and
	 * of the Java assembly that preceded it. It keeps its own hidden state from step to step.
	 */
	private static final class HostReference {
		/** The configuration being reproduced. */
		private final Weights w;
		/** Every layer's hidden state. */
		private final double[][] hidden;

		/**
		 * Creates an oracle for {@code w}.
		 *
		 * @param w the configuration
		 */
		HostReference(Weights w) {
			this.w = w;
			this.hidden = new double[LAYERS][];
		}

		/** Start of a note: every layer's hidden state becomes the summary projection. */
		void start(double[] transformerHidden) {
			double[] projection = affine(w.summaryWeight, w.summaryBias, transformerHidden);
			for (int l = 0; l < LAYERS; l++) {
				hidden[l] = projection.clone();
			}
		}

		/**
		 * One decode step: every layer in turn advances its hidden state from its input, the
		 * first layer's input being {@code x} and every later layer's the previous layer's new
		 * hidden state; the last one is projected to the logits.
		 *
		 * @param x the embedding of the previous token
		 * @return the logits
		 */
		double[] step(double[] x) {
			double[] input = x;
			for (int l = 0; l < LAYERS; l++) {
				hidden[l] = cell(w.weightIh[l], w.weightHh[l], w.biasIh[l], w.biasHh[l], input, hidden[l]);
				input = hidden[l];
			}
			if (w.fcOutWeight != null) {
				input = affine(w.fcOutWeight, w.fcOutBias, input);
			}
			return affine(w.lmHeadWeight, w.lmHeadBias, input);
		}

		/**
		 * Greedy decode of one note: the first input is the embedding of token 0 and every later
		 * input the embedding of the previous step's most likely token.
		 *
		 * @param transformerHidden the transformer hidden state of the note
		 * @return the note's tokens
		 */
		int[] decode(double[] transformerHidden) {
			start(transformerHidden);
			int[] tokens = new int[GRUDecoder.TOKENS_PER_NOTE];
			double[] x = w.embeddingRow(0);
			for (int s = 0; s < tokens.length; s++) {
				double[] logits = step(x);
				int best = 0;
				for (int i = 1; i < logits.length; i++) {
					if (logits[i] > logits[best]) best = i;
				}
				tokens[s] = best;
				x = w.embeddingRow(best);
			}
			return tokens;
		}

		/**
		 * One GRU cell step as {@code torch.nn.GRU} defines it, over weights stacked in reset,
		 * update, candidate order: {@code r = sigmoid(W_ir x + b_ir + W_hr h + b_hr)},
		 * {@code z = sigmoid(W_iz x + b_iz + W_hz h + b_hz)},
		 * {@code n = tanh(W_in x + b_in + r (W_hn h + b_hn))} and {@code h' = (1 - z) n + z h}.
		 */
		static double[] cell(PackedCollection weightIh, PackedCollection weightHh,
							 PackedCollection biasIh, PackedCollection biasHh, double[] x, double[] h) {
			double[] gi = affine(weightIh, biasIh, x);
			double[] gh = affine(weightHh, biasHh, h);
			int size = h.length;
			double[] next = new double[size];
			for (int i = 0; i < size; i++) {
				double r = sigmoid(gi[i] + gh[i]);
				double z = sigmoid(gi[size + i] + gh[size + i]);
				double n = Math.tanh(gi[2 * size + i] + r * gh[2 * size + i]);
				next[i] = (1 - z) * n + z * h[i];
			}
			return next;
		}

		/** The logistic function. */
		private static double sigmoid(double x) {
			return 1.0 / (1.0 + Math.exp(-x));
		}

		/** {@code weight @ x + bias} for a {@code [rows, x.length]} weight. */
		private static double[] affine(PackedCollection weight, PackedCollection bias, double[] x) {
			double[] wv = weight.toArray();
			double[] bv = bias.toArray();
			double[] out = new double[bv.length];
			for (int r = 0; r < out.length; r++) {
				double acc = bv[r];
				for (int c = 0; c < x.length; c++) {
					acc += wv[r * x.length + c] * x[c];
				}
				out[r] = acc;
			}
			return out;
		}
	}
}
