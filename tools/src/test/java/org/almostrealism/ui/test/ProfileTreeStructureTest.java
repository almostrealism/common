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
import io.almostrealism.profile.OperationSource;
import javafx.scene.control.TreeItem;
import org.almostrealism.ui.OperationProfileNodeInfo;
import org.almostrealism.ui.OperationProfileNodeTreeItem;
import org.almostrealism.ui.OperationProfileNodeUI;
import org.almostrealism.ui.ProfileTreeFeatures.TreeStructure;
import org.junit.Assert;
import org.junit.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Tests for the tree-building behavior shared by the profile viewers:
 * {@link TreeStructure} (via its public consumers {@link OperationProfileNodeUI}
 * and {@link OperationProfileNodeTreeItem}) and {@link OperationProfileNodeInfo}.
 *
 * <p>The Swing node and the JavaFX tree item are pure models; neither requires a
 * display or the JavaFX toolkit to be constructed.</p>
 */
public class ProfileTreeStructureTest extends OperationProfileTestBase {

	/**
	 * Returns the node keys of the direct children of a Swing tree node.
	 *
	 * @param node the tree node
	 * @return the keys of its children, in order
	 */
	private static List<String> childKeys(DefaultMutableTreeNode node) {
		return Collections.list(node.children()).stream()
				.map(c -> ((OperationProfileNodeInfo) ((DefaultMutableTreeNode) c).getUserObject()))
				.map(info -> info.getNode().getKey())
				.collect(Collectors.toList());
	}

	/**
	 * Returns the node keys of the direct children of a JavaFX tree item.
	 *
	 * @param item the tree item
	 * @return the keys of its children, in order
	 */
	private static List<String> childKeys(TreeItem<OperationProfileNodeInfo> item) {
		return item.getChildren().stream()
				.map(c -> c.getValue().getNode().getKey())
				.collect(Collectors.toList());
	}

	/**
	 * The ALL structure shows the whole hierarchy, children ordered by total
	 * duration, including compiled nodes' own children.
	 */
	@Test(timeout = 30000)
	public void allStructureShowsFullHierarchySortedByDuration() {
		OperationProfileNode root = sampleProfile();
		OperationProfileNodeUI tree = new OperationProfileNodeUI(root, root, TreeStructure.ALL);

		Assert.assertEquals(List.of(MATMUL_KEY, SOFTMAX_KEY, COPY_KEY), childKeys(tree));

		DefaultMutableTreeNode matmul = (DefaultMutableTreeNode) tree.getChildAt(0);
		Assert.assertEquals(List.of(INNER_KEY), childKeys(matmul));
		Assert.assertTrue(((DefaultMutableTreeNode) matmul.getChildAt(0)).isLeaf());
		Assert.assertEquals(5, Collections.list(tree.depthFirstEnumeration()).size());
	}

	/**
	 * COMPILED_ONLY hides non-compiled nodes (traversing through them) so only
	 * compiled operations remain; the non-compiled grandchild is hidden too.
	 */
	@Test(timeout = 30000)
	public void compiledOnlyStructureHidesNonCompiledNodes() {
		OperationProfileNode root = sampleProfile();
		OperationProfileNodeUI tree = new OperationProfileNodeUI(root, root, TreeStructure.COMPILED_ONLY);

		Assert.assertEquals(List.of(MATMUL_KEY, COPY_KEY), childKeys(tree));
		Assert.assertTrue("Non-compiled Inner Add is skipped beneath MatMul",
				tree.getChildAt(0).isLeaf());
	}

