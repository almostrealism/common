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

package io.flowtree;

import io.flowtree.jobs.ClaudeCodeJob;
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for submitting Claude Code jobs to remote Flowtree agents.
 *
 * <p>This client manages connections to one or more remote Flowtree {@link Server}
 * instances and submits jobs to be executed by Claude Code in those environments.
 * Connections are established lazily on the first submission to each agent and
 * automatically reconnected if the agent has restarted.</p>
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
 * @see Server
 * @see io.flowtree.jobs.ClaudeCodeJob
 */
public class ClaudeCodeClient implements ConsoleFeatures {

	/** Pool of remote agent connections available for job submission. */
	private final List<AgentConnection> agents = new ArrayList<>();
	/** Maps agent index to the {@link NodeProxy} used to relay jobs to that agent. */
	private final ConcurrentHashMap<Integer, NodeProxy> proxyMap = new ConcurrentHashMap<>();
	/** Local FlowTree server used to relay jobs to remote agents. */
	private Server server;
	/** Round-robin index of the next agent to receive a submitted job. */
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
	 * Starts the client without establishing any connections.
	 * Connections are created lazily when the first job is submitted
	 * to each agent.
	 *
	 * @throws IOException if the local server cannot be initialized
	 */
	public void start() throws IOException {
		if (agents.isEmpty()) {
			throw new IllegalStateException("No agents configured. Call addAgent() first.");
		}

		Properties p = new Properties();
		p.setProperty("server.port", "-1"); // Don't listen
		p.setProperty("nodes.initial", "0"); // No local nodes
		p.setProperty("servers.total", "0"); // No eager connections

		server = new Server(p);

		log("Claude Code Client started (lazy connections to " + agents.size() + " agent(s))");
	}

