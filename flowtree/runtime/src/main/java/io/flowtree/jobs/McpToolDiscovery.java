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
 *       {@code def function_name()} definitions (used by ar-manager, ar-memory,
 *       ar-consultant, ar-profile-analyzer, ar-secrets)</li>
 *   <li><b>List-tools pattern:</b> {@code @server.list_tools()} returning a
 *       list of {@code Tool(name="tool_name", ...)} entries (used by
 *       ar-test-runner, ar-jmx, ar-docs)</li>
 *   <li><b>Dynamic registration pattern:</b> {@code server.tool()(fn)} calls
 *       in a loop over a list of function references</li>
 * </ul>
 *
 * <p>Used by both {@link CodingAgentJob} (for local servers) and
 * {@link io.flowtree.controller.FlowTreeController} (for centralized servers).</p>
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
     * Matches a parameter name at the start of a trimmed parameter segment from a
     * single-line function signature, e.g. {@code query: str} or
     * {@code namespace: str = "default"}. Group 1 is the parameter name.
     */
    private static final Pattern INLINE_PARAM_PATTERN =
        Pattern.compile("^(\\w+)");

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
     * <p>Handles both single-line signatures such as:</p>
     * <pre>
     * def tool_name(param_one: str, param_two: str = "default") -&gt; dict:
     * </pre>
     * <p>and multi-line signatures of the form:</p>
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
     * Reports whether the given parameter of the named tool has a default value
     * in its Python function signature — i.e. whether the parameter is
     * <em>optional</em> from the MCP client's perspective.
     *
     * <p>FastMCP derives the JSON schema {@code required} list from parameters
     * that have no default. A parameter declared as
     * {@code workstream_id: str = ""} is optional and will not appear in the
     * schema's {@code required} list; one declared as
     * {@code workstream_id: str} (no default) is required. This helper lets
     * the discovery tests assert the policy intent — "this parameter is
     * intentionally optional" — without having to embed the full FastMCP
     * runtime in the build.</p>
     *
     * <p>Returns {@code false} when the file does not exist, the tool is not
     * found, or the parameter is not declared in the signature.</p>
     *
     * @param serverFile path to the Python server source file
     * @param toolName   the tool function name to inspect
     * @param paramName  the parameter name to check
     * @return {@code true} when the parameter declares a default value,
     *         {@code false} otherwise
     */
    public static boolean isOptionalToolParameter(Path serverFile,
                                                  String toolName,
                                                  String paramName) {
        List<String> lines = readLines(serverFile);
        if (lines.isEmpty()) return false;
        return discoverParameterHasDefault(lines, toolName, paramName);
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
     * Walks up from the current working directory looking for the
     * {@code tools/mcp/manager/server.py} source file. Maven Surefire
     * defaults the working directory of a forked test JVM to the
     * module's basedir ({@code flowtree/runtime/}) so a single
     * relative path like {@code tools/...} only resolves when the
     * test is launched from the project root. Walking the ancestor
     * chain is the standard mitigation: a path like
     * {@code /workspace/project/almostrealism-common} resolves from
     * either a module-basedir or a project-root working directory.
     *
     * <p>Uses {@link Files#isRegularFile(Path)} rather than
     * {@link Files#exists(Path)} so a directory that happens to be
     * named {@code server.py} cannot produce a false positive — the
     * helper's contract is to find a Python source file, not any
     * filesystem entry with that name.</p>
     *
     * <p>Centralised so the resolution path lives in one place;
     * {@code McpToolDiscoveryTest} and
     * {@code McpToolWorkstreamConfigSurfaceTest} both call this
     * helper, eliminating the silent-skip failure mode that
     * historical copies were re-introducing when their search depth
     * or path drifted between test classes.</p>
     *
     * @return the resolved regular file path, or {@code null} if the
     *         file is not found within five levels of ancestor
     *         directories
     */
    public static Path locateManagerServerPy() {
        Path cwd = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cwd != null; i++) {
            Path candidate = cwd.resolve("tools/mcp/manager/server.py");
            if (Files.isRegularFile(candidate)) return candidate;
            cwd = cwd.getParent();
        }
        return null;
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
            String defLine = lines.get(i);
            if (!funcDefStart.matcher(defLine).find()) continue;

            // Check whether the closing paren is on the same line as the def.
            int openParen = defLine.indexOf('(');
            int closeParen = defLine.lastIndexOf(')');
            if (openParen >= 0 && closeParen > openParen) {
                // Single-line signature: parse params between ( and ).
                String paramSection = defLine.substring(openParen + 1, closeParen);
                for (String segment : paramSection.split(",")) {
                    String trimmed = segment.trim();
                    if (trimmed.isEmpty()) continue;
                    Matcher m = INLINE_PARAM_PATTERN.matcher(trimmed);
                    if (m.find()) {
                        String param = m.group(1);
                        if (!param.equals("self") && !param.equals("cls")) {
                            params.add(param);
                        }
                    }
                }
            } else {
                // Multi-line signature: each parameter is on its own indented line.
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
            }
            break;
        }

        return params;
    }

    /**
     * Reports whether the given parameter of the named tool declares a default
     * value in its Python function signature.
     *
     * <p>A parameter with a default — for example
     * {@code workstream_id: str = ""} on a multi-line signature, or
     * {@code limit: int = 10} on a single-line signature — is optional from
     * FastMCP's perspective (it will not appear in the generated schema's
     * {@code required} list). A parameter without a default is required.</p>
     *
     * @param lines     all lines of the server source file
     * @param toolName  the tool function name to inspect
     * @param paramName the parameter name to check
     * @return {@code true} when the parameter is declared with {@code = ...}
     *         in the signature, {@code false} otherwise
     */
    private static boolean discoverParameterHasDefault(List<String> lines,
                                                       String toolName,
                                                       String paramName) {
        Pattern funcDefStart = Pattern.compile("def\\s+" + Pattern.quote(toolName) + "\\s*\\(");
        // Match the parameter token and the start of its type hint; the
        // ``=`` that follows the type hint may sit at an arbitrary
        // offset once the type contains parameterized generics
        // (e.g. ``Dict[str, str]``, ``Optional[Union[str, int]]``), so
        // the regex does not try to consume the type — it anchors on
        // the param name + colon and lets the post-scan below find
        // the equals sign while tracking bracket depth.
        Pattern paramStart = Pattern.compile(
            "(^|[\\s,(])" + Pattern.quote(paramName) + "\\s*:");

        for (int i = 0; i < lines.size(); i++) {
            String defLine = lines.get(i);
            if (!funcDefStart.matcher(defLine).find()) continue;

            int openParen = defLine.indexOf('(');
            int closeParen = defLine.lastIndexOf(')');
            if (openParen >= 0 && closeParen > openParen) {
                // Single-line signature: walk the ( ... ) section
                // and ask whether the named parameter has a default.
                String paramSection = defLine.substring(openParen + 1, closeParen);
                return paramHasDefaultInSlice(paramSection, paramStart);
            }
            // Multi-line signature: scan successive indented lines
            // until the closing ``) ->`` / ``):`` line.
            for (int j = i + 1; j < lines.size(); j++) {
                String trimmed = lines.get(j).trim();
                if (trimmed.startsWith(") ->") || trimmed.startsWith("):")) break;
                if (paramHasDefaultInSlice(lines.get(j), paramStart)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * Scans ``slice`` for a parameter matched by ``paramStart`` whose
     * declaration has a default value, where the equals sign that
     * separates type hint from default may sit inside or after a
     * parameterized generic type such as ``Dict[str, str]`` or
     * ``Optional[Union[str, int]]``. The regex ``paramStart`` already
     * encodes the parameter name, so no name argument is required here.
     * Tracks bracket depth so commas inside ``[ ]`` or ``( )`` are not
     * mistaken for the boundary between parameters.
     *
     * <p>Returns {@code true} only when the named parameter is the
     * one carrying the default — a later parameter with a default
     * does not make an earlier no-default parameter "optional."</p>
     */
    private static boolean paramHasDefaultInSlice(String slice, Pattern paramStart) {
        Matcher m = paramStart.matcher(slice);
        if (!m.find()) return false;
        int afterColon = m.end();
        int boundary = findTopLevelBoundary(slice, afterColon);
        if (boundary < 0) return false;
        return slice.charAt(boundary) == '=';
    }

    /**
     * Walks ``text`` starting at ``start`` and returns the index of
     * the first top-level {@code =} or {@code ,} — i.e., the first
     * boundary character that is not inside a {@code [ ... ]}
     * generic argument list, a {@code ( ... )} call, or a quoted
     * string. The character at the returned index is the boundary
     * itself: callers compare against {@code '='} to decide whether
     * the parameter has a default. Returns {@code -1} when neither
     * is found (e.g., the parameter is the last in the slice and has
     * no default — that means the parameter is required).
     */
    private static int findTopLevelBoundary(String text, int start) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingle) {
                if (c == '\\' && i + 1 < text.length()) { i++; continue; }
                if (c == '\'') inSingle = false;
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < text.length()) { i++; continue; }
                if (c == '"') inDouble = false;
                continue;
            }
            if (c == '\'') { inSingle = true; continue; }
            if (c == '"') { inDouble = true; continue; }
            if (c == '[' || c == '(') { depth++; continue; }
            if (c == ']' || c == ')') {
                if (depth > 0) depth--;
                continue;
            }
            if (c == '#') {
                // Trailing comment — neither '=' nor ',' past here
                // is meaningful for parameter parsing.
                return -1;
            }
            if (depth == 0 && (c == '=' || c == ',')) return i;
        }
        return -1;
    }
}
