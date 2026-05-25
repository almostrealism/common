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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exercises the per-workstream {@code agentEnv} wiring end-to-end: factory
 * propagation, factory and job wire round-trips, and injection into the
 * {@link AgentRunRequest} environment that {@code AgentProcessRunner} applies
 * to the agent subprocess.
 *
 * <p>{@code agentEnv} differs from the pushed-tool {@code env}: it is set on
 * the agent process itself, so every process the agent spawns (including
 * project-local MCP servers) inherits it.</p>
 */
public class CodingAgentJobAgentEnvTest extends TestSuiteBase {

    private static Map<String, String> sampleEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TENANT_ID", "tenant-a");
        env.put("RUNTIME_SECRET_NAME", "secret-a");
        return env;
    }

    @Test(timeout = 30000)
    public void factoryPropagatesAgentEnvToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do work");
        factory.setAgentEnv(sampleEnv());

        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        Assert.assertNotNull("Factory must produce a job", job);
        Assert.assertEquals("Job must receive agentEnv from factory",
                sampleEnv(), job.getAgentEnv());
    }

    @Test(timeout = 30000)
    public void factoryWireRoundTripPreservesAgentEnv() {
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory("do work");
        factory.setAgentEnv(sampleEnv());

        CodingAgentJob.Factory reconstructed =
                GitManagedJobSerializationTest.roundTripFactory(factory);
        CodingAgentJob job = (CodingAgentJob) reconstructed.nextJob();
        Assert.assertEquals("agentEnv must survive the factory wire round-trip",
                sampleEnv(), job.getAgentEnv());
    }

    @Test(timeout = 30000)
    public void jobWireRoundTripPreservesAgentEnv() {
        CodingAgentJob job = new CodingAgentJob("t1", "do work");
        job.setAgentEnv(sampleEnv());

        CodingAgentJob reconstructed = GitManagedJobSerializationTest.roundTrip(job);
        Assert.assertEquals("agentEnv must survive the job wire round-trip",
                sampleEnv(), reconstructed.getAgentEnv());
    }

    @Test(timeout = 30000)
    public void buildRunRequestInjectsAgentEnv() {
        CodingAgentJob job = new CodingAgentJob("t1", "do work");
        job.setAgentEnv(sampleEnv());

        AgentRunRequest req = job.buildRunRequest(
                "Read,Edit", "{\"mcpServers\":{}}", Path.of("/tmp/x.json"), 0);

        Map<String, String> env = req.getEnvironment();
        Assert.assertNotNull(env);
        Assert.assertEquals("agentEnv must reach the agent subprocess environment",
                "tenant-a", env.get("TENANT_ID"));
        Assert.assertEquals("secret-a", env.get("RUNTIME_SECRET_NAME"));
    }

    @Test(timeout = 30000)
    public void absentAgentEnvIsHarmless() {
        CodingAgentJob job = new CodingAgentJob("t1", "do work");

        AgentRunRequest req = job.buildRunRequest(
                "Read,Edit", "{\"mcpServers\":{}}", Path.of("/tmp/x.json"), 0);

        Assert.assertNull("Unset agentEnv stays null", job.getAgentEnv());
        Assert.assertNotNull("Request still has an environment map", req.getEnvironment());
    }
}
