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

package io.almostrealism.profile;

import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.Tree;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.io.TimingMetric;

import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OperationProfileNode extends OperationProfile implements Tree<OperationProfileNode> {
	private static Function<OperationMetadata, String> metadataDetail =
			OperationProfile.appendContext(
					OperationProfile.appendShape(
							meta -> meta.getShortDescription() == null ?
									meta.getDisplayName() : meta.getShortDescription()));

	private List<OperationProfileNode> children;
	private Map<String, String> operationSources;
	private TimingMetric measuredTime;
	private TimingMetric stageDetailTime;

	private Map<String, String> metadataCache;
	private FrequencyCache<String, OperationProfileNode> nodeCache;

	public OperationProfileNode() { this("default"); }

	public OperationProfileNode(String name) {
		this(name, OperationMetadata::getDisplayName);
	}

	public OperationProfileNode(String name, Function<OperationMetadata, String> key) {
		super(name, key);
	}
	
	public void setName(String name) {
		this.name = name;
	}

	public void setChildren(List<OperationProfileNode> children) {
		children.forEach(this::addChild);
	}

	@Override
	public Collection<OperationProfileNode> getChildren() {
		return getChildren(null);
	}

	public Collection<OperationProfileNode> getChildren(Comparator<? super OperationProfileNode> comparator) {
		if (children == null) return Collections.emptyList();

		return comparator == null ? children : children.stream()
				.sorted(comparator)
				.collect(Collectors.toList());
	}

	public OperationProfileNode addChild(OperationMetadata metadata) {
		return getProfileNode(metadata);
	}

	protected void addChild(OperationProfileNode node) {
		if (children == null) children = new ArrayList<>();
		children.add(node);
	}

	public Map<String, String> getMetadata() {
		return metadataCache;
	}

	public void setMetadata(Map<String, String> metadata) {
		this.metadataCache = metadata;
	}

	public TimingMetric getMeasuredTime() {
		return measuredTime;
	}
	public void setMeasuredTime(TimingMetric measuredTime) {
		this.measuredTime = measuredTime;
	}

	protected void initStageDetailTime() {
		if (stageDetailTime == null) stageDetailTime = new TimingMetric();
	}

	public TimingMetric getStageDetailTime() { return stageDetailTime; }
	public void setStageDetailTime(TimingMetric stageDetailTime) { this.stageDetailTime = stageDetailTime; }

	public String getMetadataDetail(String name) {
		if (metadataCache != null && metadataCache.containsKey(name)) {
			return metadataCache.get(name);
		}

		return name;
	}

	public double getMeasuredDuration() { return measuredTime == null ? 0 : measuredTime.getTotal(); }

	public double getSelfDuration() { return super.getTotalDuration(); }

	public double getChildDuration() {
		return getChildren().stream()
				.mapToDouble(OperationProfileNode::getTotalDuration)
				.sum();
	}

	@Override
	public double getTotalDuration() {
		return getSelfDuration() + getChildDuration();
	}

	public TimingMetric getMergedMetric() {
		if (measuredTime != null) return measuredTime;
		if (getMetric() != null) return getMetric();
		if (children == null) return null;

		TimingMetric metric = new TimingMetric();
		getChildren().stream()
				.map(OperationProfileNode::getMergedMetric)
				.filter(Objects::nonNull)
				.forEach(metric::addAll);
		return metric;
	}

	public void setOperationSources(Map<String, String> operationSources) {
		this.operationSources = operationSources;
	}

	public Map<String, String> getOperationSources() {
		return operationSources;
	}

	public OperationProfileNode getProfileNode(OperationMetadata metadata) {
		return getProfileNode(metadata, true);
	}

	public OperationProfileNode getProfileNode(OperationMetadata metadata, boolean top) {
		if (metadata == null) return null;

		OperationProfileNode node = null;

		if (Objects.equals(metadata.getDisplayName(), getName())) {
			return this;
		} else if (nodeCache != null && nodeCache.containsKey(metadata.getDisplayName())) {
			node = nodeCache.get(metadata.getDisplayName());
		} else if (children != null) {
			node = getChildren().stream().map(v -> v.getProfileNode(metadata, false))
					.filter(Objects::nonNull)
					.findFirst()
					.orElse(null);
		}

		if (top) {
			if (node == null) {
				node = OperationProfileNode.forMetadata(metadata, getKey());
				addChild(node);
			}

			if (nodeCache == null) nodeCache = new FrequencyCache(60, 0.5);
			nodeCache.put(metadata.getDisplayName(), node);

			if (metadataCache == null) metadataCache = new HashMap<>();
			metadataCache.put(metadata.getDisplayName(), metadataDetail.apply(metadata));
		}

		return node;
	}

	@Override
	public void recordDuration(OperationMetadata metadata, long nanos) {
		if (Objects.equals(getName(), metadata.getDisplayName())) {
			if (measuredTime == null) measuredTime = new TimingMetric();
			measuredTime.addEntry(getKey().apply(metadata), nanos);
			return;
		}

		getProfileNode(metadata).recordDuration(metadata, nanos);
	}

	public void recordCompilation(OperationMetadata metadata, String code, long nanos) {
		if (operationSources == null) {
			operationSources = new HashMap<>();
		}

		this.operationSources.put(metadata.getDisplayName(), code);

		OperationProfileNode node = getProfileNode(metadata);
		node.initMetric();
		node.getMetric().addEntry(getKey().apply(metadata) + " compile", nanos);
	}

	@Override
	public OperationTimingListener getRuntimeListener() {
		return (metadata, nanos) -> {
			OperationProfileNode node = getProfileNode(metadata);
			node.initMetric();
			node.getMetric().addEntry(getKey().apply(metadata) + " run", nanos);
		};
	}

	@Override
	public ScopeTimingListener getScopeListener(boolean exclusive) {
		return (root, metadata, stage, nanos) -> {
			OperationProfileNode node = getProfileNode(root);

			if (exclusive) {
				node.initMetric();
				node.getMetric().addEntry(getKey().apply(metadata) + " " + stage, nanos);
			} else {
				node.initStageDetailTime();
				node.getStageDetailTime().addEntry(stage, nanos);
			}
		};
	}

	@Override
	public CompilationTimingListener getCompilationListener() {
		return this::recordCompilation;
	}

	@Override
	public String summary() {
		if (getMetric() != null) return getMetric().summary(getName(), this::getMetadataDetail);
		if (children != null) return getMergedMetric().summary(getName(), this::getMetadataDetail);
		return super.summary();
	}

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
		if (metadata.getChildren() != null) {
			metadata.getChildren().stream()
					.map(v -> OperationProfileNode.forMetadata(v, key))
					.forEach(node::addChild);
		}

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
