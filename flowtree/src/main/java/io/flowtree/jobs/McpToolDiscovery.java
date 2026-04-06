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
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for discovering tool names from Python MCP server source files.
 *
 * <p>Supports three common MCP server patterns:</p>
 * <ul>
 *   <li><b>Decorator pattern:</b> {@code @mcp.tool()} decorating individual
 *       {@code def function_name()} definitions (used by ar-messages, ar-memory,
 *       ar-consultant, ar-profile-analyzer)</li>
 *   <li><b>List-tools pattern:</b> {@code @server.list_tools()} returning a
 *       list of {@code Tool(name="tool_name", ...)} entries (used by
 *       ar-test-runner, ar-jmx, ar-docs)</li>
 *   <li><b>Dynamic registration pattern:</b> {@code server.tool()(fn)} calls
 *       in a loop over a list of function references (used by ar-github)</li>
 * </ul>
 *
 * <p>Used by both {@link ClaudeCodeJob} (for local servers) and
 * {@link io.flowtree.slack.FlowTreeController} (for centralized servers).</p>
 *
 * @author Michael Murray
 */
public class McpToolDiscovery {

    /** Matches a Python {@code def function_name(} line to extract the function name. */
    private static final Pattern FUNC_DEF_PATTERN = Pattern.compile("def\\s+(\\w+)\\s*\\(");

    /** Matches an inline {@code Tool(name="tool_name")} constructor. */
    private static final Pattern TOOL_NAME_INLINE_PATTERN =
        Pattern.compile("Tool\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");

    /**
     * Matches a {@code name = "tool_name"} line that appears on its own line
     * inside a multi-line {@code Tool(...)} constructor.
     */
    private static final Pattern TOOL_NAME_SEPARATE_PATTERN =
        Pattern.compile("^\\s*name\\s*=\\s*\"([^\"]+)\"");

    /** Matches a {@code .tool()(varname)} dynamic registration call. */
    private static final Pattern DYNAMIC_TOOL_CALL_PATTERN =
        Pattern.compile("\\.tool\\(\\)\\(\\w+\\)");

    /**
     * Matches a parameter declaration line inside a Python function signature,
     * e.g. {@code     param_name: type = default,}. Group 1 is the parameter name.
     */
    private static final Pattern PARAM_LINE_PATTERN =
        Pattern.compile("^\\s+(\\w+)\\s*:");

    /**
     * Scans a Python MCP server source file for tool definitions and
     * returns their names.
     *
     * <p>First attempts to find {@code @mcp.tool()} decorated functions.
     * If none are found, tries parsing {@code Tool(name="...")} entries
     * inside a {@code @server.list_tools()} handler. If still empty,
     * falls back to detecting dynamic {@code .tool()(fn)} registration
     * calls that iterate over a list of function references.</p>
     *
     * @param serverFile path to the Python server source file
     * @return list of tool names, empty if file does not exist or has no tools
     */
    public static List<String> discoverToolNames(Path serverFile) {
        List<String> lines = readLines(serverFile);
        if (lines.isEmpty()) return new ArrayList<>();
        List<String> tools = discoverDecoratorTools(lines);
        if (tools.isEmpty()) {
            tools = discoverListToolsEntries(lines);
        }
        if (tools.isEmpty()) {
            tools = discoverDynamicRegistration(lines);
        }
        return tools;
    }

    /**
     * Returns the parameter names declared in the function signature of a given
     * {@code @mcp.tool()}-decorated function in a Python server source file.
     *
     * <p>Parses multi-line function signatures of the form:</p>
     * <pre>
     * &#64;mcp.tool()
     * def tool_name(
     *     param_one: str,
     *     param_two: str = "",
     * ) -&gt; dict:
     * </pre>
     *
     * @param serverFile path to the Python server source file
     * @param toolName   the tool function name to inspect
     * @return list of parameter names in declaration order, empty if the tool
     *         is not found or the file does not exist
     */
    public static List<String> discoverToolParameters(Path serverFile, String toolName) {
        List<String> lines = readLines(serverFile);
        if (lines.isEmpty()) return new ArrayList<>();
        return discoverParametersForTool(lines, toolName);
    }

    /**
     * Reads all UTF-8 lines from the given Python server source file.
     *
     * @param serverFile path to the file; may be {@code null}
     * @return list of lines, empty if the file is {@code null}, does not exist,
     *         or cannot be read
     */
    private static List<String> readLines(Path serverFile) {
        if (serverFile == null || !Files.exists(serverFile)) return new ArrayList<>();
        try {
            return Files.readAllLines(serverFile, StandardCharsets.UTF_8);
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

    /**
     * Discovers tools from the dynamic {@code .tool()(fn)} registration pattern.
     *
     * <p>Detects code like:</p>
     * <pre>
     * for fn in [
     *     github_pr_find,
     *     github_pr_review_comments,
     * ]:
     *     server.tool()(fn)
     * </pre>
     *
     * <p>Scans for {@code .tool()(} calls, then looks backward from each call
     * to find a {@code for ... in [} loop and extracts the function name
     * identifiers from the list literal.</p>
     */
    private static List<String> discoverDynamicRegistration(List<String> lines) {
        List<String> tools = new ArrayList<>();
        Pattern funcRefPattern = Pattern.compile("^\\s*(\\w+)\\s*,?\\s*$");

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!DYNAMIC_TOOL_CALL_PATTERN.matcher(trimmed).find()) continue;

            // Found a .tool()(var) call — scan backward for the list of function refs
            for (int j = i - 1; j >= 0; j--) {
                String prev = lines.get(j).trim();

                // Stop at the opening "for ... in [" line
                if (prev.startsWith("for ") && prev.contains(" in [")) {
                    break;
                }

                // Skip the closing "]:" line
                if (prev.equals("]:") || prev.equals("]")) continue;

                // Extract function name identifiers from list entries
                Matcher refMatch = funcRefPattern.matcher(lines.get(j));
                if (refMatch.find()) {
                    String name = refMatch.group(1);
                    if (!name.equals("fn") && !name.equals("f") && !name.equals("func")) {
                        tools.add(name);
                    }
                }
            }

            // Reverse to preserve source order (we scanned backward)
            Collections.reverse(tools);
            break;
        }

        return tools;
    }

    /**
     * Parses the parameter names from the function signature of the named tool.
     *
     * <p>Assumes multi-line signatures where each parameter appears on its own
     * line as {@code     param_name: type_hint [= default],}. Scanning stops when
     * a line starting with {@code ) ->} or {@code ):} is reached.</p>
     *
     * @param lines    all lines of the server source file
     * @param toolName the name of the function whose parameters to extract
     * @return parameter names in source order, excluding {@code self}
     */
    private static List<String> discoverParametersForTool(List<String> lines, String toolName) {
        Pattern funcDefStart = Pattern.compile("def\\s+" + Pattern.quote(toolName) + "\\s*\\(");
        List<String> params = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!funcDefStart.matcher(lines.get(i)).find()) continue;

            for (int j = i + 1; j < lines.size(); j++) {
                String trimmed = lines.get(j).trim();
                if (trimmed.startsWith(") ->") || trimmed.startsWith("):")) break;

                Matcher m = PARAM_LINE_PATTERN.matcher(lines.get(j));
                if (m.find()) {
                    String param = m.group(1);
                    if (!param.equals("self") && !param.equals("cls")) {
                        params.add(param);
                    }
                }
            }
            break;
        }

        return params;
    }
}
