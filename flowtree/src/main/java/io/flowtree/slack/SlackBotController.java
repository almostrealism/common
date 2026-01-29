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

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.auth.AuthTestResponse;
import com.slack.api.model.event.AppMentionEvent;
import com.slack.api.model.event.MessageEvent;
import io.flowtree.jobs.JobCompletionEvent;
import io.flowtree.jobs.JobCompletionListener;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Main controller for the Slack bot integration using Socket Mode.
 *
 * <p>This class manages the Slack bot lifecycle using the Bolt SDK's Socket Mode,
 * which allows real-time event handling without requiring a public HTTP endpoint.</p>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN} - Bot User OAuth Token (xoxb-...)</li>
 *   <li>{@code SLACK_APP_TOKEN} - App-level token for Socket Mode (xapp-...)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SlackBotController controller = new SlackBotController();
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
 * // Start the bot
 * controller.start();
 * }</pre>
 *
 * @author Michael Murray
 * @see SlackListener
 * @see SlackNotifier
 * @see SlackWorkstream
 */
public class SlackBotController implements JobCompletionListener {

    private final String botToken;
    private final String appToken;
    private final SlackNotifier notifier;
    private final SlackListener listener;

    private final AtomicBoolean running;
    private App app;
    private SocketModeApp socketModeApp;
    private String botUserId;

    // For testing/simulation
    private BiConsumer<String, String> eventSimulator;
    private boolean simulationMode = false;

    /**
     * Creates a new controller using environment variables for configuration.
     */
    public SlackBotController() {
        this(
            System.getenv("SLACK_BOT_TOKEN"),
            System.getenv("SLACK_APP_TOKEN")
        );
    }

    /**
     * Creates a new controller with explicit tokens.
     *
     * @param botToken the Slack Bot User OAuth Token
     * @param appToken the Slack App-level token (for Socket Mode)
     */
    public SlackBotController(String botToken, String appToken) {
        this.botToken = botToken;
        this.appToken = appToken;
        this.notifier = new SlackNotifier(botToken);
        this.listener = new SlackListener(notifier);
        this.running = new AtomicBoolean(false);

        // Wire up completion events
        listener.setCompletionListener(this);
    }

