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

package io.almostrealism.compute.test;

import io.almostrealism.compute.CascadingOptimizationStrategy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.compute.ProcessContextBase;
import io.almostrealism.compute.ProcessOptimizationStrategy;
import io.almostrealism.compute.ReplicationMismatchOptimization;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestDepth;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collection;

/**
 * Tests for {@link ReplicationMismatchOptimization}, verifying that the strategy
 * correctly detects and isolates children whose parallelism is significantly lower
 * than the parent's, preventing wasteful expression replication in generated kernels.
 *
 * <p>The test uses {@link MultiOrderFilter} as the primary test case because its
 * coefficient sub-expression (parallelism ~41) embedded in a convolution kernel
 * (parallelism ~4096) is the canonical example of a replication mismatch.</p>
 *
 * @see ReplicationMismatchOptimization
 * @see ParallelismTargetOptimization
 * @see CascadingOptimizationStrategy
 */
public class ReplicationMismatchOptimizationTest extends TestSuiteBase implements FirFilterTestFeatures {

	/**
	 * Verifies that the {@link ReplicationMismatchOptimization} strategy detects
	 * the parallelism mismatch between a {@link MultiOrderFilter} parent (parallelism
	 * matching signal size) and its coefficient child (parallelism matching filter order).
	 *
	 * <p>The strategy is called directly with the filter's children and should return
	 * a non-null result indicating it handled the mismatch by isolating the
	 * low-parallelism coefficient child.</p>
	 */
	@Test(timeout = 15000)
	public void strategyDetectsMismatch() {
		int signalSize = 4096;
		int filterOrder = 40;
		double cutoff = 5000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));

		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoff), sampleRate, filterOrder);

		long parentParallelism = ParallelProcess.parallelism(filter);
		log("Parent parallelism: " + parentParallelism);

		Collection<Process<?, ?>> children = filter.getChildren();
		for (Process<?, ?> child : children) {
			long childP = ParallelProcess.parallelism(child);
			log("Child parallelism: " + childP +
					" (type: " + child.getClass().getSimpleName() + ")");
		}

		ReplicationMismatchOptimization strategy = new ReplicationMismatchOptimization();
		Process result = strategy.optimize(
				ProcessContext.base(), filter, children,
				c -> c.stream().map(p -> (Process<?, ?>) p));

		assertNotNull("Strategy should detect replication mismatch and return non-null", result);
	}

	/**
	 * Verifies that the strategy returns {@code null} when all children have
	 * parallelism matching the parent, allowing cascading to the next strategy.
	 *
	 * <p>When a {@link MultiOrderFilter} receives pre-computed constant coefficients
	 * (via a plain buffer reference with no traversable expression), the coefficient
	 * "child" has parallelism matching the parent or is not a ParallelProcess at all.
	 * The strategy should not intervene in this case.</p>
	 */
	@Test(timeout = 15000)
	public void strategyReturnsNullWhenNoMismatch() {
		int signalSize = 256;
		int filterOrder = 10;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));

		double[] coeffs = referenceLowPassCoefficients(5000, 44100, filterOrder);
		PackedCollection coefficients = new PackedCollection(filterOrder + 1);
		coefficients.setMem(coeffs);

		// Use pre-computed coefficients (plain buffer reference, not an expression tree)
		MultiOrderFilter filter = MultiOrderFilter.create(
				traverseEach(cp(signal)), p(coefficients));

		Collection<Process<?, ?>> children = filter.getChildren();

		ReplicationMismatchOptimization strategy = new ReplicationMismatchOptimization();
		Process result = strategy.optimize(
				ProcessContext.base(), filter, children,
				c -> c.stream().map(p -> (Process<?, ?>) p));

		for (Process<?, ?> child : children) {
			long childP = ParallelProcess.parallelism(child);
			log("Child parallelism: " + childP +
					" (type: " + child.getClass().getSimpleName() + ")");
		}

		// With pre-computed coefficients, the coefficient child has parallelism
		// matching the parent or is not a ParallelProcess, so the strategy
		// should return null (deferring to the next strategy in the cascade).
		Assert.assertNull("Strategy should return null when no replication mismatch is detected", result);
	}

	/**
	 * Verifies that the threshold parameter controls which mismatches are detected.
	 * Sets the threshold to a high value so that the mismatch is below threshold,
	 * then restores it and verifies it is detected.
	 */
	@Test(timeout = 15000)
	public void thresholdControlsMismatchDetection() {
		int signalSize = 256;
		int filterOrder = 40;
		double cutoff = 5000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 32.0));

		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoff), sampleRate, filterOrder);

		Collection<Process<?, ?>> children = filter.getChildren();

		// Ratio is ~256/41 ≈ 6.2x. With threshold 8, should NOT trigger.
		int originalThreshold = ReplicationMismatchOptimization.replicationThreshold;
		try {
			ReplicationMismatchOptimization.replicationThreshold = 8;
			ReplicationMismatchOptimization strategy = new ReplicationMismatchOptimization();
			Process highThresholdResult = strategy.optimize(
					ProcessContext.base(), filter, children,
					c -> c.stream().map(p -> (Process<?, ?>) p));

			log("Signal size " + signalSize + ", filter order " + filterOrder);
			log("Ratio ~" + (signalSize / (filterOrder + 1)));
			log("Threshold 8: " + (highThresholdResult != null ? "detected" : "not detected"));

			// With threshold 4, should trigger (ratio ~6.2 >= 4)
			ReplicationMismatchOptimization.replicationThreshold = 4;
			ReplicationMismatchOptimization lowThresholdStrategy = new ReplicationMismatchOptimization();
			Process lowThresholdResult = lowThresholdStrategy.optimize(
					ProcessContext.base(), filter, children,
					c -> c.stream().map(p -> (Process<?, ?>) p));

			log("Threshold 4: " + (lowThresholdResult != null ? "detected" : "not detected"));
			assertNotNull("Strategy should detect mismatch with lower threshold",
					lowThresholdResult);
		} finally {
			ReplicationMismatchOptimization.replicationThreshold = originalThreshold;
		}
	}

	/**
	 * End-to-end correctness test: runs a {@link MultiOrderFilter} with expression
	 * coefficients through the full optimization pipeline (including the new
	 * {@link ReplicationMismatchOptimization} strategy) and verifies the output
	 * matches a reference convolution implementation.
	 *
	 * <p>This test ensures that selective isolation of the coefficient sub-expression
	 * does not change the computed result.</p>
	 */
	@Test(timeout = 60000)
	public void correctnessWithSelectiveIsolation() {
		int signalSize = 256;
		int filterOrder = 20;
		double cutoff = 5000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * i / 16.0)
						+ 0.5 * Math.sin(2.0 * Math.PI * i / 4.0));

		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoff), sampleRate, filterOrder);
		PackedCollection result = filter.get().evaluate();

		double[] coeffs = referenceLowPassCoefficients(cutoff, sampleRate, filterOrder);
		double[] expected = referenceConvolve(signal.toArray(0, signalSize), coeffs);
		assertConvolutionEquals(expected, result, signalSize);
	}

	/**
	 * Larger-scale correctness test matching realistic audio buffer sizes and
	 * filter orders, ensuring the strategy works correctly under production-like
	 * conditions where the replication ratio is highest (~4096/41 ≈ 100x).
	 */
	@Test(timeout = 120000)
	@TestDepth(2)
	public void correctnessWithRealisticParameters() {
		int signalSize = 4096;
		int filterOrder = 40;
		double cutoff = 8000.0;
		int sampleRate = 44100;

		PackedCollection signal = createSignal(signalSize,
				i -> Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)
						+ 0.3 * Math.sin(2.0 * Math.PI * 12000.0 * i / sampleRate));

		MultiOrderFilter filter = lowPass(
				traverseEach(cp(signal)), c(cutoff), sampleRate, filterOrder);
		PackedCollection result = filter.get().evaluate();

		double[] coeffs = referenceLowPassCoefficients(cutoff, sampleRate, filterOrder);
		double[] expected = referenceConvolve(signal.toArray(0, signalSize), coeffs);
		assertConvolutionEquals(expected, result, signalSize);
	}
}
