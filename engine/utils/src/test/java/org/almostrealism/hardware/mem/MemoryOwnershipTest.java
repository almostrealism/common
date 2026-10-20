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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests that {@link MemoryDataAdapter#ownsMemory(Memory)} claims only memory the data
 * actually holds as its owner, so that acting on a claim of ownership can never free memory
 * that belongs to someone else.
 */
public class MemoryOwnershipTest extends TestSuiteBase {

	/** Number of collections in the population the ownership claims are checked against. */
	private static final int COUNT = 100;

	/** Length of each collection. */
	private static final int SIZE = 16;

	/**
	 * Freeing memory that one collection claims to own must leave every other collection
	 * intact. Among a hundred collections, {@code a} is removed from the list, and the memory
	 * of another member {@code b} is freed only if {@code a} claims to own it. Every one of the
	 * remaining ninety-nine collections must still be readable.
	 */
	@Test(timeout = 60000)
	public void dataDoesNotOwnAnotherCollectionsMemory() {
		Random random = new Random(7);
		List<PackedCollection> members = new ArrayList<>();
		for (int i = 0; i < COUNT; i++) {
			members.add(rand(shape(SIZE), random).evaluate());
		}

		int ai = random.nextInt(COUNT);
		int bi = (ai + 1 + random.nextInt(COUNT - 1)) % COUNT;
		PackedCollection a = members.get(ai);
		PackedCollection b = members.get(bi);

		Memory aMem = a.getMem();
		members.removeIf(member -> member.getMem() == aMem);
		Assert.assertEquals(COUNT - 1, members.size());

		Assert.assertTrue("a collection owns the memory it allocated", a.ownsMemory(a.getMem()));
		Assert.assertFalse("a collection does not own another collection's memory", a.ownsMemory(b.getMem()));

		if (a.ownsMemory(b.getMem())) {
			b.getMem().getProvider().deallocate(b.getMemLength(), b.getMem());
		}

		for (PackedCollection member : members) {
			Assert.assertEquals(SIZE, member.toArray().length);
		}
	}

	/**
	 * A {@link BytesView} does not own the memory it views: destroying the view leaves the
	 * owner's data readable.
	 */
	@Test(timeout = 60000)
	public void viewDoesNotOwnViewedMemory() {
		PackedCollection owner = new PackedCollection(SIZE);
		owner.fill(3.0);

		BytesView view = new BytesView(owner.getMem(), owner.getMemLength());
		Assert.assertFalse(view.ownsMemory(owner.getMem()));
		Assert.assertTrue(owner.ownsMemory(owner.getMem()));

		view.destroy();
		Assert.assertEquals(3.0, owner.toDouble(SIZE - 1), 0.0);
	}

	/**
	 * Memory a {@link BytesView} receives by being reassigned is its own, and destroying the
	 * view frees that memory without touching the memory it was created around.
	 */
	@Test(timeout = 60000)
	public void viewOwnsReassignedMemory() {
		PackedCollection owner = new PackedCollection(SIZE);
		owner.fill(5.0);

		BytesView view = new BytesView(owner.getMem(), owner.getMemLength());
		Memory replacement = owner.getMem().getProvider().allocate(owner.getMemLength());
		view.reassign(replacement);

		Assert.assertTrue(view.ownsMemory(replacement));
		Assert.assertFalse(view.ownsMemory(owner.getMem()));

		view.destroy();
		Assert.assertEquals(5.0, owner.toDouble(0), 0.0);
	}
}
