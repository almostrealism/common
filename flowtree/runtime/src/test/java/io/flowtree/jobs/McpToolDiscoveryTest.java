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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link McpToolDiscovery} covering the {@code @mcp.tool()}
 * decorator pattern, the {@code @server.list_tools()} handler pattern,
 * and the dynamic {@code .tool()(fn)} registration pattern.
 */
public class McpToolDiscoveryTest extends TestSuiteBase {

	/**
	 * Verifies that tool names are correctly discovered from the {@code @mcp.tool()} decorator pattern.
	 */
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

	/**
	 * Verifies that tool names are correctly discovered from the {@code @server.list_tools()} handler pattern.
	 */
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

	/**
	 * Verifies that at least the expected minimum number of tools are discovered from the actual ar-test-runner server.
	 */
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

	/**
	 * Verifies that at least the expected minimum number of tools are discovered from the actual ar-docs server.
	 */
	@Test(timeout = 30000)
	public void discoverFromActualDocs() {
		Path serverFile = Path.of("docs/mcp/server.py");
		if (!Files.exists(serverFile)) return;

		List<String> tools = McpToolDiscovery.discoverToolNames(serverFile);
		assertTrue("Expected at least 5 tools from ar-docs, got " + tools.size(),
			tools.size() >= 5);
		assertTrue("Expected search_ar_docs", tools.contains("search_ar_docs"));
	}

	/**
	 * Verifies that at least the expected minimum number of tools are discovered from the actual ar-jmx server.
	 */
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

	/**
	 * Verifies that tool names are correctly discovered from the dynamic {@code .tool()(fn)} registration pattern.
	 */
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

	/**
	 * Verifies that an empty list is returned when the specified server file does not exist.
	 */
	@Test(timeout = 30000)
	public void missingFileReturnsEmpty() {
		List<String> tools = McpToolDiscovery.discoverToolNames(Path.of("/nonexistent/server.py"));
		assertTrue(tools.isEmpty());
	}

	/**
	 * Verifies that an empty list is returned when a null path is provided.
	 */
	@Test(timeout = 30000)
	public void nullFileReturnsEmpty() {
		List<String> tools = McpToolDiscovery.discoverToolNames(null);
		assertTrue(tools.isEmpty());
	}

	/**
	 * Verifies that parameter names are correctly extracted from a multi-line tool function signature.
	 */
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

	/**
	 * Verifies that an empty list is returned when parameters are requested for a tool name that does not exist.
	 */
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
			"agent_options",
			"workstream_list",
			"workstream_get_status",
			"workstream_get_job",
			"workstream_submit_task",
			"workstream_register",
			"workstream_update_config",
			"workspace_update_config",
			"workstream_archive",
			"workstream_unarchive",
			"workstream_delete",
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
			"github_request_copilot_review",
			"github_read_file",
			"github_pr_check_status",
			"tracker_list_projects",
			"tracker_create_project",
			"tracker_update_project",
			"tracker_delete_project",
			"tracker_list_releases",
			"tracker_create_release",
			"tracker_update_release",
			"tracker_delete_release",
			"tracker_create_task",
			"tracker_get_task",
			"tracker_list_tasks",
			"tracker_update_task",
			"tracker_delete_task",
			"tracker_search_tasks",
			"tracker_project_summary",
			"workspace_secret_list_names",
			"workspace_secret_render_file"
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
		assertFalse("workstream_submit_task must NOT declare the dropped legacy model"
			+ " param in its schema; callers use default_phase_config / phase_configs",
			submitParams.contains("model"));
		assertFalse("workstream_submit_task must NOT declare the dropped legacy effort"
			+ " param in its schema; callers use default_phase_config / phase_configs",
			submitParams.contains("effort"));
		assertTrue("workstream_submit_task must declare post_completion_command in signature",
			submitParams.contains("post_completion_command"));
		assertTrue("workstream_submit_task must declare max_deduplication_passes in signature",
			submitParams.contains("max_deduplication_passes"));
		assertTrue("workstream_submit_task must declare max_review_passes in signature"
			+ " so callers can override the default per-job review pass cap",
			submitParams.contains("max_review_passes"));
		assertTrue("workstream_submit_task must declare review_enabled in signature"
			+ " so callers can opt out of the review phase",
			submitParams.contains("review_enabled"));
		assertTrue("workstream_submit_task must declare repo_url in signature so callers can"
			+ " disambiguate target_branch when multiple workstreams share the same branch",
			submitParams.contains("repo_url"));
		assertTrue("workstream_submit_task must declare max_post_completion_passes in signature",
			submitParams.contains("max_post_completion_passes"));
		assertTrue("workstream_submit_task must declare delay_seconds in signature",
			submitParams.contains("delay_seconds"));
		assertFalse("workstream_submit_task must NOT declare the dropped legacy runners"
			+ " param in its schema; callers use phase_configs",
			submitParams.contains("runners"));
		assertFalse("workstream_submit_task must NOT declare the dropped legacy"
			+ " default_runner param in its schema; callers use default_phase_config",
			submitParams.contains("default_runner"));
		assertTrue("workstream_submit_task must declare default_phase_config in"
			+ " signature so per-phase (runner, model, effort) defaults can be set"
			+ " per the UNIFIED_PHASE_CONFIG plan",
			submitParams.contains("default_phase_config"));
		assertTrue("workstream_submit_task must declare phase_configs in signature"
			+ " so per-phase (runner, model, effort) overrides can be passed in",
			submitParams.contains("phase_configs"));
		assertTrue("workstream_submit_task must declare allow_commit_language in signature"
			+ " so callers can opt out of the commit-language linter when needed",
			submitParams.contains("allow_commit_language"));
		assertTrue("workstream_submit_task must declare organizational_placement_enabled in"
			+ " signature so callers can opt in to placement review for pre-merge jobs",
			submitParams.contains("organizational_placement_enabled"));
		assertTrue("workstream_submit_task must declare retrospective_enabled in signature"
			+ " so callers can opt in to the retrospective phase",
			submitParams.contains("retrospective_enabled"));

