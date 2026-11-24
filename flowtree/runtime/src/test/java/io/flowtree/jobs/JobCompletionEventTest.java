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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link JobCompletionEvent} covering factory methods,
 * builder-pattern setters, default values, and toString output.
 */
public class JobCompletionEventTest extends TestSuiteBase {

	/** Verifies that a success event is created with the SUCCESS status. */
	@Test(timeout = 30000)
	public void successEventHasCorrectStatus() {
		JobCompletionEvent event = JobCompletionEvent.success("job-1", "Test job");
		assertEquals(JobCompletionEvent.Status.SUCCESS, event.getStatus());
	}

	/**
	 * {@link JobCompletionEvent#deliveredForPullRequest()} — the predicate the
	 * controller's auto-PR gate uses to decide whether a DEGRADED job still
	 * opens its pull request — is true exactly when a primary commit exists,
	 * and false when neither a commit nor a dependent-repo push does.
	 */
	@Test(timeout = 30000)
	public void deliveredForPullRequestFollowsCommitPresence() {
		JobCompletionEvent withCommit = new JobCompletionEvent(
				"job-d", JobCompletionEvent.Status.DEGRADED, "killed but productive");
		withCommit.withGitInfo("feature/z", "mainline-ref", List.of("A.java"), List.of(), true);
		assertTrue("a commit must count as delivered", withCommit.deliveredForPullRequest());

		JobCompletionEvent nothing = new JobCompletionEvent(
				"job-e", JobCompletionEvent.Status.DEGRADED, "nothing committed");
		nothing.withGitInfo("feature/z", null, List.of(), List.of("A.java"), false);
		assertFalse("no commit and no dependent push is not delivered",
				nothing.deliveredForPullRequest());
	}

	/**
	 * A DEGRADED event's reason travels in {@code errorMessage} exactly as a
	 * FAILED event's does, and {@link JobCompletionEvent#fromStatusBody} must
	 * restore it on the controller side. Before the fix the reconstruction read
	 * {@code errorMessage} only for FAILED, so a signal-killed-but-productive
	 * primary's degraded reason — the very thing this change reports — was lost.
	 */
	@Test(timeout = 30000)
	public void fromStatusBodyRestoresErrorMessageForDegraded() {
		JobCompletionEvent original = JobCompletionEvent.degraded(
				"job-deg", "killed but productive",
				"Primary session was killed by external SIGTERM after running 1m 30s");
		original.withGitInfo("feature/z", "mainline-ref", List.of("A.java"), List.of(), true);

		JobCompletionEvent rebuilt = JobCompletionEvent.fromStatusBody(
				"job-deg", JobCompletionEvent.Status.DEGRADED, "killed but productive",
				original.toJson());

		assertEquals(JobCompletionEvent.Status.DEGRADED, rebuilt.getStatus());
		assertEquals(original.getErrorMessage(), rebuilt.getErrorMessage());
	}

	/**
	 * The auto-publish-when-degraded marker survives a {@code toJson()} ->
	 * {@link JobCompletionEvent#fromStatusBody} round trip, so the controller's
	 * reconstructed event knows the grader opted this degraded outcome into the
	 * auto-pull-request path.
	 */
	@Test(timeout = 30000)
	public void autoPublishWhenDegradedSurvivesRoundTrip() {
		JobCompletionEvent original = JobCompletionEvent.degraded(
				"job-ap", "killed but productive", "killed after 1m")
				.withAutoPublishWhenDegraded(true);
		original.withGitInfo("feature/z", "mainline-ref", List.of("A.java"), List.of(), true);

		JobCompletionEvent rebuilt = JobCompletionEvent.fromStatusBody(
				"job-ap", JobCompletionEvent.Status.DEGRADED, "killed but productive",
				original.toJson());

		assertTrue("the auto-publish marker must survive reconstruction",
				rebuilt.isAutoPublishWhenDegraded());
	}

