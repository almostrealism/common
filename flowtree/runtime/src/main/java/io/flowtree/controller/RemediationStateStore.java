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
 * counted exactly. Only jobs registered as automated remediation passes
 * ({@link #registerJob}) count; a commit made by any other FlowTree job also
 * resets the count, but a direct push the controller never sees does not.</p>
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
     * DDL creating the table of in-flight remediation jobs: the automated
     * submissions whose terminal event counts as a pass. Persisted so a job
     * that completes after a controller restart is still counted.
     */
    private static final String CREATE_JOBS_TABLE =
        "CREATE TABLE IF NOT EXISTS pr_remediation_job ("
        + "job_id VARCHAR(255) PRIMARY KEY, "
        + "workstream_id VARCHAR(255) NOT NULL)";

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
            stmt.execute(CREATE_JOBS_TABLE);
        }
    }

    /**
     * Registers a dispatched automated remediation job, so its terminal event
     * is counted as a pass by {@link #completeJob}. Jobs that were never
     * registered — a person's work, shell jobs, anything not submitted as
     * automated — never move the per-PR budget.
     *
     * @param jobId        the dispatched job's id
     * @param workstreamId the workstream (branch / PR) the job remediates
     */
    public synchronized void registerJob(String jobId, String workstreamId) {
        // TODO(review): a registered job that never emits a terminal event leaves its row here forever; consider pruning by age.
        if (!isOpen() || jobId == null || jobId.isEmpty()
                || workstreamId == null || workstreamId.isEmpty()) {
            return;
        }
        String sql = "MERGE INTO pr_remediation_job USING (VALUES(?, ?)) AS vals(jid, wid) "
            + "ON pr_remediation_job.job_id = vals.jid "
            + "WHEN MATCHED THEN UPDATE SET workstream_id = vals.wid "
            + "WHEN NOT MATCHED THEN INSERT (job_id, workstream_id) VALUES (vals.jid, vals.wid)";
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, workstreamId);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to register remediation job " + jobId + ": " + e.getMessage());
        }
    }

    /**
     * Records a job's terminal event against the per-PR record.
     *
     * <p>A job registered with {@link #registerJob} is a remediation pass: it
     * is forgotten and, when {@code countsAsPass}, recorded against the
     * workstream it was registered for via {@link #recordPass}. A job that was
     * never registered (a person's work) is not a pass and never increments
     * the count, but a commit it produced is still progress on the branch and
     * resets the count ({@link RemediationState#afterExternalCommit}).</p>
     *
     * @param jobId          the completed job's id
     * @param workstreamId   the workstream the event belongs to, used for an
     *                       unregistered job's commit; may be {@code null}
     * @param branch         the branch, for the record; may be {@code null}
     * @param producedCommit the commit SHA the job produced, or {@code null}
     * @param countsAsPass   {@code false} for a terminal event that is not a
     *                       completed pass (a cancelled run), which changes no count
     * @return the updated record, or {@code null} when nothing was recorded
     */
    public synchronized RemediationState completeJob(String jobId, String workstreamId, String branch,
                                                     String producedCommit, boolean countsAsPass) {
        if (!isOpen() || jobId == null || jobId.isEmpty()) return null;
        String registeredWorkstream = forgetJob(jobId);
        if (!countsAsPass) return null;
        if (registeredWorkstream != null) {
            return recordPass(registeredWorkstream, null, branch, producedCommit);
        }
        if (producedCommit == null || producedCommit.isEmpty()
                || workstreamId == null || workstreamId.isEmpty()) {
            return null;
        }
        RemediationState next = get(workstreamId).afterExternalCommit();
        write(next, null, branch);
        return next;
    }

    /**
     * Removes a job from the registered remediation jobs.
     *
     * @param jobId the job's id
     * @return the workstream the job was registered for, or {@code null} when
     *         it was never registered
     */
    private String forgetJob(String jobId) {
        String workstreamId = null;
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT workstream_id FROM pr_remediation_job WHERE job_id = ?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) workstreamId = rs.getString(1);
            }
        } catch (SQLException e) {
            warn("Failed to read remediation job " + jobId + ": " + e.getMessage());
        }
        if (workstreamId == null) return null;
        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM pr_remediation_job WHERE job_id = ?")) {
            ps.setString(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to forget remediation job " + jobId + ": " + e.getMessage());
        }
        return workstreamId;
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
     * <p>The read and the write happen under the store's lock, so of several
     * concurrent callers exactly one observes the flag unset and is told it
     * claimed the notification; that caller sends the ping.</p>
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return {@code true} when this call flipped the flag from unset to set,
     *         {@code false} when it was already set or the store is closed
     */
    public synchronized boolean markNotified(String workstreamId) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return false;
        }
        RemediationState current = get(workstreamId);
        if (current.isNotified()) return false;
        write(current.afterNotified(), null, null);
        return true;
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
