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

package io.flowtree.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.methods.response.conversations.ConversationsCreateResponse;
import com.slack.api.methods.response.conversations.ConversationsInviteResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.ConversationType;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;
import org.almostrealism.io.Alert;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.ConversationType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Posts job status updates to Slack channels using the Slack SDK.
 *
 * <p>This class implements {@link JobCompletionListener} to receive job events
 * and formats them as Slack messages with emoji status indicators.</p>
 *
 * @author Michael Murray
 * @see JobCompletionListener
 * @see FlowTreeController
 */
public class SlackNotifier implements JobCompletionListener, ConsoleFeatures {

    /** Maximum number of completed jobs to retain per workstream. */
    private static final int MAX_JOB_HISTORY = 100;

    private final String botToken;
    private final MethodsClient client;
    private final Map<String, SlackWorkstream> workstreams;
    private final Map<String, String> jobThreadTs;
    private final Map<String, Map<String, JobCompletionEvent>> jobHistory;
    private final Map<String, JobCompletionEvent> jobById;
    private final Map<String, Long> lastJobStartTime;
    private Consumer<String> messageCallback;
    private JobStatsStore statsStore;
    private String channelOwnerUserId;
    private String defaultChannelId;

    /**
     * Creates a new notifier with the specified bot token.
     *
     * @param botToken the Slack Bot User OAuth Token (xoxb-...)
     */
    public SlackNotifier(String botToken) {
        this.botToken = botToken;
        this.workstreams = new HashMap<>();
        this.jobThreadTs = new HashMap<>();
        this.jobHistory = new HashMap<>();
        this.jobById = new ConcurrentHashMap<>();
        this.lastJobStartTime = new ConcurrentHashMap<>();

        if (botToken != null && !botToken.isEmpty()) {
            this.client = Slack.getInstance().methods(botToken);
        } else {
            this.client = null;
        }
    }

    /**
     * Registers a workstream for notifications.
     * Messages for this workstream will be posted to its configured channel.
     *
     * @param workstream the workstream configuration
     */
    public void registerWorkstream(SlackWorkstream workstream) {
        workstreams.put(workstream.getWorkstreamId(), workstream);
    }

    /**
     * Returns the workstream for a given ID.
     *
     * @param workstreamId the workstream identifier
     * @return the workstream, or null if not registered
     */
    public SlackWorkstream getWorkstream(String workstreamId) {
        return workstreams.get(workstreamId);
    }

    /**
     * Returns all registered workstreams keyed by workstream ID.
     *
     * @return an unmodifiable view of the workstream map
     */
    public Map<String, SlackWorkstream> getWorkstreams() {
        return Collections.unmodifiableMap(workstreams);
    }

    /**
     * Creates a new private Slack channel with the given name.
     *
     * <p>The channel is created as private. If a channel owner user ID has been
     * configured via {@link #setChannelOwnerUserId(String)}, that user is
     * automatically invited to the channel.</p>
     *
     * <p>If the Slack client is not available (simulation mode), this method
     * logs the request and returns {@code null}.</p>
     *
     * @param name the channel name (lowercase, no spaces, max 80 chars)
     * @return the channel ID of the created channel, or null if creation
     *         failed or the client is unavailable
     */
    public String createChannel(String name) {
        if (client == null) {
            log("No bot token - channel '" + name + "' would be created");
            return null;
        }

        try {
            ConversationsCreateResponse response = client.conversationsCreate(req -> req
                .name(name)
                .isPrivate(true)
            );

            if (response.isOk()) {
                String channelId = response.getChannel().getId();
                log("Created private Slack channel " + name + " (" + channelId + ")");

                if (channelOwnerUserId != null && !channelOwnerUserId.isEmpty()) {
                    inviteUserToChannel(channelId, channelOwnerUserId);
                }

                return channelId;
            } else if ("name_taken".equals(response.getError())) {
                // Channel already exists — look it up by name
                log("Channel '" + name + "' already exists, looking up by name");
                return findChannelByName(name);
            } else {
                warn("Failed to create channel '" + name + "': " + response.getError());
            }
        } catch (IOException | SlackApiException e) {
            warn("Error creating channel '" + name + "': " + e.getMessage());
        }

        return null;
    }

