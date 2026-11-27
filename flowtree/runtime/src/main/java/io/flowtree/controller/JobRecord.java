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

import io.flowtree.jobs.JobCompletionEvent;

import java.time.Instant;

/**
 * A single consistent snapshot of one job's {@code job_timing} row, read in one
 * query by {@link JobStatsStore#getJobRecord(String)}.
 *
 * <p>A single-job lookup needs the job's event, its derived lifecycle state, the
 * workstream that owns it, its submission time and its recorded phase. Reading
 * those from separate store calls lets a lifecycle transition slip between the
 * reads, so a response could combine, say, a {@code STARTED} event with a label
 * computed from a row that had already completed. This record carries everything
 * that lookup needs from <em>one</em> read of the row, so every field describes
 * the same snapshot.</p>
 *
 * <p>The {@link #pending()} record is present only for a {@code SUBMITTED} row
 * and the {@link #active()} record only for a {@code STARTED} one; both are
 * {@code null} for a finished job, whose state is simply its terminal status.
 * Resolving the derived state and the workstream from whichever of these is
 * present (see {@link io.flowtree.api.StatsQueryHandler}) keeps the label and
 * the workstream drawn from the same row as the event.</p>
 *
 * @param event       the job's most recent event, as {@link JobStatsStore#getJob} returns it
 * @param pending     the pending record when the job is {@code SUBMITTED}, else {@code null}
 * @param active      the active record when the job is {@code STARTED}, else {@code null}
 * @param submittedAt when the controller accepted the job, or {@code null} when not recorded
 * @param phase       the lifecycle phase last recorded, or {@code null} when none
 *
 * @author Michael Murray
 * @see JobStatsStore#getJobRecord(String)
 */
public record JobRecord(JobCompletionEvent event, PendingJob pending, ActiveJob active,
                        Instant submittedAt, String phase) {
}