    /**
     * Loads workstream configuration from a YAML file.
     *
     * @param configFile the YAML configuration file
     * @throws IOException if the file cannot be read or parsed
     */
    public void loadConfig(File configFile) throws IOException {
        WorkstreamConfig config = WorkstreamConfig.loadFromYaml(configFile);
        for (SlackWorkstream workstream : config.toWorkstreams()) {
            registerWorkstream(workstream);
        }
        System.out.println("[SlackBotController] Loaded " + config.getWorkstreams().size() +
                          " workstream(s) from " + configFile.getName());
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
     * Registers a workstream with the controller.
     *
     * @param workstream the workstream configuration
     * @throws IOException if agent connection fails
     */
    public void registerWorkstream(SlackWorkstream workstream) throws IOException {
        listener.registerWorkstream(workstream);
        System.out.println("[SlackBotController] Registered workstream: " + workstream.getChannelName());
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
     * Starts the bot controller with Socket Mode.
     *
     * @throws Exception if startup fails
     */
    public void start() throws Exception {
        if (running.getAndSet(true)) {
            System.out.println("[SlackBotController] Already running");
            return;
        }

        System.out.println("===========================================");
        System.out.println("  Slack Bot Controller - Flowtree Agent");
        System.out.println("===========================================");

        if (botToken == null || botToken.isEmpty() || appToken == null || appToken.isEmpty()) {
            System.out.println("WARNING: Missing SLACK_BOT_TOKEN or SLACK_APP_TOKEN");
            System.out.println("         Running in simulation mode");
            simulationMode = true;
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
                System.out.println("Bot User ID: " + botUserId);
                System.out.println("Bot Name: " + authTest.getUser());
                System.out.println("Team: " + authTest.getTeam());
            } else {
                System.err.println("Auth test failed: " + authTest.getError());
            }
        } catch (SlackApiException e) {
            System.err.println("Failed to verify bot token: " + e.getMessage());
        }

        // Start Socket Mode
        socketModeApp = new SocketModeApp(appToken, app);
        socketModeApp.startAsync();

        System.out.println("Socket Mode connection established");
        printStartupSummary();
    }

    /**
     * Registers Slack event handlers with the Bolt app.
     */
    private void registerEventHandlers() {
        // Handle app mentions (@bot message)
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            AppMentionEvent event = payload.getEvent();
            String channelId = event.getChannel();
            String userId = event.getUser();
            String text = event.getText();
            String threadTs = event.getThreadTs();

            System.out.println("[SlackBotController] App mention in " + channelId + ": " + text);

            // Skip bot's own messages
            if (userId != null && userId.equals(botUserId)) {
                return ctx.ack();
            }

            listener.handleMessage(channelId, userId, text, threadTs);
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
            String threadTs = event.getThreadTs();

            // Skip bot's own messages
            if (userId != null && userId.equals(botUserId)) {
                return ctx.ack();
            }

            System.out.println("[SlackBotController] DM from " + userId + ": " + text);
            listener.handleMessage(channelId, userId, text, threadTs);
            return ctx.ack();
        });
    }

    private void printStartupSummary() {
        System.out.println("===========================================");
        System.out.println("Registered workstreams: " + listener.getWorkstreams().size());
        for (SlackWorkstream ws : listener.getWorkstreams().values()) {
            System.out.println("  - " + ws.getChannelName() + " (" + ws.getChannelId() + ")");
            System.out.println("    Agents: " + ws.getAgents().size());
            if (ws.getDefaultBranch() != null) {
                System.out.println("    Branch: " + ws.getDefaultBranch());
            }
        }
        System.out.println("Ready to receive messages");
        System.out.println("===========================================");
    }

    /**
     * Stops the bot controller.
     */
    public void stop() throws Exception {
        running.set(false);

        if (socketModeApp != null) {
            socketModeApp.stop();
            socketModeApp = null;
        }

        if (app != null) {
            app.stop();
            app = null;
        }

        System.out.println("[SlackBotController] Stopped");
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
        listener.handleMessage(channelId, "U_SIMULATED", text, null);
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

    // JobCompletionListener implementation

    @Override
    public void onJobStarted(JobCompletionEvent event) {
        // Already handled by SlackListener -> SlackNotifier chain
    }

    @Override
    public void onJobCompleted(JobCompletionEvent event) {
        notifier.onJobCompleted(event);
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
     * Main entry point for running the Slack bot controller.
     *
     * <p>Environment variables:</p>
     * <ul>
     *   <li>SLACK_BOT_TOKEN - Required</li>
     *   <li>SLACK_APP_TOKEN - Required for Socket Mode</li>
     * </ul>
     *
     * <p>Arguments:</p>
     * <ul>
     *   <li>--config &lt;file&gt; - YAML configuration file</li>
     *   <li>--channel &lt;id&gt; - Single channel to monitor</li>
     *   <li>--agent &lt;host:port&gt; - Agent endpoint</li>
     *   <li>--branch &lt;name&gt; - Default branch</li>
     * </ul>
     */
    public static void main(String[] args) throws Exception {
        String configFile = null;
        String channelId = System.getenv("SLACK_CHANNEL_ID");
        String channelName = System.getenv("SLACK_CHANNEL_NAME");
        String agentHost = System.getenv().getOrDefault("FLOWTREE_AGENT_HOST", "localhost");
        String agentPort = System.getenv().getOrDefault("FLOWTREE_AGENT_PORT", "7766");
        String defaultBranch = System.getenv("GIT_DEFAULT_BRANCH");

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                case "-c":
                    configFile = args[++i];
                    break;
                case "--channel":
                    channelId = args[++i];
                    break;
                case "--channel-name":
                    channelName = args[++i];
                    break;
                case "--agent":
                    String[] parts = args[++i].split(":");
                    agentHost = parts[0];
                    if (parts.length > 1) {
                        agentPort = parts[1];
                    }
                    break;
                case "--branch":
                    defaultBranch = args[++i];
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
            }
        }

        // Create controller
        SlackBotController controller = new SlackBotController();

        // Load configuration
        if (configFile != null) {
            controller.loadConfig(new File(configFile));
        } else if (channelId != null && !channelId.isEmpty()) {
            // Single workstream from environment/args
            SlackWorkstream workstream = new SlackWorkstream(
                channelId,
                channelName != null ? channelName : channelId
            );
            workstream.addAgent(agentHost, Integer.parseInt(agentPort));
            if (defaultBranch != null) {
                workstream.setDefaultBranch(defaultBranch);
            }
            controller.registerWorkstream(workstream);
        } else {
            System.err.println("Error: No workstream configuration provided");
            System.err.println("       Use --config <file> or --channel <id>");
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
                e.printStackTrace();
            }
        }));

        Thread.currentThread().join();
    }

    private static void printUsage() {
        System.out.println("Usage: SlackBotController [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config, -c <file>    YAML configuration file");
        System.out.println("  --channel <id>         Slack channel ID to monitor");
        System.out.println("  --channel-name <name>  Human-readable channel name");
        System.out.println("  --agent <host:port>    Flowtree agent endpoint");
        System.out.println("  --branch <name>        Default git branch for commits");
        System.out.println("  --help, -h             Show this help");
        System.out.println();
        System.out.println("Environment variables:");
        System.out.println("  SLACK_BOT_TOKEN        Bot User OAuth Token (xoxb-...)");
        System.out.println("  SLACK_APP_TOKEN        App-level token for Socket Mode (xapp-...)");
        System.out.println("  SLACK_CHANNEL_ID       Default channel to monitor");
        System.out.println("  FLOWTREE_AGENT_HOST    Agent host (default: localhost)");
        System.out.println("  FLOWTREE_AGENT_PORT    Agent port (default: 7766)");
        System.out.println("  GIT_DEFAULT_BRANCH     Default branch for commits");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  export SLACK_BOT_TOKEN=xoxb-...");
        System.out.println("  export SLACK_APP_TOKEN=xapp-...");
        System.out.println("  java -cp flowtree.jar io.flowtree.slack.SlackBotController \\");
        System.out.println("      --config workstreams.yaml");
    }
}
