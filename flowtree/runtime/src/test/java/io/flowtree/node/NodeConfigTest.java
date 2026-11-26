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

import java.util.List;
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
		config.applyLabels(node);

		Assert.assertEquals("macos", node.getLabels().get("platform"));
		Assert.assertEquals("metal", node.getLabels().get("gpu"));
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
