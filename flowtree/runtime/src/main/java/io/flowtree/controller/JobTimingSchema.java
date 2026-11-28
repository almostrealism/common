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

/**
 * The DDL for the {@link JobStatsStore} database: the {@code job_timing} table
 * and its index, the per-job {@code job_runner_cost}, {@code job_model_cost} and
 * {@code job_token} side tables, the additive column migrations applied to
 * existing databases, and the orphan-cleanup statement.
 *
 * <p>Collected here so the schema a reader needs to understand the store lives
 * in one place rather than interleaved with the store's logic. {@link JobStatsStore}
 * executes these at {@link JobStatsStore#prepare()} time; the aggregators and the
 * {@link JobBreakdownRecorder} read and write the tables they define.</p>
 *
 * @author Michael Murray
 * @see JobStatsStore
 */
final class JobTimingSchema {

    /** DDL statement that creates the {@code job_timing} table if it does not yet exist. */
    static final String CREATE_TABLE = ""
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
        + "    error_message  VARCHAR(2000),"
        + "    blocked_category VARCHAR(32),"
        + "    blocked_detail VARCHAR(2000),"
        + "    blocked_verified BOOLEAN,"
        + "    trigger_reason VARCHAR(64) DEFAULT 'manual',"
        + "    heartbeat_at   TIMESTAMP,"
        + "    phase          VARCHAR(64),"
        + "    submitted_at   TIMESTAMP,"
        + "    submitted_by   VARCHAR(255),"
        + "    required_labels VARCHAR(2000),"
        + "    node           VARCHAR(255)"
        + ")";

    /** DDL statement that creates an index on {@code (workstream_id, started_at)} for efficient queries. */
    static final String CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_timing_ws_started ON job_timing (workstream_id, started_at)";

    /**
     * DDL for the per-runner cost table. One row per (job, runner) pair holds
     * the cumulative USD cost that runner accrued across all of the job's phase
     * invocations. Joined back to {@code job_timing} on {@code job_id} for the
     * time-window and workstream filters used by the aggregation queries.
     */
    static final String CREATE_RUNNER_COST_TABLE = ""
        + "CREATE TABLE IF NOT EXISTS job_runner_cost ("
        + "    job_id   VARCHAR(255) NOT NULL,"
        + "    runner   VARCHAR(64)  NOT NULL,"
        + "    cost_usd DOUBLE,"
        + "    PRIMARY KEY (job_id, runner)"
        + ")";

    /**
     * Table storing per-model cost breakdowns per job. Mirrors the structure
     * of {@code job_runner_cost} but keyed by provider/model identifier
     * (e.g. {@code "openrouter/claude-opus-5-5"}) instead of runner name.
     */
    static final String CREATE_MODEL_COST_TABLE = ""
        + "CREATE TABLE IF NOT EXISTS job_model_cost ("
        + "    job_id   VARCHAR(255) NOT NULL,"
        + "    model    VARCHAR(128)  NOT NULL,"
        + "    cost_usd DOUBLE,"
        + "    PRIMARY KEY (job_id, model)"
        + ")";

    /**
     * Table storing per-job token counts, keyed by {@code job_id}. One row per
     * job, written on completion by {@link JobBreakdownRecorder#recordTokens}.
     * Separate from {@code job_timing} because token capture arrived after that
     * table and because — like the cost tables — it joins back on {@code job_id}
     * for the window aggregation. A job with no captured tokens has no row here,
     * which is what lets the retrospective report token coverage honestly.
     */
    static final String CREATE_TOKEN_TABLE = ""
        + "CREATE TABLE IF NOT EXISTS job_token ("
        + "    job_id             VARCHAR(255) PRIMARY KEY,"
        + "    input_tokens       BIGINT,"
        + "    output_tokens      BIGINT,"
        + "    cache_read_tokens  BIGINT,"
        + "    cache_write_tokens BIGINT"
        + ")";

    /**
     * ALTER TABLE statements to add new columns to existing databases, applied
     * by {@link ControllerDatabase#addColumns}, which ignores the ones whose
     * column already exists.
     */
    static final String[] MIGRATIONS = {
        "ALTER TABLE job_timing ADD COLUMN subtype VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN session_error BOOLEAN",
        "ALTER TABLE job_timing ADD COLUMN permission_denials INTEGER",
        "ALTER TABLE job_timing ADD COLUMN target_branch VARCHAR(255)",
        "ALTER TABLE job_timing ADD COLUMN commit_hash VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN pull_request_url VARCHAR(1000)",
        "ALTER TABLE job_timing ADD COLUMN error_message VARCHAR(2000)",
        "ALTER TABLE job_timing ADD COLUMN blocked_category VARCHAR(32)",
        "ALTER TABLE job_timing ADD COLUMN blocked_detail VARCHAR(2000)",
        "ALTER TABLE job_timing ADD COLUMN blocked_verified BOOLEAN",
        "ALTER TABLE job_timing ADD COLUMN slack_message_ts VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN trigger_reason VARCHAR(64) DEFAULT 'manual'",
        "ALTER TABLE job_timing ADD COLUMN heartbeat_at TIMESTAMP",
        "ALTER TABLE job_timing ADD COLUMN phase VARCHAR(64)",
        "ALTER TABLE job_timing ADD COLUMN submitted_at TIMESTAMP",
        "ALTER TABLE job_timing ADD COLUMN submitted_by VARCHAR(255)",
        "ALTER TABLE job_timing ADD COLUMN required_labels VARCHAR(2000)",
        "ALTER TABLE job_timing ADD COLUMN node VARCHAR(255)"
    };

    /** DML statement that removes stale {@code STARTED} rows older than a given cutoff timestamp. */
    static final String CLEAN_ORPHANS =
        "DELETE FROM job_timing WHERE status = 'STARTED' AND started_at < ?";

    /** Not instantiable; a holder for DDL constants. */
    private JobTimingSchema() {
    }
}
