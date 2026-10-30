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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * An embedded HSQLDB file database kept in the controller's data directory
 * (the directory holding {@code workstreams.yaml}, a persistent volume in the
 * controller deployment), so backing up that directory backs up every such
 * database with the configuration.
 *
 * <p>This type owns the lifecycle every such database shares: {@link #initialize()}
 * opens the file database and hands it to {@link #prepare()} to create or
 * migrate its schema; {@link #close()} shuts it down. A database that could not
 * be opened is left closed, which {@link #isOpen()} reports and
 * {@link #connection()} reflects by returning {@code null}; each subclass
 * decides whether its operations then do nothing or fail.</p>
 *
 * @see JobStatsStore
 * @see io.flowtree.api.PendingJobRecovery
 * @see io.flowtree.workstream.WorkstreamMetadataStore
 */
public abstract class ControllerDatabase implements ConsoleFeatures {

    /** Filesystem path to the HSQLDB database file (without extension). */
    private final String dbPath;

    /** Active connection, or {@code null} before {@link #initialize()}, after {@link #close()}, or when opening failed. */
    private volatile Connection connection;

    /**
     * Creates a database backed by the HSQLDB file at {@code dbPath}. Nothing
     * is opened until {@link #initialize()}.
     *
     * @param dbPath path to the database file without extension, normally a
     *               file name within the controller data directory
     */
    protected ControllerDatabase(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Opens the database and prepares its schema through {@link #prepare()}.
     * A failure is logged and leaves the database closed rather than failing
     * controller startup.
     *
     * @return {@code true} when the database is open; {@code false} when it
     *         could not be opened or prepared
     */
    public synchronized boolean initialize() {
        try {
            connection = DriverManager.getConnection(
                    "jdbc:hsqldb:file:" + dbPath + ";shutdown=true", "SA", "");
            prepare();
            log("Opened database " + dbPath);
            return true;
        } catch (SQLException e) {
            warn("Failed to open database " + dbPath + ": " + e.getMessage());
            closeConnectionQuietly();
            return false;
        }
    }

    /**
     * Creates or migrates this database's schema, and any state derived from
     * the open connection. Called by {@link #initialize()} once the connection
     * is open; a failure closes the database again.
     *
     * @throws SQLException when the schema cannot be prepared
     */
    protected abstract void prepare() throws SQLException;

    /** Returns whether the database is open. */
    public boolean isOpen() {
        return connection != null;
    }

    /** Returns the active connection, or {@code null} when the database is not open. */
    protected Connection connection() {
        return connection;
    }

    /** Returns the path to the database file (without extension). */
    protected String getDbPath() {
        return dbPath;
    }

    /** Shuts the database down and closes the connection. A no-op when it is not open. */
    public synchronized void close() {
        if (connection == null) return;
        try {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SHUTDOWN");
            }
            connection.close();
            log("Closed database " + dbPath);
        } catch (SQLException e) {
            warn("Error closing database " + dbPath + ": " + e.getMessage());
        }
        connection = null;
    }

    /** Closes a connection whose preparation failed, so a half-open database is never used. */
    private void closeConnectionQuietly() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            warn("Error closing database " + dbPath + ": " + e.getMessage());
        }
        connection = null;
    }
}
