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

/**
 * A hierarchical profiling node that forms a tree of timing data for compiled
 * and executed operations.
 *
 * <p>{@code OperationProfileNode} extends {@link OperationProfile} to add tree
 * structure, compilation source tracking, and multi-level timing aggregation.
 * Each node in the tree corresponds to a compiled operation (identified by its
 * {@link OperationMetadata}) and can contain child nodes representing sub-operations.
 * This mirrors the hierarchical structure of computation graphs, allowing performance
 * bottlenecks to be traced from high-level operations down to individual kernels.</p>
 *
 * <h2>Timing Model</h2>
 * <p>Each node tracks three categories of timing data:</p>
 * <ul>
 *   <li><b>Self duration</b> ({@link #getSelfDuration()}) &mdash; time recorded directly
 *       on this node's {@link TimingMetric} via compile and run entries</li>
 *   <li><b>Child duration</b> ({@link #getChildDuration()}) &mdash; sum of
 *       {@link #getTotalDuration()} across all child nodes</li>
 *   <li><b>Measured duration</b> ({@link #getMeasuredDuration()}) &mdash; wall-clock time
 *       recorded by {@link #recordDuration} when the node's key matches the operation,
 *       stored in a separate {@link TimingMetric}</li>
 * </ul>
 * <p>The {@link #getTotalDuration()} method returns self + child duration, giving
 * the inclusive time for the subtree rooted at this node.</p>
 *
 * <h2>Node Lookup and Caching</h2>
 * <p>Nodes are identified by a key derived from {@link OperationMetadata#getId()} via
 * {@link OperationProfile#metadataKey(OperationMetadata)}. The first call to
 * {@link #getProfileNode(OperationMetadata, boolean)} with {@code top=true} initializes
 * a flat {@link HashMap} cache of all descendant nodes keyed by their metadata key,
 * enabling O(1) lookups thereafter. New children added after cache initialization are
 * automatically inserted into the cache.</p>
 *
 * <h2>Compilation Source Tracking</h2>
 * <p>{@link #recordCompilation} captures the generated source code and argument metadata
 * for each compiled operation. Recompilation of the same operation is logged as a warning;
 * multiple distinct sources for the same key throw {@link IllegalArgumentException} unless
 * {@link #allowMultipleSources} is enabled.</p>
 *
 * <h2>Listener Integration</h2>
 * <p>The node provides factory methods for the three profiling listener interfaces:</p>
 * <ul>
 *   <li>{@link #getRuntimeListener()} &mdash; records per-invocation run times under
 *       each operation's metric with a " run" suffix</li>
 *   <li>{@link #getScopeListener(boolean)} &mdash; records compilation stage times
 *       (e.g., "optimize", "generate") either as exclusive metric entries or as
 *       non-exclusive stage detail entries</li>
 *   <li>{@link #getCompilationListener()} &mdash; delegates to {@link #recordCompilation}</li>
 * </ul>
 *
 * <h2>Serialization</h2>
 * <p>The tree can be persisted to and loaded from JavaBeans XML format using
 * {@link #save(File)} and {@link #load(File)}. The XML files are consumed by the
 * profile analyzer MCP server and the {@code OperationProfileFX} / {@code OperationProfileUI}
 * visualization tools.</p>
 *
 * <h2>Typical Usage</h2>
 * <pre>{@code
 * // Create and attach a profile
 * OperationProfileNode profile = new OperationProfileNode("training_run");
 * Hardware.getLocalHardware().getCompileScope().setProfile(profile);
 *
 * // Run operations — compile and run times are recorded automatically
 * model.compile(profile).forward(input);
 *
 * // Detach and save
 * Hardware.getLocalHardware().getCompileScope().setProfile(null);
 * profile.save("results/training_run.xml");
 *
 * // Later, load and analyze
 * OperationProfileNode loaded = OperationProfileNode.load("results/training_run.xml");
 * loaded.all()
 *     .sorted(Comparator.comparingDouble(OperationProfileNode::getTotalDuration).reversed())
 *     .limit(10)
 *     .forEach(n -> System.out.println(n.getName() + ": " + n.getTotalDuration()));
 * }</pre>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AR_PROFILE_METADATA_WARNINGS} &mdash; when enabled, logs warnings for
 *       duplicate metadata keys and missing child lookups</li>
 *   <li>{@code AR_PROFILE_MULTIPLE_SOURCES} &mdash; when enabled (default), allows
 *       multiple distinct compiled sources per operation key instead of throwing</li>
 * </ul>
 *
 * @see OperationProfile
 * @see OperationMetadata
 * @see OperationSource
 * @see OperationTimingListener
 * @see ScopeTimingListener
 * @see CompilationTimingListener
 * @see Tree
 *
 * @author Michael Murray
 */
