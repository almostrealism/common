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

package io.flowtree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A blocked private-link write: the one event that is recorded as a durable,
 * queryable incident and, when an alert sink has been installed, reported to
 * the owner.
 *
 * <p>The owner's requirement is that "being reported" is literally true when a
 * {@link PrivateLinkDetector} match is refused. An incident is the Java-side
 * record the controller's GitHub proxy and the FlowTree harness commit/PR
 * egress construct at each refusal: {@code new PrivateLinkIncident(route,
 * target, matched).record()} appends a redaction-safe line (route, target,
 * matched pattern names, timestamp) to a JSONL incident log and fires the alert
 * sink. The matched link is never part of the incident — only the names of the
 * patterns it tripped.</p>
 *
 * <p>The log path is {@code AR_PRIVATE_LINK_INCIDENT_LOG} when set, otherwise
 * {@code ~/.flowtree/incidents/private-link-incidents.jsonl}. Prior incidents
 * are queryable with {@link #recent(int)}.</p>
 *
 * @author Michael Murray
 */
public final class PrivateLinkIncident {

    /** The log discriminator identifying a private-link block record. */
    private static final String TYPE = "private_link_block";

    /** Serialises incident records to JSONL and reads them back. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The installed owner-alert sink, or {@code null} when none is wired. */
    private static volatile Consumer<String> alertSink;

    /** The egress route that was refused (e.g. {@code "controller_github_proxy"}). */
    private final String route;

    /** A redaction-safe description of the write target. */
    private final String target;

    /** The matched pattern names from {@link PrivateLinkDetector#scan(String)}. */
    private final List<String> matched;

    /** When the incident occurred. */
    private final Instant timestamp;

    /**
     * Creates an incident for a refusal happening now.
     *
     * @param route   the egress route that was refused (e.g.
     *                {@code "controller_github_proxy"})
     * @param target  a redaction-safe description of the write target
     * @param matched the matched pattern names from {@link PrivateLinkDetector#scan(String)}
     */
    public PrivateLinkIncident(String route, String target, List<String> matched) {
        this(route, target, matched, Instant.now());
    }

    /**
     * Creates an incident with an explicit timestamp, used when reconstructing a
     * stored incident from the log.
     *
     * @param route     the egress route that was refused
     * @param target    a redaction-safe description of the write target
     * @param matched   the matched pattern names
     * @param timestamp when the incident occurred
     */
    private PrivateLinkIncident(String route, String target, List<String> matched,
                                Instant timestamp) {
        this.route = route;
        this.target = target;
        this.matched = matched == null ? List.of() : List.copyOf(matched);
        this.timestamp = timestamp;
    }

    /**
     * Returns the egress route that was refused.
     *
     * @return the route
     */
    public String route() {
        return route;
    }

    /**
     * Returns the redaction-safe description of the write target.
     *
     * @return the target
     */
    public String target() {
        return target;
    }

    /**
     * Returns the matched pattern names, in the order they were reported.
     *
     * @return the matched pattern names; empty, never {@code null}
     */
    public List<String> matched() {
        return matched;
    }

    /**
     * Returns when the incident occurred.
     *
     * @return the timestamp
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * Installs the sink that delivers a redaction-safe one-line alert to the
     * owner when an incident is recorded. The controller wires this to its
     * out-of-band alert mechanism at startup; left unset (e.g. on a worker
     * node), recording still writes the durable incident log.
     *
     * @param sink the alert sink, or {@code null} to clear it
     */
    public static void installAlertSink(Consumer<String> sink) {
        alertSink = sink;
    }

    /**
     * Records this blocked private-link write to the durable incident log and
     * alerts the owner.
     *
     * @return the one-line redaction-safe summary that was recorded
     */
    public String record() {
        append();

        String summary = "Blocked a private Claude session link from reaching a public"
                + " surface. route=" + route + " target=" + target
                + " matched=" + String.join(",", matched)
                + ". The link was redacted; see the private-link incident log.";
        Consumer<String> sink = alertSink;
        if (sink != null) {
            try {
                sink.accept(summary);
            } catch (RuntimeException ignored) {
                // Alerting must never break the block it describes.
            }
        }
        return summary;
    }

    /**
     * Returns the most recent recorded incidents, newest first.
     *
     * @param limit the maximum number of incidents to return
     * @return the incidents; empty, never {@code null}, when none exist
     */
    public static List<PrivateLinkIncident> recent(int limit) {
        Path path = logPath();
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }
        List<PrivateLinkIncident> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    records.add(fromRecord(MAPPER.readValue(line, Map.class)));
                } catch (IOException | RuntimeException ignored) {
                    // A partially written or corrupt line is skipped, not fatal.
                }
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.reverse(records);
        return records.size() > limit ? records.subList(0, limit) : records;
    }

    /**
     * Reconstructs an incident from one parsed JSONL log record.
     *
     * @param record the decoded JSON object
     * @return the incident
     */
    private static PrivateLinkIncident fromRecord(Map<?, ?> record) {
        List<String> patterns = new ArrayList<>();
        Object matchedValue = record.get("matched_patterns");
        if (matchedValue instanceof List<?>) {
            for (Object name : (List<?>) matchedValue) {
                if (name != null) {
                    patterns.add(name.toString());
                }
            }
        }
        Object timestampValue = record.get("timestamp");
        Instant when = timestampValue == null ? null : Instant.parse(timestampValue.toString());
        return new PrivateLinkIncident(
                asText(record.get("route")), asText(record.get("target")), patterns, when);
    }

    /**
     * Returns the string form of a decoded JSON value, or {@code null}.
     *
     * @param value the decoded value
     * @return its string form, or {@code null} when absent
     */
    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Appends this incident to the JSONL log, creating parent directories as
     * needed. Recording is best-effort: a filesystem error must not break the
     * block, so it is swallowed.
     */
    private void append() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", timestamp.toString());
        record.put("type", TYPE);
        record.put("route", route);
        record.put("target", target);
        record.put("matched_patterns", matched);

        Path path = logPath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, MAPPER.writeValueAsString(record) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort durable record; losing it must not stop the refusal.
        }
    }

    /**
     * Returns the incident log path, honouring {@code AR_PRIVATE_LINK_INCIDENT_LOG}.
     *
     * @return the log path
     */
    private static Path logPath() {
        String property = System.getProperty("ar.privateLinkIncidentLog");
        if (property != null && !property.isEmpty()) {
            return Paths.get(property);
        }
        String configured = System.getenv("AR_PRIVATE_LINK_INCIDENT_LOG");
        if (configured != null && !configured.isEmpty()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.home"), ".flowtree", "incidents",
                "private-link-incidents.jsonl");
    }
}
