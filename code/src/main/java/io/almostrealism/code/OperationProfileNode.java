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

import io.almostrealism.profile.CompilationProfile;
import io.almostrealism.relation.Tree;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.io.TimingMetric;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OperationProfileNode extends OperationProfile implements Tree<OperationProfileNode> {
	private static Function<OperationMetadata, String> metadataDetail =
			OperationProfile.appendContext(
							meta -> meta.getShortDescription() == null ?
									meta.getDisplayName() : meta.getShortDescription());

	private CompilationProfile compilation;
	private Map<String, OperationProfileNode> children;
	private Map<String, String> metadataCache;
	private FrequencyCache<String, OperationProfileNode> parentCache;

	public OperationProfileNode() { this("default"); }

	public OperationProfileNode(String name) {
		this(name, OperationMetadata::getDisplayName);
	}

	public OperationProfileNode(String name, Function<OperationMetadata, String> key) {
		super(name, key);
	}

	protected void initChildren() {
		if (children == null) {
			children = new HashMap<>();
		}
	}

	protected void initMetadataCache() {
		if (metadataCache == null) {
			metadataCache = new HashMap<>();
		}
	}

	protected void initParentCache() {
		initChildren();

		if (parentCache == null) {
			// log("Creating cache for " + getName() + " (" + children.size() + " children)");
			parentCache = new FrequencyCache(60, 0.5);
		}
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setChildren(List<OperationProfileNode> children) {
		initChildren();
		children.forEach(this::addChild);
	}

	@Override
	public Collection<OperationProfileNode> getChildren() {
		if (children == null) return Collections.emptyList();

		return children.values().stream()
				.sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
				.collect(Collectors.toList());
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

	public Map<String, String> getMetadata() {
		return metadataCache;
	}

	public void setMetadata(Map<String, String> metadata) {
		this.metadataCache = metadata;
	}

	public String getMetadataDetail(String name) {
		if (metadataCache != null && metadataCache.containsKey(name)) {
			return metadataCache.get(name);
		}

		return name;
	}

	public void setCompilationProfile(CompilationProfile compilation) {
		this.compilation = compilation;
	}

	public CompilationProfile getCompilationProfile() {
		if (compilation == null) {
			compilation = new CompilationProfile(getName(), getKey());
		}

		return compilation;
	}

	public double getSelfDuration() { return super.getTotalDuration(); }

	public double getChildDuration() {
		return getChildren().stream()
				.filter(c -> getMetric() == null || !getMetricEntries().containsKey(c.getName()))
				.mapToDouble(OperationProfileNode::getTotalDuration)
				.sum();
	}

	@Override
	public double getTotalDuration() {
		return getSelfDuration() + getChildDuration();
	}

	public void setCompilationMetricEntries(Map<String, Double> entries) {
		if (entries ==  null || entries.isEmpty()) compilation = null;
		getCompilationProfile().setMetricEntries(entries);
	}

	public Map<String, Double> getCompilationMetricEntries() {
		return compilation == null ? null : getCompilationProfile().getMetricEntries();
	}

	public void setCompilationMetricCounts(Map<String, Integer> counts) {
		if (counts == null || counts.isEmpty()) compilation = null;
		getCompilationProfile().setMetricCounts(counts);
	}

	public Map<String, Integer> getCompilationMetricCounts() {
		return compilation == null ? null : getCompilationProfile().getMetricCounts();
	}


	public void setOperationSources(Map<String, String> operationSources) {
		if (operationSources == null || operationSources.isEmpty()) return;
		getCompilationProfile().setOperationSources(operationSources);
	}

	public Map<String, String> getOperationSources() {
		if (compilation == null) return new HashMap<>();
		return getCompilationProfile().getOperationSources();
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

	public TimingMetric getMergedMetric() {
		return getMergedMetric(false);
	}

	public TimingMetric getMergedMetric(boolean includeSubsumed) {
		TimingMetric metric = new TimingMetric(getName());
		if (getMetric() != null)
			metric.addAll(getMetric());

		getChildren()
				.stream()
				.filter(c -> includeSubsumed || getMetric() == null || !getMetricEntries().containsKey(c.getName()))
				.forEach(v -> metric.addAll(v.getMergedMetric(includeSubsumed)));

		return metric;
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

			initMetadataCache();
			metadataCache.put(metadata.getDisplayName(), metadataDetail.apply(metadata));

			node.recordDuration(metadata, nanos);
			return;
		}

		warn("No profile node found for " + metadata.getDisplayName());
		addChildren(metadata);
		recordDuration(metadata, nanos);
	}

	public String summary() { return getMergedMetric().summary(getName(), this::getMetadataDetail); }

	public void save(String file) throws IOException {
		save(new File(file));
	}

	public void save(File file) throws IOException {
		try (XMLEncoder encoder = new XMLEncoder(new FileOutputStream(file))) {
			encoder.writeObject(this);
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

	public static OperationProfileNode load(String file) throws IOException {
		return load(new File(file));
	}

	public static OperationProfileNode load(File file) throws IOException {
		try (XMLDecoder in = new XMLDecoder(new FileInputStream(file))) {
			return (OperationProfileNode) in.readObject();
		}
	}
}
