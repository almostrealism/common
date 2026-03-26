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

package org.almostrealism.studio.discovery.test;

import org.almostrealism.studio.discovery.PrototypeDiscovery;
import org.almostrealism.audio.similarity.PrototypeIndexData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link PrototypeDiscovery#buildIndex(List)} which converts
 * prototype discovery results into a persistable index.
 */
public class PrototypeDiscoveryBuildIndexTest extends TestSuiteBase {

	/**
	 * Verifies that buildIndex creates a PrototypeIndexData with the
	 * correct number of communities and member mappings.
	 */
	@Test(timeout = 5000)
	public void buildIndexCreatesCorrectStructure() {
		List<PrototypeDiscovery.PrototypeResult> prototypes = List.of(
				new PrototypeDiscovery.PrototypeResult(
						"proto-1", 0.95, 3, List.of("m1", "m2", "m3")),
				new PrototypeDiscovery.PrototypeResult(
						"proto-2", 0.72, 2, List.of("m4", "m5"))
		);

		PrototypeIndexData index = PrototypeDiscovery.buildIndex(prototypes);

		Assert.assertEquals(2, index.communities().size());
		Assert.assertTrue("computedAt should be recent",
				index.computedAt() > 0);
	}

	/**
	 * Verifies that community prototypes and members are mapped correctly.
	 */
	@Test(timeout = 5000)
	public void buildIndexMapsPrototypeAndMembers() {
		List<PrototypeDiscovery.PrototypeResult> prototypes = List.of(
				new PrototypeDiscovery.PrototypeResult(
						"proto-A", 0.9, 4, List.of("a1", "a2", "a3", "a4"))
		);

		PrototypeIndexData index = PrototypeDiscovery.buildIndex(prototypes);

		PrototypeIndexData.Community community = index.communities().get(0);
		Assert.assertEquals("proto-A", community.prototypeIdentifier());
		Assert.assertEquals(0.9, community.centrality(), 1e-10);
		Assert.assertEquals(4, community.memberIdentifiers().size());
		Assert.assertTrue(community.memberIdentifiers().contains("a1"));
		Assert.assertTrue(community.memberIdentifiers().contains("a4"));
	}

	/**
	 * Verifies that buildIndex handles an empty prototype list.
	 */
	@Test(timeout = 5000)
	public void buildIndexWithEmptyList() {
		PrototypeIndexData index = PrototypeDiscovery.buildIndex(List.of());
		Assert.assertTrue(index.communities().isEmpty());
		Assert.assertEquals(0, index.totalIndexedMembers());
	}

	/**
	 * Verifies that the totalIndexedMembers of the produced index
	 * matches the sum of all community sizes.
	 */
	@Test(timeout = 5000)
	public void buildIndexTotalMembers() {
		List<PrototypeDiscovery.PrototypeResult> prototypes = List.of(
				new PrototypeDiscovery.PrototypeResult(
						"p1", 0.8, 5, List.of("a", "b", "c", "d", "e")),
				new PrototypeDiscovery.PrototypeResult(
						"p2", 0.6, 3, List.of("f", "g", "h"))
		);

		PrototypeIndexData index = PrototypeDiscovery.buildIndex(prototypes);
		Assert.assertEquals(8, index.totalIndexedMembers());
	}

	/**
	 * Verifies that PrototypeResult record fields are accessible.
	 */
	@Test(timeout = 5000)
	public void prototypeResultFieldAccess() {
		PrototypeDiscovery.PrototypeResult result =
				new PrototypeDiscovery.PrototypeResult(
						"id-1", 0.85, 10, List.of("m1", "m2"));

		Assert.assertEquals("id-1", result.identifier());
		Assert.assertEquals(0.85, result.centrality(), 1e-10);
		Assert.assertEquals(10, result.communitySize());
		Assert.assertEquals(2, result.memberIdentifiers().size());
	}
}
