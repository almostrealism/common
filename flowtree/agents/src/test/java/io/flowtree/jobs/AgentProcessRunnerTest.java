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
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the environment {@link AgentProcessRunner#applyRequestToProcessBuilder}
 * gives every agent process.
 */
public class AgentProcessRunnerTest extends TestSuiteBase {

    /**
     * Every agent process carries the job marker the repository's hooks use to
     * tell a job session from an interactive one, even when the request's own
     * environment tries to remove or override it.
     */
    @Test(timeout = 5000)
    public void agentProcessIsMarkedAsJob() {
        Map<String, String> env = new HashMap<>();
        env.put(AgentRunRequest.ENV_FLOWTREE_JOB, null);

        ProcessBuilder removed = new ProcessBuilder("true");
        AgentProcessRunner.applyRequestToProcessBuilder(removed,
                AgentRunRequest.builder().prompt("x").environment(env).build());
        assertEquals("1", removed.environment().get(AgentRunRequest.ENV_FLOWTREE_JOB));

        env.put(AgentRunRequest.ENV_FLOWTREE_JOB, "");
        ProcessBuilder overridden = new ProcessBuilder("true");
        AgentProcessRunner.applyRequestToProcessBuilder(overridden,
                AgentRunRequest.builder().prompt("x").environment(env).build());
        assertEquals("1", overridden.environment().get(AgentRunRequest.ENV_FLOWTREE_JOB));
    }
}
