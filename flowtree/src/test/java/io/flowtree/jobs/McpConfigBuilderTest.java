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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
	public void pushedToolsEmitStdioEntryAndAllowedTools() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setPushedToolsConfig(
			"{\"ar-secrets\":{\"url\":\"http://0.0.0.0:7780/api/tools/ar-secrets\","
				+ "\"tools\":[\"secret_list_names\",\"secret_render_file\"]}}");

		String config = builder.buildMcpConfig();
		assertTrue("Config must register ar-secrets server", config.contains("\"ar-secrets\""));
		assertTrue("Config must point at downloaded server.py",
			config.contains(".flowtree/tools/mcp/ar-secrets/server.py"));
		assertTrue("Config must use stdio command", config.contains("\"command\":\"python3\""));

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertTrue("Allowlist must include secret_list_names",
			allowed.contains("mcp__ar-secrets__secret_list_names"));
		assertTrue("Allowlist must include secret_render_file",
			allowed.contains("mcp__ar-secrets__secret_render_file"));
	}

	@Test(timeout = 30000)
	public void pushedToolEntryOverridesProjectMcpJsonOfSameName() throws IOException {
		Path tempDir = Files.createTempDirectory("mcp_pushed_dedup_");
		try {
			Path mcpJson = tempDir.resolve(".mcp.json");
			Files.writeString(mcpJson, "{\"mcpServers\":{\"ar-secrets\":{"
				+ "\"command\":\"python3\",\"args\":[\"tools/mcp/secrets/server.py\"]}}}",
				StandardCharsets.UTF_8);

			McpConfigBuilder builder = new McpConfigBuilder();
			builder.setWorkingDirectory(tempDir);
			builder.setPushedToolsConfig(
				"{\"ar-secrets\":{\"url\":\"http://x/api/tools/ar-secrets\",\"tools\":[]}}");

			String config = builder.buildMcpConfig();
			// Pushed entry wins: arg must point at the downloaded path,
			// not the project-relative tools/mcp/secrets/server.py.
			assertTrue("Pushed-tool path must win",
				config.contains(".flowtree/tools/mcp/ar-secrets/server.py"));
			assertFalse("Project-relative path must not appear",
				config.contains("\"tools/mcp/secrets/server.py\""));
		} finally {
			Files.walk(tempDir).sorted(Comparator.reverseOrder())
				.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
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
		// Workspace secret tools moved to the in-container ar-secrets stdio
		// MCP server — they are now in EXCLUDED_AR_MANAGER_TOOLS and must not
		// appear in the ar-manager allowlist passed to agents.
		assertFalse("Must not include workspace_secret_list_names (moved to ar-secrets)",
			allowed.contains("mcp__ar-manager__workspace_secret_list_names"));
		assertFalse("Must not include workspace_secret_render_file (moved to ar-secrets)",
			allowed.contains("mcp__ar-manager__workspace_secret_render_file"));
	}

	/**
	 * Verifies that every {@code @mcp.tool()} function registered in
	 * {@code tools/mcp/manager/server.py} is accounted for either in
	 * {@link McpConfigBuilder#AR_MANAGER_TOOL_NAMES} (granted to agents)
	 * or in {@link McpConfigBuilder#EXCLUDED_AR_MANAGER_TOOLS}
	 * (deliberately excluded).
	 *
	 * <p>This catches the recurring failure mode where a contributor adds
	 * a new tool to {@code server.py} but forgets to update the
	 * {@code --allowedTools} allowlist, causing the Claude Code harness
	 * to silently block the tool. When this test fails it lists the
	 * unaccounted tools and instructs the contributor to add each one to
	 * exactly one of the two sets.</p>
	 */
	@Test(timeout = 30000)
	public void allowlistCoversEveryArManagerTool() {
		Path serverFile = locateManagerServerPy();
		assertNotNull(
			"Could not locate tools/mcp/manager/server.py from working directory " +
				Path.of("").toAbsolutePath() +
				". The allowlist coverage check cannot run without it.",
			serverFile);

		List<String> discovered = McpToolDiscovery.discoverToolNames(serverFile);
		assertFalse("Should discover at least one ar-manager tool", discovered.isEmpty());

		Set<String> classified = new HashSet<>(McpConfigBuilder.AR_MANAGER_TOOL_NAMES);
		classified.addAll(McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS);

		Set<String> unaccounted = new TreeSet<>();
		for (String tool : discovered) {
			if (!classified.contains(tool)) {
				unaccounted.add(tool);
			}
		}

		assertTrue(
			"The following ar-manager tools exist in server.py but are in neither " +
				"McpConfigBuilder.AR_MANAGER_TOOL_NAMES (the agent allowlist) nor " +
				"McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS (the deliberate-exclusion set). " +
				"Add each one to exactly one of those sets so coding agents either " +
				"have access or are explicitly denied access: " + unaccounted,
			unaccounted.isEmpty()
		);
	}

	/**
	 * Walks up from the current working directory looking for
	 * {@code tools/mcp/manager/server.py}. Maven Surefire defaults the
	 * working directory to the module's basedir ({@code flowtree/}) so a
	 * single relative path like {@code tools/...} only resolves when the
	 * test is launched from the project root.
	 *
	 * @return the resolved path, or {@code null} if not found within five
	 *         levels of ancestor directories
	 */
	private static Path locateManagerServerPy() {
		Path cwd = Path.of("").toAbsolutePath();
		for (int i = 0; i < 5 && cwd != null; i++) {
			Path candidate = cwd.resolve("tools/mcp/manager/server.py");
			if (Files.exists(candidate)) return candidate;
			cwd = cwd.getParent();
		}
		return null;
	}

	/**
	 * Verifies that {@link McpConfigBuilder#AR_MANAGER_TOOL_NAMES} and
	 * {@link McpConfigBuilder#EXCLUDED_AR_MANAGER_TOOLS} are disjoint.
	 * A tool in both sets would be ambiguous about whether agents have
	 * access to it.
	 */
	@Test(timeout = 30000)
	public void allowlistAndExclusionsAreDisjoint() {
		Set<String> intersection = new TreeSet<>(McpConfigBuilder.AR_MANAGER_TOOL_NAMES);
		intersection.retainAll(McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS);
		assertTrue(
			"Tools must appear in exactly one of AR_MANAGER_TOOL_NAMES or " +
				"EXCLUDED_AR_MANAGER_TOOLS, never both: " + intersection,
			intersection.isEmpty()
		);
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
