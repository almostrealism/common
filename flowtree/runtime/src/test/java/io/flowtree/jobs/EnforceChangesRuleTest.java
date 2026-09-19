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

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link EnforceChangesRule}, which must judge "did the agent make
 * a real change" by what would actually survive staging, not by whether the
 * working tree is merely dirty.
 *
 * <p>This pins the fix for the PR #492 regression: a guardrail-doomed file
 * stays uncommitted in the working tree (so {@code git status} is dirty)
 * even though it will never be staged. The old check
 * ({@code hasUncommittedChanges()}) read that dirtiness as "a change
 * happened" and never retried; the fix reads
 * {@link GitManagedJob#previewStaging()} instead, which reports the same
 * verdict {@link GitCommitHandler} will reach.</p>
 */
public class EnforceChangesRuleTest extends TestSuiteBase {

    /** A {@link CodingAgentJob} whose {@link #previewStaging()} result is controlled by the test. */
    private static class StubJob extends CodingAgentJob {
        /** The preview result to return from {@link #previewStaging()}. */
        private final StagingResult preview;

        /**
         * Creates a job that returns {@code preview} from {@link #previewStaging()}.
         *
         * @param preview the staging preview result to return
         */
        StubJob(StagingResult preview) {
            super("t1", "p");
            this.preview = preview;
        }

        @Override
        StagingResult previewStaging() {
            return preview;
        }
    }

    /** buildCorrectionPrompt always returns null: the injected prompt already covers it. */
    @Test(timeout = 30000)
    public void buildCorrectionPromptIsNull() {
        assertNull(new EnforceChangesRule().buildCorrectionPrompt(new StubJob(
                new StagingResult(Collections.emptyList(), Collections.emptyList()))));
    }

    /**
     * The exact PR #492 regression scenario: the working tree has a change
     * (it would show up in {@code git status}), but every one of today's
     * changed files is guardrail-doomed, so nothing would be staged. The
     * rule must be violated -- this is what triggers the retry the old
     * {@code hasUncommittedChanges()}-based check missed.
     */
    @Test(timeout = 30000)
    public void violatedWhenPreviewHasSkipsButNothingStaged() {
        StagingResult allSkipped = new StagingResult(
                Collections.emptyList(),
                Collections.singletonList("FooTest.java (protected - existing test method(s) changed: testFoo)"));
        StubJob job = new StubJob(allSkipped);
        assertTrue(new EnforceChangesRule().isViolated(job));
    }

    /** Not violated once at least one file would actually be staged. */
    @Test(timeout = 30000)
    public void notViolatedWhenSomethingWouldStage() {
        StagingResult someStaged = new StagingResult(
                Collections.singletonList("Fix.java"), Collections.emptyList());
        StubJob job = new StubJob(someStaged);
        assertFalse(new EnforceChangesRule().isViolated(job));
    }

    /** Not violated when there are no changes to the working tree at all. */
    @Test(timeout = 30000)
    public void notViolatedIsStillTrueWhenNoChangesAtAllTriggersViolation() {
        StagingResult empty = new StagingResult(Collections.emptyList(), Collections.emptyList());
        StubJob job = new StubJob(empty);
        assertTrue("No changes at all is still a violation (nothing to enforce)",
                new EnforceChangesRule().isViolated(job));
    }

    /** Not violated once the agent has committed, regardless of the preview. */
    @Test(timeout = 30000)
    public void notViolatedWhenAgentAlreadyCommitted() {
        StagingResult allSkipped = new StagingResult(
                Collections.emptyList(),
                Collections.singletonList("FooTest.java (protected)"));
        StubJob job = new StubJob(allSkipped) {
            @Override
            protected boolean hasAgentCommitted() {
                return true;
            }
        };
        assertFalse(new EnforceChangesRule().isViolated(job));
    }
}
