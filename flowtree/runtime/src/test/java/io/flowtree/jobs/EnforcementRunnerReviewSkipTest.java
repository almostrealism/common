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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the no-change review skip: a primary phase
 * that left the working tree unchanged produces nothing for the second-pass
 * review to read, so {@link EnforcementRunner} skips the {@code review} rule
 * rather than run a full review session against an untouched tree.
 */
public class EnforcementRunnerReviewSkipTest extends TestSuiteBase {

    /**
     * A job whose change signals are fixed and whose correction sessions are
     * recorded instead of launched. Only the {@code review} rule is active:
     * enforce-changes, organizational placement and the object-oriented review
     * are off, and no target branch means no staging / commit-message rules.
     */
    private static final class SpyJob extends CodingAgentJob {
        /** Whether the primary left uncommitted changes in the tree. */
        private final boolean changed;
        /** Rule names whose correction session the runner invoked. */
        private final List<String> correctionRules = new ArrayList<>();

        /**
         * Creates a spy whose primary either changed the tree or did not.
         *
         * @param changed whether the tree has uncommitted changes
         */
        SpyJob(boolean changed) {
            super("t1", "review the branch");
            this.changed = changed;
            setWorkingDirectory("/tmp/review-skip-test");
            setEnforceChanges(false);
            setEnforceOrganizationalPlacement(false);
            setObjectOrientedReviewEnabled(false);
        }

        @Override boolean hasUncommittedChanges() { return changed; }

        @Override List<String> extractNewFilePaths() { return Collections.emptyList(); }

        @Override List<String> extractChangedFilePaths() { return Collections.emptyList(); }

        @Override
        protected void runCorrectionSession(String prompt, String ruleName) {
            correctionRules.add(ruleName);
        }

        /** Returns the rule names whose correction session ran. */
        List<String> correctionRules() { return correctionRules; }
    }

    /** The predicate is true for an unchanged tree and false once there are changes. */
    @Test(timeout = 30000)
    public void primaryLeftTreeUnchangedReflectsTheChangeSignals() {
        assertTrue(new EnforcementRunner(new SpyJob(false)).primaryLeftTreeUnchanged());
        assertFalse(new EnforcementRunner(new SpyJob(true)).primaryLeftTreeUnchanged());
    }

    /** With no changes from the primary, no review correction session runs. */
    @Test(timeout = 30000)
    public void reviewIsSkippedWhenPrimaryMadeNoChanges() {
        SpyJob job = new SpyJob(false);
        assertTrue(job.isReviewEnabled());
        job.runEnforcementRules();
        assertFalse("the review session must not run on an unchanged tree",
                job.correctionRules().contains("review"));
    }

    /** When the primary did change the tree, the review session still runs. */
    @Test(timeout = 30000)
    public void reviewStillRunsWhenPrimaryMadeChanges() {
        SpyJob job = new SpyJob(true);
        job.runEnforcementRules();
        assertEquals("the review session must run once when there are changes",
                1, job.correctionRules().stream().filter("review"::equals).count());
    }
}
