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
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Locale;
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
 * @see FlowTreeController
 * @see ClaudeCodeJob
 */
public class SlackListener implements ConsoleFeatures {

    /**
     * Functional interface for sending slash command responses.
     * Abstracts away the Bolt SDK {@code SlashCommandContext} to allow
     * testing without a real Slack connection.
     */
    @FunctionalInterface
    public interface SlashCommandResponder {
        /**
         * Sends a response to the slash command invoker.
         *
         * @param text the response text (supports Slack mrkdwn)
         * @throws IOException if the response cannot be sent
         */
        void respond(String text) throws IOException;
    }

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
    private Runnable configReloader;
    private int nextAgent = 0;
    private int apiPort;
    private String centralizedMcpConfig;
    private String pushedToolsConfig;
    private String defaultWorkspacePath;

    private WorkstreamConfig workstreamConfig;
    private File configFile;

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
        if (workstream.getChannelId() != null) {
            channelToWorkstream.put(workstream.getChannelId(), workstream);
        }
        log("Registered workstream: " + workstream);
    }

    /**
     * Registers a workstream and persists the configuration to the YAML file.
     *
     * <p>This method is intended for programmatic registration via the HTTP API.
     * It registers the workstream in memory, adds it to the configuration model,
     * and persists the updated configuration to disk.</p>
     *
     * @param workstream the workstream to register and persist
     */
    public void registerAndPersistWorkstream(SlackWorkstream workstream) {
        registerWorkstream(workstream);

        if (workstreamConfig != null) {
            workstreamConfig.addWorkstream(workstream);
        }

        persistConfig();
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
     * Sets a callback to reload workstream configuration from the YAML file.
     * Called when a message arrives from an unknown channel.
     *
     * @param configReloader the reload callback
     */
    public void setConfigReloader(Runnable configReloader) {
        this.configReloader = configReloader;
    }

    /**
     * Returns the API endpoint port used for Slack MCP tool communication.
     */
    public int getApiPort() {
        return apiPort;
    }

    /**
     * Sets the API endpoint port. Called by {@link FlowTreeController} after the
     * API endpoint starts so that jobs can be configured with the correct URL.
     *
     * @param apiPort the port the FlowTreeApiEndpoint is listening on
     */
    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
    }

    /**
     * Returns the centralized MCP configuration JSON.
     */
    public String getCentralizedMcpConfig() {
        return centralizedMcpConfig;
    }

    /**
     * Sets the centralized MCP configuration JSON. When set, this config
     * is passed to every {@link ClaudeCodeJob.Factory} so that agents
     * connect to centralized servers over HTTP.
     *
     * @param centralizedMcpConfig JSON mapping server names to URLs and tool names
     */
    public void setCentralizedMcpConfig(String centralizedMcpConfig) {
        this.centralizedMcpConfig = centralizedMcpConfig;
    }

    /**
     * Returns the pushed MCP tools configuration JSON.
     */
    public String getPushedToolsConfig() {
        return pushedToolsConfig;
    }

    /**
     * Sets the pushed MCP tools configuration JSON. When set, this config
     * is passed to every {@link ClaudeCodeJob.Factory} so that agents
     * download tool source files from the controller and run them locally.
     *
     * @param pushedToolsConfig JSON mapping server names to download URLs and tool names
     */
    public void setPushedToolsConfig(String pushedToolsConfig) {
        this.pushedToolsConfig = pushedToolsConfig;
    }

    /**
     * Returns the global default workspace path for repo checkouts.
     */
    public String getDefaultWorkspacePath() {
        return defaultWorkspacePath;
    }

    /**
     * Sets the global default workspace path for repo checkouts.
     * Passed to every {@link ClaudeCodeJob.Factory} so agents know
     * where to clone repositories when no explicit working directory
     * is configured.
     *
     * @param defaultWorkspacePath the absolute path for repo checkouts
     */
    public void setDefaultWorkspacePath(String defaultWorkspacePath) {
        this.defaultWorkspacePath = defaultWorkspacePath;
    }

    /**
     * Handles an incoming Slack message event.
     *
     * <p>This method is typically called by {@link FlowTreeController} when
     * an app_mention event is received.</p>
     *
     * @param channelId the channel where the message was posted
     * @param userId    the user who posted the message
     * @param text      the message text
     * @param messageTs the timestamp of this message (used to create a thread under it)
     * @param threadTs  the thread timestamp (non-null if the message is already in a thread)
     * @return true if a job was created, false if the message was ignored
     */
    public boolean handleMessage(String channelId, String userId, String text, String messageTs, String threadTs) {
        SlackWorkstream workstream = channelToWorkstream.get(channelId);

        if (workstream == null && configReloader != null) {
            log("Unknown channel " + channelId + " - reloading config");
            configReloader.run();
            workstream = channelToWorkstream.get(channelId);
        }

        if (workstream == null) {
            log("Ignoring message from unknown channel: " + channelId);
            return false;
        }

        // Check for commands first
        Matcher commandMatcher = COMMAND_PATTERN.matcher(text.trim());
        if (commandMatcher.matches()) {
            String command = commandMatcher.group(1);
            String args = commandMatcher.group(2);
            return handleCommand(workstream, command, args, messageTs, threadTs);
        }

        // Extract prompt from mention
        String prompt = extractPrompt(text);
        if (prompt == null || prompt.trim().isEmpty()) {
            log("No prompt found in message: " + text);
            return false;
        }

        return submitJob(workstream, prompt, messageTs, threadTs);
    }

    /**
     * Handles a slash command or in-message command.
     */
    private boolean handleCommand(SlackWorkstream workstream, String command, String args, String messageTs, String threadTs) {
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
                    return submitJob(workstream, args.trim(), messageTs, threadTs);
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
     *
     * @param workstream the target workstream
     * @param prompt     the user prompt
     * @param messageTs  the timestamp of the triggering message (for threading)
     * @param threadTs   the existing thread timestamp (non-null if already in a thread)
     */
    private boolean submitJob(SlackWorkstream workstream, String prompt, String messageTs, String threadTs) {
        if (server == null) {
            warn("No FlowTree server configured");
            return false;
        }

        // Validate git identity before submitting - commits will fail without it
        if (workstream.getGitUserName() == null || workstream.getGitUserName().isEmpty()
                || workstream.getGitUserEmail() == null || workstream.getGitUserEmail().isEmpty()) {
            notifier.postMessage(workstream.getChannelId(),
                ":x: Git identity not configured - job not submitted.\n"
                + "Set git user name and email with:\n"
                + "  `/flowtree config gitUserName <name>`\n"
                + "  `/flowtree config gitUserEmail <email>`");
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

        if (workstream.getDefaultBranch() != null) {
            factory.setTargetBranch(workstream.getDefaultBranch());
            factory.setPushToOrigin(workstream.isPushToOrigin());
        }

        if (workstream.getBaseBranch() != null) {
            factory.setBaseBranch(workstream.getBaseBranch());
        }

        if (workstream.getWorkingDirectory() != null) {
            factory.setWorkingDirectory(workstream.getWorkingDirectory());
        }

        // Repository URL for automatic checkout
        if (workstream.getRepoUrl() != null) {
            factory.setRepoUrl(workstream.getRepoUrl());
        }

        // Default workspace path for repo checkouts
        if (defaultWorkspacePath != null) {
            factory.setDefaultWorkspacePath(defaultWorkspacePath);
        }

        // Git identity
        if (workstream.getGitUserName() != null) {
            factory.setGitUserName(workstream.getGitUserName());
        }
        if (workstream.getGitUserEmail() != null) {
            factory.setGitUserEmail(workstream.getGitUserEmail());
        }

        // Centralized MCP server config
        if (centralizedMcpConfig != null) {
            factory.setCentralizedMcpConfig(centralizedMcpConfig);
        }

        // Pushed MCP tools config
        if (pushedToolsConfig != null) {
            factory.setPushedToolsConfig(pushedToolsConfig);
        }

        // Per-workstream env vars for pushed tools
        if (workstream.getEnv() != null && !workstream.getEnv().isEmpty()) {
            factory.setWorkstreamEnv(workstream.getEnv());
        }

        // Planning document
        if (workstream.getPlanningDocument() != null) {
            factory.setPlanningDocument(workstream.getPlanningDocument());
        }

        // GitHub organization for token selection
        if (workstream.getGithubOrg() != null) {
            factory.setGithubOrg(workstream.getGithubOrg());
        }

        // Build workstream URL for status reporting and Slack messaging
        if (apiPort > 0) {
            String baseUrl = "http://0.0.0.0:" + apiPort
                + "/api/workstreams/" + workstream.getWorkstreamId()
                + "/jobs/" + factory.getTaskId();
            factory.setWorkstreamUrl(baseUrl);
        }

        // Notify that work is starting (locally, before the job leaves).
        // If the triggering message is a top-level message (threadTs == null),
        // reply under it to create a thread. If already in a thread, continue there.
        String replyTo = (threadTs == null) ? messageTs : threadTs;

        String description = prompt.length() > 100 ? prompt.substring(0, 97) + "..." : prompt;
        JobCompletionEvent startEvent = JobCompletionEvent.started(factory.getTaskId(), description);
        startEvent.withGitInfo(workstream.getDefaultBranch(), null, null, null, false);

        notifier.onJobStarted(workstream.getWorkstreamId(), startEvent, replyTo);

        // Round-robin to connected agents
        int index = nextAgent++ % peers.length;
        server.sendTask(factory, index);

        log("Submitted job to agent " + index + ": " + factory.getTaskId());
        return true;
    }

    /**
     * Sets the workstream configuration and file reference for persistence.
     * Called by {@link FlowTreeController} after loading config from YAML.
     *
     * @param config     the loaded workstream configuration
     * @param configFile the YAML file to persist changes to (may be null)
     */
    public void setWorkstreamConfig(WorkstreamConfig config, File configFile) {
        this.workstreamConfig = config;
        this.configFile = configFile;
    }

    /**
     * Handles the {@code /flowtree} slash command.
     *
     * <p>Parses the subcommand from the text and dispatches to the
     * appropriate handler. Supports ephemeral responses (visible only
     * to the invoking user) for informational commands and public
     * responses for actions that the team should see.</p>
     *
     * @param text        the full command text after "/flowtree " (e.g., "setup /workspace feature/x")
     * @param channelId   the channel where the command was invoked
     * @param channelName the human-readable channel name (from Slack payload)
     * @param responder   the responder for sending ephemeral replies
     */
    public void handleSlashCommand(String text, String channelId,
                                    String channelName, SlashCommandResponder responder) {
        String[] parts = (text != null ? text.trim() : "").split("\\s+", 2);
        String subcommand = parts.length > 0 ? parts[0].toLowerCase() : "";
        String args = parts.length > 1 ? parts[1] : null;

        try {
            switch (subcommand) {
                case "setup":
                    handleSetupCommand(channelId, channelName, args, responder);
                    break;
                case "info":
                    handleInfoCommand(channelId, responder);
                    break;
                case "status":
                    handleSlashStatusCommand(channelId, responder);
                    break;
                case "task":
                    handleSlashTaskCommand(channelId, args, responder);
                    break;
                case "cancel":
                    handleSlashCancelCommand(channelId, args, responder);
                    break;
                case "config":
                    handleSlashConfigCommand(channelId, args, responder);
                    break;
                case "jobs":
                    handleSlashJobsCommand(channelId, responder);
                    break;
                case "stats":
                    handleSlashStatsCommand(channelId, args, responder);
                    break;
                default:
                    responder.respond(":information_source: *Flowtree Commands*\n"
                        + "  `/flowtree setup <directory> <branch>` \u2014 Set up a workstream for this channel\n"
                        + "  `/flowtree info` \u2014 Show workstream details\n"
                        + "  `/flowtree status` \u2014 Show agent status\n"
                        + "  `/flowtree task <prompt>` \u2014 Submit a task\n"
                        + "  `/flowtree cancel [job-id]` \u2014 Cancel a running job\n"
                        + "  `/flowtree config [key] [value]` \u2014 View or update settings\n"
                        + "  `/flowtree jobs` \u2014 List recent jobs\n"
                        + "  `/flowtree stats` \u2014 Show weekly job statistics");
            }
        } catch (IOException e) {
            warn("Error responding to slash command: " + e.getMessage());
        }
    }

    /**
     * Handles {@code /flowtree setup <working-directory-or-repo-url> <branch>}.
     * Creates a new workstream for the channel or updates the existing one.
     *
     * <p>The first argument is treated as a git repo URL if it starts with
     * {@code https://}, {@code http://}, or {@code git@}; otherwise it is
     * treated as a local working directory path.</p>
     */
    private void handleSetupCommand(String channelId, String channelName,
                                     String args, SlashCommandResponder ctx) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            ctx.respond(":warning: Usage: `/flowtree setup <working-directory-or-repo-url> <branch>`\n"
                + "Example: `/flowtree setup /workspace/project feature/my-work`\n"
                + "Example: `/flowtree setup https://github.com/org/repo.git feature/my-work`");
            return;
        }

        String[] setupArgs = args.trim().split("\\s+", 2);
        if (setupArgs.length < 2) {
            ctx.respond(":warning: Both working directory (or repo URL) and branch are required.\n"
                + "Usage: `/flowtree setup <working-directory-or-repo-url> <branch>`");
            return;
        }

        String location = setupArgs[0];
        String branch = setupArgs[1];
        boolean isRepoUrl = isGitUrl(location);

        SlackWorkstream existing = channelToWorkstream.get(channelId);
        if (existing != null) {
            String oldBranch = existing.getDefaultBranch();
            if (isRepoUrl) {
                String oldUrl = existing.getRepoUrl();
                existing.setRepoUrl(location);
                existing.setDefaultBranch(branch);
                persistConfig();
                ctx.respond(":white_check_mark: *Workstream updated*\n"
                    + "   Repo URL: `" + (oldUrl != null ? oldUrl : "(none)") + "` \u2192 `" + location + "`\n"
                    + "   Branch: `" + (oldBranch != null ? oldBranch : "(none)") + "` \u2192 `" + branch + "`");
            } else {
                String oldDir = existing.getWorkingDirectory();
                existing.setWorkingDirectory(location);
                existing.setDefaultBranch(branch);
                persistConfig();
                ctx.respond(":white_check_mark: *Workstream updated*\n"
                    + "   Working directory: `" + (oldDir != null ? oldDir : "(none)") + "` \u2192 `" + location + "`\n"
                    + "   Branch: `" + (oldBranch != null ? oldBranch : "(none)") + "` \u2192 `" + branch + "`");
            }
        } else {
            SlackWorkstream ws = new SlackWorkstream(channelId, channelName);
            if (isRepoUrl) {
                ws.setRepoUrl(location);
            } else {
                ws.setWorkingDirectory(location);
            }
            ws.setDefaultBranch(branch);
            registerWorkstream(ws);

            if (workstreamConfig != null) {
                workstreamConfig.addWorkstream(ws);
            }
            persistConfig();

            String locationLabel = isRepoUrl ? "Repo URL" : "Working directory";
            ctx.respond(":white_check_mark: *Workstream created*\n"
                + "   Workstream ID: `" + ws.getWorkstreamId() + "`\n"
                + "   Channel: " + channelName + "\n"
                + "   " + locationLabel + ": `" + location + "`\n"
                + "   Branch: `" + branch + "`\n"
                + "   Max budget: $" + String.format("%.2f", ws.getMaxBudgetUsd()) + "\n"
                + "   Max turns: " + ws.getMaxTurns());
        }
    }

    /**
     * Returns {@code true} if the value looks like a git remote URL
     * rather than a local filesystem path.
     */
    private static boolean isGitUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://") || value.startsWith("git@");
    }

    /**
     * Handles {@code /flowtree info}. Displays the full workstream
     * configuration for the current channel.
     */
    private void handleInfoCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(":page_facing_up: *Workstream Details*\n");
        sb.append("   Workstream ID: `").append(ws.getWorkstreamId()).append("`\n");
        sb.append("   Channel: ").append(ws.getChannelName()).append(" (").append(ws.getChannelId()).append(")\n");
        if (ws.getWorkingDirectory() != null) {
            sb.append("   Working directory: `").append(ws.getWorkingDirectory()).append("`\n");
        }
        if (ws.getDefaultBranch() != null) {
            sb.append("   Branch: `").append(ws.getDefaultBranch()).append("`\n");
        }
        if (ws.getBaseBranch() != null) {
            sb.append("   Base branch: `").append(ws.getBaseBranch()).append("`\n");
        }
        sb.append("   Push to origin: ").append(ws.isPushToOrigin()).append("\n");
        sb.append("   Allowed tools: ").append(ws.getAllowedTools()).append("\n");
        sb.append("   Max turns: ").append(ws.getMaxTurns()).append("\n");
        sb.append("   Max budget: $").append(String.format("%.2f", ws.getMaxBudgetUsd()));
        if (ws.getPlanningDocument() != null) {
            sb.append("\n   Planning document: `").append(ws.getPlanningDocument()).append("`");
        }
        if (ws.getGitUserName() != null || ws.getGitUserEmail() != null) {
            sb.append("\n   Git user: ");
            if (ws.getGitUserName() != null) sb.append(ws.getGitUserName());
            if (ws.getGitUserEmail() != null) sb.append(" <").append(ws.getGitUserEmail()).append(">");
        }

        ctx.respond(sb.toString());
    }

    /**
     * Handles {@code /flowtree status}. Shows agent and connection status.
     */
    private void handleSlashStatusCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        int connectedAgents = server != null ? server.getNodeGroup().getServers().length : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(":satellite: *Agent Status*\n");
        sb.append("   Connected agents: ").append(connectedAgents).append("\n");
        sb.append("   Channel: ").append(ws.getChannelName()).append("\n");
        if (ws.getDefaultBranch() != null) {
            sb.append("   Branch: `").append(ws.getDefaultBranch()).append("`");
        }

        ctx.respond(sb.toString());
    }

    /**
     * Handles {@code /flowtree task <prompt>}. Submits a task to an agent.
     * This command posts a public message (visible to the channel) since
     * the team should see that a task was submitted.
     */
    private void handleSlashTaskCommand(String channelId, String args, SlashCommandResponder ctx) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            ctx.respond(":warning: Usage: `/flowtree task <prompt>`");
            return;
        }

        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        boolean submitted = submitJob(ws, args.trim(), null, null);
        if (submitted) {
            ctx.respond(":arrow_forward: Task submitted: " + truncate(args.trim(), 100));
        }
    }

    /**
     * Handles {@code /flowtree cancel [job-id]}. Cancels a running job.
     * Posts a public message since the team should see cancellations.
     */
    private void handleSlashCancelCommand(String channelId, String args, SlashCommandResponder ctx) throws IOException {
        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        ctx.respond(":construction: Job cancellation not yet implemented");
    }

    /**
     * Handles {@code /flowtree config [key] [value]}. Views or updates
     * workstream settings at runtime.
     */
    private void handleSlashConfigCommand(String channelId, String args, SlashCommandResponder ctx) throws IOException {
        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            // Show all settings
            StringBuilder sb = new StringBuilder();
            sb.append(":gear: *Workstream Configuration*\n");
            sb.append("   `maxBudgetUsd` = ").append(String.format("%.2f", ws.getMaxBudgetUsd())).append("\n");
            sb.append("   `maxTurns` = ").append(ws.getMaxTurns()).append("\n");
            sb.append("   `defaultBranch` = ").append(ws.getDefaultBranch() != null ? ws.getDefaultBranch() : "(not set)").append("\n");
            sb.append("   `baseBranch` = ").append(ws.getBaseBranch() != null ? ws.getBaseBranch() : "(not set)").append("\n");
            sb.append("   `repoUrl` = ").append(ws.getRepoUrl() != null ? ws.getRepoUrl() : "(not set)").append("\n");
            sb.append("   `workingDirectory` = ").append(ws.getWorkingDirectory() != null ? ws.getWorkingDirectory() : "(not set)").append("\n");
            sb.append("   `pushToOrigin` = ").append(ws.isPushToOrigin()).append("\n");
            sb.append("   `allowedTools` = ").append(ws.getAllowedTools()).append("\n");
            sb.append("   `gitUserName` = ").append(ws.getGitUserName() != null ? ws.getGitUserName() : "(not set)").append("\n");
            sb.append("   `gitUserEmail` = ").append(ws.getGitUserEmail() != null ? ws.getGitUserEmail() : "(not set)").append("\n");
            sb.append("   `planningDocument` = ").append(ws.getPlanningDocument() != null ? ws.getPlanningDocument() : "(not set)");
            ctx.respond(sb.toString());
            return;
        }

        String[] configArgs = args.trim().split("\\s+", 2);
        String key = configArgs[0];
        String value = configArgs.length > 1 ? configArgs[1] : null;

        if (value == null) {
            // Show single setting
            String currentValue = getConfigValue(ws, key);
            if (currentValue == null) {
                ctx.respond(":warning: Unknown setting: `" + key + "`\n"
                    + "Modifiable settings: `maxBudgetUsd`, `maxTurns`, `defaultBranch`, "
                    + "`baseBranch`, `repoUrl`, `workingDirectory`, `pushToOrigin`, `allowedTools`, "
                    + "`gitUserName`, `gitUserEmail`, `planningDocument`");
            } else {
                ctx.respond(":gear: `" + key + "` = " + currentValue);
            }
            return;
        }

        // Update setting
        String result = setConfigValue(ws, key, value);
        if (result != null) {
            ctx.respond(":warning: " + result);
        } else {
            persistConfig();
            ctx.respond(":white_check_mark: Updated `" + key + "` = " + value);
        }
    }

    /**
     * Handles {@code /flowtree jobs}. Lists recent jobs for the workstream.
     */
    private void handleSlashJobsCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        Map<String, JobCompletionEvent> jobs = notifier.getRecentJobs(ws.getWorkstreamId());
        if (jobs == null || jobs.isEmpty()) {
            ctx.respond(":clipboard: No recent jobs for this workstream.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(":clipboard: *Recent Jobs*\n");
        int count = 0;
        for (Map.Entry<String, JobCompletionEvent> entry : jobs.entrySet()) {
            if (count >= 10) break;
            JobCompletionEvent event = entry.getValue();
            String emoji;
            String statusText;
            switch (event.getStatus()) {
                case SUCCESS:
                    emoji = ":white_check_mark:";
                    statusText = "";
                    break;
                case FAILED:
                    emoji = ":x:";
                    statusText = " (failed)";
                    break;
                case CANCELLED:
                    emoji = ":no_entry_sign:";
                    statusText = " (cancelled)";
                    break;
                case STARTED:
                    emoji = ":hourglass_flowing_sand:";
                    statusText = " (running)";
                    break;
                default:
                    emoji = ":grey_question:";
                    statusText = "";
            }
            sb.append("   ").append(emoji).append(" `").append(truncate(entry.getKey(), 8)).append("` - ");
            sb.append(truncate(event.getDescription(), 60)).append(statusText).append("\n");
            count++;
        }

        ctx.respond(sb.toString());
    }

    /**
     * Returns the value of a workstream configuration key.
     */
    private String getConfigValue(SlackWorkstream ws, String key) {
        switch (key) {
            case "maxBudgetUsd": return String.format("%.2f", ws.getMaxBudgetUsd());
            case "maxTurns": return String.valueOf(ws.getMaxTurns());
            case "defaultBranch": return ws.getDefaultBranch() != null ? ws.getDefaultBranch() : "(not set)";
            case "baseBranch": return ws.getBaseBranch() != null ? ws.getBaseBranch() : "(not set)";
            case "repoUrl": return ws.getRepoUrl() != null ? ws.getRepoUrl() : "(not set)";
            case "workingDirectory": return ws.getWorkingDirectory() != null ? ws.getWorkingDirectory() : "(not set)";
            case "pushToOrigin": return String.valueOf(ws.isPushToOrigin());
            case "allowedTools": return ws.getAllowedTools();
            case "gitUserName": return ws.getGitUserName() != null ? ws.getGitUserName() : "(not set)";
            case "gitUserEmail": return ws.getGitUserEmail() != null ? ws.getGitUserEmail() : "(not set)";
            case "planningDocument": return ws.getPlanningDocument() != null ? ws.getPlanningDocument() : "(not set)";
            case "workstreamId": return ws.getWorkstreamId();
            case "channelId": return ws.getChannelId();
            case "channelName": return ws.getChannelName();
            default: return null;
        }
    }

    /**
     * Sets a workstream configuration value. Returns an error message
     * if the key is unknown or non-modifiable, or null on success.
     */
    private String setConfigValue(SlackWorkstream ws, String key, String value) {
        switch (key) {
            case "maxBudgetUsd":
                try {
                    ws.setMaxBudgetUsd(Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    return "Invalid number: `" + value + "`";
                }
                return null;
            case "maxTurns":
                try {
                    ws.setMaxTurns(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    return "Invalid integer: `" + value + "`";
                }
                return null;
            case "defaultBranch":
                ws.setDefaultBranch(value);
                return null;
            case "baseBranch":
                ws.setBaseBranch(value);
                return null;
            case "repoUrl":
                ws.setRepoUrl(value);
                return null;
            case "workingDirectory":
                ws.setWorkingDirectory(value);
                return null;
            case "pushToOrigin":
                ws.setPushToOrigin(Boolean.parseBoolean(value));
                return null;
            case "allowedTools":
                ws.setAllowedTools(value);
                return null;
            case "gitUserName":
                ws.setGitUserName(value);
                return null;
            case "gitUserEmail":
                ws.setGitUserEmail(value);
                return null;
            case "planningDocument":
                ws.setPlanningDocument(value);
                return null;
            case "workstreamId":
            case "channelId":
            case "channelName":
                return "`" + key + "` is read-only and cannot be modified.";
            default:
                return "Unknown setting: `" + key + "`\n"
                    + "Modifiable settings: `maxBudgetUsd`, `maxTurns`, `defaultBranch`, "
                    + "`baseBranch`, `repoUrl`, `workingDirectory`, `pushToOrigin`, `allowedTools`, "
                    + "`gitUserName`, `gitUserEmail`, `planningDocument`";
        }
    }

    /**
     * Handles {@code /flowtree stats}. Shows weekly job statistics.
     */
    private void handleSlashStatsCommand(String channelId, String args,
                                          SlashCommandResponder ctx) throws IOException {
        SlackWorkstream ws = channelToWorkstream.get(channelId);
        if (ws == null) {
            ctx.respond(":warning: No workstream configured for this channel.\n"
                + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.");
            return;
        }

        JobStatsStore statsStore = notifier.getStatsStore();
        if (statsStore == null) {
            ctx.respond(":warning: Job statistics are not available.");
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);

        JobStatsStore.WeeklyStats thisWeek = statsStore.getWeeklyStats(ws.getWorkstreamId(), thisWeekStart);
        JobStatsStore.WeeklyStats lastWeek = statsStore.getWeeklyStats(ws.getWorkstreamId(), lastWeekStart);

        StringBuilder sb = new StringBuilder();
        sb.append(":bar_chart: *Agent Activity \u2014 ").append(ws.getChannelName()).append("*\n\n");
        sb.append(formatWeeklyStats("This Week", thisWeekStart, thisWeek));
        sb.append("\n");
        sb.append(formatWeeklyStats("Last Week", lastWeekStart, lastWeek));

        ctx.respond(sb.toString());
    }

    /**
     * Formats a week's statistics for Slack display.
     */
    private String formatWeeklyStats(String label, LocalDate weekStart,
                                      JobStatsStore.WeeklyStats stats) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);
        LocalDate weekEnd = weekStart.plusDays(6);

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(label).append("* (");
        sb.append(weekStart.format(fmt)).append(" \u2014 ").append(weekEnd.format(fmt)).append(")\n");
        sb.append("  :clock1: Total time: ").append(formatDuration(stats.totalWallClockMs)).append("\n");
        sb.append("  :hammer: Jobs: ").append(stats.jobCount);
        sb.append(" (:white_check_mark: ").append(stats.successCount);
        sb.append("  :x: ").append(stats.failedCount);
        sb.append("  :no_entry_sign: ").append(stats.cancelledCount).append(")\n");
        sb.append("  :moneybag: Cost: $").append(String.format("%.2f", stats.totalCostUsd)).append("\n");
        sb.append("  :speech_balloon: Turns: ").append(String.format("%,d", stats.totalTurns)).append("\n");
        return sb.toString();
    }

    /**
     * Formats a duration in milliseconds as a human-readable string.
     */
    private static String formatDuration(long ms) {
        if (ms <= 0) return "0m";
        long totalMinutes = ms / 60000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /**
     * Persists the current workstream configuration to the YAML file.
     * If no config file was loaded, changes are runtime-only.
     */
    private void persistConfig() {
        if (workstreamConfig == null || configFile == null) {
            log("No config file loaded - changes are runtime-only");
            return;
        }

        // Sync in-memory workstream state back to config entries
        workstreamConfig.syncFromWorkstreams(channelToWorkstream.values());

        try {
            workstreamConfig.saveToYaml(configFile);
            log("Persisted config to " + configFile.getName());
        } catch (IOException e) {
            warn("Failed to persist config: " + e.getMessage());
        }
    }

    private static String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
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