	/**
	 * Ensures that a live connection exists for the given agent index.
	 * If no connection exists or the existing connection is dead, a new
	 * connection is established.
	 *
	 * @param agentIndex the index of the agent to connect to
	 * @return the live {@link NodeProxy}, or null if connection failed
	 */
	private synchronized NodeProxy ensureConnected(int agentIndex) {
		NodeProxy existing = proxyMap.get(agentIndex);
		if (existing != null && existing.isConnected()) {
			return existing;
		}

		// Remove stale proxy if present
		if (existing != null) {
			removeProxy(agentIndex);
		}

		AgentConnection agent = agents.get(agentIndex);
		log("Connecting to agent " + agentIndex + " at " + agent.host + ":" + agent.port);

		try {
			server.open(agent.host, agent.port);

			NodeProxy[] servers = server.getNodeGroup().getServers();
			NodeProxy proxy = servers[servers.length - 1];
			proxyMap.put(agentIndex, proxy);

			log("Connected to agent " + agentIndex + " at " + agent.host + ":" + agent.port);
			return proxy;
		} catch (IOException e) {
			warn("Failed to connect to agent " + agentIndex +
					" at " + agent.host + ":" + agent.port + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Removes a dead proxy from both the proxy map and the underlying
	 * {@link io.flowtree.node.NodeGroup}.
	 *
	 * @param agentIndex the index of the agent whose proxy should be removed
	 */
	private synchronized void removeProxy(int agentIndex) {
		NodeProxy proxy = proxyMap.remove(agentIndex);
		if (proxy != null) {
			server.getNodeGroup().removeServer(proxy);
		}
	}

	/**
	 * Attempts to send a job factory to the specified agent. If the first
	 * attempt fails due to a dead connection, the proxy is replaced and
	 * one retry is attempted.
	 *
	 * @param factory    the job factory to send
	 * @param agentIndex the index of the target agent
	 * @return true if the job was sent successfully, false otherwise
	 */
	private boolean trySendToAgent(ClaudeCodeJob.Factory factory, int agentIndex) {
		for (int attempt = 0; attempt < 2; attempt++) {
			NodeProxy proxy = ensureConnected(agentIndex);
			if (proxy == null) {
				return false;
			}

			try {
				Message m = new Message(Message.Task, -1, proxy);
				m.setString(factory.encode());
				m.send(-1);
				return true;
			} catch (IOException e) {
				warn("Send attempt " + (attempt + 1) + " failed for agent " +
						agentIndex + ": " + e.getMessage());
				removeProxy(agentIndex);
			}
		}

		return false;
	}

	/**
	 * Submits one or more prompts to be executed by available agents.
	 * Prompts are packaged into a single JobFactory and sent to the next agent.
	 *
	 * @param prompts the prompts to execute
	 * @return true if the job was submitted successfully, false if the agent
	 *         could not be reached
	 */
	public boolean submit(String... prompts) {
		ClaudeCodeJob.Factory factory = new ClaudeCodeJob.Factory(prompts);
		return submit(factory);
	}

	/**
	 * Submits a pre-configured job factory to the next available agent.
	 * The connection is established lazily on first use and automatically
	 * reconnected if the agent has restarted.
	 *
	 * @param factory the job factory to submit
	 * @return true if the job was submitted successfully, false if the agent
	 *         could not be reached
	 */
	public boolean submit(ClaudeCodeJob.Factory factory) {
		if (server == null) {
			throw new IllegalStateException("Client not started. Call start() first.");
		}

		int agentIndex = nextAgent % agents.size();
		nextAgent++;

		AgentConnection agent = agents.get(agentIndex);
		log("Submitting " + factory.getPrompts().size() +
				" prompt(s) to " + agent.host + ":" + agent.port);

		return trySendToAgent(factory, agentIndex);
	}

	/**
	 * Submits a job factory to a specific agent by index.
	 * The connection is established lazily on first use and automatically
	 * reconnected if the agent has restarted.
	 *
	 * @param factory    the job factory to submit
	 * @param agentIndex the index of the agent (0-based)
	 * @return true if the job was submitted successfully, false if the agent
	 *         could not be reached
	 */
	public boolean submitTo(ClaudeCodeJob.Factory factory, int agentIndex) {
		if (server == null) {
			throw new IllegalStateException("Client not started. Call start() first.");
		}

		if (agentIndex < 0 || agentIndex >= agents.size()) {
			throw new IllegalArgumentException("Invalid agent index: " + agentIndex);
		}

		AgentConnection agent = agents.get(agentIndex);
		log("Submitting to " + agent.host + ":" + agent.port);

		return trySendToAgent(factory, agentIndex);
	}

	/**
	 * Returns the number of configured agents.
	 */
	public int getAgentCount() {
		return agents.size();
	}

	/**
	 * Holds the network coordinates of a single remote Claude Code agent.
	 */
	private static class AgentConnection {
		/** Hostname or IP address of the remote agent. */
		final String host;
		/** TCP port the remote agent is listening on. */
		final int port;

		/**
		 * Constructs a new {@link AgentConnection}.
		 *
		 * @param host  hostname or IP address of the remote agent
		 * @param port  TCP port the agent is listening on
		 */
		AgentConnection(String host, int port) {
			this.host = host;
			this.port = port;
		}
	}

	/**
	 * Command-line entry point. Parses arguments for host, port(s), prompt
	 * text, allowed tools, max turns, and max budget; creates a
	 * {@link ClaudeCodeClient}; and submits a single
	 * {@link io.flowtree.jobs.ClaudeCodeJob.Factory} to the configured agents.
	 *
	 * @param args  command-line arguments (see {@link #printUsage()} for details)
	 * @throws IOException if a network error occurs while connecting to an agent
	 */
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
			Console.root().warn("Error: --prompt is required");
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
		boolean submitted = client.submit(factory);

		if (submitted) {
			Console.root().println("Job submitted. Use Flowtree monitoring to track progress.");
		} else {
			Console.root().warn("Failed to submit job. Agent may be offline.");
			System.exit(1);
		}
	}

	/**
	 * Prints usage instructions for the command-line interface to
	 * {@link System#out}.
	 */
	private static void printUsage() {
		Console.root().println("Usage: ClaudeCodeClient [options]");
		Console.root().println("");
		Console.root().println("Options:");
		Console.root().println("  --host, -h <host>       Agent hostname (default: localhost)");
		Console.root().println("  --port, -p <port,...>   Agent port(s), comma-separated (default: 7766)");
		Console.root().println("  --prompt <text>         The prompt to execute (required)");
		Console.root().println("  --tools <list>          Allowed tools (default: Read,Edit,Write,Bash,Glob,Grep)");
		Console.root().println("  --max-turns <n>         Maximum agent turns (default: 50)");
		Console.root().println("  --max-budget <usd>      Maximum budget in USD (default: 10.0)");
		Console.root().println("  --help                  Show this help");
		Console.root().println("");
		Console.root().println("Examples:");
		Console.root().println("  # Submit to single agent");
		Console.root().println("  java -cp flowtree.jar io.flowtree.ClaudeCodeClient \\");
		Console.root().println("      --host localhost --port 7766 \\");
		Console.root().println("      --prompt \"Fix the null pointer in UserService\"");
		Console.root().println("");
		Console.root().println("  # Submit to multiple agents");
		Console.root().println("  java -cp flowtree.jar io.flowtree.ClaudeCodeClient \\");
		Console.root().println("      --host localhost --port 7766,7767,7768,7769 \\");
		Console.root().println("      --prompt \"Review error handling across the codebase\"");
	}
}
