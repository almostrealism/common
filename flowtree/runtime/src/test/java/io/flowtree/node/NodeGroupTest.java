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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Properties;

/**
 * Exercises the {@link NodeGroup} aggregate as it is actually assembled: constructing a
 * group from a {@link Properties} description wires up every package-private collaborator
 * ({@link NodeGroupMetrics}, {@link NodeGroupNodeConfig}, {@link NodeGroupServerRegistry},
 * {@link NodeGroupStatusRenderer}) and applies automatic and configured labels. These tests
 * assert on the group's observable state — its node roster, label propagation, parameter
 * dispatch, server registry, and rendered status — without starting any thread or socket.
 *
 * <p>Offline mode is enforced before any group is built so the constructor never contacts a
 * {@code FLOWTREE_ROOT_HOST} controller, per {@link NodeGroupNodeConfig#OFFLINE_MODE_PROPERTY}.</p>
 *
 * @author Michael Murray
 */
public class NodeGroupTest extends TestSuiteBase {

	/** The offline-mode property value captured before the suite forced it on, or {@code null} if it was unset. */
	private static String originalOfflineMode;

	/** Forces offline mode so the constructor cannot reach a live controller. */
	@BeforeClass
	public static void enforceOfflineMode() {
		originalOfflineMode = System.getProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY);
		System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, "true");
	}

	/**
	 * Restores the offline-mode property to the value it held before the suite ran so a
	 * later test in the same JVM does not inherit the forced {@code "true"}.
	 */
	@AfterClass
	public static void restoreOfflineMode() {
		if (originalOfflineMode == null) {
			System.clearProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY);
		} else {
			System.setProperty(NodeGroupNodeConfig.OFFLINE_MODE_PROPERTY, originalOfflineMode);
		}
	}

	/**
	 * Builds a minimal, fully offline node-group configuration with the given worker count.
	 *
	 * @param workerCount the number of worker nodes to create
	 * @return the properties describing the group
	 */
	private static Properties groupProperties(int workerCount) {
		Properties p = new Properties();
		p.setProperty("group.thread.sleep", "10000");
		p.setProperty("nodes.initial", String.valueOf(workerCount));
		p.setProperty("nodes.jobs.max", "4");
		p.setProperty("nodes.peers.max", "5");
		p.setProperty("nodes.relay.peers.max", "5");
		p.setProperty("servers.total", "0");
		return p;
	}

	/** The constructor creates exactly the requested worker nodes plus a relay node. */
	@Test(timeout = 20000)
	public void constructsRequestedWorkerNodes() {
		NodeGroup group = new NodeGroup(groupProperties(2), null);

		Assert.assertEquals("two worker nodes were requested", 2, group.getNodes().length);
		Assert.assertEquals("the live node view agrees with the roster", 2, group.nodes().size());
		Assert.assertNotNull("a relay node is always present", group.getRelayNode());
	}

	/** The relay node advertises only its role and may never execute a job. */
	@Test(timeout = 20000)
	public void relayNodeCarriesRelayRole() {
		NodeGroup group = new NodeGroup(groupProperties(1), null);
		Node relay = group.getRelayNode();

		Assert.assertEquals("relay", relay.getLabels().get("role"));
		Assert.assertFalse("a relay node satisfies no job", relay.satisfies(new HashMap<>()));
	}

	/** {@link NodeGroup#getNode(int)} returns workers by index and the relay beyond them. */
	@Test(timeout = 20000)
	public void getNodeResolvesWorkersAndRelay() {
		NodeGroup group = new NodeGroup(groupProperties(2), null);

		Assert.assertSame("index 0 is the first worker", group.getNodes()[0], group.getNode(0));
		Assert.assertSame("an out-of-range index resolves to the relay node",
				group.getRelayNode(), group.getNode(99));
	}

	/** A configured node label propagates to every worker node of the group. */
	@Test(timeout = 20000)
	public void configuredLabelsPropagateToWorkers() {
		Properties p = groupProperties(2);
		p.setProperty("nodes.labels.gpu", "cuda");
		NodeGroup group = new NodeGroup(p, null);

		for (Node n : group.getNodes()) {
			Assert.assertEquals("every worker inherits the group label", "cuda", n.getLabels().get("gpu"));
		}
	}

	/** {@link NodeGroup#setParam(String, String)} reports whether a key was recognised. */
	@Test(timeout = 20000)
	public void setParamDistinguishesKnownFromUnknownKeys() {
		NodeGroup group = new NodeGroup(groupProperties(1), null);

		Assert.assertTrue("a recognised key is applied", group.setParam("nodes.relay", "0.5"));
		Assert.assertFalse("an unrecognised key is rejected", group.setParam("not.a.real.key", "x"));
	}

	/** The activity offset set through {@code group.aco} shifts the reported average. */
	@Test(timeout = 20000)
	public void activityOffsetParamShiftsAverage() {
		NodeGroup group = new NodeGroup(groupProperties(2), null);

		group.setParam("group.aco", "0.0");
		double base = group.getAverageActivityRating();

		group.setParam("group.aco", "-1.0");
		double shifted = group.getAverageActivityRating();

		Assert.assertEquals("a -1.0 offset lowers the average by exactly one",
				-1.0, shifted - base, 1e-9);
	}

	/** An offline group with no configured servers has an empty registry. */
	@Test(timeout = 20000)
	public void offlineGroupHasNoServers() {
		NodeGroup group = new NodeGroup(groupProperties(1), null);

		Assert.assertEquals("offline mode opens no server connections", 0, group.getServers().length);
		Assert.assertNull("no servers means no connection can be selected", group.getConnection(0));
	}

	/** A fresh group has completed no jobs and reports no running work. */
	@Test(timeout = 20000)
	public void freshGroupIsIdle() {
		NodeGroup group = new NodeGroup(groupProperties(2), null);

		Assert.assertEquals(0, group.getCompletedJobCount());
		Assert.assertFalse("no worker thread was started", group.isWorking());
		Assert.assertEquals("no tasks were registered", 0, group.taskList().length);
	}

	/** The HTML status report names the group and includes a per-node section. */
	@Test(timeout = 20000)
	public void statusReportRendersGroupAndNodes() {
		NodeGroup group = new NodeGroup(groupProperties(2), null);

		String status = group.getStatus("<br>\n");

		Assert.assertTrue("the report carries the group heading",
				status.contains("Network Node Group Status"));
		Assert.assertTrue("the report includes the activity-rating section",
				status.contains("Activity Rating"));
		// A second render must also succeed once the interval accumulators have been reset.
		Assert.assertTrue(group.getStatus("<br>\n").contains("Network Node Group Status"));
	}

	/** {@link NodeGroup#getLeastConnectedNode()} returns one of the group's own workers. */
	@Test(timeout = 20000)
	public void leastConnectedNodeIsAWorker() {
		NodeGroup group = new NodeGroup(groupProperties(2), null);
		Node least = group.getLeastConnectedNode();

		Assert.assertNotNull(least);
		boolean isWorker = false;
		for (Node n : group.getNodes()) {
			if (n == least) {
				isWorker = true;
			}
		}
		Assert.assertTrue("the least-connected node is one of the group's workers", isWorker);
	}

	/** Offline mode is observable through the configurator's own predicate. */
	@Test(timeout = 20000)
	public void offlineModeIsReported() {
		Assert.assertTrue("the suite enforced offline mode", NodeGroupNodeConfig.isOfflineMode());
	}

	/** Recognised parameters take effect: the working directory and max-sleep coefficient. */
	@Test(timeout = 20000)
	public void setParamAppliesConfiguredValues() {
		NodeGroup group = new NodeGroup(groupProperties(1), null);
		String originalWd = System.getProperty("flowtree.workingDirectory");
		try {
			Assert.assertTrue(group.setParam("nodes.workingDirectory", "/tmp/ft-coverage-wd"));
			Assert.assertEquals("/tmp/ft-coverage-wd", System.getProperty("flowtree.workingDirectory"));

			Assert.assertTrue(group.setParam("group.msc", "3.5"));
			Assert.assertEquals("the max-sleep coefficient was applied", 3.5, group.getMaxSleepC(), 1e-9);
		} finally {
			if (originalWd == null) {
				System.clearProperty("flowtree.workingDirectory");
			} else {
				System.setProperty("flowtree.workingDirectory", originalWd);
			}
		}
	}

	/** A numeric parameter given a non-numeric value surfaces the parse failure. */
	@Test(timeout = 20000)
	public void setParamRejectsNonNumericValue() {
		NodeGroup group = new NodeGroup(groupProperties(1), null);
		try {
			group.setParam("nodes.acs", "not-a-number");
			Assert.fail("a non-numeric activity-sleep coefficient must not be accepted");
		} catch (NumberFormatException expected) {
			// the configurator parses the value eagerly, as documented
		}
	}
}
