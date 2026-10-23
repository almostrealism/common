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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes weekly {@link WeeklyStats} rollups from the {@code job_timing} table
 * and the per-runner and per-model cost tables.
 *
 * <p>This is the read/aggregation counterpart to {@link JobLifecycleRecorder}'s
 * lifecycle writes: a cohesive slice of {@link JobStatsStore}'s database
 * interaction extracted into its own collaborator. {@code JobStatsStore} owns
 * the connection lifecycle and the row-level records; the weekly rollups — a
 * distinct reporting concern with their own window and cost-join SQL — live
 * here. The store delegates {@link JobStatsStore#getWeeklyStats(String,
 * LocalDate)} and {@link JobStatsStore#getWeeklyStatsByWorkstream(LocalDate)} to
 * this class, which it recreates whenever its connection changes. Every query
 * excludes {@code STARTED} and {@code SUBMITTED} rows so a week reflects only
 * work that actually ran.</p>
 *
 * @author Michael Murray
 * @see JobStatsStore
 * @see WeeklyStats
 */
public class WeeklyStatsAggregator implements ConsoleFeatures {

    /** The connection to the backing HSQLDB database, owned by {@link JobStatsStore}. */
    private final Connection connection;

    /**
     * Creates an aggregator that reads through {@code connection}.
     *
     * @param connection the open connection to the job-timing database
     */
    public WeeklyStatsAggregator(Connection connection) {
        this.connection = connection;
    }

    /**
     * Returns aggregated statistics for the given week, optionally filtered to a
     * single workstream.
     *
     * @param workstreamId the workstream to filter by, or {@code null} for all
     * @param weekStart    the Monday starting the week
     * @return the aggregated stats, or an empty {@link WeeklyStats} when no
     *         matching jobs ran or the query fails
     */
    public WeeklyStats forWeek(String workstreamId, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(7);
        Instant startInstant = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endInstant = weekEnd.atStartOfDay(ZoneOffset.UTC).toInstant();

        String sql = "SELECT "
            + "COUNT(*) AS job_count, "
            + "COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count, "
            + "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count, "
            + "COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_count, "
            + "COALESCE(SUM(wall_clock_ms), 0) AS total_wall_clock_ms, "
            + "COALESCE(SUM(duration_ms), 0) AS total_duration_ms, "
            + "COALESCE(SUM(cost_usd), 0) AS total_cost_usd, "
            + "COALESCE(SUM(num_turns), 0) AS total_turns "
            + "FROM job_timing "
            + "WHERE started_at >= ? AND started_at < ? AND status NOT IN ('STARTED', 'SUBMITTED')";

        if (workstreamId != null) {
            sql += " AND workstream_id = ?";
        }

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(startInstant));
            ps.setTimestamp(2, Timestamp.from(endInstant));
            if (workstreamId != null) {
                ps.setString(3, workstreamId);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    WeeklyStats stats = new WeeklyStats();
                    stats.jobCount = rs.getInt("job_count");
                    stats.successCount = rs.getInt("success_count");
                    stats.failedCount = rs.getInt("failed_count");
                    stats.cancelledCount = rs.getInt("cancelled_count");
                    stats.totalWallClockMs = rs.getLong("total_wall_clock_ms");
                    stats.totalDurationMs = rs.getLong("total_duration_ms");
                    stats.totalCostUsd = rs.getDouble("total_cost_usd");
                    stats.totalTurns = rs.getInt("total_turns");
                    stats.costByRunner = queryCostByRunner(workstreamId, startInstant, endInstant);
                    stats.costByModel = queryCostByModel(workstreamId, startInstant, endInstant);
                    return stats;
                }
            }
        } catch (SQLException e) {
            warn("Failed to query weekly stats: " + e.getMessage());
        }

        return new WeeklyStats();
    }

    /**
     * Returns per-workstream statistics for the given week.
     *
     * @param weekStart the Monday starting the week
     * @return map of workstream ID to stats
     */
    public Map<String, WeeklyStats> byWorkstream(LocalDate weekStart) {
        Map<String, WeeklyStats> result = new LinkedHashMap<>();

        LocalDate weekEnd = weekStart.plusDays(7);
        Instant startInstant = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endInstant = weekEnd.atStartOfDay(ZoneOffset.UTC).toInstant();

        String sql = "SELECT workstream_id, "
            + "COUNT(*) AS job_count, "
            + "COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count, "
            + "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count, "
            + "COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_count, "
            + "COALESCE(SUM(wall_clock_ms), 0) AS total_wall_clock_ms, "
            + "COALESCE(SUM(duration_ms), 0) AS total_duration_ms, "
            + "COALESCE(SUM(cost_usd), 0) AS total_cost_usd, "
            + "COALESCE(SUM(num_turns), 0) AS total_turns "
            + "FROM job_timing "
            + "WHERE started_at >= ? AND started_at < ? AND status NOT IN ('STARTED', 'SUBMITTED') "
            + "GROUP BY workstream_id";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(startInstant));
            ps.setTimestamp(2, Timestamp.from(endInstant));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WeeklyStats stats = new WeeklyStats();
                    stats.jobCount = rs.getInt("job_count");
                    stats.successCount = rs.getInt("success_count");
                    stats.failedCount = rs.getInt("failed_count");
                    stats.cancelledCount = rs.getInt("cancelled_count");
                    stats.totalWallClockMs = rs.getLong("total_wall_clock_ms");
                    stats.totalDurationMs = rs.getLong("total_duration_ms");
                    stats.totalCostUsd = rs.getDouble("total_cost_usd");
                    stats.totalTurns = rs.getInt("total_turns");
                    result.put(rs.getString("workstream_id"), stats);
                }
            }
        } catch (SQLException e) {
            warn("Failed to query weekly stats by workstream: " + e.getMessage());
        }

        // Populate per-runner cost for every workstream in one grouped query
        Map<String, Map<String, Double>> runnerCosts =
                queryCostByRunnerPerWorkstream(startInstant, endInstant);
        for (Map.Entry<String, WeeklyStats> entry : result.entrySet()) {
            Map<String, Double> runners = runnerCosts.get(entry.getKey());
            if (runners != null && !runners.isEmpty()) {
                entry.getValue().costByRunner = runners;
            }
        }

        // Populate per-model cost for every workstream in one grouped query
        Map<String, Map<String, Double>> modelCosts =
                queryCostByModelPerWorkstream(startInstant, endInstant);
        for (Map.Entry<String, WeeklyStats> entry : result.entrySet()) {
            Map<String, Double> models = modelCosts.get(entry.getKey());
            if (models != null && !models.isEmpty()) {
                entry.getValue().costByModel = models;
            }
        }

        return result;
    }

    /**
     * Sums per-runner cost over the given window, optionally filtered to a
     * single workstream.
     *
     * @param workstreamId the workstream to filter by, or {@code null} for all
     * @param startInstant inclusive window start
     * @param endInstant   exclusive window end
     * @return map of runner name to summed USD cost, ordered by cost descending
     */
    private Map<String, Double> queryCostByRunner(String workstreamId,
                                                  Instant startInstant, Instant endInstant) {
        return queryCostFromTable(workstreamId, startInstant, endInstant,
                "job_runner_cost", "rc", "runner", "runner_cost");
    }

    /**
     * Sums per-runner cost grouped by workstream over the given window.
     *
     * @param startInstant inclusive window start
     * @param endInstant   exclusive window end
     * @return map of workstream ID to (runner → summed USD cost), ordered by cost descending within each workstream
     */
    private Map<String, Map<String, Double>> queryCostByRunnerPerWorkstream(
            Instant startInstant, Instant endInstant) {
        return queryCostPerWorkstreamFromTable(startInstant, endInstant,
                "job_runner_cost", "rc", "runner", "runner_cost");
    }

    /**
     * Sums per-model cost over the given window, optionally filtered to a
     * single workstream.
     *
     * @param workstreamId the workstream to filter by, or {@code null} for all
     * @param startInstant inclusive window start
     * @param endInstant   exclusive window end
     * @return map of provider/model identifier to summed USD cost, ordered by cost descending
     */
    private Map<String, Double> queryCostByModel(String workstreamId,
                                                 Instant startInstant, Instant endInstant) {
        return queryCostFromTable(workstreamId, startInstant, endInstant,
                "job_model_cost", "mc", "model", "model_cost");
    }

    /**
     * Sums per-model cost grouped by workstream over the given window.
     *
     * @param startInstant inclusive window start
     * @param endInstant   exclusive window end
     * @return map of workstream ID to (model → summed USD cost), ordered by cost descending within each workstream
     */
    private Map<String, Map<String, Double>> queryCostByModelPerWorkstream(
            Instant startInstant, Instant endInstant) {
        return queryCostPerWorkstreamFromTable(startInstant, endInstant,
                "job_model_cost", "mc", "model", "model_cost");
    }

    /**
     * Shared implementation for {@link #queryCostByRunner} and {@link #queryCostByModel}.
     * Joins {@code table} to {@code job_timing} and sums {@code cost_usd} per key,
     * using the same time-window and status filters as aggregate queries.
     */
    private Map<String, Double> queryCostFromTable(String workstreamId,
                                                   Instant startInstant, Instant endInstant,
                                                   String table, String tableAlias,
                                                   String keyColumn, String costAlias) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (connection == null) return result;

        String sql = "SELECT " + tableAlias + "." + keyColumn + " AS " + keyColumn + ", "
            + "COALESCE(SUM(" + tableAlias + ".cost_usd), 0) AS " + costAlias + " "
            + "FROM " + table + " " + tableAlias + " JOIN job_timing t ON "
            + tableAlias + ".job_id = t.job_id "
            + "WHERE t.started_at >= ? AND t.started_at < ? AND t.status NOT IN ('STARTED', 'SUBMITTED')"
            + (workstreamId != null ? " AND t.workstream_id = ?" : "")
            + " GROUP BY " + tableAlias + "." + keyColumn + " ORDER BY " + costAlias + " DESC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(startInstant));
            ps.setTimestamp(2, Timestamp.from(endInstant));
            if (workstreamId != null) {
                ps.setString(3, workstreamId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(keyColumn), rs.getDouble(costAlias));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query cost from " + table + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Shared implementation for {@link #queryCostByRunnerPerWorkstream} and
     * {@link #queryCostByModelPerWorkstream}. Returns a map of workstream ID to
     * (key → summed cost), grouped and ordered by workstream then cost descending.
     */
    private Map<String, Map<String, Double>> queryCostPerWorkstreamFromTable(
            Instant startInstant, Instant endInstant,
            String table, String tableAlias,
            String keyColumn, String costAlias) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        if (connection == null) return result;

        String sql = "SELECT t.workstream_id, " + tableAlias + "." + keyColumn + ", "
            + "COALESCE(SUM(" + tableAlias + ".cost_usd), 0) AS " + costAlias + " "
            + "FROM " + table + " " + tableAlias + " JOIN job_timing t ON "
            + tableAlias + ".job_id = t.job_id "
            + "WHERE t.started_at >= ? AND t.started_at < ? AND t.status NOT IN ('STARTED', 'SUBMITTED') "
            + "GROUP BY t.workstream_id, " + tableAlias + "." + keyColumn + " "
            + "ORDER BY t.workstream_id, " + costAlias + " DESC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(startInstant));
            ps.setTimestamp(2, Timestamp.from(endInstant));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String wsId = rs.getString("workstream_id");
                    result.computeIfAbsent(wsId, k -> new LinkedHashMap<>())
                          .put(rs.getString(keyColumn), rs.getDouble(costAlias));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query cost per workstream from " + table + ": " + e.getMessage());
        }
        return result;
    }
}