	/**
	 * COMPILED_ONLY promotes compiled descendants of a non-compiled node to the
	 * place the non-compiled node would have occupied.
	 */
	@Test(timeout = 30000)
	public void compiledOnlyStructurePromotesCompiledDescendants() {
		OperationProfileNode leaf = node("k-leaf", "Leaf");
		OperationProfileNode wrapper = node("k-wrapper", "Wrapper");
		wrapper.setChildren(new ArrayList<>(List.of(leaf)));
		OperationProfileNode root = node(ROOT_KEY, "Root");
		root.setChildren(new ArrayList<>(List.of(wrapper)));

		Map<String, List<OperationSource>> sources = new HashMap<>();
		sources.put("k-leaf", List.of(new OperationSource("code", List.of(), List.of())));
		root.setOperationSources(sources);

		OperationProfileNodeUI compiled = new OperationProfileNodeUI(root, root, TreeStructure.COMPILED_ONLY);
		Assert.assertEquals(List.of("k-leaf"), childKeys(compiled));

		OperationProfileNodeUI all = new OperationProfileNodeUI(root, root, TreeStructure.ALL);
		Assert.assertEquals(List.of("k-wrapper"), childKeys(all));
	}

	/**
	 * STOP_AT_COMPILED expands the hierarchy but does not descend into compiled
	 * operations, so MatMul has no children while it does under ALL.
	 */
	@Test(timeout = 30000)
	public void stopAtCompiledStructureStopsAtCompiledNodes() {
		OperationProfileNode root = sampleProfile();
		OperationProfileNodeUI tree = new OperationProfileNodeUI(root, root, TreeStructure.STOP_AT_COMPILED);

		Assert.assertEquals(List.of(MATMUL_KEY, SOFTMAX_KEY, COPY_KEY), childKeys(tree));
		Assert.assertTrue(tree.getChildAt(0).isLeaf());
		Assert.assertEquals(4, Collections.list(tree.depthFirstEnumeration()).size());
	}

	/**
	 * SCOPE_INPUTS, rooted at a compiled node, lists the nodes named by the
	 * program's argument keys in argument order, skipping keys that do not
	 * resolve to a node; for a non-compiled root it falls back to process children.
	 */
	@Test(timeout = 30000)
	public void scopeInputsStructureFollowsProgramArguments() {
		OperationProfileNode root = sampleProfile();
		OperationProfileNode matmul = root.getProfileNode(MATMUL_KEY).orElseThrow();

		OperationProfileNodeTreeItem scope =
				new OperationProfileNodeTreeItem(root, matmul, TreeStructure.SCOPE_INPUTS);
		Assert.assertEquals("Arguments in program order; unresolvable key dropped",
				List.of(SOFTMAX_KEY, INNER_KEY), childKeys(scope));

		OperationProfileNodeTreeItem all =
				new OperationProfileNodeTreeItem(root, matmul, TreeStructure.ALL);
		Assert.assertEquals(List.of(INNER_KEY), childKeys(all));

		OperationProfileNodeTreeItem rootScope =
				new OperationProfileNodeTreeItem(root, root, TreeStructure.SCOPE_INPUTS);
		Assert.assertEquals(List.of(MATMUL_KEY, SOFTMAX_KEY, COPY_KEY), childKeys(rootScope));
		Assert.assertTrue("Compiled children are not expanded",
				rootScope.getChildren().get(0).getChildren().isEmpty());
	}

	/**
	 * The info-based tree item constructor treats the item as the root only when
	 * the wrapped node is the profile root, which determines whether a compiled
	 * node is expanded.
	 */
	@Test(timeout = 30000)
	public void treeItemRootDetectionControlsExpansion() {
		OperationProfileNode root = sampleProfile();
		OperationProfileNode matmul = root.getProfileNode(MATMUL_KEY).orElseThrow();

		OperationProfileNodeTreeItem nonRoot = new OperationProfileNodeTreeItem(
				new OperationProfileNodeInfo(root, matmul), TreeStructure.STOP_AT_COMPILED);
		Assert.assertTrue("A compiled non-root node is not expanded", nonRoot.getChildren().isEmpty());

		OperationProfileNodeTreeItem forcedRoot = new OperationProfileNodeTreeItem(
				new OperationProfileNodeInfo(root, matmul), TreeStructure.STOP_AT_COMPILED, true);
		Assert.assertEquals(List.of(INNER_KEY), childKeys(forcedRoot));

		OperationProfileNodeTreeItem actualRoot = new OperationProfileNodeTreeItem(
				new OperationProfileNodeInfo(root, root), TreeStructure.STOP_AT_COMPILED);
		Assert.assertEquals(List.of(MATMUL_KEY, SOFTMAX_KEY, COPY_KEY), childKeys(actualRoot));
		Assert.assertSame(matmul, actualRoot.getChildren().get(0).getValue().getNode());
	}

