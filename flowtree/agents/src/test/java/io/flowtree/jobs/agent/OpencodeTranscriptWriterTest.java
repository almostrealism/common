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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link OpencodeTranscriptWriter}: file creation, header and footer
 * content, event-stream passthrough, directory resolution, and error handling.
 */
public class OpencodeTranscriptWriterTest extends TestSuiteBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Basic transcript creation ---

    /** A transcript file is created under the configured directory. */
    @Test(timeout = 5000)
    public void transcriptFileIsCreated() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder()
                .taskId("job-123")
                .activityTag("deduplication")
                .build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", "sess-abc", 5000L, 0L, 3, 0.01,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_000_000L, 1_005_000L, SILENT);

        assertNotNull("write should return a non-null path on success", transcript);
        assertTrue("transcript file should exist on disk", Files.exists(transcript));
    }

    /** The filename encodes the job ID, phase, and timestamp. */
    @Test(timeout = 5000)
    public void transcriptFilenameContainsJobIdPhaseAndTimestamp() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder()
                .taskId("task-456")
                .activityTag("commit-message")
                .build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", null, 1000L, 0L, 1, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_000_000L, 1_001_000L, SILENT);

        assertNotNull(transcript);
        String name = transcript.getFileName().toString();
        assertTrue("filename should contain taskId: " + name, name.contains("task-456"));
        assertTrue("filename should contain phase: " + name, name.contains("commit-message"));
        assertTrue("filename should end with .jsonl: " + name, name.endsWith(".jsonl"));
    }

    // --- Header content ---

    /** The header line contains all session-context fields. */
    @Test(timeout = 5000)
    public void headerContainsFullSessionContext() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);

        Map<String, String> env = new HashMap<>();
        env.put("AR_WORKSTREAM_ID", "ws-999");
        AgentRunRequest req = AgentRunRequest.builder()
                .taskId("job-abc")
                .activityTag("dedup")
                .prompt("describe what changed")
                .workingDirectory(Paths.get("/workspace/project/repo"))
                .environment(env)
                .build();

        Map<String, String> meta = new HashMap<>();
        meta.put("model", "local/qwen3");
        meta.put("provider", "local");
        meta.put("provider_url", "http://localhost:8080/v1");
        meta.put("opencode_version", "1.0.0");
        AgentRunResult result = new AgentRunResult(
                0, false, "", "sess-xyz", 2000L, 0L, 2, 0.0,
                "success", false, Collections.emptyList(), meta);

        Path transcript = writer.write(req, result, 1_000_000L, 1_002_000L, SILENT);
        assertNotNull(transcript);

        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        assertTrue("transcript must have at least two lines (header + footer)",
                lines.size() >= 2);
        JsonNode header = MAPPER.readTree(lines.get(0));

        assertEquals("transcript_header", header.path("type").asText());
        assertEquals(OpencodeTranscriptWriter.FORMAT_VERSION,
                header.path("format_version").asInt());
        assertEquals(OpencodeRunner.NAME, header.path("runner").asText());
        assertEquals("job-abc", header.path("job_id").asText());
        assertEquals("ws-999", header.path("workstream_id").asText());
        assertEquals("dedup", header.path("phase").asText());
        assertEquals("local/qwen3", header.path("model").asText());
        assertEquals("local", header.path("provider").asText());
        assertEquals("sess-xyz", header.path("session_id").asText());
        assertEquals(1_000_000L, header.path("start_epoch_ms").asLong());
        assertEquals("describe what changed", header.path("prompt").asText());
        assertFalse("working_directory should be present",
                header.path("working_directory").isMissingNode());
    }

    /** The header start_iso is a valid ISO-8601 timestamp. */
    @Test(timeout = 5000)
    public void headerContainsIsoTimestamp() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-1").build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", null, 0L, 0L, 0, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_700_000_000_000L, 1_700_000_001_000L, SILENT);
        assertNotNull(transcript);

        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        JsonNode header = MAPPER.readTree(lines.get(0));
        String iso = header.path("start_iso").asText();
        assertFalse("start_iso should be present", iso.isEmpty());
        assertTrue("start_iso should look like an ISO-8601 UTC instant: " + iso,
                iso.contains("T") && iso.endsWith("Z"));
    }

    // --- Event stream passthrough ---

    /** Raw NDJSON lines from opencode are reproduced verbatim in the transcript. */
    @Test(timeout = 5000)
    public void rawNdjsonLinesArePreservedVerbatim() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-evs").build();
        String ndjson = "{\"type\":\"step_start\",\"sessionID\":\"s1\"}\n"
                + "{\"type\":\"text\",\"part\":{\"text\":\"hello world\"}}\n"
                + "{\"type\":\"tool_use\",\"tool\":\"Read\"}\n"
                + "{\"type\":\"step_finish\",\"sessionID\":\"s1\"}\n";
        AgentRunResult result = new AgentRunResult(
                0, false, ndjson, "s1", 1000L, 0L, 1, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_000_000L, 1_001_000L, SILENT);
        assertNotNull(transcript);

        String content = new String(Files.readAllBytes(transcript), StandardCharsets.UTF_8);
        assertTrue("step_start event should appear verbatim",
                content.contains("\"type\":\"step_start\""));
        assertTrue("text event should appear verbatim",
                content.contains("\"type\":\"text\""));
        assertTrue("tool_use event should appear verbatim",
                content.contains("\"type\":\"tool_use\""));
        assertTrue("step_finish event should appear verbatim",
                content.contains("\"type\":\"step_finish\""));
        assertTrue("model text content should be preserved",
                content.contains("hello world"));
    }

    /**
     * Corrupted or non-JSON output from opencode is preserved verbatim because
     * forensic analysis requires seeing the corruption exactly as it occurred.
     */
    @Test(timeout = 5000)
    public void corruptedOutputIsPreservedVerbatim() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-corrupt").build();
        String corruptOutput = "{\"type\":\"step_start\"}\n"
                + "CORRUPTED_LINE_NOT_JSON\n"
                + "{\"type\":\"text\",\"part\":{\"text\":\"partial\"}}\n"
                + "{invalid json}\n";
        AgentRunResult result = new AgentRunResult(
                1, false, corruptOutput, null, 500L, 0L, 0, 0.0,
                "error_unknown", true, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_000_000L, 1_000_500L, SILENT);
        assertNotNull(transcript);

        String content = new String(Files.readAllBytes(transcript), StandardCharsets.UTF_8);
        assertTrue("corrupted non-JSON line should be present for forensic analysis",
                content.contains("CORRUPTED_LINE_NOT_JSON"));
        assertTrue("partially valid JSON should be present",
                content.contains("{invalid json}"));
    }

    // --- Footer content ---

    /** The footer line contains all outcome metrics. */
    @Test(timeout = 5000)
    public void footerContainsOutcomeMetrics() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-footer").build();
        AgentRunResult result = new AgentRunResult(
                1, true, "", "sess-def", 3000L, 0L, 5, 0.05,
                "error_inactivity", true, List.of("Bash", "Edit"),
                Collections.emptyMap());

        Path transcript = writer.write(req, result, 2_000_000L, 2_003_000L, SILENT);
        assertNotNull(transcript);

        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        JsonNode footer = MAPPER.readTree(lines.get(lines.size() - 1));

        assertEquals("transcript_footer", footer.path("type").asText());
        assertEquals(1, footer.path("exit_code").asInt());
        assertTrue("killed_for_inactivity should be true",
                footer.path("killed_for_inactivity").asBoolean());
        assertEquals("error_inactivity", footer.path("stop_reason").asText());
        assertTrue("session_is_error should be true",
                footer.path("session_is_error").asBoolean());
        assertEquals(5, footer.path("num_turns").asInt());
        assertEquals(0.05, footer.path("cost_usd").asDouble(), 0.0001);
        assertEquals(3000L, footer.path("duration_ms").asLong());
        assertEquals(2_003_000L, footer.path("end_epoch_ms").asLong());
        assertTrue("denied_tool_names should be present",
                footer.has("denied_tool_names"));
        assertTrue("Bash should appear in denied tools",
                footer.path("denied_tool_names").toString().contains("Bash"));
        assertTrue("Edit should appear in denied tools",
                footer.path("denied_tool_names").toString().contains("Edit"));
    }

    /** The footer end_iso is a valid ISO-8601 timestamp. */
    @Test(timeout = 5000)
    public void footerContainsIsoTimestamp() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-2").build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", null, 0L, 0L, 0, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_000_000L, 1_001_000L, SILENT);
        assertNotNull(transcript);

        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        JsonNode footer = MAPPER.readTree(lines.get(lines.size() - 1));
        String iso = footer.path("end_iso").asText();
        assertFalse("end_iso should be present", iso.isEmpty());
        assertTrue("end_iso should look like an ISO-8601 UTC instant: " + iso,
                iso.contains("T") && iso.endsWith("Z"));
    }

    // --- Structure: header is first line, footer is last line ---

    /** Every transcript starts with a header and ends with a footer. */
    @Test(timeout = 5000)
    public void transcriptAlwaysStartsWithHeaderAndEndsWithFooter() throws IOException {
        Path dir = Files.createTempDirectory("opc-transcript-test-");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(dir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-struct").build();
        String events = "{\"type\":\"step_start\"}\n{\"type\":\"text\",\"part\":{\"text\":\"hi\"}}\n";
        AgentRunResult result = new AgentRunResult(
                0, false, events, null, 0L, 0L, 1, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path transcript = writer.write(req, result, 1_000_000L, 1_001_000L, SILENT);
        assertNotNull(transcript);

        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        assertTrue("transcript needs at least 4 lines: header + 2 events + footer",
                lines.size() >= 4);

        JsonNode header = MAPPER.readTree(lines.get(0));
        Assert.assertEquals("first line must be transcript_header",
                "transcript_header", header.path("type").asText());

        JsonNode footer = MAPPER.readTree(lines.get(lines.size() - 1));
        Assert.assertEquals("last line must be transcript_footer",
                "transcript_footer", footer.path("type").asText());
    }

    // --- Error handling ---

    /** When the target directory cannot be created, write returns null without throwing. */
    @Test(timeout = 5000)
    public void writeReturnsNullOnIoError() {
        Path notADir = Paths.get("/dev/null/transcripts");
        OpencodeTranscriptWriter writer = new OpencodeTranscriptWriter(notADir);
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-err").build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", null, 0L, 0L, 0, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        Path returned = writer.write(req, result, 0L, 1L, SILENT);

        assertNull("write to invalid path must return null rather than throw", returned);
    }

    // --- Filename helpers ---

    /** Sanitize replaces characters outside [A-Za-z0-9._-] with hyphens. */
    @Test(timeout = 5000)
    public void sanitizeReplacesSpecialCharacters() {
        assertEquals("a-b-c", OpencodeTranscriptWriter.sanitize("a:b/c", "fallback"));
        assertEquals("abc-123", OpencodeTranscriptWriter.sanitize("abc-123", "fallback"));
        assertEquals("a.b_c", OpencodeTranscriptWriter.sanitize("a.b_c", "fallback"));
    }

    /** Sanitize returns the fallback for null or empty inputs. */
    @Test(timeout = 5000)
    public void sanitizeReturnsFallbackForNullOrEmpty() {
        assertEquals("fallback", OpencodeTranscriptWriter.sanitize(null, "fallback"));
        assertEquals("fallback", OpencodeTranscriptWriter.sanitize("", "fallback"));
    }

    /** Sanitize truncates values longer than 64 characters. */
    @Test(timeout = 5000)
    public void sanitizeTruncatesLongValues() {
        String long70 = "a".repeat(70);
        String result = OpencodeTranscriptWriter.sanitize(long70, "fallback");
        assertEquals("sanitize should cap at 64 characters", 64, result.length());
    }

    /** buildFilename uses the task ID, phase, and timestamp. */
    @Test(timeout = 5000)
    public void buildFilenameContainsExpectedSegments() {
        AgentRunRequest req = AgentRunRequest.builder()
                .taskId("my-task")
                .activityTag("primary")
                .build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", null, 0L, 0L, 0, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        String name = OpencodeTranscriptWriter.buildFilename(req, result, 0L);

        assertTrue("filename should start with a timestamp: " + name,
                name.matches("\\d{8}-\\d{6}-.*"));
        assertTrue("filename should contain task ID: " + name, name.contains("my-task"));
        assertTrue("filename should contain phase: " + name, name.contains("primary"));
        assertTrue("filename should end with .jsonl: " + name, name.endsWith(".jsonl"));
    }

    /** buildFilename appends a session ID suffix when one is available. */
    @Test(timeout = 5000)
    public void buildFilenameAppendsSessionIdWhenPresent() {
        AgentRunRequest req = AgentRunRequest.builder().taskId("t1").build();
        AgentRunResult result = new AgentRunResult(
                0, false, "", "sess-abc123def", 0L, 0L, 0, 0.0,
                "success", false, Collections.emptyList(), Collections.emptyMap());

        String name = OpencodeTranscriptWriter.buildFilename(req, result, 0L);

        assertTrue("filename should contain (first 12 chars of) session ID: " + name,
                name.contains("sess-abc123d"));
    }

    // --- Directory resolution ---

    /** When outputCapturePath is set and OPENCODE_TRANSCRIPT_DIR is absent, uses sibling dir. */
    @Test(timeout = 5000)
    public void forRequestUsesOutputCapturePathSiblingWhenEnvAbsent() throws IOException {
        Path captureFile = Files.createTempDirectory("opc-capture-")
                .resolve("output.txt");
        AgentRunRequest req = AgentRunRequest.builder()
                .taskId("job-dir")
                .outputCapturePath(captureFile)
                .build();
        OpencodeTranscriptWriter writer = OpencodeTranscriptWriter.forRequest(req, name -> null);
        Path expected = captureFile.getParent().resolve("transcripts");
        Assert.assertEquals("transcript dir should be <capture-parent>/transcripts",
                expected, writer.getTranscriptDir());
    }

    /** When neither env var nor outputCapturePath is set, falls back to the default dir. */
    @Test(timeout = 5000)
    public void forRequestFallsBackToDefaultDirWhenEnvAbsent() {
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-def").build();
        OpencodeTranscriptWriter writer = OpencodeTranscriptWriter.forRequest(req, name -> null);
        Assert.assertEquals("transcript dir should be the default when no env var is set",
                Paths.get(OpencodeTranscriptWriter.DEFAULT_TRANSCRIPT_DIR),
                writer.getTranscriptDir());
    }

    /** forRequest(request, envLookup) honours the injected env lookup for OPENCODE_TRANSCRIPT_DIR. */
    @Test(timeout = 5000)
    public void forRequestHonoursInjectedEnvLookup() throws IOException {
        Path customDir = Files.createTempDirectory("opc-custom-transcripts-");
        AgentRunRequest req = AgentRunRequest.builder().taskId("job-env").build();
        OpencodeTranscriptWriter writer = OpencodeTranscriptWriter.forRequest(
                req, name -> OpencodeTranscriptWriter.ENV_TRANSCRIPT_DIR.equals(name)
                        ? customDir.toString() : null);
        Assert.assertEquals("should use the directory returned by the env lookup",
                customDir, writer.getTranscriptDir());
    }
}
