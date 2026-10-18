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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Random;

/**
 * Profiled reproduction of the forward passes that
 * {@code CausalLanguageModelTest.gradientsMatchFiniteDifferences} repeats thousands of times: the
 * same miniature configuration (vocabulary 16, context 4, embed 8, 2 heads, depth 2, feed-forward
 * width 8), compiled with backpropagation, run forward over the same token window.
 *
 * <p>A forward pass of this model issues several hundred small kernels, so its cost is dominated by
 * what each dispatch costs rather than by the arithmetic. The test reports the wall time per forward
 * pass together with the Metal dispatch and command-buffer commit counts per pass, and writes the
 * operation profile to {@code results/causal_lm_forward.xml}, so the time a pass takes can be
 * compared with the time recorded for the kernels it runs. The number of passes can be raised with
 * the {@code AR_PROFILE_FORWARDS} system property for a longer steady-state measurement.</p>
 */
public class CausalLanguageModelForwardProfileTest extends TestSuiteBase {
	/** Vocabulary size of the miniature configuration. */
	private static final int VOCAB = 16;

	/** Context length of the miniature configuration. */
	private static final int SEQ_LEN = 4;

	/**
	 * Runs the forward pass repeatedly under a profile, reports its cost per pass, and checks that
	 * every pass over the same window reproduces the first pass's output.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 600000)
	public void profileForwardPass() throws IOException {
		int passes = Integer.parseInt(System.getProperty("AR_PROFILE_FORWARDS", "200"));

		CausalLanguageModel lm = new CausalLanguageModel(VOCAB, SEQ_LEN, 8, 2, 2, 8, 10000.0, new Random(3));
		PackedCollection ids = PackedCollection.of(3, 7, 1, 12);

		OperationProfileNode profile = new OperationProfileNode("causalLanguageModelForward");
		Hardware.getLocalHardware().assignProfile(profile);
		CompiledModel compiled = null;

		try {
			compiled = lm.compile(true, profile);
			double[] first = compiled.forward(ids).toArray();

			MetalCommandRunner.resetBatchSizeCounters();
			long start = System.nanoTime();
			for (int i = 0; i < passes; i++) {
				compiled.forward(ids);
			}
			long elapsed = System.nanoTime() - start;

			log("passes=" + passes + " elapsedMs=" + (elapsed / 1_000_000)
					+ " usPerForward=" + (elapsed / 1000.0 / passes));
			log("dispatchesPerForward=" + (MetalCommandRunner.totalDispatchCount.get() / (double) passes)
					+ " commitsPerForward=" + (MetalCommandRunner.totalCommitCount.get() / (double) passes)
					+ " meanBatchSize=" + MetalCommandRunner.meanBatchSize());

			double[] last = compiled.forward(ids).toArray();
			Assert.assertEquals(VOCAB * SEQ_LEN, last.length);
			for (int i = 0; i < last.length; i++) {
				Assert.assertTrue("output " + i + " is not finite", Double.isFinite(last[i]));
				Assert.assertEquals("output " + i + " changed between passes", first[i], last[i], 1e-5);
			}
		} finally {
			Hardware.getLocalHardware().clearProfile();
			if (compiled != null) compiled.destroy();
			ids.destroy();
			profile.save("results/causal_lm_forward.xml");
		}
	}
}
