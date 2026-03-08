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

import io.almostrealism.uml.Plural;

/**
 * A sequence of {@link Node}s representing a path through a graph or tree.
 *
 * <p>{@link Path} extends {@link Plural} to represent an ordered sequence of nodes.
 * Paths are useful for representing traversal routes, dependencies, or
 * sequences of operations in computation graphs.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Representing traversal paths through computation graphs</li>
 *   <li>Storing dependency chains between producers</li>
 *   <li>Recording the route from root to leaf in a tree</li>
 *   <li>Tracing execution paths for debugging</li>
 * </ul>
 *
 * @see Node
 * @see Tree
 * @see Graph
 *
 * @author Michael Murray
 */
public interface Path extends Plural<Node> {
}
