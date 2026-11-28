/*
 * Copyright 2026 Michael Murray
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

package io.flowtree.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The typed configuration of one {@link Node} within a {@link NodeGroup}: its
 * {@link NodeKind kind} and the capability labels it advertises. This replaces the
 * single {@code nodes.initial} count, which could only describe a homogeneous group
 * of identically-labelled native Nodes, with an indexed list in which each Node
 * carries its own kind and its own labels.
 *
 * <p>Entries are read from a {@link Properties} description with the shape:</p>
 * <pre>
 * nodes.0.kind=native
 * nodes.0.labels.platform=macos
 * nodes.1.kind=native
 * nodes.1.labels.platform=linux
 * </pre>
 *
 * <p>The index is the Node's id, and the indices must be a contiguous range
 * beginning at zero so that a Node's id equals its position in the group's roster,
 * the invariant {@link NodeGroup#getNode(int)} relies on. When no
 * {@code nodes.<i>.kind} entry is present, {@link #parse(Properties)} returns an
 * empty list and the group falls back to the legacy {@code nodes.initial} path,
 * leaving existing deployments behaving identically.</p>
 *
 * <p>Labels configured here are applied directly to the one Node the entry
 * describes, never broadcast to the whole group. This is what makes a mixed-kind
 * Server possible: a host Node labelled {@code platform=macos} and a container Node
 * labelled {@code platform=linux} can coexist, where the group-wide broadcast of
 * {@link NodeGroup#setLabel(String, String)} made that impossible.</p>
 *
 * @author  Michael Murray
 * @see NodeKind
 * @see NodeGroup
 */
public class NodeConfig {

	/** The {@code nodes.} prefix every typed entry key begins with. */
	private static final String NODE_PREFIX = "nodes.";

	/** The {@code kind} suffix that marks a typed entry's kind value. */
	private static final String KIND_SUFFIX = ".kind";

	/** The {@code image} suffix that names a container Node's worker image. */
	private static final String IMAGE_SUFFIX = ".image";

	/** The {@code cpus} suffix that bounds a container Node's CPU allotment. */
	private static final String CPUS_SUFFIX = ".cpus";

	/** The {@code memory} suffix that bounds a container Node's memory allotment. */
	private static final String MEMORY_SUFFIX = ".memory";

	/** The infix separating a node index from its per-node label key. */
	private static final String LABELS_INFIX = ".labels.";

	/**
	 * The prefix of the legacy group-level label namespace. A key such as
	 * {@code nodes.labels.kind} belongs to this namespace, not to a typed
	 * {@code nodes.<index>.kind} entry, so these keys are left to
	 * {@link NodeGroupNodeConfig#applyNodeLabels(NodeGroup, Properties)}.
	 */
	private static final String GROUP_LABEL_PREFIX = NODE_PREFIX + "labels.";

	/** The Node's id, which is its index in the configuration and the group roster. */
	private final int index;

	/** The kind of Node this entry describes; never {@code null}. */
	private final NodeKind kind;

	/** The capability labels to apply to this Node; never {@code null}. */
	private final Map<String, String> labels;

	/**
	 * The container image a container Node runs its worker from, or {@code null}
	 * when this entry describes a host-resident kind that needs no image.
	 */
	private final String image;

	/**
	 * The CPU allotment passed to the container runtime (the value of a
	 * {@code docker run --cpus} flag), or {@code null} to leave it unbounded.
	 */
	private final String cpus;

	/**
	 * The memory allotment passed to the container runtime (the value of a
	 * {@code docker run --memory} flag), or {@code null} to leave it unbounded.
	 */
	private final String memory;

	/**
	 * Constructs a configuration entry for a single Node.
	 *
	 * @param index   The Node id / configuration index; must be non-negative.
	 * @param kind    The kind of Node; must not be {@code null}.
	 * @param labels  The capability labels to apply; copied defensively.
	 * @param image   The container image for a container kind, or {@code null}.
	 * @param cpus    The CPU allotment for a container kind, or {@code null}.
	 * @param memory  The memory allotment for a container kind, or {@code null}.
	 */
	NodeConfig(int index, NodeKind kind, Map<String, String> labels,
			String image, String cpus, String memory) {
		this.index = index;
		this.kind = kind;
		this.labels = new LinkedHashMap<>(labels);
		this.image = image;
		this.cpus = cpus;
		this.memory = memory;
	}

	/**
	 * Returns this Node's id, which is its configuration index.
	 *
	 * @return  The node index.
	 */
	public int index() {
		return index;
	}

