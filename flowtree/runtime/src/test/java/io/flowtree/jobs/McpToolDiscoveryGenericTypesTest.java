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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the bracket-depth-aware parameter-default detection in
 * {@link McpToolDiscovery#isOptionalToolParameter}.
 *
 * <p>The default-detection helper historically used a regex character
 * class {@code [^=,]+} to match the type annotation between the colon
 * and the equals sign. That regex stopped at any comma, so a parameter
 * annotated with a parameterized generic type containing a comma —
 * for example {@code Dict[str, str]}, {@code Tuple[int, str]}, or
 * {@code Optional[Union[str, int]]} — was incorrectly reported as
 * required even when a default was present. The fix replaces the
 * regex with a two-step scan that anchors on the parameter token and
 * then walks forward to the first top-level {@code =} while tracking
 * bracket depth, so commas inside {@code [ ... ]} generic argument
 * lists (and inside {@code ( ... )} calls) are not mistaken for the
 * boundary between parameters.</p>
 *
 * <p>These tests cover signatures that exercise the previously-broken
 * pattern. They are a new test file (rather than additions to
 * {@code McpToolDiscoveryTest}) so this branch does not modify the
 * base-branch {@code McpToolDiscoveryTest.java}, which the
 * agent-commit-validation rule protects against test-hiding.</p>
 */
public class McpToolDiscoveryGenericTypesTest extends TestSuiteBase {

	/**
	 * A parameter with a {@code Dict[str, str]} annotation and a
	 * default value must be reported as optional. The previous
	 * {@code [^=,]+} regex stopped at the comma between {@code str}
	 * and {@code str}, missed the equals sign, and returned
	 * {@code false}. The bracket-depth-aware scan walks past the
	 * comma inside the brackets and finds the {@code =} after the
	 * closing {@code ]}.
	 */
	@Test(timeout = 30000)
	public void dictAnnotationWithDefaultIsOptional() throws IOException {
		Path tempFile = Files.createTempFile("mcp_generic_dict_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP(\"test\")",
				"",
				"@mcp.tool()",
				"def example_tool(",
				"    required_text: str,",
				"    metadata: Dict[str, str] = {},",
				"    tags: Dict[str, int] = None,",
				") -> dict:",
				"    return {}",
				""), StandardCharsets.UTF_8);

			assertFalse("required_text has no default — must be required",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "required_text"));
			assertTrue("metadata defaults to {} — must be optional despite Dict[str, str] type",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "metadata"));
			assertTrue("tags defaults to None — must be optional despite Dict[str, int] type",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "tags"));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	/**
	 * A parameter with an {@code Optional[Union[str, int]]} annotation
	 * (multiple commas nested inside brackets) and a default must be
	 * reported as optional. The previous regex would have stopped at
	 * the first comma it saw. The bracket-depth-aware scan tracks
	 * depth across the nested brackets and only treats a comma as
	 * a parameter boundary when the depth is zero.
	 */
	@Test(timeout = 30000)
	public void nestedGenericAnnotationWithDefaultIsOptional() throws IOException {
		Path tempFile = Files.createTempFile("mcp_generic_optional_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP(\"test\")",
				"",
				"@mcp.tool()",
				"def example_tool(",
				"    value: Optional[Union[str, int]] = None,",
				"    pairs: List[Tuple[int, str]] = [],",
				"    mapping: Dict[str, List[int]] = {},",
				") -> dict:",
				"    return {}",
				""), StandardCharsets.UTF_8);

			assertTrue("value defaults to None — must be optional despite nested generics",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "value"));
			assertTrue("pairs defaults to [] — must be optional despite List[Tuple[int, str]] type",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "pairs"));
			assertTrue("mapping defaults to {} — must be optional despite Dict[str, List[int]] type",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "mapping"));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	/**
	 * A parameter that is required (no default) must be reported as
	 * required even when its annotation contains commas. The
	 * bracket-depth-aware scan returns {@code false} when no
	 * top-level {@code =} is found between the parameter token and
	 * the next comma at depth zero.
	 */
	@Test(timeout = 30000)
	public void requiredParameterWithGenericAnnotationIsRequired() throws IOException {
		Path tempFile = Files.createTempFile("mcp_generic_required_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP(\"test\")",
				"",
				"@mcp.tool()",
				"def example_tool(",
				"    required_dict: Dict[str, str],",
				"    required_pairs: List[Tuple[int, str]],",
				"    defaulted: Dict[str, int] = {},",
				") -> dict:",
				"    return {}",
				""), StandardCharsets.UTF_8);

			assertFalse("required_dict has no default — must be required even with Dict[str, str]",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "required_dict"));
			assertFalse("required_pairs has no default — must be required even with List[Tuple[int, str]]",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "required_pairs"));
			assertTrue("defaulted has a default — must be optional",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "example_tool", "defaulted"));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	/**
	 * The single-line signature path must also handle parameterized
	 * generics. A compact {@code def tool(...)} that declares a
	 * {@code Dict[str, str]} parameter with a default must report
	 * that parameter as optional.
	 */
	@Test(timeout = 30000)
	public void singleLineSignatureWithGenericDefaultIsOptional() throws IOException {
		Path tempFile = Files.createTempFile("mcp_generic_inline_", ".py");
		try {
			Files.writeString(tempFile, String.join("\n",
				"from mcp.server.fastmcp import FastMCP",
				"mcp = FastMCP(\"test\")",
				"",
				"@mcp.tool()",
				"def inline_tool(required: str, metadata: Dict[str, str] = {}) -> dict:",
				"    return {}",
				""), StandardCharsets.UTF_8);

			assertFalse("required has no default — must be required",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "inline_tool", "required"));
			assertTrue("metadata defaults to {} — must be optional in single-line signature too",
				McpToolDiscovery.isOptionalToolParameter(
					tempFile, "inline_tool", "metadata"));
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}
}
