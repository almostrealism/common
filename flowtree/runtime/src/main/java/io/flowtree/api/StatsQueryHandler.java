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
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.JsonFieldExtractor;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.github.GitHubProxyHandler;

/**
 * Handles {@code GET /api/stats} requests for {@link FlowTreeApiEndpoint}.
 *
 * <p>Queries the configured {@link JobStatsStore} for weekly aggregated
 * statistics and returns them as a JSON object with {@code thisWeek} and
 * {@code lastWeek} sections. Statistics are computed in UTC.</p>
 *
 * <p>Supported query parameters:</p>
 * <ul>
 *   <li>{@code workstream} &ndash; optional workstream ID filter; when omitted,
 *       all workstreams are included</li>
 *   <li>{@code period} &ndash; only {@code "weekly"} is currently supported
 *       (the default); other values produce a 400 error</li>
 * </ul>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see JobStatsStore
 */
class StatsQueryHandler {

    /** The backing stats store; may be {@code null} when stats are not configured. */
    private final JobStatsStore statsStore;

    /**
     * Constructs a new handler backed by the given stats store.
     *
     * @param statsStore the store to query; {@code null} disables stats queries
     */
    StatsQueryHandler(JobStatsStore statsStore) {
        this.statsStore = statsStore;
    }

    /**
     * Returns the backing store, for callers that read job history directly.
     *
     * @return the store, or {@code null} when stats are not configured
     */
    JobStatsStore store() {
        return statsStore;
    }

    /**
     * Records a liveness heartbeat for a running job.
     *
     * <p>No-op when no store is configured, so a controller running without
     * job stats behaves exactly as it did before rather than failing the
     * request that carried the signal.</p>
     *
     * @param jobId       the job reporting itself alive
     * @param heartbeatAt the moment it reported
     */
    void recordHeartbeat(String jobId, Instant heartbeatAt) {
        if (statsStore == null) return;
        statsStore.recordHeartbeat(jobId, heartbeatAt);
    }

    /**
     * Handles a {@code GET /api/workstreams/{id}/jobs/active} request.
     *
     * <p>Each entry carries the job's age and how long it has been since its
     * last liveness signal, which is what distinguishes a job running slowly
     * from one that has stopped. An entry with a large
     * {@code sinceHeartbeatSeconds} is a candidate for
     * {@link io.flowtree.controller.StuckJobScanner} and is worth an
     * operator's attention before the scanner reaches it.</p>
     *
     * @param workstreamId the workstream whose active jobs to list, or
     *                     {@code null} for every workstream
     * @return an HTTP response carrying a JSON array, empty when nothing is
     *         running or no store is configured
     */
    Response handleActiveJobs(String workstreamId) {
        if (statsStore == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", "[]");
        }
        Instant now = Instant.now();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (JobStatsStore.ActiveJob job : statsStore.getActiveJobs(workstreamId)) {
            if (!first) json.append(",");
            first = false;
            json.append(job.toJson(now));
        }
        json.append("]");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", json.toString());
    }

    /**
     * Handles a {@code GET /api/stats} request.
     *
     * <p>When no stats store is configured a JSON error object is returned
     * with HTTP 200 rather than 500, so callers can detect the condition
     * without treating it as a transport error.</p>
     *
     * @param session  the NanoHTTPD session supplying query parameters
     * @param error    callback used to build HTTP 400 error responses
     * @return an HTTP response containing weekly stats JSON
     */
    Response handle(IHTTPSession session, GitHubProxyHandler.ErrorResponder error) {
        if (statsStore == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"error\":\"Stats not configured\"}");
        }

        String period = session.getParms().get("period");
        if (period != null && !period.isEmpty() && !"weekly".equals(period)) {
            return error.respond("Unsupported period: " + period + ". Only 'weekly' is supported.");
        }

        String workstreamFilter = session.getParms().get("workstream");

        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);

        StringBuilder json = new StringBuilder();
        json.append("{\"thisWeek\":");
        json.append(formatWeekJson(thisWeekStart, workstreamFilter));
        json.append(",\"lastWeek\":");
        json.append(formatWeekJson(lastWeekStart, workstreamFilter));
        json.append("}");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", json.toString());
    }

    /**
     * Formats a single week's statistics as a JSON object string.
     *
     * <p>The returned object has the shape:</p>
     * <pre>{@code
     * {
     *   "weekStart": "2026-03-30",
     *   "stats": {
     *     "ws-example": { "jobCount": 5, "successCount": 4, ... }
     *   }
     * }
     * }</pre>
     *
     * @param weekStart        the Monday that starts the reporting week
     * @param workstreamFilter optional workstream ID to restrict results;
     *                         {@code null} or empty includes all workstreams
     * @return a JSON object string for this week's data
     */
    private String formatWeekJson(LocalDate weekStart, String workstreamFilter) {
        StringBuilder json = new StringBuilder();
        json.append("{\"weekStart\":\"").append(weekStart).append("\",\"stats\":{");

        if (workstreamFilter != null && !workstreamFilter.isEmpty()) {
            JobStatsStore.WeeklyStats stats = statsStore.getWeeklyStats(workstreamFilter, weekStart);
            json.append("\"").append(JsonFieldExtractor.escapeJson(workstreamFilter)).append("\":");
            appendStatsJson(json, stats);
        } else {
            Map<String, JobStatsStore.WeeklyStats> byWs =
                statsStore.getWeeklyStatsByWorkstream(weekStart);
            boolean first = true;
            for (Map.Entry<String, JobStatsStore.WeeklyStats> entry : byWs.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(JsonFieldExtractor.escapeJson(entry.getKey())).append("\":");
                appendStatsJson(json, entry.getValue());
            }
        }

        json.append("}}");
        return json.toString();
    }

    /**
     * Appends a single {@link JobStatsStore.WeeklyStats} as a JSON object to
     * the given builder.
     *
     * @param json  the builder to append to
     * @param stats the stats object to serialise
     */
    private static void appendStatsJson(StringBuilder json, JobStatsStore.WeeklyStats stats) {
        json.append("{\"jobCount\":").append(stats.jobCount);
        json.append(",\"successCount\":").append(stats.successCount);
        json.append(",\"failedCount\":").append(stats.failedCount);
        json.append(",\"cancelledCount\":").append(stats.cancelledCount);
        json.append(",\"totalWallClockMs\":").append(stats.totalWallClockMs);
        json.append(",\"totalDurationMs\":").append(stats.totalDurationMs);
        json.append(",\"totalCostUsd\":").append(stats.totalCostUsd);
        json.append(",\"totalTurns\":").append(stats.totalTurns);
        appendCostMapJson(json, "costByRunner", stats.costByRunner);
        appendCostMapJson(json, "costByModel", stats.costByModel);
        json.append("}");
    }

    /**
     * Appends a JSON key-value map for a cost breakdown.
     * Delegates to {@link JsonFieldExtractor#appendDoubleMapJson} for the shared
     * implementation.
     *
     * @param json     the builder to append to
     * @param keyName  the JSON key name (e.g. {@code "costByRunner"})
     * @param costMap map of cost key to USD value
     */
    private static void appendCostMapJson(StringBuilder json, String keyName,
                                       Map<String, Double> costMap) {
        JsonFieldExtractor.appendDoubleMapJson(json, keyName, costMap);
    }

}