	/**
	 * Returns the kind of Node this entry describes.
	 *
	 * @return  The node kind; never {@code null}.
	 */
	public NodeKind kind() {
		return kind;
	}

	/**
	 * Returns the container image a container Node runs its worker from.
	 *
	 * @return  the configured image, or {@code null} when none was configured
	 *          (always {@code null} for a host-resident kind).
	 */
	public String image() {
		return image;
	}

	/**
	 * Returns the CPU allotment to pass to the container runtime.
	 *
	 * @return  the configured CPU limit, or {@code null} to leave it unbounded.
	 */
	public String cpus() {
		return cpus;
	}

	/**
	 * Returns the memory allotment to pass to the container runtime.
	 *
	 * @return  the configured memory limit, or {@code null} to leave it unbounded.
	 */
	public String memory() {
		return memory;
	}

	/**
	 * Constructs the {@link Node} this entry describes as a child of the given group.
	 *
	 * @param group     The owning group.
	 * @param maxJobs   Maximum queued jobs before the Node sheds to peers.
	 * @param maxPeers  Maximum peer connections.
	 * @return  The constructed Node; never {@code null}.
	 */
	Node createNode(NodeGroup group, int maxJobs, int maxPeers) {
		return kind.create(group, maxJobs, maxPeers, this);
	}

	/**
	 * Applies labels to the given Node in order of increasing specificity, so that a
	 * more specific source overrides a less specific one:
	 * <ol>
	 *   <li>the group-level {@code hostLabels} (from {@code nodes.labels.*} and
	 *       {@code FLOWTREE_NODE_LABELS}), but only for a host-resident kind, since
	 *       they describe the machine the group runs on;</li>
	 *   <li>this entry's own {@code nodes.<i>.labels.*} labels, which override any
	 *       group-level value for the same key;</li>
	 *   <li>for a host-resident kind, the host's detected {@link AutomaticLabel}
	 *       values, assigned only for a key no earlier source already set, exactly as
	 *       {@link AutomaticLabel#applyTo(Node)} guarantees.</li>
	 * </ol>
	 *
	 * <p>A container kind takes neither the group-level host labels nor the automatic
	 * labels: its platform is a property of its image, supplied through
	 * {@code nodes.<i>.labels.platform}. Applying the host's machine labels (such as
	 * {@code hostname} or {@code node-id}) to a container Node would misroute jobs
	 * meant for the host onto the container.</p>
	 *
	 * @param node        The Node to label; must be the one this entry describes.
	 * @param hostLabels  The group-level and environment-provided labels that
	 *                    describe the host; applied only to a host-resident kind.
	 */
	void applyLabels(Node node, Map<String, String> hostLabels) {
		if (kind.describesHost()) {
			hostLabels.forEach(node::setLabel);
		}

		labels.forEach(node::setLabel);

		if (kind.describesHost()) {
			for (AutomaticLabel label : AutomaticLabel.values()) {
				label.applyTo(node);
			}
		}
	}

