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

import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.similarity.AudioSimilarityGraph;
import org.almostrealism.audio.similarity.SimilarityNode;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link AudioSimilarityGraph} verifying the refactored
 * graph that uses lightweight {@link SimilarityNode} instances
 * instead of full {@link WaveDetails}.
 */
public class AudioSimilarityGraphTest extends TestSuiteBase {

	/**
	 * Verifies that the graph correctly extracts identifiers and
	 * similarity maps from WaveDetails into SimilarityNode instances.
	 */
	@Test(timeout = 5000)
	public void nodesAreExtractedFromWaveDetails() {
		List<WaveDetails> details = createThreeNodeGraph();
		AudioSimilarityGraph graph = new AudioSimilarityGraph(details);

		Assert.assertEquals(3, graph.countNodes());

		SimilarityNode node0 = graph.nodeAt(0);
		Assert.assertNotNull(node0);
		Assert.assertEquals("id-A", node0.getIdentifier());
		Assert.assertNotNull(node0.getSimilarities());
	}

	/**
	 * Verifies that nodeAt returns null for out-of-bounds indices
	 * and negative indices.
	 */
	@Test(timeout = 5000)
	public void nodeAtBoundsCheck() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		Assert.assertNull(graph.nodeAt(-1));
		Assert.assertNull(graph.nodeAt(3));
		Assert.assertNull(graph.nodeAt(100));
	}

	/**
	 * Verifies that indexOf returns the correct index for each node
	 * and -1 for unknown nodes.
	 */
	@Test(timeout = 5000)
	public void indexOfLookup() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		SimilarityNode node0 = graph.nodeAt(0);
		SimilarityNode node1 = graph.nodeAt(1);

		Assert.assertEquals(0, graph.indexOf(node0));
		Assert.assertEquals(1, graph.indexOf(node1));

		// Unknown node
		SimilarityNode unknown = new SimilarityNode("unknown", new HashMap<>());
		Assert.assertEquals(-1, graph.indexOf(unknown));

		// Null node
		Assert.assertEquals(-1, graph.indexOf(null));
	}

	/**
	 * Verifies that edgeWeight returns the correct similarity score
	 * between two nodes and 0.0 for unconnected or null nodes.
	 */
	@Test(timeout = 5000)
	public void edgeWeightValues() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		SimilarityNode nodeA = graph.nodeAt(0);
		SimilarityNode nodeB = graph.nodeAt(1);
		SimilarityNode nodeC = graph.nodeAt(2);

		Assert.assertEquals(0.8, graph.edgeWeight(nodeA, nodeB), 1e-10);
		Assert.assertEquals(0.3, graph.edgeWeight(nodeA, nodeC), 1e-10);
		Assert.assertEquals(0.5, graph.edgeWeight(nodeB, nodeC), 1e-10);

		// Reverse direction should also work (bidirectional)
		Assert.assertEquals(0.8, graph.edgeWeight(nodeB, nodeA), 1e-10);

		// Null inputs
		Assert.assertEquals(0.0, graph.edgeWeight(null, nodeA), 1e-10);
		Assert.assertEquals(0.0, graph.edgeWeight(nodeA, null), 1e-10);
	}

	/**
	 * Verifies that edgeWeight by index returns correct values.
	 */
	@Test(timeout = 5000)
	public void edgeWeightByIndex() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		Assert.assertEquals(0.8, graph.edgeWeight(0, 1), 1e-10);
		Assert.assertEquals(0.3, graph.edgeWeight(0, 2), 1e-10);
		Assert.assertEquals(0.0, graph.edgeWeight(-1, 0), 1e-10);
		Assert.assertEquals(0.0, graph.edgeWeight(0, 100), 1e-10);
	}

	/**
	 * Verifies that neighbors returns the correct set of connected
	 * nodes above the default threshold (0.0).
	 */
	@Test(timeout = 5000)
	public void neighborsWithNoThreshold() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		SimilarityNode nodeA = graph.nodeAt(0);

		Collection<SimilarityNode> neighbors = graph.neighbors(nodeA);
		Assert.assertEquals(2, neighbors.size());

		// Null input
		Assert.assertEquals(0, graph.neighbors(null).size());
	}

	/**
	 * Verifies that withThreshold filters edges below the threshold.
	 */
	@Test(timeout = 5000)
	public void thresholdFiltering() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		AudioSimilarityGraph filtered = graph.withThreshold(0.6);

		// Node A has similarity 0.8 to B and 0.3 to C
		// With threshold 0.6, only B should be a neighbor
		SimilarityNode nodeA = filtered.nodeAt(0);
		Collection<SimilarityNode> neighbors = filtered.neighbors(nodeA);
		Assert.assertEquals(1, neighbors.size());
		Assert.assertEquals("id-B", neighbors.iterator().next().getIdentifier());

		// Edge weight below threshold returns 0.0
		Assert.assertEquals(0.0, filtered.edgeWeight(nodeA, filtered.nodeAt(2)), 1e-10);
		// Edge weight above threshold returns actual value
		Assert.assertEquals(0.8, filtered.edgeWeight(nodeA, filtered.nodeAt(1)), 1e-10);
	}

	/**
	 * Verifies that the graph correctly handles an empty collection of details.
	 */
	@Test(timeout = 5000)
	public void emptyGraph() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(new ArrayList<>());
		Assert.assertEquals(0, graph.countNodes());
		Assert.assertNull(graph.nodeAt(0));
	}

	/**
	 * Verifies that children() returns a stream of all nodes.
	 */
	@Test(timeout = 5000)
	public void childrenStream() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		List<SimilarityNode> children = graph.children().toList();
		Assert.assertEquals(3, children.size());
	}

	/**
	 * Verifies that neighborIndices returns indices of adjacent nodes.
	 */
	@Test(timeout = 5000)
	public void neighborIndicesCheck() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		List<Integer> indices = graph.neighborIndices(0);
		// Node A has similarity to B and C (both > 0.0)
		Assert.assertEquals(2, indices.size());
	}

	/**
	 * Verifies that WaveDetails with null identifiers are excluded from the graph.
	 */
	@Test(timeout = 5000)
	public void nullIdentifierExcluded() {
		List<WaveDetails> details = new ArrayList<>();
		details.add(createDetails("id-A", Map.of()));
		details.add(createDetails(null, Map.of()));
		details.add(createDetails("id-C", Map.of()));

		AudioSimilarityGraph graph = new AudioSimilarityGraph(details);
		Assert.assertEquals(2, graph.countNodes());
	}

	/**
	 * Verifies that weightedDegree sums the similarity scores of all neighbors.
	 */
	@Test(timeout = 5000)
	public void weightedDegreeCalculation() {
		AudioSimilarityGraph graph = new AudioSimilarityGraph(createThreeNodeGraph());
		SimilarityNode nodeA = graph.nodeAt(0);
		// Node A: similarity 0.8 to B, 0.3 to C => weighted degree = 1.1
		Assert.assertEquals(1.1, graph.weightedDegree(nodeA), 1e-10);
	}

	// ── Test helpers ─────────────────────────────────────────────────────

	private List<WaveDetails> createThreeNodeGraph() {
		List<WaveDetails> details = new ArrayList<>();

		// A <-> B: 0.8,  A <-> C: 0.3,  B <-> C: 0.5
		Map<String, Double> simA = new HashMap<>();
		simA.put("id-B", 0.8);
		simA.put("id-C", 0.3);

		Map<String, Double> simB = new HashMap<>();
		simB.put("id-A", 0.8);
		simB.put("id-C", 0.5);

		Map<String, Double> simC = new HashMap<>();
		simC.put("id-A", 0.3);
		simC.put("id-B", 0.5);

		details.add(createDetails("id-A", simA));
		details.add(createDetails("id-B", simB));
		details.add(createDetails("id-C", simC));

		return details;
	}

	private WaveDetails createDetails(String identifier, Map<String, Double> similarities) {
		WaveDetails details = new WaveDetails(identifier);
		details.setSimilarities(similarities);
		return details;
	}
}
