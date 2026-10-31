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

import org.almostrealism.util.SmsOptOutRegistry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * The durable record of which phone numbers have opted out of SMS, backed by
 * the controller's embedded database so the record survives restarts.
 *
 * <p>Opt-out is a carrier compliance obligation that outlives any one process:
 * a number that texted STOP last month must still be suppressed today. Keeping
 * it in the same {@link ControllerDatabase} family as the job statistics means
 * it lives in the controller data directory and is backed up with the rest of
 * the configuration, rather than in a datastore invented for this one purpose.</p>
 *
 * <p>When the database could not be opened the store fails safe in the strict
 * direction: {@link #isOptedOut(String)} returns {@code false} so a storage
 * fault never silences legitimate alerts, and the mutations log and do nothing.
 * A fault therefore degrades to "no opt-outs recorded", never to "everything
 * suppressed".</p>
 *
 * @author Michael Murray
 * @see SmsOptOutRegistry
 */
public class SmsOptOutStore extends ControllerDatabase implements SmsOptOutRegistry {

    /** DDL creating the opt-out table keyed by the number in E.164 form. */
    private static final String CREATE_TABLE = ""
            + "CREATE TABLE IF NOT EXISTS sms_opt_out ("
            + "    phone_number VARCHAR(32) PRIMARY KEY,"
            + "    opted_out_at TIMESTAMP NOT NULL"
            + ")";

    /**
     * Creates a store backed by the HSQLDB file at the given path.
     *
     * @param dbPath path to the database file without extension, normally
     *               {@code sms-optout} within the controller data directory
     */
    public SmsOptOutStore(String dbPath) {
        super(dbPath);
    }

    @Override
    protected void prepare() throws SQLException {
        try (Statement stmt = connection().createStatement()) {
            stmt.execute(CREATE_TABLE);
        }
    }

    @Override
    public boolean isOptedOut(String number) {
        String key = canonical(number);
        if (key == null || connection() == null) return false;

        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT 1 FROM sms_opt_out WHERE phone_number = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            warn("Failed to read opt-out state for a number: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void optOut(String number) {
        String key = canonical(number);
        if (key == null || connection() == null) {
            if (key != null) warn("Opt-out store is not open; STOP not persisted");
            return;
        }

        try (PreparedStatement ps = connection().prepareStatement(
                "MERGE INTO sms_opt_out USING (VALUES(?, ?)) "
                        + "AS v(phone_number, opted_out_at) "
                        + "ON sms_opt_out.phone_number = v.phone_number "
                        + "WHEN NOT MATCHED THEN INSERT VALUES v.phone_number, v.opted_out_at")) {
            ps.setString(1, key);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            log("Recorded SMS opt-out");
        } catch (SQLException e) {
            warn("Failed to record opt-out: " + e.getMessage());
        }
    }

    @Override
    public void optIn(String number) {
        String key = canonical(number);
        if (key == null || connection() == null) {
            if (key != null) warn("Opt-out store is not open; START not persisted");
            return;
        }

        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM sms_opt_out WHERE phone_number = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
            log("Cleared SMS opt-out");
        } catch (SQLException e) {
            warn("Failed to clear opt-out: " + e.getMessage());
        }
    }

    /**
     * Reduces a number to the canonical form used as the table key, so an
     * inbound {@code "+1 833 555 1234"} and a configured {@code "+18335551234"}
     * match. Whitespace and the common grouping punctuation are removed.
     *
     * @param number the number as supplied, or {@code null}
     * @return the canonical form, or {@code null} when blank
     */
    private static String canonical(String number) {
        if (number == null) return null;

        String trimmed = number.replaceAll("[\\s()\\-.]", "");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
