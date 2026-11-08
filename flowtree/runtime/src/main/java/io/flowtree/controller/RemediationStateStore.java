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
 * {@code pr_remediation} table. The submit path reads the row with
 * {@link #evaluateSubmission} to decide whether a remediation pass should be
 * dispatched: it counts every dispatched pass, resets the count when the
 * triggering commit is one no remediation pass produced, and stops dispatching
 * (pinging a person once) when the per-PR cap is reached. A remediation pass's
 * terminal event records the commit it produced via {@link #completeJob}, so
 * the next pass that commit triggers is recognised as the loop's own rather
 * than a person's.</p>
 *
 * <p>Only jobs registered as automated remediation passes ({@link #registerJob})
 * have their produced commit recorded; any other FlowTree job's commit is left
 * unrecorded, so a pass triggered by it is treated as triggered by a foreign
 * commit and resets the count.</p>
 *
 * <p>The {@code consecutive_no_progress} column name is retained from the
 * previous "consecutive no-progress passes" design to avoid a schema migration
 * on the deployed database; it now stores the dispatched-pass count.</p>
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
     * submissions whose terminal event records a produced commit. Persisted so a
     * job that completes after a controller restart is still recognised.
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
     * Registers a dispatched automated remediation job, so its terminal event's
     * produced commit is recorded by {@link #completeJob}. Jobs that were never
     * registered — a person's work, shell jobs, anything not submitted as a
     * remediation pass — never have a commit recorded as the loop's own.
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
     * <p>A job registered with {@link #registerJob} is a remediation pass: it is
     * forgotten and, when it produced a commit, that commit is recorded as the
     * workstream's last-produced commit via {@link RemediationState#withProducedCommit}.
     * The dispatched-pass count is not touched here — a pass is counted when it
     * is dispatched ({@link #evaluateSubmission}), not when it completes. A job
     * that was never registered (a person's work) records nothing: a pass
     * triggered by its commit is reset at submit time instead.</p>
     *
     * @param jobId          the completed job's id
     * @param workstreamId   the workstream the event belongs to; unused for an
     *                       unregistered job, kept for symmetry; may be {@code null}
     * @param branch         the branch, for the record; may be {@code null}
     * @param producedCommit the commit SHA the job produced, or {@code null}
     * @param countsAsPass   {@code false} for a terminal event that is not a
     *                       completed pass (a cancelled run), which records nothing
     * @return the updated record, or {@code null} when nothing was recorded
     */
    public synchronized RemediationState completeJob(String jobId, String workstreamId, String branch,
                                                     String producedCommit, boolean countsAsPass) {
        if (!isOpen() || jobId == null || jobId.isEmpty()) return null;
        String registeredWorkstream = forgetJob(jobId);
        if (!countsAsPass) return null;
        if (registeredWorkstream == null) return null;
        if (producedCommit == null || producedCommit.isEmpty()) return null;
        RemediationState current;
        try {
            current = read(registeredWorkstream);
        } catch (SQLException e) {
            warn("Failed to read remediation state for " + registeredWorkstream
                + "; not recording the produced commit: " + e.getMessage());
            return null;
        }
        if (current == null) current = RemediationState.empty(registeredWorkstream);
        RemediationState next = current.withProducedCommit(producedCommit);
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
     * store is closed, has no row for it yet, or could not be read. This lenient
     * reading suits only a caller whose outcome does not depend on the stored
     * count; the submit gate reads strictly ({@link #evaluateSubmission}) so a
     * read error fails closed rather than refunding a spent cap.
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return the record; never {@code null}
     */
    public synchronized RemediationState get(String workstreamId) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return RemediationState.empty(workstreamId);
        }
        try {
            RemediationState state = read(workstreamId);
            return state != null ? state : RemediationState.empty(workstreamId);
        } catch (SQLException e) {
            warn("Failed to read remediation state for " + workstreamId + ": " + e.getMessage());
            return RemediationState.empty(workstreamId);
        }
    }

    /**
     * Reads the stored record for a workstream, propagating a read failure so a
     * caller that must fail closed can tell a missing row from an unreadable
     * store.
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return the record, or {@code null} when the table has no row for it
     * @throws SQLException when the read itself fails (a transient DB error)
     */
    private RemediationState read(String workstreamId) throws SQLException {
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
        }
        return null;
    }

    /**
     * The outcome of {@link #evaluateSubmission}: whether an automated
     * remediation submission should be dispatched, and if not, why it is skipped.
     */
    public enum Decision {
        /** Dispatch the submission; nothing bars it. */
        DISPATCH,
        /** Skip: the cap is reached and this call claimed the one-time ping. */
        BUDGET_SPENT_NOTIFY,
        /** Skip: the cap is reached and the ping was already claimed. */
        BUDGET_SPENT_SILENT,
        /** Skip: the record could not be read, so dispatch fails closed. */
        UNAVAILABLE
    }

    /**
     * Decides, atomically, whether an automated remediation submission should be
     * dispatched, counting the pass or claiming the one-time cap-reached ping in
     * the same locked step. Reading the record, resetting the count for a foreign
     * commit, testing the cap, counting the pass and claiming the notification
     * all happen under the store's lock, so concurrent submissions cannot
     * interleave to over-count, lose a reset, or ping twice.
     *
     * <p>A commit no remediation pass produced (the triggering commit is not the
     * last one a pass produced) resets the count first: a person's push or a
     * manually submitted job starts the per-PR cap over. Otherwise, when the cap
     * is not yet reached, the pass is counted and dispatched; when it is, the
     * submission is skipped and a person pinged once.</p>
     *
     * <p>Fails closed: when the record cannot be read (a transient DB error) the
     * decision is {@link Decision#UNAVAILABLE}, so the caller refuses the
     * automated submission rather than dispatching without the cap. A disabled
     * (closed) store leaves the gate inactive, exactly as a caller with no store
     * does, so it dispatches.</p>
     *
     * @param workstreamId  the workstream (branch / PR) key
     * @param budget        the per-PR cap; non-positive disables it
     * @param triggerCommit the commit that triggered the submission, or {@code null}
     * @return the submit-time decision; never {@code null}
     */
    public synchronized Decision evaluateSubmission(String workstreamId, int budget,
                                                    String triggerCommit) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return Decision.DISPATCH;
        }
        RemediationState state;
        try {
            state = read(workstreamId);
        } catch (SQLException e) {
            warn("Failed to read remediation state for " + workstreamId
                + "; refusing automated submission until it can be read: " + e.getMessage());
            return Decision.UNAVAILABLE;
        }
        if (state == null) state = RemediationState.empty(workstreamId);
        if (!state.wasProducedByRemediationPass(triggerCommit)) {
            state = state.afterForeignCommitReset();
        }
        if (state.budgetExhausted(budget)) {
            return claimNotification(state) ? Decision.BUDGET_SPENT_NOTIFY
                    : Decision.BUDGET_SPENT_SILENT;
        }
        write(state.afterDispatch(), null, null);
        return Decision.DISPATCH;
    }

    /**
     * Marks a workstream's cap-reached notification as sent, so a person is
     * pinged once per exhaustion rather than on every refused submission.
     *
     * <p>The read and the write happen under the store's lock, so of several
     * concurrent callers exactly one observes the flag unset and is told it
     * claimed the notification; that caller sends the ping.</p>
     *
     * <p>A record that cannot be read claims nothing: writing the flag onto a
     * substitute empty record would overwrite the stored count with zero and
     * silently refund the spent cap.</p>
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return {@code true} when this call flipped the flag from unset to set and
     *         persisted it, {@code false} when it was already set, the record
     *         could not be read, the set flag could not be persisted, or the
     *         store is closed
     */
    public synchronized boolean markNotified(String workstreamId) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return false;
        }
        RemediationState current;
        try {
            current = read(workstreamId);
        } catch (SQLException e) {
            warn("Failed to read remediation state for " + workstreamId
                + "; not claiming the notification: " + e.getMessage());
            return false;
        }
        return claimNotification(current != null ? current : RemediationState.empty(workstreamId));
    }

    /**
     * Returns a claimed cap-reached notification that could not be delivered,
     * clearing the flag so the next refused submission claims the ping again.
     * Without this, a claim made while no channel or notifier is configured
     * would silence the escalation for good, even after one is configured.
     *
     * <p>A record that cannot be read, or whose flag is not set (a commit has
     * already reset it), is left unchanged.</p>
     *
     * @param workstreamId the workstream (branch / PR) key
     * @return {@code true} when the set flag was cleared and that was persisted
     */
    public synchronized boolean releaseNotification(String workstreamId) {
        if (!isOpen() || workstreamId == null || workstreamId.isEmpty()) {
            return false;
        }
        RemediationState current;
        try {
            current = read(workstreamId);
        } catch (SQLException e) {
            warn("Failed to read remediation state for " + workstreamId
                + "; not releasing the notification: " + e.getMessage());
            return false;
        }
        if (current == null || !current.isNotified()) return false;
        return write(current.afterNotificationUndelivered(), null, null);
    }

    /**
     * Sets the notification flag on a record the caller has just read under the
     * store's lock, so the claim acts on that record rather than on a second read.
     *
     * <p>The claim succeeds only when the flag is durably persisted. A write that
     * fails is not a claim: reporting one would send the one-time ping without
     * recording {@code notified=true}, so every later refused submission would
     * read the flag still unset and ping again. Returning {@code false} instead
     * keeps the ping suppressed while the store cannot persist the flag; once it
     * can, the next refused submission claims it and pings exactly once.</p>
     *
     * @param current the record as read
     * @return {@code true} when the flag was unset and the set flag was persisted
     */
    private boolean claimNotification(RemediationState current) {
        if (current.isNotified()) return false;
        return write(current.afterNotified(), null, null);
    }

    /**
     * Upserts the row holding {@code state}. {@code repoUrl}/{@code branch} are
     * recorded only when non-null, so a write that does not know them does not
     * erase values an earlier write set.
     *
     * @param state   the record to persist
     * @param repoUrl the repository URL to record, or {@code null} to keep the
     *                stored value
     * @param branch  the branch to record, or {@code null} to keep the stored value
     * @return {@code true} when the row was written, {@code false} when the write
     *         failed (a transient DB error, already logged)
     */
    private boolean write(RemediationState state, String repoUrl, String branch) {
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
            ps.setInt(4, state.getPassCount());
            ps.setString(5, state.getLastProducedCommit());
            ps.setBoolean(6, state.isNotified());
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            warn("Failed to write remediation state for " + state.getWorkstreamId()
                + ": " + e.getMessage());
            return false;
        }
    }
}
