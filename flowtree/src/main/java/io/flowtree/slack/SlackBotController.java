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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main controller for the Slack bot integration.
 *
 * <p>This class manages the Slack bot lifecycle, handles incoming events,
 * and coordinates between {@link SlackListener} and {@link SlackNotifier}.</p>
 *
 * <p>The controller supports two modes of operation:</p>
 * <ul>
 *   <li><b>Socket Mode</b> (recommended) - Uses WebSocket for real-time events,
 *       no public endpoint needed. Requires SLACK_APP_TOKEN.</li>
 *   <li><b>HTTP Mode</b> - Receives events via HTTP webhook. Requires a public
 *       endpoint and SLACK_SIGNING_SECRET for verification.</li>
 * </ul>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN} - Bot User OAuth Token (xoxb-...)</li>
 *   <li>{@code SLACK_APP_TOKEN} - App-level token for Socket Mode (xapp-...)</li>
 *   <li>{@code SLACK_SIGNING_SECRET} - For HTTP mode request verification</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SlackBotController controller = new SlackBotController();
 *
 * // Configure a workstream
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

    private static final String SLACK_API_BASE = "https://slack.com/api";

    private final String botToken;
    private final String appToken;
    private final SlackNotifier notifier;
    private final SlackListener listener;

    private final AtomicBoolean running;
    private Timer pollingTimer;
    private String botUserId;

    // For testing/simulation
    private BiConsumer<String, String> eventSimulator;

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
     * Starts the bot controller.
     *
     * <p>If an app token is provided, the controller will attempt to use
     * Socket Mode. Otherwise, it will log a warning about needing HTTP
     * mode setup.</p>
     *
     * @throws IOException if startup fails
     */
    public void start() throws IOException {
        if (running.getAndSet(true)) {
            System.out.println("[SlackBotController] Already running");
            return;
        }

        System.out.println("===========================================");
        System.out.println("  Slack Bot Controller - Flowtree Agent");
        System.out.println("===========================================");

        // Verify bot token and get bot user ID
        if (botToken != null && !botToken.isEmpty()) {
            botUserId = fetchBotUserId();
            System.out.println("Bot User ID: " + botUserId);
        } else {
            System.out.println("WARNING: No SLACK_BOT_TOKEN configured");
            System.out.println("         Running in simulation mode");
        }

        // Start event handling
        if (appToken != null && !appToken.isEmpty()) {
            startSocketMode();
        } else {
            System.out.println("WARNING: No SLACK_APP_TOKEN configured");
            System.out.println("         Socket Mode unavailable");
            System.out.println("         Configure HTTP webhook endpoint or use simulation mode");
        }

        System.out.println("===========================================");
        System.out.println("Registered workstreams: " + listener.getWorkstreams().size());
        System.out.println("Ready to receive messages");
        System.out.println("===========================================");
    }

    /**
     * Stops the bot controller.
     */
    public void stop() {
        running.set(false);

        if (pollingTimer != null) {
            pollingTimer.cancel();
            pollingTimer = null;
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
     * Handles an incoming Slack event.
     *
     * <p>This method parses the event JSON and routes it to the appropriate
     * handler. It supports app_mention and message events.</p>
     *
     * @param eventJson the raw event JSON from Slack
     */
    public void handleEvent(String eventJson) {
        // Parse event type
        String eventType = extractJsonField(eventJson, "type");

        if ("app_mention".equals(eventType) || "message".equals(eventType)) {
            String channelId = extractJsonField(eventJson, "channel");
            String userId = extractJsonField(eventJson, "user");
            String text = extractJsonField(eventJson, "text");
            String threadTs = extractJsonField(eventJson, "thread_ts");

            // Skip bot's own messages
            if (userId != null && userId.equals(botUserId)) {
                return;
            }

            listener.handleMessage(channelId, userId, text, threadTs);
        }
    }

    /**
     * Simulates a message event (for testing).
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

    // JobCompletionListener implementation

    @Override
    public void onJobStarted(JobCompletionEvent event) {
        // Already handled by SlackListener -> SlackNotifier chain
    }

    @Override
    public void onJobCompleted(JobCompletionEvent event) {
        notifier.onJobCompleted(event);
    }

    // Socket Mode implementation (simplified)

    private void startSocketMode() {
        System.out.println("[SlackBotController] Socket Mode support requires Slack SDK");
        System.out.println("[SlackBotController] For full Socket Mode, add slack-api-client dependency");
        System.out.println("[SlackBotController] Starting basic polling mode as fallback...");

        // Simple polling fallback - in production, use the Slack SDK's SocketModeClient
        pollingTimer = new Timer("slack-polling", true);
        pollingTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!running.get()) {
                    cancel();
                }
                // In a real implementation, this would use Socket Mode or RTM API
                // For now, this is a placeholder that keeps the bot alive
            }
        }, 0, 30000); // Poll every 30 seconds (placeholder)
    }

    private String fetchBotUserId() throws IOException {
        String response = callSlackApi("auth.test", "{}");
        return extractJsonField(response, "user_id");
    }

    private String callSlackApi(String method, String payload) throws IOException {
        URL url = new URL(SLACK_API_BASE + "/" + method);
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

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            return response.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Simple JSON field extraction (for basic parsing without dependencies).
     */
    private static String extractJsonField(String json, String field) {
        if (json == null) return null;

        // Look for "field":"value" or "field": "value"
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    // Command-line interface

    /**
     * Main entry point for running the Slack bot controller.
     *
     * <p>Environment variables:</p>
     * <ul>
     *   <li>SLACK_BOT_TOKEN - Required</li>
     *   <li>SLACK_APP_TOKEN - Required for Socket Mode</li>
     *   <li>SLACK_CHANNEL_ID - Channel to monitor</li>
     *   <li>FLOWTREE_AGENT_HOST - Agent host (default: localhost)</li>
     *   <li>FLOWTREE_AGENT_PORT - Agent port (default: 7766)</li>
     *   <li>GIT_DEFAULT_BRANCH - Default branch for commits</li>
     * </ul>
     */
    public static void main(String[] args) throws IOException {
        String channelId = System.getenv("SLACK_CHANNEL_ID");
        String channelName = System.getenv("SLACK_CHANNEL_NAME");
        String agentHost = System.getenv().getOrDefault("FLOWTREE_AGENT_HOST", "localhost");
        String agentPort = System.getenv().getOrDefault("FLOWTREE_AGENT_PORT", "7766");
        String defaultBranch = System.getenv("GIT_DEFAULT_BRANCH");

        if (channelId == null || channelId.isEmpty()) {
            System.err.println("Error: SLACK_CHANNEL_ID environment variable required");
            System.err.println();
            System.err.println("Usage:");
            System.err.println("  export SLACK_BOT_TOKEN=xoxb-...");
            System.err.println("  export SLACK_APP_TOKEN=xapp-...");
            System.err.println("  export SLACK_CHANNEL_ID=C0123456789");
            System.err.println("  export FLOWTREE_AGENT_HOST=localhost");
            System.err.println("  export FLOWTREE_AGENT_PORT=7766");
            System.err.println("  java -cp flowtree.jar io.flowtree.slack.SlackBotController");
            System.exit(1);
        }

        // Create controller
        SlackBotController controller = new SlackBotController();

        // Configure workstream
        SlackWorkstream workstream = new SlackWorkstream(channelId, channelName != null ? channelName : channelId);
        workstream.addAgent(agentHost, Integer.parseInt(agentPort));
        if (defaultBranch != null) {
            workstream.setDefaultBranch(defaultBranch);
        }

        controller.registerWorkstream(workstream);
        controller.start();

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            controller.stop();
        }
    }
}
