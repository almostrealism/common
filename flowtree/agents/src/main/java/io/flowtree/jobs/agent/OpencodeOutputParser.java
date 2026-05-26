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

import com.fasterxml.jackson.databind.JsonNode;
import io.flowtree.JsonFieldExtractor;
import org.almostrealism.io.ConsoleFeatures;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** Stop reason recorded on successful exit when the runner reports nothing better. */
    static final String STOP_SUCCESS = "success";

    /** Stop reason recorded on non-zero exit when the runner reports nothing better. */
    static final String STOP_ERROR_UNKNOWN = "error_unknown";

    /** Stop reason recorded when the loop detector killed the session for repeated, non-progressing actions. */
    static final String STOP_LOOP_DETECTED = "error_loop_detected";

    /** Hidden constructor; static-only utility. */
    private OpencodeOutputParser() {}

    /**
     * Returns the first {@code maxHexLen} characters of the lowercase hex
     * SHA-256 digest of {@code input}, or the string {@code "null"} when input
     * is null. Used as a collision-resistant action fingerprint in loop
     * detection.
     *
     * @param input     the text to fingerprint; {@code null} yields {@code "null"}
     * @param maxHexLen  maximum number of leading hex characters to return
     * @return the truncated hex digest, or a {@code hashCode}-based fallback if
     *         SHA-256 is unavailable
     */
    private static String sha256Prefix(String input, int maxHexLen) {
        if (input == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, Math.min(maxHexLen, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Backwards-compatible overload that defaults {@code reportsCost} to {@code false}.
     * Used by tests and callers from before the provider-axis change introduced
     * provider-aware cost reporting.
     *
     * @param rawOutput            captured stdout from opencode
     * @param exitCode             process exit code
     * @param killedForInactivity  whether the inactivity watchdog fired
     * @param measuredDurationMs   wall-clock duration measured by the runner
     * @param runnerMetadata       metadata key-values the runner wants to surface
     * @param logger               diagnostic sink; may be null
     * @return the populated result
     */
    static AgentRunResult parse(String rawOutput,
                                int exitCode,
                                boolean killedForInactivity,
                                long measuredDurationMs,
                                Map<String, String> runnerMetadata,
                                ConsoleFeatures logger) {
        return parse(rawOutput, exitCode, killedForInactivity, measuredDurationMs,
                runnerMetadata, logger, false);
    }

    /**
     * Backwards-compatible overload that defaults {@code killedForLooping} to {@code false}.
     *
     * @param rawOutput            captured stdout from opencode
     * @param exitCode             process exit code
     * @param killedForInactivity  whether the inactivity watchdog fired
     * @param measuredDurationMs   wall-clock duration measured by the runner
     * @param runnerMetadata       metadata key-values the runner wants to surface
     * @param logger               diagnostic sink; may be null
     * @param reportsCost          whether the provider reports cost in usage.cost
     * @return the populated result
     */
    static AgentRunResult parse(String rawOutput,
                                int exitCode,
                                boolean killedForInactivity,
                                long measuredDurationMs,
                                Map<String, String> runnerMetadata,
                                ConsoleFeatures logger,
                                boolean reportsCost) {
        return parse(rawOutput, exitCode, killedForInactivity, measuredDurationMs,
                runnerMetadata, logger, reportsCost, false);
    }

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
     *   <li>{@code costUsd} is, when {@code reportsCost} is true, the sum of the
     *       per-step {@code part.cost} values on the {@code step_finish} events;
     *       a top-level {@code usage.cost} block is used as a fallback. Otherwise 0.</li>
     *   <li>{@code stopReason} is taken from the final object's
     *       {@code stopReason}, {@code stop_reason}, {@code finish_reason}, or {@code reason} key,
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
     * @param reportsCost          whether the provider reports cost in usage.cost
     * @param killedForLooping     whether the loop detector killed the session for repeated actions
     * @return the populated result
     */
    static AgentRunResult parse(String rawOutput,
                                int exitCode,
                                boolean killedForInactivity,
                                long measuredDurationMs,
                                Map<String, String> runnerMetadata,
                                ConsoleFeatures logger,
                                boolean reportsCost,
                                boolean killedForLooping) {
        String responseText = "";
        String sessionId = null;
        int numTurns = 0;
        String stopReason = exitCode == 0 ? STOP_SUCCESS : STOP_ERROR_UNKNOWN;
        boolean isError = exitCode != 0;
        List<String> denied = new ArrayList<>();

        EventStreamResult events = parseEventStream(rawOutput);
        if (events != null) {
            if (events.sessionId != null) sessionId = events.sessionId;
            if (!events.responseText.isEmpty()) responseText = events.responseText;
            if (events.numTurns > 0) numTurns = events.numTurns;
            if (events.errorSeen) {
                isError = true;
                if (stopReason.equals(STOP_SUCCESS)) stopReason = STOP_ERROR_UNKNOWN;
            }
        }

        JsonNode root = findLastJsonObject(rawOutput);
        if (root != null) {
            // Only overwrite values already set by the event-stream parser
            // when the legacy single-object path can supply a non-null match.
            String legacySession = firstText(root, "session_id", "sessionId", "id");
            if (legacySession != null) {
                sessionId = legacySession;
            }
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

        int inputTokens = 0;
        int outputTokens = 0;
        int cacheReadTokens = 0;
        int cacheWriteTokens = 0;
        boolean costUnavailable = false;
        double costUsd = 0.0;
        if (reportsCost) {
            // Primary source: the per-step usage opencode emits on its
            // step_finish events (part.cost / part.tokens). This is the shape
            // real opencode --format json output uses; it carries no top-level
            // "usage" object.
            if (events != null && events.usageSeen) {
                costUsd = events.cost;
                inputTokens = events.inputTokens;
                outputTokens = events.outputTokens;
                cacheReadTokens = events.cacheReadTokens;
                cacheWriteTokens = events.cacheWriteTokens;
            } else {
                // Fallback: a top-level "usage" block. Retained for forgiving
                // single-object fixtures and any future opencode build that
                // summarises usage that way.
                JsonNode usage = firstNode(root, "usage");
                if (usage == null) {
                    usage = findUsageBlock(rawOutput);
                }
                if (usage != null) {
                    JsonNode costNode = firstNode(usage, "cost");
                    if (costNode != null && costNode.isNumber()) {
                        costUsd = costNode.asDouble(0.0);
                    }
                    JsonNode inputTokensNode = firstNode(usage, "input_tokens");
                    if (inputTokensNode != null && inputTokensNode.isNumber()) {
                        inputTokens = inputTokensNode.asInt(0);
                    }
                    JsonNode outputTokensNode = firstNode(usage, "output_tokens");
                    if (outputTokensNode != null && outputTokensNode.isNumber()) {
                        outputTokens = outputTokensNode.asInt(0);
                    }
                }
            }
            if (costUsd == 0.0 && (inputTokens > 0 || outputTokens > 0)) {
                costUnavailable = true;
            }
        }

        // A loop kill overrides any stop reason the (forcibly truncated) stream
        // may have parsed, so the run is never reported as a success.
        if (killedForLooping) {
            stopReason = STOP_LOOP_DETECTED;
            isError = true;
        }

        Map<String, String> meta = runnerMetadata == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(runnerMetadata);
        if (!responseText.isEmpty()) {
            meta.put("response_text", responseText);
        }
        if (inputTokens > 0) {
            meta.put("input_tokens", String.valueOf(inputTokens));
        }
        if (outputTokens > 0) {
            meta.put("output_tokens", String.valueOf(outputTokens));
        }
        if (cacheReadTokens > 0) {
            meta.put("cache_read_tokens", String.valueOf(cacheReadTokens));
        }
        if (cacheWriteTokens > 0) {
            meta.put("cache_write_tokens", String.valueOf(cacheWriteTokens));
        }
        if (costUnavailable) {
            meta.put("cost_unavailable", "true");
        }

        return new AgentRunResult(
                exitCode,
                killedForInactivity,
                rawOutput == null ? "" : rawOutput,
                sessionId,
                measuredDurationMs,
                0L,
                numTurns,
                costUsd,
                stopReason,
                isError,
                denied,
                meta);
    }

    /**
     * Maps a single line of opencode NDJSON output to a normalized action
     * signature for loop detection, or {@code null} when the line is not a tool
     * invocation. The signature combines the tool name with a hash of the tool
     * input, so repeated identical actions (the same command, the same edit on
     * the same file) collapse to one signature while distinct actions stay
     * distinct. Supplied to {@code AgentProcessRunner} as the loop-signature
     * extractor for opencode sessions.
     *
     * <p>Never throws: malformed, non-JSON, or non-tool lines yield {@code null}
     * and are simply not counted toward repetition.</p>
     *
     * @param line one line of captured stdout
     * @return the action signature, or {@code null} for non-action or unparseable lines
     */
    static String toActionSignature(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (Exception ignored) {
            return null;
        }
        if (node == null || !"tool_use".equals(JsonFieldExtractor.getTextOrNull(node, "type"))) {
            return null;
        }
        JsonNode part = node.get("part");
        if (part == null) {
            return null;
        }
        String tool = JsonFieldExtractor.getTextOrNull(part, "tool");
        if (tool == null || tool.isEmpty()) {
            return null;
        }
        JsonNode input = part.path("state").path("input");
        String inputText = input.isMissingNode() || input.isNull() ? "" : input.toString();
        String digest = sha256Prefix(inputText, 16);
        return "tool:" + tool + ":" + digest;
    }

    /**
     * Walks the captured stdout as an NDJSON event stream and extracts the
     * fields that map onto {@link AgentRunResult}. Recognised event types:
     * <ul>
     *   <li>{@code step_start} — counted as one turn</li>
     *   <li>{@code text} — assistant-emitted text chunk; {@code part.text} is
     *       appended to the running response text</li>
     *   <li>{@code step_finish} — per-step usage; {@code part.cost} and
     *       {@code part.tokens.input}/{@code output} are summed across steps</li>
     *   <li>{@code error} — flips {@code errorSeen}</li>
     * </ul>
     *
     * <p>Other event types contribute only their {@code sessionID} if seen.
     * Returns {@code null} when no line of {@code raw} looks like an opencode
     * event — the caller then falls back to the legacy single-object parser
     * for older fixtures.</p>
     *
     * @param raw the captured stdout
     * @return aggregated stream result, or {@code null} when not an event stream
     */
    private static EventStreamResult parseEventStream(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] lines = raw.split("\\r?\\n");
        EventStreamResult acc = new EventStreamResult();
        boolean sawAny = false;
        StringBuilder text = new StringBuilder();
        Set<String> messageIds = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue;
            JsonNode node;
            try {
                node = MAPPER.readTree(trimmed);
            } catch (Exception ignored) {
                continue;
            }
            // Recognise an event by the presence of a top-level `type` AND
            // either `sessionID` (opencode 1.x camelCase) or `part`. This
            // avoids matching legacy single-object fixtures that carry a
            // `type` field for some other reason.
            JsonNode typeNode = node.get("type");
            JsonNode partNode = node.get("part");
            JsonNode sessionNode = node.get("sessionID");
            if (typeNode == null || !typeNode.isTextual()) continue;
            if (partNode == null && sessionNode == null) continue;
            sawAny = true;
            if (sessionNode != null && sessionNode.isTextual()) {
                acc.sessionId = sessionNode.asText();
            }
            String type = typeNode.asText();
            switch (type) {
                case "step_start":
                case "step-start":
                    acc.numTurns++;
                    break;
                case "text":
                    if (partNode != null) {
                        JsonNode textNode = partNode.get("text");
                        JsonNode msgIdNode = partNode.get("messageID");
                        if (textNode != null && textNode.isTextual()) {
                            text.append(textNode.asText());
                        }
                        if (msgIdNode != null && msgIdNode.isTextual()) {
                            messageIds.add(msgIdNode.asText());
                        }
                    }
                    break;
                case "error":
                    acc.errorSeen = true;
                    break;
                case "step_finish":
                case "step-finish":
                    // opencode reports per-step usage on the step_finish event:
                    // part.cost is that step's dollar cost and part.tokens.input
                    // / part.tokens.output are that step's token counts. opencode
                    // runs on the Vercel AI SDK, whose onStepFinish callback
                    // surfaces per-step (not cumulative) usage, so the session
                    // total is the sum across every step_finish. There is no
                    // top-level "usage" object in opencode's output.
                    if (partNode != null) {
                        JsonNode costNode = partNode.get("cost");
                        if (costNode != null && costNode.isNumber()) {
                            acc.cost += costNode.asDouble();
                            acc.usageSeen = true;
                        }
                        JsonNode tokensNode = partNode.get("tokens");
                        if (tokensNode != null && tokensNode.isObject()) {
                            JsonNode inNode = tokensNode.get("input");
                            if (inNode != null && inNode.isNumber()) {
                                acc.inputTokens += inNode.asInt();
                            }
                            JsonNode outNode = tokensNode.get("output");
                            if (outNode != null && outNode.isNumber()) {
                                acc.outputTokens += outNode.asInt();
                            }
                            JsonNode cacheNode = tokensNode.get("cache");
                            if (cacheNode != null && cacheNode.isObject()) {
                                JsonNode readNode = cacheNode.get("read");
                                if (readNode != null && readNode.isNumber()) {
                                    acc.cacheReadTokens += readNode.asInt();
                                }
                                JsonNode writeNode = cacheNode.get("write");
                                if (writeNode != null && writeNode.isNumber()) {
                                    acc.cacheWriteTokens += writeNode.asInt();
                                }
                            }
                            acc.usageSeen = true;
                        }
                    }
                    break;
                default:
                    // ignore — tool_use, tool_result, etc.
                    break;
            }
        }
        if (!sawAny) return null;
        acc.responseText = text.toString();
        // Prefer step_start count; if none were seen but text was produced,
        // count distinct assistant messageIDs (some opencode flows omit
        // step_start when there is no tool invocation).
        if (acc.numTurns == 0 && !messageIds.isEmpty()) {
            acc.numTurns = messageIds.size();
        }
        return acc;
    }

    /**
     * Aggregated result of {@link #parseEventStream}: the values we surface to
     * {@link AgentRunResult} from a successful event-stream walk.
     */
    private static final class EventStreamResult {
        /** Latest {@code sessionID} seen on any event, or {@code null} when absent. */
        String sessionId;
        /** Concatenated {@code part.text} of all text events, in stream order. */
        String responseText = "";
        /** Number of {@code step_start} events, or distinct assistant messageIDs if none. */
        int numTurns;
        /** {@code true} when at least one {@code error} event was emitted. */
        boolean errorSeen;
        /** Summed {@code part.cost} across every {@code step_finish} event. */
        double cost;
        /** Summed {@code part.tokens.input} across every {@code step_finish} event. */
        int inputTokens;
        /** Summed {@code part.tokens.output} across every {@code step_finish} event. */
        int outputTokens;
        /** Summed {@code part.tokens.cache.read} across every {@code step_finish} event. */
        int cacheReadTokens;
        /** Summed {@code part.tokens.cache.write} across every {@code step_finish} event. */
        int cacheWriteTokens;
        /** {@code true} once any {@code step_finish} carried a cost or token block. */
        boolean usageSeen;
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
     * Finds the best available {@code usage} block by scanning all NDJSON lines.
     * This handles cases where the final summary with {@code usage} is not in the
     * last JSON object (e.g., when opencode outputs metadata-only objects after
     * the summary, or when the summary appears mid-stream).
     *
     * @param raw the captured stdout
     * @return the {@code usage} node, or {@code null} when absent
     */
    private static JsonNode findUsageBlock(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] lines = raw.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("{") || !line.endsWith("}")) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                JsonNode usage = firstNode(node, "usage");
                if (usage != null && usage.isObject()) {
                    return usage;
                }
            } catch (Exception ignored) {
                // continue scanning
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
            "session_id", "sessionId", "sessionID", "id",
            "stopReason", "stop_reason", "finish_reason", "reason",
            "is_error", "isError", "error",
            "steps", "iterations", "num_turns", "numTurns",
            "denials", "permission_denials", "permissionDenials",
            "result", "response", "text", "messages",
            "type", "role", "content",
            "timestamp", "part");

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
