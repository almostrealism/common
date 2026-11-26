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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Exercises {@link NodeConfig#parse(Properties)}, the parser that turns the typed
 * {@code nodes.<i>.kind}/{@code nodes.<i>.labels.*} description into an ordered list
 * of per-Node configuration entries. These tests assert the back-compat fallback
 * (an empty list when no typed entry is present), the index/kind/label extraction,
 * and the validation that rejects malformed indices, unknown kinds, and gaps.
 *
 * @author Michael Murray
 */
public class NodeConfigTest extends TestSuiteBase {

	/** Without any {@code nodes.<i>.kind} entry, the parser yields the legacy empty list. */
	@Test(timeout = 10000)
	public void noTypedEntriesFallsBackToLegacy() {
		Properties p = new Properties();
		p.setProperty("nodes.initial", "3");
		p.setProperty("nodes.labels.gpu", "cuda");

		Assert.assertTrue("a scalar count must not be read as a typed entry",
				NodeConfig.parse(p).isEmpty());
	}

	/** Each {@code nodes.<i>.kind} entry becomes a config with that index and kind. */
	@Test(timeout = 10000)
	public void readsIndexAndKind() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.1.kind", "native");

		List<NodeConfig> configs = NodeConfig.parse(p);

		Assert.assertEquals(2, configs.size());
		Assert.assertEquals(0, configs.get(0).index());
		Assert.assertEquals(1, configs.get(1).index());
		Assert.assertEquals(NodeKind.NATIVE, configs.get(0).kind());
	}

	/** Per-node labels are applied to the one Node the entry describes. */
	@Test(timeout = 10000)
	public void perNodeLabelsAreApplied() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.platform", "macos");
		p.setProperty("nodes.0.labels.gpu", "metal");

		NodeConfig config = NodeConfig.parse(p).get(0);
		Node node = new Node(null, 0, 4, 4);
		config.applyLabels(node, Collections.emptyMap());

		Assert.assertEquals("macos", node.getLabels().get("platform"));
		Assert.assertEquals("metal", node.getLabels().get("gpu"));
	}

	/**
	 * A host-resident Node takes the group-level host labels, and a per-node label
	 * overrides a host label of the same key.
	 */
	@Test(timeout = 10000)
	public void hostLabelsApplyToHostNodeAndPerNodeWins() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.gpu", "metal");

		Map<String, String> hostLabels = new HashMap<>();
		hostLabels.put("node-id", "host-5");
		hostLabels.put("gpu", "cuda");

		NodeConfig config = NodeConfig.parse(p).get(0);
		Node node = new Node(null, 0, 4, 4);
		config.applyLabels(node, hostLabels);

		Assert.assertEquals("a host label reaches a native node", "host-5",
				node.getLabels().get("node-id"));
		Assert.assertEquals("the per-node label overrides the host label", "metal",
				node.getLabels().get("gpu"));
	}

	/**
	 * A typed {@code nodes.<i>.labels.*} entry with no matching {@code nodes.<i>.kind}
	 * is reported rather than silently dropped.
	 */
	@Test(timeout = 10000)
	public void labelsWithoutMatchingKindRejected() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.1.labels.platform", "linux");

		try {
			NodeConfig.parse(p);
			Assert.fail("a typed label for an index with no kind must be rejected");
		} catch (IllegalArgumentException expected) {
			// a mislabelled node must surface rather than lose its label
		}
	}

	/**
	 * Typed labels present with no {@code nodes.<i>.kind} entry at all are reported
	 * rather than falling through to the legacy path and discarding them.
	 */
	@Test(timeout = 10000)
	public void typedLabelsWithNoKindEntriesRejected() {
		Properties p = new Properties();
		p.setProperty("nodes.0.labels.platform", "linux");

		try {
			NodeConfig.parse(p);
			Assert.fail("typed labels with no kind entry must be rejected");
		} catch (IllegalArgumentException expected) {
			// the typed namespace is incomplete; do not silently drop the labels
		}
	}

	/**
	 * A per-node label may use any key, including {@code kind}:
	 * {@code nodes.<i>.labels.kind} is the label "kind" on that Node, not the Node's
	 * {@link NodeKind}, so it must not be misread as a typed kind entry.
	 */
	@Test(timeout = 10000)
	public void perNodeLabelNamedKindIsNotAKindEntry() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.kind", "gpu-box");

		NodeConfig config = NodeConfig.parse(p).get(0);
		Node node = new Node(null, 0, 4, 4);
		config.applyLabels(node, Collections.emptyMap());

		Assert.assertEquals("the typed list builds exactly its one kind entry",
				1, NodeConfig.parse(p).size());
		Assert.assertEquals(NodeKind.NATIVE, config.kind());
		Assert.assertEquals("a per-node label named kind is stored as a label", "gpu-box",
				node.getLabels().get("kind"));
	}

	/**
	 * A legacy {@code nodes.labels.*} key is not read as a typed entry, even when it
	 * ends in {@code .kind}: a valid legacy configuration still falls back unchanged.
	 */
	@Test(timeout = 10000)
	public void legacyGroupLabelNamespaceIsNotTyped() {
		Properties p = new Properties();
		p.setProperty("nodes.initial", "2");
		p.setProperty("nodes.labels.kind", "gpu-box");
		p.setProperty("nodes.labels.role", "worker");

		Assert.assertTrue("a nodes.labels.* key must not be read as a typed entry",
				NodeConfig.parse(p).isEmpty());
	}

	/** Entries are returned ordered by ascending index regardless of property order. */
	@Test(timeout = 10000)
	public void entriesAreOrderedByIndex() {
		Properties p = new Properties();
		p.setProperty("nodes.2.kind", "native");
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.1.kind", "native");

		List<NodeConfig> configs = NodeConfig.parse(p);

		Assert.assertEquals(0, configs.get(0).index());
		Assert.assertEquals(1, configs.get(1).index());
		Assert.assertEquals(2, configs.get(2).index());
	}

	/** A gap in the index range is rejected: ids must map to roster positions. */
	@Test(timeout = 10000)
	public void nonContiguousIndicesRejected() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.2.kind", "native");

		try {
			NodeConfig.parse(p);
			Assert.fail("a gap in the typed node indices must be rejected");
		} catch (IllegalArgumentException expected) {
			// indices must be contiguous from zero so id == roster position
		}
	}

	/** An unknown kind value is rejected rather than silently defaulting. */
	@Test(timeout = 10000)
	public void unknownKindRejected() {
		Properties p = new Properties();
		p.setProperty("nodes.0.kind", "quantum");

		try {
			NodeConfig.parse(p);
			Assert.fail("an unrecognised node kind must be rejected");
		} catch (IllegalArgumentException expected) {
			// only configured kinds may be named
		}
	}

	/** A non-numeric index is reported as a malformed key rather than ignored. */
	@Test(timeout = 10000)
	public void malformedIndexRejected() {
		Properties p = new Properties();
		p.setProperty("nodes.x.kind", "native");

		try {
			NodeConfig.parse(p);
			Assert.fail("a non-numeric typed node index must be rejected");
		} catch (IllegalArgumentException expected) {
			// the index portion must parse as a non-negative integer
		}
	}
}
