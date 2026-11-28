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
import java.sql.SQLException;
import java.util.Map;

/**
 * Writes the per-job breakdown side tables — {@code job_runner_cost},
 * {@code job_model_cost} and {@code job_token} — each keyed by {@code job_id}
 * and joined back to {@code job_timing} by the aggregators.
 *
 * <p>Extracted from {@link JobStatsStore} as the cohesive write counterpart to
 * {@link WeeklyStatsAggregator}/{@link WindowStatsAggregator}'s reads: the store
 * owns the connection lifecycle and the {@code job_timing} row; the breakdown
 * tables, which arrived after that row and share a replace-on-completion idiom,
 * are recorded here. The store delegates its {@code recordRunnerCosts},
 * {@code recordModelCosts} and {@code recordTokens} methods to this class,
 * which it recreates whenever its connection changes.</p>
 *
 * @author Michael Murray
 * @see JobStatsStore
 */
class JobBreakdownRecorder implements ConsoleFeatures {

    /** The connection to the backing HSQLDB database, owned by {@link JobStatsStore}. */
    private final Connection connection;

    /**
     * Creates a recorder that writes through {@code connection}.
     *
     * @param connection the open connection to the job-timing database
     */
    JobBreakdownRecorder(Connection connection) {
        this.connection = connection;
    }

    /**
     * Records the per-runner cost breakdown for a job, replacing any existing
     * rows so a re-reported completion event is idempotent.
     *
     * @param jobId        the job identifier
     * @param costByRunner map of runner name to cumulative USD cost; {@code null}
     *                     or empty clears any existing rows for the job
     */
    void recordRunnerCosts(String jobId, Map<String, Double> costByRunner) {
        recordCostsInTable(jobId, "job_runner_cost", "runner", 64, costByRunner);
    }

    /**
     * Records the per-model cost breakdown for a job, replacing any existing
     * rows so a re-reported completion event is idempotent.
     *
     * @param jobId       the job identifier
     * @param costByModel map of provider/model identifier to cumulative USD cost;
     *                    {@code null} or empty clears any existing rows for the job
     */
    void recordModelCosts(String jobId, Map<String, Double> costByModel) {
        recordCostsInTable(jobId, "job_model_cost", "model", 128, costByModel);
    }

    /**
     * Shared implementation for {@link #recordRunnerCosts} and
     * {@link #recordModelCosts}. Deletes existing rows for the job in
     * {@code table}, then inserts one row per entry, truncating the key to
     * {@code maxKeyLen} characters.
     *
     * @param jobId     the job identifier
     * @param table     the cost table to write
     * @param keyColumn the key column name ({@code runner} or {@code model})
     * @param maxKeyLen the maximum key length to retain
     * @param costs     the cost breakdown; {@code null} or empty clears the job's rows
     */
    private void recordCostsInTable(String jobId, String table, String keyColumn,
                                    int maxKeyLen, Map<String, Double> costs) {
        if (jobId == null) return;

        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE job_id = ?")) {
            del.setString(1, jobId);
            del.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to clear costs for job " + jobId + " in " + table + ": " + e.getMessage());
            return;
        }

        if (costs == null || costs.isEmpty()) return;

        String sql = "INSERT INTO " + table + " (job_id, " + keyColumn + ", cost_usd) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Map.Entry<String, Double> entry : costs.entrySet()) {
                if (entry.getKey() == null) continue;
                ps.setString(1, jobId);
                ps.setString(2, JobStatsStore.truncate(entry.getKey(), maxKeyLen));
                ps.setDouble(3, entry.getValue() != null ? entry.getValue() : 0.0);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            warn("Failed to record costs for job " + jobId + " in " + table + ": " + e.getMessage());
        }
    }

    /**
     * Records the token counts for a job, replacing any existing row so a
     * re-reported completion event is idempotent. A job whose counts are all
     * zero (no tokens captured, as for a runner that does not report them) gets
     * no row, so the retrospective's token coverage stays honest.
     *
     * @param jobId       the job identifier
     * @param input       input tokens
     * @param output      output tokens
     * @param cacheRead   cache-read tokens
     * @param cacheWrite  cache-write tokens
     */
    void recordTokens(String jobId, long input, long output, long cacheRead, long cacheWrite) {
        if (jobId == null) return;

        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM job_token WHERE job_id = ?")) {
            del.setString(1, jobId);
            del.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to clear tokens for job " + jobId + ": " + e.getMessage());
            return;
        }

        if (input == 0 && output == 0 && cacheRead == 0 && cacheWrite == 0) return;

        String sql = "INSERT INTO job_token (job_id, input_tokens, output_tokens, "
            + "cache_read_tokens, cache_write_tokens) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, JobStatsStore.truncate(jobId, 255));
            ps.setLong(2, input);
            ps.setLong(3, output);
            ps.setLong(4, cacheRead);
            ps.setLong(5, cacheWrite);
            ps.executeUpdate();
        } catch (SQLException e) {
            warn("Failed to record tokens for job " + jobId + ": " + e.getMessage());
        }
    }
}
