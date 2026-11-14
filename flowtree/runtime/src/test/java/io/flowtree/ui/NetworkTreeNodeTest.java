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

package io.flowtree.ui;

import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import io.flowtree.node.NodeGroupNodeConfig;
import org.almostrealism.util.TestSuiteBase;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Exercises {@link NetworkTreeNode}, the {@link MutableTreeNode} adapter that wraps a FlowTree
 * {@link Node} (or {@link NodeGroup}) so the node/peer graph can be rendered in a Swing tree.
 *
 * <p>The adapter is pure logic — it creates no windows — so it is fully exercisable without a
 * display. These tests pin the three structural shapes it distinguishes: a label-only leaf (no
 * wrapped node), a plain worker node (children are its peers), and a {@link NodeGroup} (children
 * are its member nodes). They also pin the parent-wrapping behaviour and the documented no-op
 * mutators.</p>
 *
 * <p>Node groups are built in offline mode (per {@link NodeGroupNodeConfig#OFFLINE_MODE_PROPERTY})
 * with no servers so the constructor starts no thread or socket; a {@code NodeGroup} built this way
 * reports its parent as {@code null}, which bounds the recursive parent wrapping.</p>
 *
 * @author Michael Murray
 */
public class NetworkTreeNodeTest extends TestSuiteBase {

	/** The offline-mode property value captured before the suite forced it on, or {@code null} if unset. */
	private static String originalOfflineMode;

	/** Forces offline mode so {@link NodeGroup} construction never contacts a live controller. */
	@BeforeClass
	public static void enforceOfflineMode() {
		originalOfflineMode = System.getProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY);
		System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, "true");
	}

	/** Restores the offline-mode property to the value it held before the suite ran. */
	@AfterClass
	public static void restoreOfflineMode() {
		if (originalOfflineMode == null) {
			System.clearProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY);
		} else {
			System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, originalOfflineMode);
		}
	}

	/**
	 * Builds a minimal, fully offline node group with the requested number of worker nodes.
	 *
	 * @param workerCount the number of worker nodes to create
	 * @return the constructed group
	 */
	private static NodeGroup offlineGroup(int workerCount) {
		Properties p = new Properties();
		p.setProperty("group.thread.sleep", "10000");
		p.setProperty("nodes.initial", String.valueOf(workerCount));
		p.setProperty("nodes.jobs.max", "4");
		p.setProperty("nodes.peers.max", "5");
		p.setProperty("nodes.relay.peers.max", "5");
		p.setProperty("servers.total", "0");
		return new NodeGroup(p, null);
	}

	/**
	 * A node constructed from a label alone wraps no {@link Node}, so it is a childless leaf that
	 * does not allow children and has no parent.
	 */
	@Test(timeout = 20000)
	public void labelOnlyNodeIsAChildlessLeaf() {
		NetworkTreeNode leaf = new NetworkTreeNode("worker-1");

		Assert.assertTrue("a label-only node wraps no Node and is therefore a leaf", leaf.isLeaf());
		Assert.assertFalse("a leaf does not allow children", leaf.getAllowsChildren());
		Assert.assertEquals("a leaf has no children", 0, leaf.getChildCount());
		Assert.assertNull("a leaf has no child at any index", leaf.getChildAt(0));
		Assert.assertNull("a label-only node has no parent", leaf.getParent());
	}

	/**
	 * {@link NetworkTreeNode#getIndex(TreeNode)} is documented to always return {@code -1},
	 * regardless of the argument.
	 */
	@Test(timeout = 20000)
	public void getIndexIsAlwaysNegativeOne() {
		NetworkTreeNode node = new NetworkTreeNode("label");

		Assert.assertEquals("getIndex never resolves a position", -1, node.getIndex(new NetworkTreeNode("other")));
		Assert.assertEquals("getIndex tolerates a null argument", -1, node.getIndex(null));
	}

	/**
	 * A node wrapping a peerless worker {@link Node} is not a leaf and allows children, but has no
	 * children because the worker has no peers; its {@code children()} enumeration is empty.
	 */
	@Test(timeout = 20000)
	public void peerlessWorkerNodeHasNoChildrenButIsNotLeaf() {
		Node worker = new Node(null, 7, 1, 1);
		NetworkTreeNode treeNode = new NetworkTreeNode(worker);

		Assert.assertFalse("a node wrapping a Node is not a leaf", treeNode.isLeaf());
		Assert.assertTrue("a wrapped Node allows children", treeNode.getAllowsChildren());
		Assert.assertEquals("a peerless worker has no peer children", 0, treeNode.getChildCount());
		Assert.assertNull("a standalone worker's wrapped node has no parent", treeNode.getParent());

		Enumeration children = treeNode.children();
		Assert.assertFalse("the peer enumeration of a peerless worker is empty", children.hasMoreElements());
	}

	/**
	 * A node wrapping a {@link NodeGroup} exposes the group's member nodes as its children, both
	 * through {@link NetworkTreeNode#getChildCount()}/{@link NetworkTreeNode#getChildAt(int)} and
	 * through the {@code children()} enumeration.
	 */
	@Test(timeout = 20000)
	public void nodeGroupExposesMemberNodesAsChildren() {
		NodeGroup group = offlineGroup(2);
		NetworkTreeNode treeNode = new NetworkTreeNode(group);

		Assert.assertFalse("a group node is not a leaf", treeNode.isLeaf());
		Assert.assertEquals("child count equals the group's member-node count", 2, treeNode.getChildCount());

		TreeNode firstChild = treeNode.getChildAt(0);
		Assert.assertTrue("a group child is itself a NetworkTreeNode", firstChild instanceof NetworkTreeNode);
		Assert.assertFalse("a group child wraps a worker node and is not a leaf", firstChild.isLeaf());
	}

	/**
	 * The {@code children()} enumeration of a group yields exactly one element per member node and
	 * reports exhaustion after the last element.
	 */
	@Test(timeout = 20000)
	public void childrenEnumerationYieldsEachMemberNodeOnce() {
		NodeGroup group = offlineGroup(3);
		NetworkTreeNode treeNode = new NetworkTreeNode(group);

		int counted = 0;
		Enumeration children = treeNode.children();
		while (children.hasMoreElements()) {
			Object child = children.nextElement();
			Assert.assertTrue("each enumerated child is a NetworkTreeNode", child instanceof NetworkTreeNode);
			counted++;
		}

		Assert.assertEquals("the enumeration yields one element per member node", 3, counted);
		Assert.assertFalse("the enumeration is exhausted after the last member", children.hasMoreElements());
		Assert.assertEquals("the enumerated count matches getChildCount", treeNode.getChildCount(), counted);
	}

	/**
	 * A node wrapping a worker that belongs to a group wraps that group as its parent, because the
	 * worker's {@link Node#getParent()} is non-null.
	 */
	@Test(timeout = 20000)
	public void workerNodeWrapsItsGroupAsParent() {
		NodeGroup group = offlineGroup(2);
		Node worker = group.getNodes()[0];
		Assert.assertSame("precondition: the worker's parent is the group", group, worker.getParent());

		NetworkTreeNode treeNode = new NetworkTreeNode(worker);
		TreeNode parent = treeNode.getParent();

		Assert.assertNotNull("a grouped worker exposes a wrapped parent", parent);
		Assert.assertTrue("the parent wrapper is a NetworkTreeNode", parent instanceof NetworkTreeNode);
		Assert.assertEquals("the parent wrapper exposes the group's member nodes as its children",
				2, parent.getChildCount());
	}

	/**
	 * Every mutator on {@link NetworkTreeNode} is documented to do nothing. These calls must neither
	 * throw nor alter the adapter's observable structure.
	 */
	@Test(timeout = 20000)
	public void mutatorsAreNoOps() {
		NodeGroup group = offlineGroup(2);
		Node worker = group.getNodes()[0];
		NetworkTreeNode treeNode = new NetworkTreeNode(worker);
		NetworkTreeNode other = new NetworkTreeNode("other");

		TreeNode parentBefore = treeNode.getParent();
		int childrenBefore = treeNode.getChildCount();

		treeNode.insert(other, 0);
		treeNode.remove(0);
		treeNode.remove((MutableTreeNode) other);
		treeNode.setUserObject("ignored");
		treeNode.setParent(other);
		treeNode.removeFromParent();

		Assert.assertSame("setParent/removeFromParent are no-ops: parent is unchanged",
				parentBefore, treeNode.getParent());
		Assert.assertEquals("insert/remove are no-ops: child count is unchanged",
				childrenBefore, treeNode.getChildCount());
	}
}
