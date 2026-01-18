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

import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

/**
 * A hierarchical tree structure for organizing {@link Node}s.
 *
 * <p>{@link Tree} provides a unified interface for traversing and analyzing
 * computation graphs organized as tree structures. It combines the capabilities
 * of {@link Graph}, {@link NodeGroup}, and {@link Parent} into a cohesive
 * tree abstraction.</p>
 *
 * <h2>Core Operations</h2>
 * <ul>
 *   <li>{@link #children()} - Stream all descendants</li>
 *   <li>{@link #all()} - Stream this node and all descendants</li>
 *   <li>{@link #countNodes()} - Count total nodes in the tree</li>
 *   <li>{@link #treeDepth()} - Calculate the depth of the tree</li>
 * </ul>
 *
 * <h2>Traversal</h2>
 * <p>The tree can be traversed using streams. The {@link #children(boolean)}
 * method controls whether the current node is included in the traversal:</p>
 * <pre>{@code
 * // Include this node in traversal
 * tree.children(true).forEach(node -> process(node));
 *
 * // Exclude this node, only traverse descendants
 * tree.children(false).forEach(node -> process(node));
 * }</pre>
 *
 * <h2>Depth Calculation</h2>
 * <p>Tree depth can be calculated with optional filtering:</p>
 * <pre>{@code
 * // Total depth
 * int depth = tree.treeDepth();
 *
 * // Depth considering only certain node types
 * int filteredDepth = tree.treeDepth(node -> node instanceof MyType);
 * }</pre>
 *
 * @param <T> the type of tree nodes (must extend Tree itself)
 *
 * @see Node
 * @see Graph
 * @see Parent
 * @see TreeRef
 *
 * @author Michael Murray
 */
public interface Tree<T extends Tree> extends Graph<T>, NodeGroup<T>, Parent<T>, Node {

	/**
	 * Returns a stream of this node and all descendant nodes.
	 *
	 * <p>This is equivalent to calling {@code children(true)}.</p>
	 *
	 * @return a stream containing this node and all descendants
	 */
	default Stream<T> all() {
		return children(true);
	}

	/**
	 * Returns a stream of this node and all descendant nodes.
	 *
	 * <p>Note: Future versions may change this to exclude the current node
	 * by default. Use {@link #all()} explicitly if you need to include
	 * the current node.</p>
	 *
	 * @return a stream containing this node and all descendants
	 */
	default Stream<T> children() {
		return children(true);
	}

	/**
	 * Returns a stream of nodes in this tree with optional inclusion of the root.
	 *
	 * <p>The traversal is depth-first, visiting each node's children recursively.</p>
	 *
	 * @param includeThis {@code true} to include this node in the stream
	 * @return a stream of tree nodes
	 */
	default Stream<T> children(boolean includeThis) {
		Stream<T> c = getChildren().stream().flatMap(Tree::children);
		return includeThis ? Stream.concat(Stream.of((T) this), c) : c;
	}

	/**
	 * Creates a {@link TreeRef} wrapper around this tree.
	 *
	 * <p>{@link TreeRef} provides a lightweight reference to a tree that can
	 * be used for safe sharing and lazy evaluation.</p>
	 *
	 * @return a reference wrapper for this tree
	 */
	default TreeRef<T> ref() {
		return new TreeRef<>(this);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Trees do not support arbitrary neighbor relationships.
	 * Use {@link #getChildren()} for tree traversal instead.</p>
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	default Collection<T> neighbors(T node) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Counts the total number of nodes in this tree, including this node.
	 *
	 * @return the total node count
	 */
	@Override
	default int countNodes() {
		return 1 + getChildren().stream().mapToInt(Tree::countNodes).sum();
	}

	/**
	 * Calculates the depth of this tree.
	 *
	 * <p>The depth is the length of the longest path from this node to a leaf.</p>
	 *
	 * @return the tree depth
	 */
	default int treeDepth() {
		return countDepth(t -> 1, t -> true);
	}

	/**
	 * Calculates the depth of this tree considering only nodes that match the filter.
	 *
	 * @param filter predicate to determine which nodes to include in depth calculation
	 * @return the filtered tree depth
	 */
	default int treeDepth(Predicate<T> filter) {
		return countDepth(t -> 1, filter);
	}

	/**
	 * Calculates a weighted depth metric for this tree.
	 *
	 * <p>This method allows custom counting functions and filters for
	 * flexible depth calculations.</p>
	 *
	 * @param count function to compute the count contribution of each node
	 * @param filter predicate to determine which nodes to include
	 * @return the computed depth metric
	 */
	default int countDepth(ToIntFunction<Parent<T>> count, Predicate<T> filter) {
		return count.applyAsInt(this) + getChildren().stream().filter(filter)
				.mapToInt(t -> t.countDepth(count, filter))
				.max().orElse(0);
	}
}
