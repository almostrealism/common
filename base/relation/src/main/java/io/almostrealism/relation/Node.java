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

/**
 * A marker interface for types that can participate in computation graphs.
 *
 * <p>{@link Node} is the base interface for all types that form part of a
 * computation graph structure. It serves as a common type for graph traversal
 * and analysis operations.</p>
 *
 * <h2>Purpose</h2>
 * <p>The {@link Node} interface enables:</p>
 * <ul>
 *   <li>Type-safe graph operations across different node types</li>
 *   <li>Common handling of heterogeneous graph elements</li>
 *   <li>Extension point for graph-related functionality</li>
 * </ul>
 *
 * <h2>Related Types</h2>
 * <ul>
 *   <li>{@link Producer} extends {@link Node} - computation descriptions</li>
 *   <li>{@link Tree} extends {@link Node} - hierarchical tree structures</li>
 *   <li>{@link Graph} - collection of {@link Node}s with neighbor relationships</li>
 *   <li>{@link NodeGroup} - grouping of {@link Node}s</li>
 *   <li>{@link Parent} - nodes with children</li>
 *   <li>{@link Path} - sequence of {@link Node}s</li>
 * </ul>
 *
 * @see Tree
 * @see Graph
 * @see NodeGroup
 * @see Producer
 *
 * @author Michael Murray
 */
public interface Node {
}
