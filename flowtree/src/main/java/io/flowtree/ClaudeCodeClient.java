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

package io.flowtree;

import io.flowtree.jobs.ClaudeCodeJob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Client for submitting Claude Code jobs to remote Flowtree agents.
 *
 * <p>This client connects to one or more remote {@link ClaudeCodeAgent} instances
 * and submits jobs to be executed by Claude Code in those environments.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Connect to agents
 * ClaudeCodeClient client = new ClaudeCodeClient();
 * client.addAgent("localhost", 7766);  // sandbox-a
 * client.addAgent("localhost", 7767);  // sandbox-b
 * client.start();
 *
 * // Submit a single prompt to next available agent
 * client.submit("Fix the null pointer exception in UserService.java");
 *
 * // Submit multiple prompts (distributed across agents)
 * client.submit(
 *     "Add unit tests for the login flow",
 *     "Improve error handling in the API layer",
 *     "Refactor duplicate code in the validators"
 * );
 *
 * // Submit with custom configuration
 * ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(
 *     "Implement the new caching feature"
 * );
 * factory.setAllowedTools("Read,Edit,Bash,Glob,Grep");
 * factory.setMaxTurns(100);
 * factory.setMaxBudgetUsd(25.0);
 * client.submit(factory);
 * }</pre>
 *
 * <h2>Command Line Usage</h2>
 * <pre>
 * # Submit a prompt to agents
 * java -cp flowtree-*.jar io.flowtree.ClaudeCodeClient \
 *     --host localhost --port 7766 \
 *     --prompt "Fix the bug in auth.py"
 *
 * # Submit to multiple agents
 * java -cp flowtree-*.jar io.flowtree.ClaudeCodeClient \
 *     --host localhost --port 7766,7767,7768,7769 \
 *     --prompt "Review and improve error handling"
 * </pre>
 *
 * @author Michael Murray
 * @see ClaudeCodeAgent
 * @see io.flowtree.jobs.ClaudeCodeJob
 */
public class ClaudeCodeClient {

    private final List<AgentConnection> agents = new ArrayList<>();
    private Server server;
    private int nextAgent = 0;

    /**
     * Creates a new client with no initial connections.
     */
    public ClaudeCodeClient() {
    }

    /**
     * Adds a remote agent to the connection pool.
     *
     * @param host the hostname or IP of the agent
     * @param port the port the agent is listening on
     */
    public void addAgent(String host, int port) {
        agents.add(new AgentConnection(host, port));
    }

    /**
     * Starts the client and establishes connections to all configured agents.
     *
     * @throws IOException if connection fails
     */
    public void start() throws IOException {
        if (agents.isEmpty()) {
            throw new IllegalStateException("No agents configured. Call addAgent() first.");
        }

        Properties p = new Properties();
        p.setProperty("server.port", "-1"); // Don't listen
        p.setProperty("nodes.initial", "0"); // No local nodes
        p.setProperty("servers.total", String.valueOf(agents.size()));

        for (int i = 0; i < agents.size(); i++) {
            AgentConnection agent = agents.get(i);
            p.setProperty("servers." + i + ".host", agent.host);
            p.setProperty("servers." + i + ".port", String.valueOf(agent.port));
        }

        server = new Server(p);

        System.out.println("Claude Code Client started");
        System.out.println("Connected to " + agents.size() + " agent(s)");
    }

    /**
     * Submits one or more prompts to be executed by available agents.
     * Prompts are packaged into a single JobFactory and sent to the next agent.
     *
     * @param prompts the prompts to execute
     */
    public void submit(String... prompts) {
        ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(prompts);
        submit(factory);
    }

