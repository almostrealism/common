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
import org.almostrealism.model.Model;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
 * prompt through {@link CausalLanguageModel#generator}, which is logged.</p>
 */
public class CausalLanguageModelTest extends TestSuiteBase implements ModelTestFeatures {
	/** Byte vocabulary size. */
	private static final int VOCAB = ByteTokenizer.VOCAB_SIZE;
	/** Positions per window. */
	private static final int SEQ_LEN = 64;
	/** Model dimension. */
	private static final int DIM = 64;
	/** Attention heads. */
	private static final int HEADS = 4;
	/** Transformer blocks. */
	private static final int DEPTH = 2;
	/** Feed-forward hidden width (2 x embed). */
	private static final int FF_DIM = 128;
	/** Rotary embedding base. */
	private static final double ROPE_BASE = 10000.0;
	/** Seed of the weight initialization. */
	private static final long SEED = 20260930L;
	/** Adam learning rate of the first epoch. */
	private static final double LEARNING_RATE = 1e-2;
	/** Adam learning rate of the last epoch; the rate decays linearly in between. */
	private static final double FINAL_LEARNING_RATE = 1e-3;
	/** Stride of the held-out windows (non-overlapping). */
	private static final int HELD_OUT_STRIDE = SEQ_LEN;
	/**
	 * Training windows per epoch; the training set rotates, so each epoch sees new windows, and
	 * the training stride is chosen so that the windows of all epochs together span the whole
	 * training region once ({@link NextTokenDataset#spanningStride}).
	 */
	private static final int TRAIN_WINDOWS = 110;
	/** Maximum held-out windows evaluated at each epoch boundary, for the progress curve only. */
	private static final int HELD_OUT_WINDOWS = 12;
	/** Epochs passed to {@link ModelOptimizer#optimize(int)}. */
	private static final int EPOCHS = 5;
	/** Fraction of the corpus bytes in the training region. */
	private static final double TRAIN_FRACTION = 0.9;
	/** The documentation-style prompt the trained model continues. */
	private static final String GENERATION_PROMPT = "The computation graph is compiled to ";
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
		Map<String, TraversalPolicy> shapes = fresh.getWeightShapes();
		int perBlockKeys = 6;
		int sharedKeys = 4;
		Assert.assertEquals(sharedKeys + perBlockKeys * DEPTH, shapes.size());
		Assert.assertEquals(shapes.keySet(), fresh.getWeights().keySet());
		Assert.assertArrayEquals(new int[] { 3 * DIM, DIM }, shapes.get(fresh.layerKey(0, "qkv")).extent());
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
		transposed.put(fresh.layerKey(0, "w2"), new PackedCollection(shape(FF_DIM, DIM)));
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
		Assert.assertEquals(expected, minimal.getParameterCount());

		Map<String, PackedCollection> extended = new HashMap<>(minimal.getWeights().getAllWeights());
		extended.put("unused.extra", new PackedCollection(shape(32)));
		CausalLanguageModel reloaded = new CausalLanguageModel(vocab, 2, dim, 1, 0, 1,
				new StateDictionary(extended));
		Assert.assertEquals(expected, reloaded.getParameterCount());
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
		Model model = lm.buildModel(ParameterUpdate.disabled());
		assertOutputShape(model.getOutputShape());
		assertOutputShape(lm.getOutputShape());

		CompiledModel compiled = model.compile(false);
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
		ParameterUpdate<PackedCollection> gradients = gradientRecorder(recordedGradients);
		ParameterUpdate<PackedCollection> recorder = (name, weights, gradient) -> {
			recordedWeights.add(weights);
			return gradients.apply(name, weights, gradient);
		};

		CompiledModel compiled = lm.buildModel(recorder).compile(true);
		PackedCollection ids = PackedCollection.of(3, 7, 1, 12);
		PackedCollection outputGradient = randn(shape(seq, vocab), 0.0, 1.0, new Random(4)).evaluate();
		compiled.forward(ids);
		compiled.backward(outputGradient);

		Evaluable<PackedCollection> loss = multiply(cv(shape(seq, vocab), 0), cv(shape(seq, vocab), 1)).sum().get();
		double eps = 5e-3;

		for (String key : lm.getWeights().keySet()) {
			if (CausalLanguageModel.INV_FREQ_KEY.equals(key)) continue;

			PackedCollection weight = lm.getWeights().get(key);
			PackedCollection analytic = null;
			for (int i = 0; i < recordedWeights.size(); i++) {
				if (recordedWeights.get(i).get().evaluate().getMem() == weight.getMem()) {
					analytic = recordedGradients.get(i);
				}
			}

			Assert.assertNotNull("no gradient was delivered to " + key, analytic);

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
		log("parameters=" + lm.getParameterCount());
		PackedCollection learningRate = PackedCollection.of(LEARNING_RATE);
		Model model = lm.buildModel(new AdamOptimizer(cp(learningRate), c(0.9), c(0.999)));
		Runnable decayLearningRate = a(p(learningRate), max(
				cp(learningRate).add((FINAL_LEARNING_RATE - LEARNING_RATE) / (EPOCHS - 1)),
				c(FINAL_LEARNING_RATE))).get();

		CompiledModel compiled = null;
		try {
			long compileStart = System.nanoTime();
			compiled = model.compile(true);
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
			reloadedCompiled = reloaded.buildModel(ParameterUpdate.disabled()).compile(false);
			ModelOptimizer evaluator = new ModelOptimizer(reloadedCompiled, () -> heldOut);
			evaluator.setLossFunction(finiteLoss(new NegativeLogLikelihood()));
			double reloadedBits = evaluator.evaluate(heldOut) / Math.log(2);
			double trainedBits = optimizer.evaluate(heldOut) / Math.log(2);
			log("reloadedHeldOutBitsPerByte=" + reloadedBits + " trainedHeldOutBitsPerByte=" + trainedBits);
			Assert.assertEquals(trainedBits, reloadedBits, RELOAD_TOLERANCE * trainedBits);

			generated = generateSample(reloaded, reloadedCompiled, GENERATION_PROMPT, GENERATED_BYTES);
		} finally {
			Destroyable.destroy(reloadedCompiled);
			reloadedWeights.destroy();
		}

		assertNonTrivialGeneration(new ByteTokenizer().encodeAsInt(GENERATION_PROMPT), generated[0], generated[1]);
		// TODO(review): the documented configuration (all 83 held-out windows: 4.957 vs scored baseline 4.912) fails this
		// assertion; the step budget (~550 steps at ~3.6 s each, dominated by Jacobian-based weight gradients) is the limit
		Assert.assertTrue("held-out loss " + finalBits + " bits/byte did not beat the unigram baseline " +
				unigramBits, finalBits < unigramBits);
	}

	/**
	 * The sliding-window generator decodes exactly the full-sequence forward pass: on a miniature
	 * configuration with random weights, every greedily generated token, including those generated
	 * after the sequence outgrows the context and the window slides, is the most probable token of
	 * the last filled row of the inference model run over the preceding (at most {@code seqLen})
	 * tokens, with the remaining positions padded by a different filler than the generator uses,
	 * which the causal mask must ignore. Greedy decoding is reproducible after
	 * {@link AutoregressiveModel#reset()}, and a compiled model of another configuration is
	 * rejected.
	 */
	@Test(timeout = 15 * 60000)
	public void slidingWindowGenerationMatchesFullForward() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));
		CompiledModel inference = lm.buildModel(ParameterUpdate.disabled()).compile(false);

		try {
			assertRejected(() -> new CausalLanguageModel(VOCAB, seq + 1, 8, 2, 1, 8, lm.getWeights())
					.generator(inference, null));

			int[] prompt = new ByteTokenizer().encodeAsInt("Producer");
			int generated = 2 * seq;
			int[] first;
			int[] second;
			try (AutoregressiveModel<Integer> generator = lm.generator(inference, null)) {
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
	 * The generator accepts only a model with exactly this configuration's single
	 * {@code (seqLen)} input and {@code (seqLen, vocab)} output: a model whose input has the same
	 * number of elements but another shape, or whose output is the transposed
	 * {@code (vocab, seqLen)}, is rejected rather than decoded with its rows misread.
	 */
	@Test(timeout = 5 * 60000)
	public void generatorRejectsMismatchedShapes() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));
		PackedCollection table = new PackedCollection(shape(VOCAB, VOCAB));

		Model batched = new Model(shape(1, seq));
		batched.add(lm.reshape(shape(1, seq), shape(seq)));
		batched.add(lm.embedding(shape(seq), table));

		Model transposed = new Model(shape(seq));
		transposed.add(lm.embedding(shape(seq), table));
		transposed.add(lm.reshape(shape(seq, VOCAB), shape(VOCAB, seq)));

		CompiledModel batchedCompiled = null;
		CompiledModel transposedCompiled = null;
		try {
			batchedCompiled = batched.compile(false);
			transposedCompiled = transposed.compile(false);
			Assert.assertEquals(seq, batchedCompiled.getInputShape().getTotalSize());
			Assert.assertEquals(seq * VOCAB, transposedCompiled.getOutputShape().getTotalSize());

			CompiledModel rejectedBatched = batchedCompiled;
			CompiledModel rejectedTransposed = transposedCompiled;
			assertRejected(() -> lm.generator(rejectedBatched, null));
			assertRejected(() -> lm.generator(rejectedTransposed, null));
		} finally {
			Destroyable.destroy(batchedCompiled);
			Destroyable.destroy(transposedCompiled);
			table.destroy();
			lm.getWeights().destroy();
		}
	}

	/**
	 * Destroying a generator releases the buffers it decodes with, including its position, and
	 * leaves the inference model usable for another generator; a second destroy is harmless.
	 */
	@Test(timeout = 5 * 60000)
	public void destroyingGeneratorReleasesItsBuffers() {
		int seq = 8;
		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, seq, 8, 2, 1, 8, ROPE_BASE, new Random(5));
		CompiledModel inference = lm.buildModel(ParameterUpdate.disabled()).compile(false);

		try {
			int[] prompt = new ByteTokenizer().encodeAsInt("Pro");
			AutoregressiveModel<Integer> generator = lm.generator(inference, null);
			int[] before = generate(generator, prompt, 2);
			PackedCollection position = generator.getPosition();
			Assert.assertFalse(position.isDestroyed());

			generator.destroy();
			Assert.assertTrue("generator position was not released", position.isDestroyed());
			generator.destroy();

			try (AutoregressiveModel<Integer> next = lm.generator(inference, null)) {
				Assert.assertArrayEquals(before, generate(next, prompt, 2));
			}
		} finally {
			inference.destroy();
			lm.getWeights().destroy();
		}
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
		CompiledModel inference = lm.buildModel(ParameterUpdate.disabled()).compile(false);
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
	 * @param lm        the trained model
	 * @param inference its compiled inference model
	 * @param prompt    the text to continue
	 * @param length    the number of bytes to generate
	 * @return the two continuations
	 */
	private int[][] generateSample(CausalLanguageModel lm, CompiledModel inference, String prompt, int length) {
		int[] promptTokens = new ByteTokenizer().encodeAsInt(prompt);
		int[] first;
		int[] second;
		double seconds;
		try (AutoregressiveModel<Integer> generator = lm.generator(inference, null)) {
			long start = System.nanoTime();
			first = generate(generator, promptTokens, length);
			seconds = (System.nanoTime() - start) / 1e9;
			second = generate(generator, promptTokens, length);
		}

		byte[] bytes = new byte[first.length];
		IntStream.range(0, first.length).forEach(i -> bytes[i] = (byte) first[i]);
		String validity;
		try {
			StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes));
			validity = "valid";
		} catch (CharacterCodingException e) {
			validity = "invalid (" + e.getMessage() + ")";
		}

		log("generationSeconds=" + seconds + " bytesPerSecond=" + (promptTokens.length + length) / seconds +
				" utf8=" + validity);
		log("generated=" + prompt + new String(bytes, StandardCharsets.UTF_8));
		return new int[][] { first, second };
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
	private int[] generate(AutoregressiveModel<Integer> generator, int[] prompt, int length) {
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
