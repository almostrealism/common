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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.kernel.LaneIndex;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.Scope;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalLanguageOperations;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the parts of a kernel that assigns several lanes to each work item (see
 * {@link io.almostrealism.code.ComputeContext#getLaneCount()}) that need no kernel to run: how
 * Metal renders such a kernel, that languages and kernels without lanes refuse to render lane
 * constructs, that a lane index and a scope keep their lane count, and that a Metal program
 * refuses a pipeline whose SIMD groups do not match its lanes.
 */
public class KernelLaneTest extends TestSuiteBase {

	/**
	 * A Metal kernel of 32 lanes per work item receives the position and count of its threads,
	 * opens by declaring the work item, the count of work items and the lane under the names
	 * every other kernel uses, renders the lane as a signed integer, and sums across the lanes
	 * with the SIMD group sum.
	 */
	@Test(timeout = 30000)
	public void metalRendersLanes() {
		MetalLanguageOperations lang = new MetalLanguageOperations(Precision.FP32, 32);

		Assert.assertEquals(List.of(
				"uint global_id = lane_thread_id / 32;",
				"uint global_count = lane_thread_count / 32;",
				"uint lane_id = lane_thread_id % 32;"), lang.laneDeclarations());
		Assert.assertEquals("((int) lane_id)", lang.laneIndex());
		Assert.assertEquals("metal::simd_sum(x)", lang.laneSum("x"));

		StringBuilder parameters = new StringBuilder();
		lang.renderParameters(List.of(), parameters::append, Accessibility.EXTERNAL);
		Assert.assertEquals(", uint lane_thread_id [[thread_position_in_grid]], " +
				"uint lane_thread_count [[threads_per_grid]]", parameters.toString());
	}

	/**
	 * A Metal kernel of one thread per work item keeps its usual parameters and declares
	 * nothing, and neither it nor the C language renders a lane construct.
	 */
	@Test(timeout = 30000)
	public void kernelsWithoutLanesRefuseLaneConstructs() {
		MetalLanguageOperations metal = new MetalLanguageOperations(Precision.FP32);
		Assert.assertTrue(metal.laneDeclarations().isEmpty());

		StringBuilder parameters = new StringBuilder();
		metal.renderParameters(List.of(), parameters::append, Accessibility.EXTERNAL);
		Assert.assertEquals(", uint global_id [[thread_position_in_grid]], " +
				"uint global_count [[threads_per_grid]]", parameters.toString());

		assertRefused(UnsupportedOperationException.class, () -> metal.laneSum("x"));
		assertRefused(UnsupportedOperationException.class, metal::laneIndex);

		CLanguageOperations c = new CLanguageOperations(Precision.FP64, true, false);
		assertRefused(UnsupportedOperationException.class, () -> c.laneSum("x"));
		assertRefused(UnsupportedOperationException.class, c::laneIndex);
	}

	/**
	 * A lane index lies between zero and one less than its lane count, and needs at least two
	 * lanes.
	 */
	@Test(timeout = 30000)
	public void laneIndexIsBoundedByLaneCount() {
		LaneIndex lane = new LaneIndex(32);
		Assert.assertEquals(0, lane.lowerBound(null).getAsLong());
		Assert.assertEquals(31, lane.upperBound(null).getAsLong());
		Assert.assertEquals(32, lane.getLimit().getAsLong());
		assertRefused(IllegalArgumentException.class, () -> new LaneIndex(1));
	}

	/**
	 * A scope has one lane per work item unless told otherwise, keeps its lane count when it is
	 * regenerated, as simplification does, and refuses a work item of no lanes.
	 */
	@Test(timeout = 30000)
	public void scopeKeepsLaneCountWhenRegenerated() {
		Scope<PackedCollection> kernel = new Scope<>("lanedKernel",
				new OperationMetadata("lanedKernel", "Kernel of several lanes per work item"));
		Assert.assertEquals(1, kernel.getLaneCount());

		kernel.setLaneCount(32);
		Assert.assertEquals(32, ((Scope<PackedCollection>) kernel.generate(List.of())).getLaneCount());
		assertRefused(IllegalArgumentException.class, () -> kernel.setLaneCount(0));
	}

	/**
	 * A Metal program records the lanes its source was rendered for, and creates a pipeline only
	 * when the pipeline's SIMD groups execute exactly that many threads: a program of as many lanes
	 * as the SIMD groups of the device creates its pipeline, while a program of half as many is
	 * refused, and so is a program of no lanes at all.
	 */
	@Test(timeout = 60000)
	public void metalProgramRefusesPipelineOfOtherSimdWidth() {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		int lanes = metal.getLaneCount();
		MetalProgram matching = laneProgram(metal, lanes);
		MetalProgram narrower = laneProgram(metal, lanes / 2);

		try {
			Assert.assertEquals(lanes, matching.getLaneCount());
			matching.newComputePipelineState().release();
			assertRefused(HardwareException.class, narrower::newComputePipelineState);
			assertRefused(IllegalArgumentException.class, () -> laneProgram(metal, 0));
		} finally {
			matching.destroy();
			narrower.destroy();
		}
	}

	/**
	 * Compiles a Metal program, rendered for the given lanes, whose kernel writes the position of
	 * each of its threads.
	 *
	 * @param metal the Metal context to compile for
	 * @param lanes the lanes the kernel assigns to each work item
	 * @return the compiled program, to be destroyed by the caller
	 */
	private static MetalProgram laneProgram(MetalComputeContext metal, int lanes) {
		String type = metal.getDataContext().getPrecision().typeName();
		MetalProgram program = new MetalProgram(metal,
				new OperationMetadata("laneThreads", "Write the position of each thread"), "laneThreads",
				"#include <metal_stdlib>\n" +
						"[[kernel]] void laneThreads(device " + type + " *out [[buffer(0)]], " +
						"uint lane_thread_id [[thread_position_in_grid]]) { " +
						"out[lane_thread_id] = lane_thread_id; }\n", lanes);
		program.compile();
		return program;
	}

	/**
	 * Asserts that {@code action} throws an exception of the given type.
	 *
	 * @param type   the type of exception expected
	 * @param action the action expected to throw
	 */
	private static void assertRefused(Class<? extends RuntimeException> type, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			Assert.assertTrue("expected " + type.getSimpleName() + ", not " + e, type.isInstance(e));
			return;
		}

		Assert.fail("expected " + type.getSimpleName());
	}
}
