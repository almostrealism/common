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
import org.almostrealism.io.TimingMetric;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class OperationProfileUI {
	public static boolean enableJavaFx = true;

	private JScrollPane textScroll;
	private JTextArea textArea;
	private List<JTree> trees;
	private boolean updatingSelection;

	public OperationProfileUI() {
		trees = new ArrayList<>();
	}

	public JTree createTree(OperationProfileNode root, Consumer<String> textDisplay, ProfileTreeFeatures.TreeStructure structure) {
		JTree tree = new JTree(new OperationProfileNodeUI(root, root, structure));
		trees.add(tree);

		tree.setCellRenderer(new DefaultTreeCellRenderer() {
			@Override
			public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
														  boolean expanded, boolean leaf, int row,
														  boolean hasFocus) {
				super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
				if (!(value instanceof DefaultMutableTreeNode)) return this;

				Object o = ((DefaultMutableTreeNode) value).getUserObject();

				if (o instanceof OperationProfileNodeInfo) {
					if (((OperationProfileNodeInfo) o).isCompiled()) {
						setIcon(getDefaultLeafIcon());
					}
				}

				return this;
			}
		});

		if (textDisplay != null) {
			tree.addTreeSelectionListener(e -> {
				if (tree.getLastSelectedPathComponent() == null || updatingSelection) return;

				OperationProfileNodeInfo node = (OperationProfileNodeInfo)
						((OperationProfileNodeUI) tree.getLastSelectedPathComponent()).getUserObject();
				if (node == null) return;

				StringBuilder out = new StringBuilder();
				String name = root.getMetadataDetail(node.getNode().getKey());

				TimingMetric metric = node.getNode().getMetric();
				if (metric == null) metric = node.getNode().getMergedMetric();
				if (metric != null) {
					out.append(metric.summary(name, root::getMetadataDetail));
				}

				TimingMetric details = node.getNode().getStageDetailTime();
				if (details != null) {
					out.append("\n---------\nStage Details:\n");
					out.append(details.summary(name));
				}

				if (root.getOperationSources().containsKey(node.getNode().getKey())) {
					for (OperationSource source : root.getOperationSources().get(node.getNode().getKey())) {
						if (source.getArgumentKeys() != null) {
							out.append("\n---------\nArguments: \n");
							for (int i = 0; i < source.getArgumentKeys().size(); i++) {
								out.append(source.getArgumentNames().get(i))
										.append(": ")
										.append(root.getMetadataDetail(source.getArgumentKeys().get(i)))
										.append("\n");
							}
						}

						out.append("\n---------\nSource:\n");
						out.append(source.getSource());
						out.append("\n");
					}
				}

				textDisplay.accept(out.toString());
			});
		}

		tree.addTreeSelectionListener(e -> {
			if (tree.getLastSelectedPathComponent() == null || updatingSelection) return;

			OperationProfileNodeInfo selectedNode = (OperationProfileNodeInfo)
					((OperationProfileNodeUI) tree.getLastSelectedPathComponent()).getUserObject();
			if (selectedNode == null) return;

			String selectedKey = selectedNode.getNode().getKey();
			if (selectedKey != null) {
				try {
					updatingSelection = true;
					trees.stream().filter(t -> t != tree).forEach(t -> {
						DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) t.getModel().getRoot();
						traverseAndSelect(rootNode, selectedKey, t);
					});
				} finally {
					updatingSelection = false;
				}
			}
		});

		return tree;
	}

	private void traverseAndSelect(DefaultMutableTreeNode node, String key, JTree tree) {
		if (node == null) return;

		OperationProfileNodeInfo nodeInfo = node.getUserObject() instanceof OperationProfileNodeInfo ?
				(OperationProfileNodeInfo) node.getUserObject() : null;

		if (nodeInfo != null && key.equals(nodeInfo.getNode().getKey())) {
			tree.setSelectionPath(new TreePath(node.getPath()));
			tree.scrollPathToVisible(new TreePath(node.getPath()));
			return;
		}

		for (int i = 0; i < node.getChildCount(); i++) {
			traverseAndSelect((DefaultMutableTreeNode) node.getChildAt(i), key, tree);
		}
	}

	public JFrame display(OperationProfileNode root) {
		textArea = new JTextArea(80, 120);
		textScroll = new JScrollPane(textArea);

		Consumer<String> textDisplay = text -> {
			textArea.setText(text);
			textArea.setCaretPosition(0);
			textScroll.getVerticalScrollBar().setValue(0);
		};

		JSplitPane trees = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
		trees.setLeftComponent(new JScrollPane(createTree(root, textDisplay, ProfileTreeFeatures.TreeStructure.COMPILED_ONLY)));
		trees.setRightComponent(new JScrollPane(createTree(root, textDisplay, ProfileTreeFeatures.TreeStructure.ALL)));

		JSplitPane body = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
		body.setRightComponent(textScroll);
		body.setLeftComponent(trees);

		JFrame frame = new JFrame("OperationProfile - " + root.getName());
		frame.getContentPane().add(body);
		frame.pack();

		frame.setSize(1200, 900);
		frame.setVisible(true);
		return frame;
	}

	public static void main(String args[]) throws IOException {
		if (enableJavaFx) {
			OperationProfileFX.create(args[0]);
		} else {
			OperationProfileUI profileDisplay = new OperationProfileUI();
			profileDisplay.display(OperationProfileNode.load(args[0]))
					.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

			try {
				Thread.sleep(24 * 60 * 60 * 1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
