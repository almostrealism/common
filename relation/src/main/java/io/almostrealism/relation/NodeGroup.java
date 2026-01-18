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
 * A {@link Group} specifically for {@link Node} elements.
 *
 * <p>{@link NodeGroup} is a type-safe specialization of {@link Group} that
 * ensures all elements are {@link Node}s. This is used in tree and graph
 * structures to maintain type safety for node collections.</p>
 *
 * @param <T> the type of nodes in this group (must extend Node)
 *
 * @see Group
 * @see Node
 * @see Tree
 * @see NodeList
 *
 * @author Michael Murray
 */
public interface NodeGroup<T extends Node> extends Group<T> {
}
