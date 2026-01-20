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

import java.util.stream.Stream;

/**
 * An interface for types that contain a finite, enumerable collection of elements.
 *
 * <p>{@link Group} provides stream-based access to all elements in a collection.
 * It is a base interface for grouping abstractions like {@link NodeGroup}.</p>
 *
 * <h2>Group vs Graph - Fundamental Distinction</h2>
 * <p>{@link Group} and {@link Graph} represent fundamentally different properties
 * that should not be conflated:</p>
 * <ul>
 *   <li><b>Group</b>: A finite, enumerable collection. You can iterate over all
 *       elements via {@link #children()} or {@link #all()}. The collection is
 *       bounded and known.</li>
 *   <li><b>Graph</b>: Defines neighbor relationships between nodes. A graph may be
 *       infinite, lazily-defined, or procedurally generated. You can traverse from
 *       any node to its neighbors without needing to know all nodes that exist.</li>
 * </ul>
 * <p>These are independent properties: a structure may be a Group (enumerable), a
 * Graph (has neighbor relationships), both, or neither. For example:</p>
 * <ul>
 *   <li>A finite similarity graph implements both (see {@link WeightedGraph})</li>
 *   <li>An infinite procedural graph (e.g., integer lattice) is a Graph but not a Group</li>
 *   <li>A simple list is a Group but not necessarily a Graph</li>
 * </ul>
 *
 * <h2>Stream-Based Access</h2>
 * <p>Unlike {@link Parent#getChildren()} which returns a Collection,
 * {@link Group#children()} returns a Stream for lazy, functional-style
 * processing of group members.</p>
 *
 * @param <T> the type of elements in the group
 *
 * @see NodeGroup
 * @see Parent
 * @see Graph
 * @see WeightedGraph
 *
 * @author Michael Murray
 */
public interface Group<T> {
	/**
	 * Returns a stream of all elements in this group.
	 *
	 * @return a stream of group elements
	 */
	Stream<T> children();

	/**
	 * Returns a stream of all elements in this group.
	 *
	 * <p>The default implementation delegates to {@link #children()}.
	 * Subinterfaces may override this to include additional elements
	 * (e.g., the current node in a tree structure).</p>
	 *
	 * @return a stream of all elements
	 */
	default Stream<T> all() {
		return children();
	}
}
