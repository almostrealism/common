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

import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.metal.MetalComputeContext;
import org.almostrealism.hardware.metal.MetalOperator;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Consumer;

/**
 * Validates that a {@link MetalOperator} dispatches exactly the work range it is given, or
 * refuses it, as {@link io.almostrealism.kernel.KernelWork} requires.
 *
 * <p>A Metal grid always starts at the first work item, so a range that starts anywhere else is
 * refused when it is set, instead of being dispatched from the first work item. A grid too large
 * for a Metal dispatch is refused before anything is reserved or encoded, so the refusal leaves
 * the memory of the arguments, the operator and the command runner of its context as they were.</p>
 */
public class MetalOperatorWorkRangeTest extends TestSuiteBase {
	/** Number of work items, and of elements written, in the dispatches that are made. */
	private static final int COUNT = 8;

	/**
	 * A non-zero offset is refused when it is set, and the operator then dispatches from the
	 * first work item as before.
	 */
	@Test(timeout = 60000)
	public void offsetOtherThanFirstWorkItemIsRefused() {
		withFillOperator(operator -> {
			operator.setGlobalWorkOffset(0);

			try {
				operator.setGlobalWorkOffset(COUNT);
				Assert.fail("A range starting at work item " + COUNT + " cannot be a Metal grid");
			} catch (UnsupportedOperationException expected) {
				Assert.assertTrue("The refusal should name the first work item",
						expected.getMessage().contains("first work item"));
			}

			Assert.assertEquals(0, operator.getGlobalWorkOffset());

			PackedCollection out = new PackedCollection(COUNT);
			operator.setGlobalWorkSize(COUNT);
			operator.accept(new Object[] { out }, null).waitFor();
			assertFilled(out, COUNT);
		});
	}

	/**
	 * A grid too large for a Metal dispatch is refused before anything is reserved or encoded:
	 * the refused dispatch leaves no reservation on the memory of its argument, and the operator,
	 * and the command buffer the runner keeps open for it, still dispatch correctly afterwards.
	 */
	@Test(timeout = 60000)
	public void gridTooLargeIsRefusedBeforeAnythingIsEncoded() {
		withFillOperator(operator -> {
			// Moves the argument into Metal memory, the memory a reservation would hold
			PackedCollection out = new PackedCollection(COUNT);
			operator.setGlobalWorkSize(1);
			operator.accept(new Object[] { out }, null).waitFor();

			operator.setGlobalWorkSize(Integer.MAX_VALUE + 1L);

			try {
				operator.accept(new Object[] { out }, null);
				Assert.fail("A grid of more than " + Integer.MAX_VALUE + " threads cannot be dispatched");
			} catch (UnsupportedOperationException expected) {
				Assert.assertTrue("The refusal should name the operator",
						expected.getMessage().contains("fill"));
			}

			NativeExecutionFailureTest.awaitGuardReleased(out);

			operator.setGlobalWorkSize(COUNT);
			operator.accept(new Object[] { out }, null).waitFor();
			assertFilled(out, COUNT);
		});
	}

	/**
	 * Runs {@code test} with an operator for a kernel named {@code fill}, which binds its one
	 * argument as a generated kernel does: the buffer at index 0, and the offset and size of the
	 * argument, inline, at indices 1 and 2. Each work item writes its own index plus the number of
	 * work items in the grid, so the values show how large the grid of the dispatch that wrote
	 * them was. The operator and its program are destroyed afterwards. Skips when no Metal backend
	 * is available.
	 *
	 * @param test the test to run with the operator
	 */
	private void withFillOperator(Consumer<MetalOperator> test) {
		MetalComputeContext metal = SemaphoreChainBatchingTest.metalContext();
		if (metal == null) {
			log("skipping, no MetalComputeContext available");
			return;
		}

		String type = metal.getDataContext().getPrecision().typeName();
		MetalProgram program = MetalProgram.create(metal,
				new OperationMetadata("fill", "Fill with index plus grid size"), "fill",
				"#include <metal_stdlib>\n" +
						"[[kernel]] void fill(device " + type + " *out [[buffer(0)]], " +
						"device int *offsetArr [[buffer(1)]], device int *sizeArr [[buffer(2)]], " +
						"uint global_id [[thread_position_in_grid]], " +
						"uint global_count [[threads_per_grid]]) { " +
						"out[offsetArr[0] + global_id] = global_id + global_count; }\n");
		program.compile();

		MetalOperator operator = new MetalOperator(metal, program, "fill", 1);

		try {
			test.accept(operator);
		} finally {
			operator.destroy();
			program.destroy();
		}
	}

	/**
	 * Asserts that the most recent dispatch of the {@code fill} kernel into {@code out} covered a
	 * grid of {@code count} work items starting at the first.
	 *
	 * @param out   the argument of the dispatch
	 * @param count the number of work items expected
	 */
	private void assertFilled(PackedCollection out, int count) {
		for (int i = 0; i < count; i++) {
			Assert.assertEquals("element " + i, i + count, out.toDouble(i), 0.0);
		}
	}
}
