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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Scans the ar-test-runner ``runs/`` directory for test runs that the agent
 * abandoned during a Claude Code session.
 *
 * <p>An "abandoned" run is one whose ``metadata.json`` was created within the
 * session window AND whose ``status`` is still non-terminal (one of
 * ``running``, ``pending``, or ``abandoned``) when the session ends. Those
 * runs were started by the agent but never polled to completion — the most
 * common cause is an agent that called ``start_test_run`` and ended its turn
 * before polling ``get_run_status`` to a terminal state, in which case the
 * ar-test-runner subprocess is killed by the harness as claude exits.</p>
 *
 * <p>The result feeds {@link ClaudeCodeJob#createEvent} which produces a
 * {@link JobCompletionEvent.Status#DEGRADED} event when the list is non-empty,
 * so the developer can tell that the job's clean exit hides incomplete work.</p>
 *
 * @author Michael Murray
 */
public final class AbandonedTestRunDetector {

    /**
     * Default location of the ar-test-runner runs directory, relative to the
     * project root: {@code tools/mcp/test-runner/runs}.
     */
    public static final String DEFAULT_RUNS_SUBPATH = "tools/mcp/test-runner/runs";

    /**
     * Statuses recorded in ``metadata.json`` that are considered non-terminal
     * for the purposes of detecting abandoned runs. ``abandoned`` (set by the
     * test-runner's atexit handler) is included so that runs the test-runner
     * itself flagged as abandoned still count toward the DEGRADED tally.
     */
    private static final Set<String> NON_TERMINAL_STATUSES =
            Set.of("running", "pending", "abandoned");

    /** Jackson mapper used to read each run's ``metadata.json``. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Static-only utility; instances are not meaningful. */
    private AbandonedTestRunDetector() {
    }

    /**
     * Convenience that resolves the test-runner ``runs/`` directory relative
     * to {@code workingDirectory} (or {@code user.dir} if null) and scans it.
     *
     * @param workingDirectory the project working directory, or null
     * @param sessionStart     the moment the agent session began
     * @return immutable list of abandoned run IDs, never {@code null}
     */
    public static List<String> findAbandonedRunsForJob(String workingDirectory,
                                                       Instant sessionStart) {
        Path baseDir = workingDirectory != null
            ? Path.of(workingDirectory)
            : Path.of(System.getProperty("user.dir"));
        return findAbandonedRuns(baseDir.resolve(DEFAULT_RUNS_SUBPATH), sessionStart);
    }

    /**
     * Scans {@code runsDir} for runs started after {@code sessionStart} that
     * are still in a non-terminal status, and returns their run IDs.
     *
     * <p>Runs whose metadata cannot be parsed are skipped silently — there is
     * no useful action the caller can take in that case, and a malformed
     * file should not block the parent job's completion event.</p>
     *
     * @param runsDir      the test-runner ``runs`` directory; if absent or
     *                     not a directory, the result is empty
     * @param sessionStart the moment the Claude session began; runs whose
     *                     ``started_at`` predates this are ignored. May be
     *                     {@code null} to disable the time filter (used in
     *                     tests).
     * @return immutable list of abandoned run IDs, never {@code null}
     */
    public static List<String> findAbandonedRuns(Path runsDir, Instant sessionStart) {
        if (runsDir == null || !Files.isDirectory(runsDir)) {
            return Collections.emptyList();
        }
        List<String> abandoned = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runsDir)) {
            for (Path runDir : stream) {
                if (!Files.isDirectory(runDir)) continue;
                Path metadataPath = runDir.resolve("metadata.json");
                if (!Files.isRegularFile(metadataPath)) continue;
                if (isAbandoned(metadataPath, sessionStart)) {
                    abandoned.add(runDir.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
            // Best-effort: a transient I/O failure should not crash the job's
            // completion event. Return whatever we collected so far.
        }
        Collections.sort(abandoned);
        return Collections.unmodifiableList(abandoned);
    }

    /**
     * Returns whether the metadata file represents an abandoned run.
     */
    private static boolean isAbandoned(Path metadataPath, Instant sessionStart) {
        JsonNode root;
        try {
            root = MAPPER.readTree(metadataPath.toFile());
        } catch (IOException unparseable) {
            return false;
        }
        String status = ClaudeCodeJob.getTextOrNull(root, "status");
        if (!NON_TERMINAL_STATUSES.contains(status)) return false;
        if (sessionStart != null) {
            Instant startedAt = parseStartedAt(ClaudeCodeJob.getTextOrNull(root, "started_at"));
            if (startedAt == null) return false;
            if (startedAt.isBefore(sessionStart)) return false;
        }
        return true;
    }

    /**
     * Parses a ``started_at`` value from ``metadata.json``. The test-runner
     * writes {@link LocalDateTime#now()}.toString() (no zone), so we attach
     * the system zone when converting to {@link Instant}.
     */
    private static Instant parseStartedAt(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fall through to attempt an Instant parse below.
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
