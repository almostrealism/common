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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.StaticReference;
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
 * Tests that the copy-in before a kernel with aggregated arguments leaves out the slices of the
 * aggregate that the kernel's own {@link Scope} never reads, whenever nothing would observe the
 * staged value, and that nothing beyond the scope is consulted to decide it: a scope whose reads
 * cannot be enumerated copies every slice in, a slice the kernel both reads and writes is still
 * copied in, and a slice that is copied back keeps its copy-in, because the kernel is not known
 * to write every element of it.
 */
public class AggregateCopyInTest extends TestSuiteBase {

	/**
	 * A scope that assigns one aggregated argument from another reads only the second one's
	 * slice; the slice it writes is not read, and a third argument it never mentions is neither.
	 */
	@Test(timeout = 60000)
	public void readSlicesAreReadOffTheScope() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0);
			 PackedCollection unused = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.get(p(unused));

				BitSet slices = map.getReadSlices(copyKernel(writtenArg, readArg));
				Assert.assertTrue(slices.get(0));
				Assert.assertFalse(slices.get(1));
				Assert.assertFalse(slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A slice the kernel only writes is not copied in by a dispatch that does not copy back, so
	 * its region of the aggregate keeps whatever it held, while the slice the kernel reads is
	 * staged. A dispatch that copies back still copies the written slice in, because the
	 * copy-back moves the whole slice. A slice the kernel neither reads nor writes is copied in by
	 * neither.
	 */
	@Test(timeout = 60000)
	public void unreadSliceIsNotCopiedInWithoutCopyBack() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0);
			 PackedCollection unused = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = createMap(aggregates);

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.get(p(unused));

				Scope<?> kernel = copyKernel(writtenArg, readArg);
				map.limitCopyBackTo(kernel);
				map.limitCopyInTo(kernel);

				Assert.assertEquals(1, map.getPrepareOperations(false).size());
				Assert.assertEquals(2, map.getPrepareOperations(true).size());
				Assert.assertEquals(1, map.getPostprocessOperations(null).size());

				Assert.assertEquals(1, aggregates.size());
				aggregates.get(0).fill(-1.0, -1.0, -1.0, -1.0, -1.0, -1.0);
				submit(map.getPrepareOperations(false));
				Assert.assertArrayEquals(new double[] { 1.0, 2.0, -1.0, -1.0, -1.0, -1.0 },
						aggregates.get(0).toArray(), 0.0);

				submit(map.getPrepareOperations(true));
				Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0, -1.0, -1.0 },
						aggregates.get(0).toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * The prepare operations of each kind of dispatch, and the copy-back, are unmodifiable
	 * selections from the one list of slice copies, which is built once: every selection made for
	 * the same kind of dispatch holds the same copy operations, and a dispatch naming an explicit
	 * output gets a selection that leaves the output's slice out.
	 */
	@Test(timeout = 60000)
	public void selectionsShareTheSameCopies() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<?> kernel = copyKernel(writtenArg, readArg);
				map.limitCopyBackTo(kernel);
				map.limitCopyInTo(kernel);

				List<Submittable> withCopyBack = map.getPrepareOperations(true);
				List<Submittable> withoutCopyBack = map.getPrepareOperations(false);
				List<Submittable> copyBack = map.getPostprocessOperations(null);

				Assert.assertEquals(withCopyBack, map.getPrepareOperations(true));
				Assert.assertEquals(withCopyBack, map.getPrepareOperations());
				Assert.assertEquals(withoutCopyBack, map.getPrepareOperations(false));
				Assert.assertEquals(copyBack, map.getPostprocessOperations(null));
				Assert.assertTrue(withCopyBack.containsAll(withoutCopyBack));
				Assert.assertEquals(2, withCopyBack.size());
				Assert.assertEquals(1, withoutCopyBack.size());
				Assert.assertEquals(1, copyBack.size());

				assertUnmodifiable(withCopyBack);
				assertUnmodifiable(withoutCopyBack);
				assertUnmodifiable(copyBack);

				Assert.assertTrue(map.getPostprocessOperations(written).isEmpty());
				Assert.assertEquals(1, map.getPostprocessOperations(read).size());
				Assert.assertEquals(1, copyBack.size());
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A slice the kernel reads and writes (a read-modify-write, such as an accumulator) is read,
	 * so it is copied in by every dispatch whether or not the dispatch copies back.
	 */
	@Test(timeout = 60000)
	public void readModifyWriteSliceIsCopiedIn() {
		try (PackedCollection accumulator = pack(1.0, 2.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> arg = map.get(p(accumulator));

				Scope<?> kernel = new Scope<>("accumulate");
				kernel.assign(arg.reference(new IntegerConstant(0)),
						arg.reference(new IntegerConstant(0)).add(new IntegerConstant(1)));

				Assert.assertTrue(map.getWrittenSlices(kernel).get(0));
				Assert.assertTrue(map.limitCopyInTo(kernel).get(0));
				Assert.assertEquals(1, map.getPrepareOperations(false).size());
				Assert.assertEquals(1, map.getPrepareOperations(true).size());
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * An element read only to locate the element an assignment writes (an index into the
	 * destination) is a read, while the destination element itself is not.
	 */
	@Test(timeout = 60000)
	public void destinationPositionIsRead() {
		try (PackedCollection index = pack(0.0, 1.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> indexArg = map.get(p(index));
				ArrayVariable<?> writtenArg = map.get(p(written));

				Scope<?> kernel = new Scope<>("scatter");
				kernel.assign(writtenArg.reference(indexArg.reference(new IntegerConstant(0))),
						new IntegerConstant(7));

				BitSet slices = map.getReadSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertFalse(slices.get(1));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A read at the first element of a slice is not attributed to the slice that ends right
	 * before it, and a read at its last element is not attributed to the slice that starts right
	 * after it.
	 */
	@Test(timeout = 60000)
	public void readAtSliceEdgeReachesOnlyItsOwnSlice() {
		try (PackedCollection first = pack(1.0, 2.0);
			 PackedCollection second = pack(3.0, 4.0);
			 PackedCollection third = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> firstArg = map.get(p(first));
				ArrayVariable<?> secondArg = map.get(p(second));
				map.get(p(third));

				for (int i = 0; i < 2; i++) {
					Scope<?> kernel = new Scope<>("edge" + i);
					kernel.assign(firstArg.reference(new IntegerConstant(0)),
							secondArg.reference(new IntegerConstant(i)));

					BitSet slices = map.getReadSlices(kernel);
					Assert.assertFalse(slices.get(0));
					Assert.assertTrue(slices.get(1));
					Assert.assertFalse(slices.get(2));
				}
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A scope whose reads cannot be enumerated from its assignments (here, one that calls a
	 * method) may read anything, so every slice is reported as read and copied in by every
	 * dispatch.
	 */
	@Test(timeout = 60000)
	public void scopeWithOpaqueStatementsReadsEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0);
			 PackedCollection unused = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.get(p(unused));

				Scope<?> kernel = copyKernel(writtenArg, readArg);
				kernel.getMethods().add(new Method<>("external", new IntegerConstant(0)));

				BitSet slices = map.limitCopyInTo(kernel);
				Assert.assertEquals(3, slices.cardinality());
				Assert.assertEquals(3, map.getPrepareOperations(false).size());
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A static reference whose referent is an aggregated argument names no element of it, so it
	 * may read any element of the aggregate it shares a root with: every slice is read and copied
	 * in, even by a dispatch that does not copy back.
	 */
	@Test(timeout = 60000)
	public void staticReferenceToArgumentReadsEverySlice() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0);
			 PackedCollection unused = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.get(p(unused));

				Scope<?> kernel = new Scope<>("staticRead");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)),
						new StaticReference<>(Double.class, "external", readArg));

				BitSet slices = map.limitCopyInTo(kernel);
				Assert.assertEquals(3, slices.cardinality());
				Assert.assertEquals(3, map.getPrepareOperations(false).size());
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A static reference with no referent is emitted verbatim, so code that mentions the name of
	 * the aggregate may read any of it, while code that does not mention it reads none of it.
	 */
	@Test(timeout = 60000)
	public void staticCodeReadsTheAggregateOnlyWhenItNamesIt() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				String aggregateName = readArg.getRootDelegate().getName();

				Scope<?> naming = new Scope<>("staticNaming");
				naming.assign(writtenArg.reference(new IntegerConstant(0)),
						new StaticReference<>(Double.class, aggregateName + "[0]"));
				Assert.assertEquals(2, map.getReadSlices(naming).cardinality());

				Scope<?> unrelated = new Scope<>("staticUnrelated");
				unrelated.assign(writtenArg.reference(new IntegerConstant(0)),
						new StaticReference<>(Double.class, "unrelatedValue"));
				Assert.assertEquals(0, map.getReadSlices(unrelated).cardinality());
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * Generated expressions share subtrees, so an expression that is small as a graph can be
	 * exponentially large as a tree. Here every level refers to the level below it twice, so a
	 * walk that followed every path would visit hundreds of millions of nodes (close to the
	 * largest tree an expression may represent); the reads are found by visiting each node once,
	 * and they are still attributed to the right slices.
	 */
	@Test(timeout = 60000)
	public void sharedSubtreeIsWalkedOnce() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0);
			 PackedCollection unused = pack(5.0, 6.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.get(p(unused));

				Expression<Double> value = readArg.reference(new IntegerConstant(1)).toDouble();
				for (int i = 0; i < 28; i++) {
					value = Max.of(value, value);
				}

				Scope<?> kernel = new Scope<>("sharedSubtree");
				kernel.assign(writtenArg.reference(new IntegerConstant(0)), value);

				BitSet slices = map.getReadSlices(kernel);
				Assert.assertTrue(slices.get(0));
				Assert.assertFalse(slices.get(1));
				Assert.assertFalse(slices.get(2));
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A map that is never told what the kernel reads copies every slice in, whether or not the
	 * dispatch copies back.
	 */
	@Test(timeout = 60000)
	public void unknownReadSetCopiesEverySliceIn() {
		try (PackedCollection read = pack(1.0, 2.0);
			 PackedCollection written = pack(3.0, 4.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				ArrayVariable<?> readArg = map.get(p(read));
				ArrayVariable<?> writtenArg = map.get(p(written));
				map.limitCopyBackTo(copyKernel(writtenArg, readArg));

				Assert.assertEquals(2, map.getPrepareOperations(false).size());
				Assert.assertEquals(2, map.getPrepareOperations(true).size());
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * The copy-in is built once, so the read slices must be known before it is: limiting it
	 * afterwards would leave the already built copy-in in force, and is refused.
	 */
	@Test(timeout = 60000)
	public void readSlicesCannotBeGivenAfterTheCopyInIsBuilt() {
		try (PackedCollection read = pack(1.0, 2.0)) {
			MemoryDataArgumentMap map = createMap(new ArrayList<>());

			try {
				map.get(p(read));
				Assert.assertEquals(1, map.getPrepareOperations(false).size());
				try {
					map.setReadSlices(new BitSet());
					Assert.fail("Read slices given after the copy-in was built were accepted");
				} catch (IllegalStateException expected) {
					Assert.assertEquals(1, map.getPrepareOperations(false).size());
				}
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * An assignment into a view of a small (aggregated) buffer writes only part of the buffer's
	 * slice and reads none of it. The dispatch copies the whole slice back, so the elements
	 * outside the view must keep their own values: the slice is still copied in, by every
	 * dispatch, so a value changed outside the view between dispatches is kept as well.
	 */
	@Test(timeout = 60000)
	public void partialWriteOfUnreadSliceKeepsTheRestOfItsSource() {
		try (PackedCollection buffer = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
			 PackedCollection value = pack(10.0, 20.0)) {
			PackedCollection view = buffer.range(shape(2), 2);

			OperationList op = new OperationList("partial write");
			op.add(new Assignment<>(1, p(view.each()), cp(value.each())));

			Runnable run = op.get();
			run.run();
			Assert.assertArrayEquals(new double[] { 1.0, 2.0, 10.0, 20.0, 5.0, 6.0 },
					buffer.toArray(), 0.0);

			buffer.setMem(0, 100.0);
			run.run();
			Assert.assertArrayEquals(new double[] { 100.0, 2.0, 10.0, 20.0, 5.0, 6.0 },
					buffer.toArray(), 0.0);
		}
	}

	/**
	 * Creates an argument map that folds small arguments, recording every aggregate it creates.
	 *
	 * @param aggregates receives each aggregate buffer the map creates
	 * @return the argument map
	 */
	private static MemoryDataArgumentMap createMap(List<PackedCollection> aggregates) {
		return MemoryDataArgumentMap.create(Hardware.getLocalHardware().getComputeContext(), length -> {
			PackedCollection aggregate = new PackedCollection(length);
			aggregates.add(aggregate);
			return aggregate;
		});
	}

	/**
	 * Creates a scope that copies the two elements of {@code source} into {@code destination}.
	 *
	 * @param destination the argument the scope writes
	 * @param source      the argument the scope reads
	 * @return the scope
	 */
	private static Scope<?> copyKernel(ArrayVariable<?> destination, ArrayVariable<?> source) {
		Scope<?> kernel = new Scope<>("copyIn");
		for (int i = 0; i < 2; i++) {
			kernel.assign(destination.reference(new IntegerConstant(i)),
					source.reference(new IntegerConstant(i)));
		}

		return kernel;
	}

	/**
	 * Asserts that the given list of operations rejects modification, so a dispatch cannot alter
	 * the copy operations the map selected for it.
	 *
	 * @param operations the selected operations
	 */
	private static void assertUnmodifiable(List<Submittable> operations) {
		int size = operations.size();
		Submittable extra = dependsOn -> dependsOn;

		try {
			operations.add(extra);
			Assert.fail("A selection of copy operations accepted a modification");
		} catch (UnsupportedOperationException expected) {
			Assert.assertEquals(size, operations.size());
			Assert.assertFalse(operations.contains(extra));
		}
	}

	/**
	 * Submits the given copy operations and waits for them to complete.
	 *
	 * @param operations the copy operations
	 */
	private static void submit(List<Submittable> operations) {
		Semaphore done = Submittable.submit(operations, null);
		if (done != null) done.waitFor();
	}
}
