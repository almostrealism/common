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

import javax.swing.tree.DefaultMutableTreeNode;

/**
 * A Swing tree node wrapper for displaying {@link OperationProfileNode} data in a {@link javax.swing.JTree}.
 *
 * <p>This class extends {@link DefaultMutableTreeNode} to provide a hierarchical representation
 * of operation profile data suitable for display in Swing tree components. It wraps
 * {@link OperationProfileNodeInfo} objects and automatically builds the tree structure
 * based on the specified {@link ProfileTreeFeatures.TreeStructure} mode.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code OperationProfileNodeUI} is designed to:</p>
 * <ul>
 *   <li><strong>Wrap Profile Data:</strong> Encapsulate profile nodes for Swing tree display</li>
 *   <li><strong>Build Tree Structure:</strong> Automatically construct child nodes based on structure mode</li>
 *   <li><strong>Support Multiple Views:</strong> Enable different tree views (compiled, all, scope, etc.)</li>
 *   <li><strong>Integrate with Swing:</strong> Work seamlessly with {@link javax.swing.JTree} components</li>
 * </ul>
 *
 * <h2>Tree Structure Modes</h2>
 * <p>The tree structure is determined by the {@link ProfileTreeFeatures.TreeStructure} parameter:</p>
 * <ul>
 *   <li>{@code ALL} - Shows complete operation hierarchy including all intermediate nodes</li>
 *   <li>{@code COMPILED_ONLY} - Shows only compiled operations, skipping non-compiled nodes</li>
 *   <li>{@code STOP_AT_COMPILED} - Expands tree until reaching compiled operations</li>
 *   <li>{@code SCOPE_INPUTS} - Shows operation dependencies based on program arguments</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Load profile data
 * OperationProfileNode profile = OperationProfileNode.load("profile.xml");
 *
 * // Create tree node with COMPILED_ONLY structure
 * OperationProfileNodeUI rootNode = new OperationProfileNodeUI(
 *     profile, profile, ProfileTreeFeatures.TreeStructure.COMPILED_ONLY);
 *
 * // Create JTree with the root node
 * JTree tree = new JTree(rootNode);
 * }</pre>
 *
 * @see OperationProfileNodeInfo
 * @see ProfileTreeFeatures
 * @see OperationProfileUI
 * @author Michael Murray
 */
public class OperationProfileNodeUI extends DefaultMutableTreeNode implements ProfileTreeFeatures {

	/**
	 * Constructs a new tree node from profile data with the specified structure mode.
	 *
	 * <p>This constructor creates an {@link OperationProfileNodeInfo} wrapper from the provided
	 * profile nodes and initializes the tree structure based on the specified mode.</p>
	 *
	 * @param root The root node of the entire profile tree (used for metadata lookups)
	 * @param node The specific profile node this tree node represents
	 * @param structure The tree structure mode determining how children are organized
	 */
	public OperationProfileNodeUI(OperationProfileNode root, OperationProfileNode node, TreeStructure structure) {
		this(new OperationProfileNodeInfo(root, node), structure);
	}

	/**
	 * Constructs a new tree node from an existing {@link OperationProfileNodeInfo} wrapper.
	 *
	 * <p>This constructor is typically used internally when building child nodes, as the
	 * {@link OperationProfileNodeInfo} has already been created by the parent.</p>
	 *
	 * @param node The profile node info wrapper to display
	 * @param structure The tree structure mode determining how children are organized
	 */
	public OperationProfileNodeUI(OperationProfileNodeInfo node, TreeStructure structure) {
		super(node);
		init(structure);
	}

	/**
	 * Initializes the tree node by building child nodes based on the structure mode.
	 *
	 * <p>This method retrieves children from the wrapped profile node according to the
	 * specified structure mode and creates corresponding {@code OperationProfileNodeUI}
	 * instances for each child.</p>
	 *
	 * @param structure The tree structure mode determining which children to include
	 */
	protected void init(TreeStructure structure) {
		OperationProfileNodeInfo node = (OperationProfileNodeInfo) getUserObject();

		if (node != null) {
			structure.children(node, node.getRoot() == node.getNode()).forEach(child ->
						add(new OperationProfileNodeUI(child, structure)));
		}
	}
}
