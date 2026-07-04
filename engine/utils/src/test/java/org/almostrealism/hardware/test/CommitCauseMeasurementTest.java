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

package org.almostrealism.hardware.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.metal.MetalCommandRunner;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Measures how {@link MetalCommandRunner} command-buffer commits partition by cause over a
 * representative training workload, and which operations force the host-wait commits — the
 * commit-cause attribution introduced to diagnose batching collapse (a sustained workload
 * committing every couple of dispatches instead of at the {@code MAX_OPEN} cadence).
 *
 * <p>This is a measurement rather than a guarantee: it logs commits per training step broken
 * down into host-wait commits and {@code MAX_OPEN} commits, and the per-requester counts of
 * commit-forcing waits from {@link MetalCommandRunner#hostCompleteRequesters}, so a change to
 * dispatch chaining can be evaluated by comparing the logged breakdown before and after.</p>
 */
public class CommitCauseMeasurementTest extends TestSuiteBase implements ModelTestFeatures {
	/** Input feature count for the measured model. */
	private static final int FEATURES = 16;
	/** Number of forward/backward steps measured. */
	private static final int STEPS = 20;

	/**
	 * Runs {@value #STEPS} forward/backward steps of a small dense model under copy-based
	 * layer recording (the {@link DefaultCellularLayer#enableMemoryDataCopy} default) and
	 * logs the commit-cause breakdown and the top commit-forcing requesters.
	 */
	@Test(timeout = 300000)
	public void trainingCommitBreakdown() {
		measure(true);
	}

	/**
	 * The same measurement under assignment-based layer recording
	 * ({@link DefaultCellularLayer#enableMemoryDataCopy} disabled), quantifying how much of
	 * the host-wait commit cost is attributable to the deprecated synchronous copy path.
	 */
	@Test(timeout = 300000)
	public void trainingCommitBreakdownAssignmentRecording() {
		boolean original = DefaultCellularLayer.enableMemoryDataCopy;
		DefaultCellularLayer.enableMemoryDataCopy = false;

		try {
			measure(false);
		} finally {
			DefaultCellularLayer.enableMemoryDataCopy = original;
		}
	}

	/**
	 * Builds the model, runs the measured steps, and logs the breakdown.
	 *
	 * @param copyMode whether the run uses copy-based layer recording (for log labelling)
	 */
	private void measure(boolean copyMode) {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		String mode = copyMode ? "copyRecording" : "assignmentRecording";

		SequentialBlock block = new SequentialBlock(shape(FEATURES));
		block.add(dense(FEATURES, FEATURES));
		block.add(dense(FEATURES, FEATURES / 2));
		block.add(dense(FEATURES / 2, 1));

		Model model = new Model(shape(FEATURES), 0.01);
		model.add(block);

		CompiledModel compiled = model.compile(true);

		try {
			PackedCollection input = new PackedCollection(shape(FEATURES));
			input.fill(pos -> Math.random());

			PackedCollection gradient = new PackedCollection(shape(1));
			gradient.setMem(0, 1.0);

			MetalCommandRunner runner = metal.getCommandRunner();

			// Warm-up step so compilation and first-use setup are not measured
			compiled.forward(input);
			compiled.backward(gradient);

			long baseTotal = runner.getCommitCount();
			long baseHost = runner.getHostCompleteCommitCount();
			long baseMaxOpen = runner.getMaxOpenCommitCount();
			Map<String, Integer> baseCounts =
					new HashMap<>(MetalCommandRunner.hostCompleteRequesters.getCounts());

			for (int i = 0; i < STEPS; i++) {
				compiled.forward(input);
				compiled.backward(gradient);
			}

			long total = runner.getCommitCount() - baseTotal;
			long host = runner.getHostCompleteCommitCount() - baseHost;
			long maxOpen = runner.getMaxOpenCommitCount() - baseMaxOpen;

			log("mode=" + mode + " steps=" + STEPS + " commits=" + total +
					" hostCompleteCommits=" + host +
					" maxOpenCommits=" + maxOpen +
					" commitsPerStep=" + ((double) total / STEPS));

			MetalCommandRunner.hostCompleteRequesters.getCounts().entrySet().stream()
					.map(e -> Map.entry(e.getKey(),
							e.getValue() - baseCounts.getOrDefault(e.getKey(), 0)))
					.filter(e -> e.getValue() > 0)
					.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
					.limit(12)
					.forEach(e -> log("mode=" + mode + " requesterWaits=" + e.getValue() +
							" requester=" + e.getKey()));
		} finally {
			compiled.destroy();
		}
	}
}
