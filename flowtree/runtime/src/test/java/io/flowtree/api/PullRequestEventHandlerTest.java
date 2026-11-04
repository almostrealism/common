/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.flowtree.controller.ActiveJob;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.slack.NotifierRegistry.BranchResolution;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link PullRequestEventHandler}: a terminal pull request event
 * cancels the in-flight work on its branch, but only the work the controller
 * accepted before the event.
 */
public class PullRequestEventHandlerTest extends TestSuiteBase {

	/** The head branch whose pull request event drives the cancellation. */
	private static final String BRANCH = "feature/example-branch";

	/** The head repository URL used to disambiguate the branch. */
	private static final String REPO = "https://github.com/almostrealism/common.git";

	/** The workstream the test resolver maps {@link #BRANCH} to. */
	private static final String WORKSTREAM = "ws-1";

	/** The ambiguity error the ambiguous-resolution handler reports. */
	private static final String AMBIGUOUS = "Ambiguous workstream resolution: 2 workstreams"
			+ " share defaultBranch '" + BRANCH + "'";

	/** Records the terminal events the handler dispatches. */
	private final List<JobCompletionEvent> dispatched = new ArrayList<>();

	/**
	 * Creates and initialises a store backed by a fresh temporary database.
	 *
	 * @return the initialised store
	 * @throws Exception if the temporary directory cannot be created
	 */
	private JobStatsStore newStore() throws Exception {
		File tempDir = Files.createTempDirectory("pr-event-test").toFile();
		tempDir.deleteOnExit();
		JobStatsStore store = new JobStatsStore(new File(tempDir, "stats").getAbsolutePath());
		store.initialize();
		return store;
	}

	/**
	 * Creates a handler over {@code store} that resolves {@link #BRANCH} on
	 * {@link #REPO} to {@link #WORKSTREAM} and records what it dispatches.
	 *
	 * @param store the store to query
	 * @return the handler
	 */
	private PullRequestEventHandler newHandler(JobStatsStore store) {
		return new PullRequestEventHandler(store,
				(branch, repoUrl) -> BRANCH.equals(branch)
						? BranchResolution.of(new Workstream(WORKSTREAM, "C1", "#example"))
						: BranchResolution.none(),
				(ws, event) -> dispatched.add(event));
	}

	/**
	 * Creates a handler over {@code store} whose resolver reports every branch
	 * as ambiguous, as the notifier registry does when several workstreams on
	 * different repositories share the branch and no repository URL is given.
	 *
	 * @param store the store to query
	 * @return the handler
	 */
	private PullRequestEventHandler newAmbiguousHandler(JobStatsStore store) {
		return new PullRequestEventHandler(store,
				(branch, repoUrl) -> BranchResolution.failed(AMBIGUOUS),
				(ws, event) -> dispatched.add(event));
	}

