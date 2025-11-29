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

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A Swing-based graphical user interface for visualizing and analyzing operation profile data.
 *
 * <p>This class provides a classic Swing interface for exploring performance profiles of compiled
 * operations in the Almost Realism framework. It displays operation hierarchies in tree views with
 * synchronized selection and detailed timing information.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code OperationProfileUI} is designed to:</p>
 * <ul>
 *   <li><strong>Display Operation Trees:</strong> Show hierarchical views of operation profiles</li>
 *   <li><strong>Show Timing Details:</strong> Present execution metrics and stage breakdowns</li>
 *   <li><strong>Display Source Code:</strong> Show generated source for compiled operations</li>
 *   <li><strong>Synchronize Views:</strong> Keep multiple tree views in sync during navigation</li>
 * </ul>
 *
 * <h2>UI Layout</h2>
 * <p>The interface consists of:</p>
 * <ul>
 *   <li><strong>Left Tree:</strong> Compiled operations only (high-level view)</li>
 *   <li><strong>Right Tree:</strong> Complete operation hierarchy (detailed view)</li>
 *   <li><strong>Bottom Panel:</strong> Text area showing timing metrics and source code</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p><strong>Command line:</strong></p>
 * <pre>{@code
 * java -cp ar-tools.jar org.almostrealism.ui.OperationProfileUI /path/to/profile.xml
 * }</pre>
 *
 * <p><strong>Programmatic:</strong></p>
 * <pre>{@code
 * OperationProfileUI ui = new OperationProfileUI();
 * OperationProfileNode profile = OperationProfileNode.load("profile.xml");
 * JFrame frame = ui.display(profile);
 * frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
 * }</pre>
 *
 * <h2>JavaFX Alternative</h2>
 * <p>By default, the main method launches the JavaFX-based {@link OperationProfileFX} viewer.
 * Set {@link #enableJavaFx} to {@code false} to use this Swing version instead.</p>
 *
 * @see OperationProfileFX
 * @see ProfileTreeFeatures
 * @see OperationProfileNodeUI
 * @author Michael Murray
 */
public class OperationProfileUI {
	/**
	 * Flag to enable JavaFX-based UI instead of Swing when launching from main().
	 *
	 * <p>When {@code true} (default), the {@link #main(String[])} method launches
	 * {@link OperationProfileFX}. When {@code false}, launches this Swing-based UI.</p>
	 */
	public static boolean enableJavaFx = true;

	private JScrollPane textScroll;
	private JTextArea textArea;
	private List<JTree> trees;
	private boolean updatingSelection;

	/**
	 * Constructs a new OperationProfileUI instance.
	 *
	 * <p>Initializes the list of trees for synchronized selection tracking.</p>
	 */
	public OperationProfileUI() {
		trees = new ArrayList<>();
	}

	/**
	 * Creates a JTree for displaying profile data with the specified structure mode.
	 *
	 * <p>This method builds a {@link JTree} populated with profile nodes organized according
	 * to the specified {@link ProfileTreeFeatures.TreeStructure} mode. The tree includes
	 * custom cell rendering to distinguish compiled operations and synchronized selection
	 * across multiple tree views.</p>
	 *
	 * @param root The root profile node containing all profile data
	 * @param textDisplay Consumer to receive detailed information text when nodes are selected,
	 *                    or null to disable text updates
	 * @param structure The tree structure mode (ALL, COMPILED_ONLY, STOP_AT_COMPILED, or SCOPE_INPUTS)
	 * @return A configured JTree displaying the profile data
	 */
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

	/**
	 * Traverses a tree to find and select a node by its key.
	 *
	 * <p>This method recursively searches the tree for a node whose profile key matches
	 * the specified key, then selects and scrolls to that node. Used for synchronizing
	 * selection across multiple tree views.</p>
	 *
	 * @param node The current node in the traversal
	 * @param key The profile key to search for
	 * @param tree The JTree to update selection in
	 */
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

	/**
	 * Creates and displays a JFrame containing the profile viewer interface.
	 *
	 * <p>This method constructs the complete UI layout with dual tree views and a text
	 * panel for detailed information. The frame is sized to 1200x900 pixels and made
	 * visible immediately.</p>
	 *
	 * @param root The root profile node to display
	 * @return The created and visible JFrame
	 */
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

	/**
	 * Main entry point for the operation profile viewer application.
	 *
	 * <p>Launches either the JavaFX-based or Swing-based profile viewer depending on
	 * the {@link #enableJavaFx} flag. The first command-line argument should be the
	 * path to a profile XML file to load.</p>
	 *
	 * @param args Command-line arguments; args[0] should be the path to a profile XML file
	 * @throws IOException if the profile file cannot be loaded
	 */
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
