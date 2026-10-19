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

package io.flowtree.controller;

import io.flowtree.JsonFieldExtractor;

import java.time.Duration;
import java.time.Instant;

/**
 * A job the store still records as running, with the timestamps that say
 * whether it is alive and the derived liveness state that follows from them.
 *
 * <p>Carries the age arithmetic rather than leaving it to each caller: the
 * scanner, the API endpoint and the operator-facing status tool all ask the
 * same two questions, and a job that is "stuck" by one caller's arithmetic and
 * "running" by another's would be worse than either answer. The derived state
 * ({@link #RUNNING}/{@link #UNRESPONSIVE}) is computed here too so that the
 * controller stays the single source of truth for the derivation and the
 * operator tools present it rather than re-deriving a threshold of their own.</p>
 *
 * @param jobId        the job identifier
 * @param workstreamId the workstream the job belongs to
 * @param startedAt    when the job was recorded as started
 * @param heartbeatAt  the last heartbeat, or {@code null} if none yet
 * @param description  the job's human-readable description
 * @param node         the worker identity recorded for the job, or {@code null}
 */
public record ActiveJob(String jobId, String workstreamId, Instant startedAt,
                        Instant heartbeatAt, String description, String node) {

    /** Derived state of a job reporting heartbeats within the silence threshold. */
    public static final String RUNNING = "RUNNING";

    /** Derived state of a {@code STARTED} job silent longer than the silence threshold. */
    public static final String UNRESPONSIVE = "UNRESPONSIVE";

    /**
     * Returns how long the job has been running.
     *
     * @param now the reference instant
     * @return elapsed time since the job started, or {@link Duration#ZERO}
     *         when no start time was recorded
     */
    public Duration age(Instant now) {
        return startedAt == null ? Duration.ZERO : Duration.between(startedAt, now);
    }

    /**
     * Returns how long it has been since the job last reported itself alive,
     * falling back to the start time when it has not yet sent a heartbeat.
     *
     * <p>The fallback is what makes a job that died before its first heartbeat
     * detectable; without it such a job would look permanently fresh.</p>
     *
     * @param now the reference instant
     * @return elapsed time since the last liveness signal
     */
    public Duration sinceHeartbeat(Instant now) {
        Instant last = heartbeatAt != null ? heartbeatAt : startedAt;
        return last == null ? Duration.ZERO : Duration.between(last, now);
    }

    /**
     * Returns whether the job has been silent for longer than {@code threshold}.
     *
     * @param threshold the silence a job may accumulate before it counts as
     *                  stuck; non-positive disables the check
     * @param now       the reference instant
     * @return {@code true} when the job should be treated as stuck
     */
    public boolean isStale(Duration threshold, Instant now) {
        if (threshold == null || threshold.isNegative() || threshold.isZero()) {
            return false;
        }
        return sinceHeartbeat(now).compareTo(threshold) > 0;
    }

    /**
     * Returns the derived liveness state of this job: {@link #UNRESPONSIVE}
     * when it has been silent longer than {@code silentThreshold}, otherwise
     * {@link #RUNNING}.
     *
     * @param silentThreshold the silence a running job may accumulate before it
     *                        is labelled unresponsive; non-positive always
     *                        yields {@link #RUNNING}
     * @param now             the reference instant
     * @return {@link #RUNNING} or {@link #UNRESPONSIVE}
     */
    public String derivedState(Duration silentThreshold, Instant now) {
        return isStale(silentThreshold, now) ? UNRESPONSIVE : RUNNING;
    }

    /**
     * Renders this job as a JSON object for the API listing, without a derived
     * state label.
     *
     * @param now the reference instant for the derived ages
     * @return a JSON object carrying the identifiers and derived ages
     */
    public String toJson(Instant now) {
        return toJson(now, null);
    }

    /**
     * Renders this job as a JSON object for the API listing, labelling it with
     * its {@link #derivedState(Duration, Instant) derived state} when a silence
     * threshold is supplied.
     *
     * @param now             the reference instant for the derived ages
     * @param silentThreshold the silence threshold used to derive the state, or
     *                        {@code null} to omit the state label
     * @return a JSON object carrying the identifiers, derived ages, and state
     */
    public String toJson(Instant now, Duration silentThreshold) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"jobId\":\"").append(JsonFieldExtractor.escapeJson(jobId)).append("\"");
        json.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(workstreamId)).append("\"");
        if (startedAt != null) {
            json.append(",\"startedAt\":\"").append(startedAt).append("\"");
        }
        if (heartbeatAt != null) {
            json.append(",\"heartbeatAt\":\"").append(heartbeatAt).append("\"");
        }
        json.append(",\"ageSeconds\":").append(age(now).getSeconds());
        json.append(",\"sinceHeartbeatSeconds\":").append(sinceHeartbeat(now).getSeconds());
        if (node != null && !node.isEmpty()) {
            json.append(",\"node\":\"").append(JsonFieldExtractor.escapeJson(node)).append("\"");
        }
        if (silentThreshold != null) {
            json.append(",\"state\":\"").append(derivedState(silentThreshold, now)).append("\"");
        }
        if (description != null) {
            json.append(",\"description\":\"").append(JsonFieldExtractor.escapeJson(description)).append("\"");
        }
        return json.append("}").toString();
    }
}
