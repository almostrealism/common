/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.graph.algorithm.test;

import io.almostrealism.relation.IndexedGraph;
import io.almostrealism.relation.Node;
import org.almostrealism.graph.algorithm.CommunityDetection;
import org.almostrealism.graph.algorithm.GraphCentrality;
import org.almostrealism.graph.algorithm.GraphTraversal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for graph algorithms: PageRank, Louvain, shortest path, and PPR.
 */
public class GraphAlgorithmTest extends TestSuiteBase {

	/**
	 * Simple test node implementation.
	 */
	static class TestNode implements Node {
		private final String id;

		TestNode(String id) {
			this.id = id;
		}

		String getId() {
			return id;
		}

		@Override
		public String toString() {
			return id;
		}
	}

	/**
	 * Simple adjacency-list based graph for testing.
	 */
	static class TestGraph implements IndexedGraph<TestNode> {
		private final List<TestNode> nodes = new ArrayList<>();
		private final Map<String, Integer> nodeIndex = new HashMap<>();
		private final Map<Integer, Map<Integer, Double>> edges = new HashMap<>();

		void addNode(TestNode node) {
			nodeIndex.put(node.getId(), nodes.size());
			nodes.add(node);
			edges.put(nodes.size() - 1, new HashMap<>());
		}

		void addEdge(int from, int to, double weight) {
			edges.get(from).put(to, weight);
			edges.get(to).put(from, weight); // Undirected
		}

		@Override
		public int nodeCount() {
			return nodes.size();
		}

		@Override
		public TestNode nodeAt(int index) {
			return nodes.get(index);
		}

		@Override
		public int indexOf(TestNode node) {
			return nodeIndex.getOrDefault(node.getId(), -1);
		}

		@Override
		public Collection<TestNode> neighbors(TestNode node) {
			int idx = indexOf(node);
			if (idx < 0) return List.of();
			List<TestNode> result = new ArrayList<>();
			for (int neighbor : edges.get(idx).keySet()) {
				result.add(nodes.get(neighbor));
			}
			return result;
		}

		@Override
		public int countNodes() {
			return nodes.size();
		}

		@Override
		public double edgeWeight(TestNode from, TestNode to) {
			int fromIdx = indexOf(from);
			int toIdx = indexOf(to);
			if (fromIdx < 0 || toIdx < 0) return 0;
			return edges.get(fromIdx).getOrDefault(toIdx, 0.0);
		}

		@Override
		public Iterable<TestNode> nodes() {
			return nodes;
		}

		@Override
		public List<Integer> neighborIndices(int nodeIndex) {
			return new ArrayList<>(edges.get(nodeIndex).keySet());
		}

		@Override
		public double edgeWeight(int fromIndex, int toIndex) {
			return edges.get(fromIndex).getOrDefault(toIndex, 0.0);
		}

		@Override
		public double weightedDegree(TestNode node) {
			int idx = indexOf(node);
			if (idx < 0) return 0;
			return edges.get(idx).values().stream().mapToDouble(Double::doubleValue).sum();
		}
	}

	/**
	 * Creates a simple triangle graph for testing.
	 * <pre>
	 *     0
	 *    / \
	 *   1---2
	 * </pre>
	 */
	private TestGraph createTriangleGraph() {
		TestGraph graph = new TestGraph();
		graph.addNode(new TestNode("A"));
		graph.addNode(new TestNode("B"));
		graph.addNode(new TestNode("C"));

		graph.addEdge(0, 1, 1.0);
		graph.addEdge(1, 2, 1.0);
		graph.addEdge(2, 0, 1.0);

		return graph;
	}

