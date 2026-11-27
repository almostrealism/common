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

import io.flowtree.job.Job;
import org.junit.Assert;
import org.junit.Test;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the {@link Node#launch(Job)} seam, the single point at which one
 * {@link NodeKind} differs from another. The native Node's {@code launch} runs the
 * job in-process, which is today's behaviour; a subtype overrides the seam to run
 * the job elsewhere. These tests assert that the default body runs the job, that an
 * override replaces that body, and — most importantly — that the worker thread
 * executes dequeued jobs through {@code launch} rather than calling {@code run()}
 * directly, so a subtype's override is actually reached.
 *
 * @author Michael Murray
 */
public class NodeLaunchTest extends OfflineNodeTestBase {

	/** The default seam runs the job in-process: the native body is {@code j.run()}. */
	@Test(timeout = 10000)
	public void defaultLaunchRunsJobInProcess() throws Exception {
		Node node = new Node(null, 0, 4, 4);
		LabeledTestJob job = new LabeledTestJob("native");

		Assert.assertFalse("the job has not run yet", job.getCompletableFuture().isDone());
		node.launch(job);
		Assert.assertTrue("the default launch runs the job in-process",
				job.getCompletableFuture().isDone());
	}

	/** A subtype's override replaces the native body; the job is not run in-process. */
	@Test(timeout = 10000)
	public void overrideReplacesNativeBody() throws Exception {
		Node node = new Node(null, 0, 4, 4) {
			@Override
			protected void launch(Job j) {
				// A delegating kind hands the job elsewhere and does not run it here.
			}
		};
		LabeledTestJob job = new LabeledTestJob("delegated");

		node.launch(job);
		Assert.assertFalse("an override that does not call super must not run the job",
				job.getCompletableFuture().isDone());
	}

	/** The worker thread executes dequeued jobs through {@code launch}, not {@code run}. */
	@Test(timeout = 20000)
	public void workerThreadExecutesThroughLaunchSeam() throws Exception {
		Properties props = new Properties();
		props.setProperty("servers.total", "0");
		NodeGroup group = new NodeGroup(props, null);

		CountDownLatch launched = new CountDownLatch(1);
		RecordingLaunchNode node = new RecordingLaunchNode(group, 50, 4, 4, launched);
		LabeledTestJob job = new LabeledTestJob("worked");

		node.addJob(job);

		Assert.assertTrue("the worker must reach the launch seam within the timeout",
				launched.await(10, TimeUnit.SECONDS));
		Assert.assertTrue("the seam ran the job through its native body",
				job.getCompletableFuture().isDone());
	}

	/**
	 * A {@link Node} that records each time the worker thread reaches its launch seam,
	 * while still running the job through the inherited native body. Used to prove the
	 * worker executes jobs through {@link Node#launch(Job)} rather than bypassing it.
	 */
	private static final class RecordingLaunchNode extends Node {

		/** Counted down the first time the launch seam is reached. */
		private final CountDownLatch launched;

		/**
		 * @param parent    The owning group.
		 * @param id        The node id.
		 * @param maxJobs   Maximum queued jobs before shedding to peers.
		 * @param maxPeers  Maximum peer connections.
		 * @param launched  Latch counted down when the seam is reached.
		 */
		private RecordingLaunchNode(NodeGroup parent, int id, int maxJobs, int maxPeers,
									CountDownLatch launched) {
			super(parent, id, maxJobs, maxPeers);
			this.launched = launched;
		}

		@Override
		protected void launch(Job j) throws Exception {
			super.launch(j);
			launched.countDown();
		}
	}
}
