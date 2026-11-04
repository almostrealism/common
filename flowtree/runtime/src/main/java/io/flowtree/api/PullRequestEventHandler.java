/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import io.flowtree.JsonFieldExtractor;
import io.flowtree.controller.ActiveJob;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.controller.PendingJob;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.slack.NotifierRegistry.BranchResolution;
import org.almostrealism.io.ConsoleFeatures;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Cancels the in-flight work on a branch when that branch's pull request
 * reaches a terminal state (merged or closed).
 *
 * <p>A primary job can outlive the review it is carrying out: its branch's pull
 * request merges, yet the job keeps running on a worker until the stall
 * watchdog reclaims it hours later. Once a pull request is merged or closed the
 * work on its head branch is settled, so the jobs still running or waiting on
 * that branch are superseded and should be released at once rather than holding
 * a worker on an answered question.</p>
 *
 * <p>Only jobs the controller accepted <em>before</em> the pull request event
 * are cancelled. The cut-off is the submission time the controller records
 * ({@link JobStatsStore#getSubmittedAt(String)}), for a running job as well as
 * a pending one, so a job accepted before the event but picked up by a worker
 * after it is still cancelled. Only a running job with no recorded submission
 * (its row was created by a start event) falls back to
 * {@link ActiveJob#startedAt()}, which is never earlier than its submission. A
 * follow-up the owner starts on the same branch after the merge is submitted
 * after the event and is therefore left untouched.</p>
 *
 * <p>Each job is first moved to {@code CANCELLED} by the store's atomic
 * {@link JobStatsStore#cancelIfInFlight in-flight transition}; a job that
 * completed on its own after the in-flight snapshot keeps its own terminal
 * record and is not cancelled. A job that did transition travels the
 * controller's single terminal choke point
 * ({@code FlowTreeApiEndpoint.completeJob}) as a {@link JobCompletionEvent}
 * with {@link JobCompletionEvent.Status#CANCELLED}, so anything waiting on the
 * job is released, exactly as a
 * {@link io.flowtree.controller.StuckJobScanner} termination is. The
 * cancellation reason names the pull request event.</p>
 *
 * <p>This is controller-side cancellation only: no signal reaches the worker,
 * so a job already running keeps its agent process until that process ends on
 * its own or the worker's own watchdogs stop it. The controller never touches
 * the worker's checkout, so a cancelled job's working tree is left in place for
 * retrieval.</p>
 *
 * @author Michael Murray
 */
public final class PullRequestEventHandler implements ConsoleFeatures {

    /** The store queried for the branch's running and pending jobs. */
    private final JobStatsStore statsStore;

    /**
     * Resolves {@code (branch, repoUrl)} to the owning workstream: a match, no
     * match, or an ambiguity error. Bound by the endpoint to the notifier
     * registry's branch resolution.
     */
    private final BiFunction<String, String, BranchResolution> workstreamResolver;

    /** Receives the synthesized cancellation event, keyed by workstream id. */
    private final BiConsumer<String, JobCompletionEvent> terminalEventSink;

    /**
     * Creates a handler.
     *
     * @param statsStore         the store to query; {@code null} disables
     *                           cancellation
     * @param workstreamResolver resolves {@code (branch, repoUrl)} to the
     *                           owning workstream; a {@code null} resolver or
     *                           result means no workstream matches
     * @param terminalEventSink  receives each cancellation event, keyed by
     *                           workstream id; must not be {@code null}
     */
    public PullRequestEventHandler(JobStatsStore statsStore,
            BiFunction<String, String, BranchResolution> workstreamResolver,
            BiConsumer<String, JobCompletionEvent> terminalEventSink) {
        this.statsStore = statsStore;
        this.workstreamResolver = workstreamResolver;
        this.terminalEventSink = terminalEventSink;
    }

    /**
     * Handles a {@code POST /api/pr-events} request body describing a terminal
     * pull request event and returns the JSON response body.
     *
     * <p>Expected fields: {@code headBranch} (required), {@code repoUrl},
     * {@code prNumber}, {@code merged} (boolean; {@code false} is a close
     * without a merge) and {@code eventTime} (ISO-8601 instant of the event;
     * defaults to now when absent or unparseable).</p>
     *
     * @param body the request body, or {@code null}
     * @return a JSON object carrying {@code ok}, the {@code cancelled} job ids
     *         and their {@code count}; or {@code ok:false} with an
     *         {@code error} when the branch is missing or resolves ambiguously,
     *         so the caller can tell a failed cancellation from one that found
     *         nothing to cancel
     */
    public String handleJson(String body) {
        if (body == null) {
            return "{\"ok\":false,\"error\":\"missing request body\"}";
        }
        String headBranch = JsonFieldExtractor.extractString(body, "headBranch");
        if (headBranch == null || headBranch.isEmpty()) {
            return "{\"ok\":false,\"error\":\"headBranch is required\"}";
        }
        String repoUrl = JsonFieldExtractor.extractString(body, "repoUrl");
        String prNumber = JsonFieldExtractor.extractString(body, "prNumber");
        boolean merged = JsonFieldExtractor.extractBoolean(body, "merged");
        Instant eventInstant = parseInstant(JsonFieldExtractor.extractString(body, "eventTime"));

        List<String> cancelled;
        try {
            cancelled = cancelInFlightJobs(repoUrl, headBranch, prNumber, merged, eventInstant);
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"" + JsonFieldExtractor.escapeJson(e.getMessage()) + "\"}";
        }

        StringBuilder json = new StringBuilder("{\"ok\":true,\"cancelled\":[");
        for (int i = 0; i < cancelled.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(JsonFieldExtractor.escapeJson(cancelled.get(i))).append("\"");
        }
        return json.append("],\"count\":").append(cancelled.size()).append("}").toString();
    }

    /**
     * Cancels the running and pending jobs on {@code headBranch} that the
     * controller accepted before {@code eventInstant}.
     *
     * @param repoUrl      the head repository URL, used to disambiguate the
     *                     branch; may be {@code null}
     * @param headBranch   the pull request's head branch
     * @param prNumber     the pull request number, for the cancellation reason
     * @param merged       whether the pull request was merged (vs closed)
     * @param eventInstant the moment the pull request event occurred; jobs
     *                     submitted at or after it are left untouched. When
     *                     {@code null} the current instant is used.
     * @return the ids of the jobs cancelled, in the order they were cancelled;
     *         empty when no workstream tracks the branch
     * @throws IllegalArgumentException when the branch resolves to more than
     *         one workstream; nothing is cancelled, and the message says how to
     *         disambiguate
     */
    public List<String> cancelInFlightJobs(String repoUrl, String headBranch,
            String prNumber, boolean merged, Instant eventInstant) {
        List<String> cancelled = new ArrayList<>();
        if (statsStore == null || headBranch == null || headBranch.isEmpty()) {
            return cancelled;
        }
        Instant cutoff = eventInstant != null ? eventInstant : Instant.now();
        BranchResolution resolution = workstreamResolver == null ? null
                : workstreamResolver.apply(headBranch, repoUrl);
        if (resolution != null && resolution.error() != null) {
            warn("Terminal PR event on branch=" + headBranch + " not applied: "
                    + resolution.error());
            throw new IllegalArgumentException(resolution.error());
        }
        if (resolution == null || resolution.match() == null) {
            log("No workstream resolved for terminal PR event on branch=" + headBranch
                    + " repoUrl=" + repoUrl + "; nothing to cancel");
            return cancelled;
        }
        String workstreamId = resolution.match().getWorkstreamId();

        String reason = reasonFor(prNumber, merged, headBranch);
        for (ActiveJob job : statsStore.getActiveJobs(workstreamId)) {
            if (job.jobId() == null) continue;
            Instant accepted = statsStore.getSubmittedAt(job.jobId());
            if (accepted == null) accepted = job.startedAt();
            if (accepted == null || !accepted.isBefore(cutoff)) continue;
            cancel(workstreamId, job.jobId(), job.description(), reason, cancelled);
        }
        for (PendingJob job : statsStore.getPendingJobs(workstreamId)) {
            if (job.jobId() == null || job.submittedAt() == null
                    || !job.submittedAt().isBefore(cutoff)) {
                continue;
            }
            cancel(workstreamId, job.jobId(), job.description(), reason, cancelled);
        }

        if (cancelled.isEmpty()) {
            log("Terminal PR event on branch=" + headBranch + " (workstreamId="
                    + workstreamId + ") cancelled no jobs — none were in flight from before"
                    + " the event");
        }
        return cancelled;
    }

    /**
     * Moves one job to {@code CANCELLED} and, only when that transition
     * happened, posts the cancellation event and records the job as cancelled.
     *
     * <p>The transition is the store's atomic in-flight check, so a job that
     * completed on its own after the in-flight snapshot was taken keeps its
     * real terminal record and receives no second completion fan-out.</p>
     *
     * @param workstreamId the workstream the job belongs to
     * @param jobId        the job to cancel
     * @param description  the job's description, carried on the event
     * @param reason       why the job is being cancelled
     * @param cancelled    accumulates the ids cancelled so far
     */
    private void cancel(String workstreamId, String jobId, String description,
            String reason, List<String> cancelled) {
        JobCompletionEvent event = JobCompletionEvent.cancelled(jobId, description, reason);
        if (!statsStore.cancelIfInFlight(jobId, event.getTimestamp(), reason)) {
            log("Job reached a terminal state before it could be cancelled (jobId=" + jobId
                    + " workstreamId=" + workstreamId + "); leaving its record as it completed");
            return;
        }
        // TODO(review): the still-running agent's own later completion overwrites CANCELLED and fans out a second time.
        warn("Cancelling in-flight job (jobId=" + jobId + " workstreamId=" + workstreamId
                + "): " + reason);
        if (terminalEventSink != null) {
            terminalEventSink.accept(workstreamId, event);
        }
        cancelled.add(jobId);
    }

    /**
     * Builds the cancellation reason naming the pull request event, so the
     * operator reading the job record can tell a PR-driven cancellation from a
     * stall or a crash.
     *
     * @param prNumber   the pull request number, or {@code null}
     * @param merged     whether the pull request was merged (vs closed)
     * @param headBranch the branch the jobs ran on
     * @return the reason string
     */
    static String reasonFor(String prNumber, boolean merged, String headBranch) {
        String pr = prNumber == null || prNumber.isEmpty() ? "pull request" : "pull request #" + prNumber;
        return pr + " " + (merged ? "merged" : "closed")
                + "; cancelling in-flight work on branch " + headBranch;
    }

    /**
     * Parses an ISO-8601 instant, returning {@code null} for an absent or
     * unparseable value so the caller falls back to the current instant.
     *
     * @param value the candidate timestamp, or {@code null}
     * @return the parsed instant, or {@code null}
     */
    private Instant parseInstant(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            warn("Unparseable eventTime '" + value + "' on PR event; using current instant");
            return null;
        }
    }
}
