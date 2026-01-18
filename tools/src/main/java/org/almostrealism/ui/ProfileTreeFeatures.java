/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.ui;

import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.profile.OperationSource;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interface defining tree structure modes and navigation logic for operation profile visualization.
 *
 * <p>This interface provides the {@link TreeStructure} enum which determines how operation profile
 * data is organized and displayed in tree views. Different structure modes allow users to view
 * the same profile data from different perspectives, focusing on compiled operations, full
 * hierarchies, or dependency relationships.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code ProfileTreeFeatures} is designed to:</p>
 * <ul>
 *   <li><strong>Define Tree Structures:</strong> Specify how profile nodes are organized</li>
 *   <li><strong>Control Traversal:</strong> Determine when to stop expanding or skip nodes</li>
 *   <li><strong>Support Multiple Views:</strong> Enable different perspectives on the same data</li>
 *   <li><strong>Handle Dependencies:</strong> Navigate operation argument relationships</li>
 * </ul>
 *
 * <h2>Tree Structure Modes</h2>
 * <table>
 * <caption>TreeStructure modes</caption>
 *   <tr>
 *     <th>Mode</th>
 *     <th>Description</th>
 *     <th>Use Case</th>
 *   </tr>
 *   <tr>
 *     <td>{@code ALL}</td>
 *     <td>Shows complete operation hierarchy</td>
 *     <td>Understanding full operation composition</td>
 *   </tr>
 *   <tr>
 *     <td>{@code COMPILED_ONLY}</td>
 *     <td>Shows only compiled operations, skipping intermediate nodes</td>
 *     <td>High-level performance analysis</td>
 *   </tr>
 *   <tr>
 *     <td>{@code STOP_AT_COMPILED}</td>
 *     <td>Expands hierarchy until reaching compiled operations</td>
 *     <td>Understanding how operations decompose to compiled code</td>
 *   </tr>
 *   <tr>
 *     <td>{@code SCOPE_INPUTS}</td>
 *     <td>Shows operation dependencies based on program arguments</td>
 *     <td>Data flow analysis between operations</td>
 *   </tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a tree view showing only compiled operations
 * OperationProfileNode root = OperationProfileNode.load("profile.xml");
 * OperationProfileNodeInfo rootInfo = new OperationProfileNodeInfo(root, root);
 *
 * // Get children for the COMPILED_ONLY view
 * List<OperationProfileNodeInfo> children =
 *     ProfileTreeFeatures.TreeStructure.COMPILED_ONLY.children(rootInfo, true);
 * }</pre>
 *
 * @see OperationProfileNodeUI
 * @see OperationProfileNodeTreeItem
 * @see OperationProfileNodeInfo
 * @author Michael Murray
 */
public interface ProfileTreeFeatures {
	/**
	 * Comparator for sorting profile nodes by total duration in descending order.
	 *
	 * <p>This comparator is used to display the most time-consuming operations first
	 * in tree views, making it easier to identify performance bottlenecks.</p>
	 */
	Comparator<OperationProfileNode> comparator =
			Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed();

	/**
	 * Enumeration of tree structure modes for organizing operation profile data.
	 *
	 * <p>Each mode provides a different view of the operation hierarchy, allowing users
	 * to focus on different aspects of the profile data:</p>
	 * <ul>
	 *   <li>{@link #ALL} - Complete hierarchy showing all operations</li>
	 *   <li>{@link #COMPILED_ONLY} - Only compiled operations, skipping intermediates</li>
	 *   <li>{@link #STOP_AT_COMPILED} - Expand until reaching compiled operations</li>
	 *   <li>{@link #SCOPE_INPUTS} - Show dependencies based on operation arguments</li>
	 * </ul>
	 */
	enum TreeStructure {
		/** Shows the complete operation hierarchy including all intermediate nodes. */
		ALL,
		/** Shows only compiled operations, traversing through non-compiled nodes. */
		COMPILED_ONLY,
		/** Expands the hierarchy but stops when reaching compiled operations. */
		STOP_AT_COMPILED,
		/** Shows operation dependencies based on program argument keys. */
		SCOPE_INPUTS;

