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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * HSQLDB-backed storage for the one per-PR {@link RemediationState} record,
 * beside the other controller databases in the data directory so it survives
 * container rebuilds.
 *
 * <p>It keeps a single row per workstream (branch / PR) in the
 * {@code pr_remediation} table, updated as each remediation job completes via
 * {@link #recordPass}. A job that produced a commit made progress and resets
 * the consecutive-no-progress count; one that produced no commit changed
 * nothing and increments it. The submit path reads the row with {@link #get}
 * to decide whether the per-PR budget is spent (stop auto-dispatching and ping
 * a person) or whether a new review would only re-review the previous pass's
 * own commit.</p>
 *
 * <p>The controller cannot see whether a committing pass flipped a CI quality
 * gate from red to green, so a commit is taken as progress. This under-counts
 * the "changed things but no gate flipped" case the report also describes;
 * the dominant cost — the no-commit passes a clean PR keeps drawing — is
 * counted exactly, and a person's own commit always resets the count.</p>
 *
 * @author Michael Murray
 * @see RemediationState
 * @see JobStatsStore
 */
public class RemediationStateStore extends ControllerDatabase {

    /** DDL creating the per-PR remediation table if it does not yet exist. */
    private static final String CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS pr_remediation ("
        + "workstream_id VARCHAR(255) PRIMARY KEY, "
        + "repo_url VARCHAR(1000), "
        + "branch VARCHAR(255), "
        + "consecutive_no_progress INTEGER DEFAULT 0 NOT NULL, "
        + "last_produced_commit VARCHAR(64), "
        + "notified BOOLEAN DEFAULT FALSE NOT NULL, "
        + "updated_at TIMESTAMP)";

    /**
     * Creates a remediation store backed by an HSQLDB file database.
     *
     * @param dbPath path to the database file without extension, e.g.
     *               {@code ~/.flowtree/remediation}
     */
    public RemediationStateStore(String dbPath) {
        super(dbPath);
    }

    @Override
    protected void prepare() throws SQLException {
        try (Statement stmt = connection().createStatement()) {
            stmt.execute(CREATE_TABLE);
        }
    }

    /**
     * Returns the current record for a workstream, or the empty record when the
     * store is closed or has no row for it yet.
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return the record; never {@code null}
     */
    public synchronized RemediationState get(String workstreamId) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return RemediationState.empty(workstreamId);
        }
        String sql = "SELECT consecutive_no_progress, last_produced_commit, notified "
            + "FROM pr_remediation WHERE workstream_id = ?";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, workstreamId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new RemediationState(workstreamId, rs.getInt(1),
                            rs.getString(2), rs.getBoolean(3));
                }
            }
        } catch (SQLException e) {
            warn("Failed to read remediation state for " + workstreamId + ": " + e.getMessage());
        }
        return RemediationState.empty(workstreamId);
    }

    /**
     * Records the outcome of one completed remediation pass and returns the
     * updated record.
     *
     * <p>A pass that produced a commit ({@code producedCommit} non-null) made
     * progress: the consecutive-no-progress count resets to zero, the commit is
     * remembered as the last produced, and the notification flag clears so a
     * later budget exhaustion pings again. A pass that produced no commit
     * increments the count and leaves the last-produced commit and the flag as
     * they were.</p>
     *
     * @param workstreamId   the workstream (branch / PR) key
     * @param repoUrl        the repository URL, for the record; may be {@code null}
     * @param branch         the branch, for the record; may be {@code null}
     * @param producedCommit the commit SHA this pass produced, or {@code null}
     *                       when it changed nothing
     * @return the updated record (the empty record when the store is closed)
     */
    public synchronized RemediationState recordPass(String workstreamId, String repoUrl,
                                                    String branch, String producedCommit) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return RemediationState.empty(workstreamId);
        }
        RemediationState next = get(workstreamId).afterPass(producedCommit);
        write(next, repoUrl, branch);
        return next;
    }

    /**
     * Marks a workstream's budget-spent notification as sent, so a person is
     * pinged once per exhaustion rather than on every refused submission.
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return the updated record (the empty record when the store is closed)
     */
    public synchronized RemediationState markNotified(String workstreamId) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return RemediationState.empty(workstreamId);
        }
        RemediationState next = get(workstreamId).afterNotified();
        write(next, null, null);
        return next;
    }

    /**
     * Upserts the row holding {@code state}. {@code repoUrl}/{@code branch} are
     * recorded only when non-null, so a {@link #markNotified} that does not know
     * them does not erase values a {@link #recordPass} set.
     *
     * @param state   the record to persist
     * @param repoUrl the repository URL to record, or {@code null} to keep the
     *                stored value
     * @param branch  the branch to record, or {@code null} to keep the stored value
     */
    private void write(RemediationState state, String repoUrl, String branch) {
        String sql = "MERGE INTO pr_remediation USING (VALUES(?, ?, ?, ?, ?, ?, ?)) "
            + "AS vals(wid, ru, br, cnp, lpc, nt, ua) ON pr_remediation.workstream_id = vals.wid "
            + "WHEN MATCHED THEN UPDATE SET consecutive_no_progress = vals.cnp, "
            + "last_produced_commit = vals.lpc, notified = vals.nt, updated_at = vals.ua, "
            + "repo_url = COALESCE(vals.ru, pr_remediation.repo_url), "
            + "branch = COALESCE(vals.br, pr_remediation.branch) "
            + "WHEN NOT MATCHED THEN INSERT (workstream_id, repo_url, branch, "
            + "consecutive_no_progress, last_produced_commit, notified, updated_at) "
            + "VALUES (vals.wid, vals.ru, vals.br, vals.cnp, vals.lpc, vals.nt, vals.ua)";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, state.getWorkstreamId());
            ps.setString(2, repoUrl);
            ps.setString(3, branch);
            ps.setInt(4, state.getConsecutiveNoProgress());
            ps.setString(5, state.getLastProducedCommit());
            ps.setBoolean(6, state.isNotified());
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to write remediation state for " + state.getWorkstreamId()
                + ": " + e.getMessage());
        }
    }
}
