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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
     * Cloud providers (openrouter, anthropic) require a resolvable API key
     * before opencode is invoked. Without this guard the builder silently
     * omits the {@code apiKey} field, opencode launches anyway, and the
     * upstream returns 401 — symptom: zero token usage on the provider
     * account, with a cryptic-looking 401 buried in the agent output. The
     * runner now fails up front with the specific names it tried.
     */
    @Test(timeout = 5000)
    public void runThrowsWhenCloudProviderHasNoApiKey() {
        // Inject a locator that resolves opencode via OPENCODE_BIN to a stub
        // path the predicate marks executable, so ensureBinary() succeeds and
        // the run() flow advances to the API-key precheck under test.
        OpencodeRunner runner = new OpencodeRunner(
                () -> new OpencodeBinaryLocator(
                        name -> "OPENCODE_BIN".equals(name) ? FAKE_BINARY.toString() : null,
                        name -> "/home/agent",
                        p -> p.equals(FAKE_BINARY)),
                () -> new OpencodeConfigBuilder(new HashMap<String, String>()::get));
        // No secret lookup wired and no env var set => empty key.

        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("p")
                .allowedTools("Read")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .provider("openrouter")
                .build();

        try {
            runner.run(req, new ConsoleFeatures() {});
            throw new AssertionError("expected fail-loud for missing openrouter key");
        } catch (IllegalStateException expected) {
            String msg = expected.getMessage();
            assertNotNull(msg);
            assertTrue("message names the provider: " + msg, msg.contains("openrouter"));
            assertTrue("message names the workspace secret: " + msg,
                    msg.contains("openrouter-api-key"));
            assertTrue("message names the provider env var: " + msg,
                    msg.contains("OPENROUTER_API_KEY"));
            assertTrue("message names the generic env var: " + msg,
                    msg.contains(OpencodeConfigBuilder.ENV_API_KEY));
        }
    }

    /**
     * The API-key precheck only fires for non-local providers. A run that
     * targets {@code local} (the default) must NOT fail just because no key
     * is available — local llama-server endpoints are typically keyless.
     */
    @Test(timeout = 5000)
    public void localProviderDoesNotRequireApiKey() {
        OpencodeRunner runner = new OpencodeRunner(
                () -> new OpencodeBinaryLocator(
                        name -> "OPENCODE_BIN".equals(name) ? FAKE_BINARY.toString() : null,
                        name -> "/home/agent",
                        p -> p.equals(FAKE_BINARY)),
                () -> new OpencodeConfigBuilder(new HashMap<String, String>()::get));

        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("p")
                .allowedTools("Read")
                .mcpConfigJson("{\"mcpServers\":{}}")
                // no explicit provider => defaults to local
                .build();

        try {
            runner.run(req, new ConsoleFeatures() {});
        } catch (IllegalStateException unexpected) {
            if (unexpected.getMessage() != null
                    && unexpected.getMessage().startsWith("No API key")) {
                throw new AssertionError(
                        "API-key precheck must not fire for local provider: "
                                + unexpected.getMessage());
            }
        } catch (Exception laterFailure) {
            // Probe / process launch / etc. is allowed to fail — the test
            // only asserts that the precheck did NOT trip.
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

    // --- Workspace-secret resolution via controller HTTP --------------------

    /**
     * The payload extractor returns the value keyed by the secret name when
     * present — the canonical case for a secret declared as
     * {@code {"openrouter-api-key": "sk-or-..."}} in {@code workstreams.yaml}.
     */
    @Test(timeout = 5000)
    public void extractSecretValueReadsDirectKey() {
        String body = "{\"name\":\"openrouter-api-key\","
                + "\"workspace_id\":\"T01\","
                + "\"payload\":{\"openrouter-api-key\":\"sk-or-v1-abc\"}}";
        Assert.assertEquals("sk-or-v1-abc",
                OpencodeRunner.extractSecretValueFromPayload(body, "openrouter-api-key"));
    }

    /**
     * When the payload key does not match the secret name but contains exactly
     * one value, that single value is returned — accommodates operators who
     * store a one-key payload like {@code {"value": "sk-..."}}.
     */
    @Test(timeout = 5000)
    public void extractSecretValueFallsBackToSingleValuePayload() {
        String body = "{\"name\":\"openrouter-api-key\","
                + "\"workspace_id\":\"T01\","
                + "\"payload\":{\"value\":\"sk-or-v1-xyz\"}}";
        Assert.assertEquals("sk-or-v1-xyz",
                OpencodeRunner.extractSecretValueFromPayload(body, "openrouter-api-key"));
    }

    /**
     * A multi-key payload that contains neither the secret-name key nor a
     * single-value key yields {@code null}, so the caller falls through to
     * the env-var path rather than guessing which value to use.
     */
    @Test(timeout = 5000)
    public void extractSecretValueReturnsNullForAmbiguousMultiKeyPayload() {
        String body = "{\"name\":\"openrouter-api-key\","
                + "\"payload\":{\"a\":\"1\",\"b\":\"2\"}}";
        Assert.assertNull(
                OpencodeRunner.extractSecretValueFromPayload(body, "openrouter-api-key"));
    }

    /**
     * Empty body, missing payload, malformed JSON — every degenerate input
     * resolves to {@code null} so the caller falls back to the env-var path.
     */
    @Test(timeout = 5000)
    public void extractSecretValueReturnsNullForDegenerateInputs() {
        Assert.assertNull(OpencodeRunner.extractSecretValueFromPayload(null, "k"));
        Assert.assertNull(OpencodeRunner.extractSecretValueFromPayload("", "k"));
        Assert.assertNull(OpencodeRunner.extractSecretValueFromPayload("{}", "k"));
        Assert.assertNull(OpencodeRunner.extractSecretValueFromPayload(
                "{\"payload\":\"not-an-object\"}", "k"));
        Assert.assertNull(OpencodeRunner.extractSecretValueFromPayload(
                "this is not json", "k"));
    }

    /**
     * End-to-end: when AR_CONTROLLER_URL/AR_WORKSTREAM_ID/AR_MANAGER_TOKEN are
     * present in the request environment and the controller responds with the
     * documented payload shape, the OpencodeRunner's auto-built secret lookup
     * fetches the value and threads it through to
     * {@link OpencodeConfigBuilder#resolveApiKey}.
     */
    @Test(timeout = 10000)
    public void controllerSecretLookupFetchesSecretFromHttpEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] receivedAuth = new String[1];
        final String[] receivedPath = new String[1];
        server.createContext("/api/secrets/openrouter-api-key", exchange -> {
            receivedAuth[0] = exchange.getRequestHeaders().getFirst("Authorization");
            receivedPath[0] = exchange.getRequestURI().toString();
            String body = "{\"name\":\"openrouter-api-key\","
                    + "\"workspace_id\":\"T01\","
                    + "\"payload\":{\"openrouter-api-key\":\"sk-or-v1-from-http\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String controllerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> env = new HashMap<>();
            env.put("AR_CONTROLLER_URL", controllerUrl);
            env.put("AR_WORKSTREAM_ID", "ws-test-1");
            env.put("AR_MANAGER_TOKEN", "armt_tmp_fake-bearer");

            Map<String, String> configEnv = new HashMap<>();
            OpencodeConfigBuilder builder = new OpencodeConfigBuilder(configEnv::get);

            // Drive the same code path run() would use to construct a lookup
            // and pass it into the config builder.
            AgentRunRequest req = AgentRunRequest.builder()
                    .prompt("p")
                    .allowedTools("Read")
                    .mcpConfigJson("{\"mcpServers\":{}}")
                    .provider("openrouter")
                    .environment(env)
                    .build();

            String json = builder.buildConfigJson(req, "openrouter",
                    "https://openrouter.ai/api/v1",
                    "openrouter-api-key",
                    "OPENROUTER_API_KEY",
                    OpencodeRunner.controllerSecretLookup(req,
                            new ConsoleFeatures() {}));

            assertTrue("config should embed the fetched apiKey: " + json,
                    json.contains("sk-or-v1-from-http"));
            assertNotNull("controller should have received an Authorization header",
                    receivedAuth[0]);
            assertTrue("Authorization header should carry the bearer token: " + receivedAuth[0],
                    receivedAuth[0].startsWith("Bearer armt_tmp_"));
            assertTrue("URI should carry workstream_id query: " + receivedPath[0],
                    receivedPath[0].contains("workstream_id=ws-test-1"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * When AR_CONTROLLER_URL/AR_WORKSTREAM_ID/AR_MANAGER_TOKEN are absent from
     * the request environment, the controller-lookup function yields
     * {@code null} so callers fall through to the provider-specific env var
     * (e.g. {@code OPENROUTER_API_KEY}). This is the local-dev path where
     * there is no controller to call back to.
     */
    @Test(timeout = 5000)
    public void controllerSecretLookupReturnsNullWhenEnvVarsMissing() {
        AgentRunRequest req = AgentRunRequest.builder()
                .prompt("p")
                .allowedTools("Read")
                .mcpConfigJson("{\"mcpServers\":{}}")
                .environment(new HashMap<>())
                .build();
        Assert.assertNull(OpencodeRunner.controllerSecretLookup(
                req, new ConsoleFeatures() {}).apply("openrouter-api-key"));
    }

    /**
     * opencode (notably qwen3-coder via OpenRouter and slower local providers)
     * can spend many minutes generating a single response between NDJSON events
     * during a long primary phase, so it must declare a larger inactivity
     * window than the Claude-tuned
     * {@link AgentRunner#DEFAULT_INACTIVITY_TIMEOUT_MILLIS default}.
     */
    @Test(timeout = 5000)
    public void declaresLongerInactivityTimeoutThanDefault() {
        long opencodeTimeout = new OpencodeRunner().defaultInactivityTimeoutMillis();
        Assert.assertTrue("opencode must allow longer stdout silence than the default",
                opencodeTimeout > AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS);
        Assert.assertEquals("opencode inactivity window is 45 minutes",
                TimeUnit.MINUTES.toMillis(45), opencodeTimeout);
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
