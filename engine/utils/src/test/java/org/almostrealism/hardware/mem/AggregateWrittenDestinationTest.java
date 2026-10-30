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

package org.almostrealism.hardware.mem;

import io.almostrealism.concurrent.Submittable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tests the copy-back of a kernel whose computation declares the only memory it writes
 * ({@link io.almostrealism.code.Computation#getWrittenDestination()}): only the aggregated slice
 * of that memory is carried back, never the slices of the arguments the kernel merely read.
 */
public class AggregateWrittenDestinationTest extends TestSuiteBase {

	/**
	 * An assignment writes its destination and nothing else, so it declares the destination
	 * producer as the memory it writes.
	 */
	@Test(timeout = 30000)
	public void assignmentDeclaresItsDestination() {
		try (PackedCollection destination = new PackedCollection(4);
			 PackedCollection value = pack(1.0, 2.0, 3.0, 4.0)) {
			Assignment<PackedCollection> assignment = new Assignment<>(1, p(destination), cp(value));
			Assert.assertSame(assignment.getInputs().get(0), assignment.getWrittenDestination());
		}
	}

	/**
	 * Of two aggregated roots, the copy-back for the written one moves only that root's slice:
	 * the other root keeps its own contents even though its slice of the aggregate was changed.
	 */
	@Test(timeout = 60000)
	public void copyBackOfWrittenRootLeavesReadRootsAlone() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), length -> {
						PackedCollection aggregate = new PackedCollection(length);
						aggregates.add(aggregate);
						return aggregate;
					});

			try {
				map.get(p(read));
				map.get(p(written));
				Assert.assertEquals(4, map.getAggregateLength());
				Assert.assertEquals(2, map.getPostprocessOperations(null).size());
				Assert.assertEquals(1, map.getPostprocessOperationsOf(p(written)).size());

				Semaphore copiedIn = Submittable.submit(map.getPrepareOperations(), null);
				if (copiedIn != null) copiedIn.waitFor();
				Assert.assertEquals(1, aggregates.size());

				aggregates.get(0).fill(10.0, 20.0, 30.0, 40.0);
				Semaphore copiedOut = Submittable.submit(map.getPostprocessOperationsOf(p(written)), null);
				if (copiedOut != null) copiedOut.waitFor();

				Assert.assertArrayEquals(new double[] { 1.0, 2.0 }, read.toArray(), 0.0);
				Assert.assertArrayEquals(new double[] { 30.0, 40.0 }, written.toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A destination that is computed rather than provided cannot be matched to an aggregated
	 * root, since the kernel may reach one through it, so its copy-back is every writable slice.
	 */
	@Test(timeout = 60000)
	public void copyBackOfComputedDestinationIsEveryWritableSlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				map.get(p(read));
				map.get(p(written));

				Supplier<Evaluable<? extends PackedCollection>> computed = () -> args -> written;
				Assert.assertEquals(map.getPostprocessOperations(null),
						map.getPostprocessOperationsOf(computed));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * An in-place assignment over small (aggregated) arguments, run as an operation with no
	 * explicit output, still carries its result back into the destination, and leaves the
	 * argument it only read unchanged.
	 */
	@Test(timeout = 60000)
	public void inPlaceAssignmentOfAggregatedArgumentsWritesItsDestination() {
		try (PackedCollection x = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
			 PackedCollection y = pack(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)) {
			OperationList op = new OperationList("in place add");
			op.add(new Assignment<>(1, p(x.each()), cp(x.each()).add(cp(y.each()))));
			op.get().run();

			Assert.assertArrayEquals(new double[] { 11.0, 22.0, 33.0, 44.0, 55.0, 66.0, 77.0, 88.0 },
					x.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0 },
					y.toArray(), 1e-6);
		}
	}
}
