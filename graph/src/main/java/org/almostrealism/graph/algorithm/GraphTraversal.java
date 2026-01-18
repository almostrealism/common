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

package org.almostrealism.graph.algorithm;

import io.almostrealism.relation.IndexedGraph;
import io.almostrealism.relation.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Graph traversal and path-finding algorithms that work with any {@link IndexedGraph}.
 *
 * <p>This class provides static methods for traversing graphs and finding paths.
 * These algorithms are general-purpose and work with any graph implementation.</p>
 *
 * <h2>Available Algorithms</h2>
 * <ul>
 *   <li>{@link #shortestPath(IndexedGraph, int, int)} - Dijkstra's algorithm for shortest path</li>
 *   <li>{@link #findBridges(IndexedGraph, int, int, int)} - Find nodes bridging between two regions</li>
 *   <li>{@link #personalizedPageRank(IndexedGraph, Set, double, int)} - PPR from seed nodes</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * IndexedGraph<?> graph = ...;
 * List<Integer> path = GraphTraversal.shortestPath(graph, source, target);
 * Set<Integer> seeds = Set.of(1, 2, 3);
 * double[] scores = GraphTraversal.personalizedPageRank(graph, seeds, 0.85, 50);
 * }</pre>
 *
 * @see IndexedGraph
 * @see GraphCentrality
 * @see CommunityDetection
 *
 * @author Michael Murray
 */
public class GraphTraversal {

	private GraphTraversal() {}

	/**
	 * Finds the shortest path between two nodes using Dijkstra's algorithm.
	 *
	 * <p>Edge weights are treated as distances (lower is better). The algorithm
	 * finds the path that minimizes the sum of edge weights.</p>
	 *
	 * <p>For similarity graphs where higher weight means more similar, you may
	 * want to transform weights (e.g., 1/weight or 1-weight) before calling.</p>
	 *
	 * <p>Complexity: O((V + E) log V) with binary heap.</p>
	 *
	 * @param graph the graph to search
	 * @param source the starting node index
	 * @param target the destination node index
	 * @param <T> the node type
	 * @return list of node indices from source to target, or empty if no path exists
	 */
	public static <T extends Node> List<Integer> shortestPath(
			IndexedGraph<T> graph,
			int source,
			int target) {
		int n = graph.nodeCount();
		if (source < 0 || source >= n || target < 0 || target >= n) {
			return Collections.emptyList();
		}

		double[] dist = new double[n];
		int[] prev = new int[n];
		Arrays.fill(dist, Double.POSITIVE_INFINITY);
		Arrays.fill(prev, -1);
		dist[source] = 0;

		// Priority queue: (distance, node)
		PriorityQueue<long[]> pq = new PriorityQueue<>(
				Comparator.comparingDouble(a -> Double.longBitsToDouble(a[0])));
		pq.offer(new long[]{Double.doubleToLongBits(0.0), source});

		while (!pq.isEmpty()) {
			long[] entry = pq.poll();
			double d = Double.longBitsToDouble(entry[0]);
			int u = (int) entry[1];

			if (u == target) break;
			if (d > dist[u]) continue; // Stale entry

			for (int v : graph.neighborIndices(u)) {
				double weight = graph.edgeWeight(u, v);
				if (weight <= 0) weight = 1; // Treat 0 or negative as unweighted

				double alt = dist[u] + weight;
				if (alt < dist[v]) {
					dist[v] = alt;
					prev[v] = u;
					pq.offer(new long[]{Double.doubleToLongBits(alt), v});
				}
			}
		}

		// Reconstruct path
		if (prev[target] == -1 && target != source) {
			return Collections.emptyList(); // No path found
		}

		List<Integer> path = new ArrayList<>();
		for (int at = target; at != -1; at = prev[at]) {
			path.add(at);
		}
		Collections.reverse(path);
		return path;
	}

	/**
	 * Finds nodes that bridge between two specified nodes.
	 *
	 * <p>A bridge node is one that has significant similarity to both source
	 * nodes. This is useful for finding transitional samples in audio, or
	 * intermediate structures in molecules.</p>
	 *
	 * <p>The algorithm scores each node by: min(score_from_a, score_from_b),
	 * where scores are computed via personalized PageRank.</p>
	 *
	 * @param graph the graph to search
	 * @param nodeA first anchor node index
	 * @param nodeB second anchor node index
	 * @param k number of bridge nodes to return
	 * @param <T> the node type
	 * @return list of k node indices that best bridge between A and B
	 */
	public static <T extends Node> List<Integer> findBridges(
			IndexedGraph<T> graph,
			int nodeA,
			int nodeB,
			int k) {
		// Compute PPR from each anchor node
		double[] scoresFromA = personalizedPageRank(graph, Set.of(nodeA), 0.85, 30);
		double[] scoresFromB = personalizedPageRank(graph, Set.of(nodeB), 0.85, 30);

		// Score each node by minimum of both (must be reachable from both)
		int n = graph.nodeCount();
		double[] bridgeScores = new double[n];
		for (int i = 0; i < n; i++) {
			// Exclude the anchor nodes themselves
			if (i == nodeA || i == nodeB) {
				bridgeScores[i] = 0;
			} else {
				bridgeScores[i] = Math.min(scoresFromA[i], scoresFromB[i]);
			}
		}

		return GraphCentrality.topK(bridgeScores, k);
	}

	/**
	 * Computes Personalized PageRank from a set of seed nodes.
	 *
	 * <p>Unlike standard PageRank which teleports uniformly, personalized
	 * PageRank teleports back to the seed nodes. This finds nodes that are
	 * most relevant to the seed set.</p>
	 *
	 * <p>This is useful for "find samples similar to these" queries where
	 * multiple seed samples are provided.</p>
	 *
	 * @param graph the graph to search
	 * @param seeds set of seed node indices
	 * @param dampingFactor damping factor (typically 0.85)
	 * @param maxIterations maximum iterations
	 * @param <T> the node type
	 * @return array of PPR scores indexed by node index
	 */
	public static <T extends Node> double[] personalizedPageRank(
			IndexedGraph<T> graph,
			Set<Integer> seeds,
			double dampingFactor,
			int maxIterations) {
		return personalizedPageRank(graph, seeds, null, dampingFactor, maxIterations, 1e-6);
	}

	/**
	 * Computes Personalized PageRank with weighted seeds and convergence tolerance.
	 *
	 * @param graph the graph to search
	 * @param seeds set of seed node indices
	 * @param seedWeights weights for each seed (null for uniform)
	 * @param dampingFactor damping factor (typically 0.85)
	 * @param maxIterations maximum iterations
	 * @param tolerance convergence tolerance
	 * @param <T> the node type
	 * @return array of PPR scores indexed by node index
	 */
	public static <T extends Node> double[] personalizedPageRank(
			IndexedGraph<T> graph,
			Set<Integer> seeds,
			double[] seedWeights,
			double dampingFactor,
			int maxIterations,
			double tolerance) {
		int n = graph.nodeCount();
		if (n == 0 || seeds.isEmpty()) return new double[n];

		// Compute teleport distribution
		double[] teleport = new double[n];
		if (seedWeights == null) {
			double seedWeight = 1.0 / seeds.size();
			for (int seed : seeds) {
				teleport[seed] = seedWeight;
			}
		} else {
			double totalWeight = 0;
			for (int seed : seeds) {
				if (seed >= 0 && seed < seedWeights.length) {
					totalWeight += seedWeights[seed];
				}
			}
			if (totalWeight > 0) {
				for (int seed : seeds) {
					if (seed >= 0 && seed < seedWeights.length) {
						teleport[seed] = seedWeights[seed] / totalWeight;
					}
				}
			}
		}

		double[] ranks = new double[n];
		double[] newRanks = new double[n];

		// Initialize with teleport distribution
		System.arraycopy(teleport, 0, ranks, 0, n);

		for (int iter = 0; iter < maxIterations; iter++) {
			// Reset to teleport probability
			for (int i = 0; i < n; i++) {
				newRanks[i] = (1 - dampingFactor) * teleport[i];
			}

			// Distribute rank from each node
			for (int i = 0; i < n; i++) {
				T node = graph.nodeAt(i);
				double weightedDegree = graph.weightedDegree(node);

				if (weightedDegree > 0) {
					for (int j : graph.neighborIndices(i)) {
						double weight = graph.edgeWeight(i, j);
						newRanks[j] += dampingFactor * ranks[i] * weight / weightedDegree;
					}
				} else {
					// Dangling node: teleport back to seeds
					for (int seed : seeds) {
						newRanks[seed] += dampingFactor * ranks[i] * teleport[seed];
					}
				}
			}

			// Check convergence
			double maxDiff = 0;
			for (int i = 0; i < n; i++) {
				maxDiff = Math.max(maxDiff, Math.abs(newRanks[i] - ranks[i]));
			}

			double[] temp = ranks;
			ranks = newRanks;
			newRanks = temp;

			if (maxDiff < tolerance) break;
		}

		return ranks;
	}

	/**
	 * Returns the top-k nodes by score, excluding specified nodes.
	 *
	 * @param scores score array
	 * @param k number of results
	 * @param exclude nodes to exclude from results
	 * @return list of top-k node indices
	 */
	public static List<Integer> topKExcluding(double[] scores, int k, Set<Integer> exclude) {
		List<Integer> result = new ArrayList<>(k);
		boolean[] used = new boolean[scores.length];

		// Mark excluded nodes as used
		for (int ex : exclude) {
			if (ex >= 0 && ex < used.length) {
				used[ex] = true;
			}
		}

		for (int i = 0; i < k && i < scores.length; i++) {
			int maxIdx = -1;
			double maxVal = Double.NEGATIVE_INFINITY;
			for (int j = 0; j < scores.length; j++) {
				if (!used[j] && scores[j] > maxVal) {
					maxVal = scores[j];
					maxIdx = j;
				}
			}
			if (maxIdx >= 0) {
				result.add(maxIdx);
				used[maxIdx] = true;
			}
		}

		return result;
	}

	/**
	 * Performs breadth-first search from a source node.
	 *
	 * @param graph the graph to search
	 * @param source starting node index
	 * @param maxDepth maximum search depth (-1 for unlimited)
	 * @param <T> the node type
	 * @return list of node indices in BFS order
	 */
	public static <T extends Node> List<Integer> bfs(
			IndexedGraph<T> graph,
			int source,
			int maxDepth) {
		int n = graph.nodeCount();
		if (source < 0 || source >= n) return Collections.emptyList();

		List<Integer> result = new ArrayList<>();
		boolean[] visited = new boolean[n];
		List<Integer> currentLevel = new ArrayList<>();
		List<Integer> nextLevel = new ArrayList<>();

		currentLevel.add(source);
		visited[source] = true;
		int depth = 0;

		while (!currentLevel.isEmpty() && (maxDepth < 0 || depth <= maxDepth)) {
			result.addAll(currentLevel);

			for (int u : currentLevel) {
				for (int v : graph.neighborIndices(u)) {
					if (!visited[v]) {
						visited[v] = true;
						nextLevel.add(v);
					}
				}
			}

			currentLevel.clear();
			List<Integer> temp = currentLevel;
			currentLevel = nextLevel;
			nextLevel = temp;
			depth++;
		}

		return result;
	}
}
