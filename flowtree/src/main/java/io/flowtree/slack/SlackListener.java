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

import io.flowtree.ClaudeCodeClient;
import io.flowtree.jobs.ClaudeCodeJob;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for Slack messages and creates Claude Code jobs.
 *
 * <p>This class parses incoming Slack messages (typically app mentions) and
 * extracts prompts to be executed by Claude Code agents. It supports both
 * direct prompts and command-prefixed prompts.</p>
 *
 * <h2>Message Formats</h2>
 * <ul>
 *   <li>{@code @agent Fix the bug in auth.py} - Direct prompt</li>
 *   <li>{@code @agent /task Implement the caching layer} - Command-prefixed</li>
 * </ul>
 *
 * @author Michael Murray
 * @see SlackBotController
 * @see ClaudeCodeJob
 */
public class SlackListener {

    /** Pattern to extract prompt from mentions: @bot /task prompt or @bot prompt */
    private static final Pattern MENTION_PATTERN = Pattern.compile(
        "<@[A-Z0-9]+>\\s*(?:/task\\s+)?(.+)",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    /** Pattern for command messages: /command args */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
        "^/(\\w+)(?:\\s+(.*))?$",
        Pattern.DOTALL
    );

    private final Map<String, SlackWorkstream> channelToWorkstream;
    private final Map<String, ClaudeCodeClient> workstreamClients;
    private final SlackNotifier notifier;

    private JobCompletionListener completionListener;

    /**
     * Creates a new SlackListener with the specified notifier.
     *
     * @param notifier the notifier for posting status updates
     */
    public SlackListener(SlackNotifier notifier) {
        this.notifier = notifier;
        this.channelToWorkstream = new HashMap<>();
        this.workstreamClients = new HashMap<>();
    }

    /**
     * Registers a workstream and initializes its client connection.
     *
     * @param workstream the workstream to register
     * @throws IOException if client connection fails
     */
    public void registerWorkstream(SlackWorkstream workstream) throws IOException {
        if (workstream.getAgents().isEmpty()) {
            throw new IllegalArgumentException("Workstream has no agents configured");
        }

        // Register with notifier
        notifier.registerWorkstream(workstream);

        // Create client for this workstream
        ClaudeCodeClient client = new ClaudeCodeClient();
        for (SlackWorkstream.AgentEndpoint agent : workstream.getAgents()) {
            client.addAgent(agent.getHost(), agent.getPort());
        }
        client.start();

        channelToWorkstream.put(workstream.getChannelId(), workstream);
        workstreamClients.put(workstream.getWorkstreamId(), client);

        System.out.println("[SlackListener] Registered workstream: " + workstream);
    }

    /**
     * Sets the completion listener for job events.
     *
     * @param listener the listener to receive job events
     */
    public void setCompletionListener(JobCompletionListener listener) {
        this.completionListener = listener;
    }

    /**
     * Handles an incoming Slack message event.
     *
     * <p>This method is typically called by {@link SlackBotController} when
     * an app_mention event is received.</p>
     *
     * @param channelId the channel where the message was posted
     * @param userId    the user who posted the message
     * @param text      the message text
     * @param threadTs  the thread timestamp (for threading replies)
     * @return true if a job was created, false if the message was ignored
     */
    public boolean handleMessage(String channelId, String userId, String text, String threadTs) {
        SlackWorkstream workstream = channelToWorkstream.get(channelId);
        if (workstream == null) {
            System.out.println("[SlackListener] Ignoring message from unknown channel: " + channelId);
            return false;
        }

        // Check for commands first
        Matcher commandMatcher = COMMAND_PATTERN.matcher(text.trim());
        if (commandMatcher.matches()) {
            String command = commandMatcher.group(1);
            String args = commandMatcher.group(2);
            return handleCommand(workstream, command, args, threadTs);
        }

        // Extract prompt from mention
        String prompt = extractPrompt(text);
        if (prompt == null || prompt.trim().isEmpty()) {
            System.out.println("[SlackListener] No prompt found in message: " + text);
            return false;
        }

        return submitJob(workstream, prompt, threadTs);
    }

