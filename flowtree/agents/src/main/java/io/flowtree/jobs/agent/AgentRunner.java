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

import java.util.concurrent.TimeUnit;

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
     * Default stdout-silence duration applied by the orchestrator's inactivity
     * watchdog when a runner declares no runner-specific override. Claude Code
     * streams NDJSON events frequently while it works, but emits nothing between
     * lines while a single long-running tool call is in flight (for example a
     * multi-minute {@code mvn install} or test run). Thirty-five minutes leaves
     * room for those legitimately long, output-silent tool calls to finish
     * before the watchdog mistakes a healthy session for a wedged process,
     * while still bounding a truly hung subprocess.
     */
    long DEFAULT_INACTIVITY_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(35);

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
     * surface a value (such as cost or turn count) or mark it as not-reported.
     *
     * @return the capability set; never {@code null}
     */
    AgentCapabilities capabilities();

    /**
     * Returns the default provider identifier for this runner when no explicit
     * provider is configured. This is the provider the runner uses when
     * {@link AgentRunRequest#getProvider()} returns {@code null}.
     *
     * @return the default provider name, or {@code null} if the runner does
     *         not support providers (should not happen for modern runners)
     */
    default String defaultProvider() { return null; }

    /**
     * Returns the stdout-silence duration, in milliseconds, after which the
     * orchestrator's inactivity watchdog terminates this runner's subprocess.
     *
     * <p>The watchdog measures wall-clock silence between lines of captured
     * stdout, not total runtime: a long-running session keeps the timer reset
     * as long as it keeps emitting output. Runners whose models produce long
     * gaps between output lines (for example a local or proxied model thinking
     * through a large generation before emitting its next event) should
     * declare a larger window here so legitimate work is not mistaken for a
     * hung process. The default is {@link #DEFAULT_INACTIVITY_TIMEOUT_MILLIS}.</p>
     *
     * @return the inactivity-kill window in milliseconds; always positive
     */
    default long defaultInactivityTimeoutMillis() {
        return DEFAULT_INACTIVITY_TIMEOUT_MILLIS;
    }
}