	/**
	 * {@link JobCompletionEvent#shouldAutoPublishDegraded()} — the gate the
	 * controller uses for a DEGRADED event — is true only when the grader marked
	 * the outcome auto-publishable AND it delivered something to open a PR for.
	 * An unmarked degraded event with a commit (an abandoned test run, an
	 * exhausted post-completion gate) is NOT published, and a marked event with
	 * no delivery is not either.
	 */
	@Test(timeout = 30000)
	public void shouldAutoPublishDegradedRequiresMarkAndDelivery() {
		JobCompletionEvent marked = new JobCompletionEvent(
				"job-k", JobCompletionEvent.Status.DEGRADED, "killed but productive")
				.withAutoPublishWhenDegraded(true);
		marked.withGitInfo("feature/z", "mainline-ref", List.of("A.java"), List.of(), true);
		assertTrue("marked and delivered must publish", marked.shouldAutoPublishDegraded());

		JobCompletionEvent unmarkedWithCommit = new JobCompletionEvent(
				"job-u", JobCompletionEvent.Status.DEGRADED, "abandoned test run");
		unmarkedWithCommit.withGitInfo("feature/z", "mainline-ref", List.of("A.java"), List.of(), true);
		assertFalse("an unmarked degraded reason must not publish even with a commit",
				unmarkedWithCommit.shouldAutoPublishDegraded());

		JobCompletionEvent markedNoDelivery = new JobCompletionEvent(
				"job-n", JobCompletionEvent.Status.DEGRADED, "killed, nothing committed")
				.withAutoPublishWhenDegraded(true);
		markedNoDelivery.withGitInfo("feature/z", null, List.of(), List.of(), false);
		assertFalse("a marked event with nothing delivered must not publish",
				markedNoDelivery.shouldAutoPublishDegraded());
	}

	/** Verifies that a failed event captures the error message and exception. */
	@Test(timeout = 30000)
	public void failedEventCapturesError() {
		RuntimeException cause = new RuntimeException("cause");
		JobCompletionEvent event = JobCompletionEvent.failed("job-2", "Failing job", "msg", cause);

		assertEquals(JobCompletionEvent.Status.FAILED, event.getStatus());
		assertEquals("msg", event.getErrorMessage());
		assertNotNull(event.getException());
		assertEquals(cause, event.getException());
	}

	/** Verifies that withGitInfo sets branch, commit hash, staged files, skipped files, and push flag. */
	@Test(timeout = 30000)
	public void withGitInfoSetsAllFields() {
		List<String> staged = Arrays.asList("file1.java", "file2.java");
		List<String> skipped = Arrays.asList("README.md");

		JobCompletionEvent event = JobCompletionEvent.success("job-3", "Git job")
				.withGitInfo("feature-branch", "abc123", staged, skipped, true);

		assertEquals("feature-branch", event.getTargetBranch());
		assertEquals("abc123", event.getCommitHash());
		assertEquals(staged, event.getStagedFiles());
		assertEquals(skipped, event.getSkippedFiles());
		assertTrue(event.isPushed());
	}

	/**
	 * A successful job whose git info shows no commit, nothing staged and
	 * nothing held back is "without changes"; any one of those, a failure, or
	 * a missing branch makes it not.
	 */
	@Test(timeout = 30000)
	public void successWithoutChangesRequiresAnUntouchedBranch() {
		assertTrue(JobCompletionEvent.success("j", "review")
				.withGitInfo("feature/x", null, null, null, false).isSuccessWithoutChanges());

		assertFalse("a commit is a change", JobCompletionEvent.success("j", "review")
				.withGitInfo("feature/x", "abc", Arrays.asList("A.java"), null, true).isSuccessWithoutChanges());
		assertFalse("a held-back file is a change", JobCompletionEvent.success("j", "review")
				.withGitInfo("feature/x", null, null, Arrays.asList("pom.xml (blocked)"), false)
				.isSuccessWithoutChanges());
		assertFalse("no branch, no review loop", JobCompletionEvent.success("j", "review")
				.withGitInfo(null, null, null, null, false).isSuccessWithoutChanges());
		assertFalse("a failure is not a settled loop", JobCompletionEvent.failed("j", "review", "boom", null)
				.withGitInfo("feature/x", null, null, null, false).isSuccessWithoutChanges());
	}

