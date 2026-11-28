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

import org.almostrealism.io.ConsoleFeatures;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the {@link WindowStats} retrospective over an arbitrary half-open
 * window from the {@code job_timing}, cost and {@code job_token} tables.
 *
 * <p>The windowed counterpart of {@link WeeklyStatsAggregator}: it reads through
 * a {@link Connection} {@link JobStatsStore} owns and recreates whenever the
 * connection changes. It applies the plan's §2.4 counting rules — a job's
 * outcome, spend, tokens and turns land in the window containing its
 * {@code completed_at}; its agent time is the <em>overlap</em> of its run with
 * the window, so per-window busy time sums back to the total — and classifies
 * each finished job's {@link JobKind} from the trigger reason and phase on its
 * row.</p>
 *
 * @author Michael Murray
 * @see JobStatsStore
 * @see WindowStats
 * @see WindowQuery
 */
public class WindowStatsAggregator implements ConsoleFeatures {

    /** The connection to the backing HSQLDB database, owned by {@link JobStatsStore}. */
    private final Connection connection;

    /**
     * Creates an aggregator that reads through {@code connection}.
     *
     * @param connection the open connection to the job-timing database
     */
    public WindowStatsAggregator(Connection connection) {
        this.connection = connection;
    }

    /**
     * Computes the retrospective for {@code query}.
     *
     * @param query         the window and optional workstream filter
     * @param now           the reference instant for running-job time and the
     *                      unresponsive label
     * @param lostThreshold the wait past which a still-submitted job counts as
     *                      {@link PendingJob#LOST}
     * @return the computed stats, with zeroed measures when nothing matched or
     *         the query failed
     */
    public WindowStats forWindow(WindowQuery query, Instant now, Duration lostThreshold) {
        WindowStats stats = new WindowStats();
        stats.from = query.from();
        stats.to = query.to();
        if (connection == null) return stats;

        foldFinished(query, stats);
        stats.costByRunner = sumCost(query, "job_runner_cost", "runner");
        stats.costByModel = sumCost(query, "job_model_cost", "model");
        foldTokens(query, stats);
        foldSubmitted(query, now, lostThreshold, stats);
        stats.started = countStarted(query);
        foldRunning(query, now, stats);
        foldOverlap(query, now, stats);
        return stats;
    }

