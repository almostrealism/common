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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for discovering tool names from Python MCP server source files.
 *
 * <p>Supports two common MCP server patterns:</p>
 * <ul>
 *   <li><b>Decorator pattern:</b> {@code @mcp.tool()} decorating individual
 *       {@code def function_name()} definitions (used by ar-slack, ar-memory,
 *       ar-consultant, ar-profile-analyzer, ar-github)</li>
 *   <li><b>List-tools pattern:</b> {@code @server.list_tools()} returning a
 *       list of {@code Tool(name="tool_name", ...)} entries (used by
 *       ar-test-runner, ar-jmx, ar-docs)</li>
 * </ul>
 *
 * <p>Used by both {@link ClaudeCodeJob} (for local servers) and
 * {@link io.flowtree.slack.FlowTreeController} (for centralized servers).</p>
 *
 * @author Michael Murray
 */
public class McpToolDiscovery {

    private static final Pattern FUNC_DEF_PATTERN = Pattern.compile("def\\s+(\\w+)\\s*\\(");
    private static final Pattern TOOL_NAME_INLINE_PATTERN =
        Pattern.compile("Tool\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOOL_NAME_SEPARATE_PATTERN =
        Pattern.compile("^\\s*name\\s*=\\s*\"([^\"]+)\"");

    /**
     * Scans a Python MCP server source file for tool definitions and
     * returns their names.
     *
     * <p>First attempts to find {@code @mcp.tool()} decorated functions.
     * If none are found, falls back to parsing {@code Tool(name="...")}
     * entries inside a {@code @server.list_tools()} handler.</p>
     *
     * @param serverFile path to the Python server source file
     * @return list of tool names, empty if file does not exist or has no tools
     */
    public static List<String> discoverToolNames(Path serverFile) {
        if (serverFile == null || !Files.exists(serverFile)) return new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(serverFile, StandardCharsets.UTF_8);
            List<String> tools = discoverDecoratorTools(lines);
            if (tools.isEmpty()) {
                tools = discoverListToolsEntries(lines);
            }
            return tools;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Discovers tools from the {@code @mcp.tool()} decorator pattern.
     * Each decorated function's name is used as the tool name.
     */
    private static List<String> discoverDecoratorTools(List<String> lines) {
        List<String> tools = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("@mcp.tool")) {
                for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                    Matcher m = FUNC_DEF_PATTERN.matcher(lines.get(j));
                    if (m.find()) {
                        tools.add(m.group(1));
                        break;
                    }
                }
            }
        }
        return tools;
    }

    /**
     * Discovers tools from the {@code @server.list_tools()} handler pattern.
     * Parses {@code Tool(name="tool_name", ...)} entries in the body of the
     * handler function. Handles both inline ({@code Tool(name="x")}) and
     * multi-line ({@code Tool(\n  name="x"\n)}) formats.
     */
    private static List<String> discoverListToolsEntries(List<String> lines) {
        List<String> tools = new ArrayList<>();
        boolean inListTools = false;
        boolean inToolConstructor = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("@server.list_tools") || trimmed.startsWith("@mcp.list_tools")) {
                inListTools = true;
                continue;
            }

            if (inListTools) {
                // Stop at the next decorator (start of call_tool handler or another section)
                if (trimmed.startsWith("@server.") || trimmed.startsWith("@mcp.")) {
                    break;
                }

                // Check for Tool(name="..." on the same line
                Matcher inlineMatch = TOOL_NAME_INLINE_PATTERN.matcher(trimmed);
                if (inlineMatch.find()) {
                    tools.add(inlineMatch.group(1));
                    inToolConstructor = false;
                    continue;
                }

                // Track when we enter a Tool( constructor
                if (trimmed.startsWith("Tool(")) {
                    inToolConstructor = true;
                    continue;
                }

                // Inside a Tool(...) constructor, look for name="..." on its own line
                if (inToolConstructor) {
                    Matcher nameMatch = TOOL_NAME_SEPARATE_PATTERN.matcher(line);
                    if (nameMatch.find()) {
                        tools.add(nameMatch.group(1));
                        inToolConstructor = false;
                    }
                }
            }
        }
        return tools;
    }
}
