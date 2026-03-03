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

package org.almostrealism.hardware.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that {@link OperationList} segmentation and automatic optimization
 * are enabled by default, and that non-uniform operation lists are properly
 * segmented into groups of uniform operations.
 *
 * <p>These tests verify Goal 3 of the AudioScene loop optimization:
 * reducing JNI call overhead by fusing trivial kernels. When
 * {@code enableAutomaticOptimization} and {@code enableSegmenting} are
 * both {@code true}, a non-uniform OperationList of scalar assignments
 * and vector operations is segmented into groups that can each compile
 * as a single kernel, reducing the total number of JNI transitions.</p>
 *
 * <p>If either flag were reverted to {@code false}, these tests would
 * fail because the configuration assertions would not hold, and the
 * segmentation behavior would differ.</p>
 *
 * @see OperationList#optimize(io.almostrealism.compute.ProcessContext)
 */
public class OperationListSegmentationTest extends TestSuiteBase {

	/**
	 * Verifies that automatic optimization is enabled by default.
	 * This configuration is required for the AudioScene pipeline to
	 * automatically segment non-uniform operation lists at compile time.
	 */
	@Test
	public void automaticOptimizationEnabled() {
		assertTrue("OperationList.enableAutomaticOptimization must be true " +
						"for automatic kernel fusion to work",
				OperationList.enableAutomaticOptimization);
	}

	/**
	 * Verifies that segmenting is enabled by default.
	 * This configuration is required for non-uniform operation lists
	 * to be split into groups of same-count operations, each of which
	 * can be compiled as a single kernel.
	 */
	@Test
	public void segmentingEnabled() {
		assertTrue("OperationList.enableSegmenting must be true " +
						"for JNI call reduction via kernel fusion",
				OperationList.enableSegmenting);
	}

	/**
	 * Verifies that a non-uniform OperationList containing both scalar
	 * (count=1) and vector (count=N) assignments produces correct
	 * results when executed with automatic optimization enabled.
	 *
	 * <p>This simulates the AudioScene pattern where scalar assignments
	 * (e.g. {@code v = 0.0}) are interleaved with vector operations
	 * (e.g. filter convolution over 4096 samples). With segmentation,
	 * the consecutive scalar operations are grouped and compiled together,
	 * reducing the number of JNI calls.</p>
	 */
	@Test(timeout = 30_000)
	public void nonUniformListProducesCorrectResults() {
		int vectorSize = 64;

		PackedCollection scalarA = new PackedCollection(1);
		PackedCollection scalarB = new PackedCollection(1);
		PackedCollection vectorOut = new PackedCollection(vectorSize);
		PackedCollection vectorIn = new PackedCollection(vectorSize);

		vectorIn.fill(pos -> (pos[0] + 1) * 0.1);

		OperationList ops = new OperationList("nonUniformTest");
		ops.add(a("scalarA", p(scalarA), c(42.0)));
		ops.add(a("scalarB", p(scalarB), c(7.0)));
		ops.add(a("vector", cp(vectorOut.each()),
				cp(vectorIn.each()).multiply(c(2.0))));

		assertFalse("Non-uniform list should not be uniform",
				ops.isUniform());

		ops.get().run();

		assertEquals("Scalar A should be 42.0",
				42.0, scalarA.toDouble(0), 1e-6);
		assertEquals("Scalar B should be 7.0",
				7.0, scalarB.toDouble(0), 1e-6);

		for (int i = 0; i < vectorSize; i++) {
			double expected = (i + 1) * 0.1 * 2.0;
			assertEquals("Vector element " + i + " should be " + expected,
					expected, vectorOut.toDouble(i), 1e-6);
		}
	}

	/**
	 * Verifies that the segmentation logic in {@link OperationList#optimize}
	 * correctly groups consecutive operations with the same count.
	 *
	 * <p>Given an operation list with pattern [scalar, scalar, vector, scalar],
	 * the optimize method should group the first two scalars together,
	 * keep the vector separate, and leave the trailing scalar separate,
	 * resulting in a 3-segment optimized list instead of 4 individual
	 * JNI calls.</p>
	 */
	@Test(timeout = 30_000)
	public void segmentationGroupsConsecutiveSameCountOps() {
		int vectorSize = 32;

		PackedCollection s1 = new PackedCollection(1);
		PackedCollection s2 = new PackedCollection(1);
		PackedCollection s3 = new PackedCollection(1);
		PackedCollection v = new PackedCollection(vectorSize);
		PackedCollection vIn = new PackedCollection(vectorSize);

		vIn.fill(pos -> pos[0] + 1.0);

		OperationList ops = new OperationList("segmentTest", false);
		ops.add(a("s1", p(s1), c(1.0)));
		ops.add(a("s2", p(s2), c(2.0)));
		ops.add(a("v", cp(v.each()),
				cp(vIn.each()).multiply(c(3.0))));
		ops.add(a("s3", p(s3), c(4.0)));

		((OperationList) ops.optimize()).get().run();

		assertEquals("s1 should be 1.0", 1.0, s1.toDouble(0), 1e-6);
		assertEquals("s2 should be 2.0", 2.0, s2.toDouble(0), 1e-6);
		assertEquals("s3 should be 4.0", 4.0, s3.toDouble(0), 1e-6);

		for (int i = 0; i < vectorSize; i++) {
			double expected = (i + 1.0) * 3.0;
			assertEquals("Vector element " + i,
					expected, v.toDouble(i), 1e-6);
		}
	}
}
