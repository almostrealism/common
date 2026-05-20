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

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.jobs.JobCompletionEvent;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Handles the workstream archive / unarchive / delete endpoints
 * ({@code POST /api/workstreams/{id}/archive},
 * {@code /unarchive}, {@code /delete}) for {@link FlowTreeApiEndpoint}.
 *
 * <p>Archiving is non-destructive: the workstream record stays in the
 * config so historical job records and memories remain queryable, but
 * the workstream is hidden from the default
 * {@code GET /api/workstreams} response. Optionally the bound Slack
 * channel is archived via {@code conversations.archive}; failure to
 * archive the channel does not block the workstream archive.</p>
 *
 * <p>Deletion is destructive: the workstream config entry is removed
 * entirely. Deletion requires that the workstream is archived first,
 * unless the caller supplies {@code force=true}. Both archive and
 * delete reject the call when one or more jobs on the workstream are
 * still active ({@link JobCompletionEvent.Status#STARTED}); the
 * response carries the active job IDs so the caller can decide what
 * to cancel.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
final class WorkstreamLifecycleHandler {

    /** Threshold above which an unexpectedly large unarchive request body is logged. */
    private static final int MAX_REQUEST_BODY_LOG = 1024;

    /** Aggregates the per-workspace notifiers (also holds the active-job index). */
    private final NotifierRegistry notifiers;
    /** Slack listener used to persist YAML edits after each mutation; may be {@code null}. */
    private final SlackListener listener;
    /** Reads the POST body from a NanoHTTPD session; reused from the parent endpoint. */
    private final Function<IHTTPSession, String> readBody;
    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;

    /**
     * Constructs a new handler bound to the given notifier registry and
     * listener, delegating I/O and logging to the parent endpoint.
     *
     * @param notifiers     the workspace notifier registry
     * @param listener      the Slack listener used for YAML persistence
     *                      (may be {@code null} in tests)
     * @param readBody      body reader supplied by the parent endpoint
     * @param errorResponse 400-error response factory
     * @param log           log line consumer
     */
    WorkstreamLifecycleHandler(NotifierRegistry notifiers, SlackListener listener,
                               Function<IHTTPSession, String> readBody,
                               Function<String, Response> errorResponse,
                               Consumer<String> log) {
        this.notifiers = notifiers;
        this.listener = listener;
        this.readBody = readBody;
        this.errorResponse = errorResponse;
        this.log = log;
    }

    /**
     * Handles {@code POST /api/workstreams/{id}/archive}. Sets the archived
     * flag, persists the config, and optionally archives the bound Slack
     * channel (request body may carry {@code {"archiveSlackChannel": false}}
     * to opt out; the default is to archive the channel).
     *
     * @param session      the HTTP session (body is optional)
     * @param workstreamId the workstream to archive
     */
    Response handleArchive(IHTTPSession session, String workstreamId) {
        SlackNotifier ownerNotifier = notifiers.notifierFor(workstreamId);
        Workstream workstream = ownerNotifier != null
                ? ownerNotifier.getWorkstream(workstreamId) : null;
        if (workstream == null) {
            return errorResponse.apply("Unknown workstream: " + workstreamId);
        }
        Response activeErr = rejectIfActiveJobs(workstreamId, "archive");
        if (activeErr != null) return activeErr;

        String body = readBody.apply(session);
        boolean archiveSlackChannel = true;
        if (body != null && body.contains("archiveSlackChannel")) {
            archiveSlackChannel = JsonFieldExtractor.extractBoolean(body, "archiveSlackChannel");
        }
        workstream.setArchived(true);
        if (listener != null) listener.persistConfig();

        String archivedAt = Instant.now().toString();
        boolean slackChannelArchived = false;
        String slackError = null;
        if (archiveSlackChannel && workstream.getChannelId() != null
                && !workstream.getChannelId().isEmpty()) {
            slackError = ownerNotifier.archiveChannel(workstream.getChannelId());
            slackChannelArchived = slackError == null;
            if (slackError != null) {
                log.accept("Workstream " + workstreamId
                        + " archived but Slack channel "
                        + workstream.getChannelId()
                        + " could not be archived: " + slackError);
            }
        }
        log.accept("Archived workstream " + workstreamId
                + " (slackChannelArchived=" + slackChannelArchived + ")");

        StringBuilder json = new StringBuilder();
        json.append("{\"ok\":true,\"workstreamId\":\"")
                .append(JsonFieldExtractor.escapeJson(workstreamId))
                .append("\",\"archivedAt\":\"").append(archivedAt)
                .append("\",\"slackChannelArchived\":").append(slackChannelArchived);
        if (slackError != null) {
            json.append(",\"slackChannelArchiveError\":")
                    .append(FlowTreeApiEndpoint.escapeJsonValue(slackError));
        }
        json.append("}");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", json.toString());
    }

    /**
     * Handles {@code POST /api/workstreams/{id}/unarchive}. Clears the
     * archived flag and persists the config. The Slack channel — if
     * previously archived — must be unarchived manually from the Slack UI;
     * Slack's {@code conversations.unarchive} fires notifications to
     * channel members which we do not want to trigger automatically.
     *
     * @param session      the HTTP session (body is drained but ignored)
     * @param workstreamId the workstream to unarchive
     */
    Response handleUnarchive(IHTTPSession session, String workstreamId) {
        // Drain the request body so NanoHTTPD's HTTP keep-alive does not
        // leave bytes on the socket for the next request on the same
        // connection. The body itself carries no parameters for unarchive.
        String body = readBody.apply(session);
        if (body != null && body.length() > MAX_REQUEST_BODY_LOG) {
            log.accept("Unarchive request body exceeded "
                    + MAX_REQUEST_BODY_LOG + " chars; ignoring trailing bytes");
        }
        SlackNotifier ownerNotifier = notifiers.notifierFor(workstreamId);
        Workstream workstream = ownerNotifier != null
                ? ownerNotifier.getWorkstream(workstreamId) : null;
        if (workstream == null) {
            return errorResponse.apply("Unknown workstream: " + workstreamId);
        }
        workstream.setArchived(false);
        if (listener != null) listener.persistConfig();
        log.accept("Unarchived workstream " + workstreamId);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json",
                "{\"ok\":true,\"workstreamId\":\""
                        + JsonFieldExtractor.escapeJson(workstreamId) + "\"}");
    }

    /**
     * Handles {@code POST /api/workstreams/{id}/delete}. Removes the
     * workstream config entry entirely. Requires {@code archived=true}
     * unless the caller passes {@code {"force": true}}. Rejects the call
     * regardless of {@code force} when any job on the workstream is still
     * active.
     *
     * <p>This endpoint does not touch tracker tasks or memories. The
     * MCP tool layer clears tracker task linkage before calling the
     * controller; memories remain queryable by repo URL + branch.</p>
     *
     * @param session      the HTTP session (body may carry {@code force})
     * @param workstreamId the workstream to delete
     */
    Response handleDelete(IHTTPSession session, String workstreamId) {
        SlackNotifier ownerNotifier = notifiers.notifierFor(workstreamId);
        Workstream workstream = ownerNotifier != null
                ? ownerNotifier.getWorkstream(workstreamId) : null;
        if (workstream == null) {
            return errorResponse.apply("Unknown workstream: " + workstreamId);
        }
        String body = readBody.apply(session);
        boolean force = body != null && JsonFieldExtractor.extractBoolean(body, "force");
        if (!workstream.isArchived() && !force) {
            return errorResponse.apply("Workstream " + workstreamId
                    + " is not archived. Archive it first (workstream_archive)"
                    + " or pass force=true to delete a live workstream.");
        }
        Response activeErr = rejectIfActiveJobs(workstreamId, "delete");
        if (activeErr != null) return activeErr;

        if (listener != null) {
            listener.unregisterAndPersistWorkstream(workstream);
        } else {
            ownerNotifier.removeWorkstream(workstreamId);
        }
        log.accept("Deleted workstream " + workstreamId);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json",
                "{\"ok\":true,\"workstreamId\":\""
                        + JsonFieldExtractor.escapeJson(workstreamId) + "\"}");
    }

    /**
     * Returns an error response when one or more jobs on the workstream are
     * still active ({@link JobCompletionEvent.Status#STARTED}); {@code null}
     * when the workstream is safe to mutate destructively.
     *
     * @param workstreamId the workstream to check
     * @param verb         the action being attempted, used in the message
     */
    private Response rejectIfActiveJobs(String workstreamId, String verb) {
        List<String> activeJobs = notifiers.getActiveJobIds(workstreamId);
        if (activeJobs.isEmpty()) return null;
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < activeJobs.size(); i++) {
            if (i > 0) ids.append(", ");
            ids.append(activeJobs.get(i));
        }
        return errorResponse.apply("workstream has " + activeJobs.size()
                + " active job" + (activeJobs.size() == 1 ? "" : "s")
                + "; cancel or wait for completion before " + verb
                + "ing. Active job IDs: " + ids);
    }
}
