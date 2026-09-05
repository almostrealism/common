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
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackNotifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serves the read-only job record endpoints for {@link FlowTreeApiEndpoint}.
 *
 * <p>Routes dispatched by this handler:</p>
 * <ul>
 *   <li>{@code GET /api/workstreams/{id}/jobs?limit=N} — recent jobs for a workstream</li>
 *   <li>{@code GET /api/jobs/{jobId}} — a single job record</li>
 * </ul>
 *
 * <p>Both routes answer with the same JSON shape, produced here, so a job
 * looks identical whether it was reached through its workstream's listing or
 * by its own ID.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
public final class JobQueryHandler {

    /** Resolves the notifier and workstream that own a given job. */
    private final NotifierRegistry notifiers;

    /**
     * Creates a handler reading job records through the given registry.
     *
     * @param notifiers the registry owning the job history
     */
    JobQueryHandler(NotifierRegistry notifiers) {
        this.notifiers = notifiers;
    }

    /**
     * Handles {@code GET /api/workstreams/{id}/jobs?limit=N}.
     * Returns the most recent jobs for the workstream, newest first.
     *
     * @param workstreamId the workstream identifier
     * @param limit        maximum number of jobs to return
     * @return JSON array of job events
     */
    Response listJobs(String workstreamId, int limit) {
        SlackNotifier n = notifiers.notifierFor(workstreamId);
        List<JobCompletionEvent> page = n != null
                ? n.getRecentJobs(workstreamId, limit) : new ArrayList<>();

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (JobCompletionEvent event : page) {
            if (!first) json.append(",");
            first = false;
            json.append(jobEventToJson(event));
        }
        json.append("]");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
    }

    /**
     * Handles {@code GET /api/jobs/{jobId}}.
     * Returns the most recent event for the specified job.
     *
     * @param jobId the job identifier
     * @return JSON object for the job event, or 404 if not found
     */
    Response getJob(String jobId) {
        JobCompletionEvent event = notifiers.findJob(jobId);
        if (event == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND,
                    "application/json", "{\"ok\":false,\"error\":\"Job not found\"}");
        }
        String workstreamId = notifiers.findWorkstreamIdForJob(jobId);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", jobEventToJson(event, workstreamId));
    }

    /**
     * Serialises a {@link JobCompletionEvent} to a JSON object string.
     *
     * @param event the event to serialise
     * @return JSON object string
     */
    private String jobEventToJson(JobCompletionEvent event) {
        return jobEventToJson(event, null);
    }

    /**
     * Serialises a {@link JobCompletionEvent} to a JSON object string,
     * optionally including the owning workstream identifier.
     *
     * <p>Three properties of the emitted shape are deliberate:</p>
     * <ul>
     *   <li>{@code timestamp} comes from the event's own time, which is the
     *       persisted row's value when there is one and the constructor stamp
     *       when the event is still purely in memory (a pre-persist
     *       {@code CodingAgentJob} whose terminal event has not landed). Both
     *       the single-job lookup and the workstream listing therefore report
     *       when the job happened, not when the controller read it.</li>
     *   <li>{@code costIncomplete} is always emitted, matching
     *       {@link JobCompletionEvent#toJson()} and the documented wire shape,
     *       so a consumer never has to read its absence as {@code false}.</li>
     *   <li>The cost block is emitted when the total is positive <em>or</em>
     *       the cost is incomplete. An inactivity-killed session can leave the
     *       total at zero despite real, uncosted work, and the total and
     *       breakdowns are still worth reporting as a lower bound.</li>
     * </ul>
     *
     * @param event         the event to serialise
     * @param workstreamId  the workstream that owns this job, or {@code null}
     * @return JSON object string
     */
    private String jobEventToJson(JobCompletionEvent event, String workstreamId) {
        StringBuilder j = new StringBuilder();
        j.append("{");
        j.append("\"jobId\":\"").append(JsonFieldExtractor.escapeJson(event.getJobId())).append("\"");
        j.append(",\"status\":\"").append(event.getStatus().name()).append("\"");
        j.append(",\"description\":\"").append(JsonFieldExtractor.escapeJson(event.getDescription())).append("\"");
        Instant eventTime = event.getEventTime();
        j.append(",\"timestamp\":\"").append(eventTime.toString()).append("\"");
        Instant started = event.getStartedAt();
        if (started != null) {
            j.append(",\"startedAt\":\"").append(started.toString()).append("\"");
        }
        Instant finished = event.getFinishedAt();
        if (finished != null) {
            j.append(",\"finishedAt\":\"").append(finished.toString()).append("\"");
        }
        if (workstreamId != null) {
            j.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(workstreamId)).append("\"");
        }
        if (event.getTargetBranch() != null) {
            j.append(",\"targetBranch\":\"").append(JsonFieldExtractor.escapeJson(event.getTargetBranch())).append("\"");
        }
        if (event.getCommitHash() != null) {
            j.append(",\"commitHash\":\"").append(JsonFieldExtractor.escapeJson(event.getCommitHash())).append("\"");
        }
        if (event.getPullRequestUrl() != null) {
            j.append(",\"pullRequestUrl\":\"").append(JsonFieldExtractor.escapeJson(event.getPullRequestUrl())).append("\"");
        }
        if (event.getErrorMessage() != null) {
            j.append(",\"errorMessage\":\"").append(JsonFieldExtractor.escapeJson(event.getErrorMessage())).append("\"");
        }
        double totalCost = event.getTotalCostUsd();
        boolean costIncomplete = event.isCostIncomplete();
        j.append(",\"costIncomplete\":").append(costIncomplete);
        if (totalCost > 0 || costIncomplete) {
            j.append(String.format(",\"totalCostUsd\":%.2f", totalCost));
            Map<String, Double> costByRunner = event.getCostByRunner();
            if (costByRunner != null && !costByRunner.isEmpty()) {
                j.append(",\"costByRunner\":{");
                boolean first = true;
                for (Map.Entry<String, Double> e : costByRunner.entrySet()) {
                    if (!first) j.append(",");
                    first = false;
                    j.append("\"").append(JsonFieldExtractor.escapeJson(e.getKey())).append("\":")
                        .append(String.format("%.2f", e.getValue() != null ? e.getValue() : 0.0));
                }
                j.append("}");
            }
            Map<String, Double> costByModel = event.getCostByModel();
            if (costByModel != null && !costByModel.isEmpty()) {
                j.append(",\"costByModel\":{");
                boolean first = true;
                for (Map.Entry<String, Double> e : costByModel.entrySet()) {
                    if (!first) j.append(",");
                    first = false;
                    j.append("\"").append(JsonFieldExtractor.escapeJson(e.getKey())).append("\":")
                        .append(String.format("%.2f", e.getValue() != null ? e.getValue() : 0.0));
                }
                j.append("}");
            }
        }
        j.append("}");
        return j.toString();
    }
}
