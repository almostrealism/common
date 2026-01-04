/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Diagnostic test to understand why WeightedSumComputation is slow
 * when wrapped in reshape vs standalone.
 *
 * This test investigates whether the wrapping causes getValueAt() to be
 * called (unrolling expressions) vs getScope() (native loops).
 */
public class WeightedSumWrappingDiagnosticTest implements TestFeatures {

	/**
	 * Test Case 1: Small weightedSum WITHOUT reshape wrapper.
	 * Expected: Fast compilation because small group size.
	 */
	@Test(timeout = 30000)
	public void smallWeightedSumNoReshape() {
		int inputChannels = 4;
		int kernelSize = 4;
		int outLen = 8;
		int outputChannels = 4;

		// Group size = inputChannels * kernelSize = 16 (small)
		TraversalPolicy inputShape = shape(1, inputChannels, 1, outLen + kernelSize - 1);
		TraversalPolicy weightShape = shape(1, inputChannels, outputChannels, kernelSize);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TraversalPolicy resultShape = shape(1, 1, outputChannels, outLen);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, inputChannels, 1)
				.withRate(2, 1, outputChannels);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, 1)
				.withRate(1, inputChannels, 1)
				.withRate(3, kernelSize, outLen);
		TraversalPolicy groupShape = shape(1, inputChannels, 1, kernelSize);

		long start = System.currentTimeMillis();
		CollectionProducer result = weightedSum("test",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights));

		// No reshape - evaluate directly
		PackedCollection out = result.evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("Small weightedSum (no reshape): " + elapsed + "ms, shape=" + out.getShape());
	}

	/**
	 * Test Case 2: Small weightedSum WITH reshape wrapper.
	 * Expected: Should also be fast if reshape doesn't cause unrolling.
	 */
	@Test(timeout = 30000)
	public void smallWeightedSumWithReshape() {
		int inputChannels = 4;
		int kernelSize = 4;
		int outLen = 8;
		int outputChannels = 4;

		TraversalPolicy inputShape = shape(1, inputChannels, 1, outLen + kernelSize - 1);
		TraversalPolicy weightShape = shape(1, inputChannels, outputChannels, kernelSize);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TraversalPolicy resultShape = shape(1, 1, outputChannels, outLen);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, inputChannels, 1)
				.withRate(2, 1, outputChannels);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, 1)
				.withRate(1, inputChannels, 1)
				.withRate(3, kernelSize, outLen);
		TraversalPolicy groupShape = shape(1, inputChannels, 1, kernelSize);

		long start = System.currentTimeMillis();
		CollectionProducer result = weightedSum("test",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights))
				.reshape(1, outputChannels, outLen);  // Add reshape wrapper

		PackedCollection out = result.evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("Small weightedSum (with reshape): " + elapsed + "ms, shape=" + out.getShape());
	}

	/**
	 * Test Case 3: Medium weightedSum WITHOUT reshape.
	 * Group size = 256 * 16 = 4096
	 * Takes ~16 seconds for native code compilation.
	 */
	@Test(timeout = 60000)
	public void mediumWeightedSumNoReshape() {
		if (testDepth < 1) return;
		int inputChannels = 256;  // Moderate size
		int kernelSize = 16;
		int outLen = 8;
		int outputChannels = 128;

		// Group size = 256 * 16 = 4096
		TraversalPolicy inputShape = shape(1, inputChannels, 1, outLen + kernelSize - 1);
		TraversalPolicy weightShape = shape(1, inputChannels, outputChannels, kernelSize);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TraversalPolicy resultShape = shape(1, 1, outputChannels, outLen);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, inputChannels, 1)
				.withRate(2, 1, outputChannels);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, 1)
				.withRate(1, inputChannels, 1)
				.withRate(3, kernelSize, outLen);
		TraversalPolicy groupShape = shape(1, inputChannels, 1, kernelSize);

		long start = System.currentTimeMillis();
		log("Creating medium weightedSum (no reshape), groupSize=" + groupShape.getTotalSize());

		CollectionProducer result = weightedSum("test",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights));

		log("Calling evaluate...");
		PackedCollection out = result.evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("Medium weightedSum (no reshape): " + elapsed + "ms, shape=" + out.getShape());
	}

	/**
	 * Test Case 4: Medium weightedSum WITH reshape.
	 * This should show if reshape causes the problem.
	 * Takes ~16 seconds for native code compilation (or cached from previous test).
	 */
	@Test(timeout = 60000)
	public void mediumWeightedSumWithReshape() {
		if (testDepth < 1) return;
		int inputChannels = 256;
		int kernelSize = 16;
		int outLen = 8;
		int outputChannels = 128;

		TraversalPolicy inputShape = shape(1, inputChannels, 1, outLen + kernelSize - 1);
		TraversalPolicy weightShape = shape(1, inputChannels, outputChannels, kernelSize);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TraversalPolicy resultShape = shape(1, 1, outputChannels, outLen);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, inputChannels, 1)
				.withRate(2, 1, outputChannels);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, 1)
				.withRate(1, inputChannels, 1)
				.withRate(3, kernelSize, outLen);
		TraversalPolicy groupShape = shape(1, inputChannels, 1, kernelSize);

		long start = System.currentTimeMillis();
		log("Creating medium weightedSum (with reshape), groupSize=" + groupShape.getTotalSize());

		CollectionProducer result = weightedSum("test",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights))
				.reshape(1, outputChannels, outLen);

		log("Calling evaluate...");
		PackedCollection out = result.evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("Medium weightedSum (with reshape): " + elapsed + "ms, shape=" + out.getShape());
	}

	/**
	 * Test Case 5: Large weightedSum WITHOUT reshape.
	 * Group size = 2048 * 16 = 32768
	 * This is the problematic configuration from convTranspose1d.
	 * Takes 30+ minutes for expression tree compilation due to O(n^2) scaling without isolation.
	 * This test intentionally does NOT use OperationList.optimize() to measure the unoptimized path.
	 */
	@Test(timeout = 3600000) // 1 hour - intentionally slow diagnostic test
	public void largeWeightedSumNoReshape() {
		if (testDepth < 3) return; // Only run in deepest test mode due to very long runtime
		int inputChannels = 2048;  // Large
		int kernelSize = 16;
		int outLen = 8;
		int outputChannels = 64;  // Keep output small to focus on the issue

		// Group size = 2048 * 16 = 32768
		TraversalPolicy inputShape = shape(1, inputChannels, 1, outLen + kernelSize - 1);
		TraversalPolicy weightShape = shape(1, inputChannels, outputChannels, kernelSize);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TraversalPolicy resultShape = shape(1, 1, outputChannels, outLen);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, inputChannels, 1)
				.withRate(2, 1, outputChannels);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, 1)
				.withRate(1, inputChannels, 1)
				.withRate(3, kernelSize, outLen);
		TraversalPolicy groupShape = shape(1, inputChannels, 1, kernelSize);

		long start = System.currentTimeMillis();
		log("Creating large weightedSum (no reshape), groupSize=" + groupShape.getTotalSize());

		CollectionProducer result = weightedSum("test",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights));

		log("Calling evaluate...");
		PackedCollection out = result.evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("Large weightedSum (no reshape): " + elapsed + "ms, shape=" + out.getShape());
	}

	/**
	 * Test Case 6: Large weightedSum WITH reshape.
	 * This should demonstrate if reshape is the cause.
	 * Takes 30+ minutes for expression tree compilation due to O(n^2) scaling without isolation.
	 * This test intentionally does NOT use OperationList.optimize() to measure the unoptimized path.
	 */
	@Test(timeout = 3600000) // 1 hour - intentionally slow diagnostic test
	public void largeWeightedSumWithReshape() {
		if (testDepth < 3) return; // Only run in deepest test mode due to very long runtime
		int inputChannels = 2048;
		int kernelSize = 16;
		int outLen = 8;
		int outputChannels = 64;

		TraversalPolicy inputShape = shape(1, inputChannels, 1, outLen + kernelSize - 1);
		TraversalPolicy weightShape = shape(1, inputChannels, outputChannels, kernelSize);

		PackedCollection input = new PackedCollection(inputShape).randFill();
		PackedCollection weights = new PackedCollection(weightShape).randFill();

		TraversalPolicy resultShape = shape(1, 1, outputChannels, outLen);
		TraversalPolicy inputPositions = resultShape
				.withRate(1, inputChannels, 1)
				.withRate(2, 1, outputChannels);
		TraversalPolicy filterPositions = resultShape
				.withRate(0, 1, 1)
				.withRate(1, inputChannels, 1)
				.withRate(3, kernelSize, outLen);
		TraversalPolicy groupShape = shape(1, inputChannels, 1, kernelSize);

		long start = System.currentTimeMillis();
		log("Creating large weightedSum (with reshape), groupSize=" + groupShape.getTotalSize());

		CollectionProducer result = weightedSum("test",
				inputPositions, filterPositions,
				groupShape, cp(input), cp(weights))
				.reshape(1, outputChannels, outLen);

		log("Calling evaluate...");
		PackedCollection out = result.evaluate();
		long elapsed = System.currentTimeMillis() - start;

		log("Large weightedSum (with reshape): " + elapsed + "ms, shape=" + out.getShape());
	}
}
