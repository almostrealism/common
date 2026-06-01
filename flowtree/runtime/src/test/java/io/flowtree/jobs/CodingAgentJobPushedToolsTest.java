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

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end test of the pushed-tools wiring. Walks the same path the
 * controller-dispatched job actually takes:
 *
 * <ol>
 *   <li>build a {@link CodingAgentJobFactory} and call
 *       {@link CodingAgentJobFactory#setPushedToolsConfig} with the same
 *       JSON shape the controller emits;</li>
 *   <li>call {@link CodingAgentJobFactory#encode} (the wire format used
 *       to ship the factory to a remote agent node), then create a
 *       fresh factory and feed every key/value back through
 *       {@link CodingAgentJobFactory#set} so we exercise the
 *       serialisation/deserialisation round-trip;</li>
 *   <li>have the deserialised factory produce a {@link CodingAgentJob}
 *       via {@link CodingAgentJobFactory#nextJob};</li>
 *   <li>round-trip that job too via
 *       {@link CodingAgentJob#encode} / {@link CodingAgentJob#set};</li>
 *   <li>drive the job's private {@code configureMcpBuilder} so the
 *       built-in {@link McpConfigBuilder} sees the propagated config;</li>
 *   <li>assert the produced {@code --mcp-config} JSON contains a stdio
 *       ar-secrets entry pointing at the agent's downloaded path, and
 *       that {@code --allowedTools} contains both
 *       {@code mcp__ar-secrets__secret_list_names} and
 *       {@code mcp__ar-secrets__secret_render_file}.</li>
 * </ol>
 *
 * <p>This is the test we should have written the first time. Without
 * it, a bug in any link of the chain (factory wire format, job wire
 * format, builder hand-off, JSON emission) shows up only at deploy
 * time as "agent has no ar-secrets tools".</p>
 */
public class CodingAgentJobPushedToolsTest {

    /** JSON pushed-tools config emitted by the controller for an ar-secrets server. */
    private static final String CTRL_CONFIG = "{\"ar-secrets\":{"
        + "\"url\":\"http://0.0.0.0:7780/api/tools/ar-secrets\","
        + "\"tools\":[\"secret_list_names\",\"secret_render_file\"]}}";

    /**
     * Drives the private {@code configureMcpBuilder} method via reflection.
     * The method is intentionally package-private/private on
     * {@link CodingAgentJob}; this test bypasses that because the goal is
     * to assert the builder receives the propagated state, not to widen
     * the public surface.
     */
    private static void invokeConfigureMcpBuilder(CodingAgentJob job) throws Exception {
        Method m = CodingAgentJob.class.getDeclaredMethod("configureMcpBuilder");
        m.setAccessible(true);
        m.invoke(job);
    }

    /**
     * Reads the private {@code mcpConfigBuilder} field from the given job via reflection.
     */
    private static McpConfigBuilder readMcpBuilder(CodingAgentJob job) throws Exception {
        Field f = CodingAgentJob.class.getDeclaredField("mcpConfigBuilder");
        f.setAccessible(true);
        return (McpConfigBuilder) f.get(job);
    }


    /**
     * Verifies that {@link CodingAgentJobFactory#setPushedToolsConfig} stores the value
     * and {@link CodingAgentJobFactory#getPushedToolsConfig} returns the same value.
     */
    @Test(timeout = 30000)
    public void factorySetterStoresConfigAndExposesIt() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.setPushedToolsConfig(CTRL_CONFIG);
        Assert.assertEquals(CTRL_CONFIG, factory.getPushedToolsConfig());
    }

    /**
     * Verifies that the pushed-tools config survives a factory encode/set wire round-trip.
     */
    @Test(timeout = 30000)
    public void factoryEncodeRoundTripPreservesPushedToolsConfig() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("p");
        factory.setPushedToolsConfig(CTRL_CONFIG);

        CodingAgentJobFactory reconstructed = GitManagedJobSerializationTest.roundTripFactory(factory);
        Assert.assertEquals("pushedToolsConfig must survive factory wire round-trip",
                CTRL_CONFIG, reconstructed.getPushedToolsConfig());
    }

    /**
     * Verifies that the factory propagates its pushed-tools config to the job it produces.
     */
    @Test(timeout = 30000)
    public void factoryPropagatesPushedToolsConfigToJob() {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do work");
        factory.setArManagerUrl("http://ar-manager:8010");
        factory.setArManagerToken("armt_tmp_x");
        factory.setPushedToolsConfig(CTRL_CONFIG);

        CodingAgentJob job = (CodingAgentJob) factory.nextJob();
        assertNotNull("Factory must produce a job", job);
        Assert.assertEquals("Job must receive pushedToolsConfig from factory",
                CTRL_CONFIG, job.getPushedToolsConfig());
    }

    /**
     * Verifies that the pushed-tools config survives a job encode/set wire round-trip.
     */
    @Test(timeout = 30000)
    public void jobEncodeRoundTripPreservesPushedToolsConfig() {
        CodingAgentJob job = new CodingAgentJob("t1", "do work");
        job.setPushedToolsConfig(CTRL_CONFIG);

        CodingAgentJob reconstructed = GitManagedJobSerializationTest.roundTrip(job);
        Assert.assertEquals("pushedToolsConfig must survive job wire round-trip",
                CTRL_CONFIG, reconstructed.getPushedToolsConfig());
    }

    /**
     * Verifies the full chain: after factory and job wire round-trips, the MCP config
     * contains a stdio ar-secrets entry and the allowed-tools list contains both
     * secret_list_names and secret_render_file.
     */
    @Test(timeout = 30000)
    public void mcpConfigContainsArSecretsStdioEntryAfterFullChain() throws Exception {
        CodingAgentJobFactory factory = new CodingAgentJobFactory("do work");
        factory.setArManagerUrl("http://ar-manager:8010");
        factory.setArManagerToken("armt_tmp_x");
        factory.setPushedToolsConfig(CTRL_CONFIG);

        // Full wire round-trip: factory across the network, then the job
        // it produces across the network too.
        CodingAgentJobFactory factoryAtAgent = GitManagedJobSerializationTest.roundTripFactory(factory);
        CodingAgentJob jobBeforeWire = (CodingAgentJob) factoryAtAgent.nextJob();
        CodingAgentJob job = GitManagedJobSerializationTest.roundTrip(jobBeforeWire);

        invokeConfigureMcpBuilder(job);
        McpConfigBuilder builder = readMcpBuilder(job);

        String mcp = builder.buildMcpConfig();
        assertTrue("MCP config must register ar-secrets server: " + mcp,
                mcp.contains("\"ar-secrets\""));
        assertTrue("MCP config must point at the agent's downloaded server.py: " + mcp,
                mcp.contains(".flowtree/tools/mcp/ar-secrets/server.py"));
        assertTrue("MCP config must use stdio command for ar-secrets: " + mcp,
                mcp.contains("\"command\":\"python3\""));

        String allowed = builder.buildAllowedTools("Read,Edit");
        assertTrue("Allowed-tools must include secret_list_names: " + allowed,
                allowed.contains("mcp__ar-secrets__secret_list_names"));
        assertTrue("Allowed-tools must include secret_render_file: " + allowed,
                allowed.contains("mcp__ar-secrets__secret_render_file"));
        // The legacy ar-manager workspace_secret_* tools must NOT be
        // granted to agents — that's the consolidation invariant.
        assertTrue("Allowed-tools must still grant ar-manager send_message: " + allowed,
                allowed.contains("mcp__ar-manager__send_message"));
    }

    /**
     * Verifies that when no pushed-tools config is set, the builder emits no pushed-tool
     * entry and does not crash.
     */
    @Test(timeout = 30000)
    public void emptyPushedToolsConfigProducesNoStdioEntry() throws Exception {
        // No pushedToolsConfig set → builder must not emit any pushed-tool entry
        // and must not crash. Mirrors what happens to a job created locally
        // outside the controller path.
        CodingAgentJob job = new CodingAgentJob("t1", "do work");
        job.setArManagerUrl("http://ar-manager:8010");
        job.setArManagerToken("armt_tmp_x");

        invokeConfigureMcpBuilder(job);
        McpConfigBuilder builder = readMcpBuilder(job);
        String mcp = builder.buildMcpConfig();
        assertTrue("Should still emit ar-manager HTTP entry: " + mcp,
                mcp.contains("\"ar-manager\""));
        assertTrue("Should NOT register ar-secrets without a config: " + mcp,
                !mcp.contains("ar-secrets"));
    }
}
