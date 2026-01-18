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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Community detection algorithms that work with any {@link IndexedGraph}.
 *
 * <p>This class provides static methods for detecting communities (clusters)
 * in graphs. These algorithms are general-purpose and work with any graph
 * implementation.</p>
 *
 * <h2>Available Algorithms</h2>
 * <ul>
 *   <li>{@link #louvain(IndexedGraph, double)} - Louvain community detection</li>
 *   <li>{@link #modularity(IndexedGraph, int[])} - Modularity score computation</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * IndexedGraph<?> graph = ...;
 * int[] clusters = CommunityDetection.louvain(graph, 1.0);
 * double quality = CommunityDetection.modularity(graph, clusters);
 * }</pre>
 *
 * @see IndexedGraph
 * @see GraphCentrality
 * @see GraphTraversal
 *
 * @author Michael Murray
 */
public class CommunityDetection {

	private CommunityDetection() {}

	/**
	 * Detects communities using the Louvain algorithm.
	 *
	 * <p>The Louvain algorithm is a greedy optimization method that maximizes
	 * modularity. It works in two phases:</p>
	 * <ol>
	 *   <li>Move nodes to neighboring communities to maximize modularity gain</li>
	 *   <li>Aggregate nodes in the same community into super-nodes</li>
	 * </ol>
	 * <p>These phases are repeated until no further improvement is possible.</p>
	 *
	 * <p>Complexity: O(E log N) for sparse graphs.</p>
	 *
	 * @param graph the graph to detect communities in
	 * @param resolution resolution parameter (higher = more communities)
	 * @param <T> the node type
	 * @return array of cluster assignments (node index -> cluster ID)
	 */
	public static <T extends Node> int[] louvain(IndexedGraph<T> graph, double resolution) {
		int n = graph.nodeCount();
		if (n == 0) return new int[0];

		// Compute total edge weight (2m in modularity formula)
		double totalWeight = 0;
		for (int i = 0; i < n; i++) {
			totalWeight += graph.weightedDegree(graph.nodeAt(i));
		}
		if (totalWeight == 0) {
			// No edges - each node is its own community
			int[] result = new int[n];
			for (int i = 0; i < n; i++) result[i] = i;
			return result;
		}

		// Initialize: each node in its own community
		int[] community = new int[n];
		for (int i = 0; i < n; i++) {
			community[i] = i;
		}

		// Track community statistics
		double[] communityWeightSum = new double[n]; // Sum of weighted degrees in community
		double[] communityInternalWeight = new double[n]; // Sum of internal edge weights

		// Initialize community stats
		for (int i = 0; i < n; i++) {
			communityWeightSum[i] = graph.weightedDegree(graph.nodeAt(i));
		}

		boolean improved = true;
		int maxIterations = 100;
		int iteration = 0;

		while (improved && iteration < maxIterations) {
			improved = false;
			iteration++;

			// Phase 1: Move nodes to maximize modularity
			for (int i = 0; i < n; i++) {
				int currentCommunity = community[i];
				double nodeWeight = graph.weightedDegree(graph.nodeAt(i));

				// Calculate weight to each neighboring community
				Map<Integer, Double> communityWeights = new HashMap<>();
				for (int j : graph.neighborIndices(i)) {
					int neighborCommunity = community[j];
					double edgeWeight = graph.edgeWeight(i, j);
					communityWeights.merge(neighborCommunity, edgeWeight, Double::sum);
				}

				// Find best community to move to
				int bestCommunity = currentCommunity;
				double bestGain = 0;

				// Weight to current community
				double weightToCurrent = communityWeights.getOrDefault(currentCommunity, 0.0);

				// Remove node from current community for calculation
				double currentCommunityWeight = communityWeightSum[currentCommunity] - nodeWeight;

				for (Map.Entry<Integer, Double> entry : communityWeights.entrySet()) {
					int targetCommunity = entry.getKey();
					double weightToTarget = entry.getValue();

					if (targetCommunity == currentCommunity) continue;

					double targetCommunityWeight = communityWeightSum[targetCommunity];

					// Modularity gain from moving to target community
					// DeltaQ = [weight_to_target - resolution * nodeWeight * targetWeight / totalWeight]
					//        - [weight_to_current - resolution * nodeWeight * currentWeight / totalWeight]
					double gain = (weightToTarget - weightToCurrent)
							- resolution * nodeWeight * (targetCommunityWeight - currentCommunityWeight) / totalWeight;

					if (gain > bestGain) {
						bestGain = gain;
						bestCommunity = targetCommunity;
					}
				}

				// Move node if it improves modularity
				if (bestCommunity != currentCommunity) {
					community[i] = bestCommunity;
					communityWeightSum[currentCommunity] -= nodeWeight;
					communityWeightSum[bestCommunity] += nodeWeight;
					improved = true;
				}
			}
		}

		// Renumber communities to be contiguous
		return renumberCommunities(community);
	}

	/**
	 * Computes the modularity of a graph partitioning.
	 *
	 * <p>Modularity measures the quality of a community structure. Higher values
	 * indicate better community separation. Values typically range from -0.5 to 1,
	 * with values above 0.3 indicating significant community structure.</p>
	 *
	 * <p>The formula is:
	 * <pre>
	 * Q = (1/2m) * SUM [A_ij - k_i * k_j / 2m] * delta(c_i, c_j)
	 * </pre>
	 * where A_ij is the edge weight, k_i is the weighted degree, m is total
	 * edge weight, and delta is 1 if nodes are in the same community.</p>
	 *
	 * @param graph the graph
	 * @param communities cluster assignments (node index -> cluster ID)
	 * @param <T> the node type
	 * @return the modularity score
	 */
	public static <T extends Node> double modularity(IndexedGraph<T> graph, int[] communities) {
		int n = graph.nodeCount();
		if (n == 0) return 0;

		// Compute total edge weight
		double totalWeight = 0;
		for (int i = 0; i < n; i++) {
			totalWeight += graph.weightedDegree(graph.nodeAt(i));
		}
		if (totalWeight == 0) return 0;

		double modularity = 0;

		for (int i = 0; i < n; i++) {
			double ki = graph.weightedDegree(graph.nodeAt(i));
			for (int j : graph.neighborIndices(i)) {
				if (communities[i] == communities[j]) {
					double kj = graph.weightedDegree(graph.nodeAt(j));
					double aij = graph.edgeWeight(i, j);
					modularity += aij - (ki * kj) / totalWeight;
				}
			}
		}

		return modularity / totalWeight;
	}

	/**
	 * Returns the number of unique communities in the assignment.
	 *
	 * @param communities cluster assignments
	 * @return number of unique communities
	 */
	public static int countCommunities(int[] communities) {
		Set<Integer> unique = new HashSet<>();
		for (int c : communities) {
			unique.add(c);
		}
		return unique.size();
	}

	/**
	 * Returns the members of each community.
	 *
	 * @param communities cluster assignments (node index -> cluster ID)
	 * @return map from cluster ID to list of node indices
	 */
	public static Map<Integer, List<Integer>> getCommunityMembers(int[] communities) {
		Map<Integer, List<Integer>> members = new HashMap<>();
		for (int i = 0; i < communities.length; i++) {
			members.computeIfAbsent(communities[i], k -> new ArrayList<>()).add(i);
		}
		return members;
	}

	/**
	 * Renumbers communities to use contiguous IDs starting from 0.
	 */
	private static int[] renumberCommunities(int[] communities) {
		Map<Integer, Integer> mapping = new HashMap<>();
		int nextId = 0;

		int[] result = new int[communities.length];
		for (int i = 0; i < communities.length; i++) {
			int oldId = communities[i];
			Integer newId = mapping.get(oldId);
			if (newId == null) {
				newId = nextId++;
				mapping.put(oldId, newId);
			}
			result[i] = newId;
		}

		return result;
	}
}