	/**
	 * Creates a two-cluster graph for community detection testing.
	 * <pre>
	 *   0---1      3---4
	 *    \ /        \ /
	 *     2-------- 5
	 * </pre>
	 */
	private TestGraph createTwoClusterGraph() {
		TestGraph graph = new TestGraph();
		for (int i = 0; i < 6; i++) {
			graph.addNode(new TestNode("N" + i));
		}

		// Cluster 1: 0, 1, 2
		graph.addEdge(0, 1, 1.0);
		graph.addEdge(1, 2, 1.0);
		graph.addEdge(2, 0, 1.0);

		// Cluster 2: 3, 4, 5
		graph.addEdge(3, 4, 1.0);
		graph.addEdge(4, 5, 1.0);
		graph.addEdge(5, 3, 1.0);

		// Bridge between clusters (weak link)
		graph.addEdge(2, 5, 0.1);

		return graph;
	}

	/**
	 * Creates a linear graph for path testing.
	 * <pre>
	 * 0 -- 1 -- 2 -- 3 -- 4
	 * </pre>
	 */
	private TestGraph createLinearGraph() {
		TestGraph graph = new TestGraph();
		for (int i = 0; i < 5; i++) {
			graph.addNode(new TestNode("N" + i));
		}
		for (int i = 0; i < 4; i++) {
			graph.addEdge(i, i + 1, 1.0);
		}
		return graph;
	}

	@Test
	public void testPageRankTriangle() {
		TestGraph graph = createTriangleGraph();

		double[] ranks = GraphCentrality.pageRank(graph, 0.85, 50);

		assertEquals(3, ranks.length);
		// In a symmetric triangle, all ranks should be equal
		assertEquals(ranks[0], ranks[1], 0.01);
		assertEquals(ranks[1], ranks[2], 0.01);
		// Sum should be approximately 1
		double sum = ranks[0] + ranks[1] + ranks[2];
		assertEquals(1.0, sum, 0.01);
	}

	@Test
	public void testDegreeCentrality() {
		TestGraph graph = createTriangleGraph();

		int[] degrees = GraphCentrality.degreeCentrality(graph);

		assertEquals(3, degrees.length);
		// Each node in triangle has 2 neighbors
		assertEquals(2, degrees[0]);
		assertEquals(2, degrees[1]);
		assertEquals(2, degrees[2]);
	}

	@Test
	public void testBetweennessCentrality() {
		TestGraph graph = createLinearGraph();

		double[] betweenness = GraphCentrality.betweennessCentrality(graph);

		assertEquals(5, betweenness.length);
		// Middle node (2) should have highest betweenness
		assertTrue(betweenness[2] > betweenness[0]);
		assertTrue(betweenness[2] > betweenness[4]);
		// End nodes should have lowest betweenness
		assertEquals(0.0, betweenness[0], 0.01);
		assertEquals(0.0, betweenness[4], 0.01);
	}

	@Test
	public void testTopK() {
		double[] values = {0.1, 0.5, 0.3, 0.8, 0.2};

		List<Integer> top2 = GraphCentrality.topK(values, 2);

		assertEquals(2, top2.size());
		assertEquals(3, (int) top2.get(0)); // 0.8
		assertEquals(1, (int) top2.get(1)); // 0.5
	}

	@Test
	public void testLouvainTwoClusters() {
		TestGraph graph = createTwoClusterGraph();

		int[] communities = CommunityDetection.louvain(graph, 1.0);

		assertEquals(6, communities.length);
		// Nodes in same cluster should have same community
		assertEquals(communities[0], communities[1]);
		assertEquals(communities[1], communities[2]);
		assertEquals(communities[3], communities[4]);
		assertEquals(communities[4], communities[5]);
		// Clusters should be different
		assertNotEquals(communities[0], communities[3]);
	}

	@Test
	public void testModularity() {
		TestGraph graph = createTwoClusterGraph();

		// Perfect clustering
		int[] perfectClusters = {0, 0, 0, 1, 1, 1};
		double goodModularity = CommunityDetection.modularity(graph, perfectClusters);

		// Bad clustering (all in one)
		int[] badClusters = {0, 0, 0, 0, 0, 0};
		double badModularity = CommunityDetection.modularity(graph, badClusters);

		// Good clustering should have higher modularity
		assertTrue(goodModularity > badModularity);
	}

