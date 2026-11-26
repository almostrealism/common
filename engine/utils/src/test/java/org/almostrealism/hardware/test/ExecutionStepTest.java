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
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.ExecutionStep;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests {@link OperationList#getExecutionSteps()}: the units a list runs one after another,
 * with the memory each touches and writes, following the rule {@link OperationList#get()}
 * uses to decide whether a list compiles into one kernel.
 */
public class ExecutionStepTest extends TestSuiteBase {
	/**
	 * A list that compiles into one kernel is one step, which touches every collection any
	 * member reads or writes and writes every destination.
	 */
	@Test(timeout = 60000)
	public void fusedListIsOneStep() {
		PackedCollection a = new PackedCollection(8);
		PackedCollection b = new PackedCollection(8);
		PackedCollection c = new PackedCollection(8);

		OperationList list = new OperationList("fused");
		list.add(a(p(b), cp(a).multiply(2.0)));
		list.add(a(p(c), cp(b).add(1.0)));
		Assert.assertTrue(list.isCompiledAsKernel());

		List<ExecutionStep> steps = list.getExecutionSteps();
		Assert.assertEquals(1, steps.size());
		ExecutionStep step = steps.get(0);
		Assert.assertTrue(step.isResolvable());
		assertContains(step.getTouched().getRegions(), a, b, c);
		assertContains(step.getWritten().getRegions(), b, c);
		Assert.assertFalse(step.getWritten().getRegions().contains(a));
	}

	/**
	 * A list with a member that is not a computation runs its members one by one, so each
	 * member is a step of its own, in order; a member that exposes no process tree is not
	 * resolvable, while the others are.
	 */
	@Test(timeout = 60000)
	public void listWithOpaqueMemberHasOneStepPerMember() {
		PackedCollection a = new PackedCollection(8);
		PackedCollection b = new PackedCollection(8);

		OperationList list = new OperationList("sequence");
		list.add(a(p(b), cp(a).multiply(2.0)));
		list.add(() -> () -> { });
		list.add(a(p(a), cp(b).add(1.0)));
		Assert.assertFalse(list.isCompiledAsKernel());

		List<ExecutionStep> steps = list.getExecutionSteps();
		Assert.assertEquals(3, steps.size());
		Assert.assertTrue(steps.get(0).isResolvable());
		Assert.assertFalse(steps.get(1).isResolvable());
		Assert.assertTrue(steps.get(2).isResolvable());

		assertContains(steps.get(0).getWritten().getRegions(), b);
		assertContains(steps.get(2).getWritten().getRegions(), a);
		Assert.assertTrue(steps.get(1).getTouched().getRegions().isEmpty());
	}

	/**
	 * The destination of an assignment counts as written even when it is a reshape of a
	 * collection rather than the collection's own provider.
	 */
	@Test(timeout = 60000)
	public void reshapedDestinationIsWritten() {
		PackedCollection a = new PackedCollection(2, 4);
		PackedCollection b = new PackedCollection(8);

		OperationList list = new OperationList("reshaped");
		list.add(a(traverseEach(p(a)), cp(b).reshape(2, 4).multiply(3.0)));

		List<ExecutionStep> steps = list.getExecutionSteps();
		Assert.assertEquals(1, steps.size());
		assertContains(steps.get(0).getWritten().getRegions(), a);
		assertContains(steps.get(0).getTouched().getRegions(), a, b);
	}

	/**
	 * Asserts that the given regions include each expected collection, compared by identity.
	 *
	 * @param regions  the regions reported by a step
	 * @param expected the collections that must be among them
	 */
	private void assertContains(List<MemoryData> regions, PackedCollection... expected) {
		for (PackedCollection e : expected) {
			Assert.assertTrue("missing " + e.getShape() + " from " + regions.size() + " regions",
					regions.stream().anyMatch(r -> r == e || r.getRootDelegate() == e.getRootDelegate()));
		}
	}
}
