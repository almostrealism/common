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

package io.flowtree.slack;

import io.flowtree.jobs.CodingAgentJobFactory;
import io.flowtree.jobs.agent.PhaseConfigBundle;

import java.util.function.Consumer;
import io.flowtree.submission.SubmissionConfigResolver;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;
import io.flowtree.workstream.WorkspaceEntry;

/**
 * Slack-specific glue that wires {@link SubmissionConfigResolver} into the
 * Slack submission flow. Owns the workspace lookup, the missing-workspace
 * diagnostic, and the Slack-channel error message posted on validation
 * failure. Kept out of {@link SlackListener} so that the listener stays
 * focused on event dispatch.
 *
 * <p>Exists only because the Slack path needs to translate resolver errors
 * into a chat message; the REST API endpoint can call
 * {@link SubmissionConfigResolver} directly because it returns errors as
 * HTTP 400 responses.</p>
 */
final class SlackSubmissionConfig {

    /** Prevents instantiation. */
    private SlackSubmissionConfig() { }

    /**
     * Resolves request &rarr; workstream &rarr; workspace &rarr; controller
     * runner and Phase configuration for a Slack-triggered submission and
     * applies it to {@code factory}. The request bundle is always
     * {@link PhaseConfigBundle#EMPTY} because Slack messages carry no per-job
     * configuration overrides.
     *
     * <p>On validation failure the workstream's channel receives a
     * user-visible {@code :x:} message describing the error, and this method
     * returns {@code false} so the caller can abandon submission without
     * dispatching the job.</p>
     *
     * @param factory          the factory being configured; must not be {@code null}
     * @param workstream       the target workstream; must not be {@code null}
     * @param workstreamConfig the workstream/workspace registry; {@code null}
     *                         disables workspace lookup (in which case the
     *                         workspace layer of resolution is treated as
     *                         empty)
     * @param notifier         used to post the validation-error message to the
     *                         workstream's Slack channel; must not be {@code null}
     * @param diagnostic       receives an informational message when the
     *                         workstream references a {@code workspaceId} that
     *                         does not resolve to a configured workspace; may
     *                         be {@code null}
     * @return {@code true} when the resolver succeeded and the factory has
     *         been updated; {@code false} when an error was posted and the
     *         caller should abandon the submission
     */
    static boolean apply(CodingAgentJobFactory factory,
                         Workstream workstream,
                         WorkstreamConfig workstreamConfig,
                         SlackNotifier notifier,
                         Consumer<String> diagnostic) {
        WorkspaceEntry wsEntry = null;
        if (workstreamConfig != null) {
            String wsId = workstream.getWorkspaceId();
            if (wsId != null && !wsId.isEmpty()) {
                wsEntry = workstreamConfig.findWorkspace(wsId);
                if (wsEntry == null && diagnostic != null) {
                    diagnostic.accept("submitWorkspaceMissing workspaceId=" + wsId);
                }
            }
        }
        SubmissionConfigResolver resolver = SubmissionConfigResolver.resolve(
                PhaseConfigBundle.EMPTY, workstream, wsEntry);
        if (resolver.error() != null) {
            notifier.postMessage(workstream.getChannelId(),
                    ":x: Job submission rejected: " + resolver.error());
            return false;
        }
        resolver.applyTo(factory);
        return true;
    }
}
