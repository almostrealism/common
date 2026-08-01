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

import java.util.List;

/**
 * Configuration entry for a centralized MCP server.
 *
 * <p>When present in the YAML configuration, the controller starts
 * each server as an HTTP process and agents connect over HTTP
 * instead of stdio.</p>
 *
 * @author Michael Murray
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerEntry {
    /** Python source file path relative to the project root (for managed servers). */
    private String source;
    /** HTTP port the managed server process listens on. */
    private int port;
    /** URL of an already-running external server; when set, no subprocess is launched. */
    private String url;
    /** Explicit tool names exposed by this server; required when {@code url} is set. */
    private List<String> tools;

    /** Returns the Python source file path (relative to project root). */
    public String getSource() { return source; }
    /** Sets the Python source file path (relative to project root). */
    public void setSource(String source) { this.source = source; }

    /** Returns the HTTP port to listen on. */
    public int getPort() { return port; }
    /** Sets the HTTP port for the managed MCP server process. */
    public void setPort(int port) { this.port = port; }

    /**
     * Returns the URL of an already-running centralized server.
     * When set, the controller does not launch a subprocess —
     * it simply passes the URL through to agents.
     */
    public String getUrl() { return url; }
    /** Sets the URL of an already-running external MCP server. */
    public void setUrl(String url) { this.url = url; }

    /**
     * Returns the explicit tool names for this server.
     * Required when {@code url} is set (no source file to discover from).
     */
    public List<String> getTools() { return tools; }
    /** Sets the explicit tool names exposed by this server. */
    public void setTools(List<String> tools) { this.tools = tools; }

    /** Returns true if this entry references an external server by URL. */
    public boolean isExternal() {
        return url != null && !url.isEmpty();
    }
}
