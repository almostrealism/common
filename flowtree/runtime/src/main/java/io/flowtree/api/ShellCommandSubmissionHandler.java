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
import io.flowtree.Server;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.ShellCommandJob;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackListener;
import io.flowtree.workstream.Workstream;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * labels, self-notify flag, and workstream URL are propagated. The default
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
final class ShellCommandSubmissionHandler {

    /** Aggregates the per-workspace notifiers used for submission notifications. */
    private final NotifierRegistry notifiers;
    /** Slack listener supplying the operator-configured default workspace path; may be {@code null}. */
    private final SlackListener listener;
    /** Local FlowTree server used to dispatch the job. */
    private final Server server;
    /** Shared registry of pending delayed-dispatch futures, keyed by task ID. */
    private final Map<String, ScheduledFuture<?>> pendingDelayedJobs;
    /** Executor used to schedule a delayed dispatch. */
    private final ScheduledExecutorService delayedJobExecutor;
    /** Supplies the endpoint's current listening port for building the job's workstream URL. */
    private final IntSupplier listeningPort;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;
    /**
     * Records a job ID as legitimately eligible for self-notify on completion.
     * See {@code FlowTreeApiEndpoint#selfNotifyJobs}: the completion event's
     * self-notify flag is later resolved by membership in that registry rather
     * than trusted from the posted status body.
     */
    private final Consumer<String> registerSelfNotifyJob;

    /**
     * Constructs a new handler bound to the given collaborators.
     *
     * @param notifiers             the workspace notifier registry
     * @param listener              Slack listener for the default workspace path (may be {@code null})
     * @param server                the FlowTree server used to dispatch the job
     * @param pendingDelayedJobs    shared map of pending delayed-dispatch futures
     * @param delayedJobExecutor    executor used to schedule a delayed dispatch
     * @param listeningPort         supplies the endpoint's current listening port
     * @param log                   log line consumer
     * @param registerSelfNotifyJob records a job ID as eligible for self-notify on completion
     */
    ShellCommandSubmissionHandler(NotifierRegistry notifiers,
                                  SlackListener listener,
                                  Server server,
                                  Map<String, ScheduledFuture<?>> pendingDelayedJobs,
                                  ScheduledExecutorService delayedJobExecutor,
                                  IntSupplier listeningPort,
                                  Consumer<String> log,
                                  Consumer<String> registerSelfNotifyJob) {
        this.notifiers = notifiers;
        this.listener = listener;
        this.server = server;
        this.pendingDelayedJobs = pendingDelayedJobs;
        this.delayedJobExecutor = delayedJobExecutor;
        this.listeningPort = listeningPort;
        this.log = log;
        this.registerSelfNotifyJob = registerSelfNotifyJob;
    }

    /**
     * Builds and submits a {@link ShellCommandJob} that runs a single command
     * against the workstream's repository.
     *
     * @param body          the raw request body (for required-label extraction)
     * @param workstream    the resolved target workstream
     * @param workstreamId  the resolved workstream identifier
     * @param command       the shell command to execute
     * @param targetBranch  the requested branch, or {@code null} for the default
     * @param repoUrl       the requested repository URL, or {@code null}
     * @param delaySeconds  delay before dispatch, or {@code 0} for immediate
     * @param selfNotify    when {@code true}, the job's own workstream is woken with
     *                      a follow-up job when this command completes; see
     *                      {@code io.flowtree.jobs.CompletionListenerFanout#fanoutSelf}
     * @return the JSON submission response
     */
    Response handle(String body, Workstream workstream, String workstreamId, String command,
            String targetBranch, String repoUrl, int delaySeconds, boolean selfNotify) {
        ShellCommandJob.Factory factory = new ShellCommandJob.Factory(command);
        factory.setSelfNotify(selfNotify);
        if (selfNotify) {
            registerSelfNotifyJob.accept(factory.getTaskId());
        }

        String effectiveRepoUrl = repoUrl != null ? repoUrl : workstream.getRepoUrl();
        if (effectiveRepoUrl != null) {
            factory.setRepoUrl(effectiveRepoUrl);
        }
        if (workstream.getWorkingDirectory() != null) {
            factory.setWorkingDirectory(workstream.getWorkingDirectory());
        }
        if (listener != null && listener.getDefaultWorkspacePath() != null) {
            factory.setDefaultWorkspacePath(listener.getDefaultWorkspacePath());
        }
        String effectiveBranch = targetBranch != null ? targetBranch : workstream.getDefaultBranch();
        if (effectiveBranch != null) {
            factory.setTargetBranch(effectiveBranch);
        }

        Map<String, String> requiredLabels = FlowTreeApiEndpoint.extractJsonObjectFields(body, "requiredLabels");
        if (requiredLabels.isEmpty() && workstream.getRequiredLabels() != null
                && !workstream.getRequiredLabels().isEmpty()) {
            requiredLabels = workstream.getRequiredLabels();
        }
        for (Map.Entry<String, String> entry : requiredLabels.entrySet()) {
            factory.setRequiredLabel(entry.getKey(), entry.getValue());
        }

        int port = listeningPort.getAsInt();
        if (port > 0) {
            factory.setWorkstreamUrl("http://0.0.0.0:" + port
                + "/api/workstreams/" + workstream.getWorkstreamId()
                + "/jobs/" + factory.getTaskId());
        }

        JobCompletionEvent startEvent = JobCompletionEvent.started(
                factory.getTaskId(), ShellCommandJob.summarizeCommand(command));
        startEvent.withGitInfo(effectiveBranch, null, null, null, false);
        notifiers.completionListener(workstream.getWorkstreamId())
                .onJobSubmitted(workstream.getWorkstreamId(), startEvent);

        if (delaySeconds > 0) {
            pendingDelayedJobs.put(factory.getTaskId(), delayedJobExecutor.schedule(
                    () -> {
                        try {
                            server.addTask(factory);
                        } finally {
                            pendingDelayedJobs.remove(factory.getTaskId());
                        }
                    }, delaySeconds, TimeUnit.SECONDS));
            log.accept("Delayed shell-command job via API: " + factory.getTaskId()
                + " (delaySeconds=" + delaySeconds + ")");
        } else {
            server.addTask(factory);
            log.accept("Submitted shell-command job via API: " + factory.getTaskId());
        }

        StringBuilder json = new StringBuilder("{\"ok\":true,\"jobId\":\"" + factory.getTaskId()
            + "\",\"workstreamId\":\"" + workstreamId + "\",\"jobType\":\"shell\"");
        if (selfNotify) {
            json.append(",\"selfNotify\":true");
        }
        json.append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }
}
