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

package io.flowtree.jobs;

import io.flowtree.JsonFieldExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A structured "blocked" report produced when a coding agent cannot proceed and
 * declares so through the {@code job_report_blocked} ar-manager tool rather than
 * exiting with free text. It names <em>why</em> the agent stopped from a fixed
 * {@linkplain #CATEGORIES category set} and carries a free-text {@code detail}.
 *
 * <p>A report is the agent's claim. The harness re-verifies a claim where it can:
 * a {@link #GUARDRAIL} block asserts that a guardrail or hook denied the agent,
 * which the worker confirms against the denials actually recorded in the session
 * (see {@link #fetch(String, boolean)}). A claim the harness cannot corroborate
 * is kept but marked {@linkplain #isVerified() unverified} so a reader can tell a
 * confirmed block from an asserted one. Claims in the other categories carry no
 * such invariant and are always considered verified.</p>
 *
 * <p>The report drives three outcomes on the worker: {@link EnforcementRunner}
 * does not retry a blocked job, {@link CodingAgentJobEvent} reports it with the
 * terminal {@link JobCompletionEvent.Status#BLOCKED} status, and the category
 * and detail travel on the completion event so they surface in
 * {@code workstream_get_job}.</p>
 *
 * @author Michael Murray
 */
public final class BlockReport {

    /** A guardrail or hook denied an action the agent needed to take. */
    public static final String GUARDRAIL = "guardrail";
    /** A resource the task requires (a library, a model file, a credential) is absent. */
    public static final String MISSING_RESOURCE = "missing_resource";
    /** The task cannot be acted on as stated without a clarifying decision. */
    public static final String AMBIGUOUS_TASK = "ambiguous_task";
    /** The requested change falls outside what this branch should carry. */
    public static final String OUT_OF_SCOPE = "out_of_scope";
    /** The work already exists (typically already on the base branch). */
    public static final String ALREADY_DONE = "already_done";

    /**
     * The complete, fixed set of block categories. A report whose category is
     * outside this set is rejected at the controller endpoint, so every report
     * that reaches the worker names one of these.
     */
    public static final Set<String> CATEGORIES = Set.of(
            GUARDRAIL, MISSING_RESOURCE, AMBIGUOUS_TASK, OUT_OF_SCOPE, ALREADY_DONE);

    /** The block category; always a member of {@link #CATEGORIES}. */
    private final String category;
    /** Free-text explanation the agent supplied, never {@code null}. */
    private final String detail;
    /** Whether the harness corroborated the claim; see the class javadoc. */
    private final boolean verified;

    /**
     * Creates a block report.
     *
     * @param category the block category; must be a member of {@link #CATEGORIES}
     * @param detail   the agent's free-text explanation; {@code null} becomes empty
     * @param verified whether the harness corroborated the claim
     * @throws IllegalArgumentException if {@code category} is not a known category
     */
    public BlockReport(String category, String detail, boolean verified) {
        if (!isValidCategory(category)) {
            throw new IllegalArgumentException("Unknown block category: " + category);
        }
        this.category = category;
        this.detail = detail == null ? "" : detail;
        this.verified = verified;
    }

    /**
     * Returns whether {@code category} is one of the known {@link #CATEGORIES}.
     *
     * @param category the candidate category; {@code null} is not valid
     * @return {@code true} when the category is recognised
     */
    public static boolean isValidCategory(String category) {
        return category != null && CATEGORIES.contains(category);
    }

    /** Returns the block category; always a member of {@link #CATEGORIES}. */
    public String getCategory() { return category; }

    /** Returns the agent's free-text explanation; never {@code null}. */
    public String getDetail() { return detail; }

    /**
     * Returns whether the harness corroborated this claim. Always {@code true}
     * outside {@link #GUARDRAIL}; for a guardrail block, {@code true} only when a
     * tool or permission denial was recorded in the session.
     *
     * @return {@code true} when the claim is verified
     */
    public boolean isVerified() { return verified; }

    /**
     * Returns a human-readable one-line summary suitable for the completion
     * event's error-message slot, naming the category, the verification state
     * when a guardrail claim could not be confirmed, and the detail.
     *
     * @return the summary, never {@code null}
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("Agent reported blocked (").append(category);
        if (!verified) {
            sb.append(", unverified");
        }
        sb.append(")");
        if (!detail.isEmpty()) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }

    /**
     * Fetches the block report the agent recorded for a job, computing its
     * verification state from the session's recorded denials.
     *
     * <p>Reads {@code GET <jobBaseUrl>/blocked} on the controller, where the
     * {@code job_report_blocked} tool posted the claim. A {@link #GUARDRAIL}
     * claim is marked verified only when {@code denialRecorded} is {@code true}
     * — i.e. the session actually recorded a tool or permission denial; a
     * guardrail claim with no such denial is kept but marked unverified. Claims
     * in the other categories are always verified.</p>
     *
     * <p>Fire-and-forget: any transport error, a missing record, or a malformed
     * response yields {@code null} (the job was not reported blocked), so a
     * controller hiccup never turns a normal job into a blocked one.</p>
     *
     * @param jobBaseUrl     the job's controller URL
     *                       ({@code .../api/workstreams/{id}/jobs/{jobId}}), or
     *                       {@code null}/empty when no controller is configured
     * @param denialRecorded whether the session recorded a tool or permission denial
     * @return the fetched report, or {@code null} when the job was not reported blocked
     */
    public static BlockReport fetch(String jobBaseUrl, boolean denialRecorded) {
        if (jobBaseUrl == null || jobBaseUrl.isEmpty()) {
            return null;
        }
        String body = get(jobBaseUrl.endsWith("/")
                ? jobBaseUrl + "blocked" : jobBaseUrl + "/blocked");
        if (body == null || !JsonFieldExtractor.extractBoolean(body, "blocked")) {
            return null;
        }
        String category = JsonFieldExtractor.extractString(body, "category");
        if (!isValidCategory(category)) {
            return null;
        }
        String detail = JsonFieldExtractor.extractString(body, "detail");
        boolean verified = !GUARDRAIL.equals(category) || denialRecorded;
        return new BlockReport(category, detail, verified);
    }

    /**
     * Issues a short GET and returns the response body, or {@code null} on any
     * non-200 status or transport failure.
     *
     * @param url the absolute URL to read
     * @return the response body, or {@code null}
     */
    private static String get(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
