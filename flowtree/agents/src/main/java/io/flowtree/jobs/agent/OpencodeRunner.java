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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.jobs.AgentProcessRunner;
import org.almostrealism.io.ConsoleFeatures;

import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link AgentRunner} backed by the {@code opencode} CLI, targeted primarily
 * at local OpenAI-compatible providers (llama.cpp server, ollama).
 *
 * <p>This runner is the first-class path for migrating selected phases of the
 * coding-agent job (deduplication audit, organizational placement, commit
 * message, post-completion correction) onto a local model that an operator
 * runs on their own network. opencode also supports cloud providers; the
 * provider URL and API key are configurable via environment variables so the
 * same runner reaches either case without code changes.</p>
 *
 * <p>The runner owns binary discovery (see {@link OpencodeBinaryLocator}),
 * config-file synthesis ({@link OpencodeConfigBuilder}), subprocess launch
 * (via the runner-agnostic {@link AgentProcessRunner}), and output parsing
 * ({@link OpencodeOutputParser}).</p>
 *
 * @author Michael Murray
 */
public class OpencodeRunner implements AgentRunner {

    /** Canonical runner name used on the wire and registered with {@link AgentRunnerRegistry}. */
    public static final String NAME = "opencode";

    /** Discovery helper for the opencode binary. */
    private final Supplier<OpencodeBinaryLocator> locatorSupplier;
    /** Config builder used to synthesize the JSON config file. */
    private final Supplier<OpencodeConfigBuilder> configBuilderSupplier;
    /** Lazily resolved path to the opencode binary; null until first use. */
    private Path binaryPath;
    /** Lazily resolved opencode version string; null until probed. */
    private String binaryVersion;
    /** Workspace secret accessor; overridable for tests. */
    private Function<String, String> secretLookup;

    /**
     * Shared JSON mapper for parsing secret-lookup payloads. {@link ObjectMapper}
     * is thread-safe once configured; reusing a single instance avoids the
     * per-call allocation cost.
     */
    private static final ObjectMapper SECRET_PAYLOAD_MAPPER = new ObjectMapper();

    /**
     * Shared HTTP client for the secret-lookup endpoint. The JDK
     * {@link HttpClient} is thread-safe and pools connections internally;
     * reusing a single instance avoids repeated TLS setup across secret
     * fetches within a JVM lifetime.
     */
    private static final HttpClient SECRET_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Known providers and their configuration. */
    private static final Map<String, ProviderConfig> PROVIDER_MAP = new LinkedHashMap<>();
    static {
        // Local provider: uses OPENCODE_PROVIDER_URL env var, default ollama/llama.cpp
        PROVIDER_MAP.put(OpencodeConfigBuilder.PROVIDER_ID, new ProviderConfig(
                OpencodeConfigBuilder.DEFAULT_PROVIDER_URL,
                null,   // no workspace secret required
                null,   // no provider-specific env var
                false   // local-model cost is meaningless
        ));
        // OpenRouter provider: uses OpenRouter endpoint with workspace secret
        PROVIDER_MAP.put("openrouter", new ProviderConfig(
                "https://openrouter.ai/api/v1",
                "openrouter-api-key",
                "OPENROUTER_API_KEY",
                true    // OpenRouter reports cost
        ));
        // Anthropic provider: uses Anthropic OpenAI-compatible endpoint
        PROVIDER_MAP.put("anthropic", new ProviderConfig(
                "https://api.anthropic.com/v1",
                "anthropic-api-key",
                "ANTHROPIC_API_KEY",
                true    // Anthropic reports cost
        ));
    }

    /**
     * Provider configuration: base URL, workspace secret name (or null), provider-specific
     * environment variable name (or null), and cost reporting flag. This record is the
     * single source of truth for per-provider connection details; both the runner and the
     * config builder receive values from here rather than duplicating the mapping.
     */
    private record ProviderConfig(String url, String secretName, String envVarName, boolean reportsCost) {}

    /** Constructs a runner with the default locator and config builder. */
    public OpencodeRunner() {
        this(OpencodeBinaryLocator::new, OpencodeConfigBuilder::new, null);
    }

