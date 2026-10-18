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

import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.jobs.PostCompletionCommandValidator;
import io.flowtree.jobs.PromptTestInstructionLinter;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Enforces the "no broad test runs" rule against a job submission's shell command(s) and
 * free-text prompt, on behalf of {@link FlowTreeApiEndpoint#handleSubmit}.
 *
 * <p>Extracted from {@code FlowTreeApiEndpoint} so the {@code command}/{@code
 * postCompletionCommand} check ({@link PostCompletionCommandValidator}) and the {@code prompt}
 * check ({@link PromptTestInstructionLinter}) -- both halves of the same "no bypass" rule --
 * have a single home instead of inflating the endpoint's already-long dispatch method; see
 * {@code flowtree/CLAUDE.md}'s guidance that a file near its length cap should be refactored
 * into a cohesive collaborator rather than compressed, following the same pattern already used
 * by {@link ShellCommandSubmissionHandler} and {@link WorkstreamRegistrationHandler}.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see PostCompletionCommandValidator
 * @see PromptTestInstructionLinter
 */
final class TestExecutionLimitsSubmissionValidator {

    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;
    /** Builds an error {@link Response} for a rejection message. */
    private final Function<String, Response> errorResponse;

    /**
     * Constructs a new validator bound to the given collaborators.
     *
     * @param log           log line consumer
     * @param errorResponse builds an error response for a rejection message
     */
    TestExecutionLimitsSubmissionValidator(Consumer<String> log, Function<String, Response> errorResponse) {
        this.log = log;
        this.errorResponse = errorResponse;
    }

    /**
     * Validates {@code command}, {@code postCompletionCommand}, and {@code prompt} against the
     * "no broad test runs" rule. There is no bypass for either check; see
     * {@link PostCompletionCommandValidator} and {@link PromptTestInstructionLinter}.
     *
     * @param workstreamId          the resolved workstream identifier, for logging
     * @param command               a shell-command job's command, or {@code null}
     * @param postCompletionCommand a coding-agent job's post-completion command, or {@code null}
     * @param prompt                the job's free-text prompt, or {@code null}
     * @return an error {@link Response} to reject the submission, or {@code null} to continue
     */
    Response validate(String workstreamId, String command, String postCompletionCommand, String prompt) {
        for (String candidate : new String[] {command, postCompletionCommand}) {
            PostCompletionCommandValidator validator = new PostCompletionCommandValidator(candidate).validate();
            if (validator.hasViolations()) {
                log.accept("Rejected job submission for workstream " + workstreamId
                        + ": broad test-run command (" + validator.getViolations().get(0) + ")");
                return errorResponse.apply(validator.formatRejection());
            }
        }
        // A direct /api/submit caller has no other path to the prompt-instruction check
        // ar-manager's workstream_submit_task applies; see PromptTestInstructionLinter's javadoc.
        PromptTestInstructionLinter promptLinter = new PromptTestInstructionLinter(prompt).lint();
        if (promptLinter.hasViolations()) {
            log.accept("Rejected job submission for workstream " + workstreamId
                    + ": broad test-run prompt instruction (" + promptLinter.getViolations().get(0) + ")");
            return errorResponse.apply(promptLinter.formatRejection());
        }
        return null;
    }
}
