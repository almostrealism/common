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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
 * windowing ({@link NextTokenDataset#split}); windows are non-overlapping, and the training set
 * {@link NextTokenDataset#setRotating rotates}, so each capped epoch reads the next stretch of the
 * training region while the held-out windows stay fixed. The run needs a device memory ceiling of at
 * least {@code AR_HARDWARE_MEMORY_SCALE=6}.</p>
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
	private static final double LEARNING_RATE = 3e-3;
	/** Adam learning rate of the last epoch; the rate decays linearly in between. */
	private static final double FINAL_LEARNING_RATE = 3e-4;
	/** Stride of the training windows (non-overlapping). */
	private static final int TRAIN_STRIDE = SEQ_LEN;
	/** Stride of the held-out windows (non-overlapping). */
	private static final int HELD_OUT_STRIDE = SEQ_LEN;
	/** Training windows per epoch; the training set rotates, so each epoch sees new windows. */
	private static final int TRAIN_WINDOWS = 110;
	/** Maximum held-out windows evaluated at each epoch boundary. */
	private static final int HELD_OUT_WINDOWS = 12;
	/** Epochs passed to {@link ModelOptimizer#optimize(int)}. */
	private static final int EPOCHS = 5;
	/** Fraction of the corpus bytes in the training region. */
	private static final double TRAIN_FRACTION = 0.9;
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
		ParameterUpdate<PackedCollection> recorder = (name, weights, gradient) -> {
			PackedCollection buffer = new PackedCollection(shape(weights));
			recordedWeights.add(weights);
			recordedGradients.add(buffer);
			return a(name + " (recorded gradient)", p(buffer.each()), c(gradient).reshape(buffer.getShape()).each());
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
			for (int i = 0; i < weight.getMemLength(); i++) {
				double original = weight.toDouble(i);
				weight.setMem(i, original + eps);
				double plus = loss.evaluate(compiled.forward(ids), outputGradient).toDouble();
				weight.setMem(i, original - eps);
				double minus = loss.evaluate(compiled.forward(ids), outputGradient).toDouble();
				weight.setMem(i, original);

				double numeric = (plus - minus) / (2 * eps);
				maxAbs = Math.max(maxAbs, Math.abs(numeric));
				maxDiff = Math.max(maxDiff, Math.abs(numeric - analytic.toDouble(i)));
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
	 * {@link ModelOptimizer} and requires its held-out loss to end below the unigram byte-entropy
	 * baseline of the held-out region. Every training and validation loss passes through a
	 * fail-fast wrapper that rejects non-finite values, so no window is silently skipped. The
	 * trained weights are saved, reloaded, and must reproduce the held-out loss.
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
		NextTokenDataset trainWindows = new NextTokenDataset(tokens, train.getStart(), train.getEnd(),
				VOCAB, SEQ_LEN, TRAIN_STRIDE, TRAIN_WINDOWS).setRotating(true);
		NextTokenDataset heldOut = new NextTokenDataset(tokens, heldOutRegion.getStart(), heldOutRegion.getEnd(),
				VOCAB, SEQ_LEN, HELD_OUT_STRIDE, HELD_OUT_WINDOWS);

		double unigramBits = heldOut.unigramEntropyBits();
		log("corpus bytes=" + tokens.length + " trainRegion=[" + train.getStart() + ", " + train.getEnd() +
				") heldOutRegion=[" + heldOut.getStart() + ", " + heldOut.getEnd() + ")");
		log("trainStride=" + TRAIN_STRIDE + " heldOutStride=" + HELD_OUT_STRIDE +
				" trainWindowsPerEpoch=" + trainWindows.getWindowCount() +
				" (of " + trainWindows.getAvailableWindowCount() + ", rotating)" +
				" heldOutWindows=" + heldOut.getWindowCount() +
				" epochs=" + EPOCHS);
		log("baselines: uniformBitsPerByte=8.0 unigramBitsPerByte=" + unigramBits);
		log("host=" + System.getProperty("os.name") + " " + System.getProperty("os.arch") +
				" precision=" + Hardware.getLocalHardware().getPrecision() + " seed=" + SEED +
				" learningRate=" + LEARNING_RATE + "->" + FINAL_LEARNING_RATE + " betas=0.9/0.999 ffDim=" + FF_DIM + " ropeBase=" + ROPE_BASE);

		CausalLanguageModel lm = newModel();
		log("parameters=" + lm.getParameterCount());
		PackedCollection learningRate = PackedCollection.of(LEARNING_RATE);
		Model model = lm.buildModel(new AdamOptimizer(cp(learningRate), c(0.9), c(0.999)));

		long compileStart = System.nanoTime();
		CompiledModel compiled = model.compile(true);
		log("compileSeconds=" + (System.nanoTime() - compileStart) / 1e9);

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
			double progressFraction = (progress.getEpoch() + 1) / (double) (EPOCHS - 1);
			learningRate.setMem(0, LEARNING_RATE +
					(FINAL_LEARNING_RATE - LEARNING_RATE) * Math.min(1.0, progressFraction));
		});

		TrainingResult result = optimizer.optimize(EPOCHS);
		reportTimings(trainingStart, epochEnds, trainWindows.getWindowCount() + heldOut.getWindowCount());

		List<Double> heldOutHistory = result.getValidationLossHistory();
		Assert.assertFalse(heldOutHistory.isEmpty());
		double finalBits = heldOutHistory.get(heldOutHistory.size() - 1) / Math.log(2);
		log("totalSteps=" + result.getEpochsCompleted() * trainWindows.getWindowCount() +
				" epochsRun=" + result.getEpochsCompleted() +
				" finalHeldOutBitsPerByte=" + finalBits + " unigramBitsPerByte=" + unigramBits);
		Assert.assertTrue("held-out loss " + finalBits + " bits/byte did not beat the unigram baseline " +
				unigramBits, finalBits < unigramBits);

		Path weightsDir = Path.of("results", "causal-language-model");
		Files.createDirectories(weightsDir);
		lm.getWeights().save(weightsDir.resolve("weights.pb"));

		CausalLanguageModel reloaded = new CausalLanguageModel(VOCAB, SEQ_LEN, DIM, HEADS, DEPTH, FF_DIM,
				new StateDictionary(weightsDir.toString()));
		ModelOptimizer evaluator = new ModelOptimizer(
				reloaded.buildModel(ParameterUpdate.disabled()).compile(false), () -> heldOut);
		evaluator.setLossFunction(finiteLoss(new NegativeLogLikelihood()));
		double reloadedBits = evaluator.evaluate(heldOut) / Math.log(2);
		double trainedBits = optimizer.evaluate(heldOut) / Math.log(2);
		log("reloadedHeldOutBitsPerByte=" + reloadedBits + " trainedHeldOutBitsPerByte=" + trainedBits);
		Assert.assertEquals(trainedBits, reloadedBits, RELOAD_TOLERANCE * trainedBits);
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
