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
import java.awt.Component;
import java.io.IOException;
import java.util.function.Consumer;

public class OperationProfileUI {
	private JScrollPane textScroll;
	private JTextArea textArea;

	public JTree createTree(OperationProfileNode root, Consumer<String> textDisplay) {
		JTree tree = new JTree(new OperationProfileNodeUI(root, root));

		tree.setCellRenderer(new DefaultTreeCellRenderer() {
			@Override
			public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
				super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
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
				if (tree.getLastSelectedPathComponent() == null) return;

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

		return tree;
	}

	public JFrame display(OperationProfileNode root) {
		JSplitPane body = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

		textArea = new JTextArea(80, 120);
		textScroll = new JScrollPane(textArea);

		body.setRightComponent(textScroll);
		body.setLeftComponent(new JScrollPane(createTree(root, text -> {
			textArea.setText(text);
			textArea.setCaretPosition(0);
			textScroll.getVerticalScrollBar().setValue(0);
		})));

		JFrame frame = new JFrame("OperationProfile - " + root.getName());
		frame.getContentPane().add(body);
		frame.pack();

		frame.setSize(1200, 900);
		frame.setVisible(true);
		return frame;
	}

	public static void main(String args[]) throws IOException {
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
