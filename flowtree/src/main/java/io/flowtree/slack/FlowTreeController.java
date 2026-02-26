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
import io.flowtree.jobs.McpToolDiscovery;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;
import org.almostrealism.util.SignalWireDeliveryProvider;

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

    private final String botToken;
    private final String appToken;
    private final SlackNotifier notifier;
    private final SlackListener listener;

    private final AtomicBoolean running;
    private App app;
    private SocketModeApp socketModeApp;
    private String botUserId;

    private Server flowtreeServer;
    private int flowtreePort = Server.defaultPort;

    private File configFile;
    private WorkstreamConfig loadedConfig;

    private FlowTreeApiEndpoint apiEndpoint;
    private int apiPort = FlowTreeApiEndpoint.DEFAULT_PORT;
    private JobStatsStore statsStore;

    private List<Process> mcpProcesses = new ArrayList<>();

    // For testing/simulation
    private BiConsumer<String, String> eventSimulator;
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

        // Pass config and file reference to listener for /flowtree setup persistence
        listener.setWorkstreamConfig(config, configFile);

        // Start centralized MCP servers if configured
        startCentralizedMcpServers(config, configFile.getParentFile());
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
        // Set nodes.initial=0 so the controller never processes jobs locally --
        // all jobs are forwarded to connected agents.
        Properties flowtreeProps = new Properties();
        flowtreeProps.setProperty("server.port", String.valueOf(flowtreePort));
        flowtreeProps.setProperty("nodes.initial", "0");
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
            registerPushedTools();
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
        registerPushedTools();
        printStartupSummary();
    }

    /**
     * Registers Slack event handlers with the Bolt app, including
     * the {@code /flowtree} slash command.
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
     * @param config    the parsed workstream configuration
     * @param configDir the directory containing the YAML config file, used to
     *                  resolve relative source paths (may be null)
     */
    private void startCentralizedMcpServers(WorkstreamConfig config, File configDir) {
        Map<String, WorkstreamConfig.McpServerEntry> mcpServers = config.getMcpServers();
        if (mcpServers == null || mcpServers.isEmpty()) return;

        log("Starting " + mcpServers.size() + " centralized MCP server(s)...");

        // Build the centralized config JSON as we start each server
        StringBuilder configJson = new StringBuilder("{");
        boolean first = true;

        for (Map.Entry<String, WorkstreamConfig.McpServerEntry> entry : mcpServers.entrySet()) {
            String serverName = entry.getKey();
            WorkstreamConfig.McpServerEntry serverEntry = entry.getValue();

            // Resolve source path relative to config file directory
            Path sourcePath;
            if (configDir != null) {
                sourcePath = configDir.toPath().resolve(serverEntry.getSource());
            } else {
                sourcePath = Path.of(serverEntry.getSource());
            }

            // Discover tool names from the source file
            List<String> tools = McpToolDiscovery.discoverToolNames(sourcePath);
            if (tools.isEmpty()) {
                warn("No tools discovered from " + sourcePath + " for server " + serverName);
            }

            // Start the Python process
            try {
                ProcessBuilder pb = new ProcessBuilder("python3", sourcePath.toString());
                pb.environment().put("MCP_TRANSPORT", "http");
                pb.environment().put("MCP_PORT", String.valueOf(serverEntry.getPort()));

                // Forward AR_* environment variables to the subprocess so that
                // server-specific configuration (e.g., AR_CONSULTANT_LLAMACPP_URL)
                // set on the controller is visible to the Python process.
                for (Map.Entry<String, String> env : System.getenv().entrySet()) {
                    if (env.getKey().startsWith("AR_")) {
                        pb.environment().put(env.getKey(), env.getValue());
                    }
                }

                pb.inheritIO();

                Process process = pb.start();
                mcpProcesses.add(process);
                log("Started " + serverName + " on port " + serverEntry.getPort()
                    + " (PID " + process.pid() + ", " + tools.size() + " tools)");
            } catch (IOException e) {
                warn("Failed to start " + serverName + ": " + e.getMessage());
                continue;
            }

            // Build this server's entry in the config JSON
            String url = "http://0.0.0.0:" + serverEntry.getPort() + "/mcp";

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

        listener.setCentralizedMcpConfig(centralizedConfig);
        log("Centralized MCP config: " + centralizedConfig);
    }

    /**
     * Registers pushed MCP tool files with the API endpoint and builds
     * the pushed tools config JSON for passing to agents.
     *
     * <p>Each tool in the YAML {@code pushedTools} section is registered
     * with the API endpoint for serving via {@code GET /api/tools/{name}}.
     * Tool names are discovered from the Python source files so they can
     * be included in the agent's allowed tools list.</p>
     *
     * <p>Must be called after {@link #startApiEndpoint()} since it requires
     * the API endpoint reference and listening port.</p>
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

            // Resolve source path relative to config file directory
            Path sourcePath;
            if (configDir != null) {
                sourcePath = configDir.toPath().resolve(toolEntry.getSource());
            } else {
                sourcePath = Path.of(toolEntry.getSource());
            }

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

        listener.setPushedToolsConfig(pushedConfig);
        log("Pushed tools config: " + pushedConfig);
    }

    /**
     * Starts the HTTP API endpoint for receiving messages from MCP tools
     * and for programmatic job submission.
     */
    private void startApiEndpoint() {
        // Initialize stats store
        String dataDir = System.getProperty("user.home") + "/.flowtree";
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

            apiEndpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            int listeningPort = apiEndpoint.getListeningPort();
            listener.setApiPort(listeningPort);
            log("API endpoint started on port " + listeningPort);
        } catch (IOException e) {
            warn("Failed to start API endpoint on port " + apiPort + ": " + e.getMessage());
        }
    }

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
     * Simple JSON field extraction (for basic parsing).
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
