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

import io.flowtree.jobs.AgentProcessRunner;
import org.almostrealism.io.ConsoleFeatures;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    /** Constructs a runner with the default locator and config builder. */
    public OpencodeRunner() {
        this(OpencodeBinaryLocator::new, OpencodeConfigBuilder::new);
    }

    /**
     * Constructs a runner with overridable locator and config-builder factories
     * for testing.
     *
     * @param locatorSupplier        produces a binary locator on demand
     * @param configBuilderSupplier  produces a config builder on demand
     */
    OpencodeRunner(Supplier<OpencodeBinaryLocator> locatorSupplier,
                   Supplier<OpencodeConfigBuilder> configBuilderSupplier) {
        this.locatorSupplier = locatorSupplier;
        this.configBuilderSupplier = configBuilderSupplier;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public AgentCapabilities capabilities() {
        return new AgentCapabilities(
                false,  // reportsCost — local-model cost is meaningless; mark N/A
                true,   // reportsTurns — best effort, can be wrong
                false,  // supportsEffortLevel
                false,  // supportsMaxBudget
                true,   // supportsMcpHttpTransport
                true,   // supportsMcpStdioTransport
                false,  // supportsPermissionDenialReporting (until proven)
                Set.of());
    }

    @Override
    public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (logger == null) throw new IllegalArgumentException("logger must not be null");

        Path binary = ensureBinary();
        OpencodeConfigBuilder config = configBuilderSupplier.get();
        String configJson = config.buildConfigJson(request);
        Path configPath = writeConfigFile(request, configJson, logger);
        String providerUrl = config.resolveProviderUrl();
        String qualifiedModel = config.resolveQualifiedModel(request.getModel());

        List<String> command = buildCommandLine(binary, request, configPath, qualifiedModel);

        logger.log("Starting opencode session");
        logger.log("Tools: " + request.getAllowedTools());
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

        long start = System.currentTimeMillis();
        AgentProcessRunner.Result processResult = AgentProcessRunner.runAttempt(
                pb,
                request.getInactivityTimeoutMillis(),
                request.getTaskId(),
                logger);
        long durationMs = System.currentTimeMillis() - start;

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
        metadata.put("provider_url", providerUrl);
        metadata.put("model", qualifiedModel);

        return OpencodeOutputParser.parse(
                rawOutput,
                processResult.exitCode(),
                processResult.killedForInactivity(),
                durationMs,
                metadata,
                logger);
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
            Files.writeString(file, json, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            logger.warn("Failed to write opencode config file: " + e.getMessage());
            throw new AgentRunnerNotAvailableException(
                    "Failed to write opencode config file", e);
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
