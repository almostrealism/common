/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.kernel.DefaultKernelStructureContext;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationAdapter;
import org.almostrealism.collect.computations.RepeatedProducerComputationAdapter;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Tests for repeated traversable computation operations.
 */
public class RepeatedTraversableComputationTests extends TestSuiteBase {

	/**
	 * Tests addition of repeated traversable computations.
	 */
	@Test(timeout = 60000)
	public void add() {
		OperationProfile profile = new OperationProfile("HardwareOperator");
		HardwareOperator.timingListener = profile.getTimingListener();

		int len = 60000;

		PackedCollection a = new PackedCollection(len).randFill();
		PackedCollection b = new PackedCollection(len).randFill();

		PackedCollection out = new PackedCollection(len);

		Evaluable<PackedCollection> ev = add(v(shape(-1), 0), v(shape(-1), 1)).get();

		verboseLog(() -> {
			for (int i = 0; i < 100; i++) {
				ev.into(out.traverse(1)).evaluate(a.traverse(1), b.traverse(1));
			}
		});

		IntStream.range(0, len).forEach(i -> {
			double expected = a.toDouble(i) + b.toDouble(i);
			double actual = out.toDouble(i);
			assertEquals(expected, actual);
		});

		out.clear();

		Evaluable<PackedCollection> rev = ((CollectionProducerComputationAdapter) add(v(shape(-1), 0), v(shape(-1), 1))).toRepeated().get();

		verboseLog(() -> {
			for (int i = 0; i < 100; i++) {
				rev.into(out).evaluate(a, b);
			}
		});

		IntStream.range(0, len).forEach(i -> {
			double expected = a.toDouble(i) + b.toDouble(i);
			double actual = out.toDouble(i);
			assertEquals(expected, actual);
		});

		profile.print();
	}

	/**
	 * Evaluates a {@code toRepeated()} computation in place (the output is also the
	 * first input) and verifies that every element, including the first, is correct.
	 *
	 * <p>The repeated adapter previously initialised output element 0 to zero before
	 * the loop ran. When the output aliases an input, that write clobbered element 0
	 * of the input before the loop read it, so element 0 came out holding only the
	 * second operand. This test fails on element 0 unless the adapter leaves the
	 * output uninitialised.
	 */
	@Test(timeout = 60000)
	public void addInPlace() {
		int len = 16;

		PackedCollection data = new PackedCollection(len).randFill();
		PackedCollection other = new PackedCollection(len).randFill();

		double[] expected = IntStream.range(0, len)
				.mapToDouble(i -> data.toDouble(i) + other.toDouble(i)).toArray();

		Evaluable<PackedCollection> rev = ((CollectionProducerComputationAdapter)
				add(v(shape(-1), 0), v(shape(-1), 1))).toRepeated().get();

		rev.into(data).evaluate(data, other);

		IntStream.range(0, len).forEach(i ->
				assertEquals("element " + i, expected[i], data.toDouble(i)));
	}

	/**
	 * The kernel scope of a repeated computation carries the computation's compute
	 * requirements, as every kernel scope built by {@code ComputationBase} does.
	 * The repeated kernel used to be built by hand and dropped them, so the scope
	 * reported no requirements for a computation that required the CPU.
	 */
	@Test(timeout = 60000)
	public void repeatedKernelCarriesComputeRequirements() {
		RepeatedProducerComputationAdapter repeated = ((CollectionProducerComputationAdapter)
				add(v(shape(-1), 0), v(shape(-1), 1))).toRepeated();
		repeated.setComputeRequirements(List.of(ComputeRequirement.CPU));

		MemoryDataArgumentMap arguments = MemoryDataArgumentMap.create(
				Hardware.getLocalHardware().getComputeContext(), null);
		KernelStructureContext context = new DefaultKernelStructureContext();
		repeated.prepareScope(arguments, context);

		Scope<PackedCollection> scope = repeated.getScope(context);
		assertEquals(List.of(ComputeRequirement.CPU), scope.getComputeRequirements());
	}
}
