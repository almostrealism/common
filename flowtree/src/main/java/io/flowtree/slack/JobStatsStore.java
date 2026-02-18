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

package io.flowtree.slack;

import org.almostrealism.io.ConsoleFeatures;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HSQLDB-backed storage for job timing data and statistics.
 *
 * <p>Records job start/completion events with timing metrics extracted
 * from Claude Code output. Provides weekly aggregation queries for
 * the {@code /flowtree stats} command and the {@code /api/stats} endpoint.</p>
 *
 * @author Michael Murray
 * @see SlackNotifier
 * @see FlowTreeApiEndpoint
 */
public class JobStatsStore implements ConsoleFeatures {

    private static final String CREATE_TABLE = ""
        + "CREATE TABLE IF NOT EXISTS job_timing ("
        + "    job_id         VARCHAR(255) PRIMARY KEY,"
        + "    workstream_id  VARCHAR(255) NOT NULL,"
        + "    status         VARCHAR(32)  NOT NULL,"
        + "    started_at     TIMESTAMP    NOT NULL,"
        + "    completed_at   TIMESTAMP,"
        + "    wall_clock_ms  BIGINT,"
        + "    duration_ms    BIGINT,"
        + "    duration_api_ms BIGINT,"
        + "    cost_usd       DOUBLE,"
        + "    num_turns      INTEGER,"
        + "    session_id     VARCHAR(255),"
        + "    exit_code      INTEGER,"
        + "    description    VARCHAR(1000),"
        + "    subtype        VARCHAR(64),"
        + "    session_error  BOOLEAN,"
        + "    permission_denials INTEGER"
        + ")";

    private static final String CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_timing_ws_started ON job_timing (workstream_id, started_at)";

    /** ALTER TABLE statements to add new columns to existing databases. */
    private static final String[] SCHEMA_MIGRATIONS = {
        "ALTER TABLE job_timing ADD COLUMN IF NOT EXISTS subtype VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN IF NOT EXISTS session_error BOOLEAN",
        "ALTER TABLE job_timing ADD COLUMN IF NOT EXISTS permission_denials INTEGER"
    };

    private static final String CLEAN_ORPHANS =
        "DELETE FROM job_timing WHERE status = 'STARTED' AND started_at < ?";

    private final String dbPath;
    private Connection connection;

    /**
     * Creates a new stats store backed by an HSQLDB file database.
     *
     * @param dbPath path to the HSQLDB database file (without extension),
     *               e.g. {@code ~/.flowtree/stats}
     */
    public JobStatsStore(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Initializes the database: creates tables and indexes, and
     * cleans up orphaned STARTED rows older than 7 days.
     */
    public void initialize() {
        try {
            String url = "jdbc:hsqldb:file:" + dbPath + ";shutdown=true";
            connection = DriverManager.getConnection(url, "SA", "");

            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_TABLE);
                stmt.execute(CREATE_INDEX);
                for (String migration : SCHEMA_MIGRATIONS) {
                    try {
                        stmt.execute(migration);
                    } catch (SQLException ignored) {
                        // Column may already exist on databases that were
                        // created with the updated CREATE TABLE statement
                    }
                }
            }

            // Clean orphaned STARTED rows older than 7 days
            Instant cutoff = Instant.now().minusSeconds(7 * 24 * 3600);
            try (PreparedStatement ps = connection.prepareStatement(CLEAN_ORPHANS)) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                int cleaned = ps.executeUpdate();
                if (cleaned > 0) {
                    log("Cleaned " + cleaned + " orphaned STARTED job(s)");
                }
            }

