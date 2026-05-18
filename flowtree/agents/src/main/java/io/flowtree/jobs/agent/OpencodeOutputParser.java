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
import io.flowtree.JsonFieldExtractor;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forgiving parser for opencode's JSON output.
 *
 * <p>opencode's output schema is evolving across releases, so this parser
 * tolerates unknown top-level fields (logged at debug level when a logger is
 * supplied), missing fields (defaulted sensibly), and partial transcripts.
 * The strategy is to scan the raw stdout for the last well-formed JSON object
 * and extract the fields we know about; if no JSON is present, every metric
 * defaults to zero and the {@code stopReason} reflects the exit code.</p>
 */
final class OpencodeOutputParser {

    /** Shared JSON mapper. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Stop reason recorded on successful exit when the runner reports nothing better. */
    static final String STOP_SUCCESS = "success";

    /** Stop reason recorded on non-zero exit when the runner reports nothing better. */
    static final String STOP_ERROR_UNKNOWN = "error_unknown";

    /** Hidden constructor; static-only utility. */
    private OpencodeOutputParser() {}

    /**
     * Parses {@code rawOutput} into an {@link AgentRunResult}.
     *
     * <p>Behaviour:</p>
     * <ul>
     *   <li>{@code exitCode} comes from the caller (the runner measures it).</li>
     *   <li>{@code durationMs} is set by the runner from wall-clock; this method
     *       only inserts the value supplied via {@code measuredDurationMs}.</li>
     *   <li>{@code durationApiMs} is always 0 — opencode does not separate
     *       API and total time.</li>
     *   <li>{@code numTurns} is taken from {@code steps} or {@code iterations}
     *       on the final JSON object, else counted from assistant entries in
     *       the transcript, else 0.</li>
     *   <li>{@code costUsd} is always 0 — see {@link OpencodeRunner}.</li>
     *   <li>{@code stopReason} is taken from the final object's
     *       {@code stopReason}, {@code finish_reason}, or {@code reason} key,
     *       falling back to success/error_unknown by exit code.</li>
     *   <li>The result's {@code rawOutput} field holds the full captured stdout,
     *       matching the contract used by {@link ClaudeCodeRunner}. The final
     *       assistant message, when discoverable, is surfaced via the result's
     *       {@code runnerMetadata} under the {@code response_text} key.</li>
     *   <li>{@code deniedToolNames} is taken from the final object's
     *       {@code denials} or {@code permission_denials} array.</li>
     * </ul>
     *
     * @param rawOutput            captured stdout from opencode
     * @param exitCode             process exit code
     * @param killedForInactivity  whether the inactivity watchdog fired
     * @param measuredDurationMs   wall-clock duration measured by the runner
     * @param runnerMetadata       metadata key-values the runner wants to surface
     * @param logger               diagnostic sink for unknown fields; may be null
     * @return the populated result
     */
    static AgentRunResult parse(String rawOutput,
                                int exitCode,
                                boolean killedForInactivity,
                                long measuredDurationMs,
                                Map<String, String> runnerMetadata,
                                ConsoleFeatures logger) {
        String responseText = "";
        String sessionId = null;
        int numTurns = 0;
        String stopReason = exitCode == 0 ? STOP_SUCCESS : STOP_ERROR_UNKNOWN;
        boolean isError = exitCode != 0;
        List<String> denied = new ArrayList<>();

        JsonNode root = findLastJsonObject(rawOutput);
        if (root != null) {
            sessionId = firstText(root, "session_id", "sessionId", "id");
            String stop = firstText(root, "stopReason", "stop_reason", "finish_reason", "reason");
            if (stop != null && !stop.isEmpty()) {
                stopReason = stop;
            }
            JsonNode err = firstNode(root, "is_error", "isError", "error");
            if (err != null) {
                if (err.isBoolean()) isError = err.asBoolean();
                else if (err.isTextual()) isError = !err.asText().isEmpty();
            }
            JsonNode turnsNode = firstNode(root, "steps", "iterations", "num_turns", "numTurns");
            if (turnsNode != null && turnsNode.isNumber()) {
                numTurns = turnsNode.asInt(0);
            }
            JsonNode denials = firstNode(root, "denials", "permission_denials", "permissionDenials");
            if (denials != null && denials.isArray()) {
                for (JsonNode entry : denials) {
                    String name = null;
                    if (entry.isTextual()) {
                        name = entry.asText();
                    } else if (entry.isObject()) {
                        name = firstText(entry, "tool", "name", "tool_name");
                    }
                    if (name != null && !name.isEmpty()) {
                        denied.add(name);
                    }
                }
            }
            String text = extractResponseText(root);
            if (text != null) {
                responseText = text;
            }
            logUnknownFields(root, logger);
        }

        if (numTurns == 0 && rawOutput != null) {
            numTurns = countAssistantTurns(rawOutput);
        }

        Map<String, String> meta = runnerMetadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(runnerMetadata);
        if (!responseText.isEmpty()) {
            meta.put("response_text", responseText);
        }

        return new AgentRunResult(
                exitCode,
                killedForInactivity,
                rawOutput == null ? "" : rawOutput,
                sessionId,
                measuredDurationMs,
                0L,
                numTurns,
                0.0,
                stopReason,
                isError,
                denied,
                meta);
    }

