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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link McpToolDiscovery} covering both the {@code @mcp.tool()}
 * decorator pattern and the {@code @server.list_tools()} handler pattern.
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
	public void missingFileReturnsEmpty() {
		List<String> tools = McpToolDiscovery.discoverToolNames(Path.of("/nonexistent/server.py"));
		assertTrue(tools.isEmpty());
	}

	@Test(timeout = 30000)
	public void nullFileReturnsEmpty() {
		List<String> tools = McpToolDiscovery.discoverToolNames(null);
		assertTrue(tools.isEmpty());
	}
}
