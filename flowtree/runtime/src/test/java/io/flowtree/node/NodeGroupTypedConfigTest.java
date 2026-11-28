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

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Exercises the typed {@code nodes.<i>.kind} construction path of {@link NodeGroup}:
 * a group built from an indexed list of typed Node entries carries distinct per-Node
 * labels rather than one broadcast label set. This is the capability the group-wide
 * {@link NodeGroup#setLabel(String, String)} broadcast made impossible, and it is
 * what lets one Server hold Nodes of different platforms.
 *
 * <p>These tests also assert that the legacy {@code nodes.initial} path is unchanged
 * (labels still broadcast) and that a typed native Node still receives the host's
 * automatic platform label, so a job with a {@code platform} requirement continues
 * to route.</p>
 *
 * <p>Offline mode is enforced before any group is built so the constructor never
 * contacts a {@code FLOWTREE_ROOT_HOST} controller.</p>
 *
 * @author Michael Murray
 */
public class NodeGroupTypedConfigTest extends OfflineNodeTestBase {

	/** A baseline offline group configuration with the common properties set. */
	private static Properties baseProperties() {
		Properties p = new Properties();
		p.setProperty("group.thread.sleep", "10000");
		p.setProperty("nodes.jobs.max", "4");
		p.setProperty("nodes.peers.max", "5");
		p.setProperty("nodes.relay.peers.max", "5");
		p.setProperty("servers.total", "0");
		return p;
	}

	/** Two typed native Nodes carry their own distinct labels, not a broadcast set. */
	@Test(timeout = 20000)
	public void typedNodesCarryDistinctLabels() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.platform", "macos");
		p.setProperty("nodes.1.kind", "native");
		p.setProperty("nodes.1.labels.platform", "linux");

		NodeGroup group = new NodeGroup(p, null);
		Node[] nodes = group.getNodes();

		Assert.assertEquals("the typed list builds exactly its entries", 2, nodes.length);
		Assert.assertEquals("macos", nodes[0].getLabels().get("platform"));
		Assert.assertEquals("linux", nodes[1].getLabels().get("platform"));
	}

	/** A label on one typed Node is not copied onto the others (no broadcast). */
	@Test(timeout = 20000)
	public void typedLabelsAreNotBroadcast() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.gpu", "metal");
		p.setProperty("nodes.1.kind", "native");

		NodeGroup group = new NodeGroup(p, null);
		Node[] nodes = group.getNodes();

		Assert.assertEquals("metal", nodes[0].getLabels().get("gpu"));
		Assert.assertNull("a per-node label must not reach a sibling node",
				nodes[1].getLabels().get("gpu"));
	}

	/** Each typed Node routes only the job whose platform requirement it matches. */
	@Test(timeout = 20000)
	public void distinctLabelsRouteDistinctJobs() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.platform", "macos");
		p.setProperty("nodes.1.kind", "native");
		p.setProperty("nodes.1.labels.platform", "linux");

		NodeGroup group = new NodeGroup(p, null);
		Node[] nodes = group.getNodes();

		Map<String, String> macos = new HashMap<>();
		macos.put("platform", "macos");
		Map<String, String> linux = new HashMap<>();
		linux.put("platform", "linux");

		Assert.assertTrue("only the macos node runs a macos job", nodes[0].satisfies(macos));
		Assert.assertFalse(nodes[0].satisfies(linux));
		Assert.assertTrue("only the linux node runs a linux job", nodes[1].satisfies(linux));
		Assert.assertFalse(nodes[1].satisfies(macos));
	}

	/** A typed native Node with no configured platform takes the host's detected one. */
	@Test(timeout = 20000)
	public void typedNativeNodeTakesHostPlatform() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");

		NodeGroup group = new NodeGroup(p, null);

		Assert.assertEquals("a native node describes the host it runs on",
				AutomaticLabel.PLATFORM.detect(), group.getNodes()[0].getLabels().get("platform"));
	}

	/** The typed path still builds a relay node that satisfies no job. */
	@Test(timeout = 20000)
	public void typedPathBuildsRelayNode() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");

		NodeGroup group = new NodeGroup(p, null);

		Assert.assertEquals("one typed worker was requested", 1, group.getNodes().length);
		Assert.assertNotNull("a relay node is always present", group.getRelayNode());
		Assert.assertEquals("relay", group.getRelayNode().getLabels().get("role"));
	}

	/** A typed host-resident Node still inherits a group-level {@code nodes.labels.*} label. */
	@Test(timeout = 20000)
	public void typedHostNodeInheritsGroupLevelLabel() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.labels.node-id", "host-5");

		NodeGroup group = new NodeGroup(p, null);

		Assert.assertEquals("a group-level host label reaches a typed native node",
				"host-5", group.getNodes()[0].getLabels().get("node-id"));
	}

	/** A per-node typed label overrides a group-level label of the same key. */
	@Test(timeout = 20000)
	public void perNodeLabelOverridesGroupLevelLabel() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.gpu", "metal");
		p.setProperty("nodes.labels.gpu", "cuda");

		NodeGroup group = new NodeGroup(p, null);

		Assert.assertEquals("the more specific per-node label wins", "metal",
				group.getNodes()[0].getLabels().get("gpu"));
	}

	/**
	 * The typed path labels the group itself with the host labels, so the group —
	 * which is publicly a {@link Node} — reports the same host metadata through
	 * {@link Node#getLabels()} that the legacy path gives it, rather than an empty map.
	 */
	@Test(timeout = 20000)
	public void typedGroupItselfCarriesHostLabels() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.labels.node-id", "host-7");

		NodeGroup group = new NodeGroup(p, null);

		Assert.assertEquals("the group carries the group-level host label",
				"host-7", group.getLabels().get("node-id"));
		Assert.assertEquals("the group carries the host's detected platform",
				AutomaticLabel.PLATFORM.detect(), group.getLabels().get("platform"));
	}

	/**
	 * Labelling the group in the typed path does not broadcast the host labels back
	 * onto the child Nodes, so a per-node label stays on its one Node: the group's
	 * own label map and the children's are populated independently.
	 */
	@Test(timeout = 20000)
	public void typedGroupLabelsDoNotBroadcastToChildren() {
		Properties p = baseProperties();
		p.setProperty("nodes.0.kind", "native");
		p.setProperty("nodes.0.labels.gpu", "metal");
		p.setProperty("nodes.1.kind", "native");

		NodeGroup group = new NodeGroup(p, null);
		Node[] nodes = group.getNodes();

		Assert.assertNull("the group must not acquire a per-node label",
				group.getLabels().get("gpu"));
		Assert.assertEquals("metal", nodes[0].getLabels().get("gpu"));
		Assert.assertNull("a per-node label must not reach a sibling node",
				nodes[1].getLabels().get("gpu"));
	}

	/** The legacy count path is unchanged: a configured label still reaches every worker. */
	@Test(timeout = 20000)
	public void legacyPathStillBroadcasts() {
		Properties p = baseProperties();
		p.setProperty("nodes.initial", "2");
		p.setProperty("nodes.labels.gpu", "cuda");

		NodeGroup group = new NodeGroup(p, null);

		for (Node n : group.getNodes()) {
			Assert.assertEquals("the legacy path broadcasts labels to every worker",
					"cuda", n.getLabels().get("gpu"));
		}
	}
}
