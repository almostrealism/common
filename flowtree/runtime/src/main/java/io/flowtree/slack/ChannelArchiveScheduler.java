/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.slack;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Defers archiving a Slack channel until shortly after a {@code /flowtree
 * archive} command has been acknowledged.
 *
 * <p>The command is acknowledged and replied to immediately; the channel is
 * then archived after {@link #CHANNEL_ARCHIVE_DELAY_MILLIS} so the Slack client
 * registers the acknowledged command before its channel disappears, instead of
 * leaving the typed command as an unsent draft in a channel that is no longer
 * reachable.</p>
 *
 * <p>All instances share one daemon thread for the deferred work, so rebuilding
 * workspace connections cannot accumulate one idle thread per notifier over the
 * controller's lifetime.</p>
 */
class ChannelArchiveScheduler {
    /**
     * Delay between acknowledging a {@code /flowtree archive} command and
     * archiving its Slack channel.
     */
    public static final long CHANNEL_ARCHIVE_DELAY_MILLIS = 4000L;

    /**
     * Runs a task after a delay. Abstracts the scheduled executor so tests can
     * drive {@link #schedule} without a real wait.
     */
    @FunctionalInterface
    public interface DeferredExecutor {
        /**
         * Schedules {@code task} to run once, approximately {@code delayMillis}
         * from now.
         *
         * @param delayMillis delay before the task runs, in milliseconds
         * @param task        the task to run
         */
        void executeAfter(long delayMillis, Runnable task);
    }

    /**
     * Scheduler shared by every instance. A single daemon thread backs all
     * schedulers; created on first use and never shut down, as the daemon thread
     * does not keep the JVM alive.
     */
    private static ScheduledExecutorService sharedScheduler;

    /** Performs the archive, returning an error string or {@code null} on success. */
    private final Function<String, String> archiveAction;

    /** Executor used to defer the archive; lazily created, overridable for tests. */
    private DeferredExecutor deferredExecutor;

    /**
     * Creates a scheduler that archives channels through {@code archiveAction}.
     *
     * @param archiveAction archives a channel by id, returning an error string
     *                      or {@code null} on success
     */
    ChannelArchiveScheduler(Function<String, String> archiveAction) {
        this.archiveAction = archiveAction;
    }

    /**
     * Archives {@code channelId} after {@link #CHANNEL_ARCHIVE_DELAY_MILLIS},
     * reporting any failure through {@code onFailure}.
     *
     * <p>The caller is expected to have already replied to the user and let the
     * command be acknowledged; deferring the archive keeps the channel reachable
     * until that reply has been delivered. A failure from the delayed archive is
     * surfaced through {@code onFailure} rather than lost, since the original
     * command has already returned.</p>
     *
     * @param channelId the Slack channel to archive
     * @param onFailure invoked with the error string when the delayed archive fails
     */
    synchronized void schedule(String channelId, Consumer<String> onFailure) {
        if (deferredExecutor == null) {
            deferredExecutor = defaultDeferredExecutor();
        }
        deferredExecutor.executeAfter(CHANNEL_ARCHIVE_DELAY_MILLIS, () -> {
            String error = archiveAction.apply(channelId);
            if (error != null) onFailure.accept(error);
        });
    }

    /**
     * Overrides the executor used by {@link #schedule}. Intended for tests that
     * need to observe or control the deferred archive.
     *
     * @param executor the executor to use
     */
    void setDeferredExecutor(DeferredExecutor executor) {
        this.deferredExecutor = executor;
    }

    /**
     * Builds the default deferred executor, backed by the process-wide
     * {@link #sharedScheduler} so that all instances share one daemon thread
     * rather than each leaking its own.
     *
     * @return a {@link DeferredExecutor} backed by the shared scheduled thread
     */
    private static synchronized DeferredExecutor defaultDeferredExecutor() {
        if (sharedScheduler == null) {
            sharedScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "slack-channel-archive");
                thread.setDaemon(true);
                return thread;
            });
        }
        ScheduledExecutorService service = sharedScheduler;
        return (delayMillis, task) -> service.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }
}
