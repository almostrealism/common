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

import java.util.ArrayList;
import java.util.List;

/**
 * Per-workstream activity summary for a rolling time window.
 *
 * <p>Populated by {@link JobStatsStore#getActiveWorkstreams(java.time.Instant)}.</p>
 */
public class WorkstreamActivity {
    /** The workstream identifier. */
    public String workstreamId;
    /** Total number of completed jobs in the window. */
    public int jobCount;
    /** Number of successful jobs in the window. */
    public int successCount;
    /** Number of failed jobs in the window. */
    public int failedCount;
    /** Number of cancelled jobs in the window. */
    public int cancelledCount;
    /**
     * Up to 5 recent jobs, each as {@code [jobId, slackMessageTs]}.
     * Only jobs with a stored Slack message timestamp are included.
     */
    public List<String[]> recentJobs = new ArrayList<>();
}
