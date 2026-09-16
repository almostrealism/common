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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link StagingSkipRule}: the mid-session correction that tells
 * the agent, while its session can still react, that a staging guardrail is
 * about to discard one of its changes.
 */
public class StagingSkipRuleTest extends TestSuiteBase {

    /** A {@link CodingAgentJob} whose {@link #previewStaging()} result is controlled by the test. */
    private static class StubJob extends CodingAgentJob {
        /** The preview result to return from {@link #previewStaging()}. */
        private StagingResult preview;

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

    /**
     * Builds a staging preview with one skipped file and nothing staged.
     *
     * @param skippedEntry the "path (reason)" skip entry
     * @return the preview result
     */
    private static StagingResult withSkip(String skippedEntry) {
        return new StagingResult(Collections.emptyList(), Collections.singletonList(skippedEntry));
    }

    /**
     * Builds a staging preview with one staged file and nothing skipped.
     *
     * @return the preview result
     */
    private static StagingResult clean() {
        return new StagingResult(Collections.singletonList("Fix.java"), Collections.emptyList());
    }

    /** isViolated is true whenever the staging preview reports a skipped file. */
    @Test(timeout = 30000)
    public void isViolatedTrueWhenPreviewHasSkips() {
        StubJob job = new StubJob(withSkip("FooTest.java (protected - existing test method(s) changed: testFoo)"));
        assertTrue(new StagingSkipRule().isViolated(job));
    }

    /** isViolated is false when nothing would be skipped. */
    @Test(timeout = 30000)
    public void isViolatedFalseWhenPreviewIsClean() {
        StubJob job = new StubJob(clean());
        assertFalse(new StagingSkipRule().isViolated(job));
    }

    /** isViolated is false once the agent has committed, regardless of the preview. */
    @Test(timeout = 30000)
    public void isViolatedFalseWhenAgentAlreadyCommitted() {
        StubJob job = new StubJob(withSkip("FooTest.java (protected)")) {
            @Override
            protected boolean hasAgentCommitted() {
                return true;
            }
        };
        assertFalse(new StagingSkipRule().isViolated(job));
    }

    /** The correction prompt names every skipped file and its reason. */
    @Test(timeout = 30000)
    public void correctionPromptListsSkippedFilesAndReasons() {
        StubJob job = new StubJob(withSkip("src/test/java/FooTest.java (protected - existing test method(s) changed or removed: testFoo)"));
        String prompt = new StagingSkipRule().buildCorrectionPrompt(job);
        assertTrue(prompt.contains("src/test/java/FooTest.java"));
        assertTrue(prompt.contains("testFoo"));
        assertTrue("Prompt must tell the agent it can report a human is needed",
                prompt.contains("human needs to intervene"));
    }

    /** No correction prompt is built when nothing was skipped. */
    @Test(timeout = 30000)
    public void correctionPromptNullWhenNothingSkipped() {
        StubJob job = new StubJob(clean());
        assertNull(new StagingSkipRule().buildCorrectionPrompt(job));
    }

    /**
     * End-to-end through {@link CodingAgentJob#runEnforcementRules()}: a
     * skip during the first preview must trigger a correction session that
     * names the skipped file, and once the "agent" (the stub) resolves it,
     * enforcement must stop retrying.
     */
    @Test(timeout = 30000)
    public void enforcementRunnerDrivesACorrectionSessionOnSkip() {
        // Filtered to this rule's own activity tag: a git-enabled job also
        // activates CommitMessageRule, whose own (unrelated) correction
        // sessions are not what this test is about.
        List<String> stagingSkipPrompts = new ArrayList<>();

        CodingAgentJob job = new CodingAgentJob("t1", "p") {
            private boolean resolved = false;

            @Override
            StagingResult previewStaging() {
                return resolved ? clean() : withSkip("FooTest.java (protected - existing test method(s) changed: testFoo)");
            }

            @Override
            protected void runCorrectionSession(String correctionPrompt, String activity) {
                if ("staging-skip".equals(activity)) {
                    stagingSkipPrompts.add(correctionPrompt);
                    resolved = true;
                }
            }
        };
        // StagingSkipRule is only assembled into the active rule set for
        // git-enabled jobs -- see EnforcementRunner.buildActiveRules().
        job.setTargetBranch("feature/test");
        job.setEnforceOrganizationalPlacement(false);

        job.runEnforcementRules();

        assertEquals("Exactly one staging-skip correction session should have run",
                1, stagingSkipPrompts.size());
        assertTrue(stagingSkipPrompts.get(0).contains("FooTest.java"));
    }
}