	@Test
	public void testCountCommunities() {
		int[] communities = {0, 0, 1, 1, 2, 0};
		assertEquals(3, CommunityDetection.countCommunities(communities));
	}

	@Test
	public void testGetCommunityMembers() {
		int[] communities = {0, 0, 1, 1, 2, 0};
		Map<Integer, List<Integer>> members = CommunityDetection.getCommunityMembers(communities);

		assertEquals(3, members.size());
		assertEquals(List.of(0, 1, 5), members.get(0));
		assertEquals(List.of(2, 3), members.get(1));
		assertEquals(List.of(4), members.get(2));
	}

	@Test
	public void testShortestPathLinear() {
		TestGraph graph = createLinearGraph();

		List<Integer> path = GraphTraversal.shortestPath(graph, 0, 4);

		assertEquals(List.of(0, 1, 2, 3, 4), path);
	}

	@Test
	public void testShortestPathNoPath() {
		TestGraph graph = new TestGraph();
		graph.addNode(new TestNode("A"));
		graph.addNode(new TestNode("B"));
		// No edge between them

		List<Integer> path = GraphTraversal.shortestPath(graph, 0, 1);

		assertTrue(path.isEmpty());
	}

	@Test
	public void testPersonalizedPageRank() {
		TestGraph graph = createTwoClusterGraph();

		// PPR from node 0 (in cluster 1)
		double[] ppr = GraphTraversal.personalizedPageRank(graph, Set.of(0), 0.85, 50);

		assertEquals(6, ppr.length);
		// Nodes in same cluster should have higher scores
		assertTrue(ppr[1] > ppr[3]);
		assertTrue(ppr[2] > ppr[4]);
		// Seed node should have highest score
		assertTrue(ppr[0] >= ppr[1]);
	}

	@Test
	public void testFindBridges() {
		TestGraph graph = createTwoClusterGraph();

		// Find bridges between node 0 (cluster 1) and node 3 (cluster 2)
		List<Integer> bridges = GraphTraversal.findBridges(graph, 0, 3, 2);

		assertEquals(2, bridges.size());
		// Nodes 2 and 5 are the bridge nodes
		assertTrue(bridges.contains(2) || bridges.contains(5));
	}

	@Test
	public void testBFS() {
		TestGraph graph = createLinearGraph();

		List<Integer> bfs = GraphTraversal.bfs(graph, 0, 2);

		// Should contain 0, 1, 2 (depth 0, 1, 2) but not 3, 4 (depth 3, 4)
		assertTrue(bfs.contains(0));
		assertTrue(bfs.contains(1));
		assertTrue(bfs.contains(2));
		assertFalse(bfs.contains(3));
		assertFalse(bfs.contains(4));
	}

	@Test
	public void testBFSUnlimited() {
		TestGraph graph = createLinearGraph();

		List<Integer> bfs = GraphTraversal.bfs(graph, 0, -1);

		assertEquals(5, bfs.size());
		// First element should be source
		assertEquals(0, (int) bfs.get(0));
	}

	@Test
	public void testEmptyGraph() {
		TestGraph graph = new TestGraph();

		double[] ranks = GraphCentrality.pageRank(graph, 0.85, 50);
		int[] communities = CommunityDetection.louvain(graph, 1.0);
		List<Integer> path = GraphTraversal.shortestPath(graph, 0, 1);

		assertEquals(0, ranks.length);
		assertEquals(0, communities.length);
		assertTrue(path.isEmpty());
	}

	@Test
	public void testSingleNodeGraph() {
		TestGraph graph = new TestGraph();
		graph.addNode(new TestNode("A"));

		double[] ranks = GraphCentrality.pageRank(graph, 0.85, 50);
		int[] communities = CommunityDetection.louvain(graph, 1.0);

		assertEquals(1, ranks.length);
		assertEquals(1.0, ranks[0], 0.01);
		assertEquals(1, communities.length);
	}
}