    /**
     * Scans {@code raw} for the last balanced JSON object and parses it.
     *
     * @param raw the captured stdout
     * @return the parsed root, or {@code null} when no object could be parsed
     */
    private static JsonNode findLastJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        // Try line-oriented NDJSON first (one object per line).
        String[] lines = raw.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    return MAPPER.readTree(line);
                } catch (Exception ignored) {
                    // fall through to next candidate
                }
            }
        }
        // Otherwise, look for the last top-level brace and parse from there.
        int end = raw.lastIndexOf('}');
        while (end > 0) {
            int depth = 0;
            int start = -1;
            for (int i = end; i >= 0; i--) {
                char c = raw.charAt(i);
                if (c == '}') depth++;
                else if (c == '{') {
                    depth--;
                    if (depth == 0) {
                        start = i;
                        break;
                    }
                }
            }
            if (start < 0) break;
            try {
                return MAPPER.readTree(raw.substring(start, end + 1));
            } catch (Exception ignored) {
                end = raw.lastIndexOf('}', start - 1);
            }
        }
        return null;
    }

    /**
     * Returns the first child node found under one of the supplied field names.
     *
     * @param parent root object
     * @param names  candidate field names, tried in order
     * @return the first non-null, non-missing child node, or {@code null}
     */
    private static JsonNode firstNode(JsonNode parent, String... names) {
        if (parent == null) return null;
        for (String name : names) {
            JsonNode child = parent.get(name);
            if (child != null && !child.isMissingNode() && !child.isNull()) {
                return child;
            }
        }
        return null;
    }

    /**
     * Returns the first textual value under one of the supplied field names.
     *
     * @param parent root object
     * @param names  candidate field names, tried in order
     * @return the first textual value, or {@code null}
     */
    private static String firstText(JsonNode parent, String... names) {
        JsonNode node = firstNode(parent, names);
        if (node == null) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.asText();
        return null;
    }

    /**
     * Pulls the final assistant message out of the parsed JSON, looking under a
     * handful of plausible field names.
     *
     * @param root the parsed root object
     * @return the assistant text, or {@code null} when none is present
     */
    private static String extractResponseText(JsonNode root) {
        String direct = JsonFieldExtractor.getTextOrNull(root, "result");
        if (direct != null) return direct;
        direct = JsonFieldExtractor.getTextOrNull(root, "response");
        if (direct != null) return direct;
        direct = JsonFieldExtractor.getTextOrNull(root, "text");
        if (direct != null) return direct;
        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray() && messages.size() > 0) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode msg = messages.get(i);
                String role = JsonFieldExtractor.getTextOrNull(msg, "role");
                if ("assistant".equalsIgnoreCase(role)) {
                    String content = JsonFieldExtractor.getTextOrNull(msg, "content");
                    if (content != null) return content;
                }
            }
        }
        return null;
    }

    /**
     * Counts assistant-role messages in a transcript-style NDJSON or single object.
     *
     * @param raw the raw captured output
     * @return the assistant message count, or 0 when none can be inferred
     */
    private static int countAssistantTurns(String raw) {
        int count = 0;
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                String role = JsonFieldExtractor.getTextOrNull(node, "role");
                if ("assistant".equalsIgnoreCase(role)) {
                    count++;
                    continue;
                }
                String type = JsonFieldExtractor.getTextOrNull(node, "type");
                if ("assistant".equalsIgnoreCase(type)) count++;
            } catch (Exception ignored) {
                // skip malformed lines
            }
        }
        return count;
    }

    /** Known top-level field names we explicitly handle. */
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "session_id", "sessionId", "id",
            "stopReason", "stop_reason", "finish_reason", "reason",
            "is_error", "isError", "error",
            "steps", "iterations", "num_turns", "numTurns",
            "denials", "permission_denials", "permissionDenials",
            "result", "response", "text", "messages",
            "type", "role", "content");

    /**
     * Logs unknown top-level fields at debug level so future opencode releases
     * don't silently drop telemetry.
     *
     * @param root   the parsed root object
     * @param logger diagnostic sink; may be {@code null}
     */
    private static void logUnknownFields(JsonNode root, ConsoleFeatures logger) {
        if (logger == null || root == null || !root.isObject()) return;
        Iterator<String> it = root.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!KNOWN_FIELDS.contains(name)) {
                logger.log("opencode_output_unknown_field=" + name);
            }
        }
    }
}
