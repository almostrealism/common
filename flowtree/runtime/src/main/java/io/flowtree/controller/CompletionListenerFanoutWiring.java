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

package io.flowtree.controller;

import io.flowtree.jobs.RestartGovernor;
import io.flowtree.Server;
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.jobs.CompletionListenerFanout;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackListener;
import io.flowtree.slack.SlackNotifier;
import org.almostrealism.io.Alert;
import org.almostrealism.io.AlertRecipients;
import org.almostrealism.io.Console;

import java.util.Map;

/**
 * Wires the {@link CompletionListenerFanout} onto a running
 * {@link FlowTreeApiEndpoint}. Extracted from {@link FlowTreeController}
 * to keep that file under the project's per-file line budget; the
 * wiring logic itself is small but its inline presence had been
 * pushing the controller over the limit.
 */
public final class CompletionListenerFanoutWiring {

    /**
     * Utility class; not instantiable.
     */
    private CompletionListenerFanoutWiring() {}

    /**
     * Constructs a fan-out bound to the supplied endpoint, server,
     * stats store, and listener, then attaches it to the endpoint so
     * the status-event handler spawns wake-up jobs on listener
     * workstreams whenever a job reaches a terminal status. Failures
     * are logged but not propagated; a misconfigured fan-out is a
     * degraded state but not a controller-crashing one, and the
     * inert default (no listeners configured) is preserved.
     *
     * <p>Also starts the {@link StuckJobScanner}, which belongs to the same
     * wiring step rather than beside it: the scanner exists to release
     * listeners blocked behind a job that never reported, so it is only
     * correct once the fan-out it dispatches through is attached. Started
     * here, it cannot be wired in the wrong order.</p>
     *
     * <p>Also starts the {@link LostJobRecovery} counterpart, which watches for
     * {@code SUBMITTED} jobs no worker ever started and escalates them (it never
     * blindly re-dispatches) through the same workstream channel plus an
     * out-of-band alert. Whether a lost job is still queued on the controller or
     * was already handed to a peer is read from the server's own
     * {@link io.flowtree.node.NodeGroup}.</p>
     *
     * @param endpoint             the endpoint to attach the fan-out to
     * @param server               the server used to submit wake-up jobs
     * @param statsStore           the job history; also scanned for stalls
     * @param listener             the Slack listener backing job submission
     * @param primaryNotifier      the primary workspace notifier
     * @param notifiersByWorkspace every configured per-workspace notifier
     * @param alertRecipients      the directory used to raise an out-of-band
     *                             alert about a lost job, or {@code null} for none
     * @return the started scanners, so the caller can stop them on shutdown;
     *         both fields are {@code null} when wiring failed
     */
    public static ControllerScanners wire(FlowTreeApiEndpoint endpoint,
                            Server server,
                            JobStatsStore statsStore,
                            SlackListener listener,
                            SlackNotifier primaryNotifier,
                            Map<String, SlackNotifier> notifiersByWorkspace,
                            AlertRecipients alertRecipients) {
        try {
            NotifierRegistry registry = new NotifierRegistry(
                    primaryNotifier, notifiersByWorkspace);
            CompletionListenerFanout fanout = CompletionListenerFanout.bind(
                    registry, endpoint, server, statsStore, listener);
            endpoint.setCompletionListenerFanout(fanout);

            StuckJobScanner scanner = new StuckJobScanner(statsStore,
                    RestartGovernor.DEFAULT_MAX_WALL_CLOCK, endpoint::completeJob);
            // Reclaim sooner than the 12h wall-clock default (see
            // DEFAULT_DEAD_THRESHOLD) and surface the stall through the channel.
            scanner.setDeadThreshold(StuckJobScanner.DEFAULT_DEAD_THRESHOLD);
            scanner.setWarningSink(registry::postWorkstreamNotice);
            scanner.start();

            LostJobRecovery lostJobRecovery = new LostJobRecovery(statsStore,
                    registry::postWorkstreamNotice,
                    alertRecipients == null ? null : alertRecipients.sink(Alert.Severity.WARNING),
                    taskId -> server.getNodeGroup().hasQueuedJob(taskId));
            lostJobRecovery.start();

            return new ControllerScanners(scanner, lostJobRecovery);
        } catch (RuntimeException ex) {
            Console root = Console.root();
            root.println("CompletionListenerFanoutWiring: "
                    + "Completion-listener fanout wiring failed: " + ex.getMessage());
            return new ControllerScanners(null, null);
        }
    }

    /**
     * The two post-completion scanners started by {@link #wire}, bundled so the
     * controller can stop both on shutdown. Either field may be {@code null} when
     * wiring failed.
     *
     * @param stuck the scanner that fails jobs which started and went silent
     * @param lost  the scanner that escalates jobs that never started
     */
    public record ControllerScanners(StuckJobScanner stuck, LostJobRecovery lost) {

        /** Stops both scanners; a {@code null} scanner is skipped. */
        public void stopAll() {
            if (stuck != null) stuck.stop();
            if (lost != null) lost.stop();
        }
    }
}
