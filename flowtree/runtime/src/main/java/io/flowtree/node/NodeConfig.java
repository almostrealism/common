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

	/** The infix separating a node index from its per-node label key. */
	private static final String LABELS_INFIX = ".labels.";

	/** The Node's id, which is its index in the configuration and the group roster. */
	private final int index;

	/** The kind of Node this entry describes; never {@code null}. */
	private final NodeKind kind;

	/** The capability labels to apply to this Node; never {@code null}. */
	private final Map<String, String> labels;

	/**
	 * Constructs a configuration entry for a single Node.
	 *
	 * @param index   The Node id / configuration index; must be non-negative.
	 * @param kind    The kind of Node; must not be {@code null}.
	 * @param labels  The capability labels to apply; copied defensively.
	 */
	NodeConfig(int index, NodeKind kind, Map<String, String> labels) {
		this.index = index;
		this.kind = kind;
		this.labels = new LinkedHashMap<>(labels);
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
	 * Applies this entry's labels to the given Node and, for a host-resident kind,
	 * the host's {@link AutomaticLabel} values for any label key not already
	 * configured. Configured labels are applied first so they take precedence over
	 * detected ones, exactly as {@link AutomaticLabel#applyTo(Node)} guarantees.
	 *
	 * <p>A container kind does not take the host's automatic labels: its platform is
	 * a property of its image, supplied through {@code nodes.<i>.labels.platform}.</p>
	 *
	 * @param node  The Node to label; must be the one this entry describes.
	 */
	void applyLabels(Node node) {
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
	 *          no {@code nodes.<i>.kind} entry is present (the legacy path).
	 * @throws IllegalArgumentException  If a kind value is unknown, an index is not a
	 *         non-negative integer, or the indices are not a contiguous range from
	 *         zero.
	 */
	public static List<NodeConfig> parse(Properties p) {
		Map<Integer, NodeKind> kinds = new TreeMap<>();
		Map<Integer, Map<String, String>> labelsByIndex = new TreeMap<>();

		for (Object keyObj : p.keySet()) {
			String key = (String) keyObj;
			if (!key.startsWith(NODE_PREFIX)) continue;

			if (key.endsWith(KIND_SUFFIX)) {
				int index = parseIndex(key.substring(NODE_PREFIX.length(),
						key.length() - KIND_SUFFIX.length()), key);
				kinds.put(index, NodeKind.forKey(p.getProperty(key)));
			} else {
				int infix = key.indexOf(LABELS_INFIX, NODE_PREFIX.length());
				if (infix < 0) continue;

				int index = parseIndex(key.substring(NODE_PREFIX.length(), infix), key);
				String labelKey = key.substring(infix + LABELS_INFIX.length());
				labelsByIndex.computeIfAbsent(index, i -> new LinkedHashMap<>())
						.put(labelKey, p.getProperty(key));
			}
		}

		if (kinds.isEmpty()) return new ArrayList<>();

		List<NodeConfig> result = new ArrayList<>(kinds.size());
		int expected = 0;
		for (Map.Entry<Integer, NodeKind> entry : kinds.entrySet()) {
			if (entry.getKey() != expected) {
				throw new IllegalArgumentException("Typed node indices must be a "
						+ "contiguous range beginning at 0; found a gap before index "
						+ entry.getKey());
			}
			expected++;

			// TODO(review): labels for an index with no matching .kind entry are silently dropped.
			Map<String, String> labels = labelsByIndex.getOrDefault(entry.getKey(),
					new LinkedHashMap<>());
			result.add(new NodeConfig(entry.getKey(), entry.getValue(), labels));
		}

		return result;
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
