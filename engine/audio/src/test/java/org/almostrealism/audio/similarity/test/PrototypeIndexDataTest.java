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
import java.util.Set;

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
	 * Verifies that the two-argument constructor records no feature model, as an
	 * index written before models were recorded (0.40) is represented.
	 */
	@Test(timeout = 5000)
	public void twoArgumentConstructorRecordsNoModel() {
		PrototypeIndexData index = new PrototypeIndexData(1000L,
				List.of(new PrototypeIndexData.Community("p", 0.5, List.of("p"))));
		Assert.assertNull("legacy construction records no feature model", index.modelIdentifier());
	}

	/**
	 * Verifies that an index built from one feature model is stale once a
	 * different model is current, even when every member is still present.
	 */
	@Test(timeout = 5000)
	public void staleWhenCurrentModelDiffersFromIndexModel() {
		PrototypeIndexData index = new PrototypeIndexData(System.currentTimeMillis(), "model-a",
				List.of(new PrototypeIndexData.Community("p", 0.5, List.of("p", "m"))));

		Assert.assertTrue("an index from model-a is stale under model-b",
				index.isStale(Set.of("p", "m"), "model-b"));
	}

	/**
	 * Verifies that an index with no recorded model (0.40) is stale as soon as a
	 * current feature model is known, as happens on the upgrade to native SA3.
	 */
	@Test(timeout = 5000)
	public void staleWhenLegacyIndexHasNoModelButCurrentModelKnown() {
		PrototypeIndexData index = new PrototypeIndexData(System.currentTimeMillis(),
				List.of(new PrototypeIndexData.Community("p", 0.5, List.of("p", "m"))));

		Assert.assertNull(index.modelIdentifier());
		Assert.assertTrue("a model-less index is stale once a current model is known",
				index.isStale(Set.of("p", "m"), "stable-audio-3"));
	}

	/**
	 * Verifies that a fresh index whose recorded model matches the current model,
	 * with unchanged members, is not stale.
	 */
	@Test(timeout = 5000)
	public void notStaleWhenModelMatchesAndContentsUnchanged() {
		PrototypeIndexData index = new PrototypeIndexData(System.currentTimeMillis(), "model-a",
				List.of(new PrototypeIndexData.Community("p", 0.5, List.of("p", "m"))));

		Assert.assertFalse("a fresh index under its own model is not stale",
				index.isStale(Set.of("p", "m"), "model-a"));
	}

	/**
	 * Verifies that with no current feature model the model check is skipped and
	 * the index is judged only on its contents, preserving the pre-model behaviour.
	 */
	@Test(timeout = 5000)
	public void notStaleWhenNoCurrentModelAndContentsUnchanged() {
		PrototypeIndexData index = new PrototypeIndexData(System.currentTimeMillis(), "model-a",
				List.of(new PrototypeIndexData.Community("p", 0.5, List.of("p", "m"))));

		Assert.assertFalse("with no current model the index is judged only on contents",
				index.isStale(Set.of("p", "m"), null));
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
