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

package io.flowtree.api;

import io.flowtree.job.JobFactory;
import org.almostrealism.io.ConsoleFeatures;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Durable record of the serialized {@link JobFactory} for every job the
 * controller has accepted but which has not yet reached a terminal state, so a
 * job the controller took responsibility for survives a restart.
 *
 * <p>The controller's dispatch queue ({@link DelayedJobQueue}) holds pending
 * dispatch futures only in memory, and the persisted {@code job_timing} row in
 * {@code JobStatsStore} records a job's <em>metadata</em> but not the full
 * factory (repo, branch, prompt, phase config) needed to re-dispatch it. This
 * store closes that gap: at submission the endpoint persists the factory's
 * encoded blob here, keyed by job id; on startup the endpoint re-dispatches the
 * blob for every job the stats store still reports as {@code SUBMITTED}; and at
 * the terminal choke point the blob is cleared. Together with the item that
 * promotes a running job to {@code STARTED} on its first liveness signal, this
 * makes re-dispatch idempotent: a job that actually started is no longer
 * {@code SUBMITTED} and {@link #recover} never re-runs it.</p>
 *
 * <p>Backed by its own HSQLDB file database under the controller's persistent
 * data directory (the same volume that survives container rebuilds as
 * {@code JobStatsStore}'s database), rather than a column on {@code job_timing},
 * so no change to that already-large store is needed and the blob — which can
 * be tens of kilobytes — lives apart from the timing/statistics rows.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 * @see JobFactory#decode(String)
 */
public class PendingJobRecovery implements ConsoleFeatures {

    /** DDL creating the single-column-plus-key recovery table if absent. */
    private static final String CREATE_TABLE = ""
        + "CREATE TABLE IF NOT EXISTS recoverable_job ("
        + "    job_id       VARCHAR(255) PRIMARY KEY,"
        + "    factory_blob LONGVARCHAR  NOT NULL"
        + ")";

    /** Filesystem path to the HSQLDB database file (without extension). */
    private final String dbPath;

    /** Active JDBC connection to the backing HSQLDB database; {@code null} until initialized. */
    private Connection connection;

    /**
     * Creates a recovery store backed by an HSQLDB file database.
     *
     * @param dbPath path to the HSQLDB database file (without extension),
     *               e.g. {@code ~/.flowtree/job-recovery}
     */
    public PendingJobRecovery(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Opens the database and creates the recovery table if it does not yet
     * exist. A failure is logged and leaves the store disabled (every method
     * becomes a no-op) rather than failing controller startup.
     */
    public void initialize() {
        try {
            connection = DriverManager.getConnection(
                    "jdbc:hsqldb:file:" + dbPath + ";shutdown=true", "SA", "");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_TABLE);
            }
            log("PendingJobRecovery initialized: " + dbPath);
        } catch (SQLException e) {
            warn("Failed to initialize PendingJobRecovery: " + e.getMessage());
        }
    }

    /**
     * Shuts down the database and closes the connection.
     */
    public void close() {
        if (connection == null) return;
        try {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SHUTDOWN");
            }
            connection.close();
        } catch (SQLException e) {
            warn("Error closing PendingJobRecovery: " + e.getMessage());
        }
        connection = null;
    }

    /**
     * Persists the encoded factory for a submitted job, replacing any existing
     * blob for the same job id so a re-submission is idempotent.
     *
     * @param jobId          the job identifier (the factory task id)
     * @param encodedFactory the factory's {@link JobFactory#encode() encoded}
     *                       wire form; ignored when {@code null} or empty
     */
    public synchronized void persist(String jobId, String encodedFactory) {
        if (connection == null || jobId == null || jobId.isEmpty()) return;
        if (encodedFactory == null || encodedFactory.isEmpty()) return;

        String sql = "MERGE INTO recoverable_job USING (VALUES(?, ?)) "
            + "AS vals(jid, blob) ON recoverable_job.job_id = vals.jid "
            + "WHEN MATCHED THEN UPDATE SET factory_blob = vals.blob "
            + "WHEN NOT MATCHED THEN INSERT (job_id, factory_blob) VALUES (vals.jid, vals.blob)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, encodedFactory);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to persist recoverable factory for job " + jobId + ": " + e.getMessage());
        }
    }

    /**
     * Removes the persisted factory for a job, called when it reaches a terminal
     * state so the store holds only jobs that could still need re-dispatch. A
     * no-op when no blob exists for the job.
     *
     * @param jobId the job identifier
     */
    public synchronized void clear(String jobId) {
        if (connection == null || jobId == null || jobId.isEmpty()) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM recoverable_job WHERE job_id = ?")) {
            ps.setString(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to clear recoverable factory for job " + jobId + ": " + e.getMessage());
        }
    }

    /**
     * Re-dispatches the persisted factory for every job in {@code stillPending},
     * decoding each blob through {@link JobFactory#decode(String)} and handing
     * the reconstructed factory to {@code dispatch}.
     *
     * <p>Only jobs the caller still considers pending are re-dispatched: the
     * caller passes the set of job ids the stats store reports as
     * {@code SUBMITTED}, so a job that already started (and is therefore
     * {@code STARTED}, not {@code SUBMITTED}) or completed is never re-run even
     * though its blob may linger until {@link #clear} removes it. A blob that
     * fails to decode is skipped and logged rather than aborting the sweep.</p>
     *
     * @param stillPending the job ids the stats store still reports as
     *                     {@code SUBMITTED}; a blob whose id is absent is skipped
     * @param dispatch     receives each reconstructed factory for re-dispatch
     * @return the number of factories handed to {@code dispatch}
     */
    public synchronized int recover(Collection<String> stillPending,
                                    Consumer<JobFactory> dispatch) {
        if (connection == null || stillPending == null || stillPending.isEmpty()) return 0;

        int recovered = 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT job_id, factory_blob FROM recoverable_job");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String jobId = rs.getString("job_id");
                if (!stillPending.contains(jobId)) continue;
                JobFactory factory = JobFactory.decode(rs.getString("factory_blob"));
                if (factory == null) {
                    warn("Could not decode persisted factory for job " + jobId + "; skipping");
                    continue;
                }
                dispatch.accept(factory);
                recovered++;
            }
        } catch (SQLException e) {
            warn("Failed to read recoverable jobs: " + e.getMessage());
        }
        return recovered;
    }
}
