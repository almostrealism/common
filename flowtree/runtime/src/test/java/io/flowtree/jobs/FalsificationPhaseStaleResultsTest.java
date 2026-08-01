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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * Regression tests proving that {@link FalsificationPhase#analyze} cannot
 * act on a stale {@code falsification-results.json} left by a prior
 * iteration or session.
 *
 * <p>The fix under test deletes the results file <em>before</em> dispatching
 * the analysis session, so a session that crashes or times out without writing
 * a fresh file cannot mislead {@code classifyResults()} into parsing outdated
 * data. A missing results file is the correct safe signal that analysis did
 * not complete, and must produce no assessments and therefore no bounce.</p>
 */
public class FalsificationPhaseStaleResultsTest extends TestSuiteBase {

    /**
     * A {@link CodingAgentJob} whose {@link #executeSingleRun()} is a no-op,
     * simulating a session that completes without writing a results file.
     */
    private static final class NoOpJob extends CodingAgentJob {

        /** Creates a minimal no-op job with a fixed task id and prompt. */
        NoOpJob() {
            super("stale-test", "probe the claims");
        }

        /** Does not dispatch a real agent; simulates a session that writes no results file. */
        @Override
        void executeSingleRun() {
            // intentionally empty
        }
    }

    /**
     * A results file with at least one parseable claim entry, present to
     * simulate stale output from a prior analysis run.
     */
    private static String staleResultsJson() {
        return "{\"claims\":[{"
                + "\"text\":\"stale claim from prior run\","
                + "\"facet\":\"RUNTIME_BEHAVIOUR\","
                + "\"dependentHunk\":\"SomeClass.java — the hunk\","
                + "\"decisionConfiguration\":\"\","
                + "\"truthCondition\":null,"
                + "\"artifacts\":[]"
                + "}]}";
    }

    /** Recursively deletes a temporary directory, best-effort. */
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * A stale {@code falsification-results.json} from a prior run must NOT be
     * parsed as fresh output. The fix deletes the file before dispatching the
     * analysis session; a no-op session then produces no file, so
     * {@code classifyResults()} returns empty — no bounce is triggered.
     *
     * <p>Without the fix, the stale file would be parsed and the test would
     * return a non-empty assessment list, proving the regression is caught.</p>
     */
    @Test(timeout = 30000)
    public void staleResultsFileDoesNotDriveAssessments() throws IOException {
        Path tempDir = Files.createTempDirectory("falsification-stale");
        try {
            NoOpJob job = new NoOpJob();
            job.setWorkingDirectory(tempDir.toString());

            // Plant a stale results file that would parse into a non-empty assessment.
            Path staleFile = tempDir.resolve(FalsificationPhase.RESULTS_FILE);
            Files.writeString(staleFile, staleResultsJson());
            assertTrue("Precondition: stale file must exist before analyze()",
                    Files.exists(staleFile));

            FalsificationPhase phase = new FalsificationPhase();
            List<ClaimAssessment> assessments = phase.analyze(job);

            // The stale file was deleted before executeSingleRun(); no-op session
            // wrote nothing; classifyResults() found no file — result must be empty.
            assertTrue("Stale results file must be cleared before dispatch; "
                    + "no assessments should be produced from prior-run data",
                    assessments.isEmpty());
        } finally {
            deleteTree(tempDir);
        }
    }

    /**
     * When no results file exists at all (clean start or a session that never
     * wrote one), {@code analyze()} must return an empty assessment list — the
     * absence of a file is a safe no-op, not an error or a bounce trigger.
     */
    @Test(timeout = 30000)
    public void missingResultsFileProducesNoAssessmentsAndDoesNotBounce() throws IOException {
        Path tempDir = Files.createTempDirectory("falsification-missing");
        try {
            NoOpJob job = new NoOpJob();
            job.setWorkingDirectory(tempDir.toString());

            // No results file is planted; the no-op session also writes none.
            FalsificationPhase phase = new FalsificationPhase();
            List<ClaimAssessment> assessments = phase.analyze(job);

            assertTrue("A missing results file must produce no assessments",
                    assessments.isEmpty());
        } finally {
            deleteTree(tempDir);
        }
    }
}
