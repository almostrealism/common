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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link McpConfigBuilder} covering centralized HTTP entries,
 * pushed stdio entries, project server discovery, config parsing, and
 * allowed tools assembly.
 */
public class McpConfigBuilderTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void buildsCentralizedHttpEntries() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setCentralizedMcpConfig(
				"{\"ar-messages\":{\"url\":\"http://localhost:8080\",\"tools\":[\"send_message\"]}}");

		String config = builder.buildMcpConfig();
		assertNotNull(config);
		assertTrue("Config should contain http type", config.contains("\"type\":\"http\""));
		assertTrue("Config should contain ar-messages", config.contains("ar-messages"));
	}

	@Test(timeout = 30000)
	public void buildsPushedStdioEntries() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setPushedToolsConfig(
				"{\"ar-memory\":{\"url\":\"http://controller/download\",\"tools\":[\"memory_store\",\"memory_search\"]}}");

		String config = builder.buildMcpConfig();
		assertNotNull(config);
		assertTrue("Config should contain python3 command", config.contains("\"command\":\"python3\""));
		assertTrue("Config should contain ar-memory", config.contains("ar-memory"));
	}

	@Test(timeout = 30000)
	public void buildsProjectServerEntries() throws IOException {
		Path tempDir = Files.createTempDirectory("mcp_config_test_");
		try {
			// Create .mcp.json with a project server
			Path mcpJson = tempDir.resolve(".mcp.json");
			Files.writeString(mcpJson, String.join("\n",
					"{",
					"  \"mcpServers\": {",
					"    \"my-server\": {",
					"      \"command\": \"python3\",",
					"      \"args\": [\"tools/mcp/my-server/server.py\"]",
					"    }",
					"  }",
					"}"
			), StandardCharsets.UTF_8);

			// Create .claude/settings.json with enabledMcpjsonServers
			Path claudeDir = tempDir.resolve(".claude");
			Files.createDirectories(claudeDir);
			Files.writeString(claudeDir.resolve("settings.json"),
					"{\"enabledMcpjsonServers\":[\"my-server\"]}",
					StandardCharsets.UTF_8);

			McpConfigBuilder builder = new McpConfigBuilder();
			builder.setWorkingDirectory(tempDir);

			String config = builder.buildMcpConfig();
			assertNotNull(config);
			assertTrue("Config should contain my-server", config.contains("my-server"));
		} finally {
			// Clean up temp files
			Files.walk(tempDir)
					.sorted(java.util.Comparator.reverseOrder())
					.forEach(p -> {
						try { Files.deleteIfExists(p); } catch (IOException ignored) { }
					});
		}
	}

	@Test(timeout = 30000)
	public void parseCentralizedConfigExtractsServerNames() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setCentralizedMcpConfig(
				"{\"ar-messages\":{\"url\":\"http://localhost:8080\",\"tools\":[\"send_message\"]}," +
				"\"ar-memory\":{\"url\":\"http://localhost:8081\",\"tools\":[\"memory_store\",\"memory_search\"]}}");

		Map<String, List<String>> parsed = builder.parseCentralizedConfig();
		assertEquals(2, parsed.size());
		assertTrue("Should contain ar-messages", parsed.containsKey("ar-messages"));
		assertTrue("Should contain ar-memory", parsed.containsKey("ar-memory"));
		assertEquals(1, parsed.get("ar-messages").size());
		assertEquals("send_message", parsed.get("ar-messages").get(0));
		assertEquals(2, parsed.get("ar-memory").size());
	}

	@Test(timeout = 30000)
	public void buildAllowedToolsIncludesAll() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setCentralizedMcpConfig(
				"{\"ar-messages\":{\"url\":\"http://localhost:8080\",\"tools\":[\"send_message\"]}}");
		builder.setPushedToolsConfig(
				"{\"ar-memory\":{\"url\":\"http://controller/download\",\"tools\":[\"memory_store\"]}}");

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertNotNull(allowed);
		assertTrue("Should start with base tools", allowed.startsWith("Read,Edit"));
		assertTrue("Should include centralized tool", allowed.contains("mcp__ar-messages__send_message"));
		assertTrue("Should include pushed tool", allowed.contains("mcp__ar-memory__memory_store"));
	}
}
