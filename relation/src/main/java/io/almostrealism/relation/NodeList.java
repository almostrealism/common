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

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A {@link List} of {@link Node}s that also implements {@link NodeGroup}.
 *
 * <p>{@link NodeList} bridges the standard Java {@link List} interface with
 * the framework's {@link NodeGroup} abstraction, allowing node collections
 * to be used with both List operations and stream-based Group operations.</p>
 *
 * <h2>Dual Interface</h2>
 * <p>Classes implementing {@link NodeList} support:</p>
 * <ul>
 *   <li>Standard {@link List} operations (get, add, remove, etc.)</li>
 *   <li>Stream-based {@link Group} access via {@link #children()}</li>
 * </ul>
 *
 * @param <T> the type of nodes in this list (must extend Node)
 *
 * @see NodeGroup
 * @see Node
 * @see List
 *
 * @author Michael Murray
 */
public interface NodeList<T extends Node> extends NodeGroup<T>, List<T> {
	/**
	 * Returns a stream of all nodes in this list.
	 *
	 * <p>This implementation delegates to {@link List#stream()}.</p>
	 *
	 * @return a stream of nodes
	 */
	default Stream<T> children() {
		return List.super.stream();
	}
}
