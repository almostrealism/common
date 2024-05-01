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

package io.almostrealism.code;

import io.almostrealism.relation.Tree;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OperationProfileNode extends OperationProfile implements Tree<OperationProfileNode> {
	private Map<String, OperationProfileNode> children;
	private Map<String, OperationProfileNode> parentCache;

	public OperationProfileNode() { this("default"); }

	public OperationProfileNode(String name) {
		this(name, OperationProfile::defaultKey);
	}

	public OperationProfileNode(String name, Function<OperationMetadata, String> key) {
		super(name, key);
		children = new HashMap<>();
		parentCache = new HashMap<>();
	}

	@Override
	public double getTotalDuration() {
		double duration = super.getTotalDuration();
		if (duration > 0) return duration;

		return getChildren().stream()
				.mapToDouble(OperationProfileNode::getTotalDuration)
				.sum();
	}

	protected void addChild(OperationProfileNode node) {
		children.put(node.getName(), node);
		node.getChildren().forEach(v -> parentCache.put(v.getName(), node));
	}

	public void addChildren(OperationMetadata metadata) {
		if (metadata.getDisplayName() == null)
			throw new IllegalArgumentException();

		if (!Objects.equals(getName(), metadata.getDisplayName())) {
			addChild(OperationProfileNode.forMetadata(metadata, getKey()));
			return;
		}

		if (metadata.getChildren() != null) {
			metadata.getChildren().stream()
					.map(v -> OperationProfileNode.forMetadata(v, getKey()))
					.forEach(this::addChild);
		}
	}

	@Override
	public Collection<OperationProfileNode> getChildren() {
		return children.values().stream()
				.sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
				.collect(Collectors.toList());
	}

	@Override
	public void recordDuration(OperationMetadata metadata, long nanos) {
		if (children.containsKey(metadata.getDisplayName())) {
			super.recordDuration(metadata, nanos);
			return;
		}

		OperationProfileNode node = getProfileNode(metadata);

		if (node != null) {
			node.recordDuration(metadata, nanos);
			return;
		}

		warn("No profile node found for " + metadata.getDisplayName());
		addChildren(metadata);
		recordDuration(metadata, nanos);
	}

	public OperationProfileNode getProfileNode(OperationMetadata metadata) {
		if (Objects.equals(getName(), metadata.getDisplayName())) {
			throw new IllegalArgumentException("Profiling data for " + metadata.getDisplayName() +
					" should be recorded in the parent OperationProfileNode");
		} else if (parentCache.containsKey(metadata.getDisplayName())) {
			return parentCache.get(metadata.getDisplayName());
		} else {
			OperationProfileNode node = getChildren().stream().map(v -> v.getProfileNode(metadata))
					.filter(Objects::nonNull)
					.findFirst()
					.orElse(null);
			if (node == null) {
				return null;
			}

			parentCache.put(metadata.getDisplayName(), node);
			return node;
		}
	}

	@Override
	public String toString() {
		return getName() + " - " + getMetric().getFormat().format(getTotalDuration()) + " seconds";
	}

	public static OperationProfileNode forMetadata(OperationMetadata metadata) {
		return forMetadata(metadata, OperationProfile::defaultKey);
	}

	public static OperationProfileNode forMetadata(OperationMetadata metadata, Function<OperationMetadata, String> key) {
		if (metadata == null)
			return null;

		OperationProfileNode node = new OperationProfileNode(metadata.getDisplayName(), key);
		node.addChildren(metadata);
		return node;
	}
}
