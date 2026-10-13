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
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.ShellCommandJob;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackListener;
import io.flowtree.workstream.Workstream;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Builds and submits a {@link ShellCommandJob} on behalf of
 * {@link FlowTreeApiEndpoint#handleSubmit}.
 *
 * <p>Extracted from {@code FlowTreeApiEndpoint} so the shell-job construction
 * (repository/branch/working-directory resolution, required labels, the
 * submission notification, and delayed dispatch) has a single home instead of
 * inflating the endpoint's already-long dispatch method; see
 * {@code flowtree/CLAUDE.md}'s guidance that a file near its length cap should
 * be refactored into a cohesive collaborator rather than compressed.</p>
 *
 * <p>Unlike a coding-agent job, a shell job carries no agent configuration and
 * never commits; only the repository, branch, working directory, required
 * labels, self-notify flag, and workstream URL are propagated. The workstream
 * supplies its own defaults through {@link Workstream#newShellJobFactory},
 * {@link Workstream#requiredLabelsWith} and {@link Workstream#jobUrl}, and the
 * job is dispatched through the endpoint's {@link DelayedJobQueue}, the same
 * path a coding-agent submission takes. The default
 * workspace path is propagated from the Slack listener's global configuration
 * exactly as {@link FlowTreeApiEndpoint#handleSubmit} does for a coding-agent
 * job, so a shell job resolves to the same checkout location every other job
 * on the workstream uses instead of falling back to
 * {@code WorkspaceResolver}'s default.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see ShellCommandJob
 */
final class ShellCommandSubmissionHandler implements ConsoleFeatures {

    /** Aggregates the per-workspace notifiers used for submission notifications. */
    private final NotifierRegistry notifiers;
    /** Slack listener supplying the operator-configured default workspace path; may be {@code null}. */
    private final SlackListener listener;
    /** The endpoint's dispatch queue to its FlowTree server. */
    private final DelayedJobQueue delayedJobs;
    /** Supplies the endpoint's current listening port for building the job's workstream URL. */
    private final IntSupplier listeningPort;
    /**
     * Job IDs legitimately eligible for self-notify on completion. See
     * {@code FlowTreeApiEndpoint#selfNotifyJobs}: the completion event's
     * self-notify flag is later resolved by membership in this registry
     * rather than trusted from the posted status body.
     */
    private final Set<String> selfNotifyJobs;

    /**
     * Constructs a new handler bound to the given collaborators.
     *
     * @param notifiers      the workspace notifier registry
     * @param listener       Slack listener for the default workspace path (may be {@code null})
     * @param delayedJobs    the endpoint's dispatch queue to its FlowTree server
     * @param listeningPort  supplies the endpoint's current listening port
     * @param selfNotifyJobs registry of job IDs eligible for self-notify on completion
     */
    ShellCommandSubmissionHandler(NotifierRegistry notifiers,
                                  SlackListener listener,
                                  DelayedJobQueue delayedJobs,
                                  IntSupplier listeningPort,
                                  Set<String> selfNotifyJobs) {
        this.notifiers = notifiers;
        this.listener = listener;
        this.delayedJobs = delayedJobs;
        this.listeningPort = listeningPort;
        this.selfNotifyJobs = selfNotifyJobs;
    }

    /**
     * Builds and submits a {@link ShellCommandJob} that runs a single command
     * against the workstream's repository, starting from the workstream's
     * {@link Workstream#newShellJobFactory shell-job defaults}.
     *
     * @param workstream     the resolved target workstream
     * @param command        the shell command to execute
     * @param targetBranch   the requested branch, or {@code null} for the default
     * @param repoUrl        the requested repository URL, or {@code null} for the workstream's
     * @param requiredLabels the submission's Node labels; empty to use the workstream's defaults
     * @param delaySeconds   delay before dispatch, or {@code 0} for immediate
     * @param selfNotify     when {@code true}, the job's own workstream is woken with
     *                       a follow-up job when this command completes; see
     *                       {@code io.flowtree.jobs.CompletionListenerFanout#fanoutSelf}
     * @return the JSON submission response
     */
    Response handle(Workstream workstream, String command, String targetBranch, String repoUrl,
                    Map<String, String> requiredLabels, int delaySeconds, boolean selfNotify) {
        ShellCommandJob.Factory factory = workstream.newShellJobFactory(command, targetBranch);
        String jobId = factory.getTaskId();
        factory.setSelfNotify(selfNotify);
        if (selfNotify) {
            selfNotifyJobs.add(jobId);
        }
        if (repoUrl != null) {
            factory.setRepoUrl(repoUrl);
        }
        if (listener != null && listener.getDefaultWorkspacePath() != null) {
            factory.setDefaultWorkspacePath(listener.getDefaultWorkspacePath());
        }
        for (Map.Entry<String, String> entry : workstream.requiredLabelsWith(requiredLabels).entrySet()) {
            factory.setRequiredLabel(entry.getKey(), entry.getValue());
        }
        String jobUrl = workstream.jobUrl(listeningPort.getAsInt(), jobId);
        if (jobUrl != null) {
            factory.setWorkstreamUrl(jobUrl);
        }

        JobCompletionEvent startEvent = JobCompletionEvent.started(
                jobId, ShellCommandJob.summarizeCommand(command));
        startEvent.withGitInfo(factory.getTargetBranch(), null, null, null, false);
        notifiers.completionListener(workstream.getWorkstreamId())
                .onJobSubmitted(workstream.getWorkstreamId(), startEvent);

        if (delayedJobs.submit(factory, delaySeconds)) {
            log("Delayed shell-command job via API: " + jobId + " (delaySeconds=" + delaySeconds + ")");
        } else {
            log("Submitted shell-command job via API: " + jobId);
        }

        StringBuilder json = new StringBuilder("{\"ok\":true,\"jobId\":\"" + jobId
            + "\",\"workstreamId\":\"" + workstream.getWorkstreamId() + "\",\"jobType\":\"shell\"");
        if (selfNotify) {
            json.append(",\"selfNotify\":true");
        }
        json.append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }
}
