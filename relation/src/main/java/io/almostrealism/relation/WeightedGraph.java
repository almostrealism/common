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

package io.almostrealism.relation;

import java.util.HashMap;
import java.util.Map;

/**
 * A graph structure with weighted edges between nodes.
 *
 * <p>{@link WeightedGraph} extends {@link Graph} to add support for edge weights.
 * This enables graph algorithms like PageRank, community detection, and shortest
 * path computations.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>{@link #edgeWeight(Node, Node)} - Get the weight of an edge between two nodes</li>
 *   <li>{@link #children()} - Stream all nodes in the graph (inherited from {@link Group})</li>
 *   <li>{@link #weightedNeighbors(Node)} - Get neighbors with their edge weights</li>
 * </ul>
 *
 * <h2>Edge Weight Convention</h2>
 * <p>Edge weights should be non-negative, with higher values indicating stronger
 * connections. A weight of 0 indicates no edge exists between the nodes.</p>
 *
 * <h2>Usage with Graph Algorithms</h2>
 * <p>This interface is designed to work with general graph algorithms that need
 * both structure and weight information. Implementations include:</p>
 * <ul>
 *   <li>Audio similarity graphs (WaveDetails as nodes)</li>
 *   <li>3D mesh connectivity (Vector as nodes)</li>
 *   <li>Molecular structures (Element as nodes)</li>
 * </ul>
 *
 * @param <T> the type of nodes in this graph (must extend Node)
 *
 * @see Graph
 * @see Group
 * @see IndexedGraph
 * @see Node
 *
 * @author Michael Murray
 */
public interface WeightedGraph<T extends Node> extends Graph<T> {

	/**
	 * Returns the weight of the edge between two nodes.
	 *
	 * <p>Edge weights should be non-negative. A return value of 0 indicates
	 * that no edge exists between the specified nodes.</p>
	 *
	 * @param from the source node
	 * @param to the target node
	 * @return the edge weight, or 0 if no edge exists
	 */
	double edgeWeight(T from, T to);

	/**
	 * Returns a map of neighbors to their edge weights for a given node.
	 *
	 * <p>This is a convenience method that combines {@link #neighbors(Node)}
	 * and {@link #edgeWeight(Node, Node)} into a single call.</p>
	 *
	 * @param node the node to get weighted neighbors for
	 * @return a map from neighbor nodes to edge weights
	 */
	default Map<T, Double> weightedNeighbors(T node) {
		Map<T, Double> result = new HashMap<>();
		for (T neighbor : neighbors(node)) {
			result.put(neighbor, edgeWeight(node, neighbor));
		}
		return result;
	}

	/**
	 * Returns the total weight of all edges incident to a node.
	 *
	 * <p>For undirected graphs, this is the sum of weights of all edges
	 * connected to the node. This is useful for normalization in algorithms
	 * like PageRank.</p>
	 *
	 * @param node the node to compute degree for
	 * @return the sum of edge weights for the node
	 */
	default double weightedDegree(T node) {
		double sum = 0;
		for (T neighbor : neighbors(node)) {
			sum += edgeWeight(node, neighbor);
		}
		return sum;
	}
}
