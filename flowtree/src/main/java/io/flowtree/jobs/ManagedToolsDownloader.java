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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles downloading and verification of MCP tool server files
 * for pushed tool configurations.
 *
 * <p>This class extracts tool downloading logic from {@link ClaudeCodeJob},
 * providing methods to download pushed tool source files from a controller
 * and to verify that MCP tool server files exist in a working directory.</p>
 *
 * <p>Pushed tools are MCP servers that are downloaded from the controller
 * and run locally via stdio in the agent's container. Each tool's source
 * file is saved to {@code ~/.flowtree/tools/mcp/{name}/server.py}.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob
 * @see McpConfigBuilder
 */
public class ManagedToolsDownloader implements ConsoleFeatures {

	private final McpConfigBuilder configBuilder;
	private final ObjectMapper objectMapper;

	/**
	 * Creates a new {@link ManagedToolsDownloader} with the specified
	 * configuration builder.
	 *
	 * @param configBuilder the builder used to parse pushed tool configurations
	 *                      and extract JSON fields
	 */
	public ManagedToolsDownloader(McpConfigBuilder configBuilder) {
		this.configBuilder = configBuilder;
		this.objectMapper = new ObjectMapper();
	}

	/**
	 * Downloads pushed tool source files from the controller to
	 * {@code ~/.flowtree/tools/mcp/{name}/server.py} if not already present.
	 *
	 * <p>For each server defined in the pushed tools configuration, this
	 * method checks whether the target file already exists. If not, it
	 * extracts the download URL from the configuration JSON, resolves the
	 * {@code 0.0.0.0} placeholder with the value of the {@code FLOWTREE_ROOT_HOST}
	 * environment variable, and downloads the file via HTTP GET.</p>
	 *
	 * @param pushedToolsConfig the JSON string mapping server names to their
	 *                          download URLs and tool names
	 */
	public void ensurePushedTools(String pushedToolsConfig) {
		Map<String, List<String>> pushedTools = parseAllServerNames(pushedToolsConfig);
		if (pushedTools.isEmpty()) return;

		String rootHost = System.getenv("FLOWTREE_ROOT_HOST");
		String home = System.getProperty("user.home");

		for (String serverName : pushedTools.keySet()) {
			Path targetDir = Path.of(home, ".flowtree", "tools", "mcp", serverName);
			Path targetFile = targetDir.resolve("server.py");

			if (Files.exists(targetFile)) {
				log("Pushed tool already present: " + serverName);
				continue;
			}

			String url = extractUrlFromConfig(pushedToolsConfig, serverName);
			if (url == null) continue;

			if (rootHost != null && !rootHost.isEmpty()) {
				url = url.replace("0.0.0.0", rootHost);
			}

			try {
				Files.createDirectories(targetDir);
				String content = httpGet(url);
				Files.writeString(targetFile, content, StandardCharsets.UTF_8);
				log("Downloaded pushed tool: " + serverName + " -> " + targetFile);
			} catch (IOException e) {
				warn("Failed to download pushed tool " + serverName + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Verifies that MCP tool server files exist in the working directory
	 * and logs their modification times for deployment diagnostics.
	 *
	 * <p>Checks for {@code tools/mcp/slack/server.py} and
	 * {@code tools/mcp/github/server.py} relative to the given working
	 * directory. For each file that exists, the age in seconds since last
	 * modification is logged. Missing files produce a warning.</p>
	 *
	 * @param workingDirectory the working directory to resolve tool paths against
	 */
	public void verifyMcpToolFiles(Path workingDirectory) {
		String[] toolFiles = {
			"tools/mcp/slack/server.py",
			"tools/mcp/github/server.py"
		};

		for (String toolFile : toolFiles) {
			Path resolved = workingDirectory.resolve(toolFile);
			if (Files.exists(resolved)) {
				try {
					long lastModified = Files.getLastModifiedTime(resolved).toMillis();
					long ageSeconds = (System.currentTimeMillis() - lastModified) / 1000;
					log("MCP tool: " + toolFile + " (modified " + ageSeconds + "s ago)");
				} catch (IOException e) {
					log("MCP tool: " + toolFile + " (exists, could not read mtime)");
				}
			} else {
				warn("MCP tool missing: " + resolved.toAbsolutePath());
			}
		}
	}

	/**
	 * Performs an HTTP GET request and returns the response body as a string.
	 *
	 * <p>Uses a connect timeout of 10000ms and a read timeout of 30000ms.
	 * Throws an {@link IOException} if the response status code is not in
	 * the 2xx range.</p>
	 *
	 * @param url the URL to fetch
	 * @return the response body as a string
	 * @throws IOException if the request fails or returns a non-2xx status
	 */
	public String httpGet(String url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(10000);
		conn.setReadTimeout(30000);

		int responseCode = conn.getResponseCode();
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("HTTP " + responseCode + " from " + url);
		}

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append("\n");
			}
			return sb.toString();
		}
	}

	/**
	 * Parses the pushed tools config JSON to extract all server names,
	 * including those with empty tool lists. Used by {@link #ensurePushedTools}
	 * to ensure files are downloaded regardless of whether tool discovery
	 * found tool names on the controller.
	 *
	 * @param pushedToolsConfig the pushed tools configuration JSON
	 * @return map of server name to tool name list, empty if null or invalid
	 */
	private Map<String, List<String>> parseAllServerNames(String pushedToolsConfig) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		if (pushedToolsConfig == null || pushedToolsConfig.isEmpty()) return result;

		try {
			JsonNode root = objectMapper.readTree(pushedToolsConfig);
			Iterator<String> fieldNames = root.fieldNames();
			while (fieldNames.hasNext()) {
				String serverName = fieldNames.next();
				JsonNode serverNode = root.get(serverName);
				List<String> tools = new ArrayList<>();
				JsonNode toolsNode = serverNode.get("tools");
				if (toolsNode != null && toolsNode.isArray()) {
					for (JsonNode toolNode : toolsNode) {
						tools.add(toolNode.asText());
					}
				}
				result.put(serverName, tools);
			}
		} catch (IOException e) {
			warn("Failed to parse pushed tools config: " + e.getMessage());
		}

		return result;
	}

