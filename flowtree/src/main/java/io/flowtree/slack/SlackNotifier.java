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
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private Consumer<String> messageCallback;

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
     * Sets a callback for receiving formatted messages (useful for testing).
     *
     * @param callback the callback to receive message text
     */
    public void setMessageCallback(Consumer<String> callback) {
        this.messageCallback = callback;
    }

    @Override
    public void onJobStarted(String workstreamId, JobCompletionEvent event) {
        onJobStarted(workstreamId, event, null);
    }

    /**
     * Notifies that a job has started, optionally threading under an
     * existing message.
     *
     * @param workstreamId the workstream identifier
     * @param event        the start event
     * @param replyTo      if non-null, post the "Starting work" message as a
     *                      reply under this message timestamp (creating a thread)
     */
    public void onJobStarted(String workstreamId, JobCompletionEvent event, String replyTo) {
        SlackWorkstream workstream = workstreams.get(workstreamId);
        if (workstream == null) {
            warn("Unknown workstream: " + workstreamId);
            return;
        }

        trackJob(workstreamId, event);

        String message = formatStartedMessage(event, workstream);
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
    public void onJobCompleted(String workstreamId, JobCompletionEvent event) {
        SlackWorkstream workstream = workstreams.get(workstreamId);
        if (workstream == null) {
            warn("Unknown workstream: " + workstreamId);
            return;
        }

        trackJob(workstreamId, event);

        String message = formatCompletedMessage(event, workstream);
        String threadTs = event.getJobId() != null ? jobThreadTs.remove(event.getJobId()) : null;

        if (threadTs != null) {
            postMessageInThread(workstream.getChannelId(), message, threadTs);
        } else {
            postMessage(workstream.getChannelId(), message);
        }
    }

    /**
     * Posts a message directly to a channel.
     *
     * @param channelId the Slack channel ID
     * @param text      the message text (supports Slack mrkdwn formatting)
     * @return the message timestamp (for threading), or null on failure
     */
    public String postMessage(String channelId, String text) {
        // Notify callback for testing
        if (messageCallback != null) {
            messageCallback.accept("{\"channel\":\"" + channelId + "\",\"text\":\"" +
                                   escapeJson(text) + "\"}");
        }

        if (client == null) {
            log("No bot token - message would be posted to " + channelId + ":");
            log(text);
            return null;
        }

        try {
            ChatPostMessageResponse response = client.chatPostMessage(req -> req
                .channel(channelId)
                .text(text)
                .unfurlLinks(false)
                .unfurlMedia(false)
            );

            if (response.isOk()) {
                return response.getTs();
            } else {
                warn("Failed to post message: " + response.getError());
            }
        } catch (IOException | SlackApiException e) {
            warn("Error posting message: " + e.getMessage());
        }

        return null;
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
        if (messageCallback != null) {
            messageCallback.accept("{\"channel\":\"" + channelId +
                                   "\",\"thread_ts\":\"" + escapeJson(threadTs) +
                                   "\",\"text\":\"" + escapeJson(text) + "\"}");
        }

        if (client == null) {
            log("No bot token - thread reply would be posted to " + channelId +
                " (thread " + threadTs + "):");
            log(text);
            return null;
        }

        try {
            ChatPostMessageResponse response = client.chatPostMessage(req -> req
                .channel(channelId)
                .text(text)
                .threadTs(threadTs)
                .unfurlLinks(false)
                .unfurlMedia(false)
            );

            if (response.isOk()) {
                return response.getTs();
            } else {
                warn("Failed to post thread reply: " + response.getError());
            }
        } catch (IOException | SlackApiException e) {
            warn("Error posting thread reply: " + e.getMessage());
        }

        return null;
    }

    /**
     * Returns the Slack thread timestamp for a job, if one has been established.
     * This is the timestamp of the "Starting work" message posted when the job began.
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

    private String formatStartedMessage(JobCompletionEvent event, SlackWorkstream workstream) {
        StringBuilder sb = new StringBuilder();
        sb.append(":arrows_counterclockwise: *Starting work:* ");
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

                    sb.append("   :arrow_right: Please review and provide next instructions");
                } else {
                    sb.append(":white_check_mark: *Work complete* - no changes to push\n");
                    sb.append("   ").append(truncate(event.getDescription(), 100));
                }
                break;

            case FAILED:
                sb.append(":x: *Work failed*\n");
                if (event.getErrorMessage() != null) {
                    sb.append("   Error: ").append(truncate(event.getErrorMessage(), 200)).append("\n");
                }
                if (event.getExitCode() != 0) {
                    sb.append("   Exit code: ").append(event.getExitCode()).append("\n");
                }
                sb.append("   Job ID: `").append(event.getJobId()).append("`");
                break;

            case CANCELLED:
                sb.append(":no_entry_sign: *Work cancelled*\n");
                sb.append("   Job ID: `").append(event.getJobId()).append("`");
                break;

            default:
                sb.append(":grey_question: Job status: ").append(event.getStatus());
        }

        return sb.toString();
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
