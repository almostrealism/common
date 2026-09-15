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

package io.flowtree.jobs;

/**
 * A tool-call boundary observed in an agent's output stream: either the agent
 * has just asked for a tool to run, or the tool's result has just been handed
 * back to it.
 *
 * <p>Runners produce these from their own output format (see
 * {@code ClaudeCodeRunner#classifyActivity}) and {@link AgentActivityTracker}
 * pairs them up, so that the inactivity watchdog can tell "waiting on a tool"
 * from "producing nothing".</p>
 *
 * @param toolUseId identifier that pairs a start with its finish
 * @param toolName  the tool being called; {@code null} on a finish, where the
 *                  stream does not repeat it
 * @param started   {@code true} for the start of a call, {@code false} for its end
 *
 * @author Michael Murray
 */
public record AgentActivity(String toolUseId, String toolName, boolean started) {

    /** Prefix every MCP-served tool name carries in a Claude Code session. */
    public static final String MCP_TOOL_PREFIX = "mcp__";

    /**
     * Creates the start of a tool call.
     *
     * @param toolUseId identifier of the call
     * @param toolName  name of the tool being invoked
     * @return the activity
     */
    public static AgentActivity started(String toolUseId, String toolName) {
        return new AgentActivity(toolUseId, toolName, true);
    }

    /**
     * Creates the end of a tool call.
     *
     * @param toolUseId identifier of the call that finished
     * @return the activity
     */
    public static AgentActivity finished(String toolUseId) {
        return new AgentActivity(toolUseId, null, false);
    }

    /**
     * Returns whether this activity concerns an MCP-served tool. MCP calls are
     * bounded by the serving process rather than by a shell the agent wrote,
     * which is why the watchdog extends more patience to them.
     *
     * @return {@code true} when the tool name carries {@link #MCP_TOOL_PREFIX}
     */
    public boolean isMcpTool() {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX);
    }
}
