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

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import io.flowtree.controller.ActiveJob;
import io.flowtree.controller.DailyAgentTime;
import io.flowtree.controller.JobInterval;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.controller.PendingJob;
import io.flowtree.github.GitHubProxyHandler;
import io.flowtree.jobs.JobCompletionEvent;

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

    /**
     * Default silence a {@code STARTED} job may accumulate before the
     * active-job listing labels it {@code UNRESPONSIVE} (the operator's
     * {@code T_silent}). Advisory only — labelling, not termination — so it is
     * set well below the scanner's destructive dead threshold, realizing the
     * operator's "silent means dead" signal far sooner than a job is actually
     * failed.
     */
    public static final Duration DEFAULT_SILENT_THRESHOLD = Duration.ofMinutes(15);

    /**
     * Default wait a {@code SUBMITTED} job may accumulate before the
     * pending-job listing labels it {@code LOST} (the operator's
     * {@code T_lost}) — a job the controller accepted but no worker ever
     * picked up. Advisory only; a lost job never ran, so there is nothing to
     * fail.
     */
    public static final Duration DEFAULT_LOST_THRESHOLD = Duration.ofMinutes(30);

    /** The backing stats store; may be {@code null} when stats are not configured. */
    private final JobStatsStore statsStore;

    /** Silence threshold for the {@code UNRESPONSIVE} label; see {@link #DEFAULT_SILENT_THRESHOLD}. */
    private Duration silentThreshold = DEFAULT_SILENT_THRESHOLD;

    /** Wait threshold for the {@code LOST} label; see {@link #DEFAULT_LOST_THRESHOLD}. */
    private Duration lostThreshold = DEFAULT_LOST_THRESHOLD;

    /**
     * Constructs a new handler backed by the given stats store.
     *
     * @param statsStore the store to query; {@code null} disables stats queries
     */
    StatsQueryHandler(JobStatsStore statsStore) {
        this.statsStore = statsStore;
    }

    /**
     * Sets the silence threshold used to label an active job
     * {@code UNRESPONSIVE}. A {@code null} or non-positive value restores the
     * {@link #DEFAULT_SILENT_THRESHOLD default}.
     *
     * @param silentThreshold the operator's {@code T_silent}
     */
    void setSilentThreshold(Duration silentThreshold) {
        this.silentThreshold = (silentThreshold != null && !silentThreshold.isNegative()
                && !silentThreshold.isZero()) ? silentThreshold : DEFAULT_SILENT_THRESHOLD;
    }

    /**
     * Sets the wait threshold used to label a pending job {@code LOST}. A
     * {@code null} or non-positive value restores the
     * {@link #DEFAULT_LOST_THRESHOLD default}.
     *
     * @param lostThreshold the operator's {@code T_lost}
     */
    void setLostThreshold(Duration lostThreshold) {
        this.lostThreshold = (lostThreshold != null && !lostThreshold.isNegative()
                && !lostThreshold.isZero()) ? lostThreshold : DEFAULT_LOST_THRESHOLD;
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
     * Records the lifecycle phase a running job's harness says it has
     * reached. No-op when no store is configured.
     *
     * @param jobId the job reporting
     * @param phase the phase description, as the harness worded it
     */
    void recordPhase(String jobId, String phase) {
        if (statsStore == null) return;
        statsStore.recordPhase(jobId, phase);
    }

    /**
     * Returns the phase most recently recorded for a job.
     *
     * @param jobId the job identifier
     * @return the phase description, or {@code null} when none is recorded
     *         or no store is configured
     */
    String jobPhase(String jobId) {
        return statsStore == null ? null : statsStore.getJobPhase(jobId);
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
        for (ActiveJob job : statsStore.getActiveJobs(workstreamId)) {
            if (!first) json.append(",");
            first = false;
            json.append(job.toJson(now, silentThreshold));
        }
        json.append("]");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", json.toString());
    }

    /**
     * Handles a {@code GET /api/workstreams/{id}/jobs/pending} request.
     *
     * <p>Lists jobs the controller accepted but which no worker has started —
     * {@code SUBMITTED} rows that have not moved to {@code STARTED}. Each entry
     * carries its wait since submission and a derived {@code state} of
     * {@code SUBMITTED} or {@code LOST}, the latter once the wait passes the
     * configured lost threshold. This is the surface that makes a
     * submitted-but-dropped job visible; it is never auto-failed, because a job
     * that never ran has no worker to fail.</p>
     *
     * @param workstreamId the workstream whose pending jobs to list, or
     *                     {@code null} for every workstream
     * @return an HTTP response carrying a JSON array, empty when nothing is
     *         pending or no store is configured
     */
    Response handlePendingJobs(String workstreamId) {
        if (statsStore == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", "[]");
        }
        Instant now = Instant.now();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (PendingJob job : statsStore.getPendingJobs(workstreamId)) {
            if (!first) json.append(",");
            first = false;
            json.append(job.toJson(now, lostThreshold));
        }
        json.append("]");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", json.toString());
    }

    /**
     * Handles a {@code GET /api/jobs/in-flight} request: the true count of jobs
     * holding (or about to hold) an ar-manager connection across every
     * workstream, which is what a deploy drain must block on.
     *
     * <p>"In flight" is {@code STARTED} + {@code SUBMITTED}, read from the same
     * {@link JobStatsStore#getActiveJobs(String) active} and
     * {@link JobStatsStore#getPendingJobs(String) pending} views the operator's
     * job listings use, with a {@code null} workstream meaning every workstream.
     * Driving the count off those views rather than a hard-coded set of status
     * strings is deliberate: a future non-terminal status that should hold a
     * deploy is counted here as soon as it appears in those views, rather than
     * being silently dropped by a drain that enumerates statuses it knows — the
     * failure mode this endpoint replaces. The response is a small JSON object
     * {@code {"active":N,"pending":N,"total":N}} so the drain can log what it is
     * waiting on. A zero-count object (not an error) is returned when no store
     * is configured, so a controller without job stats reads as "nothing in
     * flight" rather than failing the drain's probe.</p>
     *
     * @return an HTTP response carrying the in-flight count object
     */
    Response handleInFlight() {
        int active = statsStore == null ? 0 : statsStore.getActiveJobs(null).size();
        int pending = statsStore == null ? 0 : statsStore.getPendingJobs(null).size();
        String json = "{\"active\":" + active
                + ",\"pending\":" + pending
                + ",\"total\":" + (active + pending) + "}";
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", json);
    }

    /**
     * Handles a {@code POST /api/jobs/{id}/heartbeat} request: records the
     * liveness heartbeat a job's own harness posts on a timer, along with the
     * node and phase it names.
     *
     * <p>This is the dedicated route the harness heartbeat sender targets, so
     * liveness no longer depends on a status event (and therefore on agent
     * message activity) landing. A silent but healthy job keeps beating; a
     * dead worker visibly stops. The {@code node} the payload carries is the
     * worker identity the controller otherwise never learns, and the
     * {@code phase}, when present, updates the job's recorded phase exactly as
     * a phase-tagged message would. A no-op when no store is configured, so a
     * controller without job stats answers rather than erroring.</p>
     *
     * @param jobId the job reporting itself alive
     * @param body  the POST body carrying optional {@code node} and {@code phase}
     * @return a JSON {@code {"ok":true}} response
     */
    Response handleHeartbeat(String jobId, String body) {
        if (statsStore != null && jobId != null && !jobId.isEmpty()) {
            String node = JsonFieldExtractor.extractString(body, "node");
            String phase = JsonFieldExtractor.extractString(body, "phase");
            statsStore.recordHeartbeat(jobId, Instant.now(), node);
            if (phase != null && !phase.isEmpty()) {
                statsStore.recordPhase(jobId, phase);
            }
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", "{\"ok\":true}");
    }

    /**
     * Handles a {@code GET /api/stats} request.
     *
     * <p>When no stats store is configured a JSON error object is returned
     * with HTTP 200 rather than 500, so callers can detect the condition
     * without treating it as a transport error.</p>
     *
     * <p>The response also carries {@code lastJobAt},
     * {@code lastJobStartedAt} and {@code lastJobFinishedAt} when a store is
     * available, sourced from the most recent job on the filtered
     * workstream. These timestamps come from the persisted {@code job_timing}
     * row rather than the controller's read-time instant, so a caller can
     * compute its own idle window without an additional database query.</p>
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
        json.append(",\"lastJobAt\":");
        appendLastJobTimestamps(json, workstreamFilter);
        json.append("}");

        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", json.toString());
    }

    /**
     * Handles a {@code GET /api/stats/daily} request: agent time per day across
     * every workstream, in the caller's time zone (see {@link DailyAgentTime}).
     *
     * <p>Query parameters: {@code from} and {@code to}, ISO dates, inclusive,
     * at most {@link DailyAgentTime#MAX_DAYS} days apart; and {@code tz}, a
     * zone ID such as {@code America/Los_Angeles}, defaulting to UTC. As with
     * {@code /api/stats}, a controller without a stats store answers with a
     * JSON error object and HTTP 200.</p>
     *
     * @param session the NanoHTTPD session supplying query parameters
     * @param error   callback used to build HTTP 400 error responses
     * @return an HTTP response containing the per-day JSON
     */
    Response handleDaily(IHTTPSession session, GitHubProxyHandler.ErrorResponder error) {
        if (statsStore == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"error\":\"Stats not configured\"}");
        }

        Map<String, String> params = session.getParms();
        LocalDate from;
        LocalDate to;
        ZoneId zone;
        try {
            from = LocalDate.parse(params.getOrDefault("from", ""));
            to = LocalDate.parse(params.getOrDefault("to", ""));
        } catch (DateTimeException e) {
            return error.respond("'from' and 'to' must be ISO dates, e.g. 2026-10-01");
        }
        try {
            String tz = params.get("tz");
            zone = (tz == null || tz.isEmpty()) ? ZoneId.of("UTC") : ZoneId.of(tz);
        } catch (DateTimeException e) {
            return error.respond("Unknown time zone: " + params.get("tz"));
        }
        long span = to.toEpochDay() - from.toEpochDay() + 1;
        if (span < 1 || span > DailyAgentTime.MAX_DAYS) {
            return error.respond("'to' must be on or after 'from', at most "
                + DailyAgentTime.MAX_DAYS + " days later");
        }

        List<JobInterval> jobs = statsStore.getJobIntervals(
            DailyAgentTime.rangeStart(from, zone), DailyAgentTime.rangeEnd(to, zone));
        List<DailyAgentTime.Day> days = DailyAgentTime.compute(
            jobs, from, to, zone, Instant.now(), silentThreshold);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", DailyAgentTime.toJson(zone, days));
    }

    /**
     * Appends the {@code lastJobAt} / {@code lastJobStartedAt} /
     * {@code lastJobFinishedAt} members describing the most recent job on
     * the filtered workstream. Each field is rendered as JSON null when no
     * job exists, so the response shape is stable regardless of whether the
     * store has any rows for the workstream.
     *
     * @param json             the response builder to append to
     * @param workstreamFilter the workstream filter; {@code null} or empty
     *                         means no workstream filter was supplied
     */
    private void appendLastJobTimestamps(StringBuilder json, String workstreamFilter) {
        if (workstreamFilter == null || workstreamFilter.isEmpty()) {
            json.append("null,\"lastJobStartedAt\":null,\"lastJobFinishedAt\":null");
            return;
        }
        List<JobCompletionEvent> recent = statsStore.getRecentJobs(workstreamFilter, 1);
        if (recent.isEmpty()) {
            json.append("null,\"lastJobStartedAt\":null,\"lastJobFinishedAt\":null");
            return;
        }
        JobCompletionEvent latest = recent.get(0);
        Instant eventTime = latest.getEventTime();
        Instant started = latest.getStartedAt();
        Instant finished = latest.getFinishedAt();
        json.append(eventTime == null ? "null" : "\"" + eventTime + "\"");
        json.append(",\"lastJobStartedAt\":")
                .append(started == null ? "null" : "\"" + started + "\"");
        json.append(",\"lastJobFinishedAt\":")
                .append(finished == null ? "null" : "\"" + finished + "\"");
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