    /**
     * Finds a channel by name that the bot is a member of.
     *
     * <p>Uses {@code conversations.list} with {@code types=private_channel}
     * to find channels the bot has access to. Only returns a match if the
     * bot is already a member of the channel.</p>
     *
     * @param name the channel name (without #)
     * @return the channel ID, or null if not found or bot is not a member
     */
    private String findChannelByName(String name) {
        if (client == null) return null;

        try {
            String cursor = null;
            do {
                final String pageCursor = cursor;
                ConversationsListResponse response =
                    client.conversationsList(req -> {
                        req.types(Arrays.asList(
                            ConversationType.PRIVATE_CHANNEL,
                            ConversationType.PUBLIC_CHANNEL));
                        req.limit(200);
                        if (pageCursor != null) req.cursor(pageCursor);
                        return req;
                    });

                if (!response.isOk()) {
                    warn("Failed to list channels: " + response.getError());
                    return null;
                }

                for (Conversation channel : response.getChannels()) {
                    if (name.equals(channel.getName()) && channel.isMember()) {
                        String channelId = channel.getId();
                        log("Found existing channel '" + name + "' (" + channelId + ")");
                        return channelId;
                    }
                }

                cursor = response.getResponseMetadata() != null
                    ? response.getResponseMetadata().getNextCursor() : null;
            } while (cursor != null && !cursor.isEmpty());

            log("Channel '" + name + "' exists but bot is not a member");
        } catch (IOException | SlackApiException e) {
            warn("Error looking up channel '" + name + "': " + e.getMessage());
        }

        return null;
    }

    /**
     * Invites a user to a Slack channel.
     *
     * @param channelId the channel to invite the user to
     * @param userId    the Slack user ID to invite
     */
    private void inviteUserToChannel(String channelId, String userId) {
        try {
            ConversationsInviteResponse response = client.conversationsInvite(req -> req
                .channel(channelId)
                .users(Collections.singletonList(userId))
            );

            if (response.isOk()) {
                log("Invited user " + userId + " to channel " + channelId);
            } else {
                warn("Failed to invite user " + userId + " to channel "
                    + channelId + ": " + response.getError());
            }
        } catch (IOException | SlackApiException e) {
            warn("Error inviting user to channel: " + e.getMessage());
        }
    }

    /**
     * Sets the job stats store for recording timing data.
     *
     * @param store the stats store, or null to disable recording
     */
    public void setStatsStore(JobStatsStore store) {
        this.statsStore = store;
        if (store != null) {
            loadJobHistoryFromStore(store);
        }
    }

    /**
     * Pre-populates the in-memory job caches from the persistent store so that
     * recent jobs survive a controller restart.  Loads up to
     * {@link #MAX_JOB_HISTORY} jobs per registered workstream.
     */
    private void loadJobHistoryFromStore(JobStatsStore store) {
        for (String wsId : workstreams.keySet()) {
            List<JobCompletionEvent> recent = store.getRecentJobs(wsId, MAX_JOB_HISTORY);
            // getRecentJobs returns newest-first; put oldest-first into the LRU map
            for (int i = recent.size() - 1; i >= 0; i--) {
                JobCompletionEvent e = recent.get(i);
                trackJob(wsId, e);
            }
        }
        log("Loaded job history from persistent store for "
                + workstreams.size() + " workstream(s)");
    }

    /**
     * Returns the job stats store.
     */
    public JobStatsStore getStatsStore() {
        return statsStore;
    }

    /**
     * Sets the Slack user ID to be automatically invited to newly created channels.
     *
     * <p>When set, {@link #createChannel(String)} will invite this user to every
     * private channel it creates. Typically set from the {@code SLACK_CHANNEL_OWNER}
     * environment variable during controller startup.</p>
     *
     * @param userId the Slack user ID (e.g., "U0123456789")
     */
    public void setChannelOwnerUserId(String userId) {
        this.channelOwnerUserId = userId;
    }

