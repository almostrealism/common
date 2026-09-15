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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps track of which tool calls an agent currently has in flight, by pairing
 * the {@link AgentActivity} boundaries a runner reads out of its output stream.
 *
 * <p>An agent emits nothing while a tool runs. For a shell command the agent
 * wrote itself that silence is exactly what the inactivity watchdog exists to
 * bound — an unterminated polling loop looks the same as a hang, and is one.
 * For an MCP-served tool the silence is a bounded wait on another process
 * (a test run, a build validation, a download, a peer's message), and killing
 * the agent in the middle of it loses the work for nothing. This tracker is
 * how {@link AgentInactivityMonitor} tells the two apart.</p>
 *
 * <p>Observation is driven by the output pump: every line is offered to the
 * runner's classifier, and the resulting boundaries open or close entries in
 * the in-flight table. A line that is not a tool boundary yields nothing and
 * costs nothing beyond the classifier's own filtering.</p>
 *
 * @author Michael Murray
 * @see AgentActivity
 * @see AgentInactivityMonitor
 */
public final class AgentActivityTracker {

    /** Reads tool-call boundaries out of one line of runner output. */
    private final Function<String, List<AgentActivity>> classifier;

    /** Tool calls started but not yet finished, keyed by tool-use id. Guarded by {@code this}. */
    private final Map<String, String> inFlight = new LinkedHashMap<>();

    /**
     * Creates a tracker fed by the given classifier.
     *
     * @param classifier maps one output line to the tool-call boundaries it
     *                   carries; an empty list for a line that is not one
     */
    public AgentActivityTracker(Function<String, List<AgentActivity>> classifier) {
        if (classifier == null) throw new IllegalArgumentException("classifier must not be null");
        this.classifier = classifier;
    }

    /**
     * Offers one line of runner output to the classifier and records the
     * boundaries it finds. A classifier failure is treated as "not a boundary"
     * so that a malformed line can never stall the output pump.
     *
     * @param line one line of subprocess output
     */
    public void observe(String line) {
        List<AgentActivity> activities;

        try {
            activities = classifier.apply(line);
        } catch (RuntimeException e) {
            return;
        }

        if (activities == null || activities.isEmpty()) return;

        synchronized (this) {
            for (AgentActivity activity : activities) {
                if (activity == null || activity.toolUseId() == null) continue;

                if (activity.started()) {
                    inFlight.put(activity.toolUseId(),
                            activity.toolName() == null ? "" : activity.toolName());
                } else {
                    inFlight.remove(activity.toolUseId());
                }
            }
        }
    }

    /**
     * Returns whether any MCP-served tool call is currently in flight.
     *
     * @return {@code true} when a call whose tool name carries
     *         {@link AgentActivity#MCP_TOOL_PREFIX} has started and not finished
     */
    public synchronized boolean isMcpCallInFlight() {
        for (String toolName : inFlight.values()) {
            if (toolName.startsWith(AgentActivity.MCP_TOOL_PREFIX)) return true;
        }

        return false;
    }

    /**
     * Returns the number of tool calls started but not yet finished.
     *
     * @return the in-flight count
     */
    public synchronized int inFlightCount() { return inFlight.size(); }
}
