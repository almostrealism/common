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

import io.flowtree.jobs.HarnessStatusReporter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregated weekly job statistics.
 *
 * <p>Populated by {@link JobStatsStore#getWeeklyStats(String, LocalDate)} and
 * {@link JobStatsStore#getWeeklyStatsByWorkstream(LocalDate)}, and rendered for
 * Slack via {@link #toSlackMrkdwn(String, LocalDate)}.</p>
 */
public class WeeklyStats {
    /** Total number of completed jobs. */
    public int jobCount;
    /** Number of successful jobs. */
    public int successCount;
    /** Number of failed jobs. */
    public int failedCount;
    /** Number of cancelled jobs. */
    public int cancelledCount;
    /** Total wall-clock time across all jobs in milliseconds. */
    public long totalWallClockMs;
    /** Total Claude Code duration across all jobs in milliseconds. */
    public long totalDurationMs;
    /** Total cost in USD across all jobs. */
    public double totalCostUsd;
    /** Total number of agentic turns across all jobs. */
    public int totalTurns;
    /**
     * Per-runner USD cost breakdown for the window, keyed by runner name and
     * ordered by cost descending. Empty when no per-runner data is recorded.
     */
    public Map<String, Double> costByRunner = Collections.emptyMap();
    /**
     * Per-model USD cost breakdown for the window, keyed by provider/model
     * identifier and ordered by cost descending. Empty when no per-model
     * data is recorded.
     */
    public Map<String, Double> costByModel = Collections.emptyMap();

    /**
     * Formats this week's statistics as a Slack mrkdwn block for display.
     *
     * <p>Outputs a header line with the label and date range, followed by
     * total wall-clock time, job counts by status, total USD cost (with
     * per-runner and per-model breakdowns), and total agent turns.</p>
     *
     * @param label     display label for the block (e.g., "This Week")
     * @param weekStart the Monday that begins this week
     * @return a formatted mrkdwn string ready for posting to Slack
     */
    public String toSlackMrkdwn(String label, LocalDate weekStart) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);
        LocalDate weekEnd = weekStart.plusDays(6);

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(label).append("* (");
        sb.append(weekStart.format(fmt)).append(" — ").append(weekEnd.format(fmt)).append(")\n");
        sb.append("  :clock1: Total time: ").append(HarnessStatusReporter.formatDuration(totalWallClockMs)).append("\n");
        sb.append("  :hammer: Jobs: ").append(jobCount);
        sb.append(" (:white_check_mark: ").append(successCount);
        sb.append("  :x: ").append(failedCount);
        sb.append("  :no_entry_sign: ").append(cancelledCount).append(")\n");
        sb.append("  :moneybag: Cost: $").append(String.format("%.2f", totalCostUsd));
        sb.append(JobStatsStore.formatCostBreakdown(costByRunner));
        sb.append(JobStatsStore.formatCostBreakdown(costByModel));
        sb.append("\n");
        sb.append("  :speech_balloon: Turns: ").append(String.format("%,d", totalTurns)).append("\n");
        return sb.toString();
    }
}
