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

package io.flowtree.controller;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link LostJobRecovery}: a job accepted but never started is
 * escalated exactly once past the lost threshold, a still-fresh pending job is
 * left alone, and the escalation names the job and which lost situation it is in.
 */
public class LostJobRecoveryTest extends TestSuiteBase {

	/** Workstream ids of notices a scan posted. */
	private final List<String> noticeWorkstreams = new ArrayList<>();

	/** Notice messages a scan posted. */
	private final List<String> notices = new ArrayList<>();

	/** Alert messages a scan raised. */
	private final List<String> alerts = new ArrayList<>();

	/**
	 * Creates and initialises a stats store backed by a fresh temporary database.
	 *
	 * @return the initialised store
	 * @throws Exception if the temporary directory cannot be created
	 */
	private JobStatsStore newStore() throws Exception {
		File tempDir = Files.createTempDirectory("lost-job-test").toFile();
		tempDir.deleteOnExit();
		JobStatsStore store = new JobStatsStore(new File(tempDir, "stats").getAbsolutePath());
		store.initialize();
		return store;
	}

	/**
	 * Creates a scanner over {@code store} that records what it escalates.
	 *
	 * @param store         the store to scan
	 * @param queuedLocally whether a job is reported still queued on the controller
	 * @return the scanner
	 */
	private LostJobRecovery newScanner(JobStatsStore store, boolean queuedLocally) {
		return new LostJobRecovery(store,
				(ws, msg) -> { noticeWorkstreams.add(ws); notices.add(msg); },
				alerts::add,
				taskId -> queuedLocally);
	}

	/** A job submitted past the lost threshold is escalated once, by notice and alert. */
	@Test(timeout = 60000)
	public void lostJobIsEscalatedOnce() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-11-05T12:00:00Z");
		store.recordJobSubmitted("job-lost", "ws-1", "implement the thing",
				"alice", null, now.minus(Duration.ofMinutes(45)));

		LostJobRecovery scanner = newScanner(store, false);

		List<String> raised = scanner.scanOnce(now);

		Assert.assertEquals(List.of("job-lost"), raised);
		Assert.assertEquals(List.of("ws-1"), noticeWorkstreams);
		Assert.assertEquals(1, alerts.size());
		Assert.assertTrue("the escalation names the job", alerts.get(0).contains("job-lost"));

		// A second scan must not escalate the same job again.
		Assert.assertTrue(scanner.scanOnce(now.plus(Duration.ofMinutes(10))).isEmpty());
		Assert.assertEquals(1, alerts.size());
	}

	/** A pending job still within the lost threshold is not escalated. */
	@Test(timeout = 60000)
	public void freshPendingJobIsLeftAlone() throws Exception {
		JobStatsStore store = newStore();
		Instant now = Instant.parse("2026-11-05T12:00:00Z");
		store.recordJobSubmitted("job-fresh", "ws-1", "just submitted",
				"alice", null, now.minus(Duration.ofMinutes(5)));

		LostJobRecovery scanner = newScanner(store, true);

		Assert.assertTrue(scanner.scanOnce(now).isEmpty());
		Assert.assertTrue(alerts.isEmpty());
		Assert.assertTrue(notices.isEmpty());
	}

	/** The message distinguishes a job still queued here from one handed to a peer. */
	@Test(timeout = 60000)
	public void escalationDistinguishesQueuedFromRelayed() throws Exception {
		JobStatsStore queuedStore = newStore();
		Instant now = Instant.parse("2026-11-05T12:00:00Z");
		queuedStore.recordJobSubmitted("job-queued", "ws-1", "waiting for a worker",
				"alice", null, now.minus(Duration.ofMinutes(45)));
		newScanner(queuedStore, true).scanOnce(now);
		Assert.assertTrue("queued-locally message explains the missing worker",
				alerts.get(0).contains("still queued on the controller"));

		alerts.clear();
		JobStatsStore relayedStore = newStore();
		relayedStore.recordJobSubmitted("job-relayed", "ws-1", "handed to a peer",
				"alice", null, now.minus(Duration.ofMinutes(45)));
		newScanner(relayedStore, false).scanOnce(now);
		Assert.assertTrue("relayed message warns against a duplicate run",
				alerts.get(0).contains("handed to a peer"));
	}
}
