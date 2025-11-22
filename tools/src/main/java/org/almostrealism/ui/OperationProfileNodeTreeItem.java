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
import javafx.scene.control.TreeItem;

/**
 * A JavaFX tree item wrapper for displaying {@link OperationProfileNode} data in a {@link javafx.scene.control.TreeView}.
 *
 * <p>This class extends {@link TreeItem} to provide a hierarchical representation of operation profile
 * data suitable for display in JavaFX tree components. It wraps {@link OperationProfileNodeInfo} objects
 * and automatically builds the tree structure based on the specified {@link ProfileTreeFeatures.TreeStructure}
 * mode.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code OperationProfileNodeTreeItem} is designed to:</p>
 * <ul>
 *   <li><strong>Wrap Profile Data:</strong> Encapsulate profile nodes for JavaFX tree display</li>
 *   <li><strong>Build Tree Structure:</strong> Automatically construct child items based on structure mode</li>
 *   <li><strong>Support Multiple Views:</strong> Enable different tree views (compiled, all, scope, etc.)</li>
 *   <li><strong>Integrate with JavaFX:</strong> Work seamlessly with {@link javafx.scene.control.TreeView} components</li>
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
 * // Create tree item with COMPILED_ONLY structure
 * TreeItem<OperationProfileNodeInfo> rootItem = new OperationProfileNodeTreeItem(
 *     profile, profile, ProfileTreeFeatures.TreeStructure.COMPILED_ONLY);
 *
 * // Create TreeView with the root item
 * TreeView<OperationProfileNodeInfo> treeView = new TreeView<>(rootItem);
 * }</pre>
 *
 * @see OperationProfileNodeInfo
 * @see ProfileTreeFeatures
 * @see OperationProfileFX
 * @author Michael Murray
 */
public class OperationProfileNodeTreeItem extends
			TreeItem<OperationProfileNodeInfo> implements ProfileTreeFeatures {

	/**
	 * Constructs a new tree item from profile data with the specified structure mode.
	 *
	 * <p>This constructor creates an {@link OperationProfileNodeInfo} wrapper from the provided
	 * profile nodes and initializes the tree structure based on the specified mode.</p>
	 *
	 * @param root The root node of the entire profile tree (used for metadata lookups)
	 * @param node The specific profile node this tree item represents
	 * @param structure The tree structure mode determining how children are organized
	 */
	public OperationProfileNodeTreeItem(OperationProfileNode root, OperationProfileNode node,
										TreeStructure structure) {
		this(new OperationProfileNodeInfo(root, node), structure, true);
	}

	/**
	 * Constructs a new tree item from an existing {@link OperationProfileNodeInfo} wrapper.
	 *
	 * <p>Automatically determines whether this is a root node by comparing the node
	 * reference with the root reference in the info wrapper.</p>
	 *
	 * @param nodeInfo The profile node info wrapper to display
	 * @param structure The tree structure mode determining how children are organized
	 */
	public OperationProfileNodeTreeItem(OperationProfileNodeInfo nodeInfo, TreeStructure structure) {
		this(nodeInfo, structure, nodeInfo.getRoot() == nodeInfo.getNode());
	}

	/**
	 * Constructs a new tree item with explicit root flag specification.
	 *
	 * <p>This constructor allows explicit control over whether this item is treated as
	 * the root of the tree, which affects the tree structure logic (root nodes are
	 * never stopped at, even in STOP_AT_COMPILED mode).</p>
	 *
	 * @param nodeInfo The profile node info wrapper to display
	 * @param structure The tree structure mode determining how children are organized
	 * @param isRoot Whether this item should be treated as the root of the tree
	 */
	public OperationProfileNodeTreeItem(OperationProfileNodeInfo nodeInfo, TreeStructure structure,
										boolean isRoot) {
		super(nodeInfo);
		init(structure, isRoot);
	}

	/**
	 * Initializes the tree item by building child items based on the structure mode.
	 *
	 * <p>This method retrieves children from the wrapped profile node according to the
	 * specified structure mode and creates corresponding {@code OperationProfileNodeTreeItem}
	 * instances for each child, adding them to this item's children list.</p>
	 *
	 * @param structure The tree structure mode determining which children to include
	 * @param isRoot Whether this item is the root (affects stop logic)
	 */
	protected void init(TreeStructure structure, boolean isRoot) {
		OperationProfileNodeInfo nodeInfo = getValue();

		if (nodeInfo != null) {
			structure.children(nodeInfo, isRoot).forEach(child ->
					getChildren().add(new OperationProfileNodeTreeItem(child, structure, false)));
		}
	}
}
