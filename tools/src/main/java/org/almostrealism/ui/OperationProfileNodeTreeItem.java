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

public class OperationProfileNodeTreeItem extends
			TreeItem<OperationProfileNodeInfo> implements ProfileTreeFeatures {

	public OperationProfileNodeTreeItem(OperationProfileNode root, OperationProfileNode node,
										TreeStructure structure) {
		this(new OperationProfileNodeInfo(root, node), structure, true);
	}

	public OperationProfileNodeTreeItem(OperationProfileNodeInfo nodeInfo, TreeStructure structure) {
		this(nodeInfo, structure, nodeInfo.getRoot() == nodeInfo.getNode());
	}

	public OperationProfileNodeTreeItem(OperationProfileNodeInfo nodeInfo, TreeStructure structure,
										boolean isRoot) {
		super(nodeInfo);
		init(structure, isRoot);
	}

	protected void init(TreeStructure structure, boolean isRoot) {
		OperationProfileNodeInfo nodeInfo = getValue();

		if (nodeInfo != null) {
			structure.children(nodeInfo, isRoot).forEach(child ->
					getChildren().add(new OperationProfileNodeTreeItem(child, structure, false)));
		}
	}
}
