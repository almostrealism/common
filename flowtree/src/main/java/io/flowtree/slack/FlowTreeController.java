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

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import fi.iki.elonen.NanoHTTPD;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import io.flowtree.Server;
import io.flowtree.jobs.GitOperations;
import io.flowtree.jobs.McpToolDiscovery;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.util.SignalWireDeliveryProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Main controller for FlowTree orchestration with optional Slack integration.
 *
 * <p>This class manages the FlowTree agent lifecycle. When Slack tokens are provided,
 * it connects via the Bolt SDK's Socket Mode for real-time event handling. When
 * tokens are absent, it runs in simulation mode with only the HTTP API endpoint
 * active, allowing job submission via {@code POST /api/workstreams/{id}/submit}.</p>
 *
 * <h2>Token Resolution</h2>
 * <p>Slack tokens are resolved in the following order (first match wins):</p>
 * <ol>
 *   <li>{@code --tokens <file>} CLI argument (explicit path)</li>
 *   <li>{@code slack-tokens.json} in the current working directory</li>
 *   <li>{@code SLACK_BOT_TOKEN} / {@code SLACK_APP_TOKEN} environment variables</li>
 * </ol>
 *
 * @see SlackTokens
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FlowTreeController controller = new FlowTreeController();
 *
 * // Load workstreams from YAML
 * controller.loadConfig(new File("workstreams.yaml"));
 *
 * // Or configure programmatically
 * SlackWorkstream workstream = new SlackWorkstream("C0123456789", "#project-agent");
 * workstream.addAgent("localhost", 7766);
 * workstream.setDefaultBranch("feature/work");
 * controller.registerWorkstream(workstream);
 *
 * // Start the controller
 * controller.start();
 * }</pre>
 *
 * @author Michael Murray
 * @see SlackListener
 * @see SlackNotifier
 * @see SlackWorkstream
 */
public class FlowTreeController implements ConsoleFeatures {

    /** Slack Bot User OAuth Token (xoxb-...) used for posting messages. */
    private final String botToken;
    /** Slack App-level token (xapp-...) used for Socket Mode event delivery. */
    private final String appToken;
    /** Posts job status updates and completion notifications to Slack channels. */
    private final SlackNotifier notifier;
    /** Parses incoming Slack messages and slash commands into agent jobs. */
    private final SlackListener listener;

    /** Guards against double-start; set to {@code true} on the first {@link #start()} call. */
    private final AtomicBoolean running;
    /** The Bolt Slack application that registers event handlers. */
    private App app;
    /** Maintains the persistent Socket Mode WebSocket connection to Slack. */
    private SocketModeApp socketModeApp;
    /** Bot's own Slack user ID; used to suppress echoed messages from the bot itself. */
    private String botUserId;

    /** The FlowTree server that accepts inbound agent connections and relays jobs. */
    private Server flowtreeServer;
    /** Port the FlowTree server listens on for agent connections. */
    private int flowtreePort = Server.defaultPort;

    /** The YAML config file currently loaded; used for hot-reload and persistence. */
    private File configFile;
    /** The in-memory representation of the loaded YAML configuration. */
    private WorkstreamConfig loadedConfig;

    /** HTTP API endpoint for programmatic job submission and agent communication. */
    private FlowTreeApiEndpoint apiEndpoint;
    /** Desired port for the HTTP API endpoint; 0 for ephemeral assignment. */
    private int apiPort = FlowTreeApiEndpoint.DEFAULT_PORT;
    /** Persistent store for per-workstream job statistics. */
    private JobStatsStore statsStore;

    /** Managed MCP server subprocesses started by the controller (legacy, currently unused). */
    private List<Process> mcpProcesses = new ArrayList<>();

    /** Optional simulator callback used by tests to capture outgoing Slack messages. */
    private BiConsumer<String, String> eventSimulator;
    /** True when tokens are absent and the controller runs without a live Slack connection. */
    private boolean simulationMode = false;

    /**
     * Creates a new controller, resolving tokens from the default
     * locations (convention file, then environment variables).
     *
     * @throws IOException if token resolution fails
     * @see SlackTokens#resolve(java.io.File)
     */
    public FlowTreeController() throws IOException {
        this(SlackTokens.resolve(null));
    }

    /**
     * Creates a new controller with tokens loaded from a {@link SlackTokens} instance.
     *
     * @param tokens the resolved tokens
     */
    public FlowTreeController(SlackTokens tokens) {
        this(tokens.getBotToken(), tokens.getAppToken());
    }

    /**
     * Creates a new controller with explicit tokens.
     *
     * @param botToken the Slack Bot User OAuth Token
     * @param appToken the Slack App-level token (for Socket Mode)
     */
    public FlowTreeController(String botToken, String appToken) {
        this.botToken = botToken;
        this.appToken = appToken;
        this.notifier = new SlackNotifier(botToken);
        this.listener = new SlackListener(notifier);
        this.running = new AtomicBoolean(false);
    }

