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
import java.util.Map;

/**
 * Owns the controller-side writes to {@link JobStatsStore} across a job's
 * lifecycle, so that the controller — not a notifier — is the keeper of its own
 * job record.
 *
 * <p>Before this collaborator existed, a job's durable state was written as a
 * side effect of {@code SlackNotifier.onJobStarted} / {@code onJobCompleted}:
 * a workspace with no Slack notifier configured (or one with a stale workstream
 * map) got no row at all, so the heartbeat updates were no-ops, the stuck-job
 * scanner never saw the job, and the completion path fell back to
 * {@code started_at == completedAt}. The controller now records the three
 * transitions on its own path, regardless of whether any notifier is present:
 * {@link #recordSubmitted} at acceptance, {@link #recordStarted} when the
 * started event lands, and {@link #recordTerminal} at the single terminal
 * choke point ({@code FlowTreeApiEndpoint.completeJob}) — which every terminal
 * event travels, including the synthetic completions
 * {@link StuckJobScanner} routes through it.</p>
 *
 * <p>A separate class rather than inline endpoint code because
 * {@code FlowTreeApiEndpoint} already runs close to the file-length limit, and
 * because the lifecycle writes are a cohesive responsibility of their own. A
 * {@code null} store is tolerated silently (a controller configured without job
 * stats is a supported configuration), so callers never need their own null
 * check.</p>
 *
 * @author Michael Murray
 */
public final class JobLifecycleRecorder {

    /** The backing store; {@code null} disables every record call. */
    private final JobStatsStore statsStore;

    /**
     * Creates a recorder writing through {@code statsStore}.
     *
     * @param statsStore the store to write to, or {@code null} to disable
     */
    public JobLifecycleRecorder(JobStatsStore statsStore) {
        this.statsStore = statsStore;
    }

    /**
     * Records that the controller accepted a job, before any worker has started
     * it. Creates the {@code SUBMITTED} row the instant the controller accepts
     * the job, so a submitted-but-never-started job is representable.
     *
     * @param jobId          the job identifier
     * @param workstreamId   the workstream the job belongs to
     * @param description    human-readable description, or {@code null}
     * @param submittedBy    the submitter identity, or {@code null} when unknown
     * @param requiredLabels the node-routing labels the job was submitted with
     * @param submittedAt    the moment the controller accepted the job
     */
    public void recordSubmitted(String jobId, String workstreamId, String description,
                                String submittedBy, Map<String, String> requiredLabels,
                                Instant submittedAt) {
        if (statsStore == null) return;
        statsStore.recordJobSubmitted(jobId, workstreamId, description, submittedBy,
                requiredLabels, submittedAt);
    }

    /**
     * Records that a job has started on a worker, moving an existing
     * {@code SUBMITTED} row to {@code STARTED} (or inserting a fresh row when
     * none exists) and recording the worker {@code node} identity.
     *
     * @param jobId        the job identifier
     * @param workstreamId the workstream the job belongs to
     * @param description  human-readable description, or {@code null}
     * @param node         the worker identity, or {@code null} when unknown
     * @param startedAt    the start timestamp
     */
    public void recordStarted(String jobId, String workstreamId, String description,
                              String node, Instant startedAt) {
        if (statsStore == null) return;
        statsStore.recordJobStarted(jobId, workstreamId, description, node, startedAt);
    }

    /**
     * Records a job's terminal state from its completion event. Delegates to
     * {@link JobStatsStore#recordJobCompleted(String, JobCompletionEvent)} so a
     * scanner-failed job and an ordinary completion both update the row with or
     * without a notifier present.
     *
     * @param workstreamId the workstream the job belongs to
     * @param event        the terminal event
     */
    public void recordTerminal(String workstreamId, JobCompletionEvent event) {
        if (statsStore == null || event == null) return;
        statsStore.recordJobCompleted(workstreamId, event);
    }
}
