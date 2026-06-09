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

package io.flowtree.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Owns the retrospective ("reflection") phase of a {@link CodingAgentJob}.
 *
 * <p>The retrospective phase runs after all enforcement rules have completed.
 * A single agent session analyzes the primary phase transcript for tool-use
 * and context-efficiency improvements, emitting findings as memories rather
 * than code changes — so it cannot trigger enforcement re-entry even when
 * {@link CodingAgentJob#hasAgentCommitted()} returns {@code false}.</p>
 *
 * <p>The collaborator carries the per-run telemetry counters
 * ({@link #ran()}, {@link #transcriptFound()}, {@link #findingsCount()},
 * {@link #contextUpfrontTokenEstimate()},
 * {@link #contextPressureEvents()}, {@link #costUsd()}) so
 * {@link CodingAgentJob} itself does not need to declare a separate field
 * for each. Reset each {@link CodingAgentJob#doWork()} call via
 * {@link #reset()}.</p>
 */
final class RetrospectivePhase {

    /** Result file written by the retrospective agent. */
    static final String RESULTS_FILE = "retrospective-results.json";

    /** {@code true} after {@link #run(CodingAgentJob)} has executed in this {@link CodingAgentJob#doWork()} call. */
    private boolean ran;

    /** {@code true} when the retrospective agent found and analyzed a primary-phase transcript. */
    private boolean transcriptFound;

    /** Number of improvement findings emitted as memories by the retrospective agent. */
    private int findingsCount;

    /**
     * Estimated token cost of the system prompt + standing instructions +
     * job prompt consumed before the primary agent acted. Reported by the
     * retrospective agent from the initial transcript messages; 0 when not
     * reported (e.g. no transcript available).
     */
    private int contextUpfrontTokenEstimate;

    /**
     * Number of times during the primary session the agent had to summarize,
     * compact, or otherwise dispose of earlier context. Counted by the
     * retrospective agent from the transcript's compaction events; 0 when
     * not reported.
     */
    private int contextPressureEvents;

    /** Cost (USD) of the retrospective session alone; computed as the delta in costByModel. */
    private double costUsd;

    /** Returns {@code true} once the retrospective session has executed for the current job run. */
    boolean ran() { return ran; }

    /** Returns {@code true} when the retrospective agent found and analyzed a transcript. */
    boolean transcriptFound() { return transcriptFound; }

    /** Returns the number of improvement findings emitted by the retrospective agent. */
    int findingsCount() { return findingsCount; }

    /**
     * Returns the agent's ballpark token estimate of the upfront context
     * cost (system prompt + standing instructions + job prompt) consumed
     * before the primary agent acted. 0 when not reported.
     */
    int contextUpfrontTokenEstimate() { return contextUpfrontTokenEstimate; }

    /**
     * Returns the number of times the primary agent had to compact or
     * summarize context mid-session. 0 when not reported.
     */
    int contextPressureEvents() { return contextPressureEvents; }

    /** Returns the USD cost of the retrospective session in isolation. */
    double costUsd() { return costUsd; }

    /** Resets all per-run counters; called at the top of {@link CodingAgentJob#doWork()}. */
    void reset() {
        ran = false;
        transcriptFound = false;
        findingsCount = 0;
        contextUpfrontTokenEstimate = 0;
        contextPressureEvents = 0;
        costUsd = 0.0;
    }

    /**
     * Runs the retrospective phase for {@code job}: snapshots {@code prompt}
     * and {@code currentActivity}, swaps in the retrospective prompt, dispatches
     * a single agent session through {@link CodingAgentJob#executeSingleRun()},
     * computes the session's isolated cost, and restores the prior state in
     * {@code finally}.
     *
     * <p>If {@code commit.txt} existed at entry but was removed during the
     * session (and the retrospective agent did not write its own), it is
     * restored from the snapshot so the primary commit message survives.</p>
     *
     * @param job the orchestrator owning the phase configuration and session state
     */
    void run(CodingAgentJob job) {
        String originalPrompt = job.getPrompt();
        String previousActivity = job.getCurrentActivity();
        job.setCurrentActivity(Phase.RETROSPECTIVE.wireName());
        Path commitFile = job.resolveWorkingPath("commit.txt");
        String savedMsg = (commitFile != null && Files.exists(commitFile))
                ? readStringSafely(commitFile, job::warn) : null;
        PhaseConfig retroConfig = job.resolveEffectivePhaseConfig(Phase.RETROSPECTIVE);
        double costBefore = job.getCostForModel(retroConfig.toModelKey());

        reset();

        try {
            job.setPrompt(RetrospectivePromptBuilder.build(job));
            ran = true;
            job.executeSingleRun();
            costUsd = Math.max(0.0, job.getCostForModel(retroConfig.toModelKey()) - costBefore);
            readResults(job);
        } finally {
            job.setPrompt(originalPrompt);
            job.setCurrentActivity(previousActivity);
            if (commitFile != null && !Files.exists(commitFile) && savedMsg != null) {
                writeStringSafely(commitFile, savedMsg, job::warn);
                job.log("Restored commit message from commit.txt after retrospective phase");
            }
        }
    }

    /**
     * Reads the retrospective agent's structured result file, populating
     * {@link #transcriptFound}, {@link #findingsCount},
     * {@link #contextUpfrontTokenEstimate}, and
     * {@link #contextPressureEvents}. Silent on absence or parse failure —
     * the agent may legitimately exit without writing a result file (e.g.
     * no transcript available). The two context-* fields are missing-tolerant
     * so a result file written by an older prompt version (which only
     * reported transcriptFound / findingsCount) still parses cleanly; the
     * new fields just default to 0 in that case.
     *
     * @param job the orchestrator used to resolve the working-directory path
     */
    private void readResults(CodingAgentJob job) {
        Path resultsFile = job.resolveWorkingPath(RESULTS_FILE);
        if (resultsFile == null || !Files.exists(resultsFile)) return;
        try {
            String json = Files.readString(resultsFile, StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(json);
            transcriptFound = node.has("transcriptFound") && node.get("transcriptFound").asBoolean();
            findingsCount = node.has("findingsCount") ? node.get("findingsCount").asInt() : 0;
            contextUpfrontTokenEstimate = node.has("contextUpfrontTokenEstimate")
                    ? node.get("contextUpfrontTokenEstimate").asInt() : 0;
            contextPressureEvents = node.has("contextPressureEvents")
                    ? node.get("contextPressureEvents").asInt() : 0;
        } catch (Exception e) {
            job.warn("Could not read " + RESULTS_FILE + ": " + e.getMessage());
        }
    }

    /** Reads {@code path} as a UTF-8 string; logs to {@code warn} and returns {@code null} on error. */
    private static String readStringSafely(Path path, Consumer<String> warn) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn.accept("Could not read commit.txt: " + e.getMessage());
            return null;
        }
    }

    /** Writes {@code content} to {@code path} as UTF-8; logs to {@code warn} on error. */
    private static void writeStringSafely(Path path, String content, Consumer<String> warn) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn.accept("Could not restore commit.txt: " + e.getMessage());
        }
    }
}
