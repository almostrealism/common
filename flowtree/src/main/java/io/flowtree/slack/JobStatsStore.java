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

import io.flowtree.jobs.JobCompletionEvent;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** DDL statement that creates the {@code job_timing} table if it does not yet exist. */
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
        + "    permission_denials INTEGER,"
        + "    target_branch  VARCHAR(255),"
        + "    commit_hash    VARCHAR(64),"
        + "    pull_request_url VARCHAR(1000),"
        + "    error_message  VARCHAR(2000)"
        + ")";

    /** DDL statement that creates an index on {@code (workstream_id, started_at)} for efficient queries. */
    private static final String CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_timing_ws_started ON job_timing (workstream_id, started_at)";

    /**
     * ALTER TABLE statements to add new columns to existing databases.
     * HSQLDB 2.x does not support {@code IF NOT EXISTS} on {@code ADD COLUMN},
     * so these statements will throw if the column already exists. The
     * initialization loop catches and ignores those errors.
     */
    private static final String[] SCHEMA_MIGRATIONS = {
        "ALTER TABLE job_timing ADD COLUMN subtype VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN session_error BOOLEAN",
        "ALTER TABLE job_timing ADD COLUMN permission_denials INTEGER",
        "ALTER TABLE job_timing ADD COLUMN target_branch VARCHAR(255)",
        "ALTER TABLE job_timing ADD COLUMN commit_hash VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN pull_request_url VARCHAR(1000)",
        "ALTER TABLE job_timing ADD COLUMN error_message VARCHAR(2000)",
        "ALTER TABLE job_timing ADD COLUMN slack_message_ts VARCHAR(64)"
    };

    /** DML statement that removes stale {@code STARTED} rows older than a given cutoff timestamp. */
    private static final String CLEAN_ORPHANS =
        "DELETE FROM job_timing WHERE status = 'STARTED' AND started_at < ?";

    /** Filesystem path to the HSQLDB database file (without extension). */
    private final String dbPath;
    /** Active JDBC connection to the backing HSQLDB database. */
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
     * @param targetBranch      the git branch targeted by the job, or null
     * @param commitHash        the commit hash produced by the job, or null
     * @param pullRequestUrl    the PR URL if one was created, or null
     * @param errorMessage      the error message if the job failed, or null
     */
    public synchronized void recordJobCompleted(String jobId, String workstreamId,
                                                 String status,
                                                 Instant completedAt, long durationMs,
                                                 long durationApiMs, double costUsd,
                                                 int numTurns, String sessionId,
                                                 int exitCode, String subtype,
                                                 boolean sessionError,
                                                 int permissionDenials,
                                                 String targetBranch, String commitHash,
                                                 String pullRequestUrl, String errorMessage) {
        if (connection == null) return;

        // Compute wall_clock_ms in Java to avoid HSQLDB DATEDIFF compatibility issues
        long wallClockMs = 0;
        try (PreparedStatement psQuery = connection.prepareStatement(
                "SELECT started_at FROM job_timing WHERE job_id = ?")) {
            psQuery.setString(1, jobId);
            try (ResultSet rs = psQuery.executeQuery()) {
                if (rs.next()) {
                    Timestamp startedTs = rs.getTimestamp("started_at");
                    if (startedTs != null) {
                        wallClockMs = completedAt.toEpochMilli() - startedTs.toInstant().toEpochMilli();
                    }
                }
            }
        } catch (SQLException e) {
            warn("Failed to query started_at for wall_clock_ms: " + e.getMessage());
        }

        // Try UPDATE first (job was previously started)
        String updateSql = "UPDATE job_timing SET status = ?, completed_at = ?, "
            + "wall_clock_ms = ?, "
            + "duration_ms = ?, duration_api_ms = ?, cost_usd = ?, "
            + "num_turns = ?, session_id = ?, exit_code = ?, "
            + "subtype = ?, session_error = ?, permission_denials = ?, "
            + "target_branch = ?, commit_hash = ?, pull_request_url = ?, error_message = ? "
            + "WHERE job_id = ?";

        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(completedAt));
            ps.setLong(3, wallClockMs);
            ps.setLong(4, durationMs);
            ps.setLong(5, durationApiMs);
            ps.setDouble(6, costUsd);
            ps.setInt(7, numTurns);
            ps.setString(8, sessionId);
            ps.setInt(9, exitCode);
            ps.setString(10, subtype);
            ps.setBoolean(11, sessionError);
            ps.setInt(12, permissionDenials);
            ps.setString(13, targetBranch);
            ps.setString(14, commitHash);
            ps.setString(15, pullRequestUrl);
            ps.setString(16, truncate(errorMessage, 2000));
            ps.setString(17, jobId);

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
            + "subtype, session_error, permission_denials, "
            + "target_branch, commit_hash, pull_request_url, error_message) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
            ps.setString(16, targetBranch);
            ps.setString(17, commitHash);
            ps.setString(18, pullRequestUrl);
            ps.setString(19, truncate(errorMessage, 2000));
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to insert job completed: " + e.getMessage());
        }
    }

    /**
     * Convenience overload that records job completion from a {@link JobCompletionEvent}.
     *
     * @param workstreamId the workstream this job belongs to
     * @param event        the completion event
     */
    public synchronized void recordJobCompleted(String workstreamId, JobCompletionEvent event) {
        recordJobCompleted(
            event.getJobId(), workstreamId, event.getStatus().name(),
            event.getTimestamp(), event.getDurationMs(), event.getDurationApiMs(),
            event.getCostUsd(), event.getNumTurns(), event.getSessionId(),
            event.getExitCode(), event.getSubtype(), event.isSessionError(),
            event.getPermissionDenials(),
            event.getTargetBranch(), event.getCommitHash(),
            event.getPullRequestUrl(), event.getErrorMessage());
    }

    /**
     * Returns the most recent job events for a workstream, newest first.
     *
     * @param workstreamId the workstream identifier
     * @param limit        maximum number of events to return
     * @return list of events, or empty list if none found or store unavailable
     */
    public synchronized List<JobCompletionEvent> getRecentJobs(String workstreamId, int limit) {
        if (connection == null) return Collections.emptyList();

        String sql = "SELECT * FROM job_timing WHERE workstream_id = ? "
            + "ORDER BY COALESCE(completed_at, started_at) DESC LIMIT ?";

        List<JobCompletionEvent> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, workstreamId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rowToEvent(rs));
                }
            }
        } catch (SQLException e) {
            warn("Failed to query recent jobs: " + e.getMessage());
        }
        return result;
    }

    /**
     * Returns the most recent event for a specific job, or {@code null} if not found.
     *
     * @param jobId the job identifier
     * @return the event, or null if the job is unknown or the store is unavailable
     */
    public synchronized JobCompletionEvent getJob(String jobId) {
        if (connection == null) return null;

        String sql = "SELECT * FROM job_timing WHERE job_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rowToEvent(rs);
                }
            }
        } catch (SQLException e) {
            warn("Failed to query job: " + e.getMessage());
        }
        return null;
    }

    /**
     * Constructs a {@link JobCompletionEvent} from the current row of a ResultSet.
     * Returns a best-effort reconstruction; Claude-specific fields default to zero/null.
     */
    private JobCompletionEvent rowToEvent(ResultSet rs) throws SQLException {
        String jobId = rs.getString("job_id");
        String statusStr = rs.getString("status");
        String description = rs.getString("description");
        String targetBranch = rs.getString("target_branch");
        String commitHash = rs.getString("commit_hash");
        String pullRequestUrl = rs.getString("pull_request_url");
        String errorMessage = rs.getString("error_message");

        JobCompletionEvent.Status status;
        try {
            status = JobCompletionEvent.Status.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            status = JobCompletionEvent.Status.STARTED;
        }

        JobCompletionEvent event;
        if (status == JobCompletionEvent.Status.FAILED) {
            event = JobCompletionEvent.failed(jobId, description, errorMessage, null);
        } else if (status == JobCompletionEvent.Status.SUCCESS) {
            event = JobCompletionEvent.success(jobId, description);
        } else {
            event = new JobCompletionEvent(jobId, status, description);
        }

        if (targetBranch != null || commitHash != null) {
            event.withGitInfo(targetBranch, commitHash,
                Collections.emptyList(), Collections.emptyList(), commitHash != null);
        }
        if (pullRequestUrl != null) {
            event.withPullRequestUrl(pullRequestUrl);
        }

        return event;
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

    /**
     * Stores the Slack message timestamp for a job.
     *
     * <p>Called after the submission message is successfully posted to Slack so
     * that {@link #getActiveWorkstreams} can construct message links for recent jobs.</p>
     *
     * @param jobId         the job identifier
     * @param slackMessageTs the Slack message timestamp (e.g., {@code "1234567890.123456"})
     */
    public synchronized void updateJobSlackTs(String jobId, String slackMessageTs) {
        if (connection == null || jobId == null || slackMessageTs == null) return;

        String sql = "UPDATE job_timing SET slack_message_ts = ? WHERE job_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, slackMessageTs);
            ps.setString(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to update slack_message_ts for job " + jobId + ": " + e.getMessage());
        }
    }

    /**
     * Returns per-workstream job activity for jobs completed on or after {@code since}.
     *
     * <p>Only workstreams with at least one completed job in the window are returned.
     * A job is considered "in the window" when its {@code completed_at} timestamp falls
     * on or after {@code since}. For rows that lack a {@code completed_at} value (e.g.,
     * jobs inserted during a controller restart), {@code started_at} is used as the
     * fallback via {@code COALESCE(completed_at, started_at)}.</p>
     *
     * <p>Each entry includes job counts by status and a short list of recent jobs with
     * their Slack message timestamps (for constructing message links).</p>
     *
     * @param since the start of the activity window (inclusive), matched against completion time
     * @return map of workstream ID to activity summary, ordered by job count descending
     */
    public synchronized Map<String, WorkstreamActivity> getActiveWorkstreams(Instant since) {
        Map<String, WorkstreamActivity> result = new LinkedHashMap<>();
        if (connection == null) return result;

        String aggSql = "SELECT workstream_id, "
            + "COUNT(*) AS job_count, "
            + "COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count, "
            + "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count, "
            + "COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled_count "
            + "FROM job_timing "
            + "WHERE COALESCE(completed_at, started_at) >= ? AND status <> 'STARTED' "
            + "GROUP BY workstream_id "
            + "ORDER BY job_count DESC";

        try (PreparedStatement ps = connection.prepareStatement(aggSql)) {
            ps.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WorkstreamActivity activity = new WorkstreamActivity();
                    activity.workstreamId = rs.getString("workstream_id");
                    activity.jobCount = rs.getInt("job_count");
                    activity.successCount = rs.getInt("success_count");
                    activity.failedCount = rs.getInt("failed_count");
                    activity.cancelledCount = rs.getInt("cancelled_count");
                    result.put(activity.workstreamId, activity);
                }
            }
        } catch (SQLException e) {
            warn("Failed to query active workstreams: " + e.getMessage());
            return result;
        }

        // Fetch up to 5 recent jobs with Slack ts for each active workstream
        String jobsSql = "SELECT job_id, slack_message_ts FROM job_timing "
            + "WHERE workstream_id = ? AND COALESCE(completed_at, started_at) >= ? AND slack_message_ts IS NOT NULL "
            + "ORDER BY COALESCE(completed_at, started_at) DESC LIMIT 5";

        for (WorkstreamActivity activity : result.values()) {
            try (PreparedStatement ps = connection.prepareStatement(jobsSql)) {
                ps.setString(1, activity.workstreamId);
                ps.setTimestamp(2, Timestamp.from(since));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        activity.recentJobs.add(new String[]{
                            rs.getString("job_id"),
                            rs.getString("slack_message_ts")
                        });
                    }
                }
            } catch (SQLException e) {
                warn("Failed to query recent jobs for workstream "
                    + activity.workstreamId + ": " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Truncates a string to at most {@code maxLength} characters.
     *
     * @param s         the string to truncate, or {@code null}
     * @param maxLength maximum number of characters to retain
     * @return the original string if it is shorter than {@code maxLength},
     *         its first {@code maxLength} characters otherwise, or
     *         {@code null} if {@code s} is {@code null}
     */
    private static String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength);
    }

    /**
     * Per-workstream activity summary for a rolling time window.
     */
    public static class WorkstreamActivity {
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
