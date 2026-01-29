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

import java.io.IOException;
import java.util.Properties;

/**
 * A Flowtree agent configured to receive and execute {@link io.flowtree.jobs.ClaudeCodeJob} instances.
 *
 * <p>This agent runs as a Flowtree Server inside a Docker container (or any environment
 * with Claude Code installed) and listens for incoming job submissions from external clients.</p>
 *
 * <h2>Configuration</h2>
 * <p>The agent reads configuration from environment variables:</p>
 * <ul>
 *   <li>{@code FLOWTREE_PORT} - Port to listen on (default: 7766)</li>
 *   <li>{@code FLOWTREE_NODE_ID} - Human-readable node identifier (default: hostname)</li>
 *   <li>{@code FLOWTREE_ROOT_HOST} - Optional root server to connect to for clustering</li>
 *   <li>{@code FLOWTREE_ROOT_PORT} - Port of root server (default: 7766)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>Inside a Docker container with Claude Code installed:</p>
 * <pre>
 * # Build the flowtree module
 * cd /workspace/project/common
 * mvn package -pl flowtree -DskipTests
 *
 * # Start the agent
 * java -cp flowtree/target/flowtree-*.jar io.flowtree.ClaudeCodeAgent
 * </pre>
 *
 * <p>The agent will start listening for jobs and execute them using the local Claude Code installation.</p>
 *
 * @author Michael Murray
 * @see io.flowtree.jobs.ClaudeCodeJob
 */
public class ClaudeCodeAgent {

    public static final int DEFAULT_PORT = 7766;
    public static final int DEFAULT_MAX_JOBS = 1;

    public static void main(String[] args) throws IOException {
        String port = System.getenv("FLOWTREE_PORT");
        String nodeId = System.getenv("FLOWTREE_NODE_ID");
        String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
        String rootPort = System.getenv("FLOWTREE_ROOT_PORT");

        int serverPort = port != null ? Integer.parseInt(port) : DEFAULT_PORT;

        System.out.println("===========================================");
        System.out.println("  Claude Code Agent - Flowtree Integration");
        System.out.println("===========================================");
        System.out.println("Node ID: " + (nodeId != null ? nodeId : "default"));
        System.out.println("Listening on port: " + serverPort);

        Properties p = new Properties();
        p.setProperty("server.port", String.valueOf(serverPort));
        p.setProperty("nodes.initial", "1");
        p.setProperty("nodes.jobs.max", String.valueOf(DEFAULT_MAX_JOBS));
        p.setProperty("nodes.peers.max", "4");
        p.setProperty("group.msc", "30");
        p.setProperty("network.msg.verbose", "false");

        // Connect to root server if specified (for clustering)
        if (rootHost != null && !rootHost.isEmpty()) {
            int rPort = rootPort != null ? Integer.parseInt(rootPort) : DEFAULT_PORT;
            p.setProperty("servers.total", "1");
            p.setProperty("servers.0.host", rootHost);
            p.setProperty("servers.0.port", String.valueOf(rPort));
            System.out.println("Connecting to root: " + rootHost + ":" + rPort);
        }

        Server server = new Server(p);
        server.start();

        System.out.println("Agent started. Waiting for jobs...");
        System.out.println("Submit jobs using ClaudeCodeClient or the Java API.");
        System.out.println("===========================================");

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("Agent shutting down.");
        }
    }
}
