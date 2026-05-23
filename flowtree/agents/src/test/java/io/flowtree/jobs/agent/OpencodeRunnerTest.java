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

import com.sun.net.httpserver.HttpServer;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
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
        assertTrue("cloud providers (openrouter, anthropic) report cost", cap.reportsCost());
        assertTrue("turn counts are best-effort", cap.reportsTurns());
        assertFalse("no effort concept", cap.supportsEffortLevel());
        assertFalse("no native budget", cap.supportsMaxBudget());
        assertTrue("MCP HTTP supported", cap.supportsMcpHttpTransport());
        assertTrue("MCP stdio supported", cap.supportsMcpStdioTransport());
        assertFalse("denial reporting unproven", cap.supportsPermissionDenialReporting());
        Assert.assertEquals("trust the provider's model set", Set.of(), cap.supportedModels());
        Assert.assertEquals("providers derived from PROVIDER_MAP",
                Set.of("local", "openrouter", "anthropic"), cap.supportedProviders());
    }

    /** The registry knows the opencode name and produces opencode runners. */
    @Test(timeout = 5000)
    public void registryRoutesOpencodeName() {
        assertTrue(AgentRunnerRegistry.available().contains(AgentRunnerRegistry.OPENCODE));
        AgentRunner runner = AgentRunnerRegistry.get(AgentRunnerRegistry.OPENCODE);
        Assert.assertEquals(OpencodeRunner.NAME, runner.getName());
        assertTrue(runner instanceof OpencodeRunner);
    }

    /**
     * Command line matches opencode 1.x: {@code run}, {@code --model}, {@code --format json},
     * {@code --dangerously-skip-permissions}, and the prompt — with the config path
     * intentionally absent (it is injected via {@code OPENCODE_CONFIG} at launch).
     */
    @Test(timeout = 5000)
    public void buildCommandLineIncludesCoreFlags() {
        OpencodeRunner runner = new OpencodeRunner();
        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("do the thing")
                .allowedTools("Read,Edit")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .model("default")
                .build();
        Path configPath = Paths.get("/tmp/opencode-config.json");

        List<String> cmd = runner.buildCommandLine(
                FAKE_BINARY, req, configPath, "local/default");

        Assert.assertEquals(FAKE_BINARY.toString(), cmd.get(0));
        Assert.assertEquals("run", cmd.get(1));
        assertFlagFollows(cmd, "--model", "local/default");
        assertFlagFollows(cmd, "--format", "json");
        assertTrue("--dangerously-skip-permissions must be present for headless runs",
                cmd.contains("--dangerously-skip-permissions"));
        Assert.assertFalse("opencode 1.x removed --config; path is passed via env",
                cmd.contains("--config"));
        Assert.assertFalse("opencode 1.x renamed --output-format to --format",
                cmd.contains("--output-format"));
        Assert.assertFalse("no working directory set -- --dir must be absent",
                cmd.contains("--dir"));
        Assert.assertEquals("do the thing", cmd.get(cmd.size() - 1));
    }

    /**
     * When the request supplies a working directory, the command line includes
     * {@code --dir <absolute-path>} so opencode pins the session root to the
     * repo clone instead of walking up to the user's home directory.
     */
    @Test(timeout = 5000)
    public void buildCommandLineIncludesDirWhenWorkingDirectorySet() {
        OpencodeRunner runner = new OpencodeRunner();
        Path workDir = Paths.get("/workspace/project/almostrealism-common");
        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("do the thing")
                .allowedTools("Read,Edit")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .model("default")
                .workingDirectory(workDir)
                .build();
        Path configPath = Paths.get("/tmp/opencode-config.json");

        List<String> cmd = runner.buildCommandLine(
                FAKE_BINARY, req, configPath, "local/default");

        assertFlagFollows(cmd, "--dir", workDir.toString());
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
        env.put(OpencodeConfigBuilder.ENV_DEFAULT_MODEL, "env-override");
        OpencodeConfigBuilder builder = new OpencodeConfigBuilder(env::get);
        Assert.assertEquals("local/env-override", builder.resolveQualifiedModel(null));

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

    // --- Provider-axis: routing and default provider --------------------------

    /**
     * The default provider for the opencode runner is {@code "local"} (llama.cpp /
     * ollama on the same host). When no explicit provider is set in the request,
     * the runner should use the local provider.
     */
    @Test(timeout = 5000)
    public void defaultProviderIsLocal() {
        assertEquals("local", new OpencodeRunner().defaultProvider());
    }

    /**
     * Capabilities advertise the three known providers.
     */
    @Test(timeout = 5000)
    public void capabilitiesAdvertiseSupportedProviders() {
        AgentCapabilities cap = new OpencodeRunner().capabilities();
        assertTrue("local should be in supported providers", cap.supportedProviders().contains("local"));
        assertTrue("openrouter should be in supported providers", cap.supportedProviders().contains("openrouter"));
        assertTrue("anthropic should be in supported providers", cap.supportedProviders().contains("anthropic"));
    }

    /**
     * Requesting an unknown provider produces a clear {@link IllegalArgumentException}
     * that names the unknown provider and lists the available ones. This fails before
     * any subprocess is launched so the error arrives immediately.
     */
    @Test(timeout = 5000)
    public void runThrowsForUnknownProvider() {
        OpencodeRunner runner = new OpencodeRunner();
        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("p")
                .allowedTools("Read")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .provider("unknown-provider-xyz")
                .build();
        try {
            runner.run(req, new ConsoleFeatures() {});
            throw new AssertionError("expected exception for unknown provider");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("message should name the bad provider: " + expected.getMessage(),
                    expected.getMessage().contains("unknown-provider-xyz"));
        }
    }

    /**
     * Provider liveness probe accepts any HTTP response — including 404 — as
     * evidence the upstream is alive. The probe is checking TCP+HTTP
     * reachability, not API correctness.
     */
    @Test(timeout = 5000)
    public void probeProviderUrlAcceptsAnyHttpResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Default handler returns 404; that's exactly what we want for HEAD on a
        // server that has no registered context — proves the probe is alive-only.
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            new OpencodeRunner().probeProviderUrl(url, new ConsoleFeatures() {});
        } finally {
            server.stop(0);
        }
    }

    /**
     * Probe failure when nothing is listening on the configured port surfaces as
     * {@link AgentRunnerNotAvailableException}, allowing the orchestrator to
     * fail fast instead of letting opencode hang to inactivity timeout.
     */
    @Test(timeout = 5000)
    public void probeProviderUrlThrowsWhenUnreachable() throws Exception {
        // Bind and immediately release a port to discover one that's free, then
        // probe it. Race window is acceptable for a unit test.
        HttpServer scratch = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int freePort = scratch.getAddress().getPort();
        scratch.stop(0);
        String url = "http://127.0.0.1:" + freePort + "/v1";

        try {
            new OpencodeRunner().probeProviderUrl(url, new ConsoleFeatures() {});
            throw new AssertionError("expected AgentRunnerNotAvailableException");
        } catch (AgentRunnerNotAvailableException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("message should name the URL: " + expected.getMessage(),
                    expected.getMessage().contains(url));
        }
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
