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

package org.almostrealism.audio.similarity.test;

import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link PrototypeIndexData} and its nested {@link PrototypeIndexData.Community} record.
 */
public class PrototypeIndexDataTest extends TestSuiteBase {

	/**
	 * Verifies that PrototypeIndexData stores communities and timestamp correctly.
	 */
	@Test(timeout = 5000)
	public void basicConstruction() {
		List<PrototypeIndexData.Community> communities = List.of(
				new PrototypeIndexData.Community("proto-1", 0.95, List.of("m1", "m2", "m3")),
				new PrototypeIndexData.Community("proto-2", 0.72, List.of("m4", "m5"))
		);

		PrototypeIndexData index = new PrototypeIndexData(1000L, communities);
		Assert.assertEquals(1000L, index.computedAt());
		Assert.assertEquals(2, index.communities().size());
	}

	/**
	 * Verifies that totalIndexedMembers sums all community member counts.
	 */
	@Test(timeout = 5000)
	public void totalIndexedMembers() {
		List<PrototypeIndexData.Community> communities = List.of(
				new PrototypeIndexData.Community("proto-1", 0.95, List.of("m1", "m2", "m3")),
				new PrototypeIndexData.Community("proto-2", 0.72, List.of("m4", "m5"))
		);

		PrototypeIndexData index = new PrototypeIndexData(1000L, communities);
		Assert.assertEquals(5, index.totalIndexedMembers());
	}

	/**
	 * Verifies that an empty community list has zero total indexed members.
	 */
	@Test(timeout = 5000)
	public void emptyCommunitiesZeroMembers() {
		PrototypeIndexData index = new PrototypeIndexData(0L, List.of());
		Assert.assertEquals(0, index.totalIndexedMembers());
		Assert.assertTrue(index.communities().isEmpty());
	}

	/**
	 * Verifies that the communities list is immutable (compact constructor uses List.copyOf).
	 */
	@Test(timeout = 5000)
	public void communitiesListIsImmutable() {
		List<PrototypeIndexData.Community> mutable = new ArrayList<>();
		mutable.add(new PrototypeIndexData.Community("proto-1", 0.9, List.of("m1")));

		PrototypeIndexData index = new PrototypeIndexData(1000L, mutable);

		// Modifying the original list should not affect the record
		mutable.add(new PrototypeIndexData.Community("proto-2", 0.5, List.of("m2")));
		Assert.assertEquals(1, index.communities().size());

		// The returned list should be unmodifiable
		try {
			index.communities().add(new PrototypeIndexData.Community("proto-3", 0.1, List.of("m3")));
			Assert.fail("Expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// pass
		}
	}

	/**
	 * Verifies that Community member list is immutable.
	 */
	@Test(timeout = 5000)
	public void communityMemberListIsImmutable() {
		List<String> mutableMembers = new ArrayList<>();
		mutableMembers.add("m1");
		mutableMembers.add("m2");

		PrototypeIndexData.Community community =
				new PrototypeIndexData.Community("proto-1", 0.9, mutableMembers);

		// Modifying the original list should not affect the record
		mutableMembers.add("m3");
		Assert.assertEquals(2, community.memberIdentifiers().size());

		// Returned list should be unmodifiable
		try {
			community.memberIdentifiers().add("m4");
			Assert.fail("Expected UnsupportedOperationException");
		} catch (UnsupportedOperationException expected) {
			// pass
		}
	}

	/**
	 * Verifies that Community rejects null prototypeIdentifier.
	 */
	@Test(timeout = 5000, expected = NullPointerException.class)
	public void communityRejectsNullPrototype() {
		new PrototypeIndexData.Community(null, 0.9, List.of("m1"));
	}

	/**
	 * Verifies that Community stores centrality and members correctly.
	 */
	@Test(timeout = 5000)
	public void communityFieldAccess() {
		PrototypeIndexData.Community community =
				new PrototypeIndexData.Community("proto-1", 0.85, List.of("m1", "m2"));

		Assert.assertEquals("proto-1", community.prototypeIdentifier());
		Assert.assertEquals(0.85, community.centrality(), 1e-10);
		Assert.assertEquals(2, community.memberIdentifiers().size());
		Assert.assertTrue(community.memberIdentifiers().contains("m1"));
		Assert.assertTrue(community.memberIdentifiers().contains("m2"));
	}
}
