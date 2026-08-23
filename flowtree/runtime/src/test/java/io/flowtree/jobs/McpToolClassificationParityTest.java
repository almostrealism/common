/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Verifies that the tool classification the MCP server reports matches the one
 * the harness enforces.
 *
 * <p>Which ar-manager tools an agent may call is decided here, in
 * {@link McpConfigBuilder}, and reported by the server's
 * {@code workstream_introspect} tool from its own copy in
 * {@code tools/mcp/manager/tool_capabilities.py}. The two cannot share a
 * definition across languages, so the copy can drift — and a report that
 * disagrees with the enforcement is worse than no report, because an operator
 * consults it precisely when something has been denied and they do not know
 * why.</p>
 *
 * <p>This test is the compensating control. It reads both files and fails when
 * the sets differ, which is why the Python side is written as plain literals
 * rather than assembled at runtime.</p>
 */
public class McpToolClassificationParityTest implements TestFeatures {

	/** Matches a quoted tool name in either source language. */
	private static final Pattern ENTRY = Pattern.compile("\"([a-z_]+)\"");

	/** The tools agents get by default must agree across both sources. */
	@Test(timeout = 30000)
	public void grantedToolsMatch() {
		assertSetsMatch("AR_MANAGER_TOOL_NAMES", "GRANTED_TOOLS");
	}

	/** The tools withheld from agents must agree across both sources. */
	@Test(timeout = 30000)
	public void excludedToolsMatch() {
		assertSetsMatch("EXCLUDED_AR_MANAGER_TOOLS", "EXCLUDED_TOOLS");
	}

	/** The dispatch-capable override set must agree across both sources. */
	@Test(timeout = 30000)
	public void dispatchToolsMatch() {
		assertSetsMatch("DISPATCH_AR_MANAGER_TOOLS", "DISPATCH_TOOLS");
	}

	/**
	 * Asserts a Java set and its Python counterpart hold the same tool names.
	 *
	 * @param javaName   the Java field name in {@link McpConfigBuilder}
	 * @param pythonName the tuple name in {@code tool_capabilities.py}
	 */
	private void assertSetsMatch(String javaName, String pythonName) {
		Set<String> java = javaSet(javaName);
		Set<String> python = pythonSet(pythonName);

		assertFalse("Could not read " + javaName + " from McpConfigBuilder.java;"
			+ " the parity guard cannot run and would otherwise pass by comparing"
			+ " two empty sets", java.isEmpty());
		assertFalse("Could not read " + pythonName + " from tool_capabilities.py;"
			+ " the parity guard cannot run and would otherwise pass by comparing"
			+ " two empty sets", python.isEmpty());

		Set<String> onlyJava = new TreeSet<>(java);
		onlyJava.removeAll(python);
		Set<String> onlyPython = new TreeSet<>(python);
		onlyPython.removeAll(java);

		assertTrue("Tool classification has drifted between the harness and the"
			+ " server's report. " + javaName + " (McpConfigBuilder.java) and "
			+ pythonName + " (tools/mcp/manager/tool_capabilities.py) must hold"
			+ " the same names, or workstream_introspect will describe a"
			+ " permission set the harness does not enforce."
			+ " Only in Java: " + onlyJava + ". Only in Python: " + onlyPython + ".",
			onlyJava.isEmpty() && onlyPython.isEmpty());
	}

	/**
	 * Extracts a tool-name set from the Java source rather than the loaded
	 * class, so both sides are read the same way and a name is compared as it
	 * is written.
	 *
	 * @param fieldName the set field to read
	 * @return the tool names, empty if the field cannot be located
	 */
	private static Set<String> javaSet(String fieldName) {
		Path source = locate("flowtree/runtime/src/main/java/io/flowtree/jobs/"
			+ "McpConfigBuilder.java");
		String text = read(source);
		int start = text.indexOf(fieldName + " = Collections.unmodifiableSet(");
		if (start < 0) return new LinkedHashSet<>();
		int end = text.indexOf("))", start);
		if (end < 0) return new LinkedHashSet<>();
		return names(text.substring(start, end));
	}

	/**
	 * Extracts a tool-name tuple from the Python source.
	 *
	 * @param tupleName the tuple to read
	 * @return the tool names, empty if the tuple cannot be located
	 */
	private static Set<String> pythonSet(String tupleName) {
		Path source = locate("tools/mcp/manager/tool_capabilities.py");
		String text = read(source);
		int start = text.indexOf(tupleName + " = (");
		if (start < 0) return new LinkedHashSet<>();
		int end = text.indexOf(")", start);
		if (end < 0) return new LinkedHashSet<>();
		return names(text.substring(start, end));
	}

	/**
	 * Collects every quoted tool name in a source fragment.
	 *
	 * @param block the source text to scan
	 * @return the names found, in source order
	 */
	private static Set<String> names(String block) {
		Set<String> found = new LinkedHashSet<>();
		Matcher m = ENTRY.matcher(block);
		while (m.find()) found.add(m.group(1));
		return found;
	}

	/**
	 * Reads a source file, treating an unreadable one as empty so the
	 * emptiness assertions above report it rather than a stack trace.
	 *
	 * @param source the file to read; may be {@code null}
	 * @return the contents, or the empty string
	 */
	private static String read(Path source) {
		if (source == null) return "";
		try {
			return Files.readString(source, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Walks up from the working directory to find a repository-relative file,
	 * tolerating either the module basedir or the repository root.
	 *
	 * @param relative the repository-relative path
	 * @return the resolved file, or {@code null} when not found
	 */
	private static Path locate(String relative) {
		Path cwd = Path.of("").toAbsolutePath();
		for (int i = 0; i < 5 && cwd != null; i++) {
			Path candidate = cwd.resolve(relative);
			if (Files.isRegularFile(candidate)) return candidate;
			cwd = cwd.getParent();
		}
		return null;
	}
}