	/**
	 * {@code isPrimaryPushed()} requires both staged primary files and a push;
	 * {@code hasPushedWork()} is also true when only a dependent repository was
	 * pushed, so a dependent-only job still counts as having pushed work.
	 */
	@Test(timeout = 30000)
	public void pushedWorkPredicatesDistinguishPrimaryFromDependent() {
		JobCompletionEvent primary = JobCompletionEvent.success("j", "primary")
				.withGitInfo("feature/x", "abc", Arrays.asList("A.java"), null, true);
		assertTrue("staged files that were pushed are a primary push", primary.isPrimaryPushed());
		assertTrue(primary.hasPushedWork());

		JobCompletionEvent stagedNotPushed = JobCompletionEvent.success("j", "primary")
				.withGitInfo("feature/x", "abc", Arrays.asList("A.java"), null, false);
		assertFalse("staged but not pushed is not a primary push", stagedNotPushed.isPrimaryPushed());
		assertFalse(stagedNotPushed.hasPushedWork());

		JobCompletionEvent dependentOnly = JobCompletionEvent.success("j", "dependent")
				.withGitInfo("feature/x", null, null, null, false)
				.withDependentRepoPushes(List.of(new DependentRepoPush(
						"https://github.com/acme/lib.git", "feature/x", "abc123")));
		assertFalse("no primary staged files means no primary push", dependentOnly.isPrimaryPushed());
		assertTrue("a dependent push is still pushed work", dependentOnly.hasPushedWork());

		JobCompletionEvent nothing = JobCompletionEvent.success("j", "review")
				.withGitInfo("feature/x", null, null, null, false);
		assertFalse(nothing.isPrimaryPushed());
		assertFalse("a job that pushed nothing has no pushed work", nothing.hasPushedWork());
	}

	/** Verifies that withPullRequestUrl stores the URL on the event. */
	@Test(timeout = 30000)
	public void withPullRequestUrlSetsUrl() {
		JobCompletionEvent event = JobCompletionEvent.success("job-4", "PR job")
				.withPullRequestUrl("https://github.com/org/repo/pull/1");

		assertEquals("https://github.com/org/repo/pull/1", event.getPullRequestUrl());
	}

	/** Verifies that self-notify defaults to false. */
	@Test(timeout = 30000)
	public void selfNotifyDefaultsToFalse() {
		JobCompletionEvent event = JobCompletionEvent.success("job-5", "Shell job");
		assertFalse(event.isSelfNotify());
	}

	/** Verifies that withSelfNotify stores the flag on the event. */
	@Test(timeout = 30000)
	public void withSelfNotifySetsFlag() {
		JobCompletionEvent event = JobCompletionEvent.success("job-6", "Shell job")
				.withSelfNotify(true);
		assertTrue(event.isSelfNotify());
	}

	/** Verifies that toJson serializes the self-notify flag. */
	@Test(timeout = 30000)
	public void toJsonIncludesSelfNotify() {
		JobCompletionEvent event = JobCompletionEvent.success("job-7", "Shell job")
				.withSelfNotify(true);
		assertTrue("toJson output must include the selfNotify flag: " + event.toJson(),
				event.toJson().contains("\"selfNotify\":true"));
	}

	/**
	 * A dependent-repo push survives {@code toJson()} ->
	 * {@link DependentRepoPush#listFromJson(String)} with its URL, branch, and
	 * hash intact, which is how the job's report reaches the controller.
	 */
	@Test(timeout = 30000)
	public void dependentRepoPushesRoundTripThroughJson() {
		DependentRepoPush push = new DependentRepoPush(
				"https://github.com/acme/lib.git", "feature/x", "abc123def456");
		JobCompletionEvent event = JobCompletionEvent.success("job-dep", "Dependent push")
				.withDependentRepoPushes(List.of(push));

		List<DependentRepoPush> parsed = DependentRepoPush.listFromJson(event.toJson());

		assertEquals(1, parsed.size());
		assertEquals("https://github.com/acme/lib.git", parsed.get(0).repoUrl());
		assertEquals("feature/x", parsed.get(0).branch());
		assertEquals("abc123def456", parsed.get(0).commitHash());
	}

	/** A body with no pushes yields an empty list rather than null. */
	@Test(timeout = 30000)
	public void jsonWithoutPushesYieldsEmptyList() {
		JobCompletionEvent event = JobCompletionEvent.success("job-none", "No pushes");

		assertNotNull(DependentRepoPush.listFromJson(event.toJson()));
		assertTrue(DependentRepoPush.listFromJson(event.toJson()).isEmpty());
	}

