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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves that the phase artifacts relocated into {@code .flowtree/} still
 * round-trip: each phase instructs the agent to write its result file under
 * {@code .flowtree/} and reads it back from there. Moving the paths must not
 * break the phases that depend on them.
 */
public class FlowtreeArtifactRoundTripTest extends TestSuiteBase {

    /** Temporary working directory recreated for each test. */
    private Path tempDir;

    /** Creates a fresh temporary directory before each test. */
    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("flowtree-roundtrip");
    }

    /** Recursively deletes the temporary directory after each test. */
    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
            }
        }
    }

    /**
     * A {@link CodingAgentJob} whose {@code executeSingleRun()} simulates the
     * analysis agent by writing {@code content} to {@code relativePath} (relative
     * to the working directory). Used to feed a result file at a chosen location.
     */
    private final class WritingJob extends CodingAgentJob {
        /** Working-directory-relative path the simulated session writes, or {@code null} to write nothing. */
        private final String relativePath;
        /** Content the simulated session writes to {@link #relativePath}. */
        private final String content;

        /** Creates a job whose session writes {@code content} to {@code relativePath}. */
        WritingJob(String relativePath, String content) {
            super("roundtrip", "probe the claims");
            this.relativePath = relativePath;
            this.content = content;
            setWorkingDirectory(tempDir.toString());
        }

        @Override
        void executeSingleRun() {
            if (relativePath == null) return;
            try {
                Path p = tempDir.resolve(relativePath);
                Files.createDirectories(p.getParent());
                Files.writeString(p, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** A single parseable falsification claim. */
    private static String falsificationJson() {
        return "{\"claims\":[{"
                + "\"text\":\"a load-bearing claim\","
                + "\"facet\":\"RUNTIME_BEHAVIOUR\","
                + "\"dependentHunk\":\"Foo.java — the hunk\","
                + "\"decisionConfiguration\":\"\","
                + "\"truthCondition\":null,"
                + "\"artifacts\":[]"
                + "}]}";
    }

    // ── Falsification round-trip ──────────────────────────────────────────────

    /**
     * The falsification phase reads its results back from
     * {@code .flowtree/falsification-results.json}: an analysis session that
     * writes there yields parsed assessments.
     */
    @Test(timeout = 30000)
    public void falsificationReadsResultsFromFlowtreeDirectory() {
        WritingJob job = new WritingJob(
                FlowtreeArtifacts.inDirectory(FalsificationPhase.RESULTS_FILE), falsificationJson());

        List<ClaimAssessment> assessments = new FalsificationPhase().analyze(job);

        assertFalse("Results written under .flowtree/ must be read back as assessments",
                assessments.isEmpty());
    }

    /**
     * The same content written to the OLD root location is ignored — proving the
     * read path genuinely moved to {@code .flowtree/} (fail-WITHOUT contrast).
     */
    @Test(timeout = 30000)
    public void falsificationIgnoresResultsAtLegacyRoot() {
        WritingJob job = new WritingJob(FalsificationPhase.RESULTS_FILE, falsificationJson());

        List<ClaimAssessment> assessments = new FalsificationPhase().analyze(job);

        assertTrue("A results file left at the legacy root must not be read",
                assessments.isEmpty());
    }

    // ── Retrospective round-trip ──────────────────────────────────────────────

    /**
     * The retrospective phase reads its results back from
     * {@code .flowtree/retrospective-results.json}.
     */
    @Test(timeout = 30000)
    public void retrospectiveReadsResultsFromFlowtreeDirectory() {
        String json = "{\"transcriptFound\":true,\"findingsCount\":3,"
                + "\"contextUpfrontTokenEstimate\":1200,\"contextPressureEvents\":2}";
        WritingJob job = new WritingJob(
                FlowtreeArtifacts.inDirectory(RetrospectivePhase.RESULTS_FILE), json);

        RetrospectivePhase phase = new RetrospectivePhase();
        phase.run(job);

        assertTrue("transcriptFound must be read from .flowtree/", phase.transcriptFound());
        assertEquals("findingsCount must be read from .flowtree/", 3, phase.findingsCount());
        assertEquals(1200, phase.contextUpfrontTokenEstimate());
        assertEquals(2, phase.contextPressureEvents());
    }

    /**
     * Retrospective results left at the legacy root are ignored — the read path
     * moved to {@code .flowtree/}.
     */
    @Test(timeout = 30000)
    public void retrospectiveIgnoresResultsAtLegacyRoot() {
        String json = "{\"transcriptFound\":true,\"findingsCount\":9}";
        WritingJob job = new WritingJob(RetrospectivePhase.RESULTS_FILE, json);

        RetrospectivePhase phase = new RetrospectivePhase();
        phase.run(job);

        assertFalse("A root-level results file must not be read", phase.transcriptFound());
        assertEquals("findingsCount must remain at its default when only root is written",
                0, phase.findingsCount());
    }

    // ── Prompt instructions point at .flowtree/ ───────────────────────────────

    /** The falsification analysis prompt instructs the agent to write under {@code .flowtree/}. */
    @Test(timeout = 30000)
    public void falsificationPromptInstructsFlowtreePath() {
        String prompt = FalsificationPromptBuilder.build(new CodingAgentJob("t1", "p"));
        assertTrue("Prompt must instruct the .flowtree/ results path",
                prompt.contains(FlowtreeArtifacts.inDirectory(FalsificationPhase.RESULTS_FILE)));
    }

    /** The retrospective prompt instructs the agent to write under {@code .flowtree/}. */
    @Test(timeout = 30000)
    public void retrospectivePromptInstructsFlowtreePath() {
        String prompt = RetrospectivePromptBuilder.build(new CodingAgentJob("t1", "p"));
        assertTrue("Prompt must instruct the .flowtree/ results path",
                prompt.contains(FlowtreeArtifacts.inDirectory(RetrospectivePhase.RESULTS_FILE)));
    }
}
