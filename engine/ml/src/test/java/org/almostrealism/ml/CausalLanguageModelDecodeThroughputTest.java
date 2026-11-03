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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.ml.tokenization.ByteTokenizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Measures the two ways a {@link CausalLanguageModel} decodes text against each other on the same
 * weights: the {@link SlidingWindowAutoregressiveModel sliding-window generator}, which runs a full
 * {@code (seqLen, vocabSize)} forward pass per byte, and the
 * {@link KeyValueCacheAutoregressiveModel key/value-cache generator}, which decodes one position per
 * byte against the cached keys and values of the earlier positions.
 *
 * <p>The model has the configuration of the documentation-trained model of
 * {@code CausalLanguageModelTest.trainOnDocumentation} (byte vocabulary 256, context 64, embed 64,
 * 4 heads, depth 2, feed-forward width 128) with random weights; decode cost does not depend on the
 * weight values, so the absolute throughput is comparable to that model's. Both generators continue
 * the same documentation prompt to the full context length, the longest sequence the cache holds.
 * Each is run once to warm up (first-call kernel loading is not steady-state throughput) and then
 * {@code AR_PROFILE_DECODES} times (default 3) under the measurement.</p>
 *
 * <p>The test reports, per decoded byte, the wall time, the number of kernel invocations the
 * operation profile recorded ({@link OperationProfileNode#getRunCount()}), and the Metal dispatch and
 * command-buffer commit counts (zero on other backends), with the operations whose host waits forced
 * those commits; it reports the cache's speedup and its kernel-invocation and dispatch ratios, and
 * writes the profile to {@code results/causal_lm_decode_throughput.xml}. Wall-clock time, dispatches
 * and commits are reported, not asserted: they vary by node and backend. A model this small is bound
 * by the cost of each kernel, dispatch and host wait rather than by arithmetic, so whether the
 * cache's saving (one position per byte rather than a {@code seqLen}-wide window) shows up as
 * throughput is what this benchmark measures. What is asserted is backend-independent: both
 * generators decode the same greedy continuation, so the two sets of figures describe the same
 * decode; the profile recorded the kernels each path ran; and the cache path invokes strictly fewer
 * kernels per byte than the sliding window, the work count its throughput advantage rests on.</p>
 *
 * <p>The cache path is compiled with the accelerator {@link CausalLanguageModel#setComputeRequirements
 * required} for its whole pass when one is available (see {@link #requireAccelerator}); the sliding
 * window is compiled without requirements.</p>
 *
 * <p>A second measurement runs the same comparison three times past the context length against the
 * {@link SlidingKeyValueCacheAutoregressiveModel rolling key/value cache}, which decodes past
 * {@code seqLen} where the bounded cache stops, and writes its profile to
 * {@code results/causal_lm_long_decode_throughput.xml}.</p>
 *
 * <p>Another test continues the same prompt through the cache path greedily and with nucleus (top-p)
 * sampling, showing that the two sampling modes compose with the cache. Over random weights the
 * continuations are not prose; generation quality is owned by {@code trainOnDocumentation}.</p>
 */
public class CausalLanguageModelDecodeThroughputTest extends TestSuiteBase {
	/** Seed of the random weights. */
	private static final long SEED = 20261028L;

	/** Positive sampling temperature of the nucleus continuation. */
	private static final double TEMPERATURE = 0.8;

	/** Nucleus (top-p) threshold of the nucleus continuation. */
	private static final double TOP_P = 0.9;

	/** Number of commit-forcing operations logged per generator. */
	private static final int LOGGED_COMMIT_CAUSES = 8;

	/** Length of the long-run decode, in context lengths: long enough for the rolling caches to wrap twice. */
	private static final int LONG_RUN_CONTEXTS = 3;

	/** Wall-clock and work counts of one generator's measured decoding. */
	private record Measurement(int[] continuation, double bytesPerSecond, double runsPerByte,
							   double dispatchesPerByte, double commitsPerByte) { }

	/**
	 * Decodes the same prompt to the full context with both generators, reports throughput and work
	 * per byte for each and the cache's speedup, and requires both to decode the same greedy
	 * continuation with the kernels of each recorded by the profile, and the cache path to invoke
	 * fewer kernels per byte than the sliding window.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 20 * 60000)
	public void keyValueCacheDecodeThroughput() throws IOException {
		int[] prompt = new ByteTokenizer().encodeAsInt(CausalLanguageModelTest.GENERATION_PROMPT);
		Measurement[] measured = assertFewerKernelsThanSlidingWindow("causal_lm_decode_throughput",
				"keyValueCache", prompt, CausalLanguageModelTest.SEQ_LEN - prompt.length,
				lm -> new KeyValueCacheAutoregressiveModel(lm, null));

		Assert.assertArrayEquals("the two decode paths disagree on the greedy continuation",
				measured[0].continuation(), measured[1].continuation());
	}

	/**
	 * Decodes the same prompt to {@link #LONG_RUN_CONTEXTS} times the context length with the sliding
	 * window and the {@link SlidingKeyValueCacheAutoregressiveModel rolling key/value cache}, the
	 * long-form generation the bounded cache cannot reach: past {@code seqLen} the sliding window pays a
	 * full {@code (seqLen, vocabSize)} forward pass per byte and the rolling cache one position. It
	 * reports throughput and work per byte for each and the rolling cache's speedup, and requires the
	 * kernels of each to have been profiled and the rolling cache to invoke fewer kernels per byte.
	 *
	 * <p>Within the context both paths decode the full forward pass, so their continuations must agree
	 * there. Whether they agree past {@code seqLen} is reported, not asserted. This model has two
	 * blocks, and past {@code seqLen} the rolling cache keeps the second block's keys and values of each
	 * retained token as that token was decoded, while the sliding window recomputes them over its
	 * current window only; the two are the same decode only for a single block, which
	 * {@code SlidingKeyValueCacheAutoregressiveModelTest} pins.</p>
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 20 * 60000)
	public void slidingKeyValueCacheLongDecodeThroughput() throws IOException {
		int[] prompt = new ByteTokenizer().encodeAsInt(CausalLanguageModelTest.GENERATION_PROMPT);
		Measurement[] measured = assertFewerKernelsThanSlidingWindow("causal_lm_long_decode_throughput",
				"slidingKeyValueCache", prompt,
				LONG_RUN_CONTEXTS * CausalLanguageModelTest.SEQ_LEN - prompt.length,
				lm -> new SlidingKeyValueCacheAutoregressiveModel(lm, null));

		int[] window = measured[0].continuation();
		int[] rolling = measured[1].continuation();
		int withinContext = CausalLanguageModelTest.SEQ_LEN - prompt.length;
		Assert.assertArrayEquals("within the context both paths decode the full forward pass",
				Arrays.copyOf(window, withinContext), Arrays.copyOf(rolling, withinContext));

		int agreeing = (int) IntStream.range(0, window.length).filter(i -> window[i] == rolling[i]).count();
		int firstDisagreement = IntStream.range(0, window.length)
				.filter(i -> window[i] != rolling[i]).findFirst().orElse(-1);
		log("agreeingTokens=" + agreeing + " of " + window.length
				+ " firstDisagreement=" + firstDisagreement);
	}

	/**
	 * Measures the sliding window and a key/value-cache generator decoding the same prompt over one model
	 * of the documentation-trained configuration, logs the cache's speedup and its kernel-invocation
	 * and dispatch ratios, writes the profile to {@code results/<profileName>.xml}, and requires both
	 * paths' kernels to have been profiled and the cache path to invoke strictly fewer kernels per byte.
	 *
	 * @param profileName the name of the profile and of the file it is written to
	 * @param cacheName   the label of the cache generator in the log
	 * @param prompt      the prompt tokens
	 * @param length      the number of tokens to generate after the prompt
	 * @param cache       creates the cache generator over a model that requires the accelerator when
	 *                    one is available
	 * @return the sliding window's measurement followed by the cache generator's
	 * @throws IOException if the profile cannot be written
	 */
	private Measurement[] assertFewerKernelsThanSlidingWindow(
			String profileName, String cacheName, int[] prompt, int length,
			Function<CausalLanguageModel, AutoregressiveModel<Integer>> cache) throws IOException {
		int repeats = Integer.parseInt(System.getProperty("AR_PROFILE_DECODES", "3"));
		CausalLanguageModel lm = newModel();

		OperationProfileNode profile = new OperationProfileNode(profileName);
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			Measurement sliding = measure("slidingWindow", profile, prompt, length, repeats,
					() -> new SlidingWindowAutoregressiveModel(
							new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null));
			Measurement cached = measure(cacheName, profile, prompt, length, repeats,
					() -> cache.apply(requireAccelerator(new CausalLanguageModel(lm.getConfig(), lm.getWeights()))));

			log("speedup=" + cached.bytesPerSecond() / sliding.bytesPerSecond()
					+ " runsPerByteRatio=" + sliding.runsPerByte() / cached.runsPerByte()
					+ " dispatchesPerByteRatio=" + sliding.dispatchesPerByte() / cached.dispatchesPerByte());

			Assert.assertTrue("no sliding-window kernel invocations were profiled", sliding.runsPerByte() > 0);
			Assert.assertTrue("no " + cacheName + " kernel invocations were profiled", cached.runsPerByte() > 0);
			Assert.assertTrue("the " + cacheName + " decode invokes " + cached.runsPerByte()
							+ " kernels per byte, not fewer than the sliding window's " + sliding.runsPerByte(),
					cached.runsPerByte() < sliding.runsPerByte());
			return new Measurement[] { sliding, cached };
		} finally {
			Hardware.getLocalHardware().clearProfile();
			lm.getWeights().destroy();
			profile.save("results/" + profileName + ".xml");
		}
	}

	/**
	 * Continues the documentation prompt through the key/value-cache path greedily and with nucleus
	 * sampling at a positive temperature, logging both continuations and whether each is valid UTF-8.
	 * Validity is reported rather than asserted, since a byte-level model may emit a partial
	 * multi-byte sequence; the nucleus continuation must stay within the byte vocabulary.
	 */
	@Test(timeout = 20 * 60000)
	public void greedyAndNucleusDecodeThroughCache() {
		ByteTokenizer tokenizer = new ByteTokenizer();
		int[] prompt = tokenizer.encodeAsInt(CausalLanguageModelTest.GENERATION_PROMPT);
		int length = CausalLanguageModelTest.SEQ_LEN - prompt.length;
		CausalLanguageModel lm = requireAccelerator(newModel());

		try (AutoregressiveModel<Integer> generator = new KeyValueCacheAutoregressiveModel(lm, new Random(SEED))) {
			int[] greedy = CausalLanguageModelTest.generate(generator, prompt, length);
			generator.setTemperature(TEMPERATURE);
			generator.setTopP(TOP_P);
			int[] nucleus = CausalLanguageModelTest.generate(generator, prompt, length);

			log("greedy utf8=" + (tokenizer.isValidUtf8(greedy) ? "valid" : "invalid")
					+ " generated=" + CausalLanguageModelTest.GENERATION_PROMPT + tokenizer.decodeAsInt(greedy));
			log("nucleus temperature=" + TEMPERATURE + " topP=" + TOP_P
					+ " utf8=" + (tokenizer.isValidUtf8(nucleus) ? "valid" : "invalid")
					+ " generated=" + CausalLanguageModelTest.GENERATION_PROMPT + tokenizer.decodeAsInt(nucleus));

			Assert.assertEquals(length, nucleus.length);
			Assert.assertTrue("nucleus sampling produced a token outside the byte vocabulary",
					IntStream.of(nucleus).allMatch(t -> t >= 0 && t < ByteTokenizer.VOCAB_SIZE));
		} finally {
			lm.getWeights().destroy();
		}
	}

	/**
	 * Builds a generator, decodes once to warm it up, then decodes {@code repeats} more times while
	 * measuring wall time, profiled kernel invocations and Metal dispatches and commits, and logs the
	 * per-byte figures together with the operations whose host waits forced the most commits during
	 * the measurement. Every step of a decode runs one forward pass, so the prompt bytes count as
	 * decoded bytes alongside the generated ones.
	 *
	 * @param name      the label of the generator in the log
	 * @param profile   the operation profile assigned to the hardware
	 * @param prompt    the prompt tokens
	 * @param length    the number of tokens to generate after the prompt
	 * @param repeats   the number of measured decodes
	 * @param generator creates the generator; called with the profile assigned, so its compilation is
	 *                  recorded
	 * @return the warm-up continuation and the measured figures
	 */
	private Measurement measure(String name, OperationProfileNode profile, int[] prompt, int length,
								int repeats, Supplier<AutoregressiveModel<Integer>> generator) {
		try (AutoregressiveModel<Integer> decoder = generator.get()) {
			int[] continuation = CausalLanguageModelTest.generate(decoder, prompt, length);

			long runsBefore = profile.getRunCount();
			Map<String, Integer> waitsBefore = hostCompleteWaits();
			MetalCommandRunner.resetBatchSizeCounters();
			long start = System.nanoTime();
			IntStream.range(0, repeats).forEach(i -> CausalLanguageModelTest.generate(decoder, prompt, length));
			double seconds = (System.nanoTime() - start) / 1e9;

			double bytes = repeats * (double) (prompt.length + length);
			Measurement measurement = new Measurement(continuation, bytes / seconds,
					(profile.getRunCount() - runsBefore) / bytes,
					MetalCommandRunner.totalDispatchCount.get() / bytes,
					MetalCommandRunner.totalCommitCount.get() / bytes);
			log(name + " bytes=" + (long) bytes + " seconds=" + seconds
					+ " bytesPerSecond=" + measurement.bytesPerSecond()
					+ " runsPerByte=" + measurement.runsPerByte()
					+ " dispatchesPerByte=" + measurement.dispatchesPerByte()
					+ " commitsPerByte=" + measurement.commitsPerByte());
			hostCompleteWaits().entrySet().stream()
					.map(e -> Map.entry(e.getKey(), e.getValue() - waitsBefore.getOrDefault(e.getKey(), 0)))
					.filter(e -> e.getValue() > 0)
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
					.limit(LOGGED_COMMIT_CAUSES)
					.forEach(e -> log(name + " commitForcingWaits=" + e.getValue() + " requester=" + e.getKey()));
			return measurement;
		}
	}

	/**
	 * Returns a snapshot of the host-complete waits {@link MetalCommandRunner} has attributed to each
	 * requester. The counts are a synchronized map that Metal completion threads may update, so the
	 * copy is taken while holding its lock.
	 *
	 * @return a copy of the per-requester wait counts
	 */
	private static Map<String, Integer> hostCompleteWaits() {
		Map<String, Integer> counts = MetalCommandRunner.hostCompleteRequesters.getCounts();
		synchronized (counts) {
			return new HashMap<>(counts);
		}
	}

	/**
	 * Requires the accelerator for every pass compiled from {@code lm}, including its single-position
	 * {@link CausalLanguageModel#decodeModel decode variant}, when one is available. A single position
	 * makes nearly every operation of the decode small; left to themselves small operations are placed
	 * on a CPU context while the larger ones go to the accelerator, and the pass then waits on the
	 * host at every switch between the two. This is the benchmark's choice, not the model's.
	 *
	 * @param lm the model
	 * @return {@code lm}
	 */
	private static CausalLanguageModel requireAccelerator(CausalLanguageModel lm) {
		if (Hardware.getLocalHardware().isAvailable(ComputeRequirement.GPU)) {
			lm.setComputeRequirements(ComputeRequirement.GPU);
		}

		return lm;
	}

	/**
	 * Creates a model of the documentation-trained configuration with seeded random weights.
	 *
	 * @return the model
	 */
	private CausalLanguageModel newModel() {
		return new CausalLanguageModel(CausalLanguageModelTest.VOCAB, CausalLanguageModelTest.SEQ_LEN,
				CausalLanguageModelTest.DIM, CausalLanguageModelTest.HEADS, CausalLanguageModelTest.DEPTH,
				CausalLanguageModelTest.FF_DIM, CausalLanguageModelTest.ROPE_BASE, new Random(SEED));
	}
}
