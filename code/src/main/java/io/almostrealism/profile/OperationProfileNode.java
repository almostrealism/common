/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Tree;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Nameable;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.io.SystemUtils;
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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OperationProfileNode extends OperationProfile
		implements DescribableParent<OperationProfileNode>,
					Tree<OperationProfileNode>, Nameable {

	public static boolean metadataWarnings = SystemUtils.isEnabled("AR_PROFILE_METADATA_WARNINGS").orElse(false);
	public static boolean allowMultipleSources = SystemUtils.isEnabled("AR_PROFILE_MULTIPLE_SOURCES").orElse(true);

	private static Function<OperationMetadata, String> metadataDetail =
			OperationProfile.appendContext(
					OperationProfile.appendShape(
							meta -> meta.getShortDescription() == null ?
									meta.getDisplayName() : meta.getShortDescription()));

	private List<OperationProfileNode> children;
	private Map<String, List<OperationSource>> operationSources;
	private TimingMetric measuredTime;
	private TimingMetric stageDetailTime;

	private Map<String, String> metadataCache;
	private Map<String, OperationProfileNode> nodeCache;

	public OperationProfileNode() { this(null, "default"); }

	public OperationProfileNode(String name) { this(null, name); }

	protected OperationProfileNode(String key, String name) {
		this(key, name, OperationMetadata::getDisplayName);
	}

	public OperationProfileNode(OperationMetadata metadata) {
		this(metadata, OperationMetadata::getDisplayName);
	}

	public OperationProfileNode(OperationMetadata metadata,
								Function<OperationMetadata, String> identifier) {
		super(metadata, identifier);
	}

	public OperationProfileNode(String key, String name,
								Function<OperationMetadata, String> identifier) {
		super(key, name, identifier);
	}

	/**
	 * Delegates to {@link #addAllChildren(List)} after removing any existing children.
	 * This method is necessary for deserialization.
	 */
	public void setChildren(List<OperationProfileNode> children) {
		this.children = null;

		if (children != null) {
			addAllChildren(children);
		}
	}

	@Override
	public Collection<OperationProfileNode> getChildren() {
		return getChildren(null);
	}

	/**
	 * Returns a {@link Collection} of the children of this node, optionally sorted
	 * by the provided {@link Comparator} if it is not null.
	 * This method does not allow modification of the actual children, despite
	 * returning a mutable {@link Collection} for compatibility with serialization.
	 */
	public Collection<OperationProfileNode> getChildren(Comparator<? super OperationProfileNode> comparator) {
		if (children == null) return Collections.emptyList();

		return comparator == null ? new ArrayList<>(children) : children.stream()
				.sorted(comparator)
				.collect(Collectors.toList());
	}

	public OperationProfileNode addChild(OperationMetadata metadata) {
		return getProfileNode(metadata);
	}

	protected void addAllChildren(List<OperationProfileNode> children) {
		children.forEach(this::addChild);
	}

	protected void addChild(OperationProfileNode node) {
		if (children == null) children = new ArrayList<>();
		children.add(node);

		if (nodeCache != null) {
			// If caching is active for this node,
			// cache all the new children
			cache(node);
		}
	}

	public Map<String, String> getMetadata() {
		return metadataCache;
	}

	public void setMetadata(Map<String, String> metadata) {
		this.metadataCache = metadata;
	}

	public TimingMetric getMeasuredTime() { return measuredTime; }
	public void setMeasuredTime(TimingMetric measuredTime) { this.measuredTime = measuredTime; }

	protected void initStageDetailTime() {
		if (stageDetailTime == null) stageDetailTime = new TimingMetric();
	}

	public TimingMetric getStageDetailTime() { return stageDetailTime; }
	public void setStageDetailTime(TimingMetric stageDetailTime) { this.stageDetailTime = stageDetailTime; }

	public String getMetadataDetail(String key) {
		if (key == null) return "";

		if (metadataCache != null && metadataCache.containsKey(key)) {
			return metadataCache.get(key);
		}

		return key;
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

	public void setOperationSources(Map<String, List<OperationSource>> operationSources) {
		this.operationSources = operationSources;
	}

	public Map<String, List<OperationSource>> getOperationSources() {
		return operationSources;
	}

	protected void cache(OperationProfileNode node) {
		if (nodeCache == null) {
			nodeCache = new HashMap<>();
		}

		node.all().forEach(n -> nodeCache.put(n.getKey(), n));
	}

	/**
	 * Attempt to retrieve the specific {@link OperationProfileNode} that matches the
	 * provided {@link OperationMetadata operationMetadata} which is a child of the
	 * provided {@link OperationMetadata requesterMetadata}. This will fall back to
	 * returning any {@link OperationProfileNode} that matches the provided
	 * {@link OperationMetadata operationMetadata} if the {@link OperationProfileNode}
	 * matching {@link OperationMetadata requesterMetadata} does not have a child
	 * that matches the provided {@link OperationMetadata operationMetadata}.
	 */
	public OperationProfileNode getProfileNode(OperationMetadata requesterMetadata, OperationMetadata operationMetadata) {
		if (requesterMetadata == null)
			return getProfileNode(operationMetadata);

		return getProfileNode(requesterMetadata).getProfileNode(metadataKey(operationMetadata))
				.orElseGet(() -> {
					if (metadataWarnings) {
						warn("Could not find " + operationMetadata.describe() +
								" under " + requesterMetadata.describe());
					}

					return getProfileNode(operationMetadata);
				});
	}

	public OperationProfileNode getProfileNode(OperationMetadata metadata) {
		return metadata == null ? this : getProfileNode(metadata, true);
	}

	public OperationProfileNode getProfileNode(OperationMetadata metadata, boolean top) {
		if (metadata == null) return null;

		String key = metadataKey(metadata);
		Optional<OperationProfileNode> node = getProfileNode(key);

		if (top) {
			if (nodeCache == null) {
				// If the cache doesn't exist already, initialize
				// it with all the existing nodes
				cache(this);
			}

			if (node.isEmpty()) {
				node = Optional.of(OperationProfileNode.forMetadata(metadata, this::recordMetadata, getIdentifier()));
				recordMetadata(metadata);
				addChild(node.get());
			}
		}

		return node.orElse(null);
	}

	public Optional<OperationProfileNode> getProfileNode(String key) {
		return getProfileNode(key, children != null);
	}

	public Optional<OperationProfileNode> getProfileNode(String key, boolean traverse) {
		if (Objects.equals(key, getKey())) {
			return Optional.of(this);
		} else if (nodeCache != null) {
			return Optional.ofNullable(nodeCache.get(key));
		} else if (traverse) {
			return children(false)
					.map(v -> v.getProfileNode(key, false))
					.filter(Optional::isPresent).map(Optional::get)
					.findFirst();
		}

		return Optional.empty();
	}

	protected void recordMetadata(OperationMetadata metadata) {
		if (metadataCache == null) metadataCache = new HashMap<>();

		String key = metadataKey(metadata);

		if (metadataWarnings && metadataCache.containsKey(key)) {
			warn("Duplicate metadata key " + key);
		}

		metadataCache.put(key, metadataDetail.apply(metadata));
	}

	@Override
	public void recordDuration(OperationMetadata requesterMetadata, OperationMetadata operationMetadata, long nanos) {
		if (Objects.equals(getKey(), metadataKey(operationMetadata))) {
			if (measuredTime == null) measuredTime = new TimingMetric();
			measuredTime.addEntry(metadataKey(operationMetadata), nanos);
			return;
		}

		getProfileNode(operationMetadata).recordDuration(requesterMetadata, operationMetadata, nanos);
	}

	public <A> void recordCompilation(OperationMetadata metadata,
									  List<ArrayVariable<? extends A>> arguments,
									  String code, long nanos) {
		if (operationSources == null) {
			operationSources = new HashMap<>();
		}

		List<String> argKeys = null;
		List<String> argNames = null;

		if (arguments != null) {
			List<OperationMetadata> argMeta = arguments.stream()
					.map(ArrayVariable::getProducer)
					.map(p -> p instanceof OperationInfo ?
							((OperationInfo) p).getMetadata() : null)
					.collect(Collectors.toList());
			if (metadataWarnings && argMeta.stream().anyMatch(Objects::isNull)) {
				warn("Some arguments have no metadata");
			}

			argKeys = argMeta.stream().map(OperationProfile::metadataKey)
					.map(k -> k == null ? "<unknown>" : k)
					.collect(Collectors.toList());
			argNames = argMeta.stream()
					.map(m -> m == null ? "null" : m.getDisplayName())
					.collect(Collectors.toList());
		}

		String key = metadataKey(metadata);
		OperationSource src = new OperationSource(code, argKeys, argNames);

		operationSources.putIfAbsent(key, new ArrayList<>());
		List<OperationSource> sources = operationSources.get(key);

		if (sources.contains(src)) {
			warn("Recompilation of " + metadata.getDisplayName() + " (id = " + key + ")");
		} else if (!sources.isEmpty() && allowMultipleSources) {
			warn("Recompilation of " + metadata.getDisplayName() + " (id = " + key + ") with different source");
		} else {
			sources.add(new OperationSource(code, argKeys, argNames));

			if (sources.size() > 1) {
				throw new IllegalArgumentException("Multiple sources for " + key);
			}
		}

		OperationProfileNode node = getProfileNode(metadata);
		node.initMetric();
		node.getMetric().addEntry(getIdentifier().apply(metadata) + " compile", nanos);
	}

	@Override
	public OperationTimingListener getRuntimeListener() {
		return (requesterMetadata, operationMetadata, nanos) -> {
			OperationProfileNode node = getProfileNode(requesterMetadata, operationMetadata);
			node.initMetric();
			node.getMetric().addEntry(getIdentifier().apply(operationMetadata) + " run", nanos);
		};
	}

	@Override
	public ScopeTimingListener getScopeListener(boolean exclusive) {
		return (root, metadata, stage, nanos) -> {
			OperationProfileNode node = getProfileNode(root);

			if (exclusive) {
				node.initMetric();
				node.getMetric().addEntry(getIdentifier().apply(metadata) + " " + stage, nanos);
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

	@Override
	public String description(List<String> children) { return getName(); }

	public void save(String file) throws IOException {
		save(new File(file));
	}

	public void save(File file) throws IOException {
		try (XMLEncoder encoder = new XMLEncoder(new FileOutputStream(file))) {
			encoder.writeObject(this);
		}
	}

	public static OperationProfileNode forMetadata(OperationMetadata metadata) {
		return forMetadata(metadata, null, OperationProfile::defaultIdentifier);
	}

	public static OperationProfileNode forMetadata(OperationMetadata metadata,
												   Consumer<OperationMetadata> metadataProcessor,
												   Function<OperationMetadata, String> identifier) {
		if (metadata == null)
			return null;

		OperationProfileNode node = new OperationProfileNode(metadata, identifier);
		if (metadata.getChildren() != null) {
			metadata.getChildren().stream()
					.map(v -> {
						if (metadataProcessor != null)
							metadataProcessor.accept(v);

						return OperationProfileNode.forMetadata(v, metadataProcessor, identifier);
					})
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
