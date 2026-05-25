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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.jobs.agent.AgentRunResult;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;

import java.util.function.BiConsumer;

/**
 * The harness's own operational voice in a workstream's notification channel.
 *
 * <p>Coding-agent sessions already publish their own messages through the
 * {@code send_message} MCP tool; this reporter is how the <em>harness itself</em>
 * surfaces the lifecycle transitions an operator otherwise has to infer from
 * timing gaps: which phase is entering and on which runner/model, how each
 * phase exited, when the inactivity watchdog suspended a runner, and when a
 * job ended in an unusual way (git tampering, exhausted retries, a timed-out
 * gate). Messages are deliberately sparse — one per notable event, never a
 * progress bar.</p>
 *
 * <p>Every message begins with the distinctive {@link #SYSTEM_PREFIX gear
 * prefix} so that, when scanning a channel, harness messages are immediately
 * distinguishable from agent-authored prose (which would not spontaneously
 * lead with that glyph). A state emoji follows the gear so the kind of event
 * is recognisable at a glance.</p>
 *
 * <p>Reuse, not reinvention: the reporter posts through the same
 * {@code POST <workstreamUrl>/messages} path that publishes the host
 * fingerprint, tagging each message with the {@link #ACTIVITY harness_status}
 * activity so they are filterable via {@code workstream_context}. It does not
 * open a parallel channel of its own. When constructed with a {@code null} or
 * empty base URL (no controller in the loop) every method is a silent no-op.</p>
 *
 * @author Michael Murray
 */
public final class HarnessStatusReporter {

    /** Distinctive leading glyph identifying every harness-authored message. */
    public static final String SYSTEM_PREFIX = "⚙️"; // gear

    /** State emoji for a phase being entered. */
    public static final String PHASE_ENTRY_EMOJI = "▶️"; // play

    /** State emoji for a phase that has completed. */
    public static final String PHASE_EXIT_EMOJI = "⏹️"; // stop

    /** State emoji for an inactivity suspension. */
    public static final String INACTIVITY_EMOJI = "⏸️"; // pause

    /** State emoji for an unusual or unexpected termination. */
    public static final String UNUSUAL_EMOJI = "⚠️"; // warning

    /** Activity tag applied to harness status messages for later filtering. */
    public static final String ACTIVITY = "harness_status";

    /** Shared mapper used to build the message JSON body safely. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Resolved workstream base URL; {@code null}/empty disables all output. */
    private final String workstreamBaseUrl;

    /** Sink that performs the actual POST: {@code (url, jsonBody) -> void}. */
    private final BiConsumer<String, String> poster;

    /**
     * Creates a reporter that posts to {@code workstreamBaseUrl + "/messages"}.
     *
     * @param workstreamBaseUrl the resolved workstream URL (e.g.
     *                          {@code http://host/api/workstreams/{id}}), or
     *                          {@code null}/empty to disable all messaging
     * @param poster            performs the POST given a URL and JSON body;
     *                          typically {@code GitManagedJob::postJson}
     */
    public HarnessStatusReporter(String workstreamBaseUrl, BiConsumer<String, String> poster) {
        this.workstreamBaseUrl = workstreamBaseUrl;
        this.poster = poster;
    }

    /**
     * Returns whether this reporter will actually publish messages. False when
     * no workstream URL or poster was supplied.
     *
     * @return {@code true} when messages will be posted
     */
    public boolean isEnabled() {
        return workstreamBaseUrl != null && !workstreamBaseUrl.isEmpty() && poster != null;
    }

    /**
     * Publishes a phase-entry message naming the phase and the runner/model/
     * provider/effort that will execute it.
     *
     * @param phase  the lifecycle phase being dispatched
     * @param runner the runner name that will run it
     * @param config the effective phase configuration (model/provider/effort)
     */
    public void phaseEntry(Phase phase, String runner, PhaseConfig config) {
        post(formatPhaseEntry(phase, runner, config));
    }

    /**
     * Publishes a phase-exit message summarising the outcome of {@code result}.
     *
     * @param phase  the lifecycle phase that completed
     * @param result the session result, or {@code null} when none was produced
     */
    public void phaseExit(Phase phase, AgentRunResult result) {
        post(formatPhaseExit(phase, result));
    }

