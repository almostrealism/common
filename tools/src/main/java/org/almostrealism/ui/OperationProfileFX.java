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
import javafx.application.Application;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.almostrealism.io.TimingMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class OperationProfileFX extends Application {
	private static String path;

	private TreeView<OperationProfileNodeInfo> compiledTree;
	private TreeView<OperationProfileNodeInfo> detailTree;
	private TreeView<OperationProfileNodeInfo> scopeTree;

	private TextArea textArea;
	private List<TreeView<OperationProfileNodeInfo>> trees;
	private boolean updatingSelection;

	public OperationProfileFX() {
		trees = new ArrayList<>();
	}

	public TreeView<OperationProfileNodeInfo> createTree(OperationProfileNode root,
														 Consumer<String> textDisplay,
														 ProfileTreeFeatures.TreeStructure structure,
														 boolean syncSelection) {
		TreeItem<OperationProfileNodeInfo> rootItem = new OperationProfileNodeTreeItem(root, root, structure);

		TreeView<OperationProfileNodeInfo> treeView = new TreeView<>(rootItem);
		treeView.setShowRoot(true);

		// Set cell factory for custom rendering
		treeView.setCellFactory(tv -> new TreeCell<>() {
			@Override
			protected void updateItem(OperationProfileNodeInfo item, boolean empty) {
				super.updateItem(item, empty);

				if (empty || item == null) {
					setText(null);
					setGraphic(null);
				} else {
					if (item.isCompiled()) {
						setText("[Compiled] " + item.getLabel());
					} else {
						setText(getItem().getLabel());
					}
				}
			}
		});

		// Display detailed information about the selected node
		if (textDisplay != null) {
			treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue == null || updatingSelection) return;

				OperationProfileNodeInfo nodeInfo = newValue.getValue();
				StringBuilder out = new StringBuilder();
				String name = root.getMetadataDetail(nodeInfo.getNode().getKey());

				TimingMetric metric = nodeInfo.getNode().getMetric();
				if (metric == null) metric = nodeInfo.getNode().getMergedMetric();
				if (metric != null) {
					out.append(metric.summary(name, root::getMetadataDetail));
				}

				TimingMetric details = nodeInfo.getNode().getStageDetailTime();
				if (details != null) {
					out.append("\n---------\nStage Details:\n");
					out.append(details.summary(name));
				}

				if (root.getOperationSources().containsKey(nodeInfo.getNode().getKey())) {
					for (OperationSource source : root.getOperationSources().get(nodeInfo.getNode().getKey())) {
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

		if (syncSelection) {
			treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue == null || updatingSelection) return;

				OperationProfileNodeInfo selectedNode = newValue.getValue();
				String selectedKey = selectedNode.getNode().getKey();

				if (selectedKey != null) {
					updatingSelection = true;

					try {
						for (TreeView<OperationProfileNodeInfo> otherTree : trees) {
							if (otherTree == treeView) continue;
							traverseAndSelect(otherTree.getRoot(), selectedKey, otherTree);
						}
					} finally {
						updatingSelection = false;
					}
				}
			});

			trees.add(treeView);
		}

		return treeView;
	}

	private void expandAll(TreeItem<OperationProfileNodeInfo> item) {
		if (item == null) return;

		item.setExpanded(true);
		item.getChildren().forEach(this::expandAll);
	}

	private void traverseAndSelect(TreeItem<OperationProfileNodeInfo> currentItem, String key,
								   TreeView<OperationProfileNodeInfo> tree) {
		if (currentItem == null) return;

		if (key == null) {
			currentItem.setExpanded(true);
		} else {
			OperationProfileNodeInfo nodeInfo = currentItem.getValue();

			if (nodeInfo != null && key.equals(nodeInfo.getNode().getKey())) {
				tree.getSelectionModel().select(currentItem);
				tree.scrollTo(tree.getRow(currentItem));
				return;
			}
		}

		currentItem.getChildren().forEach(c ->
				traverseAndSelect(c, null, tree));
	}

	@Override
	public void start(Stage stage) throws Exception {
		OperationProfileNode root = OperationProfileNode.load(path);

		this.textArea = new TextArea();
		this.compiledTree = createTree(root, this::updateText, ProfileTreeFeatures.TreeStructure.COMPILED_ONLY, true);
		this.detailTree = createTree(root, this::updateText, ProfileTreeFeatures.TreeStructure.STOP_AT_COMPILED, false);
		this.scopeTree = createTree(root, null, ProfileTreeFeatures.TreeStructure.SCOPE_INPUTS, false);

		this.compiledTree.getSelectionModel().selectedItemProperty().addListener((observableValue, oldValue, newValue) -> {
			if (newValue == null) return;

			OperationProfileNodeInfo nodeInfo = newValue.getValue();
			if (nodeInfo != null) {
				this.detailTree.setRoot(new OperationProfileNodeTreeItem(root, nodeInfo.getNode(),
						ProfileTreeFeatures.TreeStructure.STOP_AT_COMPILED));
				this.scopeTree.setRoot(new OperationProfileNodeTreeItem(root, nodeInfo.getNode(),
						ProfileTreeFeatures.TreeStructure.SCOPE_INPUTS));

				expandAll(this.detailTree.getRoot());
				expandAll(this.scopeTree.getRoot());
			}
		});

		SplitPane treesPane = new SplitPane(createScrollPane(compiledTree), createScrollPane(detailTree));
		treesPane.setDividerPositions(0.5);

		SplitPane infoPane = new SplitPane(createScrollPane(scopeTree), createScrollPane(textArea));
		infoPane.setDividerPositions(0.3);

		SplitPane rootPane = new SplitPane(treesPane, infoPane);
		rootPane.setOrientation(Orientation.VERTICAL);
		rootPane.setDividerPositions(0.55);

		BorderPane mainPane = new BorderPane();
		mainPane.setCenter(rootPane);

		Scene scene = new Scene(mainPane, 1250, 800);
		stage.setScene(scene);
		stage.setTitle("OperationProfile - " + root.getName());
		stage.show();
	}

	private void updateText(String text) {
		textArea.setText(text);
		textArea.setScrollTop(0);
	}

	private ScrollPane createScrollPane(Node content) {
		ScrollPane scrollPane = new ScrollPane(content);
		scrollPane.setFitToWidth(true);
		scrollPane.setFitToHeight(true);
		return scrollPane;
	}

	public static void create(String path) {
		OperationProfileFX.path = path;

		Thread t = new Thread(Application::launch);
		t.start();
	}
}
