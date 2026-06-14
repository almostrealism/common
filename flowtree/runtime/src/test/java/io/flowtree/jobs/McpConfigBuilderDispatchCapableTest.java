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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the per-workstream {@code dispatchCapable} flag's effect on
 * {@link McpConfigBuilder#buildAllowedTools(String)}.
 *
 * <p>These tests live in a new file because
 * {@link McpConfigBuilderTest} is on the base branch and the agent
 * write-lock prevents editing it. The {@code allowlistCoversEveryArManagerTool}
 * and {@code allowlistAndExclusionsAreDisjoint} invariant tests in
 * {@code McpConfigBuilderTest} continue to pass after this change:
 * {@code workstream_register} and {@code workstream_update_config} are
 * still classified in {@link McpConfigBuilder#EXCLUDED_AR_MANAGER_TOOLS}
 * as the base default, and the per-workstream dispatch override happens
 * at CSV-assembly time, not at the classification step. The two
 * questions are independent: classification is "is this tool in the
 * default allowlist or the default deny-list?", the override is "should
 * an opt-in workstream see this tool's allow entry on top of the
 * base?".</p>
 */
public class McpConfigBuilderDispatchCapableTest extends TestSuiteBase {

	/**
	 * Default behaviour (no dispatch): the CSV contains every entry in
	 * {@link McpConfigBuilder#AR_MANAGER_TOOL_NAMES} and does NOT contain
	 * either of the dispatch tool names, even though ar-manager is
	 * configured. This is the base case the existing
	 * {@code buildAllowedToolsIncludesArManager} test in
	 * {@code McpConfigBuilderTest} covers; this test pins the same
	 * behaviour for the dispatch overlay specifically.
	 */
	@Test(timeout = 30000)
	public void dispatchOffByDefaultExcludesDispatchTools() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setArManagerUrl("http://ar-manager:8010");
		builder.setArManagerToken("armt_tmp_testtoken");
		// setDispatchCapable is NOT called: default false.

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertFalse("dispatchCapable=false must NOT include workstream_register",
			allowed.contains("mcp__ar-manager__workstream_register"));
		assertFalse("dispatchCapable=false must NOT include workstream_update_config",
			allowed.contains("mcp__ar-manager__workstream_update_config"));
	}

	/**
	 * Opt-in (dispatch): the CSV re-adds the dispatch tool entries on
	 * top of the base ar-manager allowlist. The base entries are
	 * unchanged.
	 */
	@Test(timeout = 30000)
	public void dispatchOnAppendsDispatchToolsToBaseAllowlist() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setArManagerUrl("http://ar-manager:8010");
		builder.setArManagerToken("armt_tmp_testtoken");
		builder.setDispatchCapable(true);

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertTrue("dispatchCapable=true must include workstream_register",
			allowed.contains("mcp__ar-manager__workstream_register"));
		assertTrue("dispatchCapable=true must include workstream_update_config",
			allowed.contains("mcp__ar-manager__workstream_update_config"));
		// Base allowlist entries are preserved alongside the dispatch overlay.
		assertTrue("dispatchCapable=true must still include send_message",
			allowed.contains("mcp__ar-manager__send_message"));
		assertTrue("dispatchCapable=true must still include memory_recall",
			allowed.contains("mcp__ar-manager__memory_recall"));
	}

	/**
	 * Toggling dispatch back to false after a true call removes the
	 * dispatch entries. The setter is not a sticky-once-on switch.
	 */
	@Test(timeout = 30000)
	public void dispatchToggleOffRemovesDispatchTools() {
		McpConfigBuilder builder = new McpConfigBuilder();
		builder.setArManagerUrl("http://ar-manager:8010");
		builder.setArManagerToken("armt_tmp_testtoken");
		builder.setDispatchCapable(true);
		String on = builder.buildAllowedTools("Read,Edit");
		assertTrue(on.contains("mcp__ar-manager__workstream_register"));

		builder.setDispatchCapable(false);
		String off = builder.buildAllowedTools("Read,Edit");
		assertFalse("dispatchCapable=false must remove workstream_register",
			off.contains("mcp__ar-manager__workstream_register"));
		assertFalse("dispatchCapable=false must remove workstream_update_config",
			off.contains("mcp__ar-manager__workstream_update_config"));
	}

	/**
	 * The dispatch overlay is only meaningful when ar-manager is
	 * configured. Without a URL/token the CSV is empty of ar-manager
	 * entries regardless of the flag.
	 */
	@Test(timeout = 30000)
	public void dispatchOnWithoutArManagerDoesNotInjectTools() {
		McpConfigBuilder builder = new McpConfigBuilder();
		// No ar-manager URL / token.
		builder.setDispatchCapable(true);

		String allowed = builder.buildAllowedTools("Read,Edit");
		assertFalse(
			"Without ar-manager configured, dispatchCapable=true must NOT add tools",
			allowed.contains("mcp__ar-manager__workstream_register"));
	}

	/**
	 * The dispatch set is exactly the union of
	 * {@link McpConfigBuilder#DISPATCH_AR_MANAGER_TOOLS} — never larger.
	 * This is the tripwire against a future contributor widening the
	 * set to include destructive tools (archive / delete / secrets /
	 * etc.). The intent is to keep dispatch narrow: register a child
	 * and update a child's config (so the orchestrator can wire
	 * {@code completion_listeners}); nothing more.
	 */
	@Test(timeout = 30000)
	public void dispatchSetIsExactlyTheNarrowOptInList() {
		assertEquals(
			"DISPATCH_AR_MANAGER_TOOLS must be the narrow opt-in set: only"
				+ " register and update. A larger set would re-introduce the"
				+ " admin / destructive power the flag is designed to gate.",
			2, McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS.size());
		assertTrue(McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS
			.contains("workstream_register"));
		assertTrue(McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS
			.contains("workstream_update_config"));
		// Destructive / admin tools must NEVER appear in the dispatch set.
		assertFalse("Destructive workstream_delete must stay excluded even"
			+ " for dispatch-capable workstreams",
			McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS
				.contains("workstream_delete"));
		assertFalse("workstream_archive must stay excluded for dispatch-capable"
			+ " workstreams",
			McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS
				.contains("workstream_archive"));
		assertFalse("workspace_update_config must stay excluded for"
			+ " dispatch-capable workstreams — workspace admin is broader"
			+ " than workstream admin",
			McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS
				.contains("workspace_update_config"));
		assertFalse("secrets must stay excluded for dispatch-capable workstreams",
			McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS
				.contains("workspace_secret_list_names"));
	}

	/**
	 * The dispatch set is a subset of the base exclusion set: every
	 * name in {@link McpConfigBuilder#DISPATCH_AR_MANAGER_TOOLS} also
	 * appears in {@link McpConfigBuilder#EXCLUDED_AR_MANAGER_TOOLS}.
	 * This is the structural invariant that lets the conditional
	 * re-add at buildAllowedTools() time work without modifying the
	 * base classification. The {@code allowlistAndExclusionsAreDisjoint}
	 * test in {@code McpConfigBuilderTest} is unaffected because that
	 * test inspects the classification sets, not the conditional
	 * append.
	 */
	@Test(timeout = 30000)
	public void dispatchSetIsSubsetOfExclusionSet() {
		for (String tool : McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS) {
			assertTrue(
				"DISPATCH_AR_MANAGER_TOOLS entry '" + tool + "' must also be in"
					+ " EXCLUDED_AR_MANAGER_TOOLS so the conditional re-add at"
					+ " buildAllowedTools() time is a single CSV-appending step",
				McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS.contains(tool));
		}
	}
}
