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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A weighted graph with integer-indexed node access for algorithm efficiency.
 *
 * <p>{@link IndexedGraph} extends {@link WeightedGraph} to provide O(1) access
 * to nodes by integer index. This is essential for efficient implementation of
 * graph algorithms like PageRank, Louvain community detection, and shortest
 * path computations, which typically work with integer arrays.</p>
 *
 * <h2>Index Convention</h2>
 * <p>Nodes are indexed from 0 to {@link #countNodes()} - 1. The mapping between
 * nodes and indices must be stable for the lifetime of the graph.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>{@link #countNodes()} - Total number of nodes (inherited from Graph)</li>
 *   <li>{@link #nodeAt(int)} - Get node by index</li>
 *   <li>{@link #indexOf(Node)} - Get index of a node</li>
 *   <li>{@link #neighborIndices(int)} - Get neighbor indices for algorithm efficiency</li>
 * </ul>
 *
 * <h2>Algorithm Integration</h2>
 * <p>Graph algorithms in the ar-graph module work with this interface. The
 * integer indexing allows algorithms to use primitive arrays for results:</p>
 * <pre>{@code
 * public class MyAnalyzer implements GraphFeatures {
 *     public void analyze(IndexedGraph<?> graph) {
 *         double[] pageRank = pageRank(graph, 0.85, 50);
 *         int[] clusters = louvain(graph, 1.0);
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of nodes in this graph (must extend Node)
 *
 * @see WeightedGraph
 * @see Graph
 * @see Node
 *
 * @author Michael Murray
 */
public interface IndexedGraph<T extends Node> extends WeightedGraph<T> {

	/**
	 * Returns the node at the specified index.
	 *
	 * @param index the node index (0 to countNodes() - 1)
	 * @return the node at that index
	 * @throws IndexOutOfBoundsException if index is out of range
	 */
	T nodeAt(int index);

	/**
	 * Returns the index of the specified node.
	 *
	 * @param node the node to find
	 * @return the index of the node
	 * @throws IllegalArgumentException if the node is not in the graph
	 */
	int indexOf(T node);

	/**
	 * Returns the indices of all neighbors of the node at the specified index.
	 *
	 * <p>This method is optimized for graph algorithms that work with integer
	 * indices rather than node objects.</p>
	 *
	 * @param nodeIndex the index of the node
	 * @return list of neighbor indices
	 */
	default List<Integer> neighborIndices(int nodeIndex) {
		T node = nodeAt(nodeIndex);
		Collection<T> neighbors = neighbors(node);
		List<Integer> indices = new ArrayList<>(neighbors.size());
		for (T neighbor : neighbors) {
			indices.add(indexOf(neighbor));
		}
		return indices;
	}

	/**
	 * Returns the edge weight between two nodes specified by index.
	 *
	 * <p>This is a convenience method for algorithms working with indices.</p>
	 *
	 * @param fromIndex the source node index
	 * @param toIndex the target node index
	 * @return the edge weight, or 0 if no edge exists
	 */
	default double edgeWeight(int fromIndex, int toIndex) {
		return edgeWeight(nodeAt(fromIndex), nodeAt(toIndex));
	}

}
