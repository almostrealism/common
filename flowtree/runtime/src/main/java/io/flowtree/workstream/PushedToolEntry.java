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

import java.util.Map;

/**
 * Configuration entry for a pushed MCP tool.
 *
 * <p>Pushed tools are served as files by the controller and downloaded
 * into dev containers on first use. Unlike centralized servers (which
 * run as HTTP processes on the controller), pushed tools run locally
 * inside each container via stdio.</p>
 *
 * @author Michael Murray
 * @see WorkstreamConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PushedToolEntry {

    /** Python source file path relative to the config directory. */
    private String source;
    /** Per-tool environment variables injected into the agent's MCP stdio config. */
    private Map<String, String> env;

    /** Returns the Python source file path (relative to config directory). */
    public String getSource() { return source; }
    /** Sets the Python source file path (relative to config directory). */
    public void setSource(String source) { this.source = source; }

    /** Returns per-tool environment variables to inject into the MCP stdio config. */
    public Map<String, String> getEnv() { return env; }
    /** Sets per-tool environment variables for the MCP stdio config. */
    public void setEnv(Map<String, String> env) { this.env = env; }
}
