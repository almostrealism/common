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
import io.flowtree.controller.JobStatsStore;
import io.flowtree.controller.PendingJob;
import io.flowtree.controller.WindowQuery;
import io.flowtree.controller.WindowStats;
import io.flowtree.github.GitHubProxyHandler;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Handles the {@code GET /api/throughput/*} retrospective queries for
 * {@link FlowTreeApiEndpoint}, the single-call surface behind the "how much
 * happened in the last N" question (plan §4.1).
 *
 * <p>Keeps the windowing logic out of the near-cap {@code FlowTreeApiEndpoint}
 * and off the near-cap {@link JobStatsStore}: it reads the store through the
 * supplier the endpoint also uses for {@link JobQueryHandler}, so a
 * {@link FlowTreeApiEndpoint#setStatsStore(JobStatsStore) store swap} is picked
 * up without reconstructing the handler.</p>
 *
 * <p>{@code GET /api/throughput/window} accepts either a {@code preset}
 * ({@code 1h}/{@code 3h}/{@code 6h}/{@code 24h}/{@code 7d}) or an explicit
 * {@code from}/{@code to} pair of ISO-8601 instants, plus an optional
 * {@code workstream} filter, and returns the {@link WindowStats} JSON.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see WindowStats
 */
class ThroughputQueryHandler {

    /** Supplies the current stats store, or {@code null} when stats are not configured. */
    private final Supplier<JobStatsStore> storeSupplier;

    /** Silence after which a running job stops accruing agent time in the window measures. */
    private Duration silentThreshold = StatsQueryHandler.DEFAULT_SILENT_THRESHOLD;

    /** Wait past which a still-submitted job counts as {@link PendingJob#LOST}. */
    private Duration lostThreshold = PendingJob.DEFAULT_LOST_THRESHOLD;

    /**
     * Constructs a handler reading through {@code storeSupplier}.
     *
     * @param storeSupplier supplies the current stats store, possibly {@code null}
     */
    ThroughputQueryHandler(Supplier<JobStatsStore> storeSupplier) {
        this.storeSupplier = storeSupplier;
    }

    /**
     * Sets the silence threshold used for the window time measures. A
     * {@code null} or non-positive value restores the default.
     *
     * @param silentThreshold the operator's {@code T_silent}
     */
    void setSilentThreshold(Duration silentThreshold) {
        this.silentThreshold = positiveOr(silentThreshold, StatsQueryHandler.DEFAULT_SILENT_THRESHOLD);
    }

    /**
     * Sets the wait threshold used to count a submitted job {@code LOST}. A
     * {@code null} or non-positive value restores the default.
     *
     * @param lostThreshold the operator's {@code T_lost}
     */
    void setLostThreshold(Duration lostThreshold) {
        this.lostThreshold = positiveOr(lostThreshold, PendingJob.DEFAULT_LOST_THRESHOLD);
    }

    /** Returns {@code value} when positive, otherwise {@code fallback}. */
    private static Duration positiveOr(Duration value, Duration fallback) {
        return (value != null && !value.isNegative() && !value.isZero()) ? value : fallback;
    }

    /**
     * Handles {@code GET /api/throughput/window}.
     *
     * <p>When no stats store is configured a JSON error object is returned with
     * HTTP 200 rather than 500, matching {@link StatsQueryHandler}, so a caller
     * can tell "stats off" from a transport failure.</p>
     *
     * @param session the NanoHTTPD session supplying query parameters
     * @param error   callback used to build HTTP 400 error responses
     * @return an HTTP response carrying the {@link WindowStats} JSON
     */
    Response handleWindow(IHTTPSession session, GitHubProxyHandler.ErrorResponder error) {
        JobStatsStore store = storeSupplier == null ? null : storeSupplier.get();
        if (store == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"error\":\"Stats not configured\"}");
        }

        Map<String, String> params = session.getParms();
        String workstream = emptyToNull(params.get("workstream"));
        Instant now = Instant.now();

        WindowQuery query;
        String preset = emptyToNull(params.get("preset"));
        try {
            if (preset != null) {
                query = WindowQuery.ofPreset(preset, now, silentThreshold).withWorkstream(workstream);
            } else {
                String fromParam = emptyToNull(params.get("from"));
                String toParam = emptyToNull(params.get("to"));
                if (fromParam == null || toParam == null) {
                    return error.respond("Supply 'preset' (1h/3h/6h/24h/7d) or both 'from' and 'to' "
                            + "as ISO-8601 instants");
                }
                Instant from = Instant.parse(fromParam);
                Instant to = Instant.parse(toParam);
                if (!to.isAfter(from)) {
                    return error.respond("'to' must be after 'from'");
                }
                query = new WindowQuery(from, to, workstream, silentThreshold);
            }
        } catch (IllegalArgumentException | DateTimeException e) {
            return error.respond(e.getMessage());
        }

        WindowStats stats = store.getWindowStats(query, now, lostThreshold);
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,
            "application/json", stats.toJson());
    }

    /** Returns {@code null} for a {@code null} or empty string, else the string. */
    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
