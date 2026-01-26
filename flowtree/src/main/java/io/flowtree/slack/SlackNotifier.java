/*
 * Copyright 2025 Michael Murray
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

import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Posts job status updates to Slack channels.
 *
 * <p>This class implements {@link JobCompletionListener} to receive job events
 * and formats them as Slack messages. It can work with either the Slack Web API
 * (via bot token) or incoming webhooks.</p>
 *
 * <p>Message formatting uses Slack's Block Kit for rich formatting including
 * emoji status indicators, code blocks for commit info, and action buttons.</p>
 *
 * @author Michael Murray
 * @see JobCompletionListener
 * @see SlackBotController
 */
public class SlackNotifier implements JobCompletionListener {

    private static final String SLACK_API_BASE = "https://slack.com/api";

    private final String botToken;
    private final Map<String, SlackWorkstream> workstreams;
    private Consumer<String> messageCallback;

    /**
     * Creates a new notifier with the specified bot token.
     *
     * @param botToken the Slack Bot User OAuth Token (xoxb-...)
     */
    public SlackNotifier(String botToken) {
        this.botToken = botToken;
        this.workstreams = new HashMap<>();
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
     * Sets a callback for receiving formatted messages (useful for testing).
     *
     * @param callback the callback to receive message JSON
     */
    public void setMessageCallback(Consumer<String> callback) {
        this.messageCallback = callback;
    }

    @Override
    public void onJobStarted(JobCompletionEvent event) {
        SlackWorkstream workstream = workstreams.get(event.getWorkstreamId());
        if (workstream == null) {
            System.err.println("[SlackNotifier] Unknown workstream: " + event.getWorkstreamId());
            return;
        }

        String message = formatStartedMessage(event, workstream);
        postMessage(workstream.getChannelId(), message);
    }

    @Override
    public void onJobCompleted(JobCompletionEvent event) {
        SlackWorkstream workstream = workstreams.get(event.getWorkstreamId());
        if (workstream == null) {
            System.err.println("[SlackNotifier] Unknown workstream: " + event.getWorkstreamId());
            return;
        }

        String message = formatCompletedMessage(event, workstream);
        postMessage(workstream.getChannelId(), message);
    }

    /**
     * Posts a message directly to a channel.
     *
     * @param channelId the Slack channel ID
     * @param text      the message text
     */
    public void postMessage(String channelId, String text) {
        String payload = buildMessagePayload(channelId, text);

        if (messageCallback != null) {
            messageCallback.accept(payload);
        }

        if (botToken == null || botToken.isEmpty()) {
            System.out.println("[SlackNotifier] No bot token - message would be:");
            System.out.println(text);
            return;
        }

        try {
            sendToSlack(SLACK_API_BASE + "/chat.postMessage", payload);
        } catch (IOException e) {
            System.err.println("[SlackNotifier] Failed to post message: " + e.getMessage());
        }
    }

    /**
     * Posts a message as a reply in a thread.
     *
     * @param channelId the Slack channel ID
     * @param threadTs  the thread timestamp to reply to
     * @param text      the message text
     */
    public void postThreadReply(String channelId, String threadTs, String text) {
        String payload = buildThreadReplyPayload(channelId, threadTs, text);

        if (messageCallback != null) {
            messageCallback.accept(payload);
        }

        if (botToken == null || botToken.isEmpty()) {
            System.out.println("[SlackNotifier] No bot token - thread reply would be:");
            System.out.println(text);
            return;
        }

        try {
            sendToSlack(SLACK_API_BASE + "/chat.postMessage", payload);
        } catch (IOException e) {
            System.err.println("[SlackNotifier] Failed to post thread reply: " + e.getMessage());
        }
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
                sb.append(":white_check_mark: *Work complete*");
                if (event.isPushed()) {
                    sb.append(" - changes pushed\n");
                } else {
                    sb.append("\n");
                }

                if (event.getTargetBranch() != null) {
                    sb.append("   Branch: `").append(event.getTargetBranch()).append("`\n");
                }

                if (event.getCommitHash() != null) {
                    sb.append("   Commit: `").append(event.getCommitHash().substring(0, 7));
                    sb.append("` ");
                    sb.append(truncate(event.getDescription(), 50));
                    sb.append("\n");
                }

                if (!event.getStagedFiles().isEmpty()) {
                    sb.append("   Files changed: ").append(event.getStagedFiles().size()).append("\n");
                }

                sb.append("   :arrow_right: Please review and provide next instructions");
                break;

            case FAILED:
                sb.append(":x: *Work failed*\n");
                if (event.getErrorMessage() != null) {
                    sb.append("   Error: ").append(event.getErrorMessage()).append("\n");
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

    private String buildMessagePayload(String channelId, String text) {
        return String.format(
            "{\"channel\":\"%s\",\"text\":\"%s\",\"unfurl_links\":false,\"unfurl_media\":false}",
            escapeJson(channelId),
            escapeJson(text)
        );
    }

    private String buildThreadReplyPayload(String channelId, String threadTs, String text) {
        return String.format(
            "{\"channel\":\"%s\",\"thread_ts\":\"%s\",\"text\":\"%s\",\"unfurl_links\":false,\"unfurl_media\":false}",
            escapeJson(channelId),
            escapeJson(threadTs),
            escapeJson(text)
        );
    }

    private void sendToSlack(String endpoint, String payload) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + botToken);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.err.println("[SlackNotifier] Slack API returned: " + responseCode);
            }
        } finally {
            conn.disconnect();
        }
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
