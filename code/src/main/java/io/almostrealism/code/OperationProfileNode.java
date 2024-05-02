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
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.io.MetricBase;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OperationProfileNode extends OperationProfile implements Tree<OperationProfileNode> {
	private Map<String, OperationProfileNode> children;
	private FrequencyCache<String, OperationProfileNode> parentCache;

	public OperationProfileNode() { this("default"); }

	public OperationProfileNode(String name) {
		this(name, OperationProfile::defaultKey);
	}

	public OperationProfileNode(String name, Function<OperationMetadata, String> key) {
		super(name, key);
	}

	protected void initChildren() {
		if (children == null) {
			children = new HashMap<>();
		}
	}

	protected void initParentCache() {
		initChildren();

		if (parentCache == null) {
			log("Creating cache for " + getName() + " (" + children.size() + " children)");
			parentCache = new FrequencyCache(60, 0.5);
		}
	}

	public double getSelfDuration() {
		return super.getTotalDuration();
	}

	public double getChildDuration() {
		return getChildren().stream()
				.mapToDouble(OperationProfileNode::getTotalDuration)
				.sum();
	}

	@Override
	public double getTotalDuration() {
		return getSelfDuration() + getChildDuration();
	}

	protected void addChild(OperationProfileNode node) {
		initChildren();
		children.put(node.getName(), node);
	}

	public void addChildren(OperationMetadata metadata) {
		if (metadata.getDisplayName() == null)
			throw new IllegalArgumentException();

		if (children != null && children.containsKey(metadata.getDisplayName()))
			return;

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
		if (children == null) return Collections.emptyList();

		return children.values().stream()
				.sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
				.collect(Collectors.toList());
	}

	@Override
	public void recordDuration(OperationMetadata metadata, long nanos) {
		if (children != null && children.containsKey(metadata.getDisplayName())) {
			super.recordDuration(metadata, nanos);
			return;
		}

		OperationProfileNode node = getProfileNode(metadata);

		if (node != null) {
			initParentCache();
			parentCache.put(metadata.getDisplayName(), node);

			node.recordDuration(metadata, nanos);
			return;
		}

		warn("No profile node found for " + metadata.getDisplayName());
		addChildren(metadata);
		recordDuration(metadata, nanos);
	}

	public OperationProfileNode getProfileNode(OperationMetadata metadata) {
		if (children == null) {
			return null;
		} else if (children.containsKey(metadata.getDisplayName())) {
			return this;
		} else if (parentCache != null && parentCache.containsKey(metadata.getDisplayName())) {
			return parentCache.get(metadata.getDisplayName());
		} else {
			return getChildren().stream().map(v -> v.getProfileNode(metadata))
					.filter(Objects::nonNull)
					.findFirst()
					.orElse(null);
		}
	}

	@Override
	public String toString() {
		double selfDuration = getSelfDuration();

		if (selfDuration == 0.0) {
			return getName() + " - " + MetricBase.format.getValue().format(getTotalDuration()) +
					" seconds";
		} else {
			return getName() + " - " + MetricBase.format.getValue().format(getTotalDuration()) +
					" seconds (" + MetricBase.format.getValue().format(getChildDuration()) +
					"s + " + MetricBase.format.getValue().format(selfDuration) + "s)";

		}
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