		List<String> registerParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_register");
		assertFalse("workstream_register must NOT declare the dropped legacy model param",
			registerParams.contains("model"));
		assertFalse("workstream_register must NOT declare the dropped legacy effort param",
			registerParams.contains("effort"));
		assertFalse("workstream_register must NOT declare the dropped legacy runners param;"
			+ " callers use phase_configs",
			registerParams.contains("runners"));
		assertFalse("workstream_register must NOT declare the dropped legacy default_runner"
			+ " param; callers use default_phase_config",
			registerParams.contains("default_runner"));
		assertTrue("workstream_register must declare default_phase_config in signature"
			+ " so per-phase (runner, model, effort) defaults can be set at"
			+ " workstream level",
			registerParams.contains("default_phase_config"));
		assertTrue("workstream_register must declare phase_configs in signature"
			+ " so per-phase (runner, model, effort) overrides can be set at"
			+ " workstream level",
			registerParams.contains("phase_configs"));

		List<String> updateConfigParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_update_config");
		assertFalse("workstream_update_config must NOT declare the dropped legacy model param",
			updateConfigParams.contains("model"));
		assertFalse("workstream_update_config must NOT declare the dropped legacy effort param",
			updateConfigParams.contains("effort"));
		assertFalse("workstream_update_config must NOT declare the dropped legacy runners"
			+ " param; callers use phase_configs",
			updateConfigParams.contains("runners"));
		assertFalse("workstream_update_config must NOT declare the dropped legacy"
			+ " default_runner param; callers use default_phase_config",
			updateConfigParams.contains("default_runner"));
		assertTrue("workstream_update_config must declare default_phase_config in"
			+ " signature so the workstream-level (runner, model, effort)"
			+ " default can be updated",
			updateConfigParams.contains("default_phase_config"));
		assertTrue("workstream_update_config must declare phase_configs in signature"
			+ " so the workstream-level per-phase overrides can be updated",
			updateConfigParams.contains("phase_configs"));