	/**
	 * A node is compiled exactly when the root has sources under its key, and
	 * the program is the first source registered for that key.
	 */
	@Test(timeout = 30000)
	public void nodeInfoReportsCompilationAndProgram() {
		OperationProfileNode root = sampleProfile();
		OperationProfileNodeInfo matmul = new OperationProfileNodeInfo(root,
				root.getProfileNode(MATMUL_KEY).orElseThrow());
		OperationProfileNodeInfo softmax = new OperationProfileNodeInfo(root,
				root.getProfileNode(SOFTMAX_KEY).orElseThrow());

		Assert.assertTrue(matmul.isCompiled());
		Optional<OperationSource> program = matmul.getProgram();
		Assert.assertTrue(program.isPresent());
		Assert.assertEquals(KERNEL_SOURCE, program.get().getSource());

		Assert.assertFalse(softmax.isCompiled());
		Assert.assertFalse(softmax.getProgram().isPresent());
		Assert.assertSame(root, softmax.getRoot());
	}

	/**
	 * The label combines metadata detail with the total duration, adds the self
	 * duration when children contribute time, and adds the measured time when present.
	 */
	@Test(timeout = 30000)
	public void nodeInfoLabelIncludesSelfAndMeasuredTime() {
		OperationProfileNode root = sampleProfile();

		OperationProfileNodeInfo matmul = new OperationProfileNodeInfo(root,
				root.getProfileNode(MATMUL_KEY).orElseThrow());
		Assert.assertEquals("MatMul (64x64) - 2.25 seconds total (2.00s self)", matmul.getLabel());

		OperationProfileNodeInfo softmax = new OperationProfileNodeInfo(root,
				root.getProfileNode(SOFTMAX_KEY).orElseThrow());
		Assert.assertEquals("Softmax detail - 0.75 seconds [0.00s measured]", softmax.getLabel());

		OperationProfileNodeInfo copy = new OperationProfileNodeInfo(root,
				root.getProfileNode(COPY_KEY).orElseThrow());
		Assert.assertEquals("Without metadata the key is used", COPY_KEY + " - 0.00 seconds", copy.toString());
	}

	/**
	 * Durations over three minutes are expressed in minutes, and the label is
	 * computed once and cached even if the underlying metadata later changes.
	 */
	@Test(timeout = 30000)
	public void nodeInfoLabelUsesMinutesAndIsCached() {
		OperationProfileNode child = node("k-long", "Long");
		child.setMetric(metric("x run", 240.0));
		child.setMeasuredTime(metric("m", 200.0));

		OperationProfileNode parent = node("k-parent", "Parent");
		parent.setMetric(metric("x run", 60.0));
		parent.setChildren(new ArrayList<>(List.of(child)));

		OperationProfileNodeInfo info = new OperationProfileNodeInfo(parent, parent);
		Assert.assertEquals("k-parent - 5.00 minutes total (60.00s self)", info.getLabel());

		OperationProfileNodeInfo longInfo = new OperationProfileNodeInfo(parent, child);
		String label = longInfo.getLabel();
		Assert.assertEquals("k-long - 4.00 minutes [3.333m measured]", label);

		parent.setMetadata(new HashMap<>(Map.of("k-long", "Renamed")));
		Assert.assertEquals("Label is cached after first computation", label, longInfo.getLabel());
		Assert.assertEquals("A fresh info sees the new metadata",
				"Renamed - 4.00 minutes [3.333m measured]",
				new OperationProfileNodeInfo(parent, child).getLabel());
	}
}
