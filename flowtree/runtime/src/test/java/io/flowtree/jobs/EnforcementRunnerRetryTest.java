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

import io.flowtree.jobs.agent.Phase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the enforce-changes control flow in {@link EnforcementRunner}.
 *
 * <p>Prior to the fix a merge conflict resolution dropped the master-branch change
 * that skipped {@code setCurrentActivity("enforce-changes")} before calling
 * {@code executeSingleRun()}.  As a result the enforcement retry routed to the
 * deprecated {@link Phase#ENFORCE_CHANGES} instead of staying on
 * {@link Phase#PRIMARY}, allowing a different runner/model to handle the retry
 * than the one that ran the original primary session.</p>
 *
 * <p>These tests verify the corrected behaviour: enforce-changes retries keep
 * {@code currentActivity} null so {@link CodingAgentJob#resolveCurrentPhase()}
 * returns {@link Phase#PRIMARY}.</p>
 */
public class EnforcementRunnerRetryTest extends TestSuiteBase {

	/**
	 * A {@link CodingAgentJob} spy that tracks whether the primary run committed
	 * changes.  Subclasses override {@link #executeSingleRun()} to record
	 * observations, calling {@link #trackChange()} to signal that changes were made.
	 */
	private abstract static class TrackingJob extends CodingAgentJob {
		private boolean hasChanges = false;

		TrackingJob() { super("t1", "do the work"); }

		@Override boolean hasUncommittedChanges() { return hasChanges; }

		/** Marks the job as having uncommitted changes (call from {@link #executeSingleRun()}). */
		protected void trackChange() { hasChanges = true; }
	}

	/**
	 * Creates a {@link TrackingJob} whose {@link CodingAgentJob#executeSingleRun()}
	 * appends the current phase to {@code out} and then records a change.
	 *
	 * <p>Shared by {@link #enforceChangesRetryResolvesToPrimaryPhase()} and
	 * {@link #deprecatedEnforceChangesPhaseIsNeverDispatched()}, which test the same
	 * invariant from complementary angles (positive and negative assertion).</p>
	 */
	private CodingAgentJob phaseCapturingJob(List<Phase> out) {
		return new TrackingJob() {
			@Override
			void executeSingleRun() {
				out.add(resolveCurrentPhase());
				trackChange();
			}
		};
	}

	/**
	 * When {@code enforce_changes=true} and the primary run produces no changes,
	 * the enforcement retry must invoke {@code executeSingleRun()} with
	 * {@code currentActivity} still {@code null} so that
	 * {@link CodingAgentJob#resolveCurrentPhase()} returns {@link Phase#PRIMARY}.
	 *
	 * <p>Regression for: merge silently dropped the fix that wrapped
	 * {@code setCurrentActivity(rule.getName())} in
	 * {@code if (!"enforce-changes".equals(rule.getName()))}, causing the retry to
	 * route to the deprecated {@link Phase#ENFORCE_CHANGES} phase instead.</p>
	 */
	@Test(timeout = 30000)
	public void enforceChangesRetryKeepsCurrentActivityNull() {
		AtomicReference<String> activityDuringRetry = new AtomicReference<>("NOT_CAPTURED");
		AtomicInteger retryCount = new AtomicInteger();

		CodingAgentJob job = new TrackingJob() {
			@Override
			void executeSingleRun() {
				activityDuringRetry.set(getCurrentActivity());
				retryCount.incrementAndGet();
				trackChange();
			}
		};

		job.setEnforceChanges(true);
		job.setEnforceOrganizationalPlacement(false);

		job.runEnforcementRules();

		assertTrue("Expected at least one enforce-changes retry", retryCount.get() >= 1);
		assertNull("enforce-changes retry must keep currentActivity null (PRIMARY routing), "
				+ "but was: " + activityDuringRetry.get(),
				activityDuringRetry.get());
	}

	/**
	 * An opencode PRIMARY no-op with {@code enforce_changes=true} must trigger
	 * a PRIMARY retry — never the deprecated {@link Phase#ENFORCE_CHANGES} phase.
	 *
	 * <p>Verifies that every {@code executeSingleRun()} call during the enforce-changes
	 * retry loop resolves to {@link Phase#PRIMARY}.</p>
	 */
	@Test(timeout = 30000)
	public void enforceChangesRetryResolvesToPrimaryPhase() {
		List<Phase> observedPhases = new ArrayList<>();
		CodingAgentJob job = phaseCapturingJob(observedPhases);

		job.setEnforceChanges(true);
		job.setEnforceOrganizationalPlacement(false);

		job.runEnforcementRules();

		assertTrue("Expected at least one enforce-changes retry", observedPhases.size() >= 1);
		for (Phase observed : observedPhases) {
			assertEquals(Phase.PRIMARY, observed);
		}
	}

	/**
	 * Confirms that {@link Phase#ENFORCE_CHANGES} is never dispatched during
	 * the enforce-changes retry loop.  The deprecated phase must not appear in
	 * status messages or runner lookups.
	 *
	 * <p>Complements {@link #enforceChangesRetryResolvesToPrimaryPhase()} by
	 * asserting the negative: the deprecated value is absent from all observed
	 * phases, not just that the first one is PRIMARY.</p>
	 */
	@Test(timeout = 30000)
	public void deprecatedEnforceChangesPhaseIsNeverDispatched() {
		List<Phase> observedPhases = new ArrayList<>();
		CodingAgentJob job = phaseCapturingJob(observedPhases);

		job.setEnforceChanges(true);
		job.setEnforceOrganizationalPlacement(false);

		job.runEnforcementRules();

		for (Phase observed : observedPhases) {
			if (Phase.ENFORCE_CHANGES == observed) {
				throw new AssertionError(
						"Deprecated Phase.ENFORCE_CHANGES was dispatched during enforce-changes retry. "
								+ "The retry must stay on PRIMARY so the same runner/model "
								+ "handles the correction as handled the original primary session.");
			}
		}
	}

	/**
	 * The enforce-changes enforcement attempt counter must be incremented
	 * exactly once per retry, and the increment must happen before
	 * {@code executeSingleRun()} is called so the retry session can log
	 * the correct attempt number.
	 */
	@Test(timeout = 30000)
	public void enforceChangesRetryIncrementsAttemptCounterBeforeRun() {
		List<Integer> observedAttempts = new ArrayList<>();

		CodingAgentJob job = new TrackingJob() {
			@Override
			void executeSingleRun() {
				observedAttempts.add(getEnforcementAttempt());
				trackChange();
			}
		};

		job.setEnforceChanges(true);
		job.setEnforceOrganizationalPlacement(false);

		job.runEnforcementRules();

		assertTrue("Expected at least one enforce-changes retry", observedAttempts.size() >= 1);
		assertEquals("enforcementAttempt must be 1 on the first retry (incremented before run)",
				1, (int) observedAttempts.get(0));
	}

	/**
	 * When {@code currentActivity} is {@code null}, {@link CodingAgentJob#resolveCurrentPhase()}
	 * must return {@link Phase#PRIMARY}.  Verifies the routing contract that the
	 * enforce-changes retry relies on.
	 */
	@Test(timeout = 30000)
	public void resolveCurrentPhaseReturnsPrimaryWhenActivityIsNull() {
		CodingAgentJob job = new CodingAgentJob("t1", "do the work");
		assertEquals(Phase.PRIMARY, job.resolveCurrentPhase());
	}

	/**
	 * Correction sessions for rules other than enforce-changes (e.g. a custom rule
	 * that returns null from {@code buildCorrectionPrompt}) must still tag the
	 * activity, allowing per-phase runner routing for those rules.
	 *
	 * <p>This confirms the fix is scoped: only enforce-changes skips
	 * {@code setCurrentActivity}; other null-prompt rules keep their existing
	 * activity-tagging behaviour.</p>
	 */
	@Test(timeout = 30000)
	public void otherNullPromptRulesStillTagActivity() {
		AtomicReference<String> activityDuringRun = new AtomicReference<>();

		EnforcementRule nullPromptRule = EnforcementRule.singleFire("custom-null-prompt", null);

		CodingAgentJob job = new CodingAgentJob("t1", "do the work") {
			@Override
			void executeSingleRun() {
				activityDuringRun.set(getCurrentActivity());
			}
		};

		job.setEnforceOrganizationalPlacement(false);
		job.addEnforcementRule(nullPromptRule);

		job.runEnforcementRules();

		assertEquals("custom-null-prompt", activityDuringRun.get());
	}
}