    /**
     * Submits a pre-configured job factory to the next available agent.
     *
     * @param factory the job factory to submit
     */
    public void submit(ClaudeCodeJob.Factory factory) {
        if (server == null) {
            throw new IllegalStateException("Client not started. Call start() first.");
        }

        int agentIndex = nextAgent % agents.size();
        nextAgent++;

        AgentConnection agent = agents.get(agentIndex);
        System.out.println("Submitting " + factory.getPrompts().size() +
                          " prompt(s) to " + agent.host + ":" + agent.port);

        server.sendTask(factory, agentIndex);
    }

    /**
     * Submits a job factory to a specific agent by index.
     *
     * @param factory    the job factory to submit
     * @param agentIndex the index of the agent (0-based)
     */
    public void submitTo(ClaudeCodeJob.Factory factory, int agentIndex) {
        if (server == null) {
            throw new IllegalStateException("Client not started. Call start() first.");
        }

        if (agentIndex < 0 || agentIndex >= agents.size()) {
            throw new IllegalArgumentException("Invalid agent index: " + agentIndex);
        }

        AgentConnection agent = agents.get(agentIndex);
        System.out.println("Submitting to " + agent.host + ":" + agent.port);

        server.sendTask(factory, agentIndex);
    }

    /**
     * Returns the number of connected agents.
     */
    public int getAgentCount() {
        return agents.size();
    }

    private static class AgentConnection {
        final String host;
        final int port;

        AgentConnection(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    // Command-line interface
    public static void main(String[] args) throws IOException {
        String host = "localhost";
        String ports = "7766";
        String prompt = null;
        String tools = ClaudeCodeJob.DEFAULT_TOOLS;
        int maxTurns = 50;
        double maxBudget = 10.0;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                case "-h":
                    host = args[++i];
                    break;
                case "--port":
                case "-p":
                    ports = args[++i];
                    break;
                case "--prompt":
                    prompt = args[++i];
                    break;
                case "--tools":
                    tools = args[++i];
                    break;
                case "--max-turns":
                    maxTurns = Integer.parseInt(args[++i]);
                    break;
                case "--max-budget":
                    maxBudget = Double.parseDouble(args[++i]);
                    break;
                case "--help":
                    printUsage();
                    return;
            }
        }

        if (prompt == null) {
            System.err.println("Error: --prompt is required");
            printUsage();
            System.exit(1);
        }

        // Create client and add agents
        ClaudeCodeClient client = new ClaudeCodeClient();
        for (String portStr : ports.split(",")) {
            client.addAgent(host, Integer.parseInt(portStr.trim()));
        }
        client.start();

        // Create and configure job factory
        ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(prompt);
        factory.setAllowedTools(tools);
        factory.setMaxTurns(maxTurns);
        factory.setMaxBudgetUsd(maxBudget);

        // Submit
        client.submit(factory);

        System.out.println("Job submitted. Use Flowtree monitoring to track progress.");
    }

    private static void printUsage() {
        System.out.println("Usage: ClaudeCodeClient [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --host, -h <host>       Agent hostname (default: localhost)");
        System.out.println("  --port, -p <port,...>   Agent port(s), comma-separated (default: 7766)");
        System.out.println("  --prompt <text>         The prompt to execute (required)");
        System.out.println("  --tools <list>          Allowed tools (default: Read,Edit,Write,Bash,Glob,Grep)");
        System.out.println("  --max-turns <n>         Maximum agent turns (default: 50)");
        System.out.println("  --max-budget <usd>      Maximum budget in USD (default: 10.0)");
        System.out.println("  --help                  Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Submit to single agent");
        System.out.println("  java -cp flowtree.jar io.flowtree.ClaudeCodeClient \\");
        System.out.println("      --host localhost --port 7766 \\");
        System.out.println("      --prompt \"Fix the null pointer in UserService\"");
        System.out.println();
        System.out.println("  # Submit to multiple agents");
        System.out.println("  java -cp flowtree.jar io.flowtree.ClaudeCodeClient \\");
        System.out.println("      --host localhost --port 7766,7767,7768,7769 \\");
        System.out.println("      --prompt \"Review error handling across the codebase\"");
    }
}
