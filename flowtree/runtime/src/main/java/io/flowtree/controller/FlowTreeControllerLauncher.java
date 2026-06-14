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

package io.flowtree.controller;

import io.flowtree.Server;
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.slack.SlackTokens;
import io.flowtree.workstream.Workstream;
import org.almostrealism.io.Console;

import java.io.File;
// TODO(review): unused import — remove to prevent checkstyle UnusedImports failure
import java.io.IOException;

/**
 * Command-line entry point for the FlowTree controller.
 *
 * <p>Parses arguments, resolves tokens, and starts a {@link FlowTreeController}.
 * Split from {@code FlowTreeController} to keep the controller class focused on
 * its runtime concern (lifecycle management) while centralising CLI parsing here.</p>
 *
 * <p>The controller starts a FlowTree {@link Server} that listens for inbound
 * agent connections. Agents connect by setting {@code FLOWTREE_ROOT_HOST} and
 * {@code FLOWTREE_ROOT_PORT}.</p>
 *
 * <p>Arguments:</p>
 * <ul>
 *   <li>{@code --tokens/-t <file>} — JSON file containing botToken and appToken</li>
 *   <li>{@code --config/-c <file>} — YAML configuration file</li>
 *   <li>{@code --channel <id>}     — Single channel to monitor</li>
 *   <li>{@code --channel-name <name>} — Human-readable channel name</li>
 *   <li>{@code --branch <name>}    — Default branch</li>
 *   <li>{@code --api-port <port>}  — HTTP API endpoint port</li>
 *   <li>{@code --flowtree-port <port>} — FlowTree agent-connection port</li>
 *   <li>{@code --log-file <path>}  — Log file path</li>
 * </ul>
 *
 * <p>Environment variables:</p>
 * <ul>
 *   <li>{@code SLACK_BOT_TOKEN} — Bot User OAuth Token (xoxb-...)</li>
 *   <li>{@code SLACK_APP_TOKEN} — App-level token for Socket Mode (xapp-...)</li>
 *   <li>{@code SLACK_CHANNEL_ID} — Default channel to monitor</li>
 *   <li>{@code FLOWTREE_PORT}   — FlowTree listening port (default: 7766)</li>
 *   <li>{@code GIT_DEFAULT_BRANCH} — Default git branch for commits</li>
 * </ul>
 *
 * @author Michael Murray
 * @see FlowTreeController
 */
public final class FlowTreeControllerLauncher {

    private FlowTreeControllerLauncher() { }

    /**
     * Main entry point for running the FlowTree controller.
     *
     * @param args command-line arguments
     * @throws Exception if startup fails
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
                default:
                    break;
            }
        }

        File tokensPath = tokensFile != null ? new File(tokensFile) : null;
        SlackTokens tokens = SlackTokens.resolve(tokensPath);

        FlowTreeController controller = new FlowTreeController(tokens);
        controller.setApiPort(apiPort);
        controller.setFlowtreePort(flowtreePort);

        if (configFile != null) {
            controller.loadConfig(new File(configFile));
        } else if (channelId != null && !channelId.isEmpty()) {
            Workstream workstream = new Workstream(
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

        controller.start();

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
     * Prints CLI usage information to standard output.
     */
    static void printUsage() {
        Console.root().println("Usage: FlowTreeController [options]");
        Console.root().println("");
        Console.root().println("Options:");
        Console.root().println("  --tokens, -t <file>    JSON file with botToken/appToken");
        Console.root().println("  --config, -c <file>    YAML configuration file");
        Console.root().println("  --channel <id>         Slack channel ID to monitor");
        Console.root().println("  --channel-name <name>  Human-readable channel name");
        Console.root().println("  --branch <name>        Default git branch for commits");
        Console.root().println("  --api-port <port>      Port for the HTTP API endpoint (default: 7780)");
        Console.root().println("  --flowtree-port <port> Port for the FlowTree server (default: 7766)");
        Console.root().println("  --log-file <path>      Log file path (default: flowtree-controller.log)");
        Console.root().println("  --help, -h             Show this help");
        Console.root().println("");
        Console.root().println("Agents connect TO this controller on the FlowTree port.");
        Console.root().println("Set FLOWTREE_ROOT_HOST and FLOWTREE_ROOT_PORT on each agent.");
        Console.root().println("");
        Console.root().println("Token resolution (first match wins):");
        Console.root().println("  1. --tokens <file>           Explicit token file");
        Console.root().println("  2. ./slack-tokens.json       Convention file in working directory");
        Console.root().println("  3. SLACK_BOT_TOKEN / SLACK_APP_TOKEN environment variables");
        Console.root().println("");
        Console.root().println("Token file format (JSON):");
        Console.root().println("  { \"botToken\": \"xoxb-...\", \"appToken\": \"xapp-...\" }");
        Console.root().println("");
        Console.root().println("Environment variables:");
        Console.root().println("  SLACK_BOT_TOKEN        Bot User OAuth Token (xoxb-...)");
        Console.root().println("  SLACK_APP_TOKEN        App-level token for Socket Mode (xapp-...)");
        Console.root().println("  SLACK_CHANNEL_ID       Default channel to monitor");
        Console.root().println("  FLOWTREE_PORT          FlowTree listening port (default: 7766)");
        Console.root().println("  GIT_DEFAULT_BRANCH     Default git branch for commits");
    }
}
