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

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TestExecutionLimitsSubmissionValidator}: the collaborator
 * {@link FlowTreeApiEndpoint#handleSubmit} delegates to for the "no broad test runs" rule,
 * bundling the {@code command}/{@code postCompletionCommand} check
 * ({@link io.flowtree.jobs.PostCompletionCommandValidator}) with the {@code prompt} check
 * ({@link io.flowtree.jobs.PromptTestInstructionLinter}). Exercised directly against the
 * collaborator rather than through HTTP, mirroring {@code ShellCommandSubmissionHandlerTest}'s
 * pattern of testing an extracted submission collaborator without workstream resolution.
 */
public class TestExecutionLimitsSubmissionValidatorTest extends TestSuiteBase {

    /** Builds a validator recording every log line into {@code logLines} and rendering a
     * rejection message straight through as the response body (no real HTTP wrapping needed
     * for these tests). */
    private static TestExecutionLimitsSubmissionValidator validator(List<String> logLines) {
        return new TestExecutionLimitsSubmissionValidator(
                logLines::add,
                message -> NanoHTTPD.newFixedLengthResponse(
                        Response.Status.BAD_REQUEST, "application/json", message));
    }

    /** A broad postCompletionCommand is rejected, and the rejection is logged. */
    @Test(timeout = 10000)
    public void broadPostCompletionCommandIsRejected() {
        List<String> logLines = new ArrayList<>();
        Response response = validator(logLines).validate(
                "ws-1", null, "mvn test -pl engine/utils", "Fix FooTest#testBar.");
        assertNotNull("expected a rejection response", response);
        assertTrue(logLines.get(0).contains("ws-1"));
    }

    /** A broad prompt is rejected even when command/postCompletionCommand are both narrow or
     * absent -- the gap a direct /api/submit caller could otherwise use to bypass the rule. */
    @Test(timeout = 10000)
    public void broadPromptIsRejectedWhenCommandsAreNarrow() {
        List<String> logLines = new ArrayList<>();
        Response response = validator(logLines).validate(
                "ws-2", null, null, "Please run the full test suite before finishing.");
        assertNotNull("expected a rejection response", response);
        assertTrue(logLines.get(0).contains("ws-2"));
    }

    /** A narrow command and a narrow prompt together produce no rejection. */
    @Test(timeout = 10000)
    public void narrowCommandAndPromptAreAccepted() {
        List<String> logLines = new ArrayList<>();
        Response response = validator(logLines).validate(
                "ws-3", null, "mvn test -pl engine/utils -Dtest=FooTest#testBar",
                "Fix FooTest#testBar, which is failing on this branch.");
        assertNull(response);
        assertTrue(logLines.isEmpty());
    }

    /** The command check runs before the prompt check: a broad command is rejected even when
     * the prompt is also broad, and only the command violation is logged. */
    @Test(timeout = 10000)
    public void commandViolationTakesPrecedenceOverPromptViolation() {
        List<String> logLines = new ArrayList<>();
        validator(logLines).validate(
                "ws-4", null, "mvn test -pl engine/utils", "Run the full test suite.");
        assertTrue(logLines.get(0).contains("broad test-run command"));
    }
}
