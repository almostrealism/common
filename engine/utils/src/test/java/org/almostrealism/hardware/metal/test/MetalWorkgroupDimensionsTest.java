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

package org.almostrealism.hardware.metal.test;

import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Backend-independent regression test for {@link MetalOperator#splitWorkgroupDimensions(int, int)},
 * the pure threadgroup-geometry core of {@link MetalOperator#getWorkgroupDimensions()}.
 *
 * <p>A workgroup narrower than one SIMD group used to be split as
 * {@code [simdWidth, workgroupSize / simdWidth, 1]}, whose height rounds to zero &mdash; an invalid
 * threadgroup that on Metal leaves part of the dispatched region unwritten and read back later as
 * uninitialised memory (one of the causes of the resampling-block NaN). These assertions pin the
 * height-zero case to a one-dimensional dispatch and hold on every backend because the split is
 * device-independent integer arithmetic.</p>
 */
public class MetalWorkgroupDimensionsTest extends TestSuiteBase {

	/** A representative SIMD group width for Apple GPUs. */
	private static final int SIMD_WIDTH = 32;

	/**
	 * Verifies that a workgroup smaller than one SIMD group is dispatched one-dimensional as
	 * {@code [workgroupSize, 1, 1]} rather than a zero-height threadgroup. These are exactly the
	 * workgroup sizes {@link MetalOperator#getWorkgroupSize()} yields for the small,
	 * non-SIMD-multiple work sizes (144, 216) that surfaced the defect.
	 */
	@Test(timeout = 30000)
	public void subSimdWorkgroupDispatchesOneDimensional() {
		for (int workgroupSize : new int[] { 1, 2, 4, 8, 16 }) {
			int[] dims = MetalOperator.splitWorkgroupDimensions(workgroupSize, SIMD_WIDTH);
			Assert.assertArrayEquals("workgroupSize=" + workgroupSize,
					new int[] { workgroupSize, 1, 1 }, dims);
		}
	}

	/**
	 * Verifies that a workgroup at least one SIMD group wide is split into a
	 * {@code [simdWidth, workgroupSize / simdWidth, 1]} threadgroup with a positive height.
	 */
	@Test(timeout = 30000)
	public void fullSimdWorkgroupSplitsAcrossWidthAndHeight() {
		Assert.assertArrayEquals(new int[] { SIMD_WIDTH, 1, 1 },
				MetalOperator.splitWorkgroupDimensions(SIMD_WIDTH, SIMD_WIDTH));
		Assert.assertArrayEquals(new int[] { SIMD_WIDTH, 2, 1 },
				MetalOperator.splitWorkgroupDimensions(2 * SIMD_WIDTH, SIMD_WIDTH));
		Assert.assertArrayEquals(new int[] { SIMD_WIDTH, 32, 1 },
				MetalOperator.splitWorkgroupDimensions(1024, SIMD_WIDTH));
	}

	/**
	 * Verifies the two invariants every split must satisfy for a valid dispatch: no dimension is
	 * zero, and for a SIMD-width-divisible workgroup the dimensions multiply back to the requested
	 * thread count (no threads are dropped).
	 */
	@Test(timeout = 30000)
	public void everySplitHasNonZeroDimensionsAndPreservesThreadCount() {
		for (int workgroupSize : new int[] { 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024 }) {
			int[] dims = MetalOperator.splitWorkgroupDimensions(workgroupSize, SIMD_WIDTH);
			Assert.assertTrue("width > 0 for workgroupSize=" + workgroupSize, dims[0] > 0);
			Assert.assertTrue("height > 0 for workgroupSize=" + workgroupSize, dims[1] > 0);
			Assert.assertTrue("depth > 0 for workgroupSize=" + workgroupSize, dims[2] > 0);
			Assert.assertEquals("thread count preserved for workgroupSize=" + workgroupSize,
					workgroupSize, dims[0] * dims[1] * dims[2]);
		}
	}
}