    /**
     * Sets the default Slack channel ID used as a fallback when a
     * workstream has no channel configured or when publishing to
     * the configured channel fails.
     *
     * @param channelId the fallback Slack channel ID (e.g., "C0123456789")
     */
    public void setDefaultChannelId(String channelId) {
        this.defaultChannelId = channelId;
    }

    /**
     * Returns the default Slack channel ID used as a fallback.
     *
     * @return the fallback channel ID, or null if not configured
     */
    public String getDefaultChannelId() {
        return defaultChannelId;
    }

    /**
     * Finds a workstream whose {@code defaultBranch} exactly matches the
     * given branch name. Workstreams with a null {@code defaultBranch}
     * are skipped. If multiple workstreams match, the first one found is
     * returned.
     *
     * @param branch the branch name to match (e.g., "feature/new-decoder")
     * @return the matching workstream, or null if no match is found
     */
    public SlackWorkstream findWorkstreamByBranch(String branch) {
        if (branch == null || branch.isEmpty()) {
            return null;
        }

        for (SlackWorkstream ws : workstreams.values()) {
            if (branch.equals(ws.getDefaultBranch())) {
                return ws;
            }
        }

        return null;
    }

    /**
     * Finds a workstream whose {@code defaultBranch} and {@code repoUrl}
     * both match the given values. If {@code repoUrl} is null, falls back
     * to matching on branch alone.
     *
     * @param branch  the branch name to match
     * @param repoUrl the repository URL to match (may be null)
     * @return the matching workstream, or null if no match is found
     */
    public SlackWorkstream findWorkstreamByBranchAndRepo(String branch, String repoUrl) {
        if (branch == null || branch.isEmpty()) {
            return null;
        }

        if (repoUrl == null || repoUrl.isEmpty()) {
            return findWorkstreamByBranch(branch);
        }

        for (SlackWorkstream ws : workstreams.values()) {
            if (branch.equals(ws.getDefaultBranch())
                    && repoUrl.equals(ws.getRepoUrl())) {
                return ws;
            }
        }

        return null;
    }

    /**
     * Sets a callback for receiving formatted messages (useful for testing).
     *
     * @param callback the callback to receive message text
     */
    public void setMessageCallback(Consumer<String> callback) {
        this.messageCallback = callback;
    }

    @Override
    public void onJobSubmitted(String workstreamId, JobCompletionEvent event) {
        onJobSubmitted(workstreamId, event, null);
    }

    /**
     * Notifies that a job has been submitted and dispatched to an agent,
     * optionally threading under an existing message.
     *
     * @param workstreamId the workstream identifier
     * @param event        the submission event
     * @param replyTo      if non-null, post the message as a reply under
     *                      this message timestamp (creating a thread)
     */
    public void onJobSubmitted(String workstreamId, JobCompletionEvent event, String replyTo) {
        SlackWorkstream workstream = workstreams.get(workstreamId);
        if (workstream == null) {
            warn("Unknown workstream: " + workstreamId);
            return;
        }

        trackJob(workstreamId, event);
        lastJobStartTime.put(workstreamId, System.currentTimeMillis());

        String message = formatSubmittedMessage(event, workstream);
        String ts;

        if (replyTo != null && !replyTo.isEmpty()) {
            // Post as a thread reply under the user's message
            ts = postMessageInThread(workstream.getChannelId(), message, replyTo);
            // Store the user's message ts as the thread parent so completion
            // messages also appear in the same thread
            if (event.getJobId() != null) {
                jobThreadTs.put(event.getJobId(), replyTo);
            }
        } else {
            ts = postMessage(workstream.getChannelId(), message);
            if (ts != null && event.getJobId() != null) {
                jobThreadTs.put(event.getJobId(), ts);
            }
        }
    }

    @Override
    public void onJobStarted(String workstreamId, JobCompletionEvent event) {
        SlackWorkstream workstream = workstreams.get(workstreamId);
        if (workstream == null) {
            warn("Unknown workstream: " + workstreamId);
            return;
        }

        trackJob(workstreamId, event);

        if (statsStore != null) {
            statsStore.recordJobStarted(event.getJobId(), workstreamId,
                event.getDescription(), event.getTimestamp());
        }

        String message = formatStartedMessage(event, workstream);

        // Thread under the submission message if one exists
        String threadTs = jobThreadTs.get(event.getJobId());
        if (threadTs != null) {
            postMessageInThread(workstream.getChannelId(), message, threadTs);
        } else {
            postMessage(workstream.getChannelId(), message);
        }
    }

