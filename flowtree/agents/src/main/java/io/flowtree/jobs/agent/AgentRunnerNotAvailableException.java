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

/**
 * Thrown by {@link AgentRunner#run(AgentRunRequest, org.almostrealism.io.ConsoleFeatures)}
 * when the runner's required external binary, library, or configuration is
 * missing on the executor host. Distinct from a runtime failure of the agent
 * itself: the runner could not even be launched.
 *
 * <p>Surfaced by the orchestrator as a job failure with the message intact so
 * the operator can fix the install rather than chasing an opaque crash.</p>
 *
 * @author Michael Murray
 */
public class AgentRunnerNotAvailableException extends RuntimeException {

    /** Serialization identifier. */
    private static final long serialVersionUID = 1L;

    /**
     * Constructs an exception with the supplied detail message.
     *
     * @param message description of what was checked and what was missing
     */
    public AgentRunnerNotAvailableException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with the supplied detail message and cause.
     *
     * @param message description of what was checked and what was missing
     * @param cause   the underlying failure, if any
     */
    public AgentRunnerNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
