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
 * An interface for types that contain a collection of elements as a group.
 *
 * <p>{@link Group} provides stream-based access to a collection of elements.
 * It is a base interface for grouping abstractions like {@link NodeGroup}.</p>
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
