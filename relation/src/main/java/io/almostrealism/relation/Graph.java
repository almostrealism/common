/*
 * Copyright 2023 Michael Murray
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

import java.util.Collection;

/**
 * A graph structure consisting of {@link Node}s with neighbor relationships.
 *
 * <p>{@link Graph} represents an abstract graph data structure where nodes
 * can have connections (neighbors) to other nodes. This is a base interface
 * for more specific graph types like {@link Tree}.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>{@link #neighbors(Node)} - Get the nodes connected to a given node</li>
 *   <li>{@link #countNodes()} - Count the total nodes in the graph</li>
 * </ul>
 *
 * <h2>Relationship to Tree</h2>
 * <p>{@link Tree} extends {@link Graph} but restricts the structure to
 * hierarchical parent-child relationships. In a tree, neighbors are
 * typically the children of a node.</p>
 *
 * @param <T> the type of nodes in this graph (must extend Node)
 *
 * @see Node
 * @see Tree
 *
 * @author Michael Murray
 */
public interface Graph<T extends Node> {
	/**
	 * Returns the neighbors of the specified node.
	 *
	 * <p>In a general graph, neighbors are nodes that share an edge with
	 * the given node. In a tree, this typically means child nodes.</p>
	 *
	 * @param node the node to get neighbors for
	 * @return a collection of neighboring nodes
	 */
	Collection<T> neighbors(T node);

	/**
	 * Counts the total number of nodes in this graph.
	 *
	 * @return the total node count
	 */
	int countNodes();
}
