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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;
import java.util.TreeSet;

/**
 * Pins the nucleus (top-p) sampling behavior exposed through the autoregressive generators, the
 * way {@link ArgmaxConsistencyTest} pins their greedy branch. Three host-side tests characterize
 * {@link AutoregressiveModel#sampleToken(PackedCollection, int, double, double, Random)} directly
 * over a crafted distribution; one test validates {@link AutoregressiveModel#setTopP(double)};
 * and one compiles both generators and proves they now sample identically from the same logits.
 *
 * <p>The crafted logits are the natural logs of {@link #PROBS}, so a temperature of {@code 1.0}
 * reproduces {@link #PROBS} exactly as the sampled distribution, which makes the nucleus
 * boundaries deterministic: at {@code topP == 0.5} the nucleus is {@code {0, 1}} (cumulative
 * {@code 0.4} then {@code 0.7}), at {@code 0.75} it is {@code {0, 1, 2}}, at {@code 0.95} it is
 * {@code {0, 1, 2, 3}}, and at {@code 1.0} it is the whole vocabulary.</p>
 */
public class NucleusSamplingTest extends TestSuiteBase {

	/** Vocabulary size of the crafted distribution. */
	private static final int VOCAB = 5;

	/** Descending probabilities whose logs form the crafted logits. */
	private static final double[] PROBS = { 0.4, 0.3, 0.2, 0.08, 0.02 };

	/** Seed used for every sampling run so the tests are deterministic. */
	private static final long SEED = 48821L;

	/** Number of draws per run; large enough that every in-nucleus token appears. */
	private static final int DRAWS = 40000;

	/** Rotary embedding base for the tiny models built by {@link #generatorsSampleIdentically()}. */
	private static final double ROPE_BASE = 10000.0;

	/**
	 * Returns the crafted logits: the natural logs of {@link #PROBS}, as literals (so that a
	 * temperature-{@code 1.0} softmax reproduces {@link #PROBS}, whose entries sum to one). The
	 * values are {@code ln(0.4)}, {@code ln(0.3)}, {@code ln(0.2)}, {@code ln(0.08)} and
	 * {@code ln(0.02)}; they are written as literals rather than computed with {@code Math.log}
	 * because the device-write policy forbids a method-call argument to {@code pack}.
	 *
	 * @return a {@code (VOCAB)} collection of logits
	 */
	private PackedCollection logits() {
		return pack(-0.916290731874155, -1.2039728043259361, -1.6094379124341003,
				-2.5257286443082556, -3.912023005428146);
	}

	/**
	 * Draws {@link #DRAWS} tokens from the crafted distribution at the given temperature and top-p
	 * with a fixed seed, and returns the per-token selection counts.
	 *
	 * @param temperature the sampling temperature
	 * @param topP        the nucleus threshold
	 * @return a {@code (VOCAB)} array of selection counts
	 */
	private int[] counts(double temperature, double topP) {
		PackedCollection logits = logits();
		Random random = new Random(SEED);
		int[] counts = new int[VOCAB];

		try {
			for (int i = 0; i < DRAWS; i++) {
				counts[AutoregressiveModel.sampleToken(logits, VOCAB, temperature, topP, random)]++;
			}
		} finally {
			logits.destroy();
		}

		return counts;
	}

	/**
	 * Returns the set of tokens that are reachable (selected at least once) at the given top-p.
	 *
	 * @param topP the nucleus threshold
	 * @return the reachable support
	 */
	private TreeSet<Integer> support(double topP) {
		int[] counts = counts(1.0, topP);
		TreeSet<Integer> support = new TreeSet<>();
		for (int i = 0; i < VOCAB; i++) {
			if (counts[i] > 0) support.add(i);
		}

		return support;
	}

	/**
	 * Tokens outside the nucleus are never selected: at {@code topP == 0.5} the nucleus is
	 * {@code {0, 1}}, so tokens 2, 3 and 4 have zero probability and never appear, while both
	 * nucleus tokens do.
	 */
	@Test(timeout = 60000)
	public void outOfNucleusNeverSelected() {
		int[] counts = counts(1.0, 0.5);

		Assert.assertEquals(0, counts[2]);
		Assert.assertEquals(0, counts[3]);
		Assert.assertEquals(0, counts[4]);
		Assert.assertTrue(counts[0] > 0);
		Assert.assertTrue(counts[1] > 0);
	}

	/**
	 * Tightening {@code p} shrinks the reachable support monotonically, and each smaller nucleus is
	 * nested inside the larger one.
	 */
	@Test(timeout = 60000)
	public void tighteningTopPShrinksSupportMonotonically() {
		TreeSet<Integer> whole = support(1.0);
		TreeSet<Integer> wide = support(0.95);
		TreeSet<Integer> narrow = support(0.75);
		TreeSet<Integer> tight = support(0.5);

		Assert.assertEquals(5, whole.size());
		Assert.assertEquals(4, wide.size());
		Assert.assertEquals(3, narrow.size());
		Assert.assertEquals(2, tight.size());

		Assert.assertTrue(whole.containsAll(wide));
		Assert.assertTrue(wide.containsAll(narrow));
		Assert.assertTrue(narrow.containsAll(tight));
	}

