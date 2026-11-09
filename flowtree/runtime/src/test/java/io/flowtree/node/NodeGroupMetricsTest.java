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

import io.flowtree.msg.NodeProxy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Verifies the aggregate computations of {@link NodeGroupMetrics} over a live collection
 * of standalone {@link Node} instances, including the empty-collection edge cases that the
 * happy path misses and the label-aware job matching used to route work.
 *
 * @author Michael Murray
 */
public class NodeGroupMetricsTest extends TestSuiteBase {

	/** An empty collection yields the documented empty-group defaults, not a crash. */
	@Test(timeout = 10000)
	public void emptyCollectionReportsDefaults() {
		NodeGroupMetrics metrics = new NodeGroupMetrics(new ArrayList<>());

		Assert.assertEquals(0, metrics.getCompletedJobCount());
		Assert.assertEquals(0.0, metrics.getTimeWorked(), 1e-9);
		Assert.assertEquals(0.0, metrics.getTimeCommunicated(), 1e-9);
		Assert.assertEquals("no completed jobs means an undefined mean job time",
				-1.0, metrics.getAverageJobTime(), 1e-9);
		Assert.assertEquals(0.0, metrics.getAverageConnectivityRating(), 1e-9);
		Assert.assertEquals(0.0, metrics.getAverageActivityRating(0.0), 1e-9);
		Assert.assertNull(metrics.getLeastConnectedNode());
		Assert.assertNull(metrics.getLeastActiveNode());
	}

	/** Fresh nodes have completed no work, so the aggregate counters are zero. */
	@Test(timeout = 10000)
	public void freshNodesContributeNoWork() {
		List<Node> nodes = new ArrayList<>();
		nodes.add(new Node(null, 0, 4, 4));
		nodes.add(new Node(null, 1, 4, 4));
		NodeGroupMetrics metrics = new NodeGroupMetrics(nodes);

		Assert.assertEquals(0, metrics.getCompletedJobCount());
		Assert.assertEquals(0.0, metrics.getTimeWorked(), 1e-9);
		Assert.assertEquals("two unconnected nodes average zero connectivity",
				0.0, metrics.getAverageConnectivityRating(), 1e-9);
	}

	/** The activity offset biases the raw average by exactly the amount supplied. */
	@Test(timeout = 10000)
	public void activityOffsetBiasesTheAverage() {
		List<Node> nodes = new ArrayList<>();
		nodes.add(new Node(null, 0, 4, 4));
		nodes.add(new Node(null, 1, 4, 4));
		NodeGroupMetrics metrics = new NodeGroupMetrics(nodes);

		double unbiased = metrics.getAverageActivityRating(0.0);
		double biased = metrics.getAverageActivityRating(2.5);

		Assert.assertEquals("the offset shifts the mean by its own value",
				2.5, biased - unbiased, 1e-9);
	}

	/** The least-connected node is the first of equally-unconnected candidates. */
	@Test(timeout = 10000)
	public void leastConnectedIsReturnedForEqualNodes() {
		Node first = new Node(null, 0, 4, 4);
		Node second = new Node(null, 1, 4, 4);
		List<Node> nodes = new ArrayList<>();
		nodes.add(first);
		nodes.add(second);
		NodeGroupMetrics metrics = new NodeGroupMetrics(nodes);

		Assert.assertSame("ties resolve to the first node scanned", first, metrics.getLeastConnectedNode());
	}

	/** The least-active node is the one with the shallowest job queue. */
	@Test(timeout = 10000)
	public void leastActiveIsTheShallowestQueue() {
		Node idle = new Node(null, 0, 4, 4);
		Node busy = new Node(null, 1, 4, 4);
		busy.addJob(new LabeledTestJob("a"));
		busy.addJob(new LabeledTestJob("b"));

		List<Node> nodes = new ArrayList<>();
		nodes.add(busy);
		nodes.add(idle);
		NodeGroupMetrics metrics = new NodeGroupMetrics(nodes);

		Assert.assertSame("the idle node is strictly least active", idle, metrics.getLeastActiveNode());
	}

	/** Job routing picks a node whose labels satisfy the job's requirements. */
	@Test(timeout = 10000)
	public void findNodeForJobHonorsLabels() {
		Node linux = new Node(null, 0, 4, 4);
		linux.setLabel("platform", "linux");
		Node unlabeled = new Node(null, 1, 4, 4);

		List<Node> nodes = new ArrayList<>();
		nodes.add(linux);
		nodes.add(unlabeled);
		NodeGroupMetrics metrics = new NodeGroupMetrics(nodes);

		Map<String, String> requirement = Collections.singletonMap("platform", "linux");
		Assert.assertSame("only the linux node qualifies", linux,
				metrics.findNodeForJob(new LabeledTestJob("job", requirement)));

		Map<String, String> impossible = Collections.singletonMap("platform", "solaris");
		Assert.assertNull("no node carries the required label",
				metrics.findNodeForJob(new LabeledTestJob("job", impossible)));
	}

	/** With no peers reporting, the mean peer activity rating is zero rather than NaN. */
	@Test(timeout = 10000)
	public void averagePeerActivityIsZeroWithNoPeers() {
		Assert.assertEquals(0.0,
				NodeGroupMetrics.getAveragePeerActivityRating(new NodeProxy[0]), 1e-9);
	}
}
