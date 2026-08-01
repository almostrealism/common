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

import org.almostrealism.audio.similarity.SimilarityNode;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link SimilarityNode}, the lightweight graph node that
 * carries only identifier and similarity scores.
 */
public class SimilarityNodeTest extends TestSuiteBase {

	/**
	 * Verifies that the constructor stores the identifier and similarity map.
	 */
	@Test(timeout = 5000)
	public void constructorStoresFields() {
		Map<String, Double> similarities = new HashMap<>();
		similarities.put("peer-1", 0.9);
		similarities.put("peer-2", 0.5);

		SimilarityNode node = new SimilarityNode("my-id", similarities);

		Assert.assertEquals("my-id", node.getIdentifier());
		Assert.assertEquals(2, node.getSimilarities().size());
		Assert.assertEquals(0.9, node.getSimilarities().get("peer-1"), 1e-10);
	}

	/**
	 * Verifies that the similarity map is the same instance (not a copy),
	 * so modifications to the map are visible through the node.
	 */
	@Test(timeout = 5000)
	public void similaritiesMapIsShared() {
		Map<String, Double> similarities = new HashMap<>();
		SimilarityNode node = new SimilarityNode("id", similarities);

		similarities.put("new-peer", 0.7);
		Assert.assertTrue("Map should be shared, not copied",
				node.getSimilarities().containsKey("new-peer"));
	}

	/**
	 * Verifies that a node with null identifier is handled correctly.
	 */
	@Test(timeout = 5000)
	public void nullIdentifier() {
		SimilarityNode node = new SimilarityNode(null, new HashMap<>());
		Assert.assertNull(node.getIdentifier());
	}

	/**
	 * Verifies that an empty similarity map works correctly.
	 */
	@Test(timeout = 5000)
	public void emptySimilarities() {
		SimilarityNode node = new SimilarityNode("id", new HashMap<>());
		Assert.assertTrue(node.getSimilarities().isEmpty());
	}

	/**
	 * Verifies that two nodes with the same identifier are equal
	 * regardless of their similarity maps.
	 */
	@Test(timeout = 5000)
	public void equalsByIdentifier() {
		Map<String, Double> map1 = new HashMap<>();
		map1.put("peer", 0.5);
		Map<String, Double> map2 = new HashMap<>();
		map2.put("other", 0.9);

		SimilarityNode a = new SimilarityNode("same-id", map1);
		SimilarityNode b = new SimilarityNode("same-id", map2);

		Assert.assertEquals("Nodes with same identifier should be equal", a, b);
		Assert.assertEquals("Hash codes should match", a.hashCode(), b.hashCode());
	}

	/**
	 * Verifies that two nodes with different identifiers are not equal.
	 */
	@Test(timeout = 5000)
	public void notEqualsDifferentIdentifier() {
		Map<String, Double> map = new HashMap<>();
		SimilarityNode a = new SimilarityNode("id-1", map);
		SimilarityNode b = new SimilarityNode("id-2", map);

		Assert.assertNotEquals("Nodes with different identifiers should not be equal", a, b);
	}

	/**
	 * Verifies that two nodes with null identifiers are considered equal.
	 */
	@Test(timeout = 5000)
	public void equalsWithNullIdentifiers() {
		SimilarityNode a = new SimilarityNode(null, new HashMap<>());
		SimilarityNode b = new SimilarityNode(null, new HashMap<>());

		Assert.assertEquals("Nodes with null identifiers should be equal", a, b);
		Assert.assertEquals("Hash codes should match", a.hashCode(), b.hashCode());
	}

	/**
	 * Verifies that a node is not equal to null or a different type.
	 */
	@Test(timeout = 5000)
	public void notEqualsNullOrDifferentType() {
		SimilarityNode node = new SimilarityNode("id", new HashMap<>());

		Assert.assertNotEquals("Node should not equal null", node, null);
		Assert.assertNotEquals("Node should not equal String", node, "id");
	}
}