		/**
		 * Returns the child nodes for the given node according to this structure mode.
		 *
		 * <p>This method applies the structure-specific logic to determine which children
		 * to include in the tree view. For {@code COMPILED_ONLY}, non-compiled nodes are
		 * traversed but not shown. For {@code STOP_AT_COMPILED}, expansion stops at
		 * compiled operations.</p>
		 *
		 * @param node The parent node info
		 * @param isRoot Whether this node is the root of the tree (affects stop logic)
		 * @return List of child node info objects according to this structure mode
		 */
		List<OperationProfileNodeInfo> children(OperationProfileNodeInfo node, boolean isRoot) {
			if (stop(node, isRoot)) {
				return Collections.emptyList();
			}

			return directChildren(node)
					.flatMap(this::map).collect(Collectors.toList());
		}

		/**
		 * Returns the direct children of a node based on this structure mode.
		 *
		 * <p>For {@code SCOPE_INPUTS} mode, returns children based on the operation's
		 * program arguments (data dependencies). For other modes, returns the process
		 * children sorted by total duration.</p>
		 *
		 * @param node The parent node info
		 * @return Stream of direct child node info objects
		 */
		Stream<OperationProfileNodeInfo> directChildren(OperationProfileNodeInfo node) {
			Collection<OperationProfileNode> children = Collections.emptyList();

			if (this == SCOPE_INPUTS) {
				// Try to get the program arguments
				children = node.getProgram()
						.map(OperationSource::getArgumentKeys)
						.stream().flatMap(List::stream)
						.map(node.getRoot()::getProfileNode)
						.filter(Optional::isPresent)
						.map(Optional::get)
						.collect(Collectors.toList());
			}

			if (children.isEmpty()) {
				// If there are no program arguments (or the structure is not
				// expected to retrieve them), get the Process children
				children = node.getNode().getChildren(comparator);
			}

			// Create the OperationProfileNodeInfo instances from any available children
			return children.stream()
					.map(n -> new OperationProfileNodeInfo(node.getRoot(), n));
		}

		/**
		 * Maps a node according to this structure's traversal rules.
		 *
		 * <p>For {@code COMPILED_ONLY} mode, non-compiled nodes are traversed (their
		 * children are returned instead of the node itself). For other modes, the node
		 * is returned as-is.</p>
		 *
		 * @param node The node to potentially traverse
		 * @return Stream containing either the node itself or its children
		 */
		Stream<OperationProfileNodeInfo> map(OperationProfileNodeInfo node) {
			if (traverse(node)) {
				return children(node, false).stream();
			}

			return Stream.of(node);
		}

		/**
		 * Determines whether tree expansion should stop at this node.
		 *
		 * <p>Returns {@code true} if no children should be shown for this node. The root
		 * never stops (always shows children). For {@code STOP_AT_COMPILED} and
		 * {@code SCOPE_INPUTS} modes, expansion stops at compiled operations.</p>
		 *
		 * @param node The node to check
		 * @param isRoot Whether this is the root node
		 * @return {@code true} if expansion should stop, {@code false} to continue
		 */
		boolean stop(OperationProfileNodeInfo node, boolean isRoot) {
			if (isRoot) {
				return false;
			} else if (isStopAtCompiled()) {
				return node.isCompiled();
			}

			return node.getNode().getChildren().isEmpty();
		}

		/**
		 * Determines whether a node should be traversed (skipped) rather than displayed.
		 *
		 * <p>For {@code COMPILED_ONLY} mode, non-compiled nodes are traversed (their
		 * children are shown in place of the node). For other modes, nodes are never
		 * traversed.</p>
		 *
		 * @param node The node to check
		 * @return {@code true} if the node should be traversed (skipped), {@code false} to display it
		 */
		boolean traverse(OperationProfileNodeInfo node) {
			if (this == COMPILED_ONLY) {
				return !node.isCompiled();
			}

			return false;
		}

		/**
		 * Checks whether this structure mode stops expansion at compiled operations.
		 *
		 * @return {@code true} for {@code STOP_AT_COMPILED} and {@code SCOPE_INPUTS} modes
		 */
		boolean isStopAtCompiled() {
			return this == STOP_AT_COMPILED || this == SCOPE_INPUTS;
		}
	}
}
