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

package io.flowtree.jobs.agent;

import static io.flowtree.JsonFieldExtractor.MAPPER;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Writes a structured JSONL transcript file for a single OpenCode session.
 *
 * <p>The transcript is a JSONL file containing three sections:</p>
 * <ol>
 *   <li>A <em>header</em> line: JSON object of type {@code transcript_header}
 *       containing session context — job ID, workstream ID, phase, model,
 *       provider, opencode version, working directory, start timestamp, and
 *       the full instruction prompt. This line is always well-formed JSON
 *       synthesised by the runner.</li>
 *   <li>The <em>event stream</em>: the raw NDJSON emitted by opencode on
 *       stdout, reproduced line by line (blank lines are dropped). For forensic
 *       purposes this is the most valuable section: it contains {@code step_start}
 *       events at each turn boundary, {@code text} events with the model's
 *       actual response text, {@code tool_use} and {@code tool_result} events
 *       for every tool invocation, and {@code error} events. The stream is
 *       reproduced without reinterpretation so that corruption, truncation,
 *       or unexpected output shapes are visible exactly as opencode produced
 *       them.</li>
 *   <li>A <em>footer</em> line: JSON object of type {@code transcript_footer}
 *       with outcome metrics — exit code, inactivity-kill flag, stop reason,
 *       session error flag, turn count, cost, duration, denied tool names,
 *       and end timestamp.</li>
 * </ol>
 *
 * <h2>Transcript directory resolution</h2>
 * <ol>
 *   <li>{@value #ENV_TRANSCRIPT_DIR} environment variable (absolute path recommended;
 *       relative paths are accepted and resolved against the JVM working directory).</li>
 *   <li>{@value #WELL_KNOWN_TRANSCRIPT_DIR}, when that directory exists. This is the
 *       conventional mount point for a durable, <em>per-agent</em> transcript volume
 *       bound into the agent container. Its mere presence signals that the operator
 *       has deliberately provisioned a persistent sink, so it is preferred over the
 *       ephemeral output-capture and {@code /tmp} fallbacks. Cross-agent isolation of
 *       this mount (each agent container binding a distinct host directory) is the
 *       responsibility of the compose configuration and is enforced in CI by
 *       {@code tools/ci/validate_agent_volume_isolation.py} — not by this class.</li>
 *   <li>When {@link AgentRunRequest#getOutputCapturePath()} is set:
 *       {@code <capture-parent>/transcripts/}.</li>
 *   <li>Fallback: {@value #DEFAULT_TRANSCRIPT_DIR}.</li>
 * </ol>
 *
 * <h2>File naming</h2>
 * <pre>
 * {@code <yyyyMMdd-HHmmss>-<jobId>-<phase>[-<sessionId>].jsonl}
 * </pre>
 *
 * <h2>Transcript fidelity</h2>
 * <p>The fidelity of transcripts written by this class is bounded by what
 * opencode emits on stdout. When opencode runs with {@code --format json},
 * it streams an NDJSON event per turn/tool/message; all of that is captured
 * here. Limitations:</p>
 * <ul>
 *   <li>opencode does not emit per-tool-call timing — only step-level
 *       boundaries are visible.</li>
 *   <li>The model's raw token stream is not emitted; only the final assembled
 *       text of each message is present in {@code text} events.</li>
 *   <li>Internal opencode state (retry logic, caching decisions) is opaque.</li>
 *   <li>Partial or corrupted NDJSON produced by a misbehaving model or an
 *       opencode bug is preserved verbatim in the event-stream section, which
 *       is exactly what the forensic use-case requires.</li>
 * </ul>
 *
 * @author Michael Murray
 */
final class OpencodeTranscriptWriter {

    /** Format version recorded in every header; increment on incompatible schema changes. */
    static final int FORMAT_VERSION = 1;

    /** Fallback transcript directory when no other location is resolvable. */
    static final String DEFAULT_TRANSCRIPT_DIR = "/tmp/opencode-transcripts";

    /**
     * Well-known mount point for a durable, per-agent transcript volume. When this
     * directory exists it is preferred over the ephemeral output-capture and
     * {@code /tmp} fallbacks (but not over an explicit {@value #ENV_TRANSCRIPT_DIR}
     * override). The agent {@code docker-compose.yml} binds a distinct host directory
     * to this path for each agent container; that per-agent isolation is verified in
     * CI, not here.
     */
    static final String WELL_KNOWN_TRANSCRIPT_DIR = "/agent-transcripts";

    /** Environment variable that overrides the transcript directory. */
    static final String ENV_TRANSCRIPT_DIR = "OPENCODE_TRANSCRIPT_DIR";

    /** UTC timestamp formatter used to build the filename prefix. */
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** The directory in which transcript files are written. */
    private final Path transcriptDir;

    /**
     * Constructs a writer that stores transcripts in {@code transcriptDir}.
     * The directory is created on first write if it does not exist.
     *
     * @param transcriptDir the target directory
     */
    OpencodeTranscriptWriter(Path transcriptDir) {
        if (transcriptDir == null) throw new IllegalArgumentException("transcriptDir must not be null");
        this.transcriptDir = transcriptDir;
    }

    /**
     * Creates an {@link OpencodeTranscriptWriter} by resolving the transcript
     * directory from the JVM environment and the request context.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@value #ENV_TRANSCRIPT_DIR} JVM environment variable</li>
     *   <li>{@link AgentRunRequest#getOutputCapturePath()} parent sibling
     *       {@code transcripts/}</li>
     *   <li>{@value #DEFAULT_TRANSCRIPT_DIR}</li>
     * </ol>
     *
     * @param request the run request used for the output-capture-path fallback
     * @return a configured writer
     */
    static OpencodeTranscriptWriter forRequest(AgentRunRequest request) {
        return forRequest(request, System::getenv);
    }

    /**
     * Creates an {@link OpencodeTranscriptWriter} with an injectable environment
     * lookup. Exposed for tests that cannot set JVM environment variables. The
     * well-known directory check uses the real filesystem ({@link Files#isDirectory}).
     *
     * @param request   the run request used for the output-capture-path fallback
     * @param envLookup reads environment variables by name
     * @return a configured writer
     */
    static OpencodeTranscriptWriter forRequest(AgentRunRequest request,
                                               Function<String, String> envLookup) {
        return forRequest(request, envLookup, Files::isDirectory);
    }

    /**
     * Creates an {@link OpencodeTranscriptWriter} with both the environment lookup
     * and the directory-existence check injected. Exposed for tests so the
     * {@value #WELL_KNOWN_TRANSCRIPT_DIR} branch can be exercised deterministically
     * without depending on the JVM's actual filesystem.
     *
     * @param request     the run request used for the output-capture-path fallback
     * @param envLookup   reads environment variables by name
     * @param isDirectory reports whether a path is an existing directory
     * @return a configured writer
     */
    static OpencodeTranscriptWriter forRequest(AgentRunRequest request,
                                               Function<String, String> envLookup,
                                               Predicate<Path> isDirectory) {
        Path dir = resolveTranscriptDir(request, envLookup, isDirectory);
        return new OpencodeTranscriptWriter(dir);
    }

    /**
     * Writes a JSONL transcript for the completed session.
     *
     * <p>On any {@link IOException} the failure is logged at warn level and
     * {@code null} is returned; transcript failures never disrupt the session
     * result delivered to the orchestrator.</p>
     *
     * @param request      the original run request
     * @param result       the session result from {@link OpencodeOutputParser}
     * @param startEpochMs wall-clock start time in milliseconds since epoch
     * @param endEpochMs   wall-clock end time in milliseconds since epoch
     * @param logger       diagnostic sink
     * @return the path of the written transcript file, or {@code null} on failure
     */
    Path write(AgentRunRequest request,
               AgentRunResult result,
               long startEpochMs,
               long endEpochMs,
               ConsoleFeatures logger) {
        try {
            Files.createDirectories(transcriptDir);
            String baseName = buildFilename(request, result, startEpochMs);
            Path file = createNewTranscriptFile(baseName);
            try (BufferedWriter out = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
                out.write(buildHeader(request, result, startEpochMs));
                out.newLine();
                writeEventStream(out, result != null ? result.rawOutput() : null);
                out.write(buildFooter(result, endEpochMs));
                out.newLine();
            }
            logger.log("opencode_transcript_written=" + file);
            return file;
        } catch (IOException e) {
            logger.warn("Failed to write opencode transcript: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the configured transcript directory.
     *
     * @return the transcript directory path
     */
    Path getTranscriptDir() {
        return transcriptDir;
    }

    /**
     * Atomically creates an empty file for a new transcript, falling back to a
     * nonce-suffixed name if the base name is already taken.
     *
     * @param baseName the preferred filename (e.g. from {@link #buildFilename})
     * @return the path of the newly created (empty) file
     * @throws IOException if neither the base name nor the nonce variant can be created
     */
    private Path createNewTranscriptFile(String baseName) throws IOException {
        try {
            return Files.createFile(transcriptDir.resolve(baseName));
        } catch (FileAlreadyExistsException e) {
            String stem = baseName.endsWith(".jsonl")
                    ? baseName.substring(0, baseName.length() - 6) : baseName;
            String nonce = String.format("%04x", System.nanoTime() & 0xFFFFL);
            return Files.createFile(transcriptDir.resolve(stem + "-" + nonce + ".jsonl"));
        }
    }

    /**
     * Resolves the transcript directory, in precedence order:
     * <ol>
     *   <li>the {@value #ENV_TRANSCRIPT_DIR} environment variable, when set;</li>
     *   <li>{@value #WELL_KNOWN_TRANSCRIPT_DIR}, when {@code isDirectory} reports it
     *       exists (the durable per-agent volume mount);</li>
     *   <li>the {@code transcripts/} sibling of the request's output-capture path;</li>
     *   <li>{@value #DEFAULT_TRANSCRIPT_DIR}.</li>
     * </ol>
     *
     * @param request     the run request (may be null)
     * @param envLookup   reads environment variables by name
     * @param isDirectory reports whether a path is an existing directory
     * @return the resolved directory path
     */
    private static Path resolveTranscriptDir(AgentRunRequest request,
                                              Function<String, String> envLookup,
                                              Predicate<Path> isDirectory) {
        String envDir = envLookup.apply(ENV_TRANSCRIPT_DIR);
        if (envDir != null && !envDir.isEmpty()) {
            return Paths.get(envDir);
        }
        Path wellKnown = Paths.get(WELL_KNOWN_TRANSCRIPT_DIR);
        if (isDirectory.test(wellKnown)) {
            return wellKnown;
        }
        if (request != null) {
            Path capture = request.getOutputCapturePath();
            if (capture != null && capture.getParent() != null) {
                return capture.getParent().resolve("transcripts");
            }
        }
        return Paths.get(DEFAULT_TRANSCRIPT_DIR);
    }

    /**
     * Builds the transcript filename:
     * {@code <yyyyMMdd-HHmmss>-<jobId>-<phase>.jsonl}.
     *
     * @param request      the run request (provides job ID and phase)
     * @param result       the session result (provides session ID for uniqueness)
     * @param startEpochMs start time used for the timestamp prefix
     * @return the filename (not a full path)
     */
    static String buildFilename(AgentRunRequest request,
                                AgentRunResult result,
                                long startEpochMs) {
        String timestamp = TIMESTAMP_FMT.format(Instant.ofEpochMilli(startEpochMs));
        String jobId = sanitize(request != null ? request.getTaskId() : null, "notask");
        String phase = sanitize(request != null ? request.getActivityTag() : null, "default");
        String sessionSuffix = "";
        if (result != null && result.sessionId() != null) {
            String sid = sanitize(result.sessionId(), "");
            if (!sid.isEmpty()) {
                sessionSuffix = "-" + (sid.length() > 12 ? sid.substring(0, 12) : sid);
            }
        }
        return timestamp + "-" + jobId + "-" + phase + sessionSuffix + ".jsonl";
    }

    /**
     * Sanitizes a value for inclusion in a filename: replaces characters
     * outside {@code [A-Za-z0-9._-]} with hyphens, trims to 64 characters,
     * and returns {@code fallback} when the input is null or empty.
     *
     * @param value    the raw string
     * @param fallback returned when {@code value} is null or empty
     * @return a filesystem-safe segment
     */
    static String sanitize(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    /**
     * Builds the header JSON line ({@code "type":"transcript_header"}) containing
     * all session-context fields needed for later correlation.
     *
     * @param request      the run request
     * @param result       the session result (supplies resolved metadata)
     * @param startEpochMs wall-clock start time
     * @return a single-line JSON string
     * @throws IOException on serialization failure
     */
    private static String buildHeader(AgentRunRequest request,
                                       AgentRunResult result,
                                       long startEpochMs) throws IOException {
        ObjectNode header = MAPPER.createObjectNode();
        header.put("type", "transcript_header");
        header.put("format_version", FORMAT_VERSION);
        header.put("runner", OpencodeRunner.NAME);
        header.put("start_epoch_ms", startEpochMs);
        header.put("start_iso", Instant.ofEpochMilli(startEpochMs).toString());

        if (request != null) {
            if (request.getTaskId() != null) {
                header.put("job_id", request.getTaskId());
            }
            String wsId = request.getWorkstreamId();
            if (wsId != null) {
                header.put("workstream_id", wsId);
            }
            if (request.getActivityTag() != null) {
                header.put("phase", request.getActivityTag());
            }
            if (request.getWorkingDirectory() != null) {
                header.put("working_directory", request.getWorkingDirectory().toString());
            }
            if (request.getPrompt() != null) {
                header.put("prompt_length", request.getPrompt().length());
                header.put("prompt", request.getPrompt());
            }
        }

        if (result != null) {
            Map<String, String> meta = result.runnerMetadata();
            if (meta != null) {
                String model = meta.get("model");
                if (model != null) header.put("model", model);
                String provider = meta.get("provider");
                if (provider != null) header.put("provider", provider);
                String providerUrl = meta.get("provider_url");
                if (providerUrl != null) header.put("provider_url", providerUrl);
                String binaryVersion = meta.get("opencode_version");
                if (binaryVersion != null) header.put("opencode_version", binaryVersion);
            }
            if (result.sessionId() != null) {
                header.put("session_id", result.sessionId());
            }
        }

        return MAPPER.writeValueAsString(header);
    }

    /**
     * Writes the raw NDJSON event stream from opencode into the transcript.
     * Non-blank lines are reproduced verbatim; blank and whitespace-only lines
     * are dropped (they carry no information in NDJSON and only add noise).
     *
     * <p>No JSON parsing or validation is performed: corrupted or
     * non-JSON lines are reproduced exactly as opencode emitted them,
     * which is the correct behaviour for forensic capture.</p>
     *
     * @param out       the buffered writer to append to
     * @param rawOutput the raw stdout captured from opencode
     * @throws IOException on write failure
     */
    private static void writeEventStream(BufferedWriter out, String rawOutput) throws IOException {
        if (rawOutput == null || rawOutput.isEmpty()) return;
        String[] lines = rawOutput.split("\\r?\\n", -1);
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                out.write(line);
                out.newLine();
            }
        }
    }

    /**
     * Builds the footer JSON line ({@code "type":"transcript_footer"}) with
     * outcome metrics.
     *
     * @param result     the session result
     * @param endEpochMs wall-clock end time
     * @return a single-line JSON string
     * @throws IOException on serialization failure
     */
    private static String buildFooter(AgentRunResult result, long endEpochMs) throws IOException {
        ObjectNode footer = MAPPER.createObjectNode();
        footer.put("type", "transcript_footer");
        footer.put("end_epoch_ms", endEpochMs);
        footer.put("end_iso", Instant.ofEpochMilli(endEpochMs).toString());

        if (result != null) {
            footer.put("exit_code", result.exitCode());
            footer.put("killed_for_inactivity", result.killedForInactivity());
            footer.put("stop_reason", result.stopReason() != null ? result.stopReason() : "");
            footer.put("session_is_error", result.sessionIsError());
            footer.put("num_turns", result.numTurns());
            footer.put("cost_usd", result.costUsd());
            footer.put("duration_ms", result.durationMs());
            if (result.sessionId() != null) {
                footer.put("session_id", result.sessionId());
            }
            if (result.deniedToolNames() != null && !result.deniedToolNames().isEmpty()) {
                ArrayNode denials = footer.putArray("denied_tool_names");
                for (String tool : result.deniedToolNames()) {
                    denials.add(tool);
                }
            }
        }

        return MAPPER.writeValueAsString(footer);
    }
}
