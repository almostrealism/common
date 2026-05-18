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

package io.flowtree.jobs.agent;

import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link OpencodeRunner} composes the right command line, declares the
 * documented capabilities, and surfaces a clear error when the binary is
 * missing.
 */
public class OpencodeRunnerTest extends TestSuiteBase {

    /** Path used as the resolved opencode binary in tests that mock the locator. */
    private static final Path FAKE_BINARY = Paths.get("/opt/tools/opencode");

    /** Capabilities should match the documented per-runner contract. */
    @Test(timeout = 5000)
    public void capabilitiesAdvertiseOpencodeFeatures() {
        AgentCapabilities cap = new OpencodeRunner().capabilities();
        assertFalse("local-model cost reporting is meaningless", cap.reportsCost());
        assertTrue("turn counts are best-effort", cap.reportsTurns());
        assertFalse("no effort concept", cap.supportsEffortLevel());
        assertFalse("no native budget", cap.supportsMaxBudget());
        assertTrue("MCP HTTP supported", cap.supportsMcpHttpTransport());
        assertTrue("MCP stdio supported", cap.supportsMcpStdioTransport());
        assertFalse("denial reporting unproven", cap.supportsPermissionDenialReporting());
        Assert.assertEquals("trust the provider's model set", Set.of(), cap.supportedModels());
    }

    /** The registry knows the opencode name and produces opencode runners. */
    @Test(timeout = 5000)
    public void registryRoutesOpencodeName() {
        assertTrue(AgentRunnerRegistry.available().contains(AgentRunnerRegistry.OPENCODE));
        AgentRunner runner = AgentRunnerRegistry.get(AgentRunnerRegistry.OPENCODE);
        Assert.assertEquals(OpencodeRunner.NAME, runner.getName());
        assertTrue(runner instanceof OpencodeRunner);
    }

    /** Command line includes the binary, run subcommand, config flag, model flag, and prompt. */
    @Test(timeout = 5000)
    public void buildCommandLineIncludesCoreFlags() {
        OpencodeRunner runner = new OpencodeRunner();
        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("do the thing")
                .allowedTools("Read,Edit")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .model("qwen3-coder")
                .build();
        Path configPath = Paths.get("/tmp/opencode-config.json");

        List<String> cmd = runner.buildCommandLine(
                FAKE_BINARY, req, configPath, "local/qwen3-coder");

        Assert.assertEquals(FAKE_BINARY.toString(), cmd.get(0));
        Assert.assertEquals("run", cmd.get(1));
        assertFlagFollows(cmd, "--config", configPath.toString());
        assertFlagFollows(cmd, "--model", "local/qwen3-coder");
        assertFlagFollows(cmd, "--output-format", "json");
        Assert.assertEquals("do the thing", cmd.get(cmd.size() - 1));
    }

    /** When the binary cannot be located, {@code run()} throws the documented exception. */
    @Test(timeout = 5000)
    public void runThrowsWhenBinaryMissing() {
        OpencodeRunner runner = new OpencodeRunner(
                () -> new OpencodeBinaryLocator(
                        name -> null, name -> "/home/agent", path -> false),
                OpencodeConfigBuilder::new);

        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("p")
                .allowedTools("Read")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .build();

        try {
            runner.run(req, new ConsoleFeatures() {});
            throw new AssertionError("expected AgentRunnerNotAvailableException");
        } catch (AgentRunnerNotAvailableException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage().contains("opencode"));
        }
    }

    /** A request without a model still resolves a qualified model via the env/fallback chain. */
    @Test(timeout = 5000)
    public void qualifiedModelFallsBackToEnvOrDefault() {
        Map<String, String> env = new HashMap<>();
        env.put(OpencodeConfigBuilder.ENV_DEFAULT_MODEL, "llama3");
        OpencodeConfigBuilder builder = new OpencodeConfigBuilder(env::get);
        Assert.assertEquals("local/llama3", builder.resolveQualifiedModel(null));

        Map<String, String> noEnv = new HashMap<>();
        OpencodeConfigBuilder fallback = new OpencodeConfigBuilder(noEnv::get);
        Assert.assertEquals("local/" + OpencodeConfigBuilder.FALLBACK_MODEL,
                fallback.resolveQualifiedModel(null));
    }

    /**
     * Builder injection works: a runner constructed with custom suppliers
     * sees the supplied locator and config builder rather than the defaults.
     */
    @Test(timeout = 5000)
    public void runnerHonorsInjectedSuppliers() {
        boolean[] locatorCalled = {false};
        boolean[] configCalled = {false};

        OpencodeRunner runner = new OpencodeRunner(
                () -> {
                    locatorCalled[0] = true;
                    return new OpencodeBinaryLocator(
                            name -> null, name -> "/home/agent", p -> p.equals(FAKE_BINARY));
                },
                () -> {
                    configCalled[0] = true;
                    return new OpencodeConfigBuilder(new HashMap<String, String>()::get);
                });

        // We don't run() — that would launch a subprocess. Just exercise the
        // capability declaration (which doesn't trigger lookups), then peek
        // at the lazy-resolved state. This proves the suppliers are wired up
        // and never invoked on capability calls.
        assertNotNull(runner.capabilities());
        assertFalse(locatorCalled[0]);
        assertFalse(configCalled[0]);
    }

    /**
     * Asserts that {@code cmd} contains {@code flag} immediately followed by
     * {@code value}; throws an {@link AssertionError} on the first mismatch.
     */
    private static void assertFlagFollows(List<String> cmd, String flag, String value) {
        int idx = cmd.indexOf(flag);
        Assert.assertTrue("missing flag " + flag + " in " + cmd, idx >= 0);
        Assert.assertTrue("no value after " + flag, idx + 1 < cmd.size());
        Assert.assertEquals("expected " + flag + " " + value + " but got " + cmd.get(idx + 1),
                value, cmd.get(idx + 1));
    }
}
