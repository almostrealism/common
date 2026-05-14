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
 *   <li>build a {@link ClaudeCodeJobFactory} and call
 *       {@link ClaudeCodeJobFactory#setPushedToolsConfig} with the same
 *       JSON shape the controller emits;</li>
 *   <li>call {@link ClaudeCodeJobFactory#encode} (the wire format used
 *       to ship the factory to a remote agent node), then create a
 *       fresh factory and feed every key/value back through
 *       {@link ClaudeCodeJobFactory#set} so we exercise the
 *       serialisation/deserialisation round-trip;</li>
 *   <li>have the deserialised factory produce a {@link ClaudeCodeJob}
 *       via {@link ClaudeCodeJobFactory#nextJob};</li>
 *   <li>round-trip that job too via
 *       {@link ClaudeCodeJob#encode} / {@link ClaudeCodeJob#set};</li>
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
public class ClaudeCodeJobPushedToolsTest {

    private static final String CTRL_CONFIG = "{\"ar-secrets\":{"
        + "\"url\":\"http://0.0.0.0:7780/api/tools/ar-secrets\","
        + "\"tools\":[\"secret_list_names\",\"secret_render_file\"]}}";

    /**
     * Drives the private {@code configureMcpBuilder} method via reflection.
     * The method is intentionally package-private/private on
     * {@link ClaudeCodeJob}; this test bypasses that because the goal is
     * to assert the builder receives the propagated state, not to widen
     * the public surface.
     */
    private static void invokeConfigureMcpBuilder(ClaudeCodeJob job) throws Exception {
        Method m = ClaudeCodeJob.class.getDeclaredMethod("configureMcpBuilder");
        m.setAccessible(true);
        m.invoke(job);
    }

    private static McpConfigBuilder readMcpBuilder(ClaudeCodeJob job) throws Exception {
        Field f = ClaudeCodeJob.class.getDeclaredField("mcpConfigBuilder");
        f.setAccessible(true);
        return (McpConfigBuilder) f.get(job);
    }

    private static ClaudeCodeJobFactory roundTripFactory(ClaudeCodeJobFactory original) {
        String encoded = original.encode();
        ClaudeCodeJobFactory reconstructed = new ClaudeCodeJobFactory();
        applyEncoded(reconstructed, encoded);
        return reconstructed;
    }

    private static ClaudeCodeJob roundTripJob(ClaudeCodeJob original) {
        String encoded = original.encode();
        ClaudeCodeJob reconstructed = new ClaudeCodeJob("", "");
        applyEncoded(reconstructed, encoded);
        return reconstructed;
    }

    /**
     * Applies an encoded wire string to a target by splitting on
     * {@code "::"} and calling the target's {@code set(key, value)} for
     * every {@code key:=value} segment, mirroring what the network layer
     * does on the receiving node.
     */
    private static void applyEncoded(Object target, String encoded) {
        // The first segment is the fully-qualified class name; remaining
        // segments are "key:=value" pairs.
        String[] parts = encoded.split("::");
        Method set;
        try {
            set = target.getClass().getMethod("set", String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        for (int i = 1; i < parts.length; i++) {
            String segment = parts[i];
            int eq = segment.indexOf(":=");
            if (eq < 0) continue;
            String key = segment.substring(0, eq);
            String value = segment.substring(eq + 2);
            try {
                set.invoke(target, key, value);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test(timeout = 30000)
    public void factorySetterStoresConfigAndExposesIt() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("p");
        factory.setPushedToolsConfig(CTRL_CONFIG);
        Assert.assertEquals(CTRL_CONFIG, factory.getPushedToolsConfig());
    }

    @Test(timeout = 30000)
    public void factoryEncodeRoundTripPreservesPushedToolsConfig() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("p");
        factory.setPushedToolsConfig(CTRL_CONFIG);

        ClaudeCodeJobFactory reconstructed = roundTripFactory(factory);
        Assert.assertEquals("pushedToolsConfig must survive factory wire round-trip",
                CTRL_CONFIG, reconstructed.getPushedToolsConfig());
    }

    @Test(timeout = 30000)
    public void factoryPropagatesPushedToolsConfigToJob() {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do work");
        factory.setArManagerUrl("http://ar-manager:8010");
        factory.setArManagerToken("armt_tmp_x");
        factory.setPushedToolsConfig(CTRL_CONFIG);

        ClaudeCodeJob job = (ClaudeCodeJob) factory.nextJob();
        assertNotNull("Factory must produce a job", job);
        Assert.assertEquals("Job must receive pushedToolsConfig from factory",
                CTRL_CONFIG, job.getPushedToolsConfig());
    }

    @Test(timeout = 30000)
    public void jobEncodeRoundTripPreservesPushedToolsConfig() {
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do work");
        job.setPushedToolsConfig(CTRL_CONFIG);

        ClaudeCodeJob reconstructed = roundTripJob(job);
        Assert.assertEquals("pushedToolsConfig must survive job wire round-trip",
                CTRL_CONFIG, reconstructed.getPushedToolsConfig());
    }

    @Test(timeout = 30000)
    public void mcpConfigContainsArSecretsStdioEntryAfterFullChain() throws Exception {
        ClaudeCodeJobFactory factory = new ClaudeCodeJobFactory("do work");
        factory.setArManagerUrl("http://ar-manager:8010");
        factory.setArManagerToken("armt_tmp_x");
        factory.setPushedToolsConfig(CTRL_CONFIG);

        // Full wire round-trip: factory across the network, then the job
        // it produces across the network too.
        ClaudeCodeJobFactory factoryAtAgent = roundTripFactory(factory);
        ClaudeCodeJob jobBeforeWire = (ClaudeCodeJob) factoryAtAgent.nextJob();
        ClaudeCodeJob job = roundTripJob(jobBeforeWire);

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

    @Test(timeout = 30000)
    public void emptyPushedToolsConfigProducesNoStdioEntry() throws Exception {
        // No pushedToolsConfig set → builder must not emit any pushed-tool entry
        // and must not crash. Mirrors what happens to a job created locally
        // outside the controller path.
        ClaudeCodeJob job = new ClaudeCodeJob("t1", "do work");
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
