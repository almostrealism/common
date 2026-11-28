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

import io.flowtree.JsonFieldExtractor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The throughput retrospective over a single window: how much happened between
 * {@link #from} and {@link #to}.
 *
 * <p>Deliberately a sibling of {@link WeeklyStats} rather than a generalization
 * of it, so the Slack {@code /flowtree stats} path that {@code WeeklyStats}
 * serves is not destabilized. Counting follows the plan's §2.4 rules, applied
 * by {@link WindowStatsAggregator}: a job's <em>outcome, spend, tokens and
 * turns</em> are counted in the window that contains its {@code completed_at},
 * while its <em>time</em> ({@link #agentWallClockMs}) is the overlap of its run
 * with the window, so per-window busy time sums back to total busy time.</p>
 *
 * <p>Tokens are {@code null} ("not captured") for any window whose finished
 * jobs predate token capture; {@link #finishedWithTokens} against
 * {@link #finished} is the honest coverage fraction.</p>
 *
 * @author Michael Murray
 * @see WindowStatsAggregator
 * @see WeeklyStats
 */
public class WindowStats {

    /** Inclusive window start. */
    public Instant from;

    /** Exclusive window end. */
    public Instant to;

    /** Jobs whose submission landed in the window ({@code submitted_at} in range). */
    public int submitted;

    /** Jobs that began running in the window ({@code started_at} in range, not {@code SUBMITTED}). */
    public int started;

    /** Jobs that finished in the window ({@code completed_at} in range). */
    public int finished;

    /** Of {@link #finished}, those with a {@code SUCCESS} status. */
    public int success;

    /** Of {@link #finished}, those with a {@code FAILED} status. */
    public int failed;

    /** Of {@link #finished}, those with a {@code DEGRADED} status. */
    public int degraded;

    /** Of {@link #finished}, those with a {@code CANCELLED} status. */
    public int cancelled;

    /** Of {@link #finished}, those with a {@code BLOCKED} status. */
    public int blocked;

    /** Submitted-in-window jobs that waited past the lost threshold and never started. */
    public int lost;

    /** Jobs still running (no completion) that began before the window end. */
    public int running;

    /** Of {@link #running}, those silent past the silence threshold. */
    public int unresponsive;

    /** Summed overlap of each job's run with the window, in milliseconds (plan §2.4). */
    public long agentWallClockMs;

    /** Total spend of jobs finished in the window, in USD. */
    public double totalCostUsd;

    /** Agentic turns summed over jobs finished in the window. */
    public long totalTurns;

    /** Spend by runner over the window; may be empty. */
    public Map<String, Double> costByRunner = new LinkedHashMap<>();

    /** Spend by model over the window; may be empty. */
    public Map<String, Double> costByModel = new LinkedHashMap<>();

    /** Finished-job count by {@link JobKind} name; may be empty. */
    public Map<String, Long> countByKind = new LinkedHashMap<>();

    /** Spend by {@link JobKind} name; may be empty. */
    public Map<String, Double> costByKind = new LinkedHashMap<>();

    /** Finished-job count by workstream; may be empty. */
    public Map<String, Long> finishedByWorkstream = new LinkedHashMap<>();

    /** Summed busy overlap by worker node, in milliseconds; may be empty. */
    public Map<String, Long> busyMsByNode = new LinkedHashMap<>();

    /** Finished jobs in the window that had a persisted token row. */
    public int finishedWithTokens;

    /** Summed input tokens, or {@code null} when no finished job had a token row. */
    public Long inputTokens;

    /** Summed output tokens, or {@code null} when no finished job had a token row. */
    public Long outputTokens;

    /** Summed cache-read tokens, or {@code null} when no finished job had a token row. */
    public Long cacheReadTokens;

    /** Summed cache-write tokens, or {@code null} when no finished job had a token row. */
    public Long cacheWriteTokens;

    /**
     * Returns the token-coverage label for the window: {@code "full"} when every
     * finished job had a token row, {@code "none"} when none did (the measure is
     * {@code null}), and {@code "partial:<n>/<m>"} otherwise.
     *
     * @return the coverage label
     */
    public String tokenCoverage() {
        if (finished == 0 || finishedWithTokens == 0) return "none";
        if (finishedWithTokens >= finished) return "full";
        return "partial:" + finishedWithTokens + "/" + finished;
    }

    /**
     * Renders this retrospective as a JSON object.
     *
     * @return the JSON object string
     */
    public String toJson() {
        StringBuilder json = new StringBuilder("{");
        json.append("\"from\":\"").append(from).append("\"");
        json.append(",\"to\":\"").append(to).append("\"");
        json.append(",\"outcomes\":{");
        json.append("\"submitted\":").append(submitted);
        json.append(",\"started\":").append(started);
        json.append(",\"finished\":").append(finished);
        json.append(",\"success\":").append(success);
        json.append(",\"failed\":").append(failed);
        json.append(",\"degraded\":").append(degraded);
        json.append(",\"cancelled\":").append(cancelled);
        json.append(",\"blocked\":").append(blocked);
        json.append(",\"lost\":").append(lost);
        json.append(",\"running\":").append(running);
        json.append(",\"unresponsive\":").append(unresponsive);
        json.append("}");
        json.append(",\"agentWallClockMs\":").append(agentWallClockMs);
        json.append(",\"totalCostUsd\":").append(totalCostUsd);
        json.append(",\"totalTurns\":").append(totalTurns);
        json.append(",\"tokens\":{");
        json.append("\"coverage\":\"").append(tokenCoverage()).append("\"");
        appendNullableLong(json, "input", inputTokens);
        appendNullableLong(json, "output", outputTokens);
        appendNullableLong(json, "cacheRead", cacheReadTokens);
        appendNullableLong(json, "cacheWrite", cacheWriteTokens);
        json.append("}");
        JsonFieldExtractor.appendDoubleMapJson(json, "costByRunner", costByRunner);
        JsonFieldExtractor.appendDoubleMapJson(json, "costByModel", costByModel);
        JsonFieldExtractor.appendDoubleMapJson(json, "costByKind", costByKind);
        JsonFieldExtractor.appendLongMapJson(json, "countByKind", countByKind);
        JsonFieldExtractor.appendLongMapJson(json, "finishedByWorkstream", finishedByWorkstream);
        JsonFieldExtractor.appendLongMapJson(json, "busyMsByNode", busyMsByNode);
        return json.append("}").toString();
    }

    /**
     * Appends a nullable long member, rendering {@code null} as JSON null so a
     * "not captured" token measure is distinguishable from a zero.
     *
     * @param json  the builder to append to
     * @param name  the member name
     * @param value the value, or {@code null}
     */
    private static void appendNullableLong(StringBuilder json, String name, Long value) {
        json.append(",\"").append(name).append("\":").append(value == null ? "null" : value);
    }

}