    @Override
    public void onJobCompleted(String workstreamId, JobCompletionEvent event) {
        SlackWorkstream workstream = workstreams.get(workstreamId);
        if (workstream == null) {
            warn("Unknown workstream: " + workstreamId);
            return;
        }

        trackJob(workstreamId, event);

        if (statsStore != null) {
            statsStore.recordJobCompleted(workstreamId, event);
        }

        String message = formatCompletedMessage(event, workstream);
        String threadTs = event.getJobId() != null ? jobThreadTs.remove(event.getJobId()) : null;

        if (threadTs != null) {
            postMessageInThread(workstream.getChannelId(), message, threadTs);
        } else {
            postMessage(workstream.getChannelId(), message);
        }

        // Fire SMS alert via SignalWire (no-op if no provider is attached)
        fireCompletionAlert(event, workstream);
    }

    /**
     * Posts a message directly to a channel.
     *
     * @param channelId the Slack channel ID
     * @param text      the message text (supports Slack mrkdwn formatting)
     * @return the message timestamp (for threading), or null on failure
     */
    public String postMessage(String channelId, String text) {
        String effectiveChannel = resolveChannel(channelId);
        if (effectiveChannel == null) {
            log("No channel ID and no default channel - skipping message post");
            return null;
        }

        // Notify callback for testing
        if (messageCallback != null) {
            messageCallback.accept("{\"channel\":\"" + effectiveChannel + "\",\"text\":\"" +
                                   escapeJson(text) + "\"}");
        }

        if (client == null) {
            log("No bot token - message would be posted to " + effectiveChannel + ":");
            log(text);
            return null;
        }

        try {
            ChatPostMessageResponse response = client.chatPostMessage(req -> req
                .channel(effectiveChannel)
                .text(text)
                .unfurlLinks(false)
                .unfurlMedia(false)
            );

            if (response.isOk()) {
                return response.getTs();
            } else {
                warn("Failed to post message to " + effectiveChannel + ": " + response.getError());
                return postToFallbackChannel(effectiveChannel, text, null);
            }
        } catch (IOException | SlackApiException e) {
            warn("Error posting message to " + effectiveChannel + ": " + e.getMessage());
            return postToFallbackChannel(effectiveChannel, text, null);
        }
    }

    /**
     * Posts a message as a thread reply under an existing message.
     *
     * @param channelId the Slack channel ID
     * @param text      the message text (supports Slack mrkdwn formatting)
     * @param threadTs  the timestamp of the parent message to reply under
     * @return the reply message timestamp, or null on failure
     */
    public String postMessageInThread(String channelId, String text, String threadTs) {
        String effectiveChannel = resolveChannel(channelId);
        if (effectiveChannel == null) {
            log("No channel ID and no default channel - skipping thread reply");
            return null;
        }

        if (messageCallback != null) {
            messageCallback.accept("{\"channel\":\"" + effectiveChannel +
                                   "\",\"thread_ts\":\"" + escapeJson(threadTs) +
                                   "\",\"text\":\"" + escapeJson(text) + "\"}");
        }

        if (client == null) {
            log("No bot token - thread reply would be posted to " + effectiveChannel +
                " (thread " + threadTs + "):");
            log(text);
            return null;
        }

        try {
            ChatPostMessageResponse response = client.chatPostMessage(req -> req
                .channel(effectiveChannel)
                .text(text)
                .threadTs(threadTs)
                .unfurlLinks(false)
                .unfurlMedia(false)
            );

            if (response.isOk()) {
                return response.getTs();
            } else {
                warn("Failed to post thread reply to " + effectiveChannel + ": " + response.getError());
                return postToFallbackChannel(effectiveChannel, text, null);
            }
        } catch (IOException | SlackApiException e) {
            warn("Error posting thread reply to " + effectiveChannel + ": " + e.getMessage());
            return postToFallbackChannel(effectiveChannel, text, null);
        }
    }

