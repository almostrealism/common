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
import org.almostrealism.io.MetricBase;

import java.util.function.Function;

public class OperationProfileNodeInfo {
	private OperationProfileNode root;
	private OperationProfileNode node;
	private String label;

	public OperationProfileNodeInfo(OperationProfileNode root, OperationProfileNode node) {
		this.root = root;
		this.node = node;
	}

	public OperationProfileNode getRoot() {
		return root;
	}

	public OperationProfileNode getNode() {
		return node;
	}

	public boolean isCompiled() {
		return getRoot().getOperationSources().containsKey(getNode().getKey());
	}

	@Override
	public String toString() {
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
}
