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
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Profiled, standalone reproduction of the windowed transformer stack
 * {@link TransformerResamplingFeatures#addResamplingTransformerStack} builds for a midpoint-shifted
 * resampling block: a {@code numChunks=2} layer, a reshape/shift/reshape into {@code numChunks=3}
 * chunks, and a {@code numChunks=3} layer, at {@link SAMEResamplingTestBase#smallConfig(boolean)}
 * dimensions with synthetic weights. It exists to keep the compiled operation tree small enough to
 * read in full with {@code ar-profile-analyzer} (see {@code docs/plans/METAL_RESAMPLING_NAN_PLAN.md}),
 * unlike the full block {@link TransformerResamplingShapeTest} exercises. It writes an
 * {@link OperationProfileNode} to {@code results/} and logs whether the forward output contains any
 * non-finite values; it does not assert finiteness itself, since it exists to characterize the
 * failure, not (yet) to guard against a regression of it.
 */
public class ResamplingMidpointShiftProfileTest extends SAMEResamplingTestBase {

	/** Directory for the written operation profile. */
	private static final String RESULTS_DIR = "results";

	/** Number of forward passes performed by {@link #midpointShiftTwoChunkCounts()} against the same
	 *  compiled {@link Model}, to characterize whether the non-finite output is a deterministic
	 *  offset/kernel-sharing defect (same count every iteration) or a race (varies run to run). */
	private static final int ITERATIONS = 10;

	/**
	 * Builds and forwards the two-chunk-count midpoint-shift stack {@value #ITERATIONS} times against
	 * the same compiled {@link Model}, writing an operation profile from the first pass and logging
	 * the count of non-finite output elements on every iteration.
	 *
	 * @throws IOException if the profile cannot be written
	 */
	@Test(timeout = 300000)
	public void midpointShiftTwoChunkCounts() throws IOException {
		new File(RESULTS_DIR).mkdirs();

		ResamplingConfig config = smallConfig(true);
		int length = 8;
		StateDictionary weights = syntheticWeights(config, "enc");

		int numSeg = length / config.getInputSegSize();
		int segLen = numSeg * config.getSubChunkSize();
		TraversalPolicy segmentedShape = shape(1, segLen, config.getDim());

		log("config: numChunks(first)=" + ((1 * segLen) / config.getEffectiveChunkSize())
				+ " numChunks(shifted)=" + ((1 * (segLen + config.getEffectiveChunkSize())) / config.getEffectiveChunkSize())
				+ " effectiveChunkSize=" + config.getEffectiveChunkSize()
				+ " segLen=" + segLen + " dim=" + config.getDim());

		SequentialBlock block = new SequentialBlock(segmentedShape);
		addResamplingTransformerStack(block, 1, config, weights, "enc");

		// initKernelMetrics assigns the profile as the active profile before compilation, so the
		// compilation listener records the generated kernel source of every scope compiled below
		// (including any kernel shared/reused between the numChunks=2 and numChunks=3 layers).
		OperationProfileNode profile = initKernelMetrics(new OperationProfileNode("resampling_midpoint_shift"));
		Model model = new Model(segmentedShape);
		model.add(block);

		CompiledModel compiled = model.compile(false, profile);
		try {
			long totalNonFinite = 0;

			for (int iteration = 0; iteration < ITERATIONS; iteration++) {
				PackedCollection input = new PackedCollection(segmentedShape).randnFill();
				PackedCollection output = compiled.forward(input);

				double[] values = output.toArray(0, output.getShape().getTotalSize());
				long nanCount = 0;
				for (double v : values) {
					if (!Double.isFinite(v)) {
						nanCount++;
					}
				}

				log("iteration=" + iteration + " outputElements=" + values.length + " nonFiniteCount=" + nanCount);
				assertEquals(segmentedShape.getTotalSize(), values.length);
				totalNonFinite += nanCount;
			}

			log("iterations=" + ITERATIONS + " totalNonFiniteCount=" + totalNonFinite);
		} finally {
			Hardware.getLocalHardware().clearProfile();
			compiled.destroy();
		}

		String profilePath = RESULTS_DIR + "/resampling_midpoint_shift.xml";
		profile.save(profilePath);
		log("Profile saved to: " + profilePath);

		weights.destroy();
	}
}
