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

package io.flowtree.fs;

import io.flowtree.fs.ResourceDistributionTask.ResourceDistributionJob;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ResourceDistributionJob}, the reusable job that encodes a
 * chunk for transmission to a peer and, on the receiving side, applies the
 * decoded key-value fields. The parts reachable without a database or peer
 * network are the in-use flag, the idle and active {@code run()} paths, the
 * string description, and the {@code encode}/{@code set} round-trip governed by
 * whether the owning task reports a chunk is available.
 *
 * <p>The encode tests construct a {@link ResourceDistributionTask}, whose
 * JVM-wide static state is snapshotted and restored by the
 * {@link ResourceDistributionStaticStateTest} base class.
 */
public class ResourceDistributionJobTest extends ResourceDistributionStaticStateTest {

	/**
	 * An {@link InMemoryTask} whose chunk lookup reports a fixed availability, so
	 * {@link ResourceDistributionJob#encode()} either serialises the job's fields
	 * or reports no data, without a real database.
	 */
	private static final class StubTask extends InMemoryTask {
		/** Whether {@link #loadJob(ResourceDistributionJob)} reports a chunk is available. */
		private final boolean available;

		/**
		 * Constructs a task whose {@code loadJob} returns the given availability.
		 *
		 * @param available the value {@link #loadJob(ResourceDistributionJob)} returns
		 */
		private StubTask(boolean available) {
			this.available = available;
		}

		@Override
		protected boolean loadJob(ResourceDistributionJob j) {
			return available;
		}
	}

	/**
	 * A freshly constructed job with no owning task is idle, encodes to
	 * {@code null} (no data to send), and names itself by its (unset) id. The
	 * in-use flag toggles, and the completion future exists but is never
	 * completed because the job is reusable.
	 */
	@Test(timeout = 5000)
	public void idleJobWithoutTask() {
		ResourceDistributionJob job = new ResourceDistributionJob();

		Assert.assertFalse(job.isInUse());
		job.setInUse(true);
		Assert.assertTrue(job.isInUse());

		Assert.assertEquals("ResourceDistributionTask (null)", job.getTaskString());
		Assert.assertNull(job.encode());

		Assert.assertNotNull(job.getCompletableFuture());
		Assert.assertFalse(job.getCompletableFuture().isDone());
	}

	/**
	 * {@link ResourceDistributionJob#run()} on a job with no task must return
	 * immediately without sleeping or changing the in-use flag, since there is no
	 * work to schedule.
	 */
	@Test(timeout = 5000)
	public void runWithoutTaskIsANoOp() {
		ResourceDistributionJob job = new ResourceDistributionJob();
		job.setInUse(true);
		job.run();
		Assert.assertTrue(job.isInUse());
	}

	/**
	 * With an owning task that reports a chunk is available,
	 * {@link ResourceDistributionJob#encode()} must serialise the job's URI,
	 * chunk index, and raw data — the same fields that
	 * {@link ResourceDistributionJob#set(String, String)} applies on the
	 * receiving node — into the {@code uri=…:i=…:RAW:…} wire form.
	 */
	@Test(timeout = 5000)
	public void encodeSerialisesConfiguredFields() {
		StubTask task = new StubTask(true);
		ResourceDistributionJob job = new ResourceDistributionJob(task);

		job.set("uri", "/img/a.png");
		job.set("i", "3");
		job.set("data", "payload");

		String encoded = job.encode();
		Assert.assertNotNull(encoded);
		Assert.assertTrue(encoded.contains(":uri=/img/a.png"));
		Assert.assertTrue(encoded.contains(":i=3"));
		Assert.assertTrue(encoded.endsWith(":RAW:payload"));
	}

	/**
	 * When the owning task reports no chunk is available,
	 * {@link ResourceDistributionJob#encode()} must return {@code null} rather
	 * than emitting an empty transfer record.
	 */
	@Test(timeout = 5000)
	public void encodeReturnsNullWhenNoChunkAvailable() {
		StubTask task = new StubTask(false);
		ResourceDistributionJob job = new ResourceDistributionJob(task);
		Assert.assertNull(job.encode());
	}

	/**
	 * {@link ResourceDistributionJob#run()} on a job that has an owning task must
	 * complete its post-run pause and then clear the in-use flag, making the job
	 * available for reuse. A zero sleep keeps the test fast while still covering
	 * the flag reset.
	 */
	@Test(timeout = 5000)
	public void runWithTaskClearsInUseFlag() {
		StubTask task = new StubTask(true);
		ResourceDistributionJob job = new ResourceDistributionJob(task);
		job.setSleep(0);
		job.setInUse(true);

		job.run();

		Assert.assertFalse(job.isInUse());
	}
}