    /**
     * Publishes an inactivity-suspension message describing a watchdog kill.
     *
     * @param runner      the runner whose subprocess was killed
     * @param attempt     the zero-based restart attempt that was just killed
     * @param maxRestarts the configured maximum number of inactivity restarts
     */
    public void inactivitySuspended(String runner, int attempt, int maxRestarts) {
        post(formatInactivity(runner, attempt, maxRestarts));
    }

    /**
     * Publishes an unusual-termination message with the given description.
     *
     * @param description human-readable description of the unusual condition
     */
    public void unusual(String description) {
        post(SYSTEM_PREFIX + UNUSUAL_EMOJI + " " + description);
    }

    /**
     * Formats the phase-entry message. Static and side-effect-free so the
     * formatting is testable without a controller.
     *
     * @param phase  the phase being entered
     * @param runner the runner name
     * @param config the effective phase configuration; may be {@code null}
     * @return the formatted message text
     */
    public static String formatPhaseEntry(Phase phase, String runner, PhaseConfig config) {
        StringBuilder detail = new StringBuilder();
        detail.append(runner != null && !runner.isEmpty() ? runner : "default-runner");
        String model = config != null ? config.model() : null;
        String provider = config != null ? config.provider() : null;
        String effort = config != null ? config.effort() : null;
        if (model != null && !model.isEmpty()) detail.append('/').append(model);
        if (provider != null && !provider.isEmpty()) detail.append(", provider=").append(provider);
        if (effort != null && !effort.isEmpty()) detail.append(", effort=").append(effort);
        return SYSTEM_PREFIX + PHASE_ENTRY_EMOJI + " Entering " + phaseLabel(phase)
                + " (" + detail + ")";
    }

    /**
     * Formats the phase-exit message describing how a session ended.
     *
     * @param phase  the phase that completed
     * @param result the session result, or {@code null}
     * @return the formatted message text
     */
    public static String formatPhaseExit(Phase phase, AgentRunResult result) {
        String outcome;
        if (result == null) {
            outcome = "no result";
        } else if (result.killedForInactivity()) {
            outcome = "killed for inactivity";
        } else if (result.exitCode() == 0) {
            outcome = "success in " + formatDuration(result.durationMs());
        } else {
            outcome = "failed (exit " + result.exitCode() + ") in "
                    + formatDuration(result.durationMs());
        }
        return SYSTEM_PREFIX + PHASE_EXIT_EMOJI + " " + phaseLabel(phase) + " complete — " + outcome;
    }

    /**
     * Formats the inactivity-suspension message.
     *
     * @param runner      the runner whose subprocess was killed
     * @param attempt     the zero-based restart attempt that was killed
     * @param maxRestarts the configured maximum number of inactivity restarts
     * @return the formatted message text
     */
    public static String formatInactivity(String runner, int attempt, int maxRestarts) {
        String who = runner != null && !runner.isEmpty() ? runner : "agent";
        if (attempt >= maxRestarts) {
            return SYSTEM_PREFIX + INACTIVITY_EMOJI + " " + who
                    + " produced no output within the inactivity window — restart limit ("
                    + maxRestarts + ") reached, abandoning session";
        }
        return SYSTEM_PREFIX + INACTIVITY_EMOJI + " " + who
                + " produced no output within the inactivity window — relaunching (attempt "
                + (attempt + 2) + " of " + (maxRestarts + 1) + ")";
    }

    /** Returns an upper-cased, display-friendly label for {@code phase}. */
    private static String phaseLabel(Phase phase) {
        return phase != null ? phase.wireName().toUpperCase() : "UNKNOWN";
    }

    /**
     * Formats a millisecond duration compactly: {@code "Ys"} for under a minute,
     * {@code "Xm Ys"} for under an hour, and {@code "Xh Ym"} for an hour or more.
     * Sub-second values are shown as {@code "0s"}; negative values are clamped to zero.
     *
     * @param millis the duration in milliseconds
     * @return the compact duration string
     */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes >= 60) {
            long hours = minutes / 60L;
            long remainingMinutes = minutes % 60L;
            return hours + "h " + remainingMinutes + "m";
        }
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    /**
     * Posts {@code text} to the workstream message endpoint tagged with the
     * {@link #ACTIVITY} activity. A no-op when the reporter is disabled or the
     * text is empty.
     *
     * @param text the message text to publish
     */
    private void post(String text) {
        if (!isEnabled() || text == null || text.isEmpty()) {
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("text", text);
        body.put("activity", ACTIVITY);
        poster.accept(workstreamBaseUrl + "/messages", body.toString());
    }
}
