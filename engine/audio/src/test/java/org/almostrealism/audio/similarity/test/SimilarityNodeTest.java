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
}
