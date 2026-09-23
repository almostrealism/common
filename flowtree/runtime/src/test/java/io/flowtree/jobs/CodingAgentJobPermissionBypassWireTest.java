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

import io.flowtree.jobs.agent.AgentRunRequest;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the agent-runtime permission-prompt grant survives every hop between
 * the controller's decision and the launched session.
 *
 * <p>A grant that is dropped on the wire is worse than one that was never
 * made: the job record says the session may edit the tooling it was submitted
 * to edit, and the session is denied anyway — the failure mode this property
 * exists to remove. The grant is also default-off, so a job nobody granted it
 * to must not acquire it by round-tripping.</p>
 */
public class CodingAgentJobPermissionBypassWireTest extends TestSuiteBase {

    /**
     * Builds a job carrying {@code granted}, configured just enough to build
     * a run request.
     *
     * @param granted whether the job carries the permission-prompt grant
     * @return the configured job
     */
    private CodingAgentJob jobWithGrant(boolean granted) {
        CodingAgentJob job = new CodingAgentJob("task-1", "do the work");
        job.setBypassAgentPermissionPrompts(granted);
        return job;
    }

    /** A job is not granted the bypass unless someone grants it. */
    @Test(timeout = 15000)
    public void grantIsOffByDefault() {
        assertFalse(new CodingAgentJob("task-1", "do the work").isBypassAgentPermissionPrompts());
    }

    /** The grant reaches the request the runner is handed. */
    @Test(timeout = 15000)
    public void grantReachesTheRunRequest() {
        AgentRunRequest granted = jobWithGrant(true)
                .buildRunRequest("Read,Edit", "{\"mcpServers\":{}}", Path.of("out.json"), 0);
        assertTrue(granted.isBypassPermissionPrompts());

        AgentRunRequest ungranted = jobWithGrant(false)
                .buildRunRequest("Read,Edit", "{\"mcpServers\":{}}", Path.of("out.json"), 0);
        assertFalse(ungranted.isBypassPermissionPrompts());
    }

    /**
     * The grant survives the encode/decode round-trip that ships a job to a
     * remote agent node. Without the codec entry the node would run the job
     * without it and deny the very writes the job exists to make.
     */
    @Test(timeout = 15000)
    public void grantSurvivesTheWireRoundTrip() {
        CodingAgentJob restored =
                GitManagedJobSerializationTest.roundTrip(jobWithGrant(true));

        assertTrue("the grant must survive dispatch to a remote agent node",
                restored.isBypassAgentPermissionPrompts());
    }

    /** An ungranted job does not acquire the grant by being round-tripped. */
    @Test(timeout = 15000)
    public void ungrantedJobStaysUngrantedAcrossTheWire() {
        CodingAgentJob restored =
                GitManagedJobSerializationTest.roundTrip(jobWithGrant(false));

        assertFalse(restored.isBypassAgentPermissionPrompts());
    }

    /**
     * The grant also survives the factory round-trip, which is the hop that
     * actually ships a submission to an agent node.
     */
    @Test(timeout = 15000)
    public void grantSurvivesTheFactoryRoundTrip() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory();
        factory.setBypassAgentPermissionPrompts(true);

        assertTrue(GitManagedJobSerializationTest.roundTripFactory(factory)
                .isBypassAgentPermissionPrompts());
    }

    /**
     * The grant reaches the job the factory actually builds. A factory flag
     * that {@code nextJob()} never copies is the failure this covers: the job
     * record says the session may write the tooling it was submitted to edit,
     * and the session is denied anyway.
     */
    @Test(timeout = 15000)
    public void grantReachesTheJobTheFactoryBuilds() {
        CodingAgentJobFactory granted = new CodingAgentJobFactory();
        granted.setPrompts("do the work");
        granted.setBypassAgentPermissionPrompts(true);
        assertTrue(((CodingAgentJob) granted.nextJob()).isBypassAgentPermissionPrompts());

        CodingAgentJobFactory ungranted = new CodingAgentJobFactory();
        ungranted.setPrompts("do the work");
        assertFalse(((CodingAgentJob) ungranted.nextJob()).isBypassAgentPermissionPrompts());
    }

    /**
     * A workstream applies the grant to every submission path through one
     * decision, so a path cannot quietly omit it.
     */
    @Test(timeout = 15000)
    public void workstreamAppliesTheGrantForPermittedBranchesOnly() {
        Workstream ws = new Workstream();
        ws.setAgentPermissionBypassBranches(Collections.singletonList("ci/"));

        CodingAgentJobFactory permitted = new CodingAgentJobFactory();
        ws.applyCapabilities(permitted, "ci/tooling");
        assertTrue(permitted.isBypassAgentPermissionPrompts());

        CodingAgentJobFactory ordinary = new CodingAgentJobFactory();
        ws.applyCapabilities(ordinary, "feature/thing");
        assertFalse(ordinary.isBypassAgentPermissionPrompts());
    }
}
