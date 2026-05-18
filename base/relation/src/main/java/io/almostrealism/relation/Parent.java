/*
 * Copyright 2016 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.relation;

import java.util.Collection;
import java.util.List;

/**
 * An interface for types that have child elements.
 *
 * <p>{@link Parent} is a fundamental interface for hierarchical structures,
 * defining the ability to have children. It is used by {@link Tree} and
 * other hierarchical types to establish parent-child relationships.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>{@link #getChildren()} - Retrieve all direct children</li>
 *   <li>{@link #generate(List)} - Create a new parent with different children</li>
 * </ul>
 *
 * <h2>Usage in Tree Structures</h2>
 * <p>{@link Tree} extends {@link Parent} to provide full tree traversal
 * capabilities. The children returned by {@link #getChildren()} are
 * the direct descendants in the tree hierarchy.</p>
 *
 * @param <T> the type of children
 *
 * @see Tree
 * @see Node
 * @see Group
 *
 * @author Michael Murray
 */
public interface Parent<T> {
	/**
	 * Returns the direct children of this parent.
	 *
	 * <p>The returned collection contains only immediate children,
	 * not transitive descendants. Use tree traversal methods for
	 * accessing all descendants.</p>
	 *
	 * @return a collection of direct children
	 */
	Collection<T> getChildren();

	/**
	 * Creates a new parent with the specified children.
	 *
	 * <p>This method supports structural transformations where a parent
	 * needs to be recreated with different children while preserving
	 * its other properties.</p>
	 *
	 * @param children the children for the new parent
	 * @return a new parent with the specified children
	 * @throws UnsupportedOperationException if generation is not supported
	 */
	default Parent<T> generate(List<T> children) {
		throw new UnsupportedOperationException(getClass().getSimpleName());
	}
}
