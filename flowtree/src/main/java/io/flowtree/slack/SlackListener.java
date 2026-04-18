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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
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

    /**
     * Pattern to extract the user's prompt from an app-mention message.
     * Strips the bot mention and optional {@code /task} prefix.
     * Capture group 1 is the raw prompt text.
     */
    private static final Pattern MENTION_PATTERN = Pattern.compile(
        "<@[A-Z0-9]+>\\s*(?:/task\\s+)?(.+)",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to detect in-message commands of the form {@code /command [args]}.
     * Group 1 is the command name; group 2 (optional) is the argument string.
     */
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
        "^/(\\w+)(?:\\s+(.*))?$",
        Pattern.DOTALL
    );

    /** Maps Slack channel ID to the registered workstream for that channel. */
    private final Map<String, Workstream> channelToWorkstream;
    /** Posts status and completion messages back to Slack. */
    private SlackNotifier notifier;

    /** FlowTree server that accepts inbound agent connections and queues jobs. */
    private Server server;
    /** Callback invoked when a message arrives from an unrecognised channel, triggering a config reload. */
    private Runnable configReloader;
    /** Port the HTTP API endpoint is listening on; set after endpoint startup. */
    private int apiPort;
    /** HTTP base URL of the ar-manager service used for HMAC token generation. */
    private String arManagerUrl;
    /** Shared secret used to generate temporary HMAC auth tokens for ar-manager. */
    private String arManagerSharedSecret;
    /** Global fallback path for repository checkouts when no workingDirectory is set. */
    private String defaultWorkspacePath;

    /** In-memory model of the YAML config, used to persist workstream changes to disk. */
    private WorkstreamConfig workstreamConfig;
    /** The YAML config file to write when workstream settings change at runtime. */
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
     * Replaces the notifier used for posting status messages.
     *
     * <p>Called by {@link FlowTreeController} when multi-workspace config is loaded
     * so the listener's primary notifier matches the first workspace connection.</p>
     *
     * @param notifier the new notifier instance
     */
    public void setNotifier(SlackNotifier notifier) {
        this.notifier = notifier;
    }

    /**
     * Registers a workstream. Agents connect inbound to the controller's
     * FlowTree {@link Server}, so no outbound connections are needed here.
     *
     * @param workstream the workstream to register
     */
    public void registerWorkstream(Workstream workstream) {
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
    public void registerAndPersistWorkstream(Workstream workstream) {
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
     * Returns the ar-manager HTTP URL.
     */
    public String getArManagerUrl() {
        return arManagerUrl;
    }

    /**
     * Sets the ar-manager HTTP URL. When set, jobs are configured to
     * access ar-manager over HTTP with temporary HMAC auth tokens.
     *
     * @param arManagerUrl the ar-manager service URL
     */
    public void setArManagerUrl(String arManagerUrl) {
        this.arManagerUrl = arManagerUrl;
    }

    /**
     * Returns the shared secret for HMAC token generation.
     */
    public String getArManagerSharedSecret() {
        return arManagerSharedSecret;
    }

    /**
     * Sets the shared secret for generating temporary HMAC auth tokens
     * that agents use to authenticate with ar-manager.
     *
     * @param sharedSecret the shared secret string
     */
    public void setArManagerSharedSecret(String sharedSecret) {
        this.arManagerSharedSecret = sharedSecret;
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
     * Handles an incoming Slack message event (workspace-aware overload).
     *
     * <p>This method is called by {@link FlowTreeController} in multi-workspace mode.
     * The {@code workspaceId} identifies which Slack team sent the event, enabling
     * future workspace-scoped routing. For Phase 1b the parameter is recorded but
     * routing is still channel-based; full workspace routing is added in Phase 1c.</p>
     *
     * @param channelId   the channel where the message was posted
     * @param userId      the user who posted the message
     * @param text        the message text
     * @param messageTs   the timestamp of this message (used to create a thread under it)
     * @param threadTs    the thread timestamp (non-null if the message is already in a thread)
     * @param workspaceId the Slack team ID of the workspace that sent this event, or {@code null}
     * @return true if a job was created, false if the message was ignored
     */
    public boolean handleMessage(String channelId, String userId, String text,
                                  String messageTs, String threadTs, String workspaceId) {
        return handleMessage(channelId, userId, text, messageTs, threadTs);
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
        Workstream workstream = channelToWorkstream.get(channelId);

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
     * Dispatches an in-message command to the appropriate handler.
     *
     * <p>Recognized commands: {@code status}, {@code cancel}, {@code task},
     * {@code do}, {@code run}. Commands with a prompt argument ({@code task},
     * {@code do}, {@code run}) immediately submit a job. Unknown commands
     * are logged and ignored.</p>
     *
     * @param workstream the workstream associated with the channel
     * @param command    the command name (without the leading slash)
     * @param args       any arguments following the command name, or {@code null}
     * @param messageTs  the triggering message timestamp (for threading)
     * @param threadTs   the existing thread timestamp, or {@code null} if top-level
     * @return {@code true} if the command was handled (even if no job was submitted)
     */
    private boolean handleCommand(Workstream workstream, String command, String args, String messageTs, String threadTs) {
        switch (command.toLowerCase()) {
            case "status":
                handleStatusCommand(workstream);
                return true;

            case "cancel":
                handleCancelCommand(workstream);
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
     * Handles the in-message {@code /status} command.
     * Posts a public message to the workstream channel with the channel name,
     * number of connected agents, and current default branch.
     *
     * @param workstream the workstream whose status to report
     */
    private void handleStatusCommand(Workstream workstream) {
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
     * Handles the in-message {@code /cancel} command.
     * Currently posts a placeholder message; job cancellation is not yet implemented.
     *
     * @param workstream the workstream where the command was issued
     */
    private void handleCancelCommand(Workstream workstream) {
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
    private boolean submitJob(Workstream workstream, String prompt, String messageTs, String threadTs) {
        Map<String, String> labels = workstream.getRequiredLabels();
        return submitJob(workstream, prompt, messageTs, threadTs,
                labels != null ? labels : Collections.emptyMap());
    }

    /**
     * Submits a job to connected agents via the FlowTree {@link Server}.
     *
     * @param workstream     the target workstream
     * @param prompt         the user prompt
     * @param messageTs      the timestamp of the triggering message (for threading)
     * @param threadTs       the existing thread timestamp (non-null if already in a thread)
     * @param requiredLabels labels that the executing Node must have
     */
    private boolean submitJob(Workstream workstream, String prompt, String messageTs, String threadTs,
                              Map<String, String> requiredLabels) {
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

        // ar-manager config: generate temporary HMAC token for this job
        if (arManagerUrl != null && !arManagerUrl.isEmpty()
                && arManagerSharedSecret != null && !arManagerSharedSecret.isEmpty()) {
            String arToken = FlowTreeApiEndpoint.generateTemporaryToken(
                workstream.getWorkstreamId(), factory.getTaskId(),
                arManagerSharedSecret, 43200);
            if (arToken != null) {
                factory.setArManagerUrl(arManagerUrl);
                factory.setArManagerToken(arToken);
            }
        }

        // Dependent repos
        if (workstream.getDependentRepos() != null) {
            factory.setDependentRepos(workstream.getDependentRepos());
        }

        // Planning document
        if (workstream.getPlanningDocument() != null) {
            factory.setPlanningDocument(workstream.getPlanningDocument());
        }

        // GitHub organization is now handled via ar-manager's workstream resolution

        // Required labels for Node routing
        if (requiredLabels != null) {
            for (Map.Entry<String, String> entry : requiredLabels.entrySet()) {
                factory.setRequiredLabel(entry.getKey(), entry.getValue());
            }
        }

        // Build workstream URL for status reporting and Slack messaging
        if (apiPort > 0) {
            String baseUrl = "http://0.0.0.0:" + apiPort
                + "/api/workstreams/" + workstream.getWorkstreamId()
                + "/jobs/" + factory.getTaskId();
            factory.setWorkstreamUrl(baseUrl);
        }

        // Notify that the job has been submitted (locally, before it leaves).
        // If the triggering message is a top-level message (threadTs == null),
        // reply under it to create a thread. If already in a thread, continue there.
        String replyTo = (threadTs == null) ? messageTs : threadTs;

        String displaySummary = ClaudeCodeJob.summarizePrompt(prompt);
        JobCompletionEvent startEvent = JobCompletionEvent.started(factory.getTaskId(), displaySummary);
        startEvent.withGitInfo(workstream.getDefaultBranch(), null, null, null, false);

        notifier.onJobSubmitted(workstream.getWorkstreamId(), startEvent, replyTo);

        // Queue locally — the NodeGroup relay mechanism distributes
        // the job to a Node whose labels match the job's requirements
        server.addTask(factory);

        log("Submitted job: " + factory.getTaskId());
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
     * Handles the {@code /flowtree} slash command (workspace-aware overload).
     *
     * <p>In multi-workspace mode the controller calls this overload so that the
     * {@code workspaceId} is available for future workspace-scoped filtering (Phase 1c).
     * For Phase 1b the parameter is accepted but routing is still channel-based.</p>
     *
     * @param text        the full command text after "/flowtree "
     * @param channelId   the channel where the command was invoked
     * @param channelName the human-readable channel name
     * @param responder   the responder for sending ephemeral replies
     * @param workspaceId the Slack team ID of the workspace, or {@code null}
     */
    public void handleSlashCommand(String text, String channelId,
                                    String channelName, SlashCommandResponder responder,
                                    String workspaceId) {
        handleSlashCommand(text, channelId, channelName, responder);
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
                    handleSlashCancelCommand(channelId, responder);
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
                case "active":
                case "workstreams":
                    handleSlashActiveCommand(responder);
                    break;
                case "default-channel":
                    handleSlashDefaultChannelCommand(args, responder);
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
                        + "  `/flowtree stats [global]` \u2014 Show weekly job statistics\n"
                        + "  `/flowtree active` \u2014 List workstreams active in the last 7 days\n"
                        + "  `/flowtree default-channel <channel>` \u2014 Set the default fallback channel");
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

        Workstream existing = channelToWorkstream.get(channelId);
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
            Workstream ws = new Workstream(channelId, channelName);
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
     *
     * <p>Checks for the {@code https://}, {@code http://}, and {@code git@}
     * prefixes that distinguish remote URLs from local directory paths.</p>
     *
     * @param value the string to test
     * @return {@code true} if {@code value} appears to be a git remote URL
     */
    private static boolean isGitUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://") || value.startsWith("git@");
    }

    /**
     * Handles {@code /flowtree info}. Displays the full workstream
     * configuration for the current channel as an ephemeral message.
     *
     * <p>Shows workstream ID, channel binding, working directory or repo URL,
     * branch settings, push policy, allowed tools, budget, and git identity.</p>
     *
     * @param channelId the Slack channel ID where the command was invoked
     * @param ctx       the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleInfoCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        Workstream ws = channelToWorkstream.get(channelId);
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
     * Handles {@code /flowtree status}. Shows the number of connected agents
     * and the configured branch for the current channel's workstream.
     *
     * @param channelId the Slack channel ID where the command was invoked
     * @param ctx       the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashStatusCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        Workstream ws = channelToWorkstream.get(channelId);
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
     *
     * <p>This command posts an ephemeral acknowledgement to the invoking user
     * if the task is submitted successfully. If no workstream is configured
     * for the channel, an error is returned.</p>
     *
     * @param channelId the Slack channel ID where the command was invoked
     * @param args      the prompt text that follows the command
     * @param ctx       the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashTaskCommand(String channelId, String args, SlashCommandResponder ctx) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            ctx.respond(":warning: Usage: `/flowtree task <prompt>`");
            return;
        }

        Workstream ws = channelToWorkstream.get(channelId);
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
     *
     * <p>Job cancellation is not yet implemented; this method posts a
     * placeholder message. When implemented, it will post a public message
     * so the whole team can see that a job was cancelled.</p>
     *
     * @param channelId the Slack channel ID where the command was invoked
     * @param ctx       the responder for sending the reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashCancelCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        Workstream ws = channelToWorkstream.get(channelId);
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
     *
     * <p>Without arguments, lists all current settings. With a key only,
     * shows the current value. With both key and value, updates the setting
     * and persists the change to the YAML config file.</p>
     *
     * @param channelId the Slack channel ID where the command was invoked
     * @param args      the optional {@code key} or {@code key value} arguments
     * @param ctx       the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashConfigCommand(String channelId, String args, SlashCommandResponder ctx) throws IOException {
        Workstream ws = channelToWorkstream.get(channelId);
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
     * Handles {@code /flowtree jobs}. Lists up to 10 recent jobs for the
     * current channel's workstream, showing job ID, status emoji, and
     * a truncated description.
     *
     * @param channelId the Slack channel ID where the command was invoked
     * @param ctx       the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashJobsCommand(String channelId, SlashCommandResponder ctx) throws IOException {
        Workstream ws = channelToWorkstream.get(channelId);
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
     * Returns the current string value of a named workstream configuration key.
     *
     * <p>Returns {@code "(not set)"} for optional fields that have not been
     * assigned, and {@code null} if the key is not recognized.</p>
     *
     * @param ws  the workstream to read from
     * @param key the setting name (e.g., {@code "maxBudgetUsd"}, {@code "defaultBranch"})
     * @return the string representation of the current value, or {@code null} if unknown
     */
    private String getConfigValue(Workstream ws, String key) {
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
     * Applies a new value to a named workstream configuration key.
     *
     * <p>Read-only keys ({@code workstreamId}, {@code channelId},
     * {@code channelName}) return an error message. Numeric keys
     * ({@code maxBudgetUsd}, {@code maxTurns}) return an error if the
     * value cannot be parsed. The caller is responsible for persisting
     * the change to YAML after a successful update.</p>
     *
     * @param ws    the workstream to update
     * @param key   the setting name
     * @param value the new value as a string
     * @return an error message if the update failed, or {@code null} on success
     */
    private String setConfigValue(Workstream ws, String key, String value) {
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
     * Handles {@code /flowtree stats [global]}. Shows weekly job statistics.
     *
     * <p>Without arguments, shows stats for the current channel's workstream.
     * With {@code global}, shows stats across all workstreams.</p>
     */
    private void handleSlashStatsCommand(String channelId, String args,
                                          SlashCommandResponder ctx) throws IOException {
        JobStatsStore statsStore = notifier.getStatsStore();
        if (statsStore == null) {
            ctx.respond(":warning: Job statistics are not available.");
            return;
        }

        boolean global = "global".equalsIgnoreCase(args != null ? args.trim() : "");

        if (!global) {
            Workstream ws = channelToWorkstream.get(channelId);
            if (ws == null) {
                ctx.respond(":warning: No workstream configured for this channel.\n"
                    + "Use `/flowtree setup <working-directory-or-repo-url> <branch>` to create one.\n"
                    + "Or use `/flowtree stats global` for stats across all workstreams.");
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
        } else {
            LocalDate today = LocalDate.now(ZoneId.of("UTC"));
            LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate lastWeekStart = thisWeekStart.minusWeeks(1);

            // Build workstream ID to channel name lookup
            Map<String, String> wsToChannel = new HashMap<>();
            for (Workstream ws : channelToWorkstream.values()) {
                wsToChannel.put(ws.getWorkstreamId(), ws.getChannelName());
            }

            StringBuilder sb = new StringBuilder();
            sb.append(":bar_chart: *Agent Activity \u2014 All Workstreams*\n\n");

            // Global totals
            JobStatsStore.WeeklyStats thisWeekTotal = statsStore.getWeeklyStats(thisWeekStart);
            JobStatsStore.WeeklyStats lastWeekTotal = statsStore.getWeeklyStats(lastWeekStart);
            sb.append(formatWeeklyStats("This Week (total)", thisWeekStart, thisWeekTotal));
            sb.append("\n");
            sb.append(formatWeeklyStats("Last Week (total)", lastWeekStart, lastWeekTotal));

            // Per-workstream breakdown for this week
            Map<String, JobStatsStore.WeeklyStats> thisWeekByWs = statsStore.getWeeklyStatsByWorkstream(thisWeekStart);
            if (!thisWeekByWs.isEmpty()) {
                sb.append("\n:mag: *This Week by Workstream*\n");
                for (Map.Entry<String, JobStatsStore.WeeklyStats> entry : thisWeekByWs.entrySet()) {
                    String label = wsToChannel.getOrDefault(entry.getKey(), entry.getKey());
                    JobStatsStore.WeeklyStats stats = entry.getValue();
                    sb.append("  *").append(label).append("*: ");
                    sb.append(stats.jobCount).append(" jobs, ");
                    sb.append(formatDuration(stats.totalWallClockMs)).append(", ");
                    sb.append("$").append(String.format("%.2f", stats.totalCostUsd)).append("\n");
                }
            }

            ctx.respond(sb.toString());
        }
    }

    /**
     * Handles {@code /flowtree active} (alias: {@code /flowtree workstreams}).
     * Lists all workstreams that completed jobs in the last 7 days, with
     * job counts and links to the most recent Slack messages for each workstream.
     *
     * @param ctx the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashActiveCommand(SlashCommandResponder ctx) throws IOException {
        JobStatsStore statsStore = notifier.getStatsStore();
        if (statsStore == null) {
            ctx.respond(":warning: Job statistics are not available.");
            return;
        }

        Instant since = Instant.now().minusSeconds(7 * 24 * 3600);
        Map<String, JobStatsStore.WorkstreamActivity> active = statsStore.getActiveWorkstreams(since);

        if (active.isEmpty()) {
            ctx.respond(":zzz: No workstreams had activity in the last 7 days.");
            return;
        }

        // Build workstream ID to Workstream lookup
        Map<String, Workstream> wsById = new HashMap<>();
        for (Workstream ws : channelToWorkstream.values()) {
            wsById.put(ws.getWorkstreamId(), ws);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(":globe_with_meridians: *Active Workstreams \u2014 Last 7 Days*\n\n");

        // Cap total Slack API permalink calls across all workstreams to avoid rate limiting.
        // The first MAX_PERMALINK_API_CALLS links use the API; the rest fall back to
        // constructed archive URLs (which resolve in standard Slack workspaces).
        final int MAX_PERMALINK_API_CALLS = 9;
        int totalPermalinkApiCalls = 0;

        for (JobStatsStore.WorkstreamActivity activity : active.values()) {
            Workstream ws = wsById.get(activity.workstreamId);
            String label = ws != null ? ws.getChannelName() : activity.workstreamId;
            String branch = ws != null && ws.getDefaultBranch() != null
                ? "`" + ws.getDefaultBranch() + "`"
                : "(no branch)";
            String channelId = ws != null ? ws.getChannelId() : null;

            sb.append("*").append(label).append("*");
            sb.append(" \u2014 branch: ").append(branch).append("\n");
            sb.append("  :hammer: ").append(activity.jobCount).append(" jobs");
            sb.append(" (:white_check_mark: ").append(activity.successCount);
            sb.append("  :x: ").append(activity.failedCount);
            if (activity.cancelledCount > 0) {
                sb.append("  :no_entry_sign: ").append(activity.cancelledCount);
            }
            sb.append(")\n");

            if (!activity.recentJobs.isEmpty() && channelId != null) {
                sb.append("  :link: Recent: ");
                int linkCount = 0;
                for (String[] jobEntry : activity.recentJobs) {
                    String slackTs = jobEntry[1];
                    if (slackTs != null && !slackTs.isEmpty()) {
                        if (linkCount > 0) sb.append(", ");
                        String permalink = null;
                        if (totalPermalinkApiCalls < MAX_PERMALINK_API_CALLS) {
                            permalink = notifier.getPermalink(channelId, slackTs);
                            totalPermalinkApiCalls++;
                        }
                        if (permalink != null) {
                            sb.append("<").append(permalink).append("|job>");
                        } else {
                            // Fallback: construct URL from channel and ts (resolves in standard workspaces)
                            String tsForUrl = slackTs.replace(".", "");
                            sb.append("<https://slack.com/archives/").append(channelId)
                              .append("/p").append(tsForUrl).append("|job>");
                        }
                        linkCount++;
                        if (linkCount >= 3) break;
                    }
                }
                sb.append("\n");
            }

            sb.append("\n");
        }

        ctx.respond(sb.toString().trim());
    }

    /**
     * Handles {@code /flowtree default-channel <channel>}.
     * Updates the global default fallback Slack channel at runtime and
     * optionally persists the change to the YAML config file.
     *
     * <p>The channel argument must be a Slack channel ID (e.g., {@code C0123456789}).
     * Channel names are not accepted because {@code SlackNotifier} passes the value
     * directly to the Slack API without name-to-ID resolution.</p>
     *
     * @param args the channel ID to set as the default
     * @param ctx  the responder for sending the ephemeral reply
     * @throws IOException if the response cannot be sent
     */
    private void handleSlashDefaultChannelCommand(String args, SlashCommandResponder ctx) throws IOException {
        if (args == null || args.trim().isEmpty()) {
            String current = notifier.getDefaultChannelId();
            ctx.respond(":gear: Current default channel: "
                + (current != null ? "`" + current + "`" : "(not set)") + "\n"
                + "Usage: `/flowtree default-channel <channel-id>` (e.g. `C0123456789`)\n"
                + ":information_source: A channel ID is required — channel names are not resolved.");
            return;
        }

        String channel = args.trim();
        // Strip leading # as a convenience but warn that an ID is expected
        if (channel.startsWith("#")) {
            channel = channel.substring(1);
        }

        // Slack channel IDs start with C (public), D (DM), G (private/MPIM), or W (workspace).
        // If the value looks like a plain name, warn the caller so they don't accidentally
        // misconfigure the fallback channel with a value that the API will reject.
        boolean looksLikeId = channel.length() > 1
                && (channel.charAt(0) == 'C' || channel.charAt(0) == 'D'
                    || channel.charAt(0) == 'G' || channel.charAt(0) == 'W')
                && channel.chars().allMatch(Character::isLetterOrDigit);
        if (!looksLikeId) {
            ctx.respond(":warning: `" + channel + "` does not look like a Slack channel ID. "
                + "Channel IDs start with `C`, `D`, `G`, or `W` (e.g. `C0123456789`). "
                + "Channel names are not resolved — please provide the ID.");
            return;
        }

        notifier.setDefaultChannelId(channel);

        if (workstreamConfig != null) {
            workstreamConfig.setDefaultChannel(channel);
        }

        persistConfig();

        ctx.respond(":white_check_mark: Default channel set to `" + channel + "`\n"
            + "Messages without a configured workstream channel will now fall back here.");
    }

    /**
     * Formats a week's statistics as a Slack mrkdwn block for display.
     *
     * <p>Outputs a header line with the label and date range, followed by
     * total wall-clock time, job counts by status, total USD cost, and
     * total agent turns.</p>
     *
     * @param label     display label for the block (e.g., "This Week")
     * @param weekStart the Monday that begins this week
     * @param stats     the aggregated statistics for the week
     * @return a formatted mrkdwn string ready for posting to Slack
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
     *
     * <p>Returns {@code "0m"} for non-positive values, {@code "Xm"} for
     * durations under one hour, and {@code "Xh Ym"} for longer durations.</p>
     *
     * @param ms the duration in milliseconds
     * @return a human-readable duration string
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
     * Persists the current in-memory workstream state back to the YAML config file.
     *
     * <p>Syncs all {@link #channelToWorkstream} entries into the {@link #workstreamConfig}
     * model via {@link WorkstreamConfig#syncFromWorkstreams} and then writes the
     * updated config to {@link #configFile}. If either is {@code null} (e.g., when
     * the controller was configured programmatically without a file), changes
     * are runtime-only and a warning is logged.</p>
     */
    void persistConfig() {
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

    /**
     * Truncates a string to at most {@code maxLength} characters, appending
     * {@code "..."} if the string was shortened. Returns an empty string for
     * {@code null} input.
     *
     * @param s         the string to truncate (may be {@code null})
     * @param maxLength the maximum length of the returned string, inclusive of the ellipsis
     * @return the (possibly truncated) string, never {@code null}
     */
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
    public Workstream getWorkstream(String channelId) {
        return channelToWorkstream.get(channelId);
    }

    /**
     * Returns a snapshot of all registered workstreams, keyed by channel ID.
     *
     * @return a new map containing all channel-to-workstream mappings
     */
    public Map<String, Workstream> getWorkstreams() {
        return new HashMap<>(channelToWorkstream);
    }
}
