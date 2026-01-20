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
import org.almostrealism.collect.CollectionFeatures;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Stack;

/**
 * Graph centrality algorithms that work with any {@link IndexedGraph}.
 *
 * <p>This interface provides default methods for computing centrality measures
 * on graphs. These algorithms are general-purpose and work with any graph
 * implementation, including audio similarity graphs, 3D meshes, molecular
 * structures, and neural network topologies.</p>
 *
 * <h2>Available Algorithms</h2>
 * <ul>
 *   <li>{@link #pageRank(IndexedGraph, double, int)} - PageRank centrality using power iteration</li>
 *   <li>{@link #degreeCentrality(IndexedGraph)} - Simple degree count</li>
 *   <li>{@link #betweennessCentrality(IndexedGraph)} - Betweenness using Brandes algorithm</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class MyGraphAnalyzer implements GraphFeatures {
 *     public void analyze(IndexedGraph<?> graph) {
 *         double[] ranks = pageRank(graph, 0.85, 50);
 *         int mostCentral = argmax(ranks);
 *     }
 * }
 * }</pre>
 *
 * @see IndexedGraph
 * @see CommunityFeatures
 * @see TraversalFeatures
 * @see GraphFeatures
 */
public interface CentralityFeatures extends CollectionFeatures {

	/**
	 * Computes PageRank centrality for all nodes in the graph.
	 *
	 * <p>PageRank measures the importance of nodes based on the structure of
	 * incoming links. Nodes with many high-PageRank neighbors will themselves
	 * have high PageRank.</p>
	 *
	 * @param graph the graph to compute PageRank on
	 * @param dampingFactor damping factor (typically 0.85)
	 * @param maxIterations maximum number of iterations
	 * @param <T> the node type
	 * @return array of PageRank scores indexed by node index
	 */
	default <T extends Node> double[] pageRank(
			IndexedGraph<T> graph,
			double dampingFactor,
			int maxIterations) {
		return pageRank(graph, dampingFactor, maxIterations, 1e-6);
	}

	/**
	 * Computes PageRank centrality with convergence tolerance.
	 *
	 * @param graph the graph to compute PageRank on
	 * @param dampingFactor damping factor (typically 0.85)
	 * @param maxIterations maximum number of iterations
	 * @param tolerance convergence tolerance (algorithm stops when change &lt; tolerance)
	 * @param <T> the node type
	 * @return array of PageRank scores indexed by node index
	 */
	default <T extends Node> double[] pageRank(
			IndexedGraph<T> graph,
			double dampingFactor,
			int maxIterations,
			double tolerance) {
		int n = graph.countNodes();
		if (n == 0) return new double[0];

		double[] ranks = new double[n];
		double[] newRanks = new double[n];
		double initialRank = 1.0 / n;

		Arrays.fill(ranks, initialRank);

		double teleport = (1.0 - dampingFactor) / n;

		for (int iter = 0; iter < maxIterations; iter++) {
			Arrays.fill(newRanks, teleport);

			for (int i = 0; i < n; i++) {
				T node = graph.nodeAt(i);
				double weightedDegree = graph.weightedDegree(node);

				if (weightedDegree > 0) {
					List<Integer> neighbors = graph.neighborIndices(i);
					for (int j : neighbors) {
						double weight = graph.edgeWeight(i, j);
						newRanks[j] += dampingFactor * ranks[i] * weight / weightedDegree;
					}
				} else {
					double share = dampingFactor * ranks[i] / n;
					for (int j = 0; j < n; j++) {
						newRanks[j] += share;
					}
				}
			}

			double maxDiff = 0;
			for (int i = 0; i < n; i++) {
				maxDiff = Math.max(maxDiff, Math.abs(newRanks[i] - ranks[i]));
			}

			double[] temp = ranks;
			ranks = newRanks;
			newRanks = temp;

			if (maxDiff < tolerance) {
				break;
			}
		}

		return ranks;
	}

	/**
	 * Computes degree centrality for all nodes.
	 *
	 * <p>Degree centrality is simply the number of neighbors (edges) for each
	 * node. In a weighted graph, this counts edges regardless of weight.</p>
	 *
	 * @param graph the graph to compute degree centrality on
	 * @param <T> the node type
	 * @return array of degree values indexed by node index
	 */
	default <T extends Node> int[] degreeCentrality(IndexedGraph<T> graph) {
		int n = graph.countNodes();
		int[] degrees = new int[n];

		for (int i = 0; i < n; i++) {
			degrees[i] = graph.neighbors(graph.nodeAt(i)).size();
		}

		return degrees;
	}

	/**
	 * Computes weighted degree centrality for all nodes.
	 *
	 * <p>Weighted degree is the sum of edge weights for all edges connected
	 * to each node.</p>
	 *
	 * @param graph the graph to compute weighted degree on
	 * @param <T> the node type
	 * @return array of weighted degree values indexed by node index
	 */
	default <T extends Node> double[] weightedDegreeCentrality(IndexedGraph<T> graph) {
		int n = graph.countNodes();
		double[] degrees = new double[n];

		for (int i = 0; i < n; i++) {
			degrees[i] = graph.weightedDegree(graph.nodeAt(i));
		}

		return degrees;
	}

	/**
	 * Computes betweenness centrality for all nodes using Brandes algorithm.
	 *
	 * <p>Betweenness centrality measures how often a node lies on shortest
	 * paths between other nodes. Nodes with high betweenness serve as
	 * bridges between different parts of the graph.</p>
	 *
	 * <p>Complexity: O(VE) for unweighted graphs.</p>
	 *
	 * @param graph the graph to compute betweenness on
	 * @param <T> the node type
	 * @return array of betweenness centrality values indexed by node index
	 */
	default <T extends Node> double[] betweennessCentrality(IndexedGraph<T> graph) {
		int n = graph.countNodes();
		double[] betweenness = new double[n];

		for (int s = 0; s < n; s++) {
			Stack<Integer> stack = new Stack<>();
			List<List<Integer>> predecessors = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				predecessors.add(new ArrayList<>());
			}

			double[] sigma = new double[n];
			sigma[s] = 1;

			double[] dist = new double[n];
			Arrays.fill(dist, -1);
			dist[s] = 0;

			Queue<Integer> queue = new ArrayDeque<>();
			queue.offer(s);

			while (!queue.isEmpty()) {
				int v = queue.poll();
				stack.push(v);

				for (int w : graph.neighborIndices(v)) {
					if (dist[w] < 0) {
						dist[w] = dist[v] + 1;
						queue.offer(w);
					}
					if (dist[w] == dist[v] + 1) {
						sigma[w] += sigma[v];
						predecessors.get(w).add(v);
					}
				}
			}

			double[] delta = new double[n];
			while (!stack.isEmpty()) {
				int w = stack.pop();
				for (int v : predecessors.get(w)) {
					delta[v] += (sigma[v] / sigma[w]) * (1 + delta[w]);
				}
				if (w != s) {
					betweenness[w] += delta[w];
				}
			}
		}

		for (int i = 0; i < n; i++) {
			betweenness[i] /= 2.0;
		}

		return betweenness;
	}

}
