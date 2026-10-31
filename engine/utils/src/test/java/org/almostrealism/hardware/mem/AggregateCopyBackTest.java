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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Scope;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Tests that the copy-back after a kernel with aggregated arguments is limited to the slices
 * of the aggregate that the kernel's own {@link Scope} may write, and that nothing beyond the
 * scope is consulted to decide it: the scope is read, and a scope whose writes cannot be
 * enumerated copies every slice back.
 */
public class AggregateCopyBackTest extends TestSuiteBase {

	/**
	 * A scope that assigns one aggregated argument from another writes only the first one's
	 * slice, so only that slice is copied back: the root the scope only read keeps its own
	 * contents even though its staged slice of the aggregate was changed.
	 */
	@Test(timeout = 60000)
	public void copyBackIsLimitedToTheSlicesTheScopeWrites() {
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
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				Assert.assertEquals(4, map.getAggregateLength());

				Scope<?> kernel = new Scope<>("copyBack");
				for (int i = 0; i < 2; i++) {
					kernel.assign(writtenArg.reference(new IntegerConstant(i)),
							readArg.reference(new IntegerConstant(i)));
				}

				BitSet slices = map.limitCopyBackTo(kernel);
				Assert.assertFalse(slices.get(0));
				Assert.assertTrue(slices.get(1));
				Assert.assertEquals(1, map.getPostprocessOperations(null).size());

				Semaphore copiedIn = Submittable.submit(map.getPrepareOperations(), null);
				if (copiedIn != null) copiedIn.waitFor();
				Assert.assertEquals(1, aggregates.size());

				aggregates.get(0).fill(10.0, 20.0, 30.0, 40.0);
				Semaphore copiedOut = Submittable.submit(map.getPostprocessOperations(null), null);
				if (copiedOut != null) copiedOut.waitFor();

				Assert.assertArrayEquals(new double[] { 1.0, 2.0 }, read.toArray(), 0.0);
				Assert.assertArrayEquals(new double[] { 30.0, 40.0 }, written.toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A write whose position has no upper bound (a kernel index with no kernel size) may reach
	 * any slice from its lower bound on, so the slice before it is left alone while every slice
	 * from the written one on is copied back.
	 */
	@Test(timeout = 60000)
	public void unboundedWriteReachesEverySliceFromItsLowerBound() {
		try (PackedCollection first = pack(1.0, 2.0);
			 PackedCollection second = pack(3.0, 4.0);
			 PackedCollection third = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				map.get(p(first));
				ArrayVariable<?> secondArg = map.get(p(second));
				map.get(p(third));

				Scope<?> kernel = new Scope<>("unbounded");
				kernel.assign(secondArg.reference(new KernelIndex()), new IntegerConstant(0));

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertFalse(slices.get(0));
				Assert.assertTrue(slices.get(1));
				Assert.assertTrue(slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A scope whose writes cannot be enumerated from its assignments (here, one that calls a
	 * method) may write anything, so every slice is reported as written.
	 */
	@Test(timeout = 60000)
	public void scopeWithOpaqueStatementsWritesEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<?> kernel = new Scope<>("opaque");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)),
						readArg.reference(new IntegerConstant(0)));
				kernel.getMethods().add(new Method<>("external", new IntegerConstant(0)));

				BitSet slices = map.getWrittenSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertTrue(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * The copy-back is built once, so the written slices must be known before it is: limiting
	 * it afterwards would leave the already built copy-back in force, and is refused.
	 */
	@Test(timeout = 60000)
	public void writtenSlicesCannotBeGivenAfterTheCopyBackIsBuilt() {
		try (PackedCollection read = pack(1.0, 2.0)) {
			MemoryDataArgumentMap map = MemoryDataArgumentMap.create(
					Hardware.getLocalHardware().getComputeContext(), PackedCollection::new);

			try {
				map.get(p(read));
				Assert.assertEquals(1, map.getPostprocessOperations(null).size());
				try {
					map.setWrittenSlices(new BitSet());
					Assert.fail("Written slices given after the copy-back was built were accepted");
				} catch (IllegalStateException expected) {
					Assert.assertEquals(1, map.getPostprocessOperations(null).size());
				}
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

	/**
	 * Assignments into two different small destinations from the same small inputs, run
	 * repeatedly, each carry their result back into their own destination only: a kernel
	 * reused for the second assignment limits its copy-back to the slice the shared compiled
	 * scope writes.
	 */
	@Test(timeout = 60000)
	public void reusedKernelCopiesBackItsOwnDestination() {
		try (PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);
			 PackedCollection b = pack(10.0, 20.0, 30.0, 40.0);
			 PackedCollection first = new PackedCollection(4);
			 PackedCollection second = new PackedCollection(4)) {
			OperationList op = new OperationList("two sums");
			op.add(new Assignment<>(1, p(first.each()), cp(a.each()).add(cp(b.each()))));
			op.add(new Assignment<>(1, p(second.each()), cp(b.each()).add(cp(a.each()))));

			Runnable run = op.get();
			for (int i = 0; i < 3; i++) {
				run.run();
			}

			double[] expected = { 11.0, 22.0, 33.0, 44.0 };
			Assert.assertArrayEquals(expected, first.toArray(), 1e-6);
			Assert.assertArrayEquals(expected, second.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, a.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 10.0, 20.0, 30.0, 40.0 }, b.toArray(), 1e-6);
		}
	}
}
