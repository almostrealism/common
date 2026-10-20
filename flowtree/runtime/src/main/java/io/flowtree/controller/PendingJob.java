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
 * A job the controller accepted but which has not yet started on any worker — a
 * {@code SUBMITTED} row that has not moved to {@code STARTED}.
 *
 * <p>This is the record that makes the "lost" job class visible. A job that is
 * submitted and never picked up used to leave no trace at all; now it is a
 * pending row whose {@link #isLost(Duration, Instant) age since submission}
 * separates a job still waiting for a worker from one that has almost certainly
 * been dropped. The derivation lives here, on the type that holds the
 * submission time, so the scanner and the operator tools agree on it rather
 * than each re-deriving a threshold.</p>
 *
 * @param jobId          the job identifier
 * @param workstreamId   the workstream the job belongs to
 * @param submittedAt    when the controller accepted the job
 * @param submittedBy    the submitter identity, or {@code null} when unknown
 * @param requiredLabels the serialized node-routing labels, or {@code null}
 * @param description    the job's human-readable description, or {@code null}
 */
public record PendingJob(String jobId, String workstreamId, Instant submittedAt,
                         String submittedBy, String requiredLabels, String description) {

    /** Derived state of a submitted job still within the lost threshold. */
    public static final String SUBMITTED = "SUBMITTED";

    /** Derived state of a submitted job that has waited past the lost threshold. */
    public static final String LOST = "LOST";

    /**
     * Returns how long the job has waited since it was submitted.
     *
     * @param now the reference instant
     * @return elapsed time since submission, or {@link Duration#ZERO} when no
     *         submission time was recorded
     */
    public Duration age(Instant now) {
        return submittedAt == null ? Duration.ZERO : Duration.between(submittedAt, now);
    }

    /**
     * Returns whether the job has waited to start longer than
     * {@code lostThreshold} — the signal that it was accepted but never picked
     * up by a worker.
     *
     * @param lostThreshold the wait a submitted job may accumulate before it
     *                      counts as lost; non-positive disables the check
     * @param now           the reference instant
     * @return {@code true} when the job should be treated as lost
     */
    public boolean isLost(Duration lostThreshold, Instant now) {
        if (lostThreshold == null || lostThreshold.isNegative() || lostThreshold.isZero()) {
            return false;
        }
        return age(now).compareTo(lostThreshold) > 0;
    }

    /**
     * Returns the derived state of this job: {@link #LOST} when it has waited
     * past {@code lostThreshold}, otherwise {@link #SUBMITTED}.
     *
     * @param lostThreshold the lost threshold
     * @param now           the reference instant
     * @return {@link #SUBMITTED} or {@link #LOST}
     */
    public String derivedState(Duration lostThreshold, Instant now) {
        return isLost(lostThreshold, now) ? LOST : SUBMITTED;
    }

    /**
     * Renders this job as a JSON object for the pending/lost API listing,
     * labelling it with its {@link #derivedState(Duration, Instant) derived
     * state} when a lost threshold is supplied.
     *
     * @param now           the reference instant for the derived age and state
     * @param lostThreshold the lost threshold used to derive the state, or
     *                      {@code null} to omit the state label
     * @return a JSON object carrying the identifiers, submission time, and state
     */
    public String toJson(Instant now, Duration lostThreshold) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"jobId\":\"").append(JsonFieldExtractor.escapeJson(jobId)).append("\"");
        json.append(",\"workstreamId\":\"").append(JsonFieldExtractor.escapeJson(workstreamId)).append("\"");
        if (submittedAt != null) {
            json.append(",\"submittedAt\":\"").append(submittedAt).append("\"");
        }
        json.append(",\"ageSeconds\":").append(age(now).getSeconds());
        if (submittedBy != null && !submittedBy.isEmpty()) {
            json.append(",\"submittedBy\":\"").append(JsonFieldExtractor.escapeJson(submittedBy)).append("\"");
        }
        if (requiredLabels != null && !requiredLabels.isEmpty()) {
            json.append(",\"requiredLabels\":\"").append(JsonFieldExtractor.escapeJson(requiredLabels)).append("\"");
        }
        if (lostThreshold != null) {
            json.append(",\"state\":\"").append(derivedState(lostThreshold, now)).append("\"");
        }
        if (description != null) {
            json.append(",\"description\":\"").append(JsonFieldExtractor.escapeJson(description)).append("\"");
        }
        return json.append("}").toString();
    }
}
