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

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.ml.tokenization.ByteTokenizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
 * writes the profile to {@code results/causal_lm_decode_throughput.xml}. None of these figures is
 * asserted. Wall-clock time varies by node, and the cache path is not assumed to be cheaper: its work
 * per byte is one position rather than a {@code seqLen}-wide window, but a model this small is bound
 * by the cost of each dispatch and host wait rather than by arithmetic, so whether that saving shows
 * up as throughput is what this benchmark measures. What is asserted is backend-independent: both
 * generators decode the same greedy continuation, so the two sets of figures describe the same
 * decode, and the profile recorded the kernels each path ran.</p>
 *
 * <p>A second test continues the same prompt through the cache path greedily and with nucleus (top-p)
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

	/** Wall-clock and work counts of one generator's measured decoding. */
	private record Measurement(int[] continuation, double bytesPerSecond, double runsPerByte,
							   double dispatchesPerByte, double commitsPerByte) { }

	/**
	 * Decodes the same prompt to the full context with both generators, reports throughput and work
	 * per byte for each and the cache's speedup, and requires both to decode the same greedy
	 * continuation with the kernels of each recorded by the profile.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 20 * 60000)
	public void keyValueCacheDecodeThroughput() throws IOException {
		int repeats = Integer.parseInt(System.getProperty("AR_PROFILE_DECODES", "3"));
		int[] prompt = new ByteTokenizer().encodeAsInt(CausalLanguageModelTest.GENERATION_PROMPT);
		int length = CausalLanguageModelTest.SEQ_LEN - prompt.length;
		CausalLanguageModel lm = newModel();

		OperationProfileNode profile = new OperationProfileNode("causalLanguageModelDecode");
		Hardware.getLocalHardware().assignProfile(profile);

		try {
			Measurement sliding = measure("slidingWindow", profile, prompt, length, repeats,
					() -> new SlidingWindowAutoregressiveModel(
							new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null));
			Measurement cache = measure("keyValueCache", profile, prompt, length, repeats,
					() -> new KeyValueCacheAutoregressiveModel(
							new CausalLanguageModel(lm.getConfig(), lm.getWeights()), null));

			log("speedup=" + cache.bytesPerSecond() / sliding.bytesPerSecond()
					+ " runsPerByteRatio=" + sliding.runsPerByte() / cache.runsPerByte()
					+ " dispatchesPerByteRatio=" + sliding.dispatchesPerByte() / cache.dispatchesPerByte());

			Assert.assertArrayEquals("the two decode paths disagree on the greedy continuation",
					sliding.continuation(), cache.continuation());
			Assert.assertTrue("no sliding-window kernel invocations were profiled", sliding.runsPerByte() > 0);
			Assert.assertTrue("no key/value-cache kernel invocations were profiled", cache.runsPerByte() > 0);
		} finally {
			Hardware.getLocalHardware().clearProfile();
			lm.getWeights().destroy();
			profile.save("results/causal_lm_decode_throughput.xml");
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
		CausalLanguageModel lm = newModel();

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
			Map<String, Integer> waitsBefore = new HashMap<>(MetalCommandRunner.hostCompleteRequesters.getCounts());
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
			MetalCommandRunner.hostCompleteRequesters.getCounts().entrySet().stream()
					.map(e -> Map.entry(e.getKey(), e.getValue() - waitsBefore.getOrDefault(e.getKey(), 0)))
					.filter(e -> e.getValue() > 0)
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
					.limit(LOGGED_COMMIT_CAUSES)
					.forEach(e -> log(name + " commitForcingWaits=" + e.getValue() + " requester=" + e.getKey()));
			return measurement;
		}
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
