/*
 * Copyright 2016 Michael Murray
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

/**
 * Swing-based user interface components for configuring and monitoring a
 * FlowTree network.
 *
 * <p>The primary entry point is {@link io.flowtree.ui.NetworkDialog}, a panel
 * embedded in a {@link javax.swing.JFrame} that allows the user to set node
 * counts, peer limits, server addresses, and output-server coordinates, and
 * to start or stop the {@link io.flowtree.node.Client}. Supporting dialogs
 * include:
 * <ul>
 *   <li>{@link io.flowtree.ui.LoginDialog} — collects credentials before
 *       starting the client.</li>
 *   <li>{@link io.flowtree.ui.SendTaskDialog} — collects rendering-task
 *       parameters and submits them to a selected peer.</li>
 *   <li>{@link io.flowtree.ui.NetworkTreeNode} — a {@link javax.swing.tree.MutableTreeNode}
 *       adapter for displaying the node/peer graph in a Swing tree.</li>
 *   <li>{@link io.flowtree.ui.NetworkClient} — a thin programmatic client
 *       for sending jobs directly to a server socket without a GUI.</li>
 * </ul>
 *
 * @author  Michael Murray
 */
package io.flowtree.ui;