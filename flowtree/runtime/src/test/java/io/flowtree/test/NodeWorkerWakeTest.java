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

package io.flowtree.test;

import io.flowtree.node.Node;
import io.flowtree.node.NodeGroup;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests that an idle {@link Node} worker thread is woken by
 * {@link Node#addJob} rather than discovering a new job only at the end of
 * its idle poll interval.
 *
 * @author Michael Murray
 */
public class NodeWorkerWakeTest extends TestSuiteBase {

	/**
	 * Upper bound on how long a job queued to an idle worker may wait before
	 * it runs. The worker's idle poll interval is five seconds, so a worker
	 * that is not woken by the job's arrival exceeds this bound.
	 */
	private static final long WAKE_BOUND_MILLIS = 2000;

	/**
	 * Verifies that a job queued while the worker is idle (its queue empty
	 * after completing an earlier job) runs well within the idle poll
	 * interval, for several consecutive jobs.
	 */
	@Test(timeout = 30000)
	public void idleWorkerIsWokenByAddJob() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		NodeGroup group = new NodeGroup(nodeGroupProperties(),
				new NodeLabelRoutingTest.NoOpFactory());
		Node child = group.getNodes()[0];

		CountDownLatch first = new CountDownLatch(1);
		child.addJob(new NodeLabelRoutingTest.LabelledJob("wake-start",
				Collections.emptyMap(), first));
		Assert.assertTrue("First job should start the worker and run",
				first.await(10, TimeUnit.SECONDS));

		for (int i = 0; i < 3; i++) {
			// Let the worker drain its queue and enter its idle wait
			Thread.sleep(500);
			Assert.assertFalse("Worker should be idle before the next job is queued",
					child.isWorking());

			CountDownLatch executed = new CountDownLatch(1);
			long start = System.currentTimeMillis();
			child.addJob(new NodeLabelRoutingTest.LabelledJob("wake-" + i,
					Collections.emptyMap(), executed));

			Assert.assertTrue("Job queued to an idle worker should run within "
							+ WAKE_BOUND_MILLIS + " ms",
					executed.await(WAKE_BOUND_MILLIS, TimeUnit.MILLISECONDS));
			Assert.assertTrue(System.currentTimeMillis() - start < WAKE_BOUND_MILLIS);
		}
	}

	/**
	 * Verifies that a job queued to an idle worker is executed exactly once
	 * even though the wake-up and the bounded idle wait can both return.
	 */
	@Test(timeout = 30000)
	public void wokenWorkerRunsJobOnce() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		NodeGroup group = new NodeGroup(nodeGroupProperties(),
				new NodeLabelRoutingTest.NoOpFactory());
		Node child = group.getNodes()[0];

		CountDownLatch first = new CountDownLatch(1);
		child.addJob(new NodeLabelRoutingTest.LabelledJob("once-start",
				Collections.emptyMap(), first));
		Assert.assertTrue(first.await(10, TimeUnit.SECONDS));
		Thread.sleep(500);

		CountDownLatch executed = new CountDownLatch(2);
		child.addJob(new NodeLabelRoutingTest.LabelledJob("once",
				Collections.emptyMap(), executed));

		// Wait past one full idle poll interval; a second run would count down again
		Assert.assertFalse("Job should not run more than once",
				executed.await(6, TimeUnit.SECONDS));
		Assert.assertEquals(1, executed.getCount());
	}

	/**
	 * Properties for a single-node {@link NodeGroup} with no servers.
	 *
	 * @return the configuration properties
	 */
	private static Properties nodeGroupProperties() {
		Properties p = new Properties();
		p.setProperty("server.port", "0");
		p.setProperty("nodes.initial", "1");
		p.setProperty("nodes.jobs.max", "4");
		p.setProperty("servers.total", "0");
		p.setProperty("group.thread.sleep", "10000");
		return p;
	}
}
