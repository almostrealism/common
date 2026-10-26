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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Posts a liveness heartbeat to the controller on a timer, for the whole life
 * of a job, independent of the agent session.
 *
 * <p>The controller otherwise learns a job is alive only as a side effect of a
 * status event landing — which is coupled to agent message activity, exactly
 * what makes a silent-but-healthy agent indistinguishable from a dead worker.
 * This sender decouples the two: it beats on its own scheduled thread from the
 * moment the job starts executing until it terminates, so a job that is quiet
 * because an agent is legitimately deep inside a long tool call still reports
 * itself alive, and a worker that has actually died visibly stops.</p>
 *
 * <p>It does not touch the agent subprocess's output, so it is never mistaken
 * for agent activity by {@code AgentInactivityMonitor} (which watches stdout
 * silence); the two liveness mechanisms are deliberately orthogonal. Each beat
 * carries the worker {@code node} identity, the process {@code pid}, and the
 * current {@code phase} when one is known — the runtime liveness the controller
 * cannot otherwise observe. The payload is harness lifecycle state, not a
 * configured job property, so it does not round-trip through the job factory or
 * codec.</p>
 *
 * <p>Posting reuses the job's own fire-and-forget {@code postJson} sink rather
 * than opening a parallel HTTP path; a {@code null}/empty URL or {@code null}
 * poster makes {@link #start()} a no-op, so a job with no controller in the
 * loop simply never beats.</p>
 *
 * @author Michael Murray
 */
public final class HarnessHeartbeatSender {

    /**
     * Default interval between heartbeats. Short enough that the controller's
     * unresponsive/dead derivations react within minutes of a worker going
     * silent, long enough that the beat traffic is negligible.
     */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(3);

    /** Shared mapper used to build the heartbeat JSON body. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fully-resolved heartbeat endpoint URL; {@code null}/empty disables sending. */
    private final String heartbeatUrl;

    /** Sink that performs the POST: {@code (url, jsonBody) -> void}. */
    private final BiConsumer<String, String> poster;

    /** The worker identity reported in each beat. */
    private final String node;

    /** The process id reported in each beat. */
    private final long pid;

    /** Supplies the current phase for each beat, or {@code null} when none is tracked. */
    private final Supplier<String> phaseSupplier;

    /** Interval between beats. */
    private final Duration interval;

    /** Timer driving the beats; {@code null} until {@link #start()}. */
    private ScheduledExecutorService executor;

    /**
     * Creates a sender posting to {@code heartbeatUrl} every {@code interval}.
     *
     * @param heartbeatUrl  the fully-resolved heartbeat endpoint URL, or
     *                      {@code null}/empty to disable sending
     * @param poster        performs the POST given a URL and JSON body;
     *                      typically {@code GitManagedJob::postJson}
     * @param node          the worker identity to report
     * @param pid           the process id to report
     * @param phaseSupplier supplies the current phase, or {@code null} for none
     * @param interval      the interval between beats; {@code null} uses
     *                      {@link #DEFAULT_INTERVAL}
     */
    public HarnessHeartbeatSender(String heartbeatUrl, BiConsumer<String, String> poster,
                                  String node, long pid, Supplier<String> phaseSupplier,
                                  Duration interval) {
        this.heartbeatUrl = heartbeatUrl;
        this.poster = poster;
        this.node = node;
        this.pid = pid;
        this.phaseSupplier = phaseSupplier;
        this.interval = (interval != null && !interval.isNegative() && !interval.isZero())
                ? interval : DEFAULT_INTERVAL;
    }

    /**
     * Derives the controller's heartbeat URL for a job from the resolved
     * workstream base URL and the job id.
     *
     * <p>The heartbeat route is job-scoped ({@code /api/jobs/{jobId}/heartbeat})
     * rather than nested under the workstream, so this replaces everything from
     * {@code /workstreams/} onward in the workstream URL with the job route. A
     * base URL that does not contain {@code /workstreams/} is treated as the
     * API root and the job route is appended to it.</p>
     *
     * @param workstreamBaseUrl the resolved workstream URL
     *                          ({@code http://host/api/workstreams/{id}}), or
     *                          {@code null}/empty
     * @param jobId             the job identifier
     * @return the heartbeat URL, or {@code null} when no URL or job id was given
     */
    public static String heartbeatUrl(String workstreamBaseUrl, String jobId) {
        if (workstreamBaseUrl == null || workstreamBaseUrl.isEmpty()
                || jobId == null || jobId.isEmpty()) {
            return null;
        }
        int idx = workstreamBaseUrl.indexOf("/workstreams/");
        String apiRoot = idx >= 0 ? workstreamBaseUrl.substring(0, idx)
                : trimTrailingSlash(workstreamBaseUrl);
        return apiRoot + "/jobs/" + jobId + "/heartbeat";
    }

    /** Returns {@code s} without a single trailing slash, if present. */
    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Returns whether this sender will actually beat. False when no URL or
     * poster was supplied.
     *
     * @return {@code true} when heartbeats will be posted
     */
    public boolean isEnabled() {
        return heartbeatUrl != null && !heartbeatUrl.isEmpty() && poster != null;
    }

    /**
     * Starts the periodic heartbeat. A no-op when already started or disabled.
     *
     * <p>The scheduled task swallows any {@link RuntimeException} a beat throws,
     * because {@link ScheduledExecutorService#scheduleAtFixedRate} stops
     * repeating after an uncaught exception: letting one propagate would
     * silence the job for the rest of its life, defeating the purpose.</p>
     */
    public synchronized void start() {
        if (executor != null || !isEnabled()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "harness-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long seconds = interval.getSeconds();
        executor.scheduleAtFixedRate(() -> {
            try {
                beatOnce();
            } catch (RuntimeException ignored) {
                // A failed beat must never stop the schedule.
            }
        }, 0, seconds, TimeUnit.SECONDS);
    }

    /**
     * Posts a single heartbeat. Separated from the timer so the payload can be
     * exercised without waiting on a schedule.
     */
    public void beatOnce() {
        if (!isEnabled()) return;
        ObjectNode body = MAPPER.createObjectNode();
        if (node != null && !node.isEmpty()) body.put("node", node);
        body.put("pid", pid);
        String phase = phaseSupplier != null ? phaseSupplier.get() : null;
        if (phase != null && !phase.isEmpty()) body.put("phase", phase);
        poster.accept(heartbeatUrl, body.toString());
    }

    /** Stops the periodic heartbeat. Safe to call when never started. */
    public synchronized void stop() {
        if (executor == null) return;
        executor.shutdownNow();
        executor = null;
    }
}
