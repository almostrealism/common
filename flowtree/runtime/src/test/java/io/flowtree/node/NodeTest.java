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

import java.util.HashMap;
import java.util.Map;

/**
 * Exercises the single-node public contract that does not require a running worker
 * thread or a network: capability labels and the {@link Node#satisfies(Map)} routing
 * predicate, the in-memory job queue ({@link Node#addJob}, {@link Node#nextJob()},
 * {@link Node#hasJob(String)}, {@link Node#sendKill(String, int)}), the connectivity
 * and activity ratings, and the simple accessors.
 *
 * <p>Every node is constructed standalone ({@code parent == null}) so no worker thread
 * starts and the queue is observed exactly as the test leaves it.</p>
 *
 * @author Michael Murray
 */
public class NodeTest extends TestSuiteBase {

	/** A node with no requirements satisfies an empty or absent requirement map. */
	@Test(timeout = 10000)
	public void satisfiesEmptyRequirements() {
		Node node = new Node(null, 0, 4, 4);
		Assert.assertTrue("a null requirement map is satisfied by any worker", node.satisfies(null));
		Assert.assertTrue("an empty requirement map is satisfied by any worker",
				node.satisfies(new HashMap<>()));
	}

	/** A requirement is satisfied only when the node carries the exact label value. */
	@Test(timeout = 10000)
	public void satisfiesMatchingLabelsOnly() {
		Node node = new Node(null, 0, 4, 4);
		node.setLabel("platform", "linux");

		Map<String, String> match = new HashMap<>();
		match.put("platform", "linux");
		Assert.assertTrue("a matching label satisfies the requirement", node.satisfies(match));

		Map<String, String> wrongValue = new HashMap<>();
		wrongValue.put("platform", "macos");
		Assert.assertFalse("a mismatched value is not satisfied", node.satisfies(wrongValue));

		Map<String, String> missingKey = new HashMap<>();
		missingKey.put("gpu", "cuda");
		Assert.assertFalse("a missing label key is not satisfied", node.satisfies(missingKey));
	}

	/** A relay node never executes jobs, so it satisfies nothing — not even an empty map. */
	@Test(timeout = 10000)
	public void relayNodeSatisfiesNothing() {
		Node node = new Node(null, 0, 4, 4);
		node.setLabel("role", "relay");

		Assert.assertFalse("a relay node rejects even an empty requirement map",
				node.satisfies(new HashMap<>()));
		Assert.assertFalse("a relay node rejects a null requirement map", node.satisfies(null));
	}

	/** The labels view reflects what was set and cannot be mutated by the caller. */
	@Test(timeout = 10000)
	public void labelsViewIsUnmodifiable() {
		Node node = new Node(null, 0, 4, 4);
		node.setLabel("platform", "linux");
		Assert.assertEquals("linux", node.getLabels().get("platform"));

		try {
			node.getLabels().put("role", "relay");
			Assert.fail("the labels view must reject mutation");
		} catch (UnsupportedOperationException expected) {
			// the view is unmodifiable, as documented
		}
	}

	/** Jobs are dequeued in FIFO order and the queue empties to null. */
	@Test(timeout = 10000)
	public void queueIsFirstInFirstOut() {
		Node node = new Node(null, 0, 4, 4);
		LabeledTestJob first = new LabeledTestJob("first");
		LabeledTestJob second = new LabeledTestJob("second");
		node.addJob(first);
		node.addJob(second);

		Assert.assertSame(first, node.nextJob());
		Assert.assertSame(second, node.nextJob());
		Assert.assertNull("the queue is empty after both jobs are taken", node.nextJob());
	}

	/** {@link Node#hasJob(String)} finds a queued task id and rejects null and absent ids. */
	@Test(timeout = 10000)
	public void hasJobReportsQueuedTasks() {
		Node node = new Node(null, 0, 4, 4);
		node.addJob(new LabeledTestJob("queued"));

		Assert.assertTrue(node.hasJob("queued"));
		Assert.assertFalse(node.hasJob("absent"));
		Assert.assertFalse("a null task id matches nothing", node.hasJob(null));
	}

	/** {@link Node#sendKill(String, int)} removes only the matching task, leaving the rest. */
	@Test(timeout = 10000)
	public void sendKillRemovesMatchingJobsOnly() {
		Node node = new Node(null, 0, 4, 4);
		LabeledTestJob doomed = new LabeledTestJob("doomed");
		LabeledTestJob keeper = new LabeledTestJob("keeper");
		node.addJob(doomed);
		node.addJob(keeper);

		node.sendKill("doomed", 0);

		Assert.assertFalse("the killed task is gone", node.hasJob("doomed"));
		Assert.assertTrue("the unrelated task remains", node.hasJob("keeper"));
		Assert.assertSame("the surviving job is next in the queue", keeper, node.nextJob());
		Assert.assertNull(node.nextJob());
	}

	/** Connectivity rating is peers over max peers — zero for a freshly built node. */
	@Test(timeout = 10000)
	public void connectivityRatingStartsAtZero() {
		Node node = new Node(null, 0, 4, 8);
		Assert.assertEquals(0.0, node.getConnectivityRating(), 1e-9);
	}

	/** Enqueuing work raises the activity rating above its idle baseline. */
	@Test(timeout = 10000)
	public void activityRatingRisesWithQueueDepth() {
		Node node = new Node(null, 0, 4, 4);
		double idle = node.getActivityRating();

		node.addJob(new LabeledTestJob("a"));
		node.addJob(new LabeledTestJob("b"));

		Assert.assertTrue("a deeper queue reports higher activity",
				node.getActivityRating() > idle);
	}

	/** A fresh node reports zero completed, failed, and relayed jobs. */
	@Test(timeout = 10000)
	public void countersStartAtZero() {
		Node node = new Node(null, 3, 4, 4);
		Assert.assertEquals(0, node.getCompletedJobCount());
		Assert.assertEquals(0, node.getErrorJobCount());
		Assert.assertEquals(0, node.getRelayedJobCount());
	}

	/** Constructor arguments are reflected by the id and peer-capacity accessors. */
	@Test(timeout = 10000)
	public void accessorsReflectConstruction() {
		Node node = new Node(null, 7, 4, 9);
		Assert.assertEquals(7, node.getId());
		Assert.assertEquals(9, node.getMaxPeers());
		Assert.assertFalse("a standalone node never started its activity thread", node.isAlive());
	}

	/** The HTML status report mentions the node and its relayed-job count. */
	@Test(timeout = 10000)
	public void statusReportDescribesNode() {
		Node node = new Node(null, 2, 4, 4);
		String status = node.getStatus("\n");

		Assert.assertTrue("the report names the node", status.contains(node.toString()));
		Assert.assertTrue("the report mentions relayed jobs", status.contains("relayed 0 jobs"));
	}
}
