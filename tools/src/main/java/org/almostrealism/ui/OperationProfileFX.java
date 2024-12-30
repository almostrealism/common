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
import javafx.beans.value.ObservableValue;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.almostrealism.io.TimingMetric;

import java.util.ArrayList;
import java.util.List;

public class OperationProfileFX extends Application {
	private static String path;

	private TreeView<OperationProfileNodeInfo> compiledTree;
	private TreeView<OperationProfileNodeInfo> detailTree;
	private TreeView<OperationProfileNodeInfo> scopeTree;

	private TextField idField;
	private TextArea infoArea;
	private TextArea sourceArea;

	private List<TreeView<OperationProfileNodeInfo>> trees;
	private boolean updatingSelection;

	public OperationProfileFX() {
		trees = new ArrayList<>();
	}

	public TreeView<OperationProfileNodeInfo> createTree(OperationProfileNode root,
														 ProfileTreeFeatures.TreeStructure structure,
														 boolean updateInfo, boolean syncSelection) {
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
		if (updateInfo) {
			treeView.getSelectionModel().selectedItemProperty().addListener(this::selected);
		}

		if (syncSelection) {
			treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) ->
							syncSelection(treeView, observable, oldValue, newValue));

			trees.add(treeView);
		}

		return treeView;
	}

	protected void selected(ObservableValue<? extends TreeItem<OperationProfileNodeInfo>> observable,
						   TreeItem<OperationProfileNodeInfo> oldValue,
						   TreeItem<OperationProfileNodeInfo> newValue) {
		if (newValue == null || updatingSelection) return;

		OperationProfileNodeInfo nodeInfo = newValue.getValue();
		OperationProfileNode root = nodeInfo.getRoot();

		idField.setText(nodeInfo.getNode().getKey());

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

				updateSource(source.getSource());
			}
		}

		updateInfo(out.toString());
	}

	protected void syncSelection(TreeView<OperationProfileNodeInfo> treeView,
								 ObservableValue<? extends TreeItem<OperationProfileNodeInfo>> observable,
								 TreeItem<OperationProfileNodeInfo> oldValue,
								 TreeItem<OperationProfileNodeInfo> newValue) {
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

		this.infoArea = new TextArea();
		this.sourceArea = new TextArea();
		this.idField = new TextField();
		this.infoArea.setEditable(false);
		this.sourceArea.setEditable(false);
		this.idField.setEditable(false);

		this.compiledTree = createTree(root, ProfileTreeFeatures.TreeStructure.COMPILED_ONLY, true, true);
		this.detailTree = createTree(root, ProfileTreeFeatures.TreeStructure.STOP_AT_COMPILED, true, false);
		this.scopeTree = createTree(root, ProfileTreeFeatures.TreeStructure.SCOPE_INPUTS, false, false);

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
		
		TabPane tabPane = new TabPane();
		{
			HBox idBox = new HBox();
			idBox.setSpacing(5);
			idBox.getChildren().add(new Label("ID:"));
			idBox.getChildren().add(idField);

			BorderPane infoBox = new BorderPane();
			infoBox.setTop(idBox);
			infoBox.setCenter(createScrollPane(infoArea));

			Tab infoTab = new Tab("Info");
			infoTab.setContent(infoBox);
			infoTab.setClosable(false);

			Tab sourceTab = new Tab("Source");
			sourceTab.setContent(createScrollPane(sourceArea));
			sourceTab.setClosable(false);

			tabPane.getTabs().addAll(infoTab, sourceTab);
		}

		VBox scopeBox = new VBox(new Label("Scope Inputs"),
								createScrollPane(scopeTree));
		SplitPane infoPane = new SplitPane(scopeBox, tabPane);
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

	private void updateInfo(String text) {
		infoArea.setText(text);
		infoArea.setScrollTop(0);
	}

	private void updateSource(String text) {
		sourceArea.setText(text);
		sourceArea.setScrollTop(0);
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
