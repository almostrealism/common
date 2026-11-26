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

package org.almostrealism.hardware.test;

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies how an {@link OperationList} describes itself from the members that do work. A
 * nested list that is functionally empty does no work: it is not a child of the list, it does
 * not decide whether the list is uniform, and so it must not decide the count of the list
 * either. The tests also cover how the list includes itself in the profile it runs under.
 */
public class OperationListStructureTest extends TestSuiteBase {

	/**
	 * A uniform list whose first member is an empty nested list has the count of its
	 * operations, not the zero count of the empty list ahead of them.
	 */
	@Test(timeout = 60000)
	public void leadingEmptyListDoesNotDecideCount() {
		PackedCollection source = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
		PackedCollection destination = new PackedCollection(8);

		OperationList list = new OperationList("leading empty list");
		list.add(new OperationList("nothing"));
		list.add(a(cp(destination.traverseEach()), cp(source.traverseEach()).multiply(2.0)));

		assertTrue(list.isUniform());
		assertEquals(1, list.getChildren().size());
		assertEquals(8, list.getCountLong());
	}

	/**
	 * A uniform list whose first member is an empty nested list compiles and computes the
	 * values of its operations.
	 */
	@Test(timeout = 120000)
	public void listWithLeadingEmptyListComputes() {
		PackedCollection source = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0);
		PackedCollection destination = new PackedCollection(8);

		OperationList list = new OperationList("leading empty list");
		list.add(new OperationList("nothing"));
		list.add(a(cp(destination.traverseEach()), cp(source.traverseEach()).multiply(2.0)));
		list.get().run();

		for (int i = 0; i < 8; i++) {
			assertEquals(2.0 * (i + 1), destination.toDouble(i));
		}
	}

	/**
	 * A list whose members are all functionally empty nested lists does no work: it has no
	 * children, and it runs without doing anything.
	 */
	@Test(timeout = 60000)
	public void nestedEmptyListsDoNoWork() {
		OperationList inner = new OperationList("inner");
		inner.add(new OperationList("innermost"));

		OperationList list = new OperationList("only empty lists");
		list.add(new OperationList("nothing"));
		list.add(inner);

		assertTrue(inner.isFunctionallyEmpty());
		assertTrue(list.isFunctionallyEmpty());
		assertTrue(list.getChildren().isEmpty());
		assertEquals(0, list.getDepth());

		list.get().run();
	}

	/**
	 * A nested list with a member that does work is a child of its parent, and its parent is
	 * not functionally empty.
	 */
	@Test(timeout = 60000)
	public void nestedListWithWorkIsAChild() {
		AtomicBoolean ran = new AtomicBoolean();

		OperationList inner = new OperationList("inner");
		inner.add(() -> () -> ran.set(true));

		OperationList list = new OperationList("one working list");
		list.add(new OperationList("nothing"));
		list.add(inner);

		assertFalse(list.isFunctionallyEmpty());
		assertEquals(1, list.getChildren().size());
		Assert.assertSame(inner, list.getChildren().iterator().next());

		list.get().run();
		assertTrue(ran.get());
	}

	/**
	 * A list run under a profile tree is included in it: the tree has the node for the list
	 * before the list records anything.
	 */
	@Test(timeout = 60000)
	public void listIsIncludedInProfileTree() {
		AtomicBoolean ran = new AtomicBoolean();
		OperationList list = new OperationList("profiled");
		list.add(() -> () -> ran.set(true));

		OperationProfileNode profile = new OperationProfileNode("structure");
		String key = OperationProfile.metadataKey(list.getMetadata());
		assertFalse(profile.getProfileNode(key).isPresent());

		Runnable run = list.get(profile);
		assertTrue(profile.getProfileNode(key).isPresent());

		run.run();
		assertTrue(ran.get());
	}

	/**
	 * A list run under a profile that keeps a single metric, rather than a tree, runs as it
	 * would without one.
	 */
	@Test(timeout = 60000)
	public void listRunsUnderFlatProfile() {
		AtomicBoolean ran = new AtomicBoolean();
		OperationList list = new OperationList("flat");
		list.add(() -> () -> ran.set(true));

		list.get(new OperationProfile("flat")).run();
		assertTrue(ran.get());
	}
}