	/**
	 * Parses the pushed tools config JSON to extract server names and
	 * their tool lists using Jackson. Servers with empty tool lists are excluded.
	 *
	 * @param pushedToolsConfig the pushed tools configuration JSON
	 * @return map of server name to tool name list, empty if null or invalid
	 */
	private Map<String, List<String>> parsePushedServerNames(String pushedToolsConfig) {
		Map<String, List<String>> result = new LinkedHashMap<>();
		if (pushedToolsConfig == null || pushedToolsConfig.isEmpty()) return result;

		try {
			JsonNode root = objectMapper.readTree(pushedToolsConfig);
			Iterator<String> fieldNames = root.fieldNames();
			while (fieldNames.hasNext()) {
				String serverName = fieldNames.next();
				JsonNode serverNode = root.get(serverName);
				List<String> tools = new ArrayList<>();
				JsonNode toolsNode = serverNode.get("tools");
				if (toolsNode != null && toolsNode.isArray()) {
					for (JsonNode toolNode : toolsNode) {
						tools.add(toolNode.asText());
					}
				}
				if (!tools.isEmpty()) {
					result.put(serverName, tools);
				}
			}
		} catch (IOException e) {
			warn("Failed to parse pushed tools config: " + e.getMessage());
		}

		return result;
	}

	/**
	 * Extracts the download URL for a server from the pushed tools
	 * configuration JSON using Jackson {@link ObjectMapper}.
	 *
	 * <p>Parses the JSON and navigates to {@code {serverName}.url} to
	 * retrieve the URL string.</p>
	 *
	 * @param pushedToolsConfig the pushed tools configuration JSON
	 * @param serverName        the server name to look up
	 * @return the URL string, or {@code null} if not found or on parse error
	 */
	private String extractUrlFromConfig(String pushedToolsConfig, String serverName) {
		try {
			JsonNode root = objectMapper.readTree(pushedToolsConfig);
			JsonNode serverNode = root.get(serverName);
			if (serverNode == null) return null;

			JsonNode urlNode = serverNode.get("url");
			if (urlNode == null || !urlNode.isTextual()) return null;

			return urlNode.asText();
		} catch (IOException e) {
			warn("Failed to parse pushed tools config for " + serverName + ": " + e.getMessage());
			return null;
		}
	}
}