    /**
     * Resolves the effective channel ID for posting. If the provided channel
     * is null or empty, falls back to the configured default channel.
     *
     * @param channelId the primary channel ID (may be null)
     * @return the resolved channel ID, or null if no channel is available
     */
    private String resolveChannel(String channelId) {
        if (channelId != null && !channelId.isEmpty()) {
            return channelId;
        }
        return defaultChannelId;
    }

    /**
     * Attempts to post a message to the default fallback channel after a
     * failure on the primary channel. No-op if the default channel is not
     * configured or is the same as the failed channel.
     *
     * @param failedChannel the channel that failed
     * @param text          the message text
     * @param threadTs      the thread timestamp (may be null for top-level messages)
     * @return the message timestamp from the fallback post, or null
     */
    private String postToFallbackChannel(String failedChannel, String text, String threadTs) {
        if (defaultChannelId == null || defaultChannelId.isEmpty()
                || defaultChannelId.equals(failedChannel)) {
            return null;
        }

        log("Falling back to default channel " + defaultChannelId);

        try {
            ChatPostMessageResponse response;
            if (threadTs != null) {
                response = client.chatPostMessage(req -> req
                    .channel(defaultChannelId)
                    .text(text)
                    .threadTs(threadTs)
                    .unfurlLinks(false)
                    .unfurlMedia(false)
                );
            } else {
                response = client.chatPostMessage(req -> req
                    .channel(defaultChannelId)
                    .text(text)
                    .unfurlLinks(false)
                    .unfurlMedia(false)
                );
            }

            if (response.isOk()) {
                return response.getTs();
            } else {
                warn("Failed to post to fallback channel: " + response.getError());
            }
        } catch (IOException | SlackApiException e) {
            warn("Error posting to fallback channel: " + e.getMessage());
        }

        return null;
    }

    /**
     * Returns the Slack thread timestamp for a job, if one has been established.
     * This is the timestamp of the submission message posted when the job was queued.
     *
     * @param jobId the job ID
     * @return the thread timestamp, or null if no thread exists for this job
     */
    public String getThreadTs(String jobId) {
        return jobId != null ? jobThreadTs.get(jobId) : null;
    }

