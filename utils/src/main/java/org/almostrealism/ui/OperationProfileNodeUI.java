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

public class OperationProfileNodeUI extends DefaultMutableTreeNode {
	public static final Comparator<OperationProfileNode> comparator =
			Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed();

	public OperationProfileNodeUI(OperationProfileNode root, OperationProfileNode node, boolean onlyCompiled) {
		this(new OperationProfileNodeInfo(root, node), onlyCompiled);
	}

	public OperationProfileNodeUI(OperationProfileNodeInfo node, boolean onlyCompiled) {
		super(node);
		init(onlyCompiled);
	}

	protected void init(boolean onlyCompiled) {
		OperationProfileNodeInfo node = (OperationProfileNodeInfo) getUserObject();

		if (node != null) {
			List<OperationProfileNodeInfo> children;
			List<OperationProfileNode> nodes = new ArrayList<>(node.getNode().getChildren(comparator));

			if (onlyCompiled) {
				children = nodes.stream()
						.map(n -> new OperationProfileNodeInfo(node.getRoot(), n))
						.map(this::compiledChildren)
						.flatMap(List::stream)
						.collect(Collectors.toList());
			} else {
				children = nodes.stream()
						.map(n -> new OperationProfileNodeInfo(node.getRoot(), n))
						.collect(Collectors.toList());
			}

			children.forEach(child ->
					add(new OperationProfileNodeUI(child, onlyCompiled)));
		}
	}

	protected List<OperationProfileNodeInfo> compiledChildren(OperationProfileNodeInfo node) {
		if (node.isCompiled()) {
			return List.of(node);
		}

		return node.getNode().getChildren(comparator).stream()
				.map(n -> new OperationProfileNodeInfo(node.getRoot(), n))
				.map(this::compiledChildren)
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}
}
