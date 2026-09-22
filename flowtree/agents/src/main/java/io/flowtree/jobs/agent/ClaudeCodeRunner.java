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

import static io.flowtree.JsonFieldExtractor.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.AgentActivity;
import io.flowtree.jobs.AgentActivityTracker;
import io.flowtree.jobs.AgentProcessRunner;
import io.flowtree.jobs.TmuxSession;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link AgentRunner} that launches the Claude Code CLI to run an agent
 * session.
 *
 * <p>This class owns every Claude-specific concern that used to live in the
 * orchestrator: the {@code claude -p ... --allowedTools ... --output-format
 * stream-json ...} command construction, model and effort validation, NDJSON
 * output parsing, output-file dumping, and {@code AR_AGENT_ACTIVITY}
 * environment propagation. The orchestrator only hands over a runner-agnostic
 * {@link AgentRunRequest} and receives a {@link AgentRunResult} back.</p>
 *
 * <p>The actual subprocess management — starting the process, reading stdout
 * with an inactivity watchdog, surviving stale processes — is delegated to
 * {@link AgentProcessRunner}, which is runner-agnostic.</p>
 *
 * <p>Output is requested as {@code stream-json} rather than {@code json}
 * because the single-object form is written only when the session ends, which
 * leaves the inactivity watchdog with nothing to observe for the whole run.
 * The event stream is also what {@link #classifyActivity} reads to tell the
 * watchdog when the agent is waiting on an MCP tool rather than hung.</p>
 *
 * @author Michael Murray
 */
public class ClaudeCodeRunner implements AgentRunner {

    /** Canonical runner name on the wire. */
    public static final String NAME = "claude";

    /** Valid values for the Claude Code {@code --effort} flag (thinking level). */
    public static final List<String> VALID_EFFORT_LEVELS =
            List.of("low", "medium", "high", "xhigh", "max");

    /** Accepted values for the Claude Code {@code --model} flag (CLI aliases + full IDs). */
    public static final List<String> VALID_MODELS = List.of(
            "sonnet", "opus", "haiku",
            "claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5-20251001");

    /** Path of the binary the runner will launch. Overridable for tests. */
    private final String binaryPath;

    /** Constructs a runner that launches the {@code claude} binary from the user's PATH. */
    public ClaudeCodeRunner() {
        this("claude");
    }

    /**
     * Constructs a runner that launches the specified binary.
     *
     * @param binaryPath the executable to launch (e.g. {@code "claude"})
     */
    public ClaudeCodeRunner(String binaryPath) {
        if (binaryPath == null || binaryPath.isEmpty()) {
            throw new IllegalArgumentException("binaryPath must not be null or empty");
        }
        this.binaryPath = binaryPath;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public AgentCapabilities capabilities() {
        Set<String> models = new LinkedHashSet<>(VALID_MODELS);
        Set<String> providers = Set.of("anthropic");
        return new AgentCapabilities(
                true,   // reportsCost
                true,   // reportsTurns
                true,   // supportsEffortLevel
                true,   // supportsMaxBudget
                true,   // supportsMcpHttpTransport
                true,   // supportsMcpStdioTransport
                true,   // supportsPermissionDenialReporting
                models,
                providers);
    }

    @Override
    public String defaultProvider() {
        return "anthropic";
    }

    /**
     * Returns {@code true} when {@code model} is recognised by this runner.
     *
     * @param model identifier from {@link AgentRunRequest#getModel()}
     * @return {@code true} when {@code model} is null, empty, or in {@link #VALID_MODELS}
     */
    public boolean isModelSupported(String model) {
        return model == null || model.isEmpty() || VALID_MODELS.contains(model);
    }

    /**
     * Returns {@code true} when {@code effort} is recognised by this runner.
     *
     * @param effort identifier from {@link AgentRunRequest#getEffort()}
     * @return {@code true} when {@code effort} is null, empty, or in {@link #VALID_EFFORT_LEVELS}
     */
    public boolean isEffortSupported(String effort) {
        return effort == null || effort.isEmpty() || VALID_EFFORT_LEVELS.contains(effort);
    }

    @Override
    public AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (logger == null) throw new IllegalArgumentException("logger must not be null");
        validateRequest(request);

        List<String> command = buildCommandLine(request);

        logger.log("Starting Claude Code session");
        logger.log("Tools: " + request.getAllowedTools());
        if (request.getInactivityRestartAttempt() > 0) {
            logger.log("Inactivity restart attempt: " + (request.getInactivityRestartAttempt() + 1)
                    + " of " + (request.getMaxInactivityRestarts() + 1));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        AgentProcessRunner.applyRequestToProcessBuilder(pb, request);

        Path workDir = request.getWorkingDirectory();
        logger.log("Command: " + String.join(" ", command));
        logger.log("Working directory: "
                + (workDir != null ? workDir.toString() : System.getProperty("user.dir")));

        // Tmux is opt-in. The per-job request flag (set at submission time) and
        // the AR_AGENT_USE_TMUX env var are independent enables: either one being
        // on requests tmux. Default is off so deployments do not flip launch
        // backends on merge without an explicit operator or submitter decision.
        boolean tmuxRequested = request.isUseTmux()
                || SystemUtils.isEnabled("AR_AGENT_USE_TMUX").orElse(false);
        boolean useTmux = tmuxRequested && TmuxSession.isAvailable();
        if (tmuxRequested && !useTmux) {
            logger.warn("tmux launch was requested but tmux is not on PATH;"
                    + " falling back to direct process launch.");
        }
        AgentProcessRunner.Result processResult = AgentProcessRunner.runAttempt(
                pb,
                useTmux,
                request.getInactivityTimeoutMillis(),
                request.getTaskId(),
                null,
                new AgentActivityTracker(this::classifyActivity),
                logger);

        String rawOutput = processResult.output();
        if (request.getOutputCapturePath() != null) {
            try (FileWriter writer = new FileWriter(request.getOutputCapturePath().toFile(), true)) {
                writer.write(rawOutput);
            } catch (IOException e) {
                logger.warn("Failed to save Claude output to "
                        + request.getOutputCapturePath() + ": " + e.getMessage());
            }
        }

        return parseClaudeNdjson(
                rawOutput, processResult.exitCode(), processResult.killedForInactivity(), logger,
                request.getRequiredMcpServers());
    }

    /**
     * Validates the runner-specific fields on {@code request}, throwing
     * {@link IllegalArgumentException} for unrecognised values.
     *
     * @param request the request to validate
     */
    public void validateRequest(AgentRunRequest request) {
        if (!isModelSupported(request.getModel())) {
            throw new IllegalArgumentException("Invalid model '" + request.getModel()
                    + "'. Must be one of " + VALID_MODELS);
        }
        if (!isEffortSupported(request.getEffort())) {
            throw new IllegalArgumentException("Invalid effort level '" + request.getEffort()
                    + "'. Must be one of " + VALID_EFFORT_LEVELS);
        }
    }

    /**
     * Builds the {@code claude} command line for {@code request}. Exposed so
     * tests can assert the exact flags without running the subprocess.
     *
     * @param request the source of prompt, flags, and MCP config
     * @return the argv list passed to {@link ProcessBuilder}
     */
    public List<String> buildCommandLine(AgentRunRequest request) {
        List<String> command = new ArrayList<>();
        command.add(binaryPath);
        command.add("-p");
        command.add(request.getPrompt() != null ? request.getPrompt() : "");
        command.add("--output-format");
        command.add("stream-json");
        // The CLI refuses stream-json in print mode without it.
        command.add("--verbose");
        command.add("--allowedTools");
        command.add(request.getAllowedTools() != null ? request.getAllowedTools() : "");
        command.add("--max-turns");
        command.add(String.valueOf(request.getMaxTurns()));

        if (request.getMaxBudgetUsd() > 0) {
            command.add("--max-budget-usd");
            command.add(String.format("%.2f", request.getMaxBudgetUsd()));
        }

        if (request.getModel() != null && !request.getModel().isEmpty()) {
            command.add("--model");
            command.add(request.getModel());
        }

        if (request.getEffort() != null && !request.getEffort().isEmpty()) {
            command.add("--effort");
            command.add(request.getEffort());
        }

        command.add("--mcp-config");
        command.add(request.getMcpConfigJson() != null ? request.getMcpConfigJson() : "{\"mcpServers\":{}}");
        return command;
    }

    /**
     * Reads the tool-call boundaries out of one line of {@code stream-json}
     * output. An {@code assistant} event opens one call per {@code tool_use}
     * block it carries; a {@code user} event closes one per {@code tool_result}
     * block. Every other line — and any line that is not well-formed JSON —
     * yields nothing.
     *
     * @param line one line of the Claude Code event stream
     * @return the boundaries the line carries, oldest first; empty when none
     */
    public List<AgentActivity> classifyActivity(String line) {
        if (line == null || !line.startsWith("{")
                || (!line.contains("\"tool_use\"") && !line.contains("\"tool_result\""))) {
            return Collections.emptyList();
        }

        JsonNode root;

        try {
            root = MAPPER.readTree(line);
        } catch (IOException e) {
            return Collections.emptyList();
        }

        String type = JsonFieldExtractor.getTextOrNull(root, "type");
        boolean opening = "assistant".equals(type);
        if (!opening && !"user".equals(type)) return Collections.emptyList();

        List<AgentActivity> found = new ArrayList<>();

        for (JsonNode block : root.path("message").path("content")) {
            String blockType = JsonFieldExtractor.getTextOrNull(block, "type");

            if (opening && "tool_use".equals(blockType)) {
                found.add(AgentActivity.started(
                        JsonFieldExtractor.getTextOrNull(block, "id"),
                        JsonFieldExtractor.getTextOrNull(block, "name")));
            } else if (!opening && "tool_result".equals(blockType)) {
                found.add(AgentActivity.finished(
                        JsonFieldExtractor.getTextOrNull(block, "tool_use_id")));
            }
        }

        return found;
    }

    /**
     * Parses Claude Code NDJSON output into an {@link AgentRunResult}.
     *
     * @param jsonOutput           the captured stdout (may be NDJSON or a single object)
     * @param exitCode             the process exit code
     * @param killedForInactivity  whether the inactivity watchdog fired
     * @param logger               target for diagnostics on parse failure
     * @return the parsed result
     */
    public AgentRunResult parseClaudeNdjson(String jsonOutput,
                                            int exitCode,
                                            boolean killedForInactivity,
                                            ConsoleFeatures logger) {
        return parseClaudeNdjson(jsonOutput, exitCode, killedForInactivity, logger, Collections.emptySet());
    }

    /**
     * Parses Claude Code NDJSON output into an {@link AgentRunResult}, also
     * checking the session's {@code init} event for MCP servers that failed
     * to connect.
     *
     * <p>The first event of a {@code stream-json} session is
     * {@code {"type":"system","subtype":"init",...}} and carries
     * {@code mcp_servers: [{name, status}, ...]}. A server whose status is
     * not {@code connected} contributed no tools to the session — every
     * {@code mcp__<name>__*} entry on the allow list was simply absent, and
     * the model could not tell that from a server that was never configured.
     * Every such server is logged; the ones in {@code requiredMcpServers}
     * are reported on the result, where the job turns them into a failure.</p>
     *
     * @param jsonOutput           the captured stdout (may be NDJSON or a single object)
     * @param exitCode             the process exit code
     * @param killedForInactivity  whether the inactivity watchdog fired
     * @param logger               target for diagnostics on parse failure
     * @param requiredMcpServers   servers the session could not do its job without
     * @return the parsed result
     */
    public AgentRunResult parseClaudeNdjson(String jsonOutput,
                                            int exitCode,
                                            boolean killedForInactivity,
                                            ConsoleFeatures logger,
                                            Set<String> requiredMcpServers) {
        List<String> unavailableRequired = new ArrayList<>();
        for (Map.Entry<String, String> failed : failedMcpServersAtInit(jsonOutput).entrySet()) {
            logger.warn("mcpServerFailed=" + failed.getKey() + " status=" + failed.getValue()
                    + " -- its tools were absent from the session");
            if (requiredMcpServers != null && requiredMcpServers.contains(failed.getKey())) {
                unavailableRequired.add(failed.getKey());
            }
        }

        String resultJson = null;
        if (jsonOutput != null && !jsonOutput.isEmpty()) {
            resultJson = JsonFieldExtractor.extractLastJsonObject(jsonOutput, "result");
            if (resultJson == null) {
                resultJson = jsonOutput;
            }
        }

        String sessionId = null;
        String subtype = null;
        boolean sessionIsError = false;
        long durationMs = 0L;
        long durationApiMs = 0L;
        int numTurns = 0;
        double costUsd = 0.0;
        List<String> deniedToolNames = new ArrayList<>();

        if (resultJson != null && !resultJson.isEmpty()) {
            try {
                JsonNode root = MAPPER.readTree(resultJson);
                sessionId = JsonFieldExtractor.getTextOrNull(root, "session_id");
                subtype = JsonFieldExtractor.getTextOrNull(root, "subtype");
                sessionIsError = root.path("is_error").asBoolean(false);
                durationMs = root.path("duration_ms").asLong(0L);
                durationApiMs = root.path("duration_api_ms").asLong(0L);
                numTurns = root.path("num_turns").asInt(0);

                double sessionCost = root.path("total_cost_usd").asDouble(0.0);
                if (sessionCost == 0.0) {
                    sessionCost = root.path("cost_usd").asDouble(0.0);
                }
                costUsd = sessionCost;

                // Each entry of permission_denials is
                // {tool_name, tool_use_id, tool_input} — the name of the
                // denied tool is carried by tool_name, not tool.
                JsonNode denials = root.get("permission_denials");
                if (denials != null && denials.isArray()) {
                    for (JsonNode denial : denials) {
                        String toolName = JsonFieldExtractor.getTextOrNull(denial, "tool_name");
                        if (toolName != null) {
                            deniedToolNames.add(toolName);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse output metrics: " + e.getMessage());
            }
        }

        return new AgentRunResult(
                exitCode,
                killedForInactivity,
                jsonOutput != null ? jsonOutput : "",
                sessionId,
                durationMs,
                durationApiMs,
                numTurns,
                costUsd,
                subtype,
                sessionIsError,
                deniedToolNames,
                Collections.emptyMap(),
                unavailableRequired);
    }

    /**
     * Reads the MCP servers that did not connect from the session's
     * {@code init} event.
     *
     * @param jsonOutput the captured {@code stream-json} output
     * @return server name to reported status, in the order the event listed
     *         them, for every server whose status is not {@code connected};
     *         empty when there is no init event or every server connected
     */
    public Map<String, String> failedMcpServersAtInit(String jsonOutput) {
        Map<String, String> failed = new LinkedHashMap<>();
        if (jsonOutput == null || jsonOutput.isEmpty()) return failed;

        for (String line : jsonOutput.split("\n")) {
            if (!line.startsWith("{") || !line.contains("\"init\"") || !line.contains("\"mcp_servers\"")) {
                continue;
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(line);
            } catch (IOException e) {
                continue;
            }
            if (!"system".equals(JsonFieldExtractor.getTextOrNull(root, "type"))
                    || !"init".equals(JsonFieldExtractor.getTextOrNull(root, "subtype"))) {
                continue;
            }
            for (JsonNode server : root.path("mcp_servers")) {
                String name = JsonFieldExtractor.getTextOrNull(server, "name");
                String status = JsonFieldExtractor.getTextOrNull(server, "status");
                if (name != null && !"connected".equals(status)) {
                    failed.put(name, status == null ? "unknown" : status);
                }
            }
            return failed;
        }
        return failed;
    }
}
