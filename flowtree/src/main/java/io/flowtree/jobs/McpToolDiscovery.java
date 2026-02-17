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
 * <p>Scans Python files for {@code @mcp.tool()} decorated functions
 * and extracts their names. Used by both {@link ClaudeCodeJob} (for local
 * servers) and {@link io.flowtree.slack.FlowTreeController} (for centralized
 * servers).</p>
 *
 * @author Michael Murray
 */
public class McpToolDiscovery {

    private static final Pattern FUNC_DEF_PATTERN = Pattern.compile("def\\s+(\\w+)\\s*\\(");

    /**
     * Scans a Python MCP server source file for {@code @mcp.tool()}
     * decorated functions and returns their names.
     *
     * @param serverFile path to the Python server source file
     * @return list of tool function names, empty if file does not exist or has no tools
     */
    public static List<String> discoverToolNames(Path serverFile) {
        List<String> tools = new ArrayList<>();
        if (serverFile == null || !Files.exists(serverFile)) return tools;

        try {
            List<String> lines = Files.readAllLines(serverFile, StandardCharsets.UTF_8);
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
        } catch (IOException e) {
            // Caller will handle empty list
        }

        return tools;
    }
}
