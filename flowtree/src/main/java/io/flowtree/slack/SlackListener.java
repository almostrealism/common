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

import io.flowtree.Server;
import io.flowtree.jobs.ClaudeCodeJob;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.ConsoleFeatures;

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
public class SlackListener implements ConsoleFeatures {

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
    private final SlackNotifier notifier;

    private Server server;
    private int nextAgent = 0;
    private JobCompletionListener completionListener;
    private int apiPort;

    /**
     * Creates a new SlackListener with the specified notifier.
     *
     * @param notifier the notifier for posting status updates
     */
    public SlackListener(SlackNotifier notifier) {
        this.notifier = notifier;
        this.channelToWorkstream = new HashMap<>();
    }

    /**
     * Registers a workstream. Agents connect inbound to the controller's
     * FlowTree {@link Server}, so no outbound connections are needed here.
     *
     * @param workstream the workstream to register
     */
    public void registerWorkstream(SlackWorkstream workstream) {
        notifier.registerWorkstream(workstream);
        channelToWorkstream.put(workstream.getChannelId(), workstream);
        log("Registered workstream: " + workstream);
    }

    /**
     * Sets the FlowTree {@link Server} used to send tasks to connected agents.
     *
     * @param server the server accepting inbound agent connections
     */
    public void setServer(Server server) {
        this.server = server;
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
     * Returns the API endpoint port used for Slack MCP tool communication.
     */
    public int getApiPort() {
        return apiPort;
    }

    /**
     * Sets the API endpoint port. Called by {@link SlackBotController} after the
     * API endpoint starts so that jobs can be configured with the correct URL.
     *
     * @param apiPort the port the SlackApiEndpoint is listening on
     */
    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
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
            log("Ignoring message from unknown channel: " + channelId);
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
            log("No prompt found in message: " + text);
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
                log("Unknown command: " + command);
                return false;
        }
    }

    /**
     * Handles the /status command.
     */
    private void handleStatusCommand(SlackWorkstream workstream) {
        int connectedAgents = server != null ? server.getNodeGroup().getServers().length : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(":information_source: *Workstream Status*\n");
        sb.append("   Channel: ").append(workstream.getChannelName()).append("\n");
        sb.append("   Connected agents: ").append(connectedAgents).append("\n");
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
     * Submits a job to connected agents via the FlowTree {@link Server}.
     */
    private boolean submitJob(SlackWorkstream workstream, String prompt, String threadTs) {
        if (server == null) {
            warn("No FlowTree server configured");
            return false;
        }

        NodeProxy[] peers = server.getNodeGroup().getServers();
        if (peers.length == 0) {
            notifier.postMessage(workstream.getChannelId(),
                ":x: No agents connected - job not submitted. Start an agent with FLOWTREE_ROOT_HOST pointed at this controller.");
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

        // Git identity
        if (workstream.getGitUserName() != null) {
            factory.setGitUserName(workstream.getGitUserName());
        }
        if (workstream.getGitUserEmail() != null) {
            factory.setGitUserEmail(workstream.getGitUserEmail());
        }

        // Configure Slack MCP tool access
        if (apiPort > 0) {
            factory.setSlackApiUrl("http://localhost:" + apiPort);
            factory.setSlackChannelId(workstream.getChannelId());
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

        // Round-robin to connected agents
        int index = nextAgent++ % peers.length;
        server.sendTask(factory, index);

        log("Submitted job to agent " + index + ": " + factory.getTaskId());
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