    /**
     * Handles a slash command or in-message command.
     */
    private boolean handleCommand(SlackWorkstream workstream, String command, String args, String threadTs) {
        switch (command.toLowerCase()) {
            case "status":
                handleStatusCommand(workstream);
                return true;

            case "cancel":
                handleCancelCommand(workstream, args);
                return true;

            case "task":
            case "do":
            case "run":
                if (args != null && !args.trim().isEmpty()) {
                    return submitJob(workstream, args.trim(), threadTs);
                }
                notifier.postMessage(workstream.getChannelId(),
                    ":warning: Usage: /" + command + " <prompt>");
                return false;

            default:
                System.out.println("[SlackListener] Unknown command: " + command);
                return false;
        }
    }

    /**
     * Handles the /status command.
     */
    private void handleStatusCommand(SlackWorkstream workstream) {
        StringBuilder sb = new StringBuilder();
        sb.append(":information_source: *Workstream Status*\n");
        sb.append("   Channel: ").append(workstream.getChannelName()).append("\n");
        sb.append("   Agents: ").append(workstream.getAgents().size()).append(" configured\n");
        if (workstream.getDefaultBranch() != null) {
            sb.append("   Default branch: `").append(workstream.getDefaultBranch()).append("`\n");
        }
        sb.append("   Max budget: $").append(String.format("%.2f", workstream.getMaxBudgetUsd()));

        notifier.postMessage(workstream.getChannelId(), sb.toString());
    }

    /**
     * Handles the /cancel command.
     */
    private void handleCancelCommand(SlackWorkstream workstream, String jobId) {
        // TODO: Implement job cancellation
        notifier.postMessage(workstream.getChannelId(),
            ":construction: Job cancellation not yet implemented");
    }

    /**
     * Submits a job to the workstream's agents.
     */
    private boolean submitJob(SlackWorkstream workstream, String prompt, String threadTs) {
        ClaudeCodeClient client = workstreamClients.get(workstream.getWorkstreamId());
        if (client == null) {
            System.err.println("[SlackListener] No client for workstream: " + workstream.getWorkstreamId());
            return false;
        }

        // Create job factory with workstream settings
        ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(prompt);
        factory.setAllowedTools(workstream.getAllowedTools());
        factory.setMaxTurns(workstream.getMaxTurns());
        factory.setMaxBudgetUsd(workstream.getMaxBudgetUsd());
        factory.setWorkstreamId(workstream.getWorkstreamId());

        if (workstream.getDefaultBranch() != null) {
            factory.setTargetBranch(workstream.getDefaultBranch());
            factory.setPushToOrigin(workstream.isPushToOrigin());
        }

        if (workstream.getWorkingDirectory() != null) {
            factory.setWorkingDirectory(workstream.getWorkingDirectory());
        }

        // Notify that work is starting
        JobCompletionEvent startEvent = JobCompletionEvent.started(
            factory.getTaskId(),
            workstream.getWorkstreamId(),
            prompt.length() > 100 ? prompt.substring(0, 97) + "..." : prompt
        );
        startEvent.withGitInfo(workstream.getDefaultBranch(), null, null, null, false);

        notifier.onJobStarted(startEvent);
        if (completionListener != null) {
            completionListener.onJobStarted(startEvent);
        }

        // Submit the job
        client.submit(factory);

        System.out.println("[SlackListener] Submitted job: " + factory.getTaskId());
        return true;
    }

    /**
     * Extracts the prompt text from a Slack mention message.
     *
     * @param text the full message text including the mention
     * @return the extracted prompt, or null if not found
     */
    public String extractPrompt(String text) {
        if (text == null) return null;

        Matcher matcher = MENTION_PATTERN.matcher(text.trim());
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }

        // If no mention found, treat entire text as prompt (for direct messages)
        return text.trim();
    }

    /**
     * Returns the workstream for a given channel ID.
     *
     * @param channelId the Slack channel ID
     * @return the workstream, or null if not registered
     */
    public SlackWorkstream getWorkstream(String channelId) {
        return channelToWorkstream.get(channelId);
    }

    /**
     * Returns all registered workstreams.
     */
    public Map<String, SlackWorkstream> getWorkstreams() {
        return new HashMap<>(channelToWorkstream);
    }
}