		List<String> workspaceUpdateParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workspace_update_config");
		assertTrue("workspace_update_config must declare workspace_id in signature",
			workspaceUpdateParams.contains("workspace_id"));
		assertTrue("workspace_update_config must declare slack_workspace_id in signature"
			+ " (deprecated alias for workspace_id, retained for backward compatibility)",
			workspaceUpdateParams.contains("slack_workspace_id"));
		assertTrue("workspace_update_config must declare new_id in signature so"
			+ " operators can rename a workspace to a friendlier ID",
			workspaceUpdateParams.contains("new_id"));
		assertTrue("workspace_update_config must declare slack_team_id in signature so"
			+ " operators can set or clear the Slack team binding",
			workspaceUpdateParams.contains("slack_team_id"));
		assertFalse("workspace_update_config must NOT declare the dropped legacy default_runner"
			+ " param; callers use default_phase_config",
			workspaceUpdateParams.contains("default_runner"));
		assertFalse("workspace_update_config must NOT declare the dropped legacy runners"
			+ " param; callers use phase_configs",
			workspaceUpdateParams.contains("runners"));
		assertTrue("workspace_update_config must declare default_phase_config in"
			+ " signature so the workspace-level (runner, model, effort)"
			+ " default can be set",
			workspaceUpdateParams.contains("default_phase_config"));
		assertTrue("workspace_update_config must declare phase_configs in signature"
			+ " so workspace-level per-phase overrides can be set",
			workspaceUpdateParams.contains("phase_configs"));
		assertTrue("workspace_update_config must declare name in signature",
			workspaceUpdateParams.contains("name"));
		assertTrue("workspace_update_config must declare default_channel in signature",
			workspaceUpdateParams.contains("default_channel"));

		assertTrue("workstream_register must declare workspace_id in signature so"
			+ " operators can route a new workstream to a specific workspace",
			registerParams.contains("workspace_id"));
		assertTrue("workstream_register must declare slack_workspace_id in signature"
			+ " (deprecated alias for workspace_id, retained for backward compatibility)",
			registerParams.contains("slack_workspace_id"));

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

		List<String> readFileParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "github_read_file");
		assertTrue("github_read_file must declare path in signature",
			readFileParams.contains("path"));
		assertTrue("github_read_file must declare workstream_id in signature",
			readFileParams.contains("workstream_id"));
		assertTrue("github_read_file must declare repo_url in signature",
			readFileParams.contains("repo_url"));
		assertTrue("github_read_file must declare branch in signature",
			readFileParams.contains("branch"));
		assertTrue("github_read_file must declare ref in signature",
			readFileParams.contains("ref"));

		List<String> prCheckParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "github_pr_check_status");
		assertTrue("github_pr_check_status must declare pr_number in signature",
			prCheckParams.contains("pr_number"));
		assertTrue("github_pr_check_status must declare workstream_id in signature",
			prCheckParams.contains("workstream_id"));
		assertTrue("github_pr_check_status must declare branch in signature",
			prCheckParams.contains("branch"));

		List<String> trackerCreateParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "tracker_create_task");
		assertTrue("tracker_create_task must declare priority in signature",
			trackerCreateParams.contains("priority"));

		List<String> trackerUpdateParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "tracker_update_task");
		assertTrue("tracker_update_task must declare priority in signature",
			trackerUpdateParams.contains("priority"));

		List<String> trackerListParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "tracker_list_tasks");
		assertTrue("tracker_list_tasks must declare sort in signature",
			trackerListParams.contains("sort"));
		assertTrue("tracker_list_tasks must declare order in signature",
			trackerListParams.contains("order"));
		assertTrue("tracker_list_tasks must declare fields in signature",
			trackerListParams.contains("fields"));

		List<String> trackerSearchParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "tracker_search_tasks");
		assertTrue("tracker_search_tasks must declare fields in signature",
			trackerSearchParams.contains("fields"));

		List<String> trackerSummaryParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "tracker_project_summary");
		assertTrue("tracker_project_summary must declare project_id in signature",
			trackerSummaryParams.contains("project_id"));

		List<String> secretListParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workspace_secret_list_names");
		assertTrue("workspace_secret_list_names must declare workstream_id in signature",
			secretListParams.contains("workstream_id"));

		List<String> secretRenderParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workspace_secret_render_file");
		assertTrue("workspace_secret_render_file must declare workstream_id in signature",
			secretRenderParams.contains("workstream_id"));
		assertTrue("workspace_secret_render_file must declare secret_name in signature",
			secretRenderParams.contains("secret_name"));
		assertTrue("workspace_secret_render_file must declare template in signature",
			secretRenderParams.contains("template"));
		assertTrue("workspace_secret_render_file must declare output_path in signature",
			secretRenderParams.contains("output_path"));
		assertTrue("workspace_secret_render_file must declare mode in signature",
			secretRenderParams.contains("mode"));
	}

	/**
	 * Verifies that the {@code workstream_register} and {@code workstream_update_config} tools
	 * declare the {@code required_labels} and {@code dependent_repos} parameters in their signatures.
	 */
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
