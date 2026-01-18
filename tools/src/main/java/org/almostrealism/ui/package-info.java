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

/**
 * Provides JavaFX and Swing-based UI tools for visualizing and analyzing operation profiling data.
 *
 * <p>This package contains graphical user interfaces for exploring performance profiles of
 * compiled operations in the Almost Realism framework. The tools help developers understand
 * operation hierarchies, execution times, and compilation details.</p>
 *
 * <h2>Core Components</h2>
 *
 * <h3>Profile Viewers</h3>
 * <ul>
 *   <li>{@link org.almostrealism.ui.OperationProfileUI} - Swing-based operation profile viewer</li>
 *   <li>{@link org.almostrealism.ui.OperationProfileFX} - JavaFX-based operation profile viewer</li>
 * </ul>
 *
 * <h3>Tree Visualization</h3>
 * <ul>
 *   <li>{@link org.almostrealism.ui.ProfileTreeFeatures} - Defines tree structure modes for profile display</li>
 *   <li>{@link org.almostrealism.ui.OperationProfileNodeUI} - Tree model for profile nodes</li>
 *   <li>{@link org.almostrealism.ui.OperationProfileNodeInfo} - Wrapper for profile node data</li>
 *   <li>{@link org.almostrealism.ui.OperationProfileNodeTreeItem} - JavaFX tree item for profiles</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <h3>Launch Profile Viewer</h3>
 * <pre>{@code
 * // From command line
 * java -jar ar-tools.jar /path/to/profile.xml
 *
 * // Or programmatically
 * OperationProfileUI ui = new OperationProfileUI();
 * OperationProfileNode profile = OperationProfileNode.load("profile.xml");
 * JFrame frame = ui.display(profile);
 * }</pre>
 *
 * <h3>Tree Structure Modes</h3>
 * <p>The profile viewer supports multiple tree structure modes:</p>
 * <ul>
 *   <li><b>ALL</b> - Show complete operation hierarchy</li>
 *   <li><b>COMPILED_ONLY</b> - Show only compiled operations, skipping intermediate nodes</li>
 *   <li><b>STOP_AT_COMPILED</b> - Expand tree until reaching compiled operations</li>
 *   <li><b>SCOPE_INPUTS</b> - Show operation dependencies based on program arguments</li>
 * </ul>
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li><b>Dual Tree View:</b> Compare compiled vs. complete operation hierarchies side-by-side</li>
 *   <li><b>Timing Metrics:</b> View detailed execution times and percentages</li>
 *   <li><b>Source Code Display:</b> Inspect generated source code for compiled operations</li>
 *   <li><b>Argument Tracking:</b> See operation arguments and their metadata</li>
 *   <li><b>Synchronized Selection:</b> Selecting a node in one tree highlights it in all views</li>
 * </ul>
 *
 * @see io.almostrealism.profile.OperationProfileNode
 * @see org.almostrealism.io.TimingMetric
 * @author Michael Murray
 */
package org.almostrealism.ui;
