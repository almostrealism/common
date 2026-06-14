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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration entry for an agent endpoint.
 *
 * <p>Agents connect inbound to the controller's FlowTree server, so the
 * {@code agents} field on a workstream is optional and typically omitted in
 * modern deployments. This entry retains backward-compatibility for
 * configurations that still enumerate explicit outbound agent hosts.</p>
 *
 * @author Michael Murray
 * @see WorkstreamConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentEntry {

    /** The agent hostname or IP address (default: {@code "localhost"}). */
    private String host = "localhost";
    /** The port the agent's FlowTree node listens on (default: 7766). */
    private int port = 7766;

    /** No-arg constructor for Jackson deserialization. */
    public AgentEntry() {}

    /**
     * Creates a new agent entry with the specified host and port.
     *
     * @param host the agent hostname or IP address
     * @param port the agent port number
     */
    public AgentEntry(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Returns the agent hostname or IP address. */
    public String getHost() { return host; }
    /** Sets the agent hostname or IP address. */
    public void setHost(String host) { this.host = host; }

    /** Returns the agent port number. */
    public int getPort() { return port; }
    /** Sets the agent port number. */
    public void setPort(int port) { this.port = port; }
}
