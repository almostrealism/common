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

import io.almostrealism.uml.Named;
import org.almostrealism.io.ConsoleFeatures;

/**
 * Pluggable backend that runs one agent session against a prompt and returns
 * the resulting metrics. Implementations are stateless across calls; the
 * orchestrator threads all context through {@link AgentRunRequest}.
 *
 * <p>Phase 1 of the pluggable-agents refactor introduces this contract so the
 * job orchestrator no longer mentions Claude-specific flags, subprocess
 * construction, or NDJSON parsing in its body. The single concrete
 * implementation shipped today is
 * {@link io.flowtree.jobs.agent.ClaudeCodeRunner}; later phases add other
 * runners (e.g. opencode) without changing the orchestrator.</p>
 *
 * @author Michael Murray
 */
public interface AgentRunner extends Named {

    /**
     * Runs one agent session.
     *
     * <p>Captures the session's stdout, exit code, accumulated metrics, and any
     * runner-specific metadata. Throws only for unrecoverable wiring errors
     * (binary missing, working directory does not exist); a non-zero process
     * exit code is a normal result and is surfaced via
     * {@link AgentRunResult#exitCode()}.</p>
     *
     * @param request the parameters of the session
     * @param logger  target for {@code log}/{@code warn} messages emitted by
     *                the runner (typically the orchestrator job)
     * @return the captured result, never {@code null}
     */
    AgentRunResult run(AgentRunRequest request, ConsoleFeatures logger);

    /**
     * Declares which optional capabilities this runner faithfully reports.
     * The orchestrator and telemetry layer use this to decide whether to
     * surface a value or mark it as not-reported.
     *
     * @return the capability set; never {@code null}
     */
    AgentCapabilities capabilities();
}