	/**
	 * Reads the typed Node entries from the given properties, ordered by index.
	 *
	 * @param p  The configuration properties.
	 * @return  The typed entries ordered by ascending index, or an empty list when
	 *          no {@code nodes.<i>.kind} entry is present (the legacy path). Keys in
	 *          the legacy {@code nodes.labels.*} namespace are never read as typed
	 *          entries, so a legacy configuration keeps falling back unchanged.
	 * @throws IllegalArgumentException  If a kind value is unknown, an index is not a
	 *         non-negative integer, the indices are not a contiguous range from zero,
	 *         or a {@code nodes.<i>.labels.*} / {@code nodes.<i>.image|cpus|memory}
	 *         entry has no matching {@code nodes.<i>.kind} (a mislabelled or
	 *         misconfigured Node is reported rather than silently dropped).
	 */
	public static List<NodeConfig> parse(Properties p) {
		Map<Integer, NodeKind> kinds = new TreeMap<>();
		Map<Integer, Map<String, String>> labelsByIndex = new TreeMap<>();
		Map<Integer, String> imageByIndex = new TreeMap<>();
		Map<Integer, String> cpusByIndex = new TreeMap<>();
		Map<Integer, String> memoryByIndex = new TreeMap<>();

		for (Object keyObj : p.keySet()) {
			String key = (String) keyObj;
			if (!key.startsWith(NODE_PREFIX)) continue;
			if (key.startsWith(GROUP_LABEL_PREFIX)) continue;

			// A per-node label may use any key, including "kind" or "image", so the
			// .labels. infix is matched before every scalar suffix: nodes.<i>.labels.kind
			// is the label "kind" on node <i>, not the kind of node <i>.labels.
			int infix = key.indexOf(LABELS_INFIX, NODE_PREFIX.length());
			if (infix >= 0) {
				int index = parseIndex(key.substring(NODE_PREFIX.length(), infix), key);
				String labelKey = key.substring(infix + LABELS_INFIX.length());
				labelsByIndex.computeIfAbsent(index, i -> new LinkedHashMap<>())
						.put(labelKey, p.getProperty(key));
			} else if (key.endsWith(KIND_SUFFIX)) {
				int index = parseIndex(key.substring(NODE_PREFIX.length(),
						key.length() - KIND_SUFFIX.length()), key);
				kinds.put(index, NodeKind.forKey(p.getProperty(key)));
			} else if (key.endsWith(IMAGE_SUFFIX)) {
				imageByIndex.put(parseIndex(key.substring(NODE_PREFIX.length(),
						key.length() - IMAGE_SUFFIX.length()), key), p.getProperty(key));
			} else if (key.endsWith(CPUS_SUFFIX)) {
				cpusByIndex.put(parseIndex(key.substring(NODE_PREFIX.length(),
						key.length() - CPUS_SUFFIX.length()), key), p.getProperty(key));
			} else if (key.endsWith(MEMORY_SUFFIX)) {
				memoryByIndex.put(parseIndex(key.substring(NODE_PREFIX.length(),
						key.length() - MEMORY_SUFFIX.length()), key), p.getProperty(key));
			}
		}

		if (kinds.isEmpty()) {
			if (!labelsByIndex.isEmpty() || !imageByIndex.isEmpty()
					|| !cpusByIndex.isEmpty() || !memoryByIndex.isEmpty()) {
				throw new IllegalArgumentException("Typed node attributes were configured "
						+ "but no nodes.<i>.kind entry is present; add the matching kind or "
						+ "move the labels to the legacy nodes.labels.* namespace");
			}

			return new ArrayList<>();
		}

		requireMatchingKind(kinds, labelsByIndex.keySet());
		requireMatchingKind(kinds, imageByIndex.keySet());
		requireMatchingKind(kinds, cpusByIndex.keySet());
		requireMatchingKind(kinds, memoryByIndex.keySet());

		List<NodeConfig> result = new ArrayList<>(kinds.size());
		int expected = 0;
		for (Map.Entry<Integer, NodeKind> entry : kinds.entrySet()) {
			if (entry.getKey() != expected) {
				throw new IllegalArgumentException("Typed node indices must be a "
						+ "contiguous range beginning at 0; found a gap before index "
						+ entry.getKey());
			}
			expected++;

			Map<String, String> labels = labelsByIndex.getOrDefault(entry.getKey(),
					new LinkedHashMap<>());
			result.add(new NodeConfig(entry.getKey(), entry.getValue(), labels,
					imageByIndex.get(entry.getKey()), cpusByIndex.get(entry.getKey()),
					memoryByIndex.get(entry.getKey())));
		}

		return result;
	}

	/**
	 * Verifies that every index carrying a typed attribute (a label or a resource
	 * override) also declares a {@code nodes.<i>.kind}, so a misconfigured Node is
	 * reported rather than silently dropped.
	 *
	 * @param kinds            the parsed kinds, keyed by index.
	 * @param attributeIndices the indices that carry an attribute.
	 * @throws IllegalArgumentException  If an attribute index has no matching kind.
	 */
	private static void requireMatchingKind(Map<Integer, NodeKind> kinds,
			Iterable<Integer> attributeIndices) {
		for (Integer index : attributeIndices) {
			if (!kinds.containsKey(index)) {
				throw new IllegalArgumentException("Typed node attributes for index " + index
						+ " have no matching nodes." + index + ".kind entry");
			}
		}
	}

	/**
	 * Parses a node index from the portion of a configuration key between the
	 * {@code nodes.} prefix and its suffix.
	 *
	 * @param text     The index portion of the key.
	 * @param fullKey  The full key, for the error message.
	 * @return  The parsed, non-negative index.
	 * @throws IllegalArgumentException  If the text is not a non-negative integer.
	 */
	private static int parseIndex(String text, String fullKey) {
		int index;
		try {
			index = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Malformed typed node key: " + fullKey, e);
		}

		if (index < 0) {
			throw new IllegalArgumentException("Negative typed node index: " + fullKey);
		}

		return index;
	}
}