            log("JobStatsStore initialized: " + dbPath);
        } catch (SQLException e) {
            warn("Failed to initialize JobStatsStore: " + e.getMessage());
        }
    }

    /**
     * Shuts down the HSQLDB database and closes the connection.
     */
    public void close() {
        if (connection != null) {
            try {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SHUTDOWN");
                }
                connection.close();
                log("JobStatsStore closed");
            } catch (SQLException e) {
                warn("Error closing JobStatsStore: " + e.getMessage());
            }
            connection = null;
        }
    }

    /**
     * Records that a job has started.
     *
     * @param jobId         the job identifier
     * @param workstreamId  the workstream this job belongs to
     * @param description   human-readable description
     * @param startedAt     the start timestamp
     */
    public synchronized void recordJobStarted(String jobId, String workstreamId,
                                               String description, Instant startedAt) {
        if (connection == null) return;

        String sql = "MERGE INTO job_timing USING (VALUES(?, ?, ?, ?, ?)) "
            + "AS vals(jid, wid, sts, sta, dsc) ON job_timing.job_id = vals.jid "
            + "WHEN NOT MATCHED THEN INSERT (job_id, workstream_id, status, started_at, description) "
            + "VALUES (vals.jid, vals.wid, vals.sts, vals.sta, vals.dsc)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, workstreamId);
            ps.setString(3, "STARTED");
            ps.setTimestamp(4, Timestamp.from(startedAt));
            ps.setString(5, truncate(description, 1000));
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to record job started: " + e.getMessage());
        }
    }

    /**
     * Records that a job has completed.
     *
     * <p>Updates the existing row if the job was previously recorded as started.
     * If no row exists (e.g., controller restarted), inserts a new row with
     * {@code started_at} set to the completion time and uses the provided
     * {@code workstreamId} to attribute the job correctly.</p>
     *
     * @param jobId             the job identifier
     * @param workstreamId      the workstream this job belongs to
     * @param status            the completion status (SUCCESS, FAILED, CANCELLED)
     * @param completedAt       the completion timestamp
     * @param durationMs        duration reported by Claude Code
     * @param durationApiMs     API duration reported by Claude Code
     * @param costUsd           cost in USD
     * @param numTurns          number of agentic turns
     * @param sessionId         the Claude Code session ID
     * @param exitCode          the process exit code
     * @param subtype           session stop reason (e.g. "success", "error_max_turns")
     * @param sessionError      whether the session ended with an error
     * @param permissionDenials number of permission denials during the session
     */
    public synchronized void recordJobCompleted(String jobId, String workstreamId,
                                                 String status,
                                                 Instant completedAt, long durationMs,
                                                 long durationApiMs, double costUsd,
                                                 int numTurns, String sessionId,
                                                 int exitCode, String subtype,
                                                 boolean sessionError,
                                                 int permissionDenials) {
        if (connection == null) return;

        // Try UPDATE first (job was previously started)
        String updateSql = "UPDATE job_timing SET status = ?, completed_at = ?, "
            + "wall_clock_ms = DATEDIFF('MILLISECOND', started_at, ?), "
            + "duration_ms = ?, duration_api_ms = ?, cost_usd = ?, "
            + "num_turns = ?, session_id = ?, exit_code = ?, "
            + "subtype = ?, session_error = ?, permission_denials = ? "
            + "WHERE job_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(completedAt));
            ps.setTimestamp(3, Timestamp.from(completedAt));
            ps.setLong(4, durationMs);
            ps.setLong(5, durationApiMs);
            ps.setDouble(6, costUsd);
            ps.setInt(7, numTurns);
            ps.setString(8, sessionId);
            ps.setInt(9, exitCode);
            ps.setString(10, subtype);
            ps.setBoolean(11, sessionError);
            ps.setInt(12, permissionDenials);
            ps.setString(13, jobId);

            int updated = ps.executeUpdate();
            if (updated > 0) return;
        } catch (SQLException e) {
            warn("Failed to update job completed: " + e.getMessage());
            return;
        }

        // No row existed - insert with started_at = completedAt
        String wsId = workstreamId != null ? workstreamId : "unknown";
        String insertSql = "INSERT INTO job_timing (job_id, workstream_id, status, "
            + "started_at, completed_at, wall_clock_ms, duration_ms, duration_api_ms, "
            + "cost_usd, num_turns, session_id, exit_code, "
            + "subtype, session_error, permission_denials) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setString(1, jobId);
            ps.setString(2, wsId);
            ps.setString(3, status);
            ps.setTimestamp(4, Timestamp.from(completedAt));
            ps.setTimestamp(5, Timestamp.from(completedAt));
            ps.setLong(6, durationMs);
            ps.setLong(7, durationMs);
            ps.setLong(8, durationApiMs);
            ps.setDouble(9, costUsd);
            ps.setInt(10, numTurns);
            ps.setString(11, sessionId);
            ps.setInt(12, exitCode);
            ps.setString(13, subtype);
            ps.setBoolean(14, sessionError);
            ps.setInt(15, permissionDenials);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to insert job completed: " + e.getMessage());
        }
    }

    /**
     * Returns aggregated statistics for all workstreams in the given week.
     *
     * @param weekStart the Monday starting the week
     * @return the aggregated stats
     */
    public WeeklyStats getWeeklyStats(LocalDate weekStart) {
        return getWeeklyStats(null, weekStart);
    }

    /**
     * Returns aggregated statistics for a specific workstream in the given week.
     *
     * @param workstreamId the workstream to filter by, or null for all
     * @param weekStart    the Monday starting the week
     * @return the aggregated stats
     */
    public synchronized WeeklyStats getWeeklyStats(String workstreamId, LocalDate weekStart) {
        if (connection == null) return new WeeklyStats();

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
            + "WHERE started_at >= ? AND started_at < ? AND status <> 'STARTED'";

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
    public synchronized Map<String, WeeklyStats> getWeeklyStatsByWorkstream(LocalDate weekStart) {
        Map<String, WeeklyStats> result = new LinkedHashMap<>();
        if (connection == null) return result;

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
            + "WHERE started_at >= ? AND started_at < ? AND status <> 'STARTED' "
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

        return result;
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength);
    }

    /**
     * Aggregated weekly job statistics.
     */
    public static class WeeklyStats {
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
    }
}