    /**
     * Backwards-compatible two-argument constructor used by tests and call
     * sites from before the workspace-secret lookup was added. Delegates to
     * the three-argument form with a {@code null} secret lookup.
     *
     * @param locatorSupplier       produces a binary locator on demand
     * @param configBuilderSupplier produces a config builder on demand
     */
    OpencodeRunner(Supplier<OpencodeBinaryLocator> locatorSupplier,
                   Supplier<OpencodeConfigBuilder> configBuilderSupplier) {
        this(locatorSupplier, configBuilderSupplier, null);
    }

    /**
     * Constructs a runner with overridable locator and config-builder factories
     * for testing.
     *
     * @param locatorSupplier        produces a binary locator on demand
     * @param configBuilderSupplier  produces a config builder on demand
     * @param secretLookup           optional workspace secret lookup; when null,
     *                               reads from System.getenv as fallback
     */
    OpencodeRunner(Supplier<OpencodeBinaryLocator> locatorSupplier,
                   Supplier<OpencodeConfigBuilder> configBuilderSupplier,
                   Function<String, String> secretLookup) {
        this.locatorSupplier = locatorSupplier;
        this.configBuilderSupplier = configBuilderSupplier;
        this.secretLookup = secretLookup;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public AgentCapabilities capabilities() {
        return new AgentCapabilities(
                true,   // reportsCost — cloud providers (openrouter, anthropic) report cost
                true,   // reportsTurns — best effort, can be wrong
                false,  // supportsEffortLevel
                false,  // supportsMaxBudget
                true,   // supportsMcpHttpTransport
                true,   // supportsMcpStdioTransport
                false,  // supportsPermissionDenialReporting (until proven)
                Set.of(),
                Set.copyOf(PROVIDER_MAP.keySet()));
    }

    @Override
    public String defaultProvider() {
        return OpencodeConfigBuilder.PROVIDER_ID;
    }

    /**
     * Stdout-silence window for opencode sessions. opencode (notably
     * qwen3-coder via OpenRouter, and slower local llama.cpp providers) can
     * spend several minutes generating a single response between NDJSON
     * events during a long primary phase, so the {@value
     * AgentRunner#DEFAULT_INACTIVITY_TIMEOUT_MILLIS}-millisecond default tuned
     * for Claude Code's faster cadence risks killing legitimate work. A longer
     * window is safe here because the {@link #probeProviderUrl provider
     * liveness probe} already fails fast when the upstream is actually down,
     * leaving this watchdog as a backstop only for a truly wedged subprocess.
     */
    private static final long OPENCODE_INACTIVITY_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(45);

    @Override
    public long defaultInactivityTimeoutMillis() {
        return OPENCODE_INACTIVITY_TIMEOUT_MILLIS;
    }

    /**
     * Sets the workspace secret lookup function. This is used to fetch
     * API keys from the workspace secrets store.
     *
     * @param secretLookup function that takes a secret name and returns
     *                     the secret value, or null if not found
     */
    public void setSecretLookup(Function<String, String> secretLookup) {
        this.secretLookup = secretLookup;
    }

    /**
     * Pre-flight check: cloud providers (anything other than
     * {@link OpencodeConfigBuilder#PROVIDER_ID local}) require a resolvable API
     * key before opencode is invoked. The builder silently omits the
     * {@code apiKey} field when {@link OpencodeConfigBuilder#resolveApiKey}
     * returns empty, which lets opencode launch and post requests that the
     * upstream provider rejects with 401 — slow, opaque failure mode whose
     * symptom (zero token usage on the provider account) is easy to misread
     * as "the runner is still hitting the local endpoint." Failing here, with
     * the specific names that were tried, makes that misconfiguration
     * impossible to confuse with anything else.
     *
     * @param config         the builder used to resolve the key
     * @param provider       the resolved provider identifier
     * @param providerConfig the matching {@link ProviderConfig} from
     *                       {@link #PROVIDER_MAP}
     * @param secretLookup   the workspace secret lookup function in effect
     * @throws IllegalStateException when no API key can be resolved from
     *         {@link OpencodeConfigBuilder#ENV_API_KEY}, the workspace
     *         secret named in {@code providerConfig}, or the provider-specific
     *         env var named in {@code providerConfig}
     */
    private void requireApiKeyForCloudProvider(OpencodeConfigBuilder config,
                                               String provider,
                                               ProviderConfig providerConfig,
                                               Function<String, String> secretLookup) {
        if (OpencodeConfigBuilder.PROVIDER_ID.equals(provider)) return;
        String key = config.resolveApiKey(providerConfig.secretName(),
                providerConfig.envVarName(), secretLookup);
        if (!key.isEmpty()) return;
        throw new IllegalStateException(
                "No API key available for provider '" + provider + "'. Tried: "
                        + OpencodeConfigBuilder.ENV_API_KEY + " env var, workspace secret '"
                        + providerConfig.secretName() + "', and "
                        + providerConfig.envVarName() + " env var.");
    }

    /**
     * Resolves the provider to use: request provider takes priority, then
     * runner's defaultProvider(), falling back to {@link OpencodeConfigBuilder#PROVIDER_ID}.
     *
     * @param request the run request
     * @return the resolved provider identifier
     */
    private String resolveProvider(AgentRunRequest request) {
        String p = request.getProvider();
        if (p != null && !p.isEmpty()) return p;
        String d = defaultProvider();
        return (d != null && !d.isEmpty()) ? d : OpencodeConfigBuilder.PROVIDER_ID;
    }

    @Override
    public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (logger == null) throw new IllegalArgumentException("logger must not be null");

        String provider = resolveProvider(request);
        ProviderConfig providerConfig = PROVIDER_MAP.get(provider);
        if (providerConfig == null) {
            throw new IllegalArgumentException("Unknown provider: " + provider
                    + " (available: " + PROVIDER_MAP.keySet() + ")");
        }

        Path binary = ensureBinary();
        OpencodeConfigBuilder config = configBuilderSupplier.get();
        Function<String, String> effectiveSecretLookup = secretLookup != null
                ? secretLookup
                : controllerSecretLookup(request, logger);
        // Validate API key BEFORE the URL probe so misconfiguration fails
        // fast with a clear message instead of letting the probe block on
        // network I/O only to be followed by an opaque 401 from the upstream.
        requireApiKeyForCloudProvider(config, provider, providerConfig,
                effectiveSecretLookup);
        String providerUrl = config.resolveProviderUrl(provider, providerConfig.url());
        probeProviderUrl(providerUrl, logger);
        String configJson = config.buildConfigJson(request, provider, providerUrl,
                providerConfig.secretName(), providerConfig.envVarName(),
                effectiveSecretLookup);
        Path configPath = writeConfigFile(request, configJson, logger);
        String qualifiedModel = config.resolveQualifiedModel(provider, request.getModel());

        List<String> command = buildCommandLine(binary, request, configPath, qualifiedModel);

        logger.log("Starting opencode session");
        logger.log("Tools: " + request.getAllowedTools());
        logger.log("Provider: " + provider);
        logger.log("Provider URL: " + providerUrl);
        logger.log("Model: " + qualifiedModel);
        if (request.getInactivityRestartAttempt() > 0) {
            logger.log("Inactivity restart attempt: " + (request.getInactivityRestartAttempt() + 1)
                    + " of " + (request.getMaxInactivityRestarts() + 1));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        AgentProcessRunner.applyRequestToProcessBuilder(pb, request);
        // Must be absolute: opencode resolves OPENCODE_CONFIG relative to the
        // subprocess cwd, which is the agent's working directory (the repo
        // clone), not the directory where the runner wrote the config file.
        pb.environment().put("OPENCODE_CONFIG", configPath.toAbsolutePath().toString());

        Path workDir = request.getWorkingDirectory();
        logger.log("Command: " + String.join(" ", command));
        logger.log("Working directory: "
                + (workDir != null ? workDir.toString() : System.getProperty("user.dir")));

        try {
            long start = System.currentTimeMillis();
            AgentProcessRunner.Result processResult = AgentProcessRunner.runAttempt(
                    pb,
                    request.getInactivityTimeoutMillis(),
                    request.getTaskId(),
                    logger);
            long endMs = System.currentTimeMillis();
            long durationMs = endMs - start;

            String rawOutput = processResult.output();
            if (request.getOutputCapturePath() != null) {
                try (FileWriter writer = new FileWriter(request.getOutputCapturePath().toFile(), true)) {
                    writer.write(rawOutput);
                } catch (IOException e) {
                    logger.warn("Failed to save opencode output to "
                            + request.getOutputCapturePath() + ": " + e.getMessage());
                }
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("opencode_binary_path", binary.toString());
            if (binaryVersion != null) {
                metadata.put("opencode_version", binaryVersion);
            }
            metadata.put("provider", provider);
            metadata.put("provider_url", providerUrl);
            metadata.put("model", qualifiedModel);

            AgentRunResult result = OpencodeOutputParser.parse(
                    rawOutput,
                    processResult.exitCode(),
                    processResult.killedForInactivity(),
                    durationMs,
                    metadata,
                    logger,
                    providerConfig.reportsCost());

            OpencodeTranscriptWriter.forRequest(request).write(request, result, start, endMs, logger);
            return result;
        } finally {
            deleteConfigFile(configPath, logger);
        }
    }

    /**
     * Builds the {@code opencode} command line. Exposed so tests can assert
     * the exact flags without running the subprocess.
     *
     * <p>The {@code configPath} is intentionally not passed on the argv —
     * opencode 1.x removed {@code --config}. The runner injects it instead
     * via the {@code OPENCODE_CONFIG} environment variable when launching the
     * process (see {@link #run}). The parameter is retained so the runner can
     * synthesize and persist the JSON before launch and so the test surface
     * stays stable.</p>
     *
     * @param binary         resolved binary path
     * @param request        the run request
     * @param configPath     path to the synthesized config file (passed via
     *                       {@code OPENCODE_CONFIG} at launch, not on argv)
     * @param qualifiedModel resolved provider/model identifier
     * @return the argv list passed to {@link ProcessBuilder}
     */
    public List<String> buildCommandLine(Path binary,
                                         AgentRunRequest request,
                                         Path configPath,
                                         String qualifiedModel) {
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.add("run");
        command.add("--model");
        command.add(qualifiedModel);
        command.add("--format");
        command.add("json");
        command.add("--dangerously-skip-permissions");
        // opencode walks UP from the subprocess cwd looking for a project
        // root and falls back to the user's home directory when none is
        // found, producing a "global" projectID with every absolute file
        // path treated as external_directory. Pin the session root to the
        // request's working directory so the repo clone IS the project
        // root and subagents inherit it.
        Path workDir = request.getWorkingDirectory();
        if (workDir != null) {
            command.add("--dir");
            command.add(workDir.toAbsolutePath().toString());
        }
        command.add(request.getPrompt() != null ? request.getPrompt() : "");
        return command;
    }

    /**
     * Resolves the opencode binary path, caching the result. Also probes
     * {@code opencode --version} once for inclusion in result metadata.
     *
     * @return the absolute binary path
     * @throws AgentRunnerNotAvailableException when no binary can be found
     */
    private synchronized Path ensureBinary() {
        if (binaryPath != null) {
            return binaryPath;
        }
        binaryPath = locatorSupplier.get().locate();
        binaryVersion = probeVersion(binaryPath);
        return binaryPath;
    }

    /**
     * Runs {@code opencode --version} and returns the trimmed stdout, or
     * {@code null} when the probe fails. Probe failure does not block runner
     * launch — operators can still run jobs with an unknown version.
     *
     * @param binary the resolved binary path
     * @return the version string or {@code null}
     */
    private static String probeVersion(Path binary) {
        try {
            ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            byte[] data = p.getInputStream().readAllBytes();
            String text = new String(data, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Probes the configured provider URL to confirm it is reachable before
     * launching the opencode subprocess. Sends a HEAD request and accepts ANY
     * HTTP response (including 4xx/5xx) as evidence the upstream is alive — the
     * probe is checking TCP+HTTP layer health, not API correctness. Only
     * connection refused, DNS failure, or read/connect timeout is treated as
     * "down."
     *
     * <p>Without this probe, when the provider (e.g. a local llama-server) has
     * crashed, opencode spawns successfully, blocks waiting for the upstream,
     * and only fails when {@link io.flowtree.jobs.AgentInactivityMonitor} kills
     * it after the inactivity window — burning the configured restart attempts
     * in the process. Failing here returns control to the orchestrator in
     * seconds instead of tens of minutes.</p>
     *
     * <p>Can be bypassed by setting the {@code OPENCODE_SKIP_PROVIDER_PROBE}
     * environment variable to any truthy value ({@code 1}, {@code true}, etc.).
     * The connect/read timeout is configurable via
     * {@code OPENCODE_PROVIDER_PROBE_TIMEOUT_MS}; see
     * {@link #resolveProbeTimeout()} for the default.</p>
     *
     * @param providerUrl resolved provider base URL (e.g. {@code http://mac-studio:8084/v1})
     * @param logger      diagnostic sink
     * @throws AgentRunnerNotAvailableException when the URL is malformed or
     *                                          the provider is unreachable
     */
    void probeProviderUrl(String providerUrl, ConsoleFeatures logger) {
        if (isProbeSkipped()) {
            logger.log("Provider liveness probe skipped (OPENCODE_SKIP_PROVIDER_PROBE)");
            return;
        }
        URI uri;
        try {
            uri = URI.create(providerUrl);
        } catch (IllegalArgumentException e) {
            throw new AgentRunnerNotAvailableException(
                    "Invalid provider URL " + providerUrl + ": " + e.getMessage(), e);
        }
        Duration timeout = resolveProbeTimeout();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(timeout)
                .build();
        try {
            HttpResponse<Void> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.discarding());
            logger.log("Provider liveness probe: " + providerUrl
                    + " responded HTTP " + response.statusCode());
        } catch (IOException e) {
            throw new AgentRunnerNotAvailableException(
                    "Provider URL " + providerUrl + " is unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentRunnerNotAvailableException(
                    "Provider liveness probe interrupted for " + providerUrl, e);
        }
    }

    /**
     * Builds a workspace-secret lookup function that resolves a secret name by
     * calling the FlowTree controller's {@code /api/secrets/{name}} endpoint.
     *
     * <p>This is the default lookup used when no explicit {@link #setSecretLookup}
     * has been wired in. It reads {@code AR_CONTROLLER_URL},
     * {@code AR_WORKSTREAM_ID}, and {@code AR_MANAGER_TOKEN} from
     * {@link AgentRunRequest#getEnvironment()} — the same env values the
     * controller already places on the agent context for {@code ar-secrets}
     * (see {@code McpConfigBuilder.applyAgentEnvironment}). When any of those
     * three is missing the returned function always yields {@code null}, so
     * {@link OpencodeConfigBuilder#resolveApiKey} cleanly falls through to the
     * provider-specific environment variable.</p>
     *
     * <p>The secret value is extracted from the response payload's value keyed
     * by {@code secretName} (so a payload like
     * {@code {"openrouter-api-key": "sk-or-..."}} matches the configured secret
     * name in {@code OpencodeRunner.PROVIDER_MAP}). When the payload contains
     * exactly one key, that single value is returned instead, accommodating
     * operators who prefer {@code {"value": "sk-..."}}.</p>
     *
     * <p>Network failures and non-2xx responses are logged at warn level and
     * yield {@code null}, never throw — the caller will fall through to the
     * env-var path so a transient controller outage doesn't fail-fast the
     * subprocess launch.</p>
     *
     * @param request the agent-run request whose environment carries the
     *                controller URL / workstream ID / bearer token
     * @param logger  diagnostic sink
     * @return a {@code name -> value} function backed by the controller endpoint
     */
    static Function<String, String> controllerSecretLookup(
            AgentRunRequest request, ConsoleFeatures logger) {
        Map<String, String> env = request.getEnvironment();
        if (env == null) return name -> null;
        String controllerUrl = env.get("AR_CONTROLLER_URL");
        String workstreamId = env.get("AR_WORKSTREAM_ID");
        String managerToken = env.get("AR_MANAGER_TOKEN");
        if (controllerUrl == null || controllerUrl.isEmpty()
                || workstreamId == null || workstreamId.isEmpty()
                || managerToken == null || managerToken.isEmpty()) {
            return name -> null;
        }
        String base = controllerUrl.endsWith("/")
                ? controllerUrl.substring(0, controllerUrl.length() - 1)
                : controllerUrl;
        return secretName -> fetchSecretValue(
                base, workstreamId, managerToken, secretName, logger);
    }

    /**
     * Performs the single secret fetch behind {@link #controllerSecretLookup}.
     * Issues {@code GET /api/secrets/{name}?workstream_id=...} with a
     * {@code Bearer} authorisation header and parses the JSON response for the
     * payload value. Returns {@code null} on any failure path.
     *
     * @param controllerBaseUrl the controller base URL (no trailing slash)
     * @param workstreamId      the requesting workstream ID
     * @param managerToken      the {@code armt_tmp_...} bearer token
     * @param secretName        the secret to fetch
     * @param logger            diagnostic sink
     * @return the resolved value, or {@code null} when missing/unfetched
     */
    private static String fetchSecretValue(String controllerBaseUrl,
                                           String workstreamId,
                                           String managerToken,
                                           String secretName,
                                           ConsoleFeatures logger) {
        if (secretName == null || secretName.isEmpty()) return null;
        String encodedName = URLEncoder.encode(secretName, StandardCharsets.UTF_8);
        String encodedWs = URLEncoder.encode(workstreamId, StandardCharsets.UTF_8);
        URI uri;
        try {
            uri = URI.create(controllerBaseUrl + "/api/secrets/" + encodedName
                    + "?workstream_id=" + encodedWs);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid controller URL for secret fetch: " + e.getMessage());
            return null;
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .GET()
                .header("Authorization", "Bearer " + managerToken)
                .timeout(Duration.ofSeconds(10))
                .build();
        try {
            HttpResponse<String> response = SECRET_HTTP_CLIENT.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status != 404) {
                    logger.warn("Secret fetch HTTP " + status + " for " + secretName);
                }
                return null;
            }
            return extractSecretValueFromPayload(response.body(), secretName);
        } catch (IOException e) {
            logger.warn("Secret fetch failed for " + secretName + ": " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Secret fetch interrupted for " + secretName);
            return null;
        }
    }

    /**
     * Parses the {@code /api/secrets/{name}} response body and pulls a value
     * out of the {@code payload} object. Looks for the key matching
     * {@code secretName} first; when absent, falls back to the single value
     * when the payload has exactly one key. Returns {@code null} for any
     * unrecognised shape — the caller treats {@code null} as "secret missing"
     * and proceeds to the env-var fallback.
     *
     * @param body       the raw JSON response body
     * @param secretName the secret name (also used as the preferred payload key)
     * @return the resolved value or {@code null}
     */
    static String extractSecretValueFromPayload(String body, String secretName) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonNode root = SECRET_PAYLOAD_MAPPER.readTree(body);
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) return null;
            JsonNode direct = payload.get(secretName);
            if (direct != null && direct.isTextual() && !direct.asText().isEmpty()) {
                return direct.asText();
            }
            if (payload.size() == 1) {
                JsonNode only = payload.fields().next().getValue();
                if (only.isTextual() && !only.asText().isEmpty()) {
                    return only.asText();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads the {@code OPENCODE_SKIP_PROVIDER_PROBE} environment variable and
     * returns whether the probe should be skipped. Recognizes {@code 1},
     * {@code true}, {@code yes} (case-insensitive) as truthy.
     *
     * @return {@code true} if the probe is disabled
     */
    private static boolean isProbeSkipped() {
        String raw = System.getenv("OPENCODE_SKIP_PROVIDER_PROBE");
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String value = raw.trim().toLowerCase();
        return value.equals("1") || value.equals("true") || value.equals("yes");
    }

    /**
     * Returns the probe connect/read timeout. Configurable via the JVM system
     * property {@code opencode.provider.probe.timeout.ms} (preferred for tests)
     * or the environment variable {@code OPENCODE_PROVIDER_PROBE_TIMEOUT_MS}.
     * Defaults to {@value #DEFAULT_PROBE_TIMEOUT_MS} milliseconds when neither
     * is set or both are unparseable.
     *
     * @return the probe timeout
     */
    private static Duration resolveProbeTimeout() {
        String prop = System.getProperty("opencode.provider.probe.timeout.ms");
        Duration parsed = parseMillis(prop);
        if (parsed != null) return parsed;
        parsed = parseMillis(System.getenv("OPENCODE_PROVIDER_PROBE_TIMEOUT_MS"));
        if (parsed != null) return parsed;
        return Duration.ofMillis(DEFAULT_PROBE_TIMEOUT_MS);
    }

    /**
     * Default probe connect/read timeout when no override is configured.
     * Two seconds is long enough to accommodate a slow upstream while still
     * giving the orchestrator a quick failure signal when the provider is
     * unreachable (vs. waiting on the inactivity watchdog).
     */
    private static final long DEFAULT_PROBE_TIMEOUT_MS = 2000L;

    /**
     * Parses a millisecond duration string, returning {@code null} when blank
     * or unparseable.
     */
    private static Duration parseMillis(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0 ? Duration.ofMillis(millis) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Writes the synthesized config JSON to a temp file next to
     * {@link AgentRunRequest#getOutputCapturePath()} when available, or to the
     * system temp directory otherwise.
     *
     * @param request the run request (provides the capture path neighbour)
     * @param json    the JSON content
     * @param logger  diagnostic sink
     * @return the absolute path of the written file
     */
    private Path writeConfigFile(AgentRunRequest request, String json, ConsoleFeatures logger) {
        Path capture = request.getOutputCapturePath();
        Path dir;
        try {
            if (capture != null && capture.getParent() != null) {
                Files.createDirectories(capture.getParent());
                dir = capture.getParent();
            } else {
                dir = Files.createTempDirectory("opencode-config-");
            }
            Path file = Files.createTempFile(dir, "opencode-config-", ".json");
            try {
                // Restrict to owner read/write only so API keys written next are not
                // world-readable on POSIX filesystems. Non-POSIX systems skip silently.
                Files.setPosixFilePermissions(file,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem; default permissions apply
            } catch (IOException e) {
                // Best-effort hardening: if chmod fails (e.g., filesystem refuses
                // POSIX bits), continue with whatever permissions the OS produced
                // rather than aborting the run.
                logger.warn("Failed to restrict opencode config file permissions: "
                        + e.getMessage());
            }
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            logger.warn("Failed to write opencode config file: " + e.getMessage());
            throw new AgentRunnerNotAvailableException(
                    "Failed to write opencode config file", e);
        }
    }

    /**
     * Deletes the synthesized config file after the subprocess exits. When the
     * file lives inside a directory whose name starts with {@code "opencode-config-"}
     * (i.e., a temp directory created by this runner), the directory is also removed.
     *
     * @param configFile the config file path returned by {@link #writeConfigFile}
     * @param logger     diagnostic sink for deletion warnings
     */
    private void deleteConfigFile(Path configFile, ConsoleFeatures logger) {
        try {
            Files.deleteIfExists(configFile);
            Path dir = configFile.getParent();
            if (dir != null && dir.getFileName() != null
                    && dir.getFileName().toString().startsWith("opencode-config-")) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete opencode config file: " + e.getMessage());
        }
    }

    /**
     * Returns the runner's lazily resolved binary path, or {@code null} when
     * the locator has not yet been invoked. Exposed for tests.
     *
     * @return the cached binary path
     */
    Path getBinaryPath() {
        return binaryPath;
    }

    /**
     * Returns the runner's lazily resolved version string, or {@code null}
     * when no probe has occurred. Exposed for tests.
     *
     * @return the cached binary version
     */
    String getBinaryVersion() {
        return binaryVersion;
    }

}
