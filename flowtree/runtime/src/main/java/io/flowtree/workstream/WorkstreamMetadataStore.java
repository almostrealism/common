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

package io.flowtree.workstream;

import io.flowtree.controller.ControllerDatabase;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * HSQLDB-backed storage for workstream <em>metadata</em>: the per-workstream
 * values that do not belong in {@code workstreams.yaml}.
 *
 * <h2>Configuration versus metadata</h2>
 *
 * <p>A workstream's state is split by what kind of value it is:</p>
 * <ul>
 *   <li><b>Configuration</b> stays in the YAML: the small, operator-edited
 *       description of what the workstream is and how its jobs run — branch,
 *       base branch, repository, dependent repositories, labels, phase
 *       configuration, capabilities and flags. These values change rarely,
 *       are bounded in size, and an operator reads and edits them by hand.</li>
 *   <li><b>Metadata</b> lives here: any value that grows without bound (a
 *       free-text record such as {@link #PLAN_INSTRUCTIONS}) or that changes
 *       on every job. Keeping such values in the YAML would make the single
 *       configuration document large and expensive, and riskier, to rewrite
 *       on every change.</li>
 * </ul>
 *
 * <p>A field that is added to a workstream must be placed on one side of this
 * line. When it is metadata, give it a name constant here and add it to
 * {@link #loadMetadata} and {@link #saveMetadata}, which
 * {@link WorkstreamConfig} calls to fill workstreams from this store and to
 * write them back to it ahead of the YAML. Every workstream field other than
 * {@link #PLAN_INSTRUCTIONS} is currently configuration.</p>
 *
 * <h2>Storage</h2>
 *
 * <p>The store is a {@link ControllerDatabase}, like the job statistics: an
 * HSQLDB file database in the controller's data directory, opened by
 * {@link #initialize()} at startup and shut down by {@link #close()} with the
 * controller. Backing up that directory backs up the configuration, the job
 * statistics and this store together.</p>
 *
 * <p>Values are held in one name/value table keyed by
 * {@code (workstream_id, name)}, so a further metadata field needs no schema
 * change. The table is {@code CACHED}, keeping large values on disk rather
 * than in the database script, and the file write delay is disabled so a
 * committed write is on disk before the call returns: the YAML migration in
 * {@link WorkstreamConfig#attachMetadataStore} relies on that to remove a
 * value from the YAML only after it is durable here.</p>
 *
 * @see WorkstreamConfig#attachMetadataStore(WorkstreamMetadataStore, java.io.File)
 */
public class WorkstreamMetadataStore extends ControllerDatabase {

    /**
     * Metadata name of a workstream's plan instructions: the natural-language
     * record of the intent that originally seeded it. See
     * {@link Workstream#getPlanInstructions()}.
     */
    public static final String PLAN_INSTRUCTIONS = "planInstructions";

    /** File name, within the controller data directory, of the database (without extension). */
    public static final String DATABASE_NAME = "workstream-metadata";

    /** DDL for the name/value table, created on first use. */
    private static final String CREATE_TABLE = ""
        + "CREATE CACHED TABLE IF NOT EXISTS workstream_metadata ("
        + "    workstream_id VARCHAR(255) NOT NULL,"
        + "    name          VARCHAR(64)  NOT NULL,"
        + "    meta_value    VARCHAR(16777216) NOT NULL,"
        + "    updated_at    TIMESTAMP    NOT NULL,"
        + "    PRIMARY KEY (workstream_id, name)"
        + ")";

    /** Upserts one value. */
    private static final String MERGE_VALUE = ""
        + "MERGE INTO workstream_metadata m"
        + " USING (VALUES(CAST(? AS VARCHAR(255)), CAST(? AS VARCHAR(64)),"
        + "  CAST(? AS VARCHAR(16777216)), CAST(? AS TIMESTAMP)))"
        + "  AS v(workstream_id, name, meta_value, updated_at)"
        + " ON m.workstream_id = v.workstream_id AND m.name = v.name"
        + " WHEN MATCHED THEN UPDATE SET m.meta_value = v.meta_value, m.updated_at = v.updated_at"
        + " WHEN NOT MATCHED THEN INSERT (workstream_id, name, meta_value, updated_at)"
        + "  VALUES (v.workstream_id, v.name, v.meta_value, v.updated_at)";

    /** Removes one value, used to clear a field a workstream now carries as empty. */
    private static final String DELETE_VALUE =
        "DELETE FROM workstream_metadata WHERE workstream_id = ? AND name = ?";

    /**
     * Creates a store backed by the HSQLDB file database at {@code dbPath}.
     *
     * @param dbPath path to the database file without extension, normally
     *               {@code <data directory>/}{@value #DATABASE_NAME}
     */
    public WorkstreamMetadataStore(String dbPath) {
        super(dbPath);
    }

    /**
     * Disables the file write delay, so a committed write is durable before
     * the call returns, and creates the table when it does not yet exist.
     */
    @Override
    protected void prepare() throws SQLException {
        try (Statement stmt = connection().createStatement()) {
            stmt.execute("SET FILES WRITE DELAY FALSE");
            stmt.execute(CREATE_TABLE);
        }
    }

    /**
     * Returns one workstream's value of a metadata field.
     *
     * @param workstreamId the workstream
     * @param name         the metadata name, e.g. {@link #PLAN_INSTRUCTIONS}
     * @return the value, or {@code null} when none is stored
     * @throws IOException when the database cannot be read
     */
    public synchronized String get(String workstreamId, String name) throws IOException {
        requireOpen();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT meta_value FROM workstream_metadata WHERE workstream_id = ? AND name = ?")) {
            ps.setString(1, workstreamId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read " + name + " of " + workstreamId, e);
        }
    }

    /**
     * Returns every workstream's value of a metadata field in a single query,
     * so a caller describing many workstreams pays one read rather than one
     * per workstream.
     *
     * @param name the metadata name, e.g. {@link #PLAN_INSTRUCTIONS}
     * @return the values keyed by workstream ID; workstreams with no value are absent
     * @throws IOException when the database cannot be read
     */
    public synchronized Map<String, String> getAll(String name) throws IOException {
        requireOpen();
        Map<String, String> values = new HashMap<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT workstream_id, meta_value FROM workstream_metadata WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    values.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read " + name, e);
        }
        return values;
    }

    /**
     * Stores values of one metadata field for several workstreams as a single
     * transaction: either every change is committed or none is. Values equal to
     * what is already stored are not rewritten.
     *
     * <p>The two absent-value cases are distinguished so a caller can clear a
     * field without deleting the whole workstream. A {@code null} value means
     * the workstream does not carry this field — its stored row, if any, is
     * left untouched. An <em>empty</em> value is an explicit clear: the stored
     * row is removed. (A workstream is removed whole by
     * {@link #removeWorkstream(String)}.)</p>
     *
     * @param name   the metadata name, e.g. {@link #PLAN_INSTRUCTIONS}
     * @param values the values keyed by workstream ID
     * @return the number of rows written or removed
     * @throws IOException when the transaction cannot be committed; nothing
     *                     is written in that case
     */
    public synchronized int putAll(String name, Map<String, String> values) throws IOException {
        requireOpen();
        Map<String, String> current = getAll(name);
        Timestamp now = Timestamp.from(Instant.now());
        int written = 0;
        try {
            connection().setAutoCommit(false);
            try (PreparedStatement merge = connection().prepareStatement(MERGE_VALUE);
                 PreparedStatement delete = connection().prepareStatement(DELETE_VALUE)) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    String key = e.getKey();
                    String value = e.getValue();
                    // A null value is "no value carried" and leaves the row in place.
                    if (key == null || value == null) continue;
                    if (value.isEmpty()) {
                        // An empty value is an explicit clear: remove any stored row.
                        if (!current.containsKey(key)) continue;
                        delete.setString(1, key);
                        delete.setString(2, name);
                        delete.executeUpdate();
                        written++;
                        continue;
                    }
                    if (value.equals(current.get(key))) continue;
                    merge.setString(1, key);
                    merge.setString(2, name);
                    merge.setString(3, value);
                    merge.setTimestamp(4, now);
                    merge.executeUpdate();
                    written++;
                }
            }
            connection().commit();
            return written;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IOException("Failed to write " + name + ": " + e.getMessage(), e);
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * Fills in the metadata of each workstream that does not already carry it,
     * with one batched read per metadata field rather than one per workstream.
     *
     * <p>A value a workstream already carries is kept: it came from its YAML
     * entry, which holds metadata only when it was written after the last
     * migration or that migration failed, so it is the newer value. A failed
     * read is logged and leaves the workstreams as they are, which
     * {@link #saveMetadata} never mistakes for a deletion.</p>
     *
     * @param workstreams the workstreams to fill in
     */
    public void loadMetadata(Collection<Workstream> workstreams) {
        try {
            Map<String, String> planInstructions = getAll(PLAN_INSTRUCTIONS);
            for (Workstream ws : workstreams) {
                String current = ws.getPlanInstructions();
                if (current == null || current.isEmpty()) {
                    ws.setPlanInstructions(planInstructions.get(ws.getWorkstreamId()));
                }
            }
        } catch (IOException e) {
            warn("Could not read workstream metadata: " + e.getMessage());
        }
    }

    /**
     * Writes the metadata the workstreams carry, through {@link #putAll}: only
     * changed values are written, a workstream carrying no value ({@code null})
     * leaves its stored value in place, and one carrying an empty value has its
     * stored value cleared.
     *
     * @param workstreams the workstreams whose metadata to persist
     * @return the number of rows written or removed
     * @throws IOException when the values cannot be committed; nothing is
     *                     written in that case
     */
    public int saveMetadata(Collection<Workstream> workstreams) throws IOException {
        Map<String, String> planInstructions = new HashMap<>();
        for (Workstream ws : workstreams) {
            if (ws.getWorkstreamId() != null) {
                planInstructions.put(ws.getWorkstreamId(), ws.getPlanInstructions());
            }
        }
        return putAll(PLAN_INSTRUCTIONS, planInstructions);
    }

    /**
     * Removes every metadata value of a workstream, used when the workstream
     * itself is deleted.
     *
     * @param workstreamId the deleted workstream
     * @return the number of values removed
     * @throws IOException when the deletion cannot be committed
     */
    public synchronized int removeWorkstream(String workstreamId) throws IOException {
        requireOpen();
        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM workstream_metadata WHERE workstream_id = ?")) {
            ps.setString(1, workstreamId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to remove metadata of " + workstreamId, e);
        }
    }

    /** Throws when the store is not open, so a caller never mistakes a closed store for an empty one. */
    private void requireOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("WorkstreamMetadataStore is not open: " + getDbPath());
        }
    }

    /** Rolls back the open transaction, logging rather than masking the original failure. */
    private void rollbackQuietly() {
        try {
            connection().rollback();
        } catch (SQLException e) {
            warn("Rollback failed: " + e.getMessage());
        }
    }

    /** Returns the connection to auto-commit after a {@link #putAll} transaction. */
    private void restoreAutoCommit() {
        try {
            connection().setAutoCommit(true);
        } catch (SQLException e) {
            warn("Could not restore auto-commit: " + e.getMessage());
        }
    }
}
