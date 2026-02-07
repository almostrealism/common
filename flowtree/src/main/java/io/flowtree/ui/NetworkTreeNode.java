/*
 * Copyright 2017 Michael Murray
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

import io.flowtree.msg.Connection;
import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;

import javax.swing.tree.MutableTreeNode;
import java.util.Enumeration;


/**
 * A {@link NetworkTreeNode} object wraps a network Node object and can be used
 * to display the connections between the node and other nodes.
 * 
 * @author Mike Murray
 */
public class NetworkTreeNode implements javax.swing.tree.MutableTreeNode {
	private String label;
	private Node node;
	private NetworkTreeNode parent;
	
	/**
	 * Constructs a new NetworkTreeNode object that will act as a leaf node and display
	 * the specified label.
	 * 
	 * @param label  String label to use.
	 */
	public NetworkTreeNode(String label) { this.label = label; }
	
	/**
	 * Constructs a new NetworkTreeNode object that will display the info from the specified
	 * network Node object.
	 * 
	 * @param n  Node object to use.
	 */
	public NetworkTreeNode(Node n) {
		this.node = n;
		if (node.getParent() != null) this.parent = new NetworkTreeNode(node.getParent());
	}
	
	/**
	 * @see javax.swing.tree.TreeNode#getChildAt(int)
	 */
	public javax.swing.tree.TreeNode getChildAt(int index) {
		if (this.node == null) {
			return null;
		} else if (this.node instanceof NodeGroup) {
			return new NetworkTreeNode(((NodeGroup)this.node).getNodes()[index]);
		} else {
			return new NetworkTreeNode(this.node.getPeers()[index].toString());
		}
	}

	/**
	 * @see javax.swing.tree.TreeNode#getChildCount()
	 */
	public int getChildCount() {
		if (this.node == null)
			return 0;
		else if (this.node instanceof NodeGroup)
			return ((NodeGroup)this.node).getNodes().length;
		else
			return this.node.getPeers().length;
	}

	/**
	 * @see javax.swing.tree.TreeNode#getParent()
	 */
	public javax.swing.tree.TreeNode getParent() { return this.parent; }

	/**
	 * @see javax.swing.tree.TreeNode#getIndex(javax.swing.tree.TreeNode)
	 * @return  -1
	 */
	public int getIndex(javax.swing.tree.TreeNode node) { return -1; }

	/**
	 * @see javax.swing.tree.TreeNode#getAllowsChildren()
	 */
	public boolean getAllowsChildren() { return (this.node != null); }

	/**
	 * @see javax.swing.tree.TreeNode#isLeaf()
	 */
	public boolean isLeaf() { return (this.node == null); }

	/**
	 * @see javax.swing.tree.TreeNode#children()
	 */
	public Enumeration children() {
		final NetworkTreeNode[] c;
		
		if (this.node instanceof NodeGroup) {
			Node[] n = ((NodeGroup)this.node).getNodes();
			c = new NetworkTreeNode[n.length];
			for (int i = 0; i < c.length; i++) c[i] = new NetworkTreeNode(n[i]);
		} else {
			Connection[] n = this.node.getPeers();
			c = new NetworkTreeNode[n.length];
			for (int i = 0; i < c.length; i++) c[i] = new NetworkTreeNode(n[i].toString());
		}
		
		Enumeration en = new Enumeration() {
			int i = 0;
			
			public boolean hasMoreElements() { return (i < c.length); }
			public Object nextElement() { return c[i++]; }
		};
		
		return en;
	}
	
	/**
	 * Does nothing.
	 */
	public void insert(MutableTreeNode node, int index) { }
	
	/**
	 * Does nothing.
	 */
	public void remove(int index) { }
	
	/**
	 * Does nothing.
	 */
	public void remove(MutableTreeNode node) { }
	
	/**
	 * Does nothing.
	 */
	public void setUserObject(Object o) { }
	
	/**
	 * Does nothing.
	 */
	public void removeFromParent() { }
	
	/**
	 * Does nothing.
	 */
	public void setParent(MutableTreeNode node) { }
}
