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

import io.flowtree.JsonFieldExtractor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks that {@link OperatorGrants} grants exactly what the controller
 * signed: accepted merge resolutions travel from the submission to the job
 * that runs, a grant nothing signed accepts nothing, and an agent cannot
 * forge the commit trailer that records one.
 */
public class OperatorGrantsTest extends TestSuiteBase {

    /** The signing secret these tests stand in for the controller's with. */
    private static final String SECRET = "test-secret";

    /**
     * A grant signed at submission survives the factory's wire encoding and
     * reaches the job the factory configures, which then accepts exactly the
     * named paths.
     */
    @Test(timeout = 30000)
    public void signedGrantTravelsFromSubmissionToJob() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt");
        factory.applyOperatorGrants(List.of("src/A.java", "docs/b.md"), SECRET);

        CodingAgentJob.Factory restored = GitManagedJobSerializationTest.roundTripFactory(factory);
        assertEquals(List.of("src/A.java", "docs/b.md"),
                restored.operatorGrants().getAcceptedMergeResolutions());

        CodingAgentJob job = GitManagedJobSerializationTest.roundTrip((CodingAgentJob) restored.nextJob());
        assertTrue(job.operatorGrants().acceptsMergeResolution("src/A.java"));
        assertTrue(job.operatorGrants().acceptsMergeResolution("docs/b.md"));
        assertFalse(job.operatorGrants().acceptsMergeResolution("src/Other.java"));
        assertEquals(OperatorGrants.signMergeResolutions(SECRET, factory.getTaskId(),
                        List.of("docs/b.md", "src/A.java")),
                job.operatorGrants().getMergeResolutionSignature());
    }

    /**
     * The sensitive-file bypass, now held by the same grants, survives the
     * wire exactly as before.
     */
    @Test(timeout = 30000)
    public void sensitiveFileBypassStillTravels() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt");
        factory.setSensitiveFileProtectionEnabled(false);
        factory.applyOperatorGrants(List.of(), SECRET);

        CodingAgentJob job = GitManagedJobSerializationTest.roundTrip(
                (CodingAgentJob) GitManagedJobSerializationTest.roundTripFactory(factory).nextJob());
        assertFalse(job.isSensitiveFileProtectionEnabled());
        assertEquals(SensitiveFileBypassTrailer.sign(SECRET, factory.getTaskId()),
                job.getSensitiveFileBypassSignature());
    }

    /**
     * A controller with no signing secret forwards no acceptance at all, so
     * a job never carries paths that nothing authorized.
     */
    @Test(timeout = 30000)
    public void grantWithoutSecretIsDropped() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("Prompt");
        factory.applyOperatorGrants(List.of("src/A.java"), null);

        assertTrue(factory.operatorGrants().getAcceptedMergeResolutions().isEmpty());
        assertNull(factory.operatorGrants().getMergeResolutionSignature());
    }

    /**
     * A value that is not an array of strings is rejected at the controller's
     * extraction point rather than being coerced, which is what lets the
     * submit path drop the grant (see
     * {@code FlowTreeApiEndpoint.handleSubmit}). A well-formed array extracts
     * to its elements.
     */
    @Test(timeout = 30000)
    public void malformedGrantIsIgnored() {
        try {
            JsonFieldExtractor.extractStrictStringArray(
                    "{\"acceptedMergeResolutions\":\"src/A.java\"}", "acceptedMergeResolutions");
            fail("a non-array acceptedMergeResolutions value must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals(List.of("src/B.java"), JsonFieldExtractor.extractStrictStringArray(
                "{\"acceptedMergeResolutions\":[\"src/B.java\"]}", "acceptedMergeResolutions"));
    }

    /** Paths carried without a signature accept nothing. */
    @Test(timeout = 30000)
    public void unsignedPathsAcceptNothing() {
        OperatorGrants grants = new OperatorGrants();
        grants.setAcceptedMergeResolutions(List.of("src/A.java"), null);
        assertFalse(grants.acceptsMergeResolution("src/A.java"));
    }

    /**
     * Any {@code Merge-Resolution-Accepted} line the agent wrote is removed,
     * and the controller-signed trailer is appended only when the commit
     * accepted something.
     */
    @Test(timeout = 30000)
    public void agentWrittenTrailerIsStrippedAndReplaced() {
        OperatorGrants grants = new OperatorGrants();
        grants.setAcceptedMergeResolutions(List.of("src/A.java"), "sig");
        String forged = "Fix things\n\nmerge-resolution-accepted: job=forged; paths=src/B.java\n";

        String none = grants.applyToMessage(forged, "job-1", List.of());
        assertFalse(none, none.toLowerCase().contains("merge-resolution-accepted"));

        String applied = grants.applyToMessage(forged, "job-1", List.of("src/A.java"));
        assertFalse(applied, applied.contains("forged"));
        assertTrue(applied, applied.contains(
                OperatorGrants.RESOLUTION_TRAILER_KEY + ": job-1=sig; paths=src/A.java"));
        assertNotNull(applied);
    }

    /**
     * Stripping the grant trailers from an agent-supplied message removes both
     * a forged {@code Merge-Resolution-Accepted} and a forged
     * {@code Sensitive-File-Bypass} line, in any case or spacing, while leaving
     * the agent's own prose intact. This is what keeps a trailer the agent
     * wrote into {@code commit.txt} from surviving into a message the harness
     * composes from it (for instance a rescue commit).
     */
    @Test(timeout = 30000)
    public void stripGrantTrailersRemovesBothForgedTrailers() {
        String forged = "Real work here\n\n"
                + "merge-resolution-accepted: job=forged; paths=src/B.java\n"
                + "  Sensitive-File-Bypass : job=forged\n";

        String stripped = OperatorGrants.stripGrantTrailers(forged);

        assertTrue(stripped, stripped.contains("Real work here"));
        assertFalse(stripped, stripped.toLowerCase().contains("merge-resolution-accepted"));
        assertFalse(stripped, stripped.toLowerCase().contains("sensitive-file-bypass"));
        assertFalse(stripped, stripped.contains("forged"));
        assertEquals("", OperatorGrants.stripGrantTrailers(null));
    }

    /**
     * The grants stamp both of their trailers: the sensitive-file bypass when
     * the protections are off and the bypass is signed, after removing any
     * copy the agent wrote, and nothing for it while the protections are on.
     */
    @Test(timeout = 30000)
    public void bypassTrailerIsAppliedByTheGrants() {
        String forged = "Fix things\n\n  sensitive-file-bypass : job-1=forged\n";
        OperatorGrants grants = new OperatorGrants();
        String protectedMessage = grants.applyToMessage(forged, "job-1", List.of());
        assertFalse(protectedMessage, protectedMessage.toLowerCase().contains("sensitive-file-bypass"));

        grants.setSensitiveFileProtectionEnabled(false);
        grants.setSensitiveFileBypassSignature("bypass-sig");
        grants.setAcceptedMergeResolutions(List.of("src/A.java"), "sig");
        String applied = grants.applyToMessage(forged, "job-1", List.of("src/A.java"));
        assertFalse(applied, applied.contains("forged"));
        assertTrue(applied, applied.startsWith("Fix things\n"));
        assertTrue(applied, applied.contains("\n" + SensitiveFileBypassTrailer.TRAILER_KEY + ": job-1=bypass-sig\n"));
        assertTrue(applied, applied.endsWith(
                "\n" + OperatorGrants.RESOLUTION_TRAILER_KEY + ": job-1=sig; paths=src/A.java\n"));
    }
}
