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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link McpToolDiscovery} covering the {@code @mcp.tool()}
 * decorator pattern, the {@code @server.list_tools()} handler pattern,
 * and the dynamic {@code .tool()(fn)} registration pattern.
 */
public class McpToolDiscoveryTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void discoverDecoratorPattern() throws IOException {
		Path tempFile = Files.createTempFile("mcp_decorator_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP('test-server')",
				"",
				"@mcp.tool()",
				"async def search_docs(query: str):",
				"    pass",
				"",
				"@mcp.tool()",
				"async def read_module(module: str):",
				"    pass"
			), StandardCharsets.UTF_8);

			List<String> tools = McpToolDiscovery.discoverToolNames(tempFile);
			assertEquals(2, tools.size());
			assertEquals("search_docs", tools.get(0));
			assertEquals("read_module", tools.get(1));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test(timeout = 30000)
	public void discoverListToolsPattern() throws IOException {
		Path tempFile = Files.createTempFile("mcp_list_tools_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server import Server",
				"from mcp.types import Tool",
				"server = Server('test-runner')",
				"",
				"@server.list_tools()",
				"async def list_tools():",
				"    return [",
				"        Tool(",
				"            name=\"start_test_run\",",
				"            description=\"Start a test\",",
				"            inputSchema={}",
				"        ),",
				"        Tool(",
				"            name=\"get_run_status\",",
				"            description=\"Get status\",",
				"            inputSchema={}",
				"        ),",
				"        Tool(",
				"            name=\"cancel_run\",",
				"            description=\"Cancel\",",
				"            inputSchema={}",
				"        )",
				"    ]",
				"",
				"@server.call_tool()",
				"async def call_tool(name: str, arguments: dict):",
				"    pass"
			), StandardCharsets.UTF_8);

			List<String> tools = McpToolDiscovery.discoverToolNames(tempFile);
			assertEquals(3, tools.size());
			assertEquals("start_test_run", tools.get(0));
			assertEquals("get_run_status", tools.get(1));
			assertEquals("cancel_run", tools.get(2));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test(timeout = 30000)
	public void discoverFromActualTestRunner() {
		Path serverFile = Path.of("tools/mcp/test-runner/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> tools = McpToolDiscovery.discoverToolNames(serverFile);
		assertTrue("Expected at least 5 tools from test-runner, got " + tools.size(),
			tools.size() >= 5);
		assertTrue("Expected start_test_run", tools.contains("start_test_run"));
		assertTrue("Expected get_run_status", tools.contains("get_run_status"));
		assertTrue("Expected get_run_output", tools.contains("get_run_output"));
		assertTrue("Expected get_run_failures", tools.contains("get_run_failures"));
		assertTrue("Expected list_runs", tools.contains("list_runs"));
		assertTrue("Expected cancel_run", tools.contains("cancel_run"));
	}

	@Test(timeout = 30000)
	public void discoverFromActualDocs() {
		Path serverFile = Path.of("docs/mcp/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> tools = McpToolDiscovery.discoverToolNames(serverFile);
		assertTrue("Expected at least 5 tools from ar-docs, got " + tools.size(),
			tools.size() >= 5);
		assertTrue("Expected search_ar_docs", tools.contains("search_ar_docs"));
	}

	@Test(timeout = 30000)
	public void discoverFromActualJmx() {
		Path serverFile = Path.of("tools/mcp/jmx/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> tools = McpToolDiscovery.discoverToolNames(serverFile);
		assertTrue("Expected at least 5 tools from ar-jmx, got " + tools.size(),
			tools.size() >= 5);
		assertTrue("Expected attach_to_run", tools.contains("attach_to_run"));
		assertTrue("Expected get_heap_summary", tools.contains("get_heap_summary"));
	}

	@Test(timeout = 30000)
	public void discoverDynamicRegistrationPattern() throws IOException {
		Path tempFile = Files.createTempFile("mcp_dynamic_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"",
				"def _get_mcp():",
				"    return FastMCP('test-server')",
				"",
				"def tool_alpha(query: str):",
				"    pass",
				"",
				"def tool_beta(item: str):",
				"    pass",
				"",
				"def tool_gamma():",
				"    pass",
				"",
				"def _register_mcp_tools():",
				"    server = _get_mcp()",
				"    for fn in [",
				"        tool_alpha,",
				"        tool_beta,",
				"        tool_gamma,",
				"    ]:",
				"        server.tool()(fn)",
				"",
				"if __name__ == '__main__':",
				"    _register_mcp_tools()",
				"    mcp = _get_mcp()",
				"    mcp.run()"
			), StandardCharsets.UTF_8);

			List<String> tools = McpToolDiscovery.discoverToolNames(tempFile);
			assertEquals(3, tools.size());
			assertEquals("tool_alpha", tools.get(0));
			assertEquals("tool_beta", tools.get(1));
			assertEquals("tool_gamma", tools.get(2));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test(timeout = 30000)
	public void discoverFromActualGitHub() {
		Path serverFile = Path.of("tools/mcp/github/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> tools = McpToolDiscovery.discoverToolNames(serverFile);
		assertTrue("Expected at least 4 tools from ar-github, got " + tools.size(),
			tools.size() >= 4);
		assertTrue("Expected github_pr_find", tools.contains("github_pr_find"));
		assertTrue("Expected github_pr_review_comments", tools.contains("github_pr_review_comments"));
		assertTrue("Expected github_pr_conversation", tools.contains("github_pr_conversation"));
		assertTrue("Expected github_pr_reply", tools.contains("github_pr_reply"));
	}

	@Test(timeout = 30000)
	public void missingFileReturnsEmpty() {
		List<String> tools = McpToolDiscovery.discoverToolNames(Path.of("/nonexistent/server.py"));
		assertTrue(tools.isEmpty());
	}

	@Test(timeout = 30000)
	public void nullFileReturnsEmpty() {
		List<String> tools = McpToolDiscovery.discoverToolNames(null);
		assertTrue(tools.isEmpty());
	}

	@Test(timeout = 30000)
	public void discoverToolParametersFromSignature() throws IOException {
		Path tempFile = Files.createTempFile("mcp_params_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP('test-server')",
				"",
				"@mcp.tool()",
				"def register_item(",
				"    name: str,",
				"    label: str = \"\",",
				"    tags: str = \"\",",
				") -> dict:",
				"    pass"
			), StandardCharsets.UTF_8);

			List<String> params = McpToolDiscovery.discoverToolParameters(tempFile, "register_item");
			assertEquals(3, params.size());
			assertEquals("name", params.get(0));
			assertEquals("label", params.get(1));
			assertEquals("tags", params.get(2));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test(timeout = 30000)
	public void discoverToolParametersMissingToolReturnsEmpty() throws IOException {
		Path tempFile = Files.createTempFile("mcp_params_missing_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP('test-server')",
				"",
				"@mcp.tool()",
				"def other_tool(x: str) -> dict:",
				"    pass"
			), StandardCharsets.UTF_8);

			List<String> params = McpToolDiscovery.discoverToolParameters(tempFile, "nonexistent_tool");
			assertTrue(params.isEmpty());
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	/**
	 * Verifies that every tool expected in the ar-manager MCP server is actually
	 * registered with the {@code @mcp.tool()} decorator in {@code server.py}.
	 *
	 * <p>If a new tool is added to the expected set but its function is missing
	 * the decorator, this test fails with a clear list of which tools are missing
	 * from the registry.</p>
	 *
	 * <p>History: new tools were repeatedly added as plain functions without the
	 * {@code @mcp.tool()} decorator, making them invisible to MCP clients.
	 * This test catches that mistake before code is merged.</p>
	 */
	@Test(timeout = 30000)
	public void managerAllExpectedToolsAreRegisteredInServerPy() {
		Path serverFile = Path.of("tools/mcp/manager/server.py");
		if (!Files.exists(serverFile)) return;

		Set<String> expected = new HashSet<>(Arrays.asList(
			"controller_health",
			"controller_update_config",
			"workstream_list",
			"workstream_get_status",
			"workstream_get_job",
			"workstream_submit_task",
			"workstream_register",
			"workstream_update_config",
			"project_create_branch",
			"project_verify_branch",
			"project_commit_plan",
			"project_read_plan",
			"memory_recall",
			"workstream_context",
			"memory_store",
			"send_message",
			"github_pr_find",
			"github_pr_review_comments",
			"github_pr_conversation",
			"github_pr_reply",
			"github_list_open_prs",
			"github_create_pr",
			"github_request_copilot_review"
		));

		List<String> discovered = McpToolDiscovery.discoverToolNames(serverFile);
		Set<String> discoveredSet = new HashSet<>(discovered);

		Set<String> missing = new HashSet<>(expected);
		missing.removeAll(discoveredSet);

		assertTrue(
			"The following tools are expected in ar-manager but are NOT decorated with " +
				"@mcp.tool() in server.py (they will be invisible to MCP clients): " + missing,
			missing.isEmpty()
		);
	}

	/**
	 * Verifies that key parameters are properly declared in the function signatures
	 * of ar-manager MCP tools.
	 *
	 * <p>FastMCP generates the MCP tool schema from the Python function signature,
	 * including type hints and defaults. If a parameter is handled inside the function
	 * body (e.g. extracted from {@code **kwargs}) rather than declared in the
	 * signature, it will be absent from the schema and invisible to MCP clients.</p>
	 *
	 * <p>This test catches that pattern by asserting that important parameters appear
	 * in the function signature as discovered by {@link McpToolDiscovery}.</p>
	 */
	@Test(timeout = 30000)
	public void managerToolParametersAreProperlyDeclaredInSignatures() {
		Path serverFile = Path.of("tools/mcp/manager/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> copilotParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "github_request_copilot_review");
		assertTrue("github_request_copilot_review must declare pr_number in signature",
			copilotParams.contains("pr_number"));
		assertTrue("github_request_copilot_review must declare workstream_id in signature",
			copilotParams.contains("workstream_id"));
		assertTrue("github_request_copilot_review must declare branch in signature",
			copilotParams.contains("branch"));

		List<String> createPrParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "github_create_pr");
		assertTrue("github_create_pr must declare title in signature",
			createPrParams.contains("title"));
		assertTrue("github_create_pr must declare body in signature",
			createPrParams.contains("body"));
		assertTrue("github_create_pr must declare request_copilot_review in signature",
			createPrParams.contains("request_copilot_review"));

		List<String> submitParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_submit_task");
		assertTrue("workstream_submit_task must declare prompt in signature",
			submitParams.contains("prompt"));
		assertTrue("workstream_submit_task must declare workstream_id in signature",
			submitParams.contains("workstream_id"));
		assertTrue("workstream_submit_task must declare model in signature",
			submitParams.contains("model"));
		assertTrue("workstream_submit_task must declare effort in signature",
			submitParams.contains("effort"));

		List<String> registerParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_register");
		assertTrue("workstream_register must declare model in signature",
			registerParams.contains("model"));
		assertTrue("workstream_register must declare effort in signature",
			registerParams.contains("effort"));

		List<String> updateConfigParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_update_config");
		assertTrue("workstream_update_config must declare model in signature",
			updateConfigParams.contains("model"));
		assertTrue("workstream_update_config must declare effort in signature",
			updateConfigParams.contains("effort"));

		List<String> memoryRecallParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "memory_recall");
		assertTrue("memory_recall must declare query in signature",
			memoryRecallParams.contains("query"));

		List<String> memoryStoreParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "memory_store");
		assertTrue("memory_store must declare content in signature",
			memoryStoreParams.contains("content"));

		List<String> sendMessageParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "send_message");
		assertTrue("send_message must declare text in signature",
			sendMessageParams.contains("text"));
		assertTrue("send_message must declare activity in signature",
			sendMessageParams.contains("activity"));

		List<String> workstreamContextParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_context");
		assertTrue("workstream_context must declare include_activities in signature",
			workstreamContextParams.contains("include_activities"));
	}

	@Test(timeout = 30000)
	public void managerRegisterAndUpdateConfigHaveRequiredLabelsAndDependentRepos() {
		Path serverFile = Path.of("tools/mcp/manager/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> registerParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_register");
		assertTrue("workstream_register must declare required_labels parameter",
			registerParams.contains("required_labels"));
		assertTrue("workstream_register must declare dependent_repos parameter",
			registerParams.contains("dependent_repos"));

		List<String> updateParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_update_config");
		assertTrue("workstream_update_config must declare required_labels parameter",
			updateParams.contains("required_labels"));
		assertTrue("workstream_update_config must declare dependent_repos parameter",
			updateParams.contains("dependent_repos"));
	}
}
