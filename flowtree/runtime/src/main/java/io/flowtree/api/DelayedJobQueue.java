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

package io.flowtree.api;

import io.flowtree.Server;
import io.flowtree.job.JobFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The dispatch path from API submissions to one {@link Server}: jobs are handed
 * to the server immediately, or held until a requested delay elapses.
 *
 * <p>Every submission path of {@link FlowTreeApiEndpoint} — coding-agent and
 * shell-command alike — dispatches through the endpoint's single queue, so a
 * job's pending state is tracked in one place regardless of its type. The
 * queue is bound to its server at construction, so a caller cannot dispatch a
 * job to any server other than the one the queue tracks pending jobs for.</p>
 *
 * @author Michael Murray
 * @see FlowTreeApiEndpoint
 */
final class DelayedJobQueue {

    /** The server every job in this queue is dispatched to. */
    private final Server server;

    /** Schedules delayed dispatches. Daemon thread so it never blocks JVM shutdown. */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flowtree-delay");
        t.setDaemon(true);
        return t;
    });

    /** Task ID to pending future, removed when the dispatch runs. Concurrent for NanoHTTPD workers. */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /**
     * Constructs a queue that dispatches to {@code server}.
     *
     * @param server the server every job in this queue is dispatched to
     */
    DelayedJobQueue(Server server) {
        this.server = server;
    }

    /**
     * Hands {@code factory} to this queue's server, after {@code delaySeconds}
     * when positive and immediately otherwise.
     *
     * @param factory      the job to dispatch
     * @param delaySeconds the delay before dispatch; {@code 0} or less for immediate
     * @return {@code true} when the dispatch was deferred, {@code false} when
     *         the job was handed to the server immediately
     */
    boolean submit(JobFactory factory, int delaySeconds) {
        if (delaySeconds <= 0) {
            server.addTask(factory);
            return false;
        }

        String taskId = factory.getTaskId();
        pending.put(taskId, executor.schedule(() -> {
            try {
                server.addTask(factory);
            } finally {
                pending.remove(taskId);
            }
        }, delaySeconds, TimeUnit.SECONDS));
        return true;
    }

    /**
     * Returns whether the job with {@code taskId} is waiting for its delay to
     * elapse.
     *
     * @param taskId the job's task ID
     * @return {@code true} while the job's dispatch is deferred
     */
    boolean isPending(String taskId) {
        return pending.containsKey(taskId);
    }
}