    /**
     * Folds the jobs finished in the window into their outcome counts, spend,
     * turns, per-kind and per-workstream breakdowns.
     */
    private void foldFinished(WindowQuery query, WindowStats stats) {
        String sql = "SELECT status, cost_usd, num_turns, workstream_id, "
            + "trigger_reason, phase FROM job_timing "
            + "WHERE completed_at >= ? AND completed_at < ?"
            + (query.workstreamId() == null ? "" : " AND workstream_id = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindWindow(ps, query);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.finished++;
                    String status = rs.getString("status");
                    countStatus(stats, status);
                    double cost = rs.getDouble("cost_usd");
                    stats.totalCostUsd += cost;
                    stats.totalTurns += rs.getInt("num_turns");
                    addLong(stats.finishedByWorkstream, rs.getString("workstream_id"), 1L);
                    String kind = JobKind.classify(
                            rs.getString("trigger_reason"), rs.getString("phase")).name();
                    addLong(stats.countByKind, kind, 1L);
                    addDouble(stats.costByKind, kind, cost);
                }
            }
        } catch (SQLException e) {
            warn("Failed to fold finished jobs: " + e.getMessage());
        }
    }

    /** Increments the per-status outcome counter matching {@code status}. */
    private static void countStatus(WindowStats stats, String status) {
        if (status == null) return;
        switch (status) {
            case "SUCCESS": stats.success++; break;
            case "FAILED": stats.failed++; break;
            case "DEGRADED": stats.degraded++; break;
            case "CANCELLED": stats.cancelled++; break;
            case "BLOCKED": stats.blocked++; break;
            default: break;
        }
    }

    /**
     * Sums cost from {@code table} keyed by {@code keyColumn} for jobs finished
     * in the window, mirroring {@link WeeklyStatsAggregator}'s cost joins but
     * over {@code completed_at} rather than {@code started_at}.
     */
    private Map<String, Double> sumCost(WindowQuery query, String table, String keyColumn) {
        Map<String, Double> result = new LinkedHashMap<>();
        String sql = "SELECT c." + keyColumn + " AS k, COALESCE(SUM(c.cost_usd), 0) AS v "
            + "FROM " + table + " c JOIN job_timing t ON c.job_id = t.job_id "
            + "WHERE t.completed_at >= ? AND t.completed_at < ?"
            + (query.workstreamId() == null ? "" : " AND t.workstream_id = ?")
            + " GROUP BY c." + keyColumn + " ORDER BY v DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindWindow(ps, query);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("k"), rs.getDouble("v"));
                }
            }
        } catch (SQLException e) {
            warn("Failed to sum cost from " + table + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Sums token counts for jobs finished in the window and records how many of
     * them had a token row (the coverage numerator). Leaves the token measures
     * {@code null} when no finished job had a row, so "not captured" stays
     * distinct from a genuine zero.
     */
    private void foldTokens(WindowQuery query, WindowStats stats) {
        String sql = "SELECT COUNT(*) AS n, "
            + "COALESCE(SUM(k.input_tokens), 0) AS it, "
            + "COALESCE(SUM(k.output_tokens), 0) AS ot, "
            + "COALESCE(SUM(k.cache_read_tokens), 0) AS crt, "
            + "COALESCE(SUM(k.cache_write_tokens), 0) AS cwt "
            + "FROM job_token k JOIN job_timing t ON k.job_id = t.job_id "
            + "WHERE t.completed_at >= ? AND t.completed_at < ?"
            + (query.workstreamId() == null ? "" : " AND t.workstream_id = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindWindow(ps, query);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int n = rs.getInt("n");
                    stats.finishedWithTokens = n;
                    if (n > 0) {
                        stats.inputTokens = rs.getLong("it");
                        stats.outputTokens = rs.getLong("ot");
                        stats.cacheReadTokens = rs.getLong("crt");
                        stats.cacheWriteTokens = rs.getLong("cwt");
                    }
                }
            }
        } catch (SQLException e) {
            warn("Failed to fold tokens: " + e.getMessage());
        }
    }

    /**
     * Counts jobs whose submission landed in the window and, among those still
     * {@code SUBMITTED} and unstarted, how many have waited past the lost
     * threshold.
     */
    private void foldSubmitted(WindowQuery query, Instant now, Duration lostThreshold,
                               WindowStats stats) {
        String sql = "SELECT status, submitted_at, completed_at FROM job_timing "
            + "WHERE submitted_at >= ? AND submitted_at < ?"
            + (query.workstreamId() == null ? "" : " AND workstream_id = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindWindow(ps, query);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.submitted++;
                    Timestamp submitted = rs.getTimestamp("submitted_at");
                    Timestamp completed = rs.getTimestamp("completed_at");
                    if ("SUBMITTED".equals(rs.getString("status")) && completed == null) {
                        PendingJob pending = new PendingJob(null, null,
                                submitted == null ? null : submitted.toInstant(),
                                null, null, null);
                        if (pending.isLost(lostThreshold, now)) {
                            stats.lost++;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            warn("Failed to fold submitted jobs: " + e.getMessage());
        }
    }

    /**
     * Counts jobs that began running in the window — a {@code started_at} in
     * range on a row that is no longer merely {@code SUBMITTED} (the placeholder
     * {@code started_at} a submitted row carries is excluded).
     */
    private int countStarted(WindowQuery query) {
        String sql = "SELECT COUNT(*) FROM job_timing "
            + "WHERE started_at >= ? AND started_at < ? AND status <> 'SUBMITTED'"
            + (query.workstreamId() == null ? "" : " AND workstream_id = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindWindow(ps, query);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            warn("Failed to count started jobs: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Counts jobs still running at the window end and, among them, those silent
     * past the silence threshold (the {@code UNRESPONSIVE} label).
     */
    private void foldRunning(WindowQuery query, Instant now, WindowStats stats) {
        String sql = "SELECT started_at, heartbeat_at FROM job_timing "
            + "WHERE status = 'STARTED' AND completed_at IS NULL AND started_at < ?"
            + (query.workstreamId() == null ? "" : " AND workstream_id = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(query.to()));
            if (query.workstreamId() != null) ps.setString(2, query.workstreamId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.running++;
                    Timestamp started = rs.getTimestamp("started_at");
                    Timestamp beat = rs.getTimestamp("heartbeat_at");
                    if (ActiveJob.isStale(started == null ? null : started.toInstant(),
                            beat == null ? null : beat.toInstant(),
                            query.silentThreshold(), now)) {
                        stats.unresponsive++;
                    }
                }
            }
        } catch (SQLException e) {
            warn("Failed to fold running jobs: " + e.getMessage());
        }
    }

    /**
     * Sums each job's run-overlap with the window into the total agent
     * wall-clock and the per-node busy breakdown, applying
     * {@link JobInterval#end(Instant, Duration)}'s running-job rule.
     */
    private void foldOverlap(WindowQuery query, Instant now, WindowStats stats) {
        String sql = "SELECT job_id, workstream_id, status, started_at, completed_at, "
            + "heartbeat_at, cost_usd, node FROM job_timing "
            + "WHERE status <> 'SUBMITTED' AND started_at < ? "
            + "AND (completed_at IS NULL OR completed_at >= ?)"
            + (query.workstreamId() == null ? "" : " AND workstream_id = ?");
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            // Overlap selects jobs started before the window end and not completed
            // before its start, so the bind order is (to, from), not bindWindow's.
            ps.setTimestamp(1, Timestamp.from(query.to()));
            ps.setTimestamp(2, Timestamp.from(query.from()));
            if (query.workstreamId() != null) ps.setString(3, query.workstreamId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp started = rs.getTimestamp("started_at");
                    if (started == null) continue;
                    Timestamp completed = rs.getTimestamp("completed_at");
                    Timestamp beat = rs.getTimestamp("heartbeat_at");
                    JobInterval interval = new JobInterval(
                            rs.getString("job_id"), rs.getString("workstream_id"),
                            rs.getString("status"), started.toInstant(),
                            completed == null ? null : completed.toInstant(),
                            beat == null ? null : beat.toInstant(), rs.getDouble("cost_usd"));
                    long overlap = overlapMs(interval, query, now);
                    if (overlap <= 0) continue;
                    stats.agentWallClockMs += overlap;
                    String node = rs.getString("node");
                    if (node != null && !node.isEmpty()) {
                        addLong(stats.busyMsByNode, node, overlap);
                    }
                }
            }
        } catch (SQLException e) {
            warn("Failed to fold overlap: " + e.getMessage());
        }
    }

    /**
     * Returns the milliseconds of {@code interval}'s run that fall inside the
     * query window, clamping the run end with the running-job rule.
     */
    private static long overlapMs(JobInterval interval, WindowQuery query, Instant now) {
        Instant runStart = interval.startedAt();
        Instant runEnd = interval.end(now, query.silentThreshold());
        Instant lo = runStart.isAfter(query.from()) ? runStart : query.from();
        Instant hi = runEnd.isBefore(query.to()) ? runEnd : query.to();
        return hi.isAfter(lo) ? Duration.between(lo, hi).toMillis() : 0L;
    }

    /** Binds the window start, end and optional workstream parameters. */
    private static void bindWindow(PreparedStatement ps, WindowQuery query) throws SQLException {
        ps.setTimestamp(1, Timestamp.from(query.from()));
        ps.setTimestamp(2, Timestamp.from(query.to()));
        if (query.workstreamId() != null) {
            ps.setString(3, query.workstreamId());
        }
    }

    /** Adds {@code delta} to the entry for {@code key}, skipping a null key. */
    private static void addLong(Map<String, Long> map, String key, long delta) {
        if (key == null) return;
        map.merge(key, delta, Long::sum);
    }

    /** Adds {@code delta} to the entry for {@code key}, skipping a null key. */
    private static void addDouble(Map<String, Double> map, String key, double delta) {
        if (key == null) return;
        map.merge(key, delta, Double::sum);
    }
}
