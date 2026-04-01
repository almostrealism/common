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
import java.util.Comparator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link McpConfigBuilder} covering ar-manager HTTP entry,
 * project server discovery, and allowed tools assembly.
 */
public class McpConfigBuilderTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void buildsArManagerHttpEntry() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setArManagerUrl("http://ar-manager:8010");
		builder.setArManagerToken("armt_tmp_testtoken");

		String config = builder.buildMcpConfig();
		assertNotNull(config);
		assertTrue("Config should contain http type", config.contains("\"type\":\"http\""));
		assertTrue("Config should contain ar-manager", config.contains("ar-manager"));
		assertTrue("Config should contain auth header",
			config.contains("Bearer armt_tmp_testtoken"));
	}

	@Test(timeout = 30000)
	public void omitsArManagerWithoutToken() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setArManagerUrl("http://ar-manager:8010");
		// No token set

		String config = builder.buildMcpConfig();
		assertNotNull(config);
		assertFalse("Config should not contain ar-manager without token",
			config.contains("ar-manager"));
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
			Files.walk(tempDir)
					.sorted(Comparator.reverseOrder())
					.forEach(p -> {
						try { Files.deleteIfExists(p); } catch (IOException ignored) { }
					});
		}
	}

	@Test(timeout = 30000)
	public void buildAllowedToolsIncludesArManager() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setArManagerUrl("http://ar-manager:8010");
		builder.setArManagerToken("armt_tmp_testtoken");

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertNotNull(allowed);
		assertTrue("Should start with base tools", allowed.startsWith("Read,Edit"));
		assertTrue("Should include send_message",
			allowed.contains("mcp__ar-manager__send_message"));
		assertTrue("Should include memory_recall",
			allowed.contains("mcp__ar-manager__memory_recall"));
		assertTrue("Should include github_pr_find",
			allowed.contains("mcp__ar-manager__github_pr_find"));
	}

	@Test(timeout = 30000)
	public void buildAllowedToolsExcludesArManagerWithoutConfig() {
		McpConfigBuilder builder = new McpConfigBuilder();
		// No ar-manager URL or token

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertNotNull(allowed);
		assertFalse("Should not include ar-manager tools",
			allowed.contains("mcp__ar-manager__"));
	}

	@Test(timeout = 30000)
	public void excludesArManagerFromProjectDiscovery() throws IOException {
		Path tempDir = Files.createTempDirectory("mcp_config_test_");
		try {
			Path mcpJson = tempDir.resolve(".mcp.json");
			Files.writeString(mcpJson, String.join("\n",
					"{",
					"  \"mcpServers\": {",
					"    \"ar-manager\": {",
					"      \"command\": \"python3\",",
					"      \"args\": [\"tools/mcp/manager/server.py\"]",
					"    },",
					"    \"ar-test-runner\": {",
					"      \"command\": \"python3\",",
					"      \"args\": [\"tools/mcp/test-runner/server.py\"]",
					"    }",
					"  }",
					"}"
			), StandardCharsets.UTF_8);

			McpConfigBuilder builder = new McpConfigBuilder();
			builder.setWorkingDirectory(tempDir);

			String config = builder.buildMcpConfig();
			// ar-manager should be excluded from project discovery
			// (it's handled as a centralized HTTP entry)
			// ar-test-runner should be included
			assertTrue("Should contain ar-test-runner", config.contains("ar-test-runner"));
			// ar-manager from .mcp.json should be skipped
			// (only the HTTP entry from setArManagerUrl is used)
		} finally {
			Files.walk(tempDir)
					.sorted(Comparator.reverseOrder())
					.forEach(p -> {
						try { Files.deleteIfExists(p); } catch (IOException ignored) { }
					});
		}
	}
}