    /**
     * Returns the port used by the HTTP API endpoint.
     */
    public int getApiPort() {
        return apiPort;
    }

    /**
     * Sets the port for the HTTP API endpoint. Must be called before {@link #start()}.
     *
     * @param apiPort the port number (0 for ephemeral)
     */
    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
    }

    /**
     * Returns the port the FlowTree server listens on for agent connections.
     */
    public int getFlowtreePort() {
        return flowtreePort;
    }

    /**
     * Sets the port the FlowTree server listens on for agent connections.
     * Must be called before {@link #start()}.
     *
     * @param flowtreePort the port number
     */
    public void setFlowtreePort(int flowtreePort) {
        this.flowtreePort = flowtreePort;
    }

    /**
     * Loads workstream configuration from a YAML file.
     *
     * <p>If any workstream entries lack a {@code workstreamId}, one is
     * auto-generated and the file is rewritten to persist the IDs.</p>
     *
     * @param configFile the YAML configuration file
     * @throws IOException if the file cannot be read or parsed
     */
    public void loadConfig(File configFile) throws IOException {
        this.configFile = configFile;
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(configFile);
        this.loadedConfig = config;

        if (config.ensureWorkstreamIds()) {
            config.saveToYaml(configFile);
            log("Generated workstream IDs and saved to " + configFile.getName());
        }

        for (SlackWorkstream workstream : config.toWorkstreams()) {
            registerWorkstream(workstream);
        }
        log("Loaded " + config.getWorkstreams().size() +
                          " workstream(s) from " + configFile.getName());

        // Pass global default workspace path to listener
        if (config.getDefaultWorkspacePath() != null) {
            listener.setDefaultWorkspacePath(config.getDefaultWorkspacePath());
        }

        // Pass channel owner user ID to notifier for auto-inviting to new channels
        if (config.getChannelOwnerUserId() != null) {
            notifier.setChannelOwnerUserId(config.getChannelOwnerUserId());
        }

        // Pass default channel to notifier for fallback message delivery
        if (config.getDefaultChannel() != null) {
            notifier.setDefaultChannelId(config.getDefaultChannel());
        }

        // Pass config and file reference to listener for /flowtree setup persistence
        listener.setWorkstreamConfig(config, configFile);

        // Validate GitHub tokens before proceeding
        validateGitHubTokens(config);

        // Configure ar-manager URL and shared secret for agent jobs
        String arManagerUrl = System.getenv("AR_MANAGER_URL");
        if (arManagerUrl == null || arManagerUrl.isEmpty()) {
            arManagerUrl = "http://ar-manager:8010";
        }
        String arManagerSecret = loadSharedSecret();
        listener.setArManagerUrl(arManagerUrl);
        if (arManagerSecret != null && !arManagerSecret.isEmpty()) {
            listener.setArManagerSharedSecret(arManagerSecret);
            log("ar-manager URL: " + arManagerUrl + " (HMAC auth enabled)");
        } else {
            log("ar-manager URL: " + arManagerUrl + " (WARNING: no shared secret — agents will not have ar-manager access)");
        }
    }

    /**
     * Validates all GitHub tokens referenced by the configuration.
     *
     * <p>Checks that each token is valid, can access its target repositories,
     * and has the required permissions for PR operations. If any token fails
     * validation, the controller exits with an error to prevent agents from
     * running with broken credentials.</p>
     *
     * @param config the loaded workstream configuration
     */
    private void validateGitHubTokens(WorkstreamConfig config) {
        GitHubTokenValidator validator = new GitHubTokenValidator();
        List<GitHubTokenValidator.TokenValidationResult> results =
                validator.validateAll(config);

        if (results.isEmpty()) {
            log("No GitHub tokens configured — skipping validation");
            return;
        }

        log("Validating " + results.size() + " GitHub token(s)...");

        boolean anyFailed = false;
        for (GitHubTokenValidator.TokenValidationResult result : results) {
            if (result.isValid()) {
                log("  [OK] " + result.getLabel());
            } else {
                anyFailed = true;
                warn("  [FAIL] " + result.getLabel());
                for (String error : result.getErrors()) {
                    warn("    - " + error);
                }
            }
        }

        if (anyFailed) {
            warn("GitHub token validation failed — fix the tokens above and restart");
            System.exit(1);
        }

        log("All GitHub tokens validated successfully");
    }

    /**
     * Loads workstream configuration from a YAML string.
     *
     * @param yaml the YAML configuration content
     * @throws IOException if the content cannot be parsed
     */
    public void loadConfigFromYaml(String yaml) throws IOException {
        WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
        for (SlackWorkstream workstream : config.toWorkstreams()) {
            registerWorkstream(workstream);
        }
    }

    /**
     * Reloads workstream configuration from the YAML file originally
     * passed to {@link #loadConfig(File)}.
     *
     * <p>New workstreams are registered and existing ones are updated.
     * Any missing workstream IDs are generated and persisted.</p>
     */
    public synchronized void reloadConfig() {
        if (configFile == null || !configFile.exists()) {
            log("No config file to reload");
            return;
        }

        try {
            WorkstreamConfig config = WorkstreamConfig.loadFromYaml(configFile);
            if (config.ensureWorkstreamIds()) {
                config.saveToYaml(configFile);
                log("Generated workstream IDs and saved to " + configFile.getName());
            }

            if (config.getDefaultWorkspacePath() != null) {
                listener.setDefaultWorkspacePath(config.getDefaultWorkspacePath());
            }

            for (SlackWorkstream workstream : config.toWorkstreams()) {
                registerWorkstream(workstream);
            }
            log("Reloaded " + config.getWorkstreams().size() +
                              " workstream(s) from " + configFile.getName());
        } catch (IOException e) {
            warn("Failed to reload config: " + e.getMessage());
        }
    }

    /**
     * Registers a workstream with the controller.
     *
     * @param workstream the workstream configuration
     */
    public void registerWorkstream(SlackWorkstream workstream) {
        listener.registerWorkstream(workstream);
        log("Registered workstream: " + workstream.getChannelName());
    }

    /**
     * Returns the notifier instance.
     */
    public SlackNotifier getNotifier() {
        return notifier;
    }

    /**
     * Returns the listener instance.
     */
    public SlackListener getListener() {
        return listener;
    }

    /**
     * Starts the controller.
     *
     * <p>When Slack tokens are present, connects via Socket Mode. Otherwise,
     * runs in simulation mode with only the HTTP API endpoint active for
     * programmatic job submission.</p>
     *
     * @throws Exception if startup fails
     */
    public void start() throws Exception {
        if (running.getAndSet(true)) {
            log("Already running");
            return;
        }

        String logFile = System.getProperty("flowtree.log.file",
            System.getenv().getOrDefault("FLOWTREE_LOG_FILE", "flowtree-controller.log"));
        Console.root().addListener(OutputFeatures.fileOutput(logFile));
        log("Logging to file: " + logFile);

        log("===========================================");
        log("  FlowTree Controller - Agent Orchestrator");
        log("===========================================");

        // Start FlowTree server for inbound agent connections.
        // The controller runs one relay Node (nodes.initial=1) with a "role:relay"
        // label so it never matches job requirements (e.g., platform:macos).
        // This Node serves as a warehouse: jobs sit in its queue and get
        // picked up by connected agent Nodes for execution.
        Properties flowtreeProps = new Properties();
        flowtreeProps.setProperty("server.port", String.valueOf(flowtreePort));
        flowtreeProps.setProperty("nodes.initial", "1");
        flowtreeProps.setProperty("nodes.jobs.max", "100");
        flowtreeProps.setProperty("nodes.mjp", "0.0");
        flowtreeProps.setProperty("group.thread.sleep", "2000");
        flowtreeProps.setProperty("nodes.labels.role", "relay");
        flowtreeServer = new Server(flowtreeProps);
        flowtreeServer.start();
        listener.setServer(flowtreeServer);
        listener.setConfigReloader(this::reloadConfig);
        log("FlowTree server listening on port " + flowtreePort);

        if (botToken == null || botToken.isEmpty() || appToken == null || appToken.isEmpty()) {
            log("WARNING: Missing SLACK_BOT_TOKEN or SLACK_APP_TOKEN");
            log("         Running in simulation mode");
            simulationMode = true;
            startApiEndpoint();
            // Pushed tools removed — ar-manager is the single centralized tool
            printStartupSummary();
            return;
        }

        // Create the Bolt app
        AppConfig appConfig = AppConfig.builder()
            .singleTeamBotToken(botToken)
            .build();
        app = new App(appConfig);

        // Register event handlers
        registerEventHandlers();

        // Get bot user ID
        try {
            AuthTestResponse authTest = app.client().authTest(r -> r.token(botToken));
            if (authTest.isOk()) {
                botUserId = authTest.getUserId();
                log("Bot User ID: " + botUserId);
                log("Bot Name: " + authTest.getUser());
                log("Team: " + authTest.getTeam());
            } else {
                warn("Auth test failed: " + authTest.getError());
            }
        } catch (SlackApiException e) {
            warn("Failed to verify bot token: " + e.getMessage());
        }

        // Start Socket Mode
        socketModeApp = new SocketModeApp(appToken, app);
        socketModeApp.startAsync();

        log("Socket Mode connection established");

        startApiEndpoint();
        // Pushed tools removed — ar-manager is the single centralized tool
        printStartupSummary();
    }

    /**
     * Registers all Slack event handlers with the Bolt {@link App}.
     *
     * <p>Handlers registered here:</p>
     * <ul>
     *   <li>{@code /flowtree} slash command — dispatched to
     *       {@link SlackListener#handleSlashCommand}</li>
     *   <li>{@code app_mention} events — direct job submission via
     *       {@link SlackListener#handleMessage}</li>
     *   <li>{@code message} events in DM channels ({@code im} channel type) —
     *       also dispatched to {@link SlackListener#handleMessage}</li>
     * </ul>
     *
     * <p>The bot suppresses its own messages by comparing the event user ID to
     * {@link #botUserId}.</p>
     */
    private void registerEventHandlers() {
        // Handle /flowtree slash command
        app.command("/flowtree", (req, ctx) -> {
            String channelId = req.getPayload().getChannelId();
            String channelName = req.getPayload().getChannelName();
            String text = req.getPayload().getText();
            listener.handleSlashCommand(text, channelId, channelName, ctx::respond);
            return ctx.ack();
        });

        // Handle app mentions (@bot message)
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            AppMentionEvent event = payload.getEvent();
            String channelId = event.getChannel();
            String userId = event.getUser();
            String text = event.getText();
            String messageTs = event.getTs();
            String threadTs = event.getThreadTs();

            log("App mention in " + channelId + ": " + text);

            // Skip bot's own messages
            if (userId != null && userId.equals(botUserId)) {
                return ctx.ack();
            }

            listener.handleMessage(channelId, userId, text, messageTs, threadTs);
            return ctx.ack();
        });

        // Handle direct messages (optional)
        app.event(MessageEvent.class, (payload, ctx) -> {
            MessageEvent event = payload.getEvent();

            // Only handle DMs (channel type "im")
            if (!"im".equals(event.getChannelType())) {
                return ctx.ack();
            }

            String channelId = event.getChannel();
            String userId = event.getUser();
            String text = event.getText();
            String messageTs = event.getTs();
            String threadTs = event.getThreadTs();

            // Skip bot's own messages
            if (userId != null && userId.equals(botUserId)) {
                return ctx.ack();
            }

            log("DM from " + userId + ": " + text);
            listener.handleMessage(channelId, userId, text, messageTs, threadTs);
            return ctx.ack();
        });
    }

    /**
     * Starts centralized MCP servers as HTTP processes and builds the
     * centralized config JSON for passing to agents.
     *
     * <p>Each server in the YAML {@code mcpServers} section is started as a
     * Python subprocess with {@code MCP_TRANSPORT=http} and its configured
     * port. The source file paths are resolved relative to the config file's
     * parent directory.</p>
     *
     * <p><b>Deprecated:</b> Centralized MCP servers are no longer managed by the controller.
     * ar-manager is the single centralized tool, running as a separate Docker service.
     * This method is retained for reference but is not called during normal startup.</p>
     *
     * @param config    the parsed workstream configuration
     * @param configDir the directory containing the YAML config file, used to
     *                  resolve relative source paths (may be null)
     * @deprecated ar-manager replaces all centralized MCP servers
     */
    @Deprecated
    private void startCentralizedMcpServers(WorkstreamConfig config, File configDir) {
        // No-op: ar-manager replaces all centralized MCP servers.
        // Agents access ar-manager over HTTP with HMAC temp tokens.
        Map<String, WorkstreamConfig.McpServerEntry> mcpServers = config.getMcpServers();
        if (mcpServers == null || mcpServers.isEmpty()) return;

        log("Configuring " + mcpServers.size() + " centralized MCP server(s)...");

        // Build the centralized config JSON as we register each server
        StringBuilder configJson = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, WorkstreamConfig.McpServerEntry> entry : mcpServers.entrySet()) {
            String serverName = entry.getKey();
            WorkstreamConfig.McpServerEntry serverEntry = entry.getValue();

            String url;
            List<String> tools;

            if (serverEntry.isExternal()) {
                // External server: already running, just reference by URL
                url = serverEntry.getUrl();
                tools = serverEntry.getTools();
                if (tools == null || tools.isEmpty()) {
                    warn("External server " + serverName + " has no tools listed — "
                        + "agents will not be able to use it");
                    tools = List.of();
                }
                log("External " + serverName + " at " + url
                    + " (" + tools.size() + " tools)");
            } else {
                // Managed server: resolve source, discover tools, launch process
                Path sourcePath = resolveToolSource(serverEntry.getSource(), configDir);

                tools = McpToolDiscovery.discoverToolNames(sourcePath);
                if (tools.isEmpty()) {
                    warn("No tools discovered from " + sourcePath + " for server " + serverName);
                }

                try {
                    ProcessBuilder pb = new ProcessBuilder("python3", sourcePath.toString());
                    pb.environment().put("MCP_TRANSPORT", "http");
                    pb.environment().put("MCP_PORT", String.valueOf(serverEntry.getPort()));

                    for (Map.Entry<String, String> env : System.getenv().entrySet()) {
                        if (env.getKey().startsWith("AR_")) {
                            pb.environment().put(env.getKey(), env.getValue());
                        }
                    }

                    pb.inheritIO();
                    GitOperations.augmentPath(pb);

                    Process process = pb.start();
                    mcpProcesses.add(process);
                    log("Started " + serverName + " on port " + serverEntry.getPort()
                        + " (PID " + process.pid() + ", " + tools.size() + " tools)");
                } catch (IOException e) {
                    warn("Failed to start " + serverName + ": " + e.getMessage());
                    continue;
                }

                url = "http://0.0.0.0:" + serverEntry.getPort() + "/mcp";
            }

            if (!first) configJson.append(",");
            first = false;
            configJson.append("\"").append(serverName).append("\":{");
            configJson.append("\"url\":\"").append(url).append("\",");
            configJson.append("\"tools\":[");
            for (int i = 0; i < tools.size(); i++) {
                if (i > 0) configJson.append(",");
                configJson.append("\"").append(tools.get(i)).append("\"");
            }
            configJson.append("]}");
        }

        configJson.append("}");
        String centralizedConfig = configJson.toString();

        log("Centralized MCP config (legacy, not used): " + centralizedConfig);
    }

    /**
     * Loads the ar-manager shared secret from a file or environment variable.
     *
     * <p>Checks {@code AR_MANAGER_SHARED_SECRET_FILE} first (path to a file
     * containing the secret), then falls back to {@code AR_MANAGER_SHARED_SECRET}
     * (the secret value directly).</p>
     *
     * @return the shared secret, or null if not configured
     */
    static String loadSharedSecret() {
        String secretFile = System.getenv("AR_MANAGER_SHARED_SECRET_FILE");
        if (secretFile != null && !secretFile.isEmpty()) {
            try {
                String secret = Files.readString(
                    Path.of(secretFile), StandardCharsets.UTF_8).trim();
                if (!secret.isEmpty()) {
                    return secret;
                }
            } catch (IOException e) {
                Console.root().warn("Failed to read shared secret file " + secretFile + ": " + e.getMessage());
            }
        }
        return System.getenv("AR_MANAGER_SHARED_SECRET");
    }

    /**
     * Resolves a tool source path, trying the config directory first then
     * falling back to the application directory.
     *
     * <p>This supports container deployments where {@code /config} is a
     * volume mount that hides files bundled into the image at {@code /app}.
     * The fallback application directory is controlled by the
     * {@code FLOWTREE_APP_DIR} environment variable (default {@code /app}).</p>
     *
     * @param source    the relative source path from the YAML configuration
     * @param configDir the directory containing the loaded YAML config file,
     *                  or {@code null} if no file was loaded
     * @return the resolved {@link Path}; may not exist if neither location found
     */
    private static Path resolveToolSource(String source, File configDir) {
        if (configDir != null) {
            Path configRelative = configDir.toPath().resolve(source);
            if (Files.exists(configRelative)) {
                return configRelative;
            }
        }

        // Fall back to app directory (set via FLOWTREE_APP_DIR, default /app)
        String appDir = System.getenv("FLOWTREE_APP_DIR");
        if (appDir == null) appDir = "/app";
        Path appRelative = Path.of(appDir).resolve(source);
        if (Files.exists(appRelative)) {
            return appRelative;
        }

        // Neither exists — return the config-relative path for the error message
        if (configDir != null) {
            return configDir.toPath().resolve(source);
        }
        return Path.of(source);
    }

    /**
     * Registers pushed MCP tool files with the API endpoint and builds
     * the pushed-tools configuration JSON for passing to agents.
     *
     * <p>Each tool in the YAML {@code pushedTools} section is registered
     * with the API endpoint for serving via {@code GET /api/tools/{name}}.
     * Tool names are discovered from the Python source files so they can
     * be included in the per-job allowed-tools list. The resulting JSON
     * is logged but is no longer actively used since ar-manager replaced
     * pushed tools as the standard MCP integration.</p>
     *
     * <p>Must be called after {@link #startApiEndpoint()} since it requires
     * the API endpoint reference and its resolved listening port.</p>
     */
    private void registerPushedTools() {
        if (loadedConfig == null || apiEndpoint == null) return;

        Map<String, WorkstreamConfig.PushedToolEntry> pushedTools = loadedConfig.getPushedTools();
        if (pushedTools == null || pushedTools.isEmpty()) return;

        File configDir = configFile != null ? configFile.getParentFile() : null;
        int listeningPort = apiEndpoint.getListeningPort();

        log("Registering " + pushedTools.size() + " pushed tool(s)...");

        StringBuilder configJson = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, WorkstreamConfig.PushedToolEntry> entry : pushedTools.entrySet()) {
            String serverName = entry.getKey();
            WorkstreamConfig.PushedToolEntry toolEntry = entry.getValue();

            // Resolve source path relative to config file directory,
            // falling back to the app directory for container deployments
            // where /config is a volume mount that hides bundled files
            Path sourcePath = resolveToolSource(toolEntry.getSource(), configDir);

            // Discover tool names from the source file
            List<String> tools = McpToolDiscovery.discoverToolNames(sourcePath);
            if (tools.isEmpty()) {
                warn("No tools discovered from " + sourcePath + " for pushed tool " + serverName);
            }

            // Register the file with the API endpoint
            apiEndpoint.registerToolFile(serverName, sourcePath);

            // Build this tool's entry in the config JSON
            String url = "http://0.0.0.0:" + listeningPort + "/api/tools/" + serverName;

            if (!first) configJson.append(",");
            first = false;
            configJson.append("\"").append(serverName).append("\":{");
            configJson.append("\"url\":\"").append(url).append("\",");
            configJson.append("\"tools\":[");
            for (int i = 0; i < tools.size(); i++) {
                if (i > 0) configJson.append(",");
                configJson.append("\"").append(tools.get(i)).append("\"");
            }
            configJson.append("]");

            Map<String, String> env = toolEntry.getEnv();
            if (env != null && !env.isEmpty()) {
                configJson.append(",\"env\":{");
                boolean firstEnv = true;
                for (Map.Entry<String, String> e : env.entrySet()) {
                    if (!firstEnv) configJson.append(",");
                    firstEnv = false;
                    configJson.append("\"").append(e.getKey()).append("\":\"")
                              .append(e.getValue()).append("\"");
                }
                configJson.append("}");
            }

            configJson.append("}");

            log("Registered pushed tool: " + serverName
                + " (" + tools.size() + " tools, served at /api/tools/" + serverName + ")");
        }

        configJson.append("}");
        String pushedConfig = configJson.toString();

        log("Pushed tools config (legacy, not used): " + pushedConfig);
    }

    /**
     * Starts the HTTP API endpoint for receiving messages from MCP tools
     * and for programmatic job submission.
     */
    private void startApiEndpoint() {
        // Initialize stats store — prefer the config directory (which is
        // typically a persistent volume mount) over user.home so that stats
        // survive container rebuilds.
        String dataDir = configFile != null
                ? configFile.getParentFile().getAbsolutePath()
                : System.getProperty("user.home") + "/.flowtree";
        new File(dataDir).mkdirs();
        statsStore = new JobStatsStore(dataDir + "/stats");
        statsStore.initialize();
        notifier.setStatsStore(statsStore);

        // Initialize SignalWire SMS alerting (no-op if config file is absent)
        SignalWireDeliveryProvider.attachDefault();

        try {
            apiEndpoint = new FlowTreeApiEndpoint(apiPort, notifier);
            apiEndpoint.setServer(flowtreeServer);
            apiEndpoint.setListener(listener);
            apiEndpoint.setStatsStore(statsStore);

            // Populate per-org GitHub tokens from config
            if (loadedConfig != null && loadedConfig.getGithubOrgs() != null) {
                Map<String, String> orgTokens = new LinkedHashMap<>();
                for (Map.Entry<String, WorkstreamConfig.GitHubOrgEntry> entry
                        : loadedConfig.getGithubOrgs().entrySet()) {
                    String orgToken = entry.getValue().getToken();
                    if (orgToken != null && !orgToken.isEmpty()) {
                        orgTokens.put(entry.getKey(), orgToken);
                    }
                }
                if (!orgTokens.isEmpty()) {
                    apiEndpoint.setGithubOrgTokens(orgTokens);
                    log("Loaded GitHub tokens for " + orgTokens.size() + " org(s)");
                }
            }

            // Configure memory server URL for message storage
            String memoryUrl = System.getenv("AR_MEMORY_URL");
            if (memoryUrl == null || memoryUrl.isEmpty()) {
                memoryUrl = "http://localhost:8020";
            }
            apiEndpoint.setMemoryServerUrl(memoryUrl);
            log("Memory server URL: " + memoryUrl);

            // Configure ar-manager URL for token generation
            String arManagerUrl = System.getenv("AR_MANAGER_URL");
            if (arManagerUrl == null || arManagerUrl.isEmpty()) {
                arManagerUrl = "http://ar-manager:8010";
            }
            apiEndpoint.setArManagerUrl(arManagerUrl);
            log("AR Manager URL: " + arManagerUrl);

            apiEndpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            int listeningPort = apiEndpoint.getListeningPort();
            listener.setApiPort(listeningPort);
            log("API endpoint started on port " + listeningPort);
        } catch (IOException e) {
            warn("Failed to start API endpoint on port " + apiPort + ": " + e.getMessage());
        }
    }

    /**
     * Logs a startup summary showing the FlowTree server port, HTTP API port,
     * connected agent count, and per-workstream channel/branch information.
     * Called after all services have been started successfully.
     */
    private void printStartupSummary() {
        log("===========================================");
        if (flowtreeServer != null) {
            log("FlowTree server: port " + flowtreePort + " (agents connect here)");
            log("Connected agents: " + flowtreeServer.getNodeGroup().getServers().length);
        }
        if (apiEndpoint != null) {
            log("API endpoint: http://localhost:" + apiEndpoint.getListeningPort());
        }
        log("Registered workstreams: " + listener.getWorkstreams().size());
        for (SlackWorkstream ws : listener.getWorkstreams().values()) {
            log("  - " + ws.getChannelName() + " (" + ws.getChannelId() + ")");
            if (ws.getDefaultBranch() != null) {
                log("    Branch: " + ws.getDefaultBranch());
            }
        }
        log("Ready to receive messages");
        log("===========================================");
    }

    /**
     * Stops the controller.
     */
    public void stop() throws Exception {
        running.set(false);

        // Stop centralized MCP server processes
        for (Process p : mcpProcesses) {
            if (p.isAlive()) {
                p.destroy();
                log("Stopped MCP server process (PID " + p.pid() + ")");
            }
        }
        mcpProcesses.clear();

        if (flowtreeServer != null) {
            flowtreeServer.stop();
            flowtreeServer = null;
        }

        if (apiEndpoint != null) {
            apiEndpoint.stop();
            apiEndpoint = null;
        }

        if (statsStore != null) {
            statsStore.close();
            statsStore = null;
        }

        if (socketModeApp != null) {
            socketModeApp.stop();
            socketModeApp = null;
        }

        if (app != null) {
            app.stop();
            app = null;
        }

        log("Stopped");
    }

    /**
     * Returns whether the controller is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Simulates a message event (for testing without Slack connection).
     *
     * @param channelId the channel ID
     * @param text      the message text
     */
    public void simulateMessage(String channelId, String text) {
        listener.handleMessage(channelId, "U_SIMULATED", text, null, null);
    }

    /**
     * Sets a simulator callback for testing without real Slack connection.
     *
     * @param simulator callback receiving (channelId, message) for outgoing messages
     */
    public void setEventSimulator(BiConsumer<String, String> simulator) {
        this.eventSimulator = simulator;
        notifier.setMessageCallback(json -> {
            String channel = extractJsonField(json, "channel");
            String text = extractJsonField(json, "text");
            simulator.accept(channel, text);
        });
    }

    /**
     * Returns whether the controller is in simulation mode.
     */
    public boolean isSimulationMode() {
        return simulationMode;
    }

    /**
     * Extracts the string value of a named field from a minimal JSON document.
     *
     * <p>This is a lightweight alternative to full JSON parsing used only
     * to pull channel and text fields out of notification payloads in the
     * test simulator callback. It does not handle escaped characters,
     * nested objects, or arrays.</p>
     *
     * @param json  the JSON string to search
     * @param field the field name to look up
     * @return the string value, or {@code null} if the field is absent
     */
    private static String extractJsonField(String json, String field) {
        if (json == null) return null;

        int fieldStart = json.indexOf("\"" + field + "\"");
        if (fieldStart < 0) return null;

        int colonPos = json.indexOf(":", fieldStart);
        if (colonPos < 0) return null;

        int valueStart = json.indexOf("\"", colonPos) + 1;
        if (valueStart <= 0) return null;

        int valueEnd = json.indexOf("\"", valueStart);
        if (valueEnd < 0) return null;

        return json.substring(valueStart, valueEnd);
    }

    // Command-line interface

    /**
     * Main entry point for running the FlowTree controller.
     *
     * <p>The controller starts a FlowTree {@link Server} that listens for
     * inbound agent connections. Agents connect to this server by setting
     * {@code FLOWTREE_ROOT_HOST} and {@code FLOWTREE_ROOT_PORT}.</p>
     *
     * <p>Environment variables:</p>
     * <ul>
     *   <li>SLACK_BOT_TOKEN - Required for Slack integration</li>
     *   <li>SLACK_APP_TOKEN - Required for Socket Mode</li>
     *   <li>FLOWTREE_PORT - FlowTree listening port (default: 7766)</li>
     * </ul>
     *
     * <p>Arguments:</p>
     * <ul>
     *   <li>--tokens &lt;file&gt; - JSON file containing botToken and appToken</li>
     *   <li>--config &lt;file&gt; - YAML configuration file</li>
     *   <li>--channel &lt;id&gt; - Single channel to monitor</li>
     *   <li>--branch &lt;name&gt; - Default branch</li>
     *   <li>--flowtree-port &lt;port&gt; - FlowTree listening port</li>
     * </ul>
     */
    public static void main(String[] args) throws Exception {
        String configFile = null;
        String tokensFile = null;
        String channelId = System.getenv("SLACK_CHANNEL_ID");
        String channelName = System.getenv("SLACK_CHANNEL_NAME");
        String defaultBranch = System.getenv("GIT_DEFAULT_BRANCH");
        int apiPort = FlowTreeApiEndpoint.DEFAULT_PORT;
        int flowtreePort = Integer.parseInt(
                System.getenv().getOrDefault("FLOWTREE_PORT",
                        String.valueOf(Server.defaultPort)));

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                case "-c":
                    configFile = args[++i];
                    break;
                case "--tokens":
                case "-t":
                    tokensFile = args[++i];
                    break;
                case "--channel":
                    channelId = args[++i];
                    break;
                case "--channel-name":
                    channelName = args[++i];
                    break;
                case "--branch":
                    defaultBranch = args[++i];
                    break;
                case "--api-port":
                    apiPort = Integer.parseInt(args[++i]);
                    break;
                case "--flowtree-port":
                    flowtreePort = Integer.parseInt(args[++i]);
                    break;
                case "--log-file":
                    System.setProperty("flowtree.log.file", args[++i]);
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
            }
        }

        // Resolve tokens
        File tokensPath = tokensFile != null ? new File(tokensFile) : null;
        SlackTokens tokens = SlackTokens.resolve(tokensPath);

        // Create controller
        FlowTreeController controller = new FlowTreeController(tokens);
        controller.setApiPort(apiPort);
        controller.setFlowtreePort(flowtreePort);

        // Load configuration
        if (configFile != null) {
            controller.loadConfig(new File(configFile));
        } else if (channelId != null && !channelId.isEmpty()) {
            // Single workstream from environment/args
            SlackWorkstream workstream = new SlackWorkstream(
                channelId,
                channelName != null ? channelName : channelId
            );
            if (defaultBranch != null) {
                workstream.setDefaultBranch(defaultBranch);
            }
            controller.registerWorkstream(workstream);
        } else {
            Console.root().warn("Error: No workstream configuration provided");
            Console.root().warn("Use --config <file> or --channel <id>");
            printUsage();
            System.exit(1);
        }

        // Start controller
        controller.start();

        // Keep main thread alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                controller.stop();
            } catch (Exception e) {
                Console.root().warn(e.getMessage());
            }
        }));

        Thread.currentThread().join();
    }

    /**
     * Prints CLI usage information to standard output and returns.
     *
     * <p>Called when {@code --help} or {@code -h} is passed on the command line,
     * or when no workstream configuration is found. Lists all supported flags,
     * environment variables, token resolution order, and example invocations.</p>
     */
    private static void printUsage() {
        System.out.println("Usage: FlowTreeController [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --tokens, -t <file>    JSON file with botToken/appToken");
        System.out.println("  --config, -c <file>    YAML configuration file");
        System.out.println("  --channel <id>         Slack channel ID to monitor");
        System.out.println("  --channel-name <name>  Human-readable channel name");
        System.out.println("  --branch <name>        Default git branch for commits");
        System.out.println("  --api-port <port>      Port for the HTTP API endpoint (default: 7780)");
        System.out.println("  --flowtree-port <port> Port for the FlowTree server (default: 7766)");
        System.out.println("  --log-file <path>      Log file path (default: flowtree-controller.log)");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Agents connect TO this controller on the FlowTree port.");
        System.out.println("Set FLOWTREE_ROOT_HOST and FLOWTREE_ROOT_PORT on each agent.");
        System.out.println();
        System.out.println("Token resolution (first match wins):");
        System.out.println("  1. --tokens <file>           Explicit token file");
        System.out.println("  2. ./slack-tokens.json       Convention file in working directory");
        System.out.println("  3. SLACK_BOT_TOKEN / SLACK_APP_TOKEN environment variables");
        System.out.println();
        System.out.println("Token file format (JSON):");
        System.out.println("  { \"botToken\": \"xoxb-...\", \"appToken\": \"xapp-...\" }");
        System.out.println();
        System.out.println("Environment variables:");
        System.out.println("  SLACK_BOT_TOKEN        Bot User OAuth Token (xoxb-...)");
        System.out.println("  SLACK_APP_TOKEN        App-level token for Socket Mode (xapp-...)");
        System.out.println("  SLACK_CHANNEL_ID       Default channel to monitor");
        System.out.println("  FLOWTREE_PORT          FlowTree listening port (default: 7766)");
        System.out.println("  GIT_DEFAULT_BRANCH     Default git branch for commits");
        System.out.println();
        System.out.println("Example with token file:");
        System.out.println("  java -cp flowtree.jar io.flowtree.slack.FlowTreeController \\");
        System.out.println("      --tokens slack-tokens.json --config workstreams.yaml");
        System.out.println();
        System.out.println("Example with environment variables:");
        System.out.println("  export SLACK_BOT_TOKEN=xoxb-...");
        System.out.println("  export SLACK_APP_TOKEN=xapp-...");
        System.out.println("  java -cp flowtree.jar io.flowtree.slack.FlowTreeController \\");
        System.out.println("      --config workstreams.yaml");
    }
}
