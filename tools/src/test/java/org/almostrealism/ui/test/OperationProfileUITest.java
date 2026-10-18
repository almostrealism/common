/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ui.test;

import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.ui.OperationProfileNodeInfo;
import org.almostrealism.ui.OperationProfileUI;
import org.almostrealism.ui.ProfileTreeFeatures.TreeStructure;
import org.junit.Assert;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for the tree-building and selection behavior of the Swing profile viewer,
 * {@link OperationProfileUI#createTree}. Only lightweight components are created,
 * so these tests do not need a display; the top-level window ({@code display})
 * and the {@code main} launcher are not exercised.
 */
public class OperationProfileUITest extends OperationProfileTestBase {

	/**
	 * Finds the tree node wrapping the profile node with the given key.
	 *
	 * @param tree the tree to search
	 * @param key  the profile node key
	 * @return the matching tree node
	 */
	private static DefaultMutableTreeNode find(JTree tree, String key) {
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
		for (Object o : Collections.list(root.depthFirstEnumeration())) {
			DefaultMutableTreeNode n = (DefaultMutableTreeNode) o;
			if (key.equals(((OperationProfileNodeInfo) n.getUserObject()).getNode().getKey())) {
				return n;
			}
		}

		Assert.fail("No tree node for key " + key);
		return null;
	}

	/**
	 * Returns the profile key of the node currently selected in the tree.
	 *
	 * @param tree the tree
	 * @return the selected node's key, or {@code null} if nothing is selected
	 */
	private static String selectedKey(JTree tree) {
		TreePath path = tree.getSelectionPath();
		if (path == null) return null;
		DefaultMutableTreeNode n = (DefaultMutableTreeNode) path.getLastPathComponent();
		return ((OperationProfileNodeInfo) n.getUserObject()).getNode().getKey();
	}

	/**
	 * Selects the tree node for the given key.
	 *
	 * @param tree the tree
	 * @param key  the profile node key
	 */
	private static void select(JTree tree, String key) {
		tree.setSelectionPath(new TreePath(find(tree, key).getPath()));
	}

	/**
	 * The tree's root wraps the profile root and its children follow the
	 * requested structure.
	 */
	@Test(timeout = 30000)
	public void createTreeUsesRequestedStructure() {
		OperationProfileNode root = sampleProfile();
		OperationProfileUI ui = new OperationProfileUI();

		JTree compiled = ui.createTree(root, null, TreeStructure.COMPILED_ONLY);
		DefaultMutableTreeNode top = (DefaultMutableTreeNode) compiled.getModel().getRoot();
		Assert.assertSame(root, ((OperationProfileNodeInfo) top.getUserObject()).getNode());
		Assert.assertEquals(2, top.getChildCount());

		JTree all = ui.createTree(root, null, TreeStructure.ALL);
		Assert.assertEquals(3, ((DefaultMutableTreeNode) all.getModel().getRoot()).getChildCount());
	}

	/**
	 * Selecting a compiled node publishes its timing summary, stage details,
	 * argument names resolved to metadata detail, and generated source.
	 */
	@Test(timeout = 30000)
	public void selectingCompiledNodePublishesDetails() {
		OperationProfileNode root = sampleProfile();
		List<String> published = new ArrayList<>();
		JTree tree = new OperationProfileUI().createTree(root, published::add, TreeStructure.ALL);

		select(tree, MATMUL_KEY);

		Assert.assertEquals(1, published.size());
		String text = published.get(0);
		Assert.assertTrue(text, text.contains("Stage Details:"));
		Assert.assertTrue(text, text.contains("arg0: Softmax detail"));
		Assert.assertTrue(text, text.contains("arg1: Add detail"));
		Assert.assertTrue("Unresolvable argument keys fall back to the key itself",
				text.contains("arg2: " + MISSING_KEY));
		Assert.assertTrue(text, text.contains("Source:\n" + KERNEL_SOURCE));
	}

	/**
	 * Selecting a node with neither its own metric nor children, whose source has
	 * no arguments, publishes only the source section.
	 */
	@Test(timeout = 30000)
	public void selectingNodeWithoutMetricPublishesOnlySource() {
		OperationProfileNode root = sampleProfile();
		List<String> published = new ArrayList<>();
		JTree tree = new OperationProfileUI().createTree(root, published::add, TreeStructure.ALL);

		select(tree, COPY_KEY);

		Assert.assertEquals(List.of("\n---------\nSource:\nnull\n"), published);

		select(tree, SOFTMAX_KEY);
		Assert.assertEquals(2, published.size());
		Assert.assertFalse("A non-compiled node has no source section",
				published.get(1).contains("Source:"));
		Assert.assertFalse(published.get(1).contains("Stage Details:"));
	}

	/**
	 * Selecting a node in one tree selects the node with the same key in every
	 * other tree created by the same viewer, without re-publishing details; a
	 * node absent from the other tree leaves that tree's selection unchanged.
	 */
	@Test(timeout = 30000)
	public void selectionIsSynchronizedAcrossTrees() {
		OperationProfileNode root = sampleProfile();
		OperationProfileUI ui = new OperationProfileUI();
		List<String> published = new ArrayList<>();
		JTree compiled = ui.createTree(root, published::add, TreeStructure.COMPILED_ONLY);
		JTree all = ui.createTree(root, published::add, TreeStructure.ALL);

		select(all, MATMUL_KEY);
		Assert.assertEquals(MATMUL_KEY, selectedKey(compiled));
		Assert.assertEquals("Only the user-selected tree publishes details", 1, published.size());

		select(all, SOFTMAX_KEY);
		Assert.assertEquals("Softmax is not in the compiled tree", MATMUL_KEY, selectedKey(compiled));

		select(compiled, COPY_KEY);
		Assert.assertEquals(COPY_KEY, selectedKey(all));

		OperationProfileUI other = new OperationProfileUI();
		JTree independent = other.createTree(root, null, TreeStructure.ALL);
		select(all, MATMUL_KEY);
		Assert.assertNull("Trees of a different viewer are not synchronized", selectedKey(independent));
	}

	/**
	 * The cell renderer shows compiled operations with the leaf icon even when
	 * they have children, while non-compiled branches keep the folder icon.
	 */
	@Test(timeout = 30000)
	public void rendererMarksCompiledNodesWithLeafIcon() {
		OperationProfileNode root = sampleProfile();
		JTree tree = new OperationProfileUI().createTree(root, null, TreeStructure.ALL);
		DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) tree.getCellRenderer();

		DefaultMutableTreeNode matmul = find(tree, MATMUL_KEY);
		Assert.assertFalse(matmul.isLeaf());
		JLabel compiled = (JLabel) renderer.getTreeCellRendererComponent(
				tree, matmul, false, false, false, 0, false);
		Assert.assertSame(renderer.getDefaultLeafIcon(), compiled.getIcon());

		DefaultMutableTreeNode top = (DefaultMutableTreeNode) tree.getModel().getRoot();
		JLabel branch = (JLabel) renderer.getTreeCellRendererComponent(
				tree, top, false, false, false, 0, false);
		Assert.assertSame(renderer.getDefaultClosedIcon(), branch.getIcon());

		JLabel other = (JLabel) renderer.getTreeCellRendererComponent(
				tree, "not a tree node", false, false, true, 0, false);
		Assert.assertEquals("not a tree node", other.getText());
	}
}
