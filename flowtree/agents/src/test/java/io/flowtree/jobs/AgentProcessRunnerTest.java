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
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    /**
     * A launch that never starts a process — an unresolvable command whose
     * {@link ProcessBuilder#start()} throws — reports no runtime and no output,
     * so {@code AgentRunResult.endState()} can tell it from a session that ran
     * and was killed. The wall-clock timer is armed only after a successful
     * launch, so a measurable elapsed time on the failing {@code start()} call
     * does not leak into the result as a positive duration.
     */
    @Test(timeout = 5000)
    public void launchFailureReportsNoRuntime() {
        ProcessBuilder pb = new ProcessBuilder(
                "/nonexistent/definitely-no-such-agent-binary");
        AgentProcessRunner.Result result =
                AgentProcessRunner.runAttempt(pb, 1_000L, "launch-fail-test", SILENT);

        assertEquals("a launch failure exits -1", -1, result.exitCode());
        assertEquals("a process that never started has no measured runtime",
                0L, result.wallClockMs());
        assertTrue("a launch failure produced no output", result.output().isBlank());
    }

    /**
     * By default stdin is wired from {@code /dev/null} (a file redirect), not a
     * pipe: the agent reads EOF immediately and nothing can be written to it.
     * This is the unflagged path every session takes today.
     */
    @Test(timeout = 5000)
    public void defaultStdinIsDevNullNotAPipe() {
        ProcessBuilder pb = new ProcessBuilder("true");
        AgentProcessRunner.applyRequestToProcessBuilder(pb,
                AgentRunRequest.builder().prompt("x").build());
        Assert.assertNotEquals("default stdin must not be a writable pipe",
                ProcessBuilder.Redirect.PIPE, pb.redirectInput());
        Assert.assertEquals("default stdin is a file (/dev/null) redirect",
                ProcessBuilder.Redirect.Type.READ, pb.redirectInput().type());
    }

    /**
     * The streaming path leaves stdin as a writable {@link ProcessBuilder.Redirect#PIPE},
     * hands that pipe to the stdin consumer once the process starts, and a
     * consumer that writes a turn and then closes the pipe sends EOF so the
     * session exits cleanly. {@code cat} stands in for the agent: it echoes
     * whatever is written to its stdin, so the injected text appears in the
     * captured output, and it exits only when stdin reaches EOF.
     */
    @Test(timeout = 15000)
    public void streamingStdinWiresAWritablePipeAndEofLetsTheProcessExit() {
        ProcessBuilder pb = new ProcessBuilder("cat");
        AgentProcessRunner.applyRequestToProcessBuilder(pb,
                AgentRunRequest.builder().prompt("x").deliverMailboxToSession(true).build(),
                true);
        Assert.assertEquals("streaming stdin must be a writable pipe",
                ProcessBuilder.Redirect.PIPE, pb.redirectInput());

        AgentProcessRunner.Result result = AgentProcessRunner.runAttempt(
                pb, false, 5_000L, "stream-stdin-test", null, null,
                stdin -> {
                    try {
                        stdin.write("injected-turn\n".getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                        stdin.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                SILENT);

        assertEquals("the agent exits cleanly once stdin reaches EOF", 0, result.exitCode());
        assertTrue("the injected turn written to stdin is echoed to stdout: " + result.output(),
                result.output().contains("injected-turn"));
    }

    /**
     * A stdin consumer that fails after launch — modelling a broken pipe
     * surfaced as an {@link UncheckedIOException} — must not escape
     * {@code runAttempt} nor leave the started child running. The failure is
     * caught, the child is torn down, and a {@code -1} result is returned
     * rather than the exception propagating and leaking the process. The long
     * {@code sleep} stands in for an agent that would otherwise outlive the
     * failed setup; because the run returns inside the 5s timeout, the child
     * was destroyed rather than left to run its full duration.
     */
    @Test(timeout = 5000)
    public void stdinConsumerFailureTearsDownTheChildAndReportsFailure() {
        ProcessBuilder pb = new ProcessBuilder("sleep", "30");
        AgentProcessRunner.applyRequestToProcessBuilder(pb,
                AgentRunRequest.builder().prompt("x").deliverMailboxToSession(true).build(),
                true);

        AgentProcessRunner.Result result = AgentProcessRunner.runAttempt(
                pb, false, 60_000L, "stream-stdin-failure-test", null, null,
                stdin -> { throw new UncheckedIOException(new IOException("broken pipe")); },
                SILENT);

        assertEquals("a failed stdin setup reports -1 rather than propagating",
                -1, result.exitCode());
        assertTrue("a torn-down child produced no output", result.output().isBlank());
    }
}
