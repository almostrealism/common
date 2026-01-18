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
import org.almostrealism.io.MetricBase;

import java.util.Collections;
import java.util.Optional;
import java.util.function.Function;

/**
 * A wrapper class that pairs an {@link OperationProfileNode} with its root context for UI display.
 *
 * <p>This class encapsulates the data needed to display a profile node in tree views, including
 * access to the root node (for metadata lookups) and formatted labels showing timing information.
 * It provides a bridge between the raw profile data and the UI components that display it.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code OperationProfileNodeInfo} is designed to:</p>
 * <ul>
 *   <li><strong>Maintain Context:</strong> Keep reference to root node for metadata lookups</li>
 *   <li><strong>Format Display:</strong> Generate human-readable labels with timing information</li>
 *   <li><strong>Track Compilation Status:</strong> Determine if a node represents a compiled operation</li>
 *   <li><strong>Provide Program Access:</strong> Retrieve operation source code information</li>
 * </ul>
 *
 * <h2>Label Format</h2>
 * <p>The generated label includes:</p>
 * <ul>
 *   <li>Operation name/description from metadata</li>
 *   <li>Total duration (in seconds or minutes)</li>
 *   <li>Self duration if different from total (time excluding children)</li>
 *   <li>Measured duration if available (actual profiled time)</li>
 * </ul>
 *
 * <p><strong>Example label:</strong></p>
 * <pre>{@code
 * "MatrixMultiply - 5.23 seconds total (2.1s self) [1.8s measured]"
 * }</pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * OperationProfileNode root = OperationProfileNode.load("profile.xml");
 * OperationProfileNode child = root.getChildren().get(0);
 *
 * OperationProfileNodeInfo info = new OperationProfileNodeInfo(root, child);
 *
 * System.out.println(info.getLabel());           // Formatted timing info
 * System.out.println(info.isCompiled());         // true if has compiled source
 * info.getProgram().ifPresent(source ->
 *     System.out.println(source.getSource()));   // Print generated code
 * }</pre>
 *
 * @see OperationProfileNodeUI
 * @see OperationProfileNodeTreeItem
 * @see ProfileTreeFeatures
 * @author Michael Murray
 */
public class OperationProfileNodeInfo {
	private OperationProfileNode root;
	private OperationProfileNode node;
	private String label;

	/**
	 * Constructs a new info wrapper for a profile node.
	 *
	 * @param root The root node of the profile tree (used for metadata lookups)
	 * @param node The specific node this wrapper represents
	 */
	public OperationProfileNodeInfo(OperationProfileNode root, OperationProfileNode node) {
		this.root = root;
		this.node = node;
	}

	/**
	 * Returns the root node of the profile tree.
	 *
	 * <p>The root node is used for metadata lookups and accessing operation source information.</p>
	 *
	 * @return The root profile node
	 */
	public OperationProfileNode getRoot() { return root; }

	/**
	 * Returns the specific profile node this wrapper represents.
	 *
	 * @return The wrapped profile node
	 */
	public OperationProfileNode getNode() { return node; }

	/**
	 * Determines whether this node represents a compiled operation.
	 *
	 * <p>A node is considered compiled if the root's operation sources map contains
	 * an entry for this node's key, indicating that source code was generated and
	 * compiled for this operation.</p>
	 *
	 * @return {@code true} if this node has compiled source code, {@code false} otherwise
	 */
	public boolean isCompiled() {
		return getRoot().getOperationSources().containsKey(getNode().getKey());
	}

	/**
	 * Retrieves the operation source information for this node, if available.
	 *
	 * <p>Returns the first {@link OperationSource} associated with this node's key,
	 * which contains the generated source code and argument information for the
	 * compiled operation.</p>
	 *
	 * @return An Optional containing the operation source, or empty if not compiled
	 */
	public Optional<OperationSource> getProgram() {
		return getRoot().getOperationSources()
				.getOrDefault(getNode().getKey(), Collections.emptyList())
				.stream().findFirst();
	}

	/**
	 * Returns a human-readable label for this node including timing information.
	 *
	 * <p>The label is lazily computed on first access and cached for subsequent calls.
	 * It includes the operation name from metadata and timing information formatted
	 * as follows:</p>
	 * <ul>
	 *   <li>Total duration (in seconds, or minutes if over 3 minutes)</li>
	 *   <li>Self duration if different from total (time excluding children)</li>
	 *   <li>Measured duration if available (actual profiled execution time)</li>
	 * </ul>
	 *
	 * @return A formatted string suitable for display in tree views
	 */
	public String getLabel() {
		if (label == null) {
			Function<Double, String> displayShort = duration ->
					MetricBase.format.getValue().format(
							duration > 180.0 ? (duration / 60.0) : duration) +
							(duration > 180.0 ? "m" : "s");

			Function<Double, String> displayLong = duration ->
					MetricBase.format.getValue().format(
							duration > 180.0 ? (duration / 60.0) : duration) +
							(duration > 180.0 ? " minutes" : " seconds");

			String result = root.getMetadataDetail(node.getKey()) +
					" - " + displayLong.apply(node.getTotalDuration());

			double selfDuration = node.getSelfDuration();
			if (selfDuration > 0.0 && node.getTotalDuration() > selfDuration) {
				result = result + " total (" + displayShort.apply(selfDuration) + " self)";
			}

			double measuredDuration = node.getMeasuredDuration();
			if (measuredDuration > 0.0) {
				result = result + " [" + displayShort.apply(measuredDuration) + " measured]";
			}

			label = result;
		}

		return label;
	}

	/**
	 * Returns a string representation of this node info, which is the formatted label.
	 *
	 * <p>This method delegates to {@link #getLabel()} to provide a human-readable
	 * representation suitable for display in tree views and debugging.</p>
	 *
	 * @return The formatted label string
	 */
	@Override
	public String toString() {
		return getLabel();
	}
}
