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
import io.flowtree.jobs.agent.AgentRunner;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles what one agent session launches with: the MCP server
 * configuration, the composed tool allowlist, the subprocess environment, and
 * the {@link AgentRunRequest} that carries all of it to an {@link AgentRunner}.
 *
 * <p>This is the boundary between a job's durable configuration and a single
 * session's launch. {@link CodingAgentJob} holds the former — what the
 * workload is, which survives serialization to another machine — and this
 * class turns it into the latter each time a phase dispatches, reading the
 * job's state through the same package-private accessors the job's other
 * collaborators use ({@code PhaseRunnerConfig}, {@code RestartGovernor},
 * {@code EnforcementRunner}).</p>
 *
 * <p>Nothing here is per-job state: an instance is a view onto its job, so a
 * value that must survive a dispatch belongs on {@link CodingAgentJob} and
 * its codec, never on this class.</p>
 *
 * @author Michael Murray
 */
class AgentLaunchConfig {

    /** The job whose configuration each launch is assembled from. */
    private final CodingAgentJob job;

    /** Builder for the MCP configuration JSON and the composed tool allowlist. */
    private final McpConfigBuilder mcpConfigBuilder;

    /** Downloads pushed MCP tool sources before a launch that needs them. */
    private final ManagedToolsDownloader toolsDownloader;

    /**
     * Creates a launch assembler bound to {@code job}.
     *
     * @param job              the job to read configuration from
     * @param mcpConfigBuilder the builder the job exposes to its MCP plumbing
     */
    AgentLaunchConfig(CodingAgentJob job, McpConfigBuilder mcpConfigBuilder) {
        this.job = job;
        this.mcpConfigBuilder = mcpConfigBuilder;
        this.toolsDownloader = new ManagedToolsDownloader(mcpConfigBuilder);
    }

    /**
     * Downloads the pushed MCP tool sources named by {@code pushedToolsConfig},
     * so the stdio servers the configuration refers to exist before a session
     * tries to start them.
     *
     * @param pushedToolsConfig the pushed-tools configuration JSON, or {@code null}
     */
    void ensurePushedTools(String pushedToolsConfig) {
        toolsDownloader.ensurePushedTools(pushedToolsConfig);
    }

    /**
     * Pushes the job's current state into the MCP config builder.
     *
     * <p>Called before every launch rather than once, because a job's working
     * directory and pushed-tools configuration can both change between
     * phases.</p>
     */
    void configureMcpBuilder() {
        mcpConfigBuilder.setArManagerUrl(job.getArManagerUrl());
        mcpConfigBuilder.setArManagerToken(job.getArManagerToken());
        mcpConfigBuilder.setPushedToolsConfig(job.getPushedToolsConfig());
        mcpConfigBuilder.setPythonCommand(job.getPythonCommand());
        mcpConfigBuilder.setDispatchCapable(job.isDispatchCapable());
        Path workDir = job.getWorkingDirectory() != null
                ? Path.of(job.getWorkingDirectory())
                : Path.of(System.getProperty("user.dir"));
        mcpConfigBuilder.setWorkingDirectory(workDir);
    }

    /**
     * Composes the allowed-tools CSV handed to the launched agent, layering
     * ar-manager, dispatch (only when {@link CodingAgentJob#isDispatchCapable()}),
     * pushed, and project-server entries onto the job's base tools.
     * {@link #configureMcpBuilder()} must run first so the builder reflects
     * current job state; this is the real artifact that grants the dispatch
     * tools to an orchestrator.
     *
     * @return the composed comma-separated allowed-tools list
     */
    String buildComposedAllowedTools() {
        return mcpConfigBuilder.buildAllowedTools(job.getAllowedTools());
    }

    /**
     * Builds the {@link AgentRunRequest} for the current session, snapshotting
     * the instruction prompt and the orchestrator-owned MCP and tool policy.
     *
     * <p>Reads per-phase model and effort via
     * {@link CodingAgentJob#resolveEffectivePhaseConfig(Phase)} so that
     * mixed-phase jobs dispatch each phase with its own resolved
     * {@code (model, effort)} pair. Runner selection still goes through
     * {@link CodingAgentJob#resolveRunner(Phase)}.</p>
     *
     * @param composedAllowedTools allowed-tools CSV including ar-manager and
     *                             pushed-tool entries from {@link McpConfigBuilder}
     * @param mcpConfigJson        MCP config JSON in the canonical
     *                             {@code {"mcpServers":{...}}} shape
     * @param outputCapturePath    file path where the runner should dump its
     *                             raw output
     * @param attempt              current inactivity-restart attempt index
     * @return the request handed to {@link AgentRunner#run}
     */
    AgentRunRequest buildRunRequest(String composedAllowedTools,
                                    String mcpConfigJson,
                                    Path outputCapturePath,
                                    int attempt) {
        Map<String, String> env = new LinkedHashMap<>();
        // Per-workstream agentEnv first, so framework-critical vars set by
        // applyAgentEnvironment (ar-manager URL/token, workstream URL) win on
        // any key collision.
        Map<String, String> agentEnv = job.getAgentEnv();
        if (agentEnv != null && !agentEnv.isEmpty()) {
            env.putAll(agentEnv);
        }
        String wsUrl = job.resolveWorkstreamUrl();
        if (wsUrl != null && !wsUrl.isEmpty()) {
            job.log("AR_WORKSTREAM_URL: " + wsUrl);
        }
        mcpConfigBuilder.applyAgentEnvironment(env, wsUrl);

        Path workDir = job.getWorkingDirectory() != null
                ? Path.of(job.getWorkingDirectory()) : null;
        Phase phase = job.resolveCurrentPhase();
        PhaseConfig effective = job.resolveEffectivePhaseConfig(phase);
        return AgentRunRequest.builder()
                .prompt(job.buildInstructionPrompt())
                .workingDirectory(workDir)
                .allowedTools(composedAllowedTools)
                .mcpConfigJson(mcpConfigJson)
                .requiredMcpServers(mcpConfigBuilder.requiredServerNames())
                .environment(env)
                .model(effective.model())
                .effort(effective.effort())
                .provider(effective.provider())
                .maxTurns(job.getMaxTurns())
                .maxBudgetUsd(job.getMaxBudgetUsd())
                .inactivityTimeoutMillis(job.resolveRunner(phase).defaultInactivityTimeoutMillis())
                .inactivityRestartAttempt(attempt)
                .maxInactivityRestarts(job.restartGovernor().getMaxInactivityRestarts())
                .taskId(job.getTaskId())
                .activityTag(job.getCurrentActivity())
                .outputCapturePath(outputCapturePath)
                .useTmux(job.isUseTmux())
                .bypassPermissionPrompts(job.isBypassAgentPermissionPrompts())
                .build();
    }
}
