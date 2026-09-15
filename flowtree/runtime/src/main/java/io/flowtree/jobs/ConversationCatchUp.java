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

import com.fasterxml.jackson.databind.JsonNode;
import io.flowtree.JsonFieldExtractor;
import io.flowtree.workstream.WorkstreamMailbox;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What a relaunched collaborative session missed: the workstream conversation
 * since the job's own last message, rendered as a prompt block.
 *
 * <p>A job that is relaunched — after an inactivity kill, an enforcement
 * retry, or a guardrail violation — starts a fresh agent session that
 * remembers nothing. The conversation it was holding with a collaborator is
 * durable on the controller ({@link WorkstreamMailbox}), but nothing handed it
 * back: the relaunch prompt pointed the agent at its memories and the user
 * request, and the agent redid preparatory work it had been told to skip while
 * instructions addressed to it sat unread. This class reads the mailbox
 * through the job's workstream URL and quotes the messages that followed the
 * job's own last message, so the relaunched session can act on the newest
 * instruction instead of the original one.</p>
 *
 * <p>Reading is best-effort. An unreachable controller, an unknown workstream,
 * or a body that does not parse all yield an empty rendering, and the session
 * proceeds as it would have without this.</p>
 *
 * @author Michael Murray
 * @see WorkstreamMailbox
 * @see InstructionPromptBuilder#setConversationCatchUp(String)
 */
public final class ConversationCatchUp implements ConsoleFeatures {

    /**
     * Most recent messages quoted when the job never spoke, or when a great
     * deal was said after it did. A relaunch needs the latest instruction, not
     * the archive.
     */
    public static final int MAX_MESSAGES = 40;

    /** Sender identity a job records on its own messages, as {@code send_message} derives it. */
    static final String JOB_SENDER_PREFIX = "job:";

    /** Mailbox read URL, or {@code null} when the job has no workstream URL. */
    private final String mailboxUrl;

    /** The sender identity this job's own messages carry. */
    private final String ownSender;

    /** Fetches the body at a URL, or {@code null} when it cannot be read. */
    private final Function<String, String> fetch;

    /**
     * Creates a catch-up for the job identified by {@code jobId} on the
     * workstream behind {@code workstreamUrl}, reading over HTTP.
     *
     * @param workstreamUrl the job's resolved workstream URL
     *                      ({@code …/api/workstreams/{ws}[/jobs/{job}]}), or
     *                      {@code null} when the job has none
     * @param jobId         the job's task identifier
     */
    public ConversationCatchUp(String workstreamUrl, String jobId) {
        this(mailboxUrlFor(workstreamUrl), JOB_SENDER_PREFIX + jobId, null);
    }

    /**
     * Creates a catch-up with an explicit mailbox URL and fetcher.
     *
     * @param mailboxUrl the mailbox read URL, or {@code null} to render nothing
     * @param ownSender  the sender identity of this job's own messages
     * @param fetch      body fetcher, or {@code null} to read over HTTP
     */
    ConversationCatchUp(String mailboxUrl, String ownSender, Function<String, String> fetch) {
        this.mailboxUrl = mailboxUrl;
        this.ownSender = ownSender;
        this.fetch = fetch != null ? fetch : this::fetchOverHttp;
    }

    /**
     * Derives the mailbox read URL from a workstream URL, replaying the whole
     * conversation so the cut can be made at the job's own last message.
     *
     * @param workstreamUrl the workstream (or workstream job) URL
     * @return the mailbox URL, or {@code null} when the URL is not a workstream URL
     */
    static String mailboxUrlFor(String workstreamUrl) {
        if (workstreamUrl == null || workstreamUrl.isEmpty()) return null;

        String base = DeduplicationSpawner.extractControllerBaseUrl(workstreamUrl);
        String workstreamId = DeduplicationSpawner.extractWorkstreamId(workstreamUrl);
        if (base == null || workstreamId == null || workstreamId.isEmpty()) return null;

        return base + "/api/workstreams/" + workstreamId + "/mailbox?since=0";
    }

    /**
     * Returns the mailbox URL this catch-up reads from.
     *
     * @return the URL, or {@code null} when there is nothing to read
     */
    public String getMailboxUrl() { return mailboxUrl; }

    /**
     * Reads the conversation and returns the messages that followed this
     * job's own last message, oldest first. When the job never spoke, the
     * most recent {@link #MAX_MESSAGES} are returned instead.
     *
     * @return the missed messages; empty when nothing followed or nothing
     *         could be read
     */
    public List<WorkstreamMailbox.Message> missedMessages() {
        if (mailboxUrl == null) return List.of();

        String body = fetch.apply(mailboxUrl);
        if (body == null || body.isEmpty()) return List.of();

        List<WorkstreamMailbox.Message> all = new ArrayList<>();

        try {
            JsonNode root = JsonFieldExtractor.MAPPER.readTree(body);

            for (JsonNode node : root.path("messages")) {
                WorkstreamMailbox.Message message = WorkstreamMailbox.Message.fromJson(node.toString());
                if (message != null) all.add(message);
            }
        } catch (IOException e) {
            warn("Unable to parse the workstream conversation: " + e.getMessage());
            return List.of();
        }

        int cut = 0;

        for (int i = all.size() - 1; i >= 0; i--) {
            if (ownSender.equals(all.get(i).sender())) {
                cut = i + 1;
                break;
            }
        }

        List<WorkstreamMailbox.Message> missed = all.subList(cut, all.size());
        if (missed.size() > MAX_MESSAGES) {
            missed = missed.subList(missed.size() - MAX_MESSAGES, missed.size());
        }

        return new ArrayList<>(missed);
    }

    /**
     * Renders the missed messages as a prompt block, or the empty string when
     * there are none.
     *
     * @return the block, oldest message first
     */
    public String render() {
        List<WorkstreamMailbox.Message> missed = missedMessages();
        if (missed.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        for (WorkstreamMailbox.Message message : missed) {
            sb.append("- [").append(Instant.ofEpochMilli(message.createdAtMillis()))
                    .append("] ").append(message.sender());
            if (message.activity() != null && !message.activity().isEmpty()) {
                sb.append(" (").append(message.activity()).append(')');
            }
            sb.append(": ").append(message.text().replace("\n", "\n  ")).append('\n');
        }

        return sb.toString();
    }

    /**
     * GETs {@code url} and returns the body, or {@code null} on any failure.
     *
     * @param url the URL to read
     * @return the response body, or {@code null}
     */
    private String fetchOverHttp(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                warn("Conversation read from " + url + " returned " + status);
                return null;
            }

            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            warn("Unable to read the workstream conversation: " + e.getMessage());
            return null;
        }
    }
}
