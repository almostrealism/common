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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the package-private collaborator seams on {@link CodingAgentJob}
 * and {@link GitManagedJob} that {@link AgentJobFaultCatalogue} installs its
 * doubles through. A seam that only replaced the field, leaving the job's
 * other collaborators bound to the original instance, would make every fault
 * that uses it inert, and the catalogue would then report the end-to-end
 * scenario as blind when it was the seam that failed.
 */
public class CodingAgentJobCollaboratorSeamTest extends TestSuiteBase {

    /**
     * {@link CodingAgentJob#setMcpConfigBuilder} rebinds the launch
     * configuration too: configuring the job's MCP state after the swap
     * reaches the new builder, and the allowed tools are composed from it.
     */
    @Test(timeout = 30000)
    public void mcpConfigBuilderSwapRebindsLaunchConfig() {
        CodingAgentJob job = new CodingAgentJob("t1", "do the work");
        job.setArManagerUrl("http://controller.invalid:7780/mcp");
        job.setArManagerToken("token");

        McpConfigBuilder original = job.mcpConfigBuilder;
        McpConfigBuilder replacement = new McpConfigBuilder();
        job.setMcpConfigBuilder(replacement);
        job.configureMcpBuilder();

        assertSame(replacement, job.mcpConfigBuilder);
        assertTrue("The replacement builder must receive the job's ar-manager settings",
                replacement.arManagerEnabled());
        assertFalse("The discarded builder must not be configured any more",
                original.arManagerEnabled());
        assertTrue("Allowed tools must be composed from the replacement builder",
                job.buildComposedAllowedTools().contains("mcp__ar-manager__"));
    }

    /**
     * {@link CodingAgentJob#setCommitMessageBuilder} replaces the builder the
     * job resolves its commit message with, and the source tag the job
     * reports comes from that builder.
     */
    @Test(timeout = 30000)
    public void commitMessageBuilderSwapIsUsedForResolution() {
        CodingAgentJob job = new CodingAgentJob("t1", "do the work");
        CommitMessageBuilder replacement = new CommitMessageBuilder() {
            @Override
            String resolve(CodingAgentJob j) {
                setSource(SOURCE_AGENT);
                return "Replacement message";
            }
        };

        job.setCommitMessageBuilder(replacement);

        assertSame(replacement, job.commitMessageBuilder());
        assertNull("No source before the message is resolved", job.getCommitMessageSource());
        assertEquals("Replacement message", job.getCommitMessage());
        assertEquals(CommitMessageBuilder.SOURCE_AGENT, job.getCommitMessageSource());
    }

    /**
     * {@link GitManagedJob#setWorkOutcome} replaces the classification the
     * job reports its work through, and a job that has not run reports no
     * lost work through the production classification.
     */
    @Test(timeout = 30000)
    public void workOutcomeSwapReplacesClassification() {
        CodingAgentJob job = new CodingAgentJob("t1", "do the work");
        assertFalse("A job that never ran dropped nothing", job.workOutcome().allChangesDropped());

        JobWorkOutcome replacement = new JobWorkOutcome(job) {
            @Override
            boolean allChangesDropped() {
                return true;
            }
        };
        job.setWorkOutcome(replacement);

        assertSame(replacement, job.workOutcome());
        assertTrue(job.workOutcome().allChangesDropped());
    }
}
