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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.layers.ParameterUpdate;
import org.almostrealism.ml.tokenization.ByteTokenizer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.optimize.AdamOptimizer;
import org.almostrealism.optimize.Dataset;
import org.almostrealism.optimize.LossProvider;
import org.almostrealism.optimize.ModelOptimizer;
import org.almostrealism.optimize.NegativeLogLikelihood;
import org.almostrealism.optimize.TrainingResult;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Tests of {@link CausalLanguageModel}: the output shape and loss contract, and an end-to-end
 * training run of a tiny byte-level causal transformer on a fixed subset of the platform's own
 * documentation.
 *
 * <h2>Training configuration</h2>
 * <p>Byte vocabulary 256 ({@link ByteTokenizer}), context 64, embed 64, 4 heads, depth 2,
 * feed-forward width 128 (the plan's first reduction from 256, needed because the per-step
 * gradient memory of the gated feed-forward grows with the square of its width), full rotary embedding with base 10000, no query/key normalization,
 * weights initialized from {@code N(0, 0.02)} (normalization scales one) with seed
 * {@link #SEED}, Adam with betas 0.9 / 0.999 and a learning rate decaying linearly per epoch from
 * {@link #LEARNING_RATE} to {@link #FINAL_LEARNING_RATE}, batch 1.
 * The corpus bytes are split 90/10 into contiguous training and held-out regions before
 * windowing ({@link NextTokenDataset#split}). The training windows are spaced at the
 * {@link NextTokenDataset#spanningStride spanning stride} of the whole run, so that the windows of
 * all epochs together cover the training region once from its start to its end, and the training
 * set {@link NextTokenDataset#setRotating rotates}, so each capped epoch reads the next stretch of
 * the region. Held-out windows are non-overlapping: a fixed subset is scored at every epoch
 * boundary for the progress curve, and every held-out window is scored once after training, which
 * is the number the unigram baseline of exactly those targets is compared with. The run needs a
 * device memory ceiling of at least {@code AR_HARDWARE_MEMORY_SCALE=6}.</p>
 *
 * <p>After training, the reloaded checkpoint generates a greedy continuation of a documentation
 * prompt through a {@link SlidingWindowAutoregressiveModel}, which is logged together with the
 * most probable next bytes after the prompt, and with a continuation of a verbatim training
 * fragment for comparison.</p>
 */
public class CausalLanguageModelTest extends TestSuiteBase implements ModelTestFeatures {
	/** Byte vocabulary size. */
	static final int VOCAB = ByteTokenizer.VOCAB_SIZE;
	/** Positions per window. */
	static final int SEQ_LEN = 64;
	/** Model dimension. */
	static final int DIM = 64;
	/** Attention heads. */
	static final int HEADS = 4;
	/** Transformer blocks. */
	static final int DEPTH = 2;
	/** Feed-forward hidden width (2 x embed). */
	static final int FF_DIM = 128;
	/** Rotary embedding base. */
	static final double ROPE_BASE = 10000.0;
	/** Seed of the weight initialization. */
	private static final long SEED = 20260930L;
	/** Adam learning rate of the first epoch. */
	private static final double LEARNING_RATE = 5e-4;
	/** Adam learning rate of the last epoch; the rate decays linearly in between. */
	private static final double FINAL_LEARNING_RATE = 5e-5;
	/** Stride of the held-out windows (non-overlapping). */
	private static final int HELD_OUT_STRIDE = SEQ_LEN;
	/**
	 * Training windows per epoch; the training set rotates, so each epoch sees new windows, and
	 * the training stride is chosen so that the windows of all epochs together span the whole
	 * training region once ({@link NextTokenDataset#spanningStride}).
	 */
	private static final int TRAIN_WINDOWS = 150;
	/** Maximum held-out windows evaluated at each epoch boundary, for the progress curve only. */
	private static final int HELD_OUT_WINDOWS = 12;
	/** Epochs passed to {@link ModelOptimizer#optimize(int)}. */
	private static final int EPOCHS = 40;
	/** Fraction of the corpus bytes in the training region. */
	private static final double TRAIN_FRACTION = 0.9;
	/** The documentation-style prompt the trained model continues. */
	static final String GENERATION_PROMPT = "The computation graph is compiled to ";
	/**
	 * A verbatim fragment of the first training page, continued alongside
	 * {@link #GENERATION_PROMPT} so that the log shows how generation differs between text the
	 * model trained on and text it did not.
	 */
	private static final String TRAINING_PROMPT = "A Features interface is a Java interface whose methods are all ";
	/** Number of most probable next bytes logged after each prompt. */
	private static final int LOGGED_CANDIDATES = 5;
	/** Number of bytes generated greedily after {@link #GENERATION_PROMPT}. */
	private static final int GENERATED_BYTES = 96;
	/** Relative tolerance on the reloaded held-out loss (weights are saved as FP32). */
	private static final double RELOAD_TOLERANCE = 1e-4;

	/**
	 * Absolute error of a single-precision central finite difference of the gradient-check loss
	 * at the step used there; the measured differences for weights with large gradients sit at
	 * about 1.5e-3.
	 */
	private static final double FD_NOISE_FLOOR = 2e-3;

	/**
	 * Step of the directional central difference for projection and embedding weights, whose
	 * entries are of the order of the initialization scale {@code 0.02}.
	 */
	private static final double DIRECTIONAL_STEP = 1e-3;

	/** Step of the directional central difference for normalization scales, whose entries are one. */
	private static final double NORM_DIRECTIONAL_STEP = 1e-2;

	/**
	 * Single-precision rounding of the summed loss in the directional check, as an absolute error
	 * of the loss: divided by the step it bounds the noise of the central difference, and the
	 * relative term of the check covers the rest. Measured deviations at the documented
	 * configuration were at most about 1.0 at step {@code 1e-3} for the projections, about 1.95
	 * for the token embedding (whose directional derivative of about 1340 keeps that within 0.15%,
	 * well inside the relative term), and at most about 0.04 at step {@code 1e-2}.
	 */
	private static final double DIRECTIONAL_NOISE = 1.5e-3;

	/** The documentation pages the model is trained on, relative to the repository root. */
	private static final String[] CORPUS = {
			"docs/internals/features-pattern.md",
			"docs/internals/producer-evaluable-pattern.md",
			"docs/internals/shape-and-traversal.md",
			"docs/internals/collection-producer-operations.md",
			"docs/internals/state-dictionary.md",
			"docs/internals/end-to-end-computation.md"
	};

	/**
	 * A structurally invalid configuration is rejected at construction, before any weight is
	 * created, while the smallest valid configuration (one head of dimension two, no blocks) is
	 * accepted.
	 */
	@Test(timeout = 60000)
	public void rejectsInvalidConfiguration() {
		StateDictionary weights = new StateDictionary(new HashMap<>());
		assertRejected(() -> new CausalLanguageModel(0, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, 0, DIM, HEADS, DEPTH, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, -DIM, HEADS, DEPTH, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, 0, DEPTH, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, -HEADS, DEPTH, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, -1, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, 0, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM, null));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, 3, DEPTH, FF_DIM, weights));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, 12, 4, DEPTH, FF_DIM, weights));

		StateDictionary minimalWeights = new CausalLanguageModel(1, 1, 2, 1, 0, 1, ROPE_BASE, new Random(SEED))
				.getWeights();
		CausalLanguageModel minimal = new CausalLanguageModel(1, 1, 2, 1, 0, 1, minimalWeights);
		Assert.assertEquals(shape(1, 1), minimal.getOutputShape());
		Assert.assertSame(minimalWeights, minimal.getWeights());
	}

	/**
	 * The existing-weights constructor accepts exactly the weights the configuration needs, and
	 * rejects a dictionary that lacks one of them (including one saved for fewer blocks) or holds
	 * one with the wrong shape, instead of failing later when the model is built or run.
	 */
	@Test(timeout = 60000)
	public void rejectsIncompatibleWeights() {
		CausalLanguageModel fresh = newModel();
		Map<String, TraversalPolicy> shapes = fresh.getConfig().getWeightShapes();
		int perBlockKeys = 6;
		int sharedKeys = 4;
		Assert.assertEquals(sharedKeys + perBlockKeys * DEPTH, shapes.size());
		Assert.assertEquals(shapes.keySet(), fresh.getWeights().keySet());
		Assert.assertArrayEquals(new int[] { 3 * DIM, DIM }, shapes.get(fresh.getConfig().layerKey(0, "qkv")).extent());
		Assert.assertArrayEquals(new int[] { DIM / HEADS / 2 },
				shapes.get(CausalLanguageModel.INV_FREQ_KEY).extent());

		CausalLanguageModel reused = new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
				fresh.getWeights());
		Assert.assertSame(fresh.getWeights(), reused.getWeights());

		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
				new StateDictionary(new HashMap<>())));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH + 1, FF_DIM,
				fresh.getWeights()));
		assertRejected(() -> new CausalLanguageModel(VOCAB + 1, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
				fresh.getWeights()));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, 2 * FF_DIM,
				fresh.getWeights()));

		for (String key : shapes.keySet()) {
			Map<String, PackedCollection> missing = new HashMap<>(fresh.getWeights().getAllWeights());
			missing.remove(key);
			assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
					new StateDictionary(missing)));
		}

		Map<String, PackedCollection> transposed = new HashMap<>(fresh.getWeights().getAllWeights());
		transposed.put(fresh.getConfig().layerKey(0, "w2"), new PackedCollection(shape(FF_DIM, DIM)));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
				new StateDictionary(transposed)));
	}

	/**
	 * The parameter count covers exactly the trainable weights the configuration declares: the
	 * rotary frequencies are excluded, and an extra entry carried by a loaded checkpoint, which
	 * the model never reads, is not counted.
	 */
	@Test(timeout = 60000)
	public void parameterCountIgnoresUndeclaredWeights() {
		int vocab = 2;
		int dim = 4;
		long expected = 2L * vocab * dim + dim;

		CausalLanguageModel minimal = new CausalLanguageModel(vocab, 2, dim, 1, 0, 1, ROPE_BASE,
				new Random(SEED));
		Assert.assertEquals(expected, minimal.getConfig().getParameterCount());

		Map<String, PackedCollection> extended = new HashMap<>(minimal.getWeights().getAllWeights());
		extended.put("unused.extra", new PackedCollection(shape(32)));
		CausalLanguageModel reloaded = new CausalLanguageModel(vocab, 2, dim, 1, 0, 1,
				new StateDictionary(extended));
		Assert.assertEquals(expected, reloaded.getConfig().getParameterCount());
	}

	/**
	 * The fresh-weights constructor rejects a rotary base for which the inverse frequencies would
	 * be {@code NaN} (zero, negative, infinite or {@code NaN}), instead of creating a model with
	 * invalid rotary weights; a valid base produces {@code theta^(-2i / dimHead)}.
	 */
	@Test(timeout = 60000)
	public void rejectsInvalidRopeBase() {
		Random random = new Random(SEED);
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM, 0.0, random));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM, -ROPE_BASE, random));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
				Double.POSITIVE_INFINITY, random));
		assertRejected(() -> new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM, Double.NaN, random));

		CausalLanguageModel lm = new CausalLanguageModel(2, 2, 4, 1, 0, 1, ROPE_BASE, random);
		PackedCollection invFreq = lm.getWeights().get(CausalLanguageModel.INV_FREQ_KEY);
		Assert.assertEquals(2, invFreq.getShape().getTotalSize());
		Assert.assertEquals(1.0, invFreq.toDouble(0), 1e-6);
		Assert.assertEquals(Math.pow(ROPE_BASE, -0.5), invFreq.toDouble(1), 1e-6);
	}

	/**
	 * Asserts that constructing a model throws {@link IllegalArgumentException}.
	 *
	 * @param construction the constructor call
	 */
	private void assertRejected(Runnable construction) {
		try {
			construction.run();
			Assert.fail("Expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			Assert.assertNotNull(expected.getMessage());
		}
	}

	/**
	 * The assembled model's output is two-dimensional, {@code (seqLen, vocab)}, each row is a
	 * normalized log-distribution over the vocabulary, and the negative log-likelihood of one
	 * window is the mean over its {@code seqLen} positions.
	 */
	@Test(timeout = 15 * 60000)
	public void outputShapeAndMeanLoss() {
		CausalLanguageModel lm = newModel();
		assertOutputShape(lm.getOutputShape());
		assertOutputShape(lm.getConfig().getOutputShape());

		CompiledModel compiled = lm.compile(false);
		assertOutputShape(compiled.getOutputShape());

		int[] tokens = new ByteTokenizer().encodeAsInt(
				"Java is the orchestration language; the computation graph is compiled to native code.");
		ValueTarget<PackedCollection> window =
				new NextTokenDataset(tokens, VOCAB, SEQ_LEN, SEQ_LEN, 1).iterator().next();
		PackedCollection output = new PackedCollection(compiled.forward(window.getInput()));
		Assert.assertEquals(SEQ_LEN * VOCAB, output.getShape().getTotalSize());

		double sum = 0.0;
		for (int r = 0; r < SEQ_LEN; r++) {
			double total = 0.0;
			for (int i = 0; i < VOCAB; i++) {
				total += Math.exp(output.toDouble(r * VOCAB + i));
			}

			Assert.assertEquals("row " + r + " is not normalized", 1.0, total, 1e-3);
			sum -= output.toDouble(r * VOCAB + tokens[r + 1]);
		}

		double rowDifference = 0.0;
		for (int i = 0; i < VOCAB; i++) {
			rowDifference = Math.max(rowDifference, Math.abs(output.toDouble(i) - output.toDouble(VOCAB + i)));
		}

		log("maximum difference between the first two output rows = " + rowDifference);
		Assert.assertTrue("positions with different tokens must produce different distributions",
				rowDifference > 1e-5);

		double loss = new NegativeLogLikelihood().loss(output.reshape(shape(SEQ_LEN, VOCAB)),
				window.getExpectedOutput());
		log("initial window loss = " + loss + " (uniform = " + Math.log(VOCAB) + ")");
		Assert.assertEquals(sum / SEQ_LEN, loss, 1e-4);
		Assert.assertEquals(Math.log(VOCAB), loss, 0.5);
		compiled.destroy();
	}

	/**
	 * A causal model's output at a position depends only on the tokens up to it, so the same
	 * weights over the same tokens must give the same rows whatever the context length. Running
	 * the documented configuration at context {@link #SEQ_LEN} and at one position less compares
	 * a model whose sequence length equals its embedding width (so the position count coincides
	 * with the row count of the square and down projections) with one where nothing coincides;
	 * an operation that pairs axes by size rather than by meaning changes only the first.
	 */
	@Test(timeout = 15 * 60000)
	public void outputsDoNotDependOnContextLength() {
		CausalLanguageModel full = newModel();
		CausalLanguageModel shorter = new CausalLanguageModel(VOCAB, SEQ_LEN - 1, DIM, HEADS, DEPTH, FF_DIM,
				full.getWeights());

		int[] tokens = new ByteTokenizer().encodeAsInt(
				"Java is the orchestration language; the computation graph is compiled to native code.");
		double[] fullRows = forwardRows(full, tokens, SEQ_LEN);
		double[] shorterRows = forwardRows(shorter, tokens, SEQ_LEN - 1);

		for (int i = 0; i < shorterRows.length; i++) {
			Assert.assertEquals("row " + i / VOCAB + " entry " + i % VOCAB, shorterRows[i], fullRows[i], 1e-4);
		}

		full.getWeights().destroy();
	}

	/**
	 * Compiles a model for inference and returns its log-probabilities for the first window of
	 * {@code seqLen} of the given tokens.
	 *
	 * @param lm     the model
	 * @param tokens the token ids, more than {@code seqLen} of them
	 * @param seqLen the model's context length
	 * @return the {@code seqLen * VOCAB} output values, row by row
	 */
	private double[] forwardRows(CausalLanguageModel lm, int[] tokens, int seqLen) {
		CompiledModel compiled = lm.compile(false);
		NextTokenDataset window = new NextTokenDataset(tokens, VOCAB, seqLen, seqLen, 1);
		try {
			return compiled.forward(window.iterator().next().getInput()).toArray(0, seqLen * VOCAB);
		} finally {
			window.destroy();
			compiled.destroy();
		}
	}

	/**
	 * The backward pass of the assembled model delivers the correct gradient to every trainable
	 * weight. On a miniature configuration (vocabulary 16, context 4, embed 8, 2 heads, depth 2,
	 * feed-forward width 8) the scalar {@code L = sum(output * G)} for a fixed random {@code G} is
	 * differentiated by the backward pass, through a parameter update that records gradients
	 * instead of applying them, and compared with central finite differences of {@code L} taken at
	 * the test boundary, element by element, for each weight collection, within 5% of the largest
	 * gradient of that collection plus the single-precision noise floor {@link #FD_NOISE_FLOOR}.
	 */
	@Test(timeout = 20 * 60000)
	public void gradientsMatchFiniteDifferences() {
		int vocab = 16;
		int seq = 4;
		CausalLanguageModel lm = new CausalLanguageModel(vocab, seq, 8, 2, 2, 8, ROPE_BASE, new Random(3));

		List<Producer<PackedCollection>> recordedWeights = new ArrayList<>();
		List<PackedCollection> recordedGradients = new ArrayList<>();
		lm.setParameterUpdate(weightGradientRecorder(recordedWeights, recordedGradients));
		CompiledModel compiled = lm.compile(true);
		PackedCollection ids = PackedCollection.of(3, 7, 1, 12);
		PackedCollection outputGradient = randn(shape(seq, vocab), 0.0, 1.0, new Random(4)).evaluate();
		compiled.forward(ids);
		compiled.backward(outputGradient);

		Evaluable<PackedCollection> loss = multiply(cv(shape(seq, vocab), 0), cv(shape(seq, vocab), 1)).sum().get();
		double eps = 5e-3;

		for (String key : lm.getWeights().keySet()) {
			if (CausalLanguageModel.INV_FREQ_KEY.equals(key)) continue;

			PackedCollection weight = lm.getWeights().get(key);
			PackedCollection analytic = recordedGradient(key, weight, recordedWeights, recordedGradients);

			double maxAbs = 0.0;
			double maxDiff = 0.0;
			double[] numeric = centralDifferences(weight, eps,
					() -> loss.evaluate(compiled.forward(ids), outputGradient).toDouble());
			for (int i = 0; i < numeric.length; i++) {
				maxAbs = Math.max(maxAbs, Math.abs(numeric[i]));
				maxDiff = Math.max(maxDiff, Math.abs(numeric[i] - analytic.toDouble(i)));
			}

			log("weight=" + key + " maxNumericGradient=" + maxAbs + " maxDifference=" + maxDiff);
			Assert.assertTrue(key + " gradient is trivially zero", maxAbs > 1e-4);
			Assert.assertTrue(key + " gradient differs from finite differences by " + maxDiff +
					" (max magnitude " + maxAbs + ")", maxDiff <= 0.05 * maxAbs + FD_NOISE_FLOOR);
		}

		compiled.destroy();
	}

	/**
	 * The backward pass delivers the correct gradient at the documented configuration, where
	 * the miniature check of {@link #gradientsMatchFiniteDifferences()} cannot reach defects that
	 * appear only at scale (such as an index that overflows only for large operands). For the scalar
	 * {@code L = sum(output * G)} over a window of documentation text and a fixed random {@code G},
	 * the analytic gradient of every weight collection is projected onto a random direction
	 * {@code D} and compared with the central difference of {@code L} along {@code D}. One
	 * directional derivative per collection keeps the check to two forward passes per weight,
	 * and aggregating over every entry keeps it above the single-precision noise of the sum.
	 */
	@Test(timeout = 15 * 60000)
	public void gradientsMatchDirectionalDerivativesAtDocumentedScale() {
		CausalLanguageModel lm = newModel();
		List<Producer<PackedCollection>> recordedWeights = new ArrayList<>();
		List<PackedCollection> recordedGradients = new ArrayList<>();
		lm.setParameterUpdate(weightGradientRecorder(recordedWeights, recordedGradients));

		CompiledModel compiled = lm.compile(true);
		int[] tokens = new ByteTokenizer().encodeAsInt(
				"Java is the orchestration language; the computation graph is compiled to native code.");
		NextTokenDataset window = new NextTokenDataset(tokens, VOCAB, SEQ_LEN, SEQ_LEN, 1);
		PackedCollection ids = window.iterator().next().getInput();
		PackedCollection outputGradient = randn(shape(SEQ_LEN, VOCAB), 0.0, 1.0, new Random(4)).evaluate();
		Evaluable<PackedCollection> loss = multiply(cv(shape(SEQ_LEN, VOCAB), 0), cv(shape(SEQ_LEN, VOCAB), 1))
				.sum().get();

		try {
			compiled.forward(ids);
			compiled.backward(outputGradient);

			Random directions = new Random(6);
			for (String key : lm.getWeights().keySet()) {
				if (CausalLanguageModel.INV_FREQ_KEY.equals(key)) continue;

				double eps = key.contains("norm") ? NORM_DIRECTIONAL_STEP : DIRECTIONAL_STEP;
				PackedCollection weight = lm.getWeights().get(key);
				PackedCollection analytic = recordedGradient(key, weight, recordedWeights, recordedGradients);
				TraversalPolicy flat = shape(weight.getMemLength());
				try (PackedCollection original = new PackedCollection(flat);
					 PackedCollection direction = randn(flat, 0.0, 1.0, directions).evaluate()) {
					original.setFrom(0, weight);
					double expected = cp(analytic.reshape(flat)).multiply(cp(direction)).sum().evaluate().toDouble();

					a(p(weight.reshape(flat)), cp(original).add(cp(direction).multiply(eps))).get().run();
					double up = loss.evaluate(compiled.forward(ids), outputGradient).toDouble();
					a(p(weight.reshape(flat)), cp(original).subtract(cp(direction).multiply(eps))).get().run();
					double down = loss.evaluate(compiled.forward(ids), outputGradient).toDouble();
					weight.setFrom(0, original);
					double numeric = (up - down) / (2 * eps);

					log("weight=" + key + " eps=" + eps + " analyticDirectional=" + expected +
							" numericDirectional=" + numeric);
					Assert.assertTrue(key + " directional derivative " + expected + " differs from the central " +
									"difference " + numeric,
							Math.abs(expected - numeric) <= 0.05 * Math.abs(numeric) + DIRECTIONAL_NOISE / eps);
				}
			}
		} finally {
			compiled.destroy();
			window.destroy();
			lm.getWeights().destroy();
		}
	}

	/**
	 * Returns a parameter update that records, for every weight the model wires, the weight's
	 * producer and its gradient (see {@link #gradientRecorder}), leaving the weights unchanged.
	 *
	 * @param weights   the list receiving the producer of each weight, in wiring order
	 * @param gradients the list receiving the gradient of each weight, in the same order
	 * @return the recording parameter update
	 */
	private ParameterUpdate<PackedCollection> weightGradientRecorder(List<Producer<PackedCollection>> weights,
																	 List<PackedCollection> gradients) {
		ParameterUpdate<PackedCollection> recorder = gradientRecorder(gradients);
		return (name, weight, gradient) -> {
			weights.add(weight);
			return recorder.apply(name, weight, gradient);
		};
	}

	/**
	 * Returns the gradient recorded for a weight by {@link #weightGradientRecorder}, failing if
	 * none was delivered to it.
	 *
	 * @param key       the weight key, for the failure message
	 * @param weight    the weight
	 * @param weights   the recorded weight producers
	 * @param gradients the recorded gradients, in the same order
	 * @return the gradient of the weight from the most recent backward pass
	 */
	private PackedCollection recordedGradient(String key, PackedCollection weight,
											  List<Producer<PackedCollection>> weights,
											  List<PackedCollection> gradients) {
		PackedCollection analytic = null;
		for (int i = 0; i < weights.size(); i++) {
			if (weights.get(i).get().evaluate().getMem() == weight.getMem()) {
				analytic = gradients.get(i);
			}
		}

		Assert.assertNotNull("no gradient was delivered to " + key, analytic);
		return analytic;
	}

	/**
	 * Trains the tiny causal language model on the documentation corpus through
	 * {@link ModelOptimizer} and requires its loss over every held-out window to end below the
	 * unigram byte-entropy baseline of exactly the held-out targets those windows score
	 * ({@link NextTokenDataset#scoredTargetEntropyBits()} of the same dataset). Every training and
	 * validation loss passes through a fail-fast wrapper that rejects non-finite values, so no
	 * window is silently skipped. The trained weights are saved, reloaded, must reproduce the
	 * per-epoch held-out loss, and generate a logged greedy continuation, which must be
	 * reproducible and non-trivial. All of those checks run before the baseline check, so a run
	 * that misses the baseline still verifies its checkpoint and its generation.
	 *
	 * @throws IOException if the corpus cannot be read or the weights cannot be saved
	 */
	@Test(timeout = 38 * 60000)
	@TestProperties(longRunning = true, excludeProfiles = TestUtils.PIPELINE)
	public void trainOnDocumentation() throws IOException {
		int[] tokens = new ByteTokenizer().encodeAsInt(readCorpus());
		NextTokenDataset corpus = new NextTokenDataset(tokens, VOCAB, SEQ_LEN, SEQ_LEN, 0);
		List<Dataset<PackedCollection>> parts = corpus.split(TRAIN_FRACTION);
		NextTokenDataset train = (NextTokenDataset) parts.get(0);
		NextTokenDataset heldOutRegion = (NextTokenDataset) parts.get(1);
		int trainStride = train.spanningStride(EPOCHS * TRAIN_WINDOWS);
		NextTokenDataset trainWindows = new NextTokenDataset(tokens, train.getStart(), train.getEnd(),
				VOCAB, SEQ_LEN, trainStride, TRAIN_WINDOWS).setRotating(true);
		NextTokenDataset heldOut = new NextTokenDataset(tokens, heldOutRegion.getStart(), heldOutRegion.getEnd(),
				VOCAB, SEQ_LEN, HELD_OUT_STRIDE, HELD_OUT_WINDOWS);
		NextTokenDataset heldOutAll = new NextTokenDataset(tokens, heldOutRegion.getStart(), heldOutRegion.getEnd(),
				VOCAB, SEQ_LEN, HELD_OUT_STRIDE, 0);

		double unigramBits = heldOutAll.scoredTargetEntropyBits();
		log("corpus bytes=" + tokens.length + " trainRegion=[" + train.getStart() + ", " + train.getEnd() +
				") heldOutRegion=[" + heldOut.getStart() + ", " + heldOut.getEnd() + ")");
		log("trainStride=" + trainStride + " heldOutStride=" + HELD_OUT_STRIDE +
				" trainWindowsPerEpoch=" + trainWindows.getWindowCount() +
				" (of " + trainWindows.getAvailableWindowCount() + ", rotating)" +
				" perEpochHeldOutWindows=" + heldOut.getWindowCount() +
				" finalHeldOutWindows=" + heldOutAll.getWindowCount() +
				" epochs=" + EPOCHS);
		log("baselines: uniformBitsPerByte=8.0 unigramBitsPerByte=" + unigramBits +
				" perEpochUnigramBitsPerByte=" + heldOut.scoredTargetEntropyBits() +
				" heldOutRegionUnigramBitsPerByte=" + heldOut.unigramEntropyBits());
		log("host=" + System.getProperty("os.name") + " " + System.getProperty("os.arch") +
				" precision=" + Hardware.getLocalHardware().getPrecision() + " seed=" + SEED +
				" learningRate=" + LEARNING_RATE + "->" + FINAL_LEARNING_RATE + " betas=0.9/0.999 ffDim=" + FF_DIM + " ropeBase=" + ROPE_BASE);

		CausalLanguageModel lm = newModel();
		log("parameters=" + lm.getConfig().getParameterCount());
		PackedCollection learningRate = PackedCollection.of(LEARNING_RATE);
		lm.setParameterUpdate(new AdamOptimizer(cp(learningRate), c(0.9), c(0.999)));
		Runnable decayLearningRate = a(p(learningRate), max(
				cp(learningRate).add((FINAL_LEARNING_RATE - LEARNING_RATE) / (EPOCHS - 1)),
				c(FINAL_LEARNING_RATE))).get();

		CompiledModel compiled = null;
		try {
			long compileStart = System.nanoTime();
			compiled = lm.compile(true);
			log("compileSeconds=" + (System.nanoTime() - compileStart) / 1e9);

			assertTrainsPastBaselineAndReloads(lm, compiled, learningRate, decayLearningRate,
					trainWindows, heldOut, heldOutAll);
		} finally {
			Destroyable.destroy(compiled);
			lm.getWeights().destroy();
			learningRate.destroy();
			trainWindows.destroy();
			heldOut.destroy();
			heldOutAll.destroy();
		}
	}

	/**
	 * Runs the training loop of {@link #trainOnDocumentation()}, scores every held-out window
	 * once, saves and reloads the trained weights, requires the reloaded model to reproduce the
	 * per-epoch held-out loss, generates text from the reloaded model and requires it to be
	 * reproducible and non-trivial, and finally requires the all-window held-out loss to beat the
	 * unigram baseline of exactly the targets those windows score. The
	 * reload model and its weights are released before returning or throwing.
	 *
	 * @param lm                the model being trained
	 * @param compiled          the compiled training model
	 * @param learningRate      the learning-rate cell read by the optimizer
	 * @param decayLearningRate the per-epoch learning-rate decay
	 * @param trainWindows      the training windows
	 * @param heldOut           the held-out windows scored at every epoch boundary
	 * @param heldOutAll        every held-out window, scored once after training
	 * @throws IOException if the weights cannot be saved or reloaded
	 */
	private void assertTrainsPastBaselineAndReloads(CausalLanguageModel lm, CompiledModel compiled,
													PackedCollection learningRate, Runnable decayLearningRate,
													NextTokenDataset trainWindows, NextTokenDataset heldOut,
													NextTokenDataset heldOutAll) throws IOException {
		ModelOptimizer optimizer = new ModelOptimizer(compiled, () -> trainWindows);
		optimizer.setLossFunction(finiteLoss(new NegativeLogLikelihood()));
		optimizer.setValidationDataset(() -> heldOut);
		optimizer.setLogFrequency(1);

		List<Long> epochEnds = new ArrayList<>();
		long trainingStart = System.nanoTime();
		optimizer.setProgressCallback(progress -> {
			epochEnds.add(System.nanoTime());
			log("epoch=" + progress.getEpoch() + " trainBitsPerByte=" + progress.getTrainLoss() / Math.log(2) +
					" heldOutBitsPerByte=" + progress.getValidationLoss() / Math.log(2) +
					" learningRate=" + learningRate.toDouble() +
					" elapsedSeconds=" + (System.nanoTime() - trainingStart) / 1e9);
			decayLearningRate.run();
		});

		TrainingResult result = optimizer.optimize(EPOCHS);
		reportTimings(trainingStart, epochEnds, trainWindows.getWindowCount() + heldOut.getWindowCount());

		List<Double> heldOutHistory = result.getValidationLossHistory();
		Assert.assertFalse(heldOutHistory.isEmpty());
		long evaluationStart = System.nanoTime();
		double finalBits = optimizer.evaluate(heldOutAll) / Math.log(2);
		double unigramBits = heldOutAll.scoredTargetEntropyBits();
		log("totalSteps=" + result.getEpochsCompleted() * trainWindows.getWindowCount() +
				" epochsRun=" + result.getEpochsCompleted() +
				" lastEpochHeldOutBitsPerByte=" + heldOutHistory.get(heldOutHistory.size() - 1) / Math.log(2) +
				" finalHeldOutBitsPerByte=" + finalBits + " unigramBitsPerByte=" + unigramBits +
				" heldOutWindows=" + heldOutAll.getWindowCount() +
				" evaluationSeconds=" + (System.nanoTime() - evaluationStart) / 1e9);
		Path weightsDir = Path.of("results", "causal-language-model");
		Files.createDirectories(weightsDir);
		lm.getWeights().save(weightsDir.resolve("weights.pb"));

		StateDictionary reloadedWeights = new StateDictionary(weightsDir.toString());
		CompiledModel reloadedCompiled = null;
		int[][] generated;
		try {
			CausalLanguageModel reloaded = new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
					reloadedWeights);
			reloadedCompiled = reloaded.compile(false);
			ModelOptimizer evaluator = new ModelOptimizer(reloadedCompiled, () -> heldOut);
			evaluator.setLossFunction(finiteLoss(new NegativeLogLikelihood()));
			double reloadedBits = evaluator.evaluate(heldOut) / Math.log(2);
			double trainedBits = optimizer.evaluate(heldOut) / Math.log(2);
			log("reloadedHeldOutBitsPerByte=" + reloadedBits + " trainedHeldOutBitsPerByte=" + trainedBits);
			Assert.assertEquals(trainedBits, reloadedBits, RELOAD_TOLERANCE * trainedBits);

			logNextByteDistribution(reloadedCompiled, GENERATION_PROMPT);
			logNextByteDistribution(reloadedCompiled, TRAINING_PROMPT);
			generateSample(new CausalLanguageModel(reloaded.getConfig(), reloadedWeights),
					TRAINING_PROMPT, GENERATED_BYTES);
			generated = generateSample(new CausalLanguageModel(reloaded.getConfig(), reloadedWeights),
					GENERATION_PROMPT, GENERATED_BYTES);
		} finally {
			Destroyable.destroy(reloadedCompiled);
			reloadedWeights.destroy();
		}

		assertNonTrivialGeneration(new ByteTokenizer().encodeAsInt(GENERATION_PROMPT), generated[0], generated[1]);
		// The documented configuration scores 3.426 bits/byte over all 83 held-out windows against a scored
		// baseline of 4.904; the earlier peak rate of 3e-3 reached only 4.495 and generated 96 spaces
		Assert.assertTrue("held-out loss " + finalBits + " bits/byte did not beat the unigram baseline " +
				unigramBits, finalBits < unigramBits);
	}

	/**
	 * The sliding-window generator decodes exactly the full-sequence forward pass: on a miniature
	 * configuration with random weights, every greedily generated token, including those generated
	 * after the sequence outgrows the context and the window slides, is the most probable token of
	 * the last filled row of an independent inference compilation of the same weights run over the
	 * preceding (at most {@code seqLen}) tokens, with the remaining positions padded by a different
	 * filler than the generator uses, which the causal mask must ignore. Greedy decoding is
	 * reproducible after {@link AutoregressiveModel#reset()}.
	 */
	@Test(timeout = 15 * 60000)
	public void slidingWindowGenerationMatchesFullForward() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));
		CompiledModel inference = lm.compile(false);

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Producer");
			int generated = 2 * seq;
			int[] first;
			int[] second;
			try (AutoregressiveModel<Integer> generator = new SlidingWindowAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				first = generate(generator, prompt, generated);
				second = generate(generator, prompt, generated);
			}
			Assert.assertArrayEquals("greedy decoding is not reproducible", first, second);

			int[] sequence = IntStream.concat(IntStream.of(prompt), IntStream.of(first)).toArray();
			for (int p = prompt.length; p < sequence.length; p++) {
				int from = Math.max(0, p - seq);
				int filled = p - from;
				int[] padded = IntStream.rangeClosed(0, seq)
						.map(i -> i < filled ? sequence[from + i] : VOCAB - 1).toArray();
				try (NextTokenDataset window = new NextTokenDataset(padded, VOCAB, seq, seq, 1)) {
					PackedCollection row = inference.forward(window.iterator().next().getInput())
							.range(shape(VOCAB), (filled - 1) * VOCAB);
					Assert.assertEquals("token at position " + p, sequence[p],
							AutoregressiveModel.sampleToken(row, VOCAB, 0.0, 1.0, null));
				}
			}
		} finally {
			inference.destroy();
			lm.getWeights().destroy();
		}
	}

	/**
	 * Destroying a generator releases the compilation and buffers it decodes with, including its
	 * position, and leaves the weights of its model usable for another generator; a second
	 * destroy is harmless.
	 */
	@Test(timeout = 5 * 60000)
	public void destroyingGeneratorReleasesItsBuffers() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pro");
			AutoregressiveModel<Integer> generator = new SlidingWindowAutoregressiveModel(lm, null);
			int[] before = generate(generator, prompt, 2);
			PackedCollection position = generator.getPosition();
			Assert.assertFalse(position.isDestroyed());

			generator.destroy();
			Assert.assertTrue("generator position was not released", position.isDestroyed());
			Assert.assertFalse("model weights were released",
					lm.getWeights().get(CausalLanguageModel.EMBEDDING_KEY).isDestroyed());
			generator.destroy();

			try (AutoregressiveModel<Integer> next = new SlidingWindowAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				Assert.assertArrayEquals(before, generate(next, prompt, 2));
			}
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * The single-position key/value-cache decode decodes exactly the full-sequence forward pass: on a
	 * miniature configuration with random weights, every greedily generated token — including those
	 * generated after the first, where the cache is the only thing carrying the past — is the most
	 * probable token of the last filled row of an independent inference compilation of the same weights
	 * run over the preceding tokens, with the remaining positions padded by a different filler than the
	 * generator uses, which the causal mask must ignore. Greedy decoding is reproducible after
	 * {@link AutoregressiveModel#reset()}. This is the committed proof that the fused-QKV +
	 * {@code rope_inv_freq} bridge, the cache advance and the rotary rotation at each position all agree
	 * with the trained full-window path; it is the counterpart of
	 * {@link #slidingWindowGenerationMatchesFullForward()} for the cache decode.
	 */
	@Test(timeout = 15 * 60000)
	public void keyValueCacheDecodeMatchesFullForward() {
		int seq = 12;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 2, 8, ROPE_BASE, new Random(5));
		CompiledModel inference = lm.compile(false);

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pro");
			int generated = 8;
			int[] first;
			int[] second;
			try (AutoregressiveModel<Integer> generator = new KeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				first = generate(generator, prompt, generated);
				second = generate(generator, prompt, generated);
			}
			Assert.assertArrayEquals("greedy decoding is not reproducible", first, second);

			int[] sequence = IntStream.concat(IntStream.of(prompt), IntStream.of(first)).toArray();
			for (int p = prompt.length; p < sequence.length; p++) {
				int filled = p;
				int[] padded = IntStream.rangeClosed(0, seq)
						.map(i -> i < filled ? sequence[i] : VOCAB - 1).toArray();
				try (NextTokenDataset window = new NextTokenDataset(padded, VOCAB, seq, seq, 1)) {
					PackedCollection row = inference.forward(window.iterator().next().getInput())
							.range(shape(VOCAB), (filled - 1) * VOCAB);
					Assert.assertEquals("token at position " + p, sequence[p],
							AutoregressiveModel.sampleToken(row, VOCAB, 0.0, 1.0, null));
				}
			}
		} finally {
			inference.destroy();
			lm.getWeights().destroy();
		}
	}

	/**
	 * The key/value-cache generator's {@link AutoregressiveModel#reset()} clears the per-layer caches,
	 * so a sequence decoded after a previous one is byte-identical to the same sequence decoded by a
	 * fresh generator: the previous sequence's cached keys and values do not leak into it.
	 */
	@Test(timeout = 15 * 60000)
	public void keyValueCacheResetClearsCacheAcrossSequences() {
		// seq must hold the longer prompt and its generated tokens, since the cache is bounded by it.
		int seq = 20;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 2, 8, ROPE_BASE, new Random(5));

		try {
			int[] promptA = new ByteTokenizer().encodeAsInt("Longer prompt");
			int[] promptB = new ByteTokenizer().encodeAsInt("Pro");
			int generated = 4;
			int[] reusedB;
			int[] freshB;
			try (AutoregressiveModel<Integer> generator = new KeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				generate(generator, promptA, generated);
				reusedB = generate(generator, promptB, generated);
			}
			try (AutoregressiveModel<Integer> fresh = new KeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				freshB = generate(fresh, promptB, generated);
			}

			Assert.assertArrayEquals("reset must clear the cache so a previous sequence does not leak",
					freshB, reusedB);
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * A later position reads the key and value an earlier position wrote to the cache: decoding the
	 * same token at position one, after two different tokens at position zero, gives two different
	 * output rows, which can only be because position one attends to the entry position zero cached.
	 * This is the cache-causality counterpart of the SkyTnt
	 * {@code laterPositionsReadEarlierCachedEntries} test, driven directly over the decode model so it
	 * does not depend on greedy argmax divergence.
	 */
	@Test(timeout = 10 * 60000)
	public void keyValueCacheLaterPositionsReadEarlierEntries() {
		int seq = 8;
		int dim = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, dim, 2, 1, 8, ROPE_BASE, new Random(5));
		PackedCollection position = new PackedCollection(1);
		List<PackedCollection> keyCaches = new ArrayList<>();
		List<PackedCollection> valueCaches = new ArrayList<>();
		CompiledModel decode = null;

		try {
			decode = lm.decodeModel(position, keyCaches, valueCaches).compile(false);
			PackedCollection embedding = lm.getWeights().get(CausalLanguageModel.EMBEDDING_KEY);
			PackedCollection input = new PackedCollection(shape(1, dim));

			double[] afterLow = decodeRow(decode, position, keyCaches, valueCaches, embedding, input,
					new int[] { 10, 42 }, dim);
			double[] afterHigh = decodeRow(decode, position, keyCaches, valueCaches, embedding, input,
					new int[] { 200, 42 }, dim);

			double maxDifference = 0.0;
			for (int i = 0; i < VOCAB; i++) {
				maxDifference = Math.max(maxDifference, Math.abs(afterLow[i] - afterHigh[i]));
			}

			log("maxDifference between position-1 rows for different position-0 tokens = " + maxDifference);
			Assert.assertTrue("position 1 output must depend on the token cached at position 0",
					maxDifference > 1e-4);
		} finally {
			Destroyable.destroy(decode);
			position.destroy();
			keyCaches.forEach(PackedCollection::destroy);
			valueCaches.forEach(PackedCollection::destroy);
			lm.getWeights().destroy();
		}
	}

	/**
	 * The rotary frequency table the decode path indexes by position is built from the model's stored
	 * inverse frequencies and holds exactly {@code cos(position * invFreq[i])} and
	 * {@code sin(position * invFreq[i])}, the same angles the full-sequence rotation applies at that
	 * row. This pins the {@code rope_inv_freq}-to-{@code freqCis} half of the bridge numerically,
	 * independently of the end-to-end decode match.
	 */
	@Test(timeout = 60000)
	public void decodeRopeFrequenciesMatchStoredInverseFrequencies() {
		int headDim = 8;
		int seqLen = 5;
		CausalLanguageModel lm = new CausalLanguageModel(2, seqLen, headDim, 1, 0, 1, ROPE_BASE, new Random(SEED));
		PackedCollection invFreq = lm.getWeights().get(CausalLanguageModel.INV_FREQ_KEY);
		int freqDim = headDim / 2;
		Assert.assertEquals(freqDim, invFreq.getShape().getTotalSize());

		PackedCollection freqCis = RotationFeatures.getInstance().computeRopeFreqs(seqLen, invFreq).evaluate();
		Assert.assertArrayEquals(new int[] { seqLen, freqDim, 2 }, freqCis.getShape().extent());

		for (int p = 0; p < seqLen; p++) {
			for (int i = 0; i < freqDim; i++) {
				double angle = p * invFreq.toDouble(i);
				Assert.assertEquals("cos at position " + p + " pair " + i,
						Math.cos(angle), freqCis.toDouble((p * freqDim + i) * 2), 1e-5);
				Assert.assertEquals("sin at position " + p + " pair " + i,
						Math.sin(angle), freqCis.toDouble((p * freqDim + i) * 2 + 1), 1e-5);
			}
		}

		lm.getWeights().destroy();
	}

	/**
	 * Destroying a key/value-cache generator releases the decode compilation, the input buffer, the
	 * per-layer caches and the position, and leaves the weights of its model usable for another
	 * generator; a second destroy is harmless, and a generator over the same weights reproduces the
	 * decoded tokens.
	 */
	@Test(timeout = 5 * 60000)
	public void destroyingKeyValueCacheGeneratorReleasesItsBuffers() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pro");
			AutoregressiveModel<Integer> generator = new KeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null);
			int[] before = generate(generator, prompt, 2);
			PackedCollection position = generator.getPosition();
			Assert.assertFalse(position.isDestroyed());

			generator.destroy();
			Assert.assertTrue("generator position was not released", position.isDestroyed());
			Assert.assertFalse("model weights were released",
					lm.getWeights().get(CausalLanguageModel.EMBEDDING_KEY).isDestroyed());
			generator.destroy();

			try (AutoregressiveModel<Integer> next = new KeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				Assert.assertArrayEquals(before, generate(next, prompt, 2));
			}
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * The key/value-cache generator decodes the first {@code seqLen} positions without error and then
	 * refuses to decode further: its per-layer caches are fixed {@code (seqLen, dim)} buffers, so a step
	 * at or beyond {@code seqLen} would index past them, and is rejected with an
	 * {@link IllegalStateException} rather than left to corrupt memory. A generator advanced to exactly
	 * {@code seqLen} positions is the boundary, and the next step is the one that throws.
	 */
	@Test(timeout = 5 * 60000)
	public void keyValueCacheRefusesDecodingPastSeqLen() {
		int seq = 4;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pr");
			try (AutoregressiveModel<Integer> generator = new KeyValueCacheAutoregressiveModel(
					new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null)) {
				generator.reset();
				generator.setPrompt(IntStream.of(prompt).boxed().toArray(Integer[]::new), prompt.length);
				for (int i = 0; i < seq; i++) {
					generator.next();
				}
				Assert.assertEquals(seq, generator.getCurrentStep());

				try {
					generator.next();
					Assert.fail("decoding at seqLen must throw rather than index past the cache");
				} catch (IllegalStateException expected) {
					Assert.assertTrue("message should name the cache size",
							expected.getMessage().contains(String.valueOf(seq)));
				}
			}
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * Decodes a fixed sequence of tokens through a compiled decode model, driving the position by hand,
	 * and returns the output row of the last token. The caches are cleared first so each call starts a
	 * new sequence.
	 *
	 * @param decode      the compiled decode model
	 * @param position    the device-resident position the decode model reads
	 * @param keyCaches   the per-layer key caches to clear before decoding
	 * @param valueCaches the per-layer value caches to clear before decoding
	 * @param embedding   the token embedding table
	 * @param input       the model input buffer, {@code (1, dim)}
	 * @param tokens      the token ids to feed at positions {@code 0, 1, ...}
	 * @param dim         the model dimension
	 * @return the {@code (vocabSize)} output row of the last token
	 */
	private double[] decodeRow(CompiledModel decode, PackedCollection position,
							   List<PackedCollection> keyCaches, List<PackedCollection> valueCaches,
							   PackedCollection embedding, PackedCollection input, int[] tokens, int dim) {
		keyCaches.forEach(PackedCollection::clear);
		valueCaches.forEach(PackedCollection::clear);

		double[] row = null;
		for (int s = 0; s < tokens.length; s++) {
			position.fill((double) s);
			input.setFrom(0, embedding.range(shape(dim), tokens[s] * dim), 0, dim);
			row = decode.forward(input).toArray(0, VOCAB);
		}

		return row;
	}

	/**
	 * A generator built by {@link AutoregressiveModel#of} releases only what it created: after it
	 * is destroyed, the caller's position and token embedding are still live, and the compiled
	 * model still produces the same tokens for a second generator.
	 */
	@Test(timeout = 5 * 60000)
	public void destroyingFactoryGeneratorKeepsCallerResources() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));
		CompiledModel inference = lm.compile(false);
		PackedCollection position = new PackedCollection(1);
		PackedCollection embedded = new PackedCollection(shape(seq));

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pro");
			// of() takes the argmax over the whole flattened (seq, vocab) output, so a generated
			// token may exceed VOCAB; it is reduced to a valid token id so that the embedding
			// never gathers outside its table and the comparison below is deterministic
			IntFunction<PackedCollection> embed = t -> {
				embedded.fill((double) (t % VOCAB));
				return embedded;
			};
			AutoregressiveModel<Integer> generator = AutoregressiveModel.of(inference, position, embed);
			int[] before = generate(generator, prompt, 2);
			Assert.assertEquals(prompt.length + 2, (int) position.toDouble(0));

			generator.destroy();
			Assert.assertFalse("caller position was released", position.isDestroyed());
			Assert.assertFalse("caller embedding was released", embedded.isDestroyed());
			generator.destroy();

			try (AutoregressiveModel<Integer> next = AutoregressiveModel.of(inference, position, embed)) {
				Assert.assertArrayEquals(before, generate(next, prompt, 2));
			}
		} finally {
			inference.destroy();
			position.destroy();
			embedded.destroy();
			lm.getWeights().destroy();
		}
	}

	/**
	 * A {@link AutoregressiveModel#tokenLoader token loader} writes the embedding of each token's
	 * packed values into the caller's input, replacing the previous token's embedding, and
	 * destroying it leaves that input live and holding the last embedding.
	 */
	@Test(timeout = 5 * 60000)
	public void tokenLoaderWritesEmbeddingAndKeepsCallerInput() {
		PackedCollection input = new PackedCollection(shape(1, 4));

		try {
			AutoregressiveModel.TokenLoader<Double> loader = AutoregressiveModel.tokenLoader(
					input, 4, (token, values) -> values.fill(token), values -> c(values).multiply(2.0));

			loader.accept(3.0);
			Assert.assertArrayEquals(new double[] { 6.0, 6.0, 6.0, 6.0 }, input.toArray(0, 4), 0.0);
			loader.accept(-5.0);
			Assert.assertArrayEquals(new double[] { -10.0, -10.0, -10.0, -10.0 }, input.toArray(0, 4), 0.0);

			loader.destroy();
			Assert.assertFalse("caller input was released", input.isDestroyed());
			Assert.assertArrayEquals(new double[] { -10.0, -10.0, -10.0, -10.0 }, input.toArray(0, 4), 0.0);
		} finally {
			input.destroy();
		}
	}

	/**
	 * Greedily continues a prompt with a trained model, twice, restarting the generator in
	 * between. Logs the generated text, the generation time, and whether the generated bytes are
	 * valid UTF-8 under a strict decoder; validity is reported rather than asserted, because a
	 * byte-level model may legitimately emit a partial multi-byte sequence. The continuations are
	 * returned unchecked so that the sample is logged even when a later assertion fails.
	 *
	 * @param lm     the trained model, not yet compiled
	 * @param prompt the text to continue
	 * @param length the number of bytes to generate
	 * @return the two continuations
	 */
	private int[][] generateSample(CausalLanguageModel lm, String prompt, int length) {
		int[] promptTokens = new ByteTokenizer().encodeAsInt(prompt);
		int[] first;
		int[] second;
		double seconds;
		try (AutoregressiveModel<Integer> generator = new SlidingWindowAutoregressiveModel(lm, null)) {
			long start = System.nanoTime();
			first = generate(generator, promptTokens, length);
			seconds = (System.nanoTime() - start) / 1e9;
			second = generate(generator, promptTokens, length);
		}

		ByteTokenizer tokenizer = new ByteTokenizer();
		log("generationSeconds=" + seconds + " bytesPerSecond=" + (promptTokens.length + length) / seconds +
				" utf8=" + (tokenizer.isValidUtf8(first) ? "valid" : "invalid"));
		log("generated=" + prompt + tokenizer.decodeAsInt(first));
		return new int[][] { first, second };
	}

	/**
	 * Logs the {@link #LOGGED_CANDIDATES} most probable bytes the model predicts after a prompt,
	 * with their probabilities, from one forward pass over the prompt (padded with zeros, which the
	 * causal mask keeps from affecting the prompt's rows). The margin between the two most probable
	 * bytes shows how decisively greedy decoding picks its first byte.
	 *
	 * @param inference the inference compilation of the trained model
	 * @param prompt    the prompt, shorter than {@link #SEQ_LEN} bytes
	 */
	private void logNextByteDistribution(CompiledModel inference, String prompt) {
		int[] promptTokens = new ByteTokenizer().encodeAsInt(prompt);
		int[] padded = IntStream.rangeClosed(0, SEQ_LEN)
				.map(i -> i < promptTokens.length ? promptTokens[i] : 0).toArray();
		double[] logProbabilities;
		try (NextTokenDataset window = new NextTokenDataset(padded, VOCAB, SEQ_LEN, SEQ_LEN, 1)) {
			logProbabilities = inference.forward(window.iterator().next().getInput())
					.toArray((promptTokens.length - 1) * VOCAB, VOCAB);
		}

		int[] ranked = IntStream.range(0, VOCAB).boxed()
				.sorted((a, b) -> Double.compare(logProbabilities[b], logProbabilities[a]))
				.mapToInt(Integer::intValue).limit(LOGGED_CANDIDATES).toArray();
		StringBuilder candidates = new StringBuilder();
		for (int t : ranked) {
			candidates.append(" byte=").append(t).append(" p=").append(Math.exp(logProbabilities[t]));
		}

		log("prompt=\"" + prompt + "\" topNextBytes:" + candidates + " logMargin=" +
				(logProbabilities[ranked[0]] - logProbabilities[ranked[1]]));
	}

	/**
	 * Requires two greedy continuations of the same prompt to be identical and non-trivial: more
	 * than one distinct byte, and not the prompt repeated.
	 *
	 * @param prompt the prompt tokens
	 * @param first  the first continuation
	 * @param second the continuation generated again after a restart
	 */
	private void assertNonTrivialGeneration(int[] prompt, int[] first, int[] second) {
		int[] promptRepeated = IntStream.range(0, first.length).map(i -> prompt[i % prompt.length]).toArray();
		Assert.assertArrayEquals("greedy decoding is not reproducible", first, second);
		Assert.assertTrue("generation is a single repeated byte", IntStream.of(first).distinct().count() > 1);
		Assert.assertFalse("generation repeats the prompt", Arrays.equals(promptRepeated, first));
	}

	/**
	 * Restarts a generator, feeds it a prompt, and returns the tokens it generates after the
	 * prompt.
	 *
	 * @param generator the generator
	 * @param prompt    the prompt tokens
	 * @param length    the number of tokens to generate
	 * @return the generated tokens
	 */
	static int[] generate(AutoregressiveModel<Integer> generator, int[] prompt, int length) {
		generator.reset();
		generator.setPrompt(IntStream.of(prompt).boxed().toArray(Integer[]::new), prompt.length);
		IntStream.range(0, prompt.length).forEach(i -> generator.next());
		return IntStream.range(0, length).map(i -> generator.next()).toArray();
	}

	/**
	 * Asserts that a shape is the two-dimensional {@code (seqLen, vocab)} output shape.
	 *
	 * @param shape the shape to check
	 */
	private void assertOutputShape(TraversalPolicy shape) {
		Assert.assertTrue("output shape " + shape + " is not (" + SEQ_LEN + ", " + VOCAB + ")",
				shape.equalsIgnoreAxis(shape(SEQ_LEN, VOCAB)));
	}

	/**
	 * Creates the model under test with seeded initial weights.
	 *
	 * @return the model
	 */
	private CausalLanguageModel newModel() {
		return new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM, ROPE_BASE, new Random(SEED));
	}

	/**
	 * Reads the corpus files, in order, as one string.
	 *
	 * @return the corpus text
	 * @throws IOException if a file cannot be read
	 */
	private String readCorpus() throws IOException {
		Path root = Path.of("..", "..");
		StringBuilder text = new StringBuilder();
		for (String file : CORPUS) {
			text.append(Files.readString(root.resolve(file), StandardCharsets.UTF_8)).append('\n');
		}

		return text.toString();
	}

	/**
	 * Logs the cold first step and the warm per-step time. The first epoch contains the cold step;
	 * the later epochs give the warm time per window (training step or validation pass).
	 *
	 * @param start          the time training started
	 * @param epochEnds      the time each epoch (including its validation pass) ended
	 * @param windowsPerEpoch training plus validation windows per epoch
	 */
	private void reportTimings(long start, List<Long> epochEnds, int windowsPerEpoch) {
		if (epochEnds.size() < 2) return;

		double first = (epochEnds.get(0) - start) / 1e9;
		double warmEpoch = (epochEnds.get(epochEnds.size() - 1) - epochEnds.get(0)) / 1e9 / (epochEnds.size() - 1);
		double warmWindow = warmEpoch / windowsPerEpoch;
		log("firstEpochSeconds=" + first + " warmEpochSeconds=" + warmEpoch +
				" warmSecondsPerWindow=" + warmWindow +
				" coldFirstStepSeconds=" + (first - warmWindow * (windowsPerEpoch - 1)));
	}

	/**
	 * Wraps a loss so that any non-finite loss value fails the run immediately, instead of being
	 * skipped ({@code NaN}) or applied ({@code Infinity}) by {@link ModelOptimizer}.
	 *
	 * @param loss the loss to wrap
	 * @return the fail-fast loss
	 */
	private LossProvider finiteLoss(LossProvider loss) {
		return new LossProvider() {
			@Override
			public double loss(PackedCollection output, PackedCollection target) {
				double value = loss.loss(output, target);
				if (!Double.isFinite(value)) {
					throw new AssertionError("Non-finite loss " + value);
				}

				return value;
			}

			@Override
			public Producer<PackedCollection> gradient(Producer<PackedCollection> output,
													   Producer<PackedCollection> target) {
				return loss.gradient(output, target);
			}
		};
	}
}
