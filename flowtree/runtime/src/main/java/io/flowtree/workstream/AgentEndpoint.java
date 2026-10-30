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

package io.flowtree.workstream;

/**
 * Connection details for a Flowtree agent.
 *
 * <p>This is retained for optional pre-configured agent lists, but the
 * primary connection model is inbound: agents connect to the controller's
 * {@link io.flowtree.Server} using {@code FLOWTREE_ROOT_HOST} and
 * {@code FLOWTREE_ROOT_PORT}.</p>
 */
public class AgentEndpoint {
    /** The hostname or IP address of the agent. */
    private final String host;
    /** The port the agent's FlowTree node is listening on. */
    private final int port;

    /**
     * Creates an agent endpoint with the given host and port.
     *
     * @param host the agent hostname or IP address
     * @param port the agent port number
     */
    public AgentEndpoint(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Returns the agent hostname or IP address. */
    public String getHost() {
        return host;
    }

    /** Returns the agent port number. */
    public int getPort() {
        return port;
    }

    /** Returns "host:port" for logging and diagnostics. */
    @Override
    public String toString() {
        return host + ":" + port;
    }
}