public class OperationProfileNode extends OperationProfile
		implements DescribableParent<OperationProfileNode>,
					Tree<OperationProfileNode>, Nameable {

	/**
	 * When {@code true}, logs warnings for duplicate metadata keys and failed
	 * child lookups. Controlled by the {@code AR_PROFILE_METADATA_WARNINGS}
	 * environment variable.
	 */
	public static boolean metadataWarnings = SystemUtils.isEnabled("AR_PROFILE_METADATA_WARNINGS").orElse(false);

	/**
	 * When {@code true} (default), permits multiple distinct compiled source
	 * entries per operation key with a warning instead of throwing
	 * {@link IllegalArgumentException}. Controlled by the
	 * {@code AR_PROFILE_MULTIPLE_SOURCES} environment variable.
	 */
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

	/** Creates a default profile node with no key and the name "default". */
	public OperationProfileNode() { this(null, "default"); }

	/**
	 * Creates a profile node with the given name and no key.
	 *
	 * @param name the display name for this node
	 */
	public OperationProfileNode(String name) { this(null, name); }

	/**
	 * Creates a profile node with the given key and name, using
	 * {@link OperationMetadata#getDisplayName()} as the identifier function.
	 *
	 * @param key  the unique key for this node (may be {@code null})
	 * @param name the display name for this node
	 */
	protected OperationProfileNode(String key, String name) {
		this(key, name, OperationMetadata::getDisplayName);
	}

	/**
	 * Creates a profile node from the given metadata, using
	 * {@link OperationMetadata#getDisplayName()} as the identifier function.
	 *
	 * @param metadata the operation metadata to derive key and name from
	 */
	public OperationProfileNode(OperationMetadata metadata) {
		this(metadata, OperationMetadata::getDisplayName);
	}

	/**
	 * Creates a profile node from the given metadata with a custom identifier function.
	 *
	 * @param metadata   the operation metadata to derive key and name from
	 * @param identifier a function that extracts a display identifier from metadata
	 */
	public OperationProfileNode(OperationMetadata metadata,
								Function<OperationMetadata, String> identifier) {
		super(metadata, identifier);
	}

	/**
	 * Creates a profile node with the given key, name, and identifier function.
	 *
	 * @param key        the unique key for this node (may be {@code null})
	 * @param name       the display name for this node
	 * @param identifier a function that extracts a display identifier from metadata
	 */
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

	/**
	 * Adds a child node for the given metadata, creating one if it does not
	 * already exist. Returns the (possibly new) child node.
	 *
	 * @param metadata the metadata identifying the child operation
	 * @return the child node corresponding to the metadata
	 */
	public OperationProfileNode addChild(OperationMetadata metadata) {
		return getProfileNode(metadata);
	}

	/**
	 * Adds all nodes in the list as children by delegating to
	 * {@link #addChild(OperationProfileNode)} for each.
	 *
	 * @param children the list of child nodes to add
	 */
	protected void addAllChildren(List<OperationProfileNode> children) {
		children.forEach(this::addChild);
	}

	/**
	 * Adds a pre-existing node as a direct child. If the node cache is active,
	 * the new child and all of its descendants are inserted into the cache.
	 *
	 * @param node the child node to add
	 */
	protected void addChild(OperationProfileNode node) {
		if (children == null) children = new ArrayList<>();
		children.add(node);

		if (nodeCache != null) {
			// If caching is active for this node,
			// cache all the new children
			cache(node);
		}
	}

	/**
	 * Returns the metadata cache mapping operation keys to their human-readable
	 * detail strings. Used during serialization and by summary formatters.
	 *
	 * @return the metadata cache, or {@code null} if no metadata has been recorded
	 */
	public Map<String, String> getMetadata() {
		return metadataCache;
	}

	/**
	 * Sets the metadata cache. Primarily used during deserialization.
	 *
	 * @param metadata the metadata cache to set
	 */
	public void setMetadata(Map<String, String> metadata) {
		this.metadataCache = metadata;
	}

	/**
	 * Returns the measured wall-clock timing metric for this node. This metric
	 * is populated by {@link #recordDuration} when the operation key matches
	 * this node's key directly.
	 *
	 * @return the measured time metric, or {@code null} if none recorded
	 */
	public TimingMetric getMeasuredTime() { return measuredTime; }

	/**
	 * Sets the measured time metric. Primarily used during deserialization.
	 *
	 * @param measuredTime the timing metric to set
	 */
	public void setMeasuredTime(TimingMetric measuredTime) { this.measuredTime = measuredTime; }

	/** Lazily initializes the stage detail timing metric if not already present. */
	protected void initStageDetailTime() {
		if (stageDetailTime == null) stageDetailTime = new TimingMetric();
	}

	/**
	 * Returns the stage detail timing metric, which tracks non-exclusive stage
	 * durations recorded via {@link #getScopeListener(boolean)} with
	 * {@code exclusive=false}.
	 *
	 * @return the stage detail time metric, or {@code null} if none recorded
	 */
	public TimingMetric getStageDetailTime() { return stageDetailTime; }

	/**
	 * Sets the stage detail timing metric. Primarily used during deserialization.
	 *
	 * @param stageDetailTime the timing metric to set
	 */
	public void setStageDetailTime(TimingMetric stageDetailTime) { this.stageDetailTime = stageDetailTime; }

	/**
	 * Returns the human-readable detail string for the given metadata key.
	 * Falls back to returning the key itself if no cached detail is available.
	 *
	 * @param key the metadata key to look up
	 * @return the detail string, or the key if not found, or empty string if key is {@code null}
	 */
	public String getMetadataDetail(String key) {
		if (key == null) return "";

		if (metadataCache != null && metadataCache.containsKey(key)) {
			return metadataCache.get(key);
		}

		return key;
	}

	/**
	 * Returns the total measured wall-clock duration in seconds, or 0 if no
	 * measured time has been recorded.
	 *
	 * @return the measured duration in seconds
	 */
	public double getMeasuredDuration() { return measuredTime == null ? 0 : measuredTime.getTotal(); }

	/**
	 * Returns the duration directly attributed to this node (excluding children).
	 * This is the total from this node's own {@link TimingMetric}.
	 *
	 * @return the self duration in seconds
	 */
	public double getSelfDuration() { return super.getTotalDuration(); }

	/**
	 * Returns the sum of {@link #getTotalDuration()} across all direct children.
	 *
	 * @return the aggregate child duration in seconds
	 */
	public double getChildDuration() {
		return getChildren().stream()
				.mapToDouble(OperationProfileNode::getTotalDuration)
				.sum();
	}

	/**
	 * Returns the inclusive duration for the subtree rooted at this node,
	 * computed as {@link #getSelfDuration()} + {@link #getChildDuration()}.
	 *
	 * @return the total duration in seconds
	 */
	@Override
	public double getTotalDuration() {
		return getSelfDuration() + getChildDuration();
	}

	/**
	 * Returns a merged {@link TimingMetric} representing the aggregate timing
	 * for this node and its descendants. Prefers the measured time if available,
	 * then the node's own metric, then recursively merges child metrics.
	 *
	 * @return the merged metric, or {@code null} if no timing data exists
	 */
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

	/**
	 * Sets the map of compiled operation sources keyed by metadata key.
	 * Primarily used during deserialization.
	 *
	 * @param operationSources the operation sources map to set
	 */
	public void setOperationSources(Map<String, List<OperationSource>> operationSources) {
		this.operationSources = operationSources;
	}

	/**
	 * Returns the map of compiled operation sources. Each entry maps a metadata
	 * key to the list of {@link OperationSource} instances that were compiled
	 * for that operation.
	 *
	 * @return the operation sources map, or {@code null} if no compilations recorded
	 */
	public Map<String, List<OperationSource>> getOperationSources() {
		return operationSources;
	}

	/**
	 * Inserts the given node and all its descendants into the flat node cache
	 * for O(1) key-based lookup.
	 *
	 * @param node the node to cache (including its entire subtree)
	 */
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

	/**
	 * Returns the child node for the given metadata, creating it if necessary.
	 * If metadata is {@code null}, returns this node.
	 *
	 * @param metadata the metadata identifying the desired child node
	 * @return the matching child node, or this node if metadata is {@code null}
	 */
	public OperationProfileNode getProfileNode(OperationMetadata metadata) {
		return metadata == null ? this : getProfileNode(metadata, true);
	}

	/**
	 * Looks up a child node by metadata. When {@code top} is {@code true},
	 * this method initializes the node cache on first call and auto-creates
	 * a new child node if no match is found. When {@code top} is {@code false},
	 * only existing nodes are searched.
	 *
	 * @param metadata the metadata identifying the desired node
	 * @param top      whether this is a top-level lookup (enables cache init and node creation)
	 * @return the matching node, or {@code null} if not found and {@code top} is {@code false}
	 */
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

	/**
	 * Looks up a node by its string key, traversing children if they exist.
	 *
	 * @param key the metadata key to search for
	 * @return an {@link Optional} containing the matching node, or empty if not found
	 */
	public Optional<OperationProfileNode> getProfileNode(String key) {
		return getProfileNode(key, children != null);
	}

	/**
	 * Looks up a node by its string key. If the key matches this node, returns
	 * this node. Otherwise checks the node cache (if initialized), or traverses
	 * children directly if {@code traverse} is {@code true}.
	 *
	 * @param key      the metadata key to search for
	 * @param traverse whether to search child nodes if no cache hit
	 * @return an {@link Optional} containing the matching node, or empty if not found
	 */
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

	/**
	 * Records a metadata entry in the cache, mapping the metadata's key to a
	 * human-readable detail string derived from its short description, shape,
	 * and context name. Logs a warning if the key already exists and
	 * {@link #metadataWarnings} is enabled.
	 *
	 * @param metadata the metadata to record
	 */
	protected void recordMetadata(OperationMetadata metadata) {
		if (metadataCache == null) metadataCache = new HashMap<>();

		String key = metadataKey(metadata);

		if (metadataWarnings && metadataCache.containsKey(key)) {
			warn("Duplicate metadata key " + key);
		}

		metadataCache.put(key, metadataDetail.apply(metadata));
	}

	/**
	 * Records an operation duration. If the operation key matches this node's key,
	 * the duration is added to the {@link #getMeasuredTime() measuredTime} metric.
	 * Otherwise, the call is delegated to the child node matching the operation metadata.
	 *
	 * @param requesterMetadata  the metadata of the operation that requested this timing
	 * @param operationMetadata  the metadata of the operation being timed
	 * @param nanos              the duration in nanoseconds
	 */
	@Override
	public void recordDuration(OperationMetadata requesterMetadata, OperationMetadata operationMetadata, long nanos) {
		if (Objects.equals(getKey(), metadataKey(operationMetadata))) {
			if (measuredTime == null) measuredTime = new TimingMetric();
			measuredTime.addEntry(metadataKey(operationMetadata), nanos);
			return;
		}

		getProfileNode(operationMetadata).recordDuration(requesterMetadata, operationMetadata, nanos);
	}

	/**
	 * Records a compilation event, capturing the generated source code, argument
	 * metadata, and compilation duration. The source is stored in
	 * {@link #getOperationSources()} and the timing is added to the operation's
	 * metric with a " compile" suffix.
	 *
	 * <p>If the same operation key is compiled again with identical source, a
	 * recompilation warning is logged. If compiled with different source and
	 * {@link #allowMultipleSources} is {@code false}, an
	 * {@link IllegalArgumentException} is thrown.</p>
	 *
	 * @param <A>       the argument array element type
	 * @param metadata  the metadata of the compiled operation
	 * @param arguments the argument variables for the compiled operation
	 * @param code      the generated source code
	 * @param nanos     the compilation time in nanoseconds
	 */
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

	/**
	 * Returns a runtime timing listener that records each operation invocation's
	 * duration under the appropriate child node's metric with a " run" suffix.
	 *
	 * @return a listener for recording operation run times
	 */
	@Override
	public OperationTimingListener getRuntimeListener() {
		return (requesterMetadata, operationMetadata, nanos) -> {
			OperationProfileNode node = getProfileNode(requesterMetadata, operationMetadata);
			node.initMetric();
			node.getMetric().addEntry(getIdentifier().apply(operationMetadata) + " run", nanos);
		};
	}

	/**
	 * Returns a scope timing listener for recording compilation stage durations.
	 *
	 * <p>When {@code exclusive} is {@code true}, stage times are recorded as entries
	 * in the node's primary {@link TimingMetric} with the stage name as suffix
	 * (e.g., "MyOp optimize"). When {@code false}, they are recorded in the separate
	 * {@link #getStageDetailTime()} metric, allowing non-exclusive accumulation of
	 * overlapping stage timings.</p>
	 *
	 * @param exclusive whether stage times should be exclusive metric entries
	 * @return a listener for recording compilation stage times
	 */
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

	/**
	 * Returns a compilation timing listener that delegates to
	 * {@link #recordCompilation}.
	 *
	 * @return a listener for recording compilation events
	 */
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

	/**
	 * Saves this profile tree to the specified file path in JavaBeans XML format.
	 *
	 * @param file the file path to write to
	 * @throws IOException if an I/O error occurs
	 */
	public void save(String file) throws IOException {
		save(new File(file));
	}

	/**
	 * Saves this profile tree to the specified file in JavaBeans XML format.
	 * The resulting XML can be loaded with {@link #load(File)} and analyzed
	 * by the profile analyzer tools.
	 *
	 * @param file the file to write to
	 * @throws IOException if an I/O error occurs
	 */
	public void save(File file) throws IOException {
		try (XMLEncoder encoder = new XMLEncoder(new FileOutputStream(file))) {
			encoder.writeObject(this);
		}
	}

	/**
	 * Creates a profile node from the given metadata using the default identifier.
	 * Recursively creates child nodes for any children in the metadata hierarchy.
	 *
	 * @param metadata the metadata to create the node from
	 * @return the new profile node, or {@code null} if metadata is {@code null}
	 */
	public static OperationProfileNode forMetadata(OperationMetadata metadata) {
		return forMetadata(metadata, null, OperationProfile::defaultIdentifier);
	}

	/**
	 * Creates a profile node tree from the given metadata hierarchy. Each
	 * {@link OperationMetadata} child is recursively converted to a child
	 * {@code OperationProfileNode}, and the optional metadata processor is
	 * called for each child before conversion.
	 *
	 * @param metadata          the root metadata to create the node from
	 * @param metadataProcessor an optional consumer called for each child metadata
	 *                          before node creation (may be {@code null})
	 * @param identifier        the function used to derive display identifiers
	 * @return the new profile node tree, or {@code null} if metadata is {@code null}
	 */
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

	/**
	 * Loads a profile tree from the specified file path in JavaBeans XML format.
	 *
	 * @param file the file path to read from
	 * @return the deserialized profile node tree
	 * @throws IOException if an I/O error occurs
	 */
	public static OperationProfileNode load(String file) throws IOException {
		return load(new File(file));
	}

	/**
	 * Loads a profile tree from the specified file in JavaBeans XML format.
	 *
	 * @param file the file to read from
	 * @return the deserialized profile node tree
	 * @throws IOException if an I/O error occurs
	 */
	public static OperationProfileNode load(File file) throws IOException {
		try (XMLDecoder in = new XMLDecoder(new FileInputStream(file))) {
			return (OperationProfileNode) in.readObject();
		}
	}
}
