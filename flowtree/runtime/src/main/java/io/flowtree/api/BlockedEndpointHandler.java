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
import io.flowtree.jobs.BlockReport;
import io.flowtree.jobs.BlockReportRegistry;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Handles the job "blocked" endpoint
 * ({@code POST|GET /api/workstreams/{id}/jobs/{jobId}/blocked}) for
 * {@link FlowTreeApiEndpoint}.
 *
 * <p>The {@code job_report_blocked} ar-manager tool POSTs a {@code category}
 * (one of {@link BlockReport#CATEGORIES}) and a free-text {@code detail}; the
 * handler records the claim in a {@link BlockReportRegistry} keyed by job id. A
 * category outside the fixed set is rejected with a 400, so only a well-formed
 * claim is ever stored.</p>
 *
 * <p>The worker that ran the job reads the claim back with a GET before it
 * decides whether to run enforcement (see {@link BlockReport#fetch(String,
 * boolean)}). The GET answers {@code {"blocked":true,"category":...,
 * "detail":...}} when a report exists and {@code {"blocked":false}} otherwise.
 * Verification is the worker's concern; the controller only stores and returns
 * the agent's claim.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see BlockReportRegistry
 */
public final class BlockedEndpointHandler {

    /** The controller's record of reported blocks, keyed by job id. */
    private final BlockReportRegistry registry;
    /** Reads the POST body from a NanoHTTPD session; reused from the parent endpoint. */
    private final Function<IHTTPSession, String> readBody;
    /** Builds a 400 error response with the given message. */
    private final Function<String, Response> errorResponse;
    /** Emits a log line via the parent endpoint's logger. */
    private final Consumer<String> log;

    /**
     * Constructs a handler bound to the given registry.
     *
     * @param registry      the registry reports are recorded in and read from
     * @param readBody      body reader supplied by the parent endpoint
     * @param errorResponse 400-error response factory
     * @param log           log line consumer
     */
    BlockedEndpointHandler(BlockReportRegistry registry,
                           Function<IHTTPSession, String> readBody,
                           Function<String, Response> errorResponse,
                           Consumer<String> log) {
        this.registry = registry;
        this.readBody = readBody;
        this.errorResponse = errorResponse;
        this.log = log;
    }

    /**
     * Records a block an agent reported for a job.
     *
     * @param session the HTTP session carrying the {@code category}/{@code detail} body
     * @param jobId   the job identifier from the URL path, or {@code null}
     * @return a JSON response: {@code {"ok":true}} on success, 400 on a missing
     *         job id or an unknown category
     */
    public Response handlePost(IHTTPSession session, String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            return errorResponse.apply("A block can only be reported for a job");
        }
        String body = readBody.apply(session);
        if (body == null) {
            return errorResponse.apply("Failed to read request body");
        }
        String category = JsonFieldExtractor.extractString(body, "category");
        if (!BlockReport.isValidCategory(category)) {
            return errorResponse.apply("Unknown block category: " + category
                    + "; expected one of " + BlockReport.CATEGORIES);
        }
        String detail = JsonFieldExtractor.extractString(body, "detail");
        registry.record(jobId, new BlockReport(category, detail, false));
        log.accept("Job " + jobId + " reported blocked (" + category + ")");
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                "{\"ok\":true}");
    }

    /**
     * Returns the block reported for a job, for the worker to read before it
     * runs enforcement.
     *
     * @param jobId the job identifier from the URL path, or {@code null}
     * @return {@code {"blocked":true,"category":...,"detail":...}} when a report
     *         exists, {@code {"blocked":false}} otherwise
     */
    public Response handleGet(String jobId) {
        BlockReport report = jobId == null ? null : registry.lookup(jobId).orElse(null);
        if (report == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"blocked\":false}");
        }
        String json = "{\"blocked\":true,\"category\":\""
                + JsonFieldExtractor.escapeJson(report.getCategory())
                + "\",\"detail\":\"" + JsonFieldExtractor.escapeJson(report.getDetail()) + "\"}";
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", json);
    }
}