    /**
     * Tracks a job event for the {@code /flowtree jobs} command.
     * Maintains a bounded history per workstream.
     *
     * @param workstreamId the workstream identifier
     * @param event        the job event to track
     */
    private void trackJob(String workstreamId, JobCompletionEvent event) {
        if (event.getJobId() == null) return;

        Map<String, JobCompletionEvent> jobs = jobHistory.computeIfAbsent(
            workstreamId, k -> new LinkedHashMap<String, JobCompletionEvent>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JobCompletionEvent> eldest) {
                    return size() > MAX_JOB_HISTORY;
                }
            });
        jobs.put(event.getJobId(), event);
        jobById.put(event.getJobId(), event);
    }

    /**
     * Returns a specific job event by its job ID, across all workstreams.
     * Checks the persistent store first (when available), then falls back
     * to the in-memory cache.
     *
     * @param jobId the job identifier
     * @return the most recent event for that job, or null if not found
     */
    public JobCompletionEvent getJob(String jobId) {
        if (jobId == null) return null;
        if (statsStore != null) {
            JobCompletionEvent persisted = statsStore.getJob(jobId);
            if (persisted != null) return persisted;
        }
        return jobById.get(jobId);
    }

    /**
     * Returns recent jobs for a workstream from the persistent store (newest first),
     * falling back to the in-memory cache when the store is unavailable.
     *
     * @param workstreamId the workstream identifier
     * @param limit        maximum number of jobs to return
     * @return list of events, newest first
     */
    public List<JobCompletionEvent> getRecentJobs(String workstreamId, int limit) {
        if (statsStore != null) {
            return statsStore.getRecentJobs(workstreamId, limit);
        }
        // In-memory fallback: history is oldest-first; return newest-first slice
        Map<String, JobCompletionEvent> history = jobHistory.get(workstreamId);
        if (history == null) return Collections.emptyList();
        List<JobCompletionEvent> all = new ArrayList<>(history.values());
        int fromIndex = Math.max(0, all.size() - limit);
        List<JobCompletionEvent> page = new ArrayList<>(all.subList(fromIndex, all.size()));
        Collections.reverse(page);
        return page;
    }

    /**
     * Returns recent jobs for a workstream, ordered from oldest to newest.
     *
     * @param workstreamId the workstream identifier
     * @return an unmodifiable map of job ID to event, or an empty map
     */
    public Map<String, JobCompletionEvent> getRecentJobs(String workstreamId) {
        Map<String, JobCompletionEvent> jobs = jobHistory.get(workstreamId);
        if (jobs == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(jobs);
    }

    /**
     * Checks whether any job on the given workstream was started after the
     * specified epoch timestamp. Used by the submission endpoint to skip
     * stale auto-resolve jobs from CI pipelines that ran hours ago.
     *
     * <p>The tracking map is in-memory and reset on controller restart,
     * which is safe: a restart means no false positives, only missed guards.</p>
     *
     * @param workstreamId the workstream identifier
     * @param epochMillis  the epoch milliseconds threshold
     * @return {@code true} if a more recent job exists
     */
    public boolean hasJobStartedAfter(String workstreamId, long epochMillis) {
        Long last = lastJobStartTime.get(workstreamId);
        return last != null && last > epochMillis;
    }

    /**
     * Fires an SMS alert via {@link Console#alert(Alert)} summarizing
     * the job completion. This is a no-op if no {@link org.almostrealism.io.AlertDeliveryProvider}
     * is registered (e.g., no {@code signalwire.properties} file).
     */
    private void fireCompletionAlert(JobCompletionEvent event, SlackWorkstream workstream) {
        StringBuilder sb = new StringBuilder();
        sb.append("Job ").append(event.getStatus().name().toLowerCase());
        if (workstream.getChannelName() != null) {
            sb.append(" (").append(workstream.getChannelName()).append(")");
        }
        sb.append(": ").append(truncate(event.getDescription(), 80));
        if (event.getPullRequestUrl() != null) {
            sb.append(" | PR: ").append(event.getPullRequestUrl());
        }
        if (event.getCostUsd() > 0) {
            sb.append(String.format(" | $%.2f", event.getCostUsd()));
        }
        Console.root().alert(new Alert(Alert.Severity.INFO, sb.toString()));
    }

    private String formatSubmittedMessage(JobCompletionEvent event, SlackWorkstream workstream) {
        StringBuilder sb = new StringBuilder();
        sb.append(":outbox_tray: *Job submitted:* ");
        sb.append(truncate(event.getDescription(), 100));
        sb.append("\n");

        if (event.getTargetBranch() != null) {
            sb.append("   Branch: `").append(event.getTargetBranch()).append("`\n");
        } else if (workstream.getDefaultBranch() != null) {
            sb.append("   Branch: `").append(workstream.getDefaultBranch()).append("`\n");
        }

        sb.append("   Job ID: `").append(event.getJobId()).append("`");

        return sb.toString();
    }

    private String formatStartedMessage(JobCompletionEvent event, SlackWorkstream workstream) {
        StringBuilder sb = new StringBuilder();
        sb.append(":arrows_counterclockwise: *Starting work:* ");
        sb.append(truncate(event.getDescription(), 100));

        return sb.toString();
    }

    private String formatCompletedMessage(JobCompletionEvent event, SlackWorkstream workstream) {
        StringBuilder sb = new StringBuilder();

        switch (event.getStatus()) {
            case SUCCESS:
                int fileCount = event.getStagedFiles().size();

                if (fileCount > 0 && event.isPushed()) {
                    sb.append(":white_check_mark: *Work complete* - pushed ");
                    sb.append(fileCount).append(" file(s)\n");

                    if (event.getTargetBranch() != null) {
                        sb.append("   Branch: `").append(event.getTargetBranch()).append("`\n");
                    }

                    if (event.getCommitHash() != null) {
                        String shortHash = event.getCommitHash().length() > 7
                            ? event.getCommitHash().substring(0, 7)
                            : event.getCommitHash();
                        sb.append("   Commit: `").append(shortHash).append("` ");
                        sb.append(truncate(event.getDescription(), 50));
                        sb.append("\n");
                    }

                    if (event.getPullRequestUrl() != null) {
                        sb.append("   :link: PR: ").append(event.getPullRequestUrl()).append("\n");
                    }
                } else {
                    sb.append(":white_check_mark: *Work complete* - no changes to push\n");
                    sb.append("   ").append(truncate(event.getDescription(), 100)).append("\n");
                }
                appendSessionMetrics(sb, event);
                break;

            case FAILED:
                sb.append(":x: *Work failed*\n");
                if (event.getErrorMessage() != null) {
                    sb.append("   Error: ").append(truncate(event.getErrorMessage(), 200)).append("\n");
                }
                if (event.getExitCode() != 0) {
                    sb.append("   Exit code: ").append(event.getExitCode()).append("\n");
                }
                sb.append("   Job ID: `").append(event.getJobId()).append("`\n");
                appendSessionMetrics(sb, event);
                break;

            case CANCELLED:
                sb.append(":no_entry_sign: *Work cancelled*\n");
                sb.append("   Job ID: `").append(event.getJobId()).append("`\n");
                appendSessionMetrics(sb, event);
                break;

            default:
                sb.append(":grey_question: Job status: ").append(event.getStatus());
        }

        return sb.toString();
    }

    /**
     * Appends session metrics (stop reason, turns, cost, time, permission denials)
     * to the Slack message if any metrics are available.
     */
    private void appendSessionMetrics(StringBuilder sb, JobCompletionEvent event) {
        boolean hasMetrics = event.getNumTurns() > 0
            || event.getCostUsd() > 0
            || event.getDurationMs() > 0
            || event.getSubtype() != null;
        if (!hasMetrics) return;

        sb.append("   ---\n");

        // Stop reason / subtype
        if (event.getSubtype() != null) {
            String stopLabel = formatStopReason(event.getSubtype());
            if (event.isSessionError()) {
                sb.append("   :warning: Stop reason: *").append(stopLabel).append("*\n");
            } else {
                sb.append("   Stop reason: ").append(stopLabel).append("\n");
            }
        }

        // Turns
        if (event.getNumTurns() > 0) {
            sb.append("   Turns: ").append(event.getNumTurns()).append("\n");
        }

        // Cost
        if (event.getCostUsd() > 0) {
            sb.append("   Cost: $").append(String.format("%.2f", event.getCostUsd())).append("\n");
        }

        // Duration (wall time and API time)
        if (event.getDurationMs() > 0) {
            sb.append("   Time: ").append(formatDuration(event.getDurationMs()));
            if (event.getDurationApiMs() > 0) {
                sb.append(" (API: ").append(formatDuration(event.getDurationApiMs())).append(")");
            }
            sb.append("\n");
        }

        // Permission denials
        if (event.getPermissionDenials() > 0) {
            sb.append("   :no_entry: Permission denials: ").append(event.getPermissionDenials());
            List<String> denied = event.getDeniedToolNames();
            if (!denied.isEmpty()) {
                // Deduplicate and show unique denied tool names
                List<String> unique = new ArrayList<>();
                for (String name : denied) {
                    if (!unique.contains(name)) unique.add(name);
                }
                sb.append(" (");
                for (int i = 0; i < unique.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("`").append(unique.get(i)).append("`");
                }
                sb.append(")");
            }
            sb.append("\n");
        }
    }

    /**
     * Formats a Claude Code subtype into a human-readable stop reason label.
     */
    private static String formatStopReason(String subtype) {
        if (subtype == null) return "unknown";
        switch (subtype) {
            case "success": return "completed normally";
            case "error_max_turns": return "max turns reached";
            case "error_budget": return "budget exhausted";
            case "error": return "error";
            default: return subtype.replace('_', ' ');
        }
    }

    /**
     * Formats a duration in milliseconds into a human-readable string.
     */
    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + remainingSeconds + "s";
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        return hours + "h " + remainingMinutes + "m";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }
}
