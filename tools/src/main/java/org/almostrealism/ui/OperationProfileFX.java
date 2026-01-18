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
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.TimingMetric;

import java.util.ArrayList;
import java.util.List;

/**
 * A JavaFX-based graphical user interface for visualizing and analyzing operation profile data.
 *
 * <p>This class provides a modern, responsive interface for exploring performance profiles
 * of compiled operations in the Almost Realism framework. It extends {@link Application}
 * to create a standalone JavaFX application with multiple synchronized tree views and
 * detailed information panels.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code OperationProfileFX} is designed to:</p>
 * <ul>
 *   <li><strong>Visualize Operation Hierarchies:</strong> Display tree structures of compiled operations</li>
 *   <li><strong>Show Timing Metrics:</strong> Present execution times, averages, and percentages</li>
 *   <li><strong>Display Source Code:</strong> Show generated source code for compiled operations</li>
 *   <li><strong>Analyze Dependencies:</strong> Explore operation arguments and data flow</li>
 *   <li><strong>Synchronize Views:</strong> Keep multiple tree views in sync when selecting nodes</li>
 * </ul>
 *
 * <h2>UI Layout</h2>
 * <p>The interface consists of:</p>
 * <ul>
 *   <li><strong>Top Section:</strong> Split pane with two synchronized tree views:
 *     <ul>
 *       <li>Left: Compiled operations only (high-level structure)</li>
 *       <li>Right: Detailed view (expands to show operation composition)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Bottom Section:</strong> Split pane with:
 *     <ul>
 *       <li>Left: Scope inputs tree showing operation dependencies</li>
 *       <li>Right: Tabbed pane with Info and Source tabs</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p><strong>Launch from command line:</strong></p>
 * <pre>{@code
 * java -jar ar-tools.jar /path/to/profile.xml
 * }</pre>
 *
 * <p><strong>Launch programmatically:</strong></p>
 * <pre>{@code
 * // Start JavaFX application with profile path
 * OperationProfileFX.create("/path/to/profile.xml");
 * }</pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li><strong>Synchronized Selection:</strong> Selecting a node in one tree highlights it in others</li>
 *   <li><strong>Timing Display:</strong> Shows total time, self time, and measured time for operations</li>
 *   <li><strong>Stage Details:</strong> Displays compilation vs execution time breakdown</li>
 *   <li><strong>Argument Inspection:</strong> Shows operation arguments with their metadata</li>
 *   <li><strong>Source Code Viewer:</strong> Displays generated kernel/native source code</li>
 * </ul>
 *
 * @see OperationProfileUI
 * @see ProfileTreeFeatures
 * @see OperationProfileNodeTreeItem
 * @author Michael Murray
 */
public class OperationProfileFX extends Application implements ConsoleFeatures {
	private static String path;

	private TreeView<OperationProfileNodeInfo> compiledTree;
	private TreeView<OperationProfileNodeInfo> detailTree;
	private TreeView<OperationProfileNodeInfo> scopeTree;

	private TextField idField;
	private TextArea infoArea;
	private TextArea sourceArea;

	private List<TreeView<OperationProfileNodeInfo>> trees;
	private boolean updatingSelection;

	/**
	 * Constructs a new OperationProfileFX instance.
	 *
	 * <p>Initializes the list of tree views for synchronized selection tracking.</p>
	 */
	public OperationProfileFX() {
		trees = new ArrayList<>();
	}

	/**
	 * Creates a tree view for displaying profile data with the specified structure mode.
	 *
	 * <p>This method builds a {@link TreeView} populated with profile nodes organized
	 * according to the specified {@link ProfileTreeFeatures.TreeStructure} mode.
	 * The tree can optionally update the info panel on selection and synchronize
	 * selection across multiple tree views.</p>
	 *
	 * @param root The root profile node containing all profile data
	 * @param structure The tree structure mode (ALL, COMPILED_ONLY, STOP_AT_COMPILED, or SCOPE_INPUTS)
	 * @param updateInfo If true, selecting a node updates the info and source panels
	 * @param syncSelection If true, selection is synchronized across all registered tree views
	 * @return A configured TreeView displaying the profile data
	 */
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

	/**
	 * Handles tree selection events and updates the info and source panels.
	 *
	 * <p>When a node is selected, this method extracts timing metrics, stage details,
	 * operation arguments, and source code from the profile data and displays them
	 * in the appropriate UI panels.</p>
	 *
	 * @param observable The observable value that changed
	 * @param oldValue The previously selected tree item (may be null)
	 * @param newValue The newly selected tree item (may be null)
	 */
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

	/**
	 * Synchronizes selection across multiple tree views.
	 *
	 * <p>When a node is selected in one tree view, this method finds and selects
	 * the corresponding node (by key) in all other registered tree views. This
	 * allows users to easily navigate between different structural views of the
	 * same profile data.</p>
	 *
	 * @param treeView The source tree view where selection changed
	 * @param observable The observable value that changed
	 * @param oldValue The previously selected tree item
	 * @param newValue The newly selected tree item
	 */
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
		log("Loading " + path);
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

	/**
	 * Creates and launches the JavaFX profile viewer application.
	 *
	 * <p>This static method sets the profile path and launches the JavaFX application
	 * on a separate thread. The profile data is loaded when the application starts.</p>
	 *
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * // Launch the profile viewer
	 * OperationProfileFX.create("/path/to/profile.xml");
	 * }</pre>
	 *
	 * @param path The file path to the operation profile XML file to load
	 */
	public static void create(String path) {
		OperationProfileFX.path = path;

		Thread t = new Thread(Application::launch);
		t.start();
	}
}