	/**
	 * With {@code topP == 1.0} no filtering is applied, so the sampled frequencies reproduce the
	 * unfiltered tempered distribution ({@link #PROBS} at temperature {@code 1.0}), including the
	 * low-probability tail token that nucleus filtering would remove.
	 */
	@Test(timeout = 60000)
	public void topPOneReproducesUnfilteredDistribution() {
		int[] counts = counts(1.0, 1.0);

		for (int i = 0; i < VOCAB; i++) {
			double frequency = counts[i] / (double) DRAWS;
			Assert.assertEquals("token " + i, PROBS[i], frequency, 0.02);
		}
	}

	/**
	 * {@link AutoregressiveModel#setTopP(double)} defaults to {@code 1.0}, accepts values in
	 * {@code (0, 1]} including the {@code 1.0} boundary, and rejects values outside that range,
	 * including {@code 0}, negatives, values above {@code 1} and {@code NaN}.
	 */
	@Test(timeout = 60000)
	public void setTopPValidatesRangeAndDefaults() {
		PackedCollection position = new PackedCollection(1);
		PackedCollection temperature = new PackedCollection(1);

		try (AutoregressiveModel<Integer> model =
					 new AutoregressiveModel<>(position, null, null, null, temperature)) {
			Assert.assertEquals(1.0, model.getTopP(), 0.0);

			model.setTopP(0.5);
			Assert.assertEquals(0.5, model.getTopP(), 0.0);

			model.setTopP(1.0);
			Assert.assertEquals(1.0, model.getTopP(), 0.0);

			assertRejected(() -> model.setTopP(0.0));
			assertRejected(() -> model.setTopP(-0.1));
			assertRejected(() -> model.setTopP(1.0001));
			assertRejected(() -> model.setTopP(Double.NaN));

			// a rejected call leaves the previous value in place
			Assert.assertEquals(1.0, model.getTopP(), 0.0);
		} finally {
			position.destroy();
			temperature.destroy();
		}
	}

	/**
	 * At {@code temperature > 0}, {@link SlidingWindowAutoregressiveModel} and
	 * {@link AutoregressiveModel#of(CompiledModel, PackedCollection, java.util.function.IntFunction, Random)}
	 * route the same logits through {@link AutoregressiveModel#sampleToken} with the same
	 * temperature and top-p, so, given {@link Random}s started from the same seed, they select the
	 * same token on every draw. This guards against the return of the {@code of(...)}
	 * double-softmax discrepancy. Sampling, not greedy, is the unified path: the crafted logits are
	 * spread so that several tokens are reachable, confirming the agreement exercises real
	 * sampling rather than collapsing to one token.
	 *
	 * <p>The generators are fed crafted logits through their {@code protected sample} method rather
	 * than a forward pass, so no agreement between the two models' outputs is assumed; only the
	 * shared sampling path is compared.</p>
	 */
	@Test(timeout = 5 * 60000)
	public void generatorsSampleIdentically() {
		int vocab = 7;
		int dim = 4;
		int heads = 1;
		int ffDim = 4;
		double temperature = 0.8;
		double topP = 0.9;
		int draws = 5000;

		CausalLanguageModel slidingModel =
				new CausalLanguageModel(vocab, 1, dim, heads, 0, ffDim, ROPE_BASE, new Random(1));
		CausalLanguageModel factoryModel =
				new CausalLanguageModel(vocab, 1, dim, heads, 0, ffDim, ROPE_BASE, new Random(2));

		PackedCollection position = new PackedCollection(1);
		PackedCollection embedBuffer = new PackedCollection(factoryModel.getInputShape());
		PackedCollection logits = pack(2.0, 1.5, 1.0, 0.5, 0.0, -1.0, -2.0);

		CompiledModel factoryInference = null;
		SlidingWindowAutoregressiveModel sliding = null;
		AutoregressiveModel<Integer> factory = null;

		try {
			sliding = new SlidingWindowAutoregressiveModel(slidingModel, new Random(SEED));
			factoryInference = factoryModel.compile(false);
			factory = AutoregressiveModel.of(factoryInference, position, t -> embedBuffer, new Random(SEED));

			sliding.setTemperature(temperature);
			sliding.setTopP(topP);
			factory.setTemperature(temperature);
			factory.setTopP(topP);

			int[] slidingDraws = new int[draws];
			int[] factoryDraws = new int[draws];
			TreeSet<Integer> distinct = new TreeSet<>();

			for (int i = 0; i < draws; i++) {
				slidingDraws[i] = sliding.sample(logits);
				factoryDraws[i] = factory.sample(logits);
				distinct.add(slidingDraws[i]);
			}

			Assert.assertArrayEquals(slidingDraws, factoryDraws);
			Assert.assertTrue("sampling collapsed to a single token", distinct.size() > 1);
		} finally {
			if (sliding != null) sliding.destroy();
			if (factory != null) factory.destroy();
			if (factoryInference != null) factoryInference.destroy();
			position.destroy();
			embedBuffer.destroy();
			logits.destroy();
			slidingModel.getWeights().destroy();
			factoryModel.getWeights().destroy();
		}
	}

	/**
	 * Fails unless {@code action} throws {@link IllegalArgumentException}.
	 *
	 * @param action the action expected to be rejected
	 */
	private void assertRejected(Runnable action) {
		try {
			action.run();
			Assert.fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
