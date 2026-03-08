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
import java.util.stream.Collectors;

/**
 * A reference wrapper around a {@link Tree} that provides indirect access.
 *
 * <p>{@link TreeRef} wraps an existing tree and provides the same tree interface
 * while indirecting through the reference. When children are accessed, new
 * {@link TreeRef} instances are created for each child.</p>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Safe sharing of tree structures across contexts</li>
 *   <li>Breaking circular references in tree transformations</li>
 *   <li>Providing a stable reference while underlying tree changes</li>
 *   <li>Lazy tree traversal patterns</li>
 * </ul>
 *
 * <h2>Child Wrapping</h2>
 * <p>When {@link #getChildren()} is called, each child of the underlying tree
 * is wrapped in a new {@link TreeRef}. This creates a parallel reference tree
 * structure.</p>
 *
 * @param <T> the type of tree nodes
 *
 * @see Tree
 * @see Tree#ref()
 *
 * @author Michael Murray
 */
public class TreeRef<T extends Tree> implements Tree<T> {
	private Tree<?> tree;

	/**
	 * Creates a new reference to the given tree.
	 *
	 * @param tree the tree to reference
	 */
	public TreeRef(Tree tree) {
		this.tree = tree;
	}

	/**
	 * Returns the children of the referenced tree, each wrapped in a {@link TreeRef}.
	 *
	 * @return a collection of wrapped child trees
	 */
	@Override
	public Collection<T> getChildren() {
		return (Collection<T>) tree.getChildren().stream().map(TreeRef::new).collect(Collectors.toList());
	}
}
