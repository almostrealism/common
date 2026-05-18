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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a single {@link AgentRunner#run(AgentRunRequest, org.almostrealism.io.ConsoleFeatures) run}.
 *
 * <p>Carries the captured exit code, the raw stdout text, accumulated metrics,
 * and any runner-specific metadata such as a session identifier. The
 * orchestrator absorbs these values across phases and rolls them up into the
 * job completion event.</p>
 *
 * @param exitCode             process exit code; {@code -1} on launch failure
 * @param killedForInactivity  {@code true} when the inactivity watchdog fired
 * @param rawOutput            captured stdout (may be partial after a kill)
 * @param sessionId            runner's session identifier; may be {@code null}
 * @param durationMs           total wall-clock duration of the session in ms
 * @param durationApiMs        time spent in API calls in ms; 0 when not separated
 * @param numTurns             number of agentic turns reported by the runner; 0 when not reported
 * @param costUsd              session cost in USD; 0 when not reported
 * @param stopReason           termination tag such as {@code "success"} or {@code "error_max_turns"}
 * @param sessionIsError       whether the runner flagged the session as an error
 * @param deniedToolNames      tool names denied during the session; empty when unsupported
 * @param runnerMetadata       free-form runner-specific data
 */
public record AgentRunResult(
        int exitCode,
        boolean killedForInactivity,
        String rawOutput,
        String sessionId,
        long durationMs,
        long durationApiMs,
        int numTurns,
        double costUsd,
        String stopReason,
        boolean sessionIsError,
        List<String> deniedToolNames,
        Map<String, String> runnerMetadata) {

    /**
     * Canonical compact constructor that defensively normalises the collections
     * so consumers can iterate them without null checks.
     */
    public AgentRunResult {
        rawOutput = rawOutput == null ? "" : rawOutput;
        deniedToolNames = deniedToolNames == null
                ? Collections.emptyList()
                : List.copyOf(deniedToolNames);
        runnerMetadata = runnerMetadata == null
                ? Collections.emptyMap()
                : Map.copyOf(runnerMetadata);
    }

    /**
     * Convenience factory for a launch failure where no process was started.
     *
     * @param reason short description recorded as the {@code stopReason}
     * @return a result with {@code exitCode=-1} and empty output
     */
    public static AgentRunResult launchFailed(String reason) {
        return new AgentRunResult(
                -1, false, "", null,
                0L, 0L, 0, 0.0,
                reason, true,
                Collections.emptyList(),
                Collections.emptyMap());
    }
}
