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
import io.almostrealism.scope.ArrayVariable;
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

/**
 * Regression coverage for the invariant that {@link MemoryDataArgumentMap} never assigns the same
 * region of its aggregate buffer to two unrelated {@link org.almostrealism.hardware.MemoryData}
 * providers.
 *
 * <p>The offset of each folded root is assigned strictly monotonically (each root takes the next
 * {@link org.almostrealism.hardware.MemoryData#getDelegatedLength() delegated length} elements
 * after the previous), so distinct roots occupy disjoint half-open ranges; and views into the same
 * underlying memory, and a provider referenced more than once, resolve through the map's root
 * memory dedup to a single shared slice rather than a fresh region. These tests pin both halves of
 * that invariant — distinct roots are disjoint, shared roots coincide — by folding arguments of
 * several sizes directly into a map and checking the resulting layout, by copying each root into
 * its slice and asserting the aggregate holds exactly the concatenation of the sources (so an
 * overlap would show as one root clobbering another), and by confirming two separate maps (as two
 * compilations would use) fold into separate buffers with the same layout.</p>
 */
public class AggregateRegionDisjointnessTest extends TestSuiteBase {

	/**
	 * Distinct providers of several different sizes each occupy their own disjoint region of the
	 * aggregate: the layout places them back to back, and copying every root in leaves the aggregate
	 * holding exactly the concatenation of the sources, which it could not if any two regions
	 * overlapped.
	 */
	@Test(timeout = 60000)
	public void distinctProvidersOfDifferentSizesOccupyDisjointRegions() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection a = pack(1.0, 2.0);
			 PackedCollection b = pack(3.0, 4.0, 5.0);
			 PackedCollection c = pack(6.0);
			 PackedCollection d = pack(7.0, 8.0, 9.0, 10.0)) {
			MemoryDataArgumentMap map = createMap(aggregates);

			try {
				map.get(p(a));
				map.get(p(b));
				map.get(p(c));
				map.get(p(d));

				Assert.assertEquals(4, map.getFoldedArgumentCount());
				Assert.assertEquals(10, map.getAggregateLength());
				Assert.assertEquals("len=10 [0:2,2:3,5:1,6:4,]", map.describeAggregate());

				Semaphore done = Submittable.submit(map.getPrepareOperations(), null);
				if (done != null) done.waitFor();

				Assert.assertEquals(1, aggregates.size());
				Assert.assertArrayEquals(
						new double[] { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0 },
						aggregates.get(0).toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * A provider referenced twice in the same map is folded once: the second reference matches the
	 * value already folded and reuses its slice, so no second region is allocated, and a distinct
	 * provider folded afterwards starts right after the single shared slice.
	 */
	@Test(timeout = 60000)
	public void sameProviderReferencedTwiceSharesOneSlice() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection shared = pack(1.0, 2.0, 3.0);
			 PackedCollection other = pack(4.0, 5.0)) {
			MemoryDataArgumentMap map = createMap(aggregates);

			try {
				map.get(p(shared));
				map.get(p(other));
				map.get(p(shared));

				Assert.assertEquals(2, map.getFoldedArgumentCount());
				Assert.assertEquals(5, map.getAggregateLength());
				Assert.assertEquals("len=5 [0:3,3:2,]", map.describeAggregate());

				Semaphore done = Submittable.submit(map.getPrepareOperations(), null);
				if (done != null) done.waitFor();

				Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0, 5.0 },
						aggregates.get(0).toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * Two different views into the same underlying memory resolve to one folded slice covering the
	 * whole root, because the map keys its dedup on the root memory, not on the view: a sub-range at
	 * the front and a sub-range at the back of one buffer fold that buffer once, and a separate
	 * buffer folded afterwards takes a disjoint region after it.
	 */
	@Test(timeout = 60000)
	public void viewsIntoTheSameRootShareOneSlice() {
		List<PackedCollection> aggregates = new ArrayList<>();

		try (PackedCollection buffer = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
			 PackedCollection separate = pack(7.0, 8.0)) {
			PackedCollection front = buffer.range(shape(2), 0);
			PackedCollection back = buffer.range(shape(3), 3);
			MemoryDataArgumentMap map = createMap(aggregates);

			try {
				ArrayVariable<?> frontArg = map.get(p(front));
				ArrayVariable<?> backArg = map.get(p(back));
				map.get(p(separate));

				Assert.assertEquals(2, map.getFoldedArgumentCount());
				Assert.assertEquals(8, map.getAggregateLength());
				Assert.assertEquals("len=8 [0:6,6:2,]", map.describeAggregate());

				// TODO(review): every folded root's root delegate is the aggregate argument, so this holds for distinct roots too
				Assert.assertSame(frontArg.getRootDelegate(), backArg.getRootDelegate());

				Semaphore done = Submittable.submit(map.getPrepareOperations(), null);
				if (done != null) done.waitFor();

				Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0 },
						aggregates.get(0).toArray(), 0.0);
			} finally {
				map.destroy();
			}
		}
	}

	/**
	 * Two maps folding the same providers in the same order — as two compilations of the same
	 * computation would — produce the same layout but allocate separate aggregate buffers, so
	 * neither map's slices can ever alias the other's: reusing a layout across compilations shares
	 * positions, never memory.
	 */
	@Test(timeout = 60000)
	public void separateMapsFoldIntoSeparateBuffersWithTheSameLayout() {
		List<PackedCollection> first = new ArrayList<>();
		List<PackedCollection> second = new ArrayList<>();

		try (PackedCollection a = pack(1.0, 2.0);
			 PackedCollection b = pack(3.0, 4.0, 5.0)) {
			MemoryDataArgumentMap one = createMap(first);
			MemoryDataArgumentMap two = createMap(second);

			try {
				one.get(p(a));
				one.get(p(b));
				two.get(p(a));
				two.get(p(b));

				Assert.assertEquals(one.describeAggregate(), two.describeAggregate());
				Assert.assertEquals(one.describeAggregatePositions(), two.describeAggregatePositions());

				Semaphore firstDone = Submittable.submit(one.getPrepareOperations(), null);
				if (firstDone != null) firstDone.waitFor();
				Semaphore secondDone = Submittable.submit(two.getPrepareOperations(), null);
				if (secondDone != null) secondDone.waitFor();

				Assert.assertEquals(1, first.size());
				Assert.assertEquals(1, second.size());
				Assert.assertNotSame(first.get(0), second.get(0));
				Assert.assertNotSame(first.get(0).getMem(), second.get(0).getMem());
				Assert.assertArrayEquals(first.get(0).toArray(), second.get(0).toArray(), 0.0);
			} finally {
				one.destroy();
				two.destroy();
			}
		}
	}

	/**
	 * End-to-end, the observable symptom the reported defect would produce: several assignments,
	 * each reading its own pair of small (aggregated) inputs into its own small destination, must
	 * each land only their own result. If two unrelated inputs shared an aggregate region, one
	 * assignment's inputs would overwrite another's and the results would be wrong.
	 */
	@Test(timeout = 60000)
	public void distinctAggregatedComputationsDoNotBleedIntoEachOther() {
		try (PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);
			 PackedCollection b = pack(10.0, 20.0, 30.0, 40.0);
			 PackedCollection c = pack(100.0, 200.0, 300.0, 400.0);
			 PackedCollection d = pack(5.0, 6.0, 7.0, 8.0);
			 PackedCollection first = new PackedCollection(4);
			 PackedCollection second = new PackedCollection(4);
			 PackedCollection third = new PackedCollection(4)) {
			OperationList op = new OperationList("distinct sums");
			op.add(new Assignment<>(1, p(first.each()), cp(a.each()).add(cp(b.each()))));
			op.add(new Assignment<>(1, p(second.each()), cp(c.each()).subtract(cp(d.each()))));
			op.add(new Assignment<>(1, p(third.each()), cp(a.each()).multiply(cp(d.each()))));

			op.get().run();

			Assert.assertArrayEquals(new double[] { 11.0, 22.0, 33.0, 44.0 }, first.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 95.0, 194.0, 293.0, 392.0 }, second.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 5.0, 12.0, 21.0, 32.0 }, third.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, a.toArray(), 1e-6);
			Assert.assertArrayEquals(new double[] { 5.0, 6.0, 7.0, 8.0 }, d.toArray(), 1e-6);
		}
	}

	/**
	 * Creates an argument map that folds small arguments, recording every aggregate buffer it
	 * creates so the tests can inspect the folded layout directly.
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
}