	/**
	 * A job running on the branch when its pull request merges is cancelled, and
	 * the synthesized event is a {@code CANCELLED} naming the merge.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void runningJobIsCancelledOnMerge() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobStarted("job-impl", WORKSTREAM, "Implement the plan",
				event.minus(Duration.ofHours(1)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, BRANCH, "123", true, event);

		assertEquals("the running job should be cancelled", 1, cancelled.size());
		assertEquals("job-impl", cancelled.get(0));
		assertEquals("one terminal event dispatched", 1, dispatched.size());
		Assert.assertEquals("a cancelled job is CANCELLED, not FAILED",
				JobCompletionEvent.Status.CANCELLED, dispatched.get(0).getStatus());
		assertTrue("the reason names the merge event: " + dispatched.get(0).getErrorMessage(),
				dispatched.get(0).getErrorMessage().contains("merged")
						&& dispatched.get(0).getErrorMessage().contains("123"));
		store.close();
	}

	/**
	 * A pending job submitted before the branch's pull request closes without a
	 * merge is cancelled too, and the reason names the close rather than a merge.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void pendingJobIsCancelledOnCloseWithoutMerge() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobSubmitted("job-queued", WORKSTREAM, "waiting for a worker",
				"ci", null, event.minus(Duration.ofMinutes(10)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, BRANCH, "124", false, event);

		assertEquals("the pending job should be cancelled", 1, cancelled.size());
		assertEquals("job-queued", cancelled.get(0));
		Assert.assertEquals("a cancelled job is CANCELLED", JobCompletionEvent.Status.CANCELLED,
				dispatched.get(0).getStatus());
		assertTrue("the reason names the close, not a merge: " + dispatched.get(0).getErrorMessage(),
				dispatched.get(0).getErrorMessage().contains("closed"));
		assertFalse("a close without merge must not read as merged",
				dispatched.get(0).getErrorMessage().contains("merged"));
		store.close();
	}

	/**
	 * A follow-up the owner submits on the same branch after the event is left
	 * untouched: its submission time is after the cut-off, so it is not cancelled.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void jobSubmittedAfterTheEventIsNotCancelled() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobSubmitted("job-followup", WORKSTREAM, "owner's follow-up",
				"owner", null, event.plus(Duration.ofMinutes(5)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, BRANCH, "123", true, event);

		assertTrue("a job submitted after the event must not be cancelled", cancelled.isEmpty());
		assertTrue("nothing should be dispatched", dispatched.isEmpty());
		store.close();
	}

	/**
	 * A job the controller accepted before the event but that a worker picked up
	 * only after it is still cancelled: the cut-off reads the submission time,
	 * not the start time.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void jobSubmittedBeforeButStartedAfterTheEventIsCancelled() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobSubmitted("job-late-start", WORKSTREAM, "picked up late",
				"ci", null, event.minus(Duration.ofMinutes(5)));
		store.recordJobStarted("job-late-start", WORKSTREAM, "picked up late",
				event.plus(Duration.ofMinutes(2)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, BRANCH, "125", true, event);

		Assert.assertEquals("a job submitted before the event is cancelled even if it started after",
				List.of("job-late-start"), cancelled);
		assertEquals("one terminal event dispatched", 1, dispatched.size());
		Assert.assertEquals("the stored record is CANCELLED", JobCompletionEvent.Status.CANCELLED,
				store.getJob("job-late-start").getStatus());
		store.close();
	}

	/**
	 * A running job submitted after the event is left untouched even though its
	 * start time, if it were the cut-off, would also be after the event — and a
	 * running job submitted before it alongside is cancelled.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void runningJobSubmittedAfterTheEventIsNotCancelled() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobSubmitted("job-old", WORKSTREAM, "before", "ci", null,
				event.minus(Duration.ofHours(2)));
		store.recordJobStarted("job-old", WORKSTREAM, "before", event.minus(Duration.ofHours(1)));
		store.recordJobSubmitted("job-new", WORKSTREAM, "after", "owner", null,
				event.plus(Duration.ofMinutes(1)));
		store.recordJobStarted("job-new", WORKSTREAM, "after", event.plus(Duration.ofMinutes(3)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, BRANCH, "126", true, event);

		Assert.assertEquals("only the job submitted before the event is cancelled",
				List.of("job-old"), cancelled);
		Assert.assertEquals("the follow-up keeps running", 1, store.getActiveJobs(WORKSTREAM).size());
		store.close();
	}

	/**
	 * A job that completes on its own between the handler's in-flight snapshot
	 * and its cancellation keeps its real terminal record, and no cancellation
	 * event is dispatched for it.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void jobCompletingAfterTheSnapshotIsNotCancelled() throws Exception {
		File tempDir = Files.createTempDirectory("pr-event-race").toFile();
		tempDir.deleteOnExit();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		JobStatsStore store = new JobStatsStore(new File(tempDir, "stats").getAbsolutePath()) {
			@Override
			public synchronized List<ActiveJob> getActiveJobs(String workstreamId) {
				List<ActiveJob> snapshot = super.getActiveJobs(workstreamId);
				// The job finishes naturally right after the snapshot is taken.
				recordJobCompleted(WORKSTREAM, JobCompletionEvent.success("job-racing", "racing"));
				return snapshot;
			}
		};
		store.initialize();
		store.recordJobStarted("job-racing", WORKSTREAM, "racing", event.minus(Duration.ofHours(1)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, BRANCH, "127", true, event);

		assertTrue("a job that completed on its own is not cancelled", cancelled.isEmpty());
		assertTrue("no duplicate completion is dispatched", dispatched.isEmpty());
		Assert.assertEquals("the job keeps its own terminal status", JobCompletionEvent.Status.SUCCESS,
				store.getJob("job-racing").getStatus());
		store.close();
	}

	/**
	 * When no workstream tracks the branch there is nothing to cancel, and the
	 * handler dispatches nothing rather than erroring.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void unknownBranchCancelsNothing() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobStarted("job-impl", WORKSTREAM, "Implement the plan",
				event.minus(Duration.ofHours(1)));

		List<String> cancelled = newHandler(store)
				.cancelInFlightJobs(REPO, "some/other-branch", "900", true, event);

		assertTrue("an unresolved branch cancels nothing", cancelled.isEmpty());
		assertTrue("nothing dispatched for an unresolved branch", dispatched.isEmpty());
		store.close();
	}

	/**
	 * The JSON entry point parses the request body and reports the cancelled
	 * job ids and their count.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void handleJsonParsesBodyAndReportsCount() throws Exception {
		JobStatsStore store = newStore();
		store.recordJobStarted("job-impl", WORKSTREAM, "Implement the plan",
				Instant.parse("2026-11-01T16:12:00Z"));

		String body = "{\"headBranch\":\"" + BRANCH + "\",\"repoUrl\":\"" + REPO
				+ "\",\"prNumber\":\"123\",\"merged\":true,"
				+ "\"eventTime\":\"2026-11-01T17:40:00Z\"}";
		String response = newHandler(store).handleJson(body);

		assertTrue("response reports success: " + response, response.contains("\"ok\":true"));
		assertTrue("response reports the count: " + response, response.contains("\"count\":1"));
		assertTrue("response names the cancelled job: " + response, response.contains("job-impl"));
		store.close();
	}

	/**
	 * A request missing the required head branch is rejected without cancelling
	 * anything.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void handleJsonRejectsMissingBranch() throws Exception {
		JobStatsStore store = newStore();
		String response = newHandler(store).handleJson("{\"merged\":true}");
		Assert.assertTrue("a missing headBranch is rejected: " + response,
				response.contains("\"ok\":false"));
		store.close();
	}

	/**
	 * An ambiguous branch resolution is a failure, not an empty success: no job
	 * is cancelled, nothing is dispatched, and the caller receives the
	 * resolution error so it can tell this apart from "nothing was in flight".
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void ambiguousBranchIsRejectedWithoutCancelling() throws Exception {
		JobStatsStore store = newStore();
		Instant event = Instant.parse("2026-11-01T17:40:00Z");
		store.recordJobStarted("job-impl", WORKSTREAM, "Implement the plan",
				event.minus(Duration.ofHours(1)));

		try {
			newAmbiguousHandler(store).cancelInFlightJobs(null, BRANCH, "123", true, event);
			Assert.fail("an ambiguous branch must not be reported as a successful cancellation");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(AMBIGUOUS, e.getMessage());
		}
		assertTrue("nothing dispatched for an ambiguous branch", dispatched.isEmpty());
		assertEquals("the running job is still in flight", 1,
				store.getActiveJobs(WORKSTREAM).size());
		store.close();
	}

	/**
	 * The JSON entry point reports an ambiguous branch as {@code ok:false}
	 * carrying the resolution error, rather than {@code ok:true} with a zero
	 * count.
	 *
	 * @throws Exception if the temporary store cannot be created
	 */
	@Test(timeout = 60000)
	public void handleJsonReportsAmbiguousBranchAsError() throws Exception {
		JobStatsStore store = newStore();
		store.recordJobStarted("job-impl", WORKSTREAM, "Implement the plan",
				Instant.parse("2026-11-01T16:12:00Z"));

		String response = newAmbiguousHandler(store).handleJson("{\"headBranch\":\"" + BRANCH
				+ "\",\"merged\":true,\"eventTime\":\"2026-11-01T17:40:00Z\"}");

		assertTrue("an ambiguous branch is a failure: " + response,
				response.contains("\"ok\":false"));
		assertTrue("the response carries the resolution error: " + response,
				response.contains("Ambiguous workstream resolution"));
		assertFalse("no count is reported for a failed resolution: " + response,
				response.contains("\"count\""));
		assertTrue("nothing dispatched", dispatched.isEmpty());
		store.close();
	}
}
