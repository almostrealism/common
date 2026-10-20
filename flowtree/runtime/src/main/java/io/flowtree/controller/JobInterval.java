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

import java.time.Duration;
import java.time.Instant;

/**
 * The span of time a job occupied an agent, as recorded in the stats store:
 * from its start to its completion, or, while it is still running, to its most
 * recent sign of life.
 *
 * <p>A running job's span ends at {@code now} only while the job is alive. Once
 * it has been silent longer than the silence threshold it ends at its last
 * liveness signal instead (the last heartbeat, or the start when it never sent
 * one), the same rule {@link ActiveJob#isStale(Instant, Instant, Duration, Instant)}
 * applies to label it {@code UNRESPONSIVE}. Without that, a worker that died mid-job would
 * keep adding agent time until the orphan cleanup removed its row.</p>
 *
 * @param jobId        the job identifier
 * @param workstreamId the workstream the job belongs to
 * @param status       the recorded status ({@code STARTED} while running,
 *                     otherwise a terminal status)
 * @param startedAt    when the job started
 * @param completedAt  when the job finished, or {@code null} while running
 * @param heartbeatAt  the last heartbeat, or {@code null} if none was recorded
 * @param costUsd      the job's recorded cost in USD, zero when unknown
 */
public record JobInterval(String jobId, String workstreamId, String status,
                          Instant startedAt, Instant completedAt,
                          Instant heartbeatAt, double costUsd) {

    /**
     * Returns whether the job is still recorded as running.
     *
     * @return {@code true} when the job has no completion yet
     */
    public boolean isRunning() {
        return completedAt == null;
    }

    /**
     * Returns where the job's span ends.
     *
     * @param now             the reference instant
     * @param silentThreshold the silence after which a running job stops
     *                        accruing time; non-positive means it never stops
     * @return the end of the span, never before {@link #startedAt()}
     */
    public Instant end(Instant now, Duration silentThreshold) {
        Instant end;
        if (completedAt != null) {
            end = completedAt;
        } else {
            end = ActiveJob.isStale(startedAt, heartbeatAt, silentThreshold, now)
                    ? (heartbeatAt != null ? heartbeatAt : startedAt)
                    : now;
        }
        return end.isBefore(startedAt) ? startedAt : end;
    }
}