	/** Verifies that staged and skipped file lists default to non-null empty lists. */
	@Test(timeout = 30000)
	public void defaultListsAreEmpty() {
		JobCompletionEvent event = JobCompletionEvent.success("job-5", "Default lists");

		assertNotNull(event.getStagedFiles());
		assertTrue(event.getStagedFiles().isEmpty());
		assertNotNull(event.getSkippedFiles());
		assertTrue(event.getSkippedFiles().isEmpty());
	}

	/** Verifies that toString includes the job ID, status, and commit hash. */
	@Test(timeout = 30000)
	public void toStringIncludesKeyFields() {
		JobCompletionEvent event = JobCompletionEvent.success("job-6", "ToString job")
				.withGitInfo("main", "def456", null, null, false);

		String str = event.toString();
		assertTrue("toString should contain jobId", str.contains("job-6"));
		assertTrue("toString should contain status name", str.contains("SUCCESS"));
		assertTrue("toString should contain commitHash", str.contains("def456"));
	}

	/** A description within the budget is returned unchanged. */
	@Test(timeout = 30000)
	public void shortDescriptionLeavesAFittingDescriptionAlone() {
		JobCompletionEvent event = JobCompletionEvent.success("job-7", "Short");

		assertEquals("Short", event.shortDescription(5));
		assertEquals("Short", event.shortDescription(80));
	}

	/** A description past the budget is elided with an ellipsis. */
	@Test(timeout = 30000)
	public void shortDescriptionElidesPastTheBudget() {
		JobCompletionEvent event = JobCompletionEvent.success("job-7", "Add alert delivery");

		assertEquals("Add a...", event.shortDescription(8));
	}

	/**
	 * A budget too small to hold the ellipsis truncates plainly instead of
	 * throwing. The budget comes from whatever channel is reporting the job,
	 * so an awkward one is a reason to shorten differently.
	 */
	@Test(timeout = 30000)
	public void shortDescriptionSurvivesABudgetSmallerThanTheEllipsis() {
		JobCompletionEvent event = JobCompletionEvent.success("job-7", "Add alert delivery");

		assertEquals("A", event.shortDescription(1));
		assertEquals("Ad", event.shortDescription(2));
		assertEquals("Add", event.shortDescription(3));
	}

	/** A budget of zero or less yields an empty string, never an exception. */
	@Test(timeout = 30000)
	public void shortDescriptionSurvivesANonPositiveBudget() {
		JobCompletionEvent event = JobCompletionEvent.success("job-7", "Add alert delivery");

		assertEquals("", event.shortDescription(0));
		assertEquals("", event.shortDescription(-1));
	}

	/** A missing description yields an empty string rather than null. */
	@Test(timeout = 30000)
	public void shortDescriptionOfAMissingDescriptionIsEmpty() {
		JobCompletionEvent event = JobCompletionEvent.success("job-7", null);

		assertEquals("", event.shortDescription(80));
		assertEquals("", event.shortDescription(1));
	}

	/** Only positively-costed runners survive the positive-cost filter. */
	@Test(timeout = 30000)
	public void positiveCostByRunnerExcludesZeroAndNegativeRunners() {
		JobCompletionEvent event = JobCompletionEvent.success("job-8", "Mixed costs");
		Map<String, Double> costs = new HashMap<>();
		costs.put("claude", 1.25);
		costs.put("free-runner", 0.0);
		costs.put("refunded", -0.5);
		event.setCostByRunner(costs);

		Map<String, Double> positive = event.getPositiveCostByRunner();
		assertEquals(1, positive.size());
		assertEquals(Double.valueOf(1.25), positive.get("claude"));
		assertFalse(positive.containsKey("free-runner"));
		assertFalse(positive.containsKey("refunded"));
	}

	/** Percentages are zero, not a divide-by-zero, when the denominator is absent. */
	@Test(timeout = 30000)
	public void apiAndCostPercentAreZeroWhenNoWallTimeOrCost() {
		JobCompletionEvent event = JobCompletionEvent.success("job-9", "No metrics");

		assertEquals(0, event.getApiTimePercent());
		assertEquals(0, event.getCostPercent(5.0));
	}
}
