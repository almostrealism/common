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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class OperationProfileNodeUI extends DefaultMutableTreeNode implements ProfileTreeFeatures {

	public OperationProfileNodeUI(OperationProfileNode root, OperationProfileNode node, TreeStructure structure) {
		this(new OperationProfileNodeInfo(root, node), structure);
	}

	public OperationProfileNodeUI(OperationProfileNodeInfo node, TreeStructure structure) {
		super(node);
		init(structure);
	}

	protected void init(TreeStructure structure) {
		OperationProfileNodeInfo node = (OperationProfileNodeInfo) getUserObject();

		if (node != null) {
			structure.children(node, node.getRoot() == node.getNode()).forEach(child ->
						add(new OperationProfileNodeUI(child, structure)));
		}
	}
}
