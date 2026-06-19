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

import io.flowtree.JsonFieldExtractor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link CodingAgentJobEvent} covering inheritance,
 * Claude Code-specific builder methods, and default values.
 */
public class CodingAgentJobEventTest extends TestSuiteBase {

	/** Verifies that {@link CodingAgentJobEvent} is an instance of {@link JobCompletionEvent}. */
	@Test(timeout = 30000)
	public void extendsBaseEvent() {
		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-1", "Claude job");
		assertTrue(event instanceof JobCompletionEvent);
	}

	/** Verifies that {@code withClaudeCodeInfo} sets the prompt, session ID, and exit code fields. */
	@Test(timeout = 30000)
	public void withClaudeCodeInfoSetsFields() {
		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-2", "Claude job")
				.withClaudeCodeInfo("prompt", "session-1", 0);

		assertEquals("prompt", event.getPrompt());
		assertEquals("session-1", event.getSessionId());
		assertEquals(0, event.getExitCode());
	}

	/** Verifies that {@code withSessionDetails} sets subtype, error flag, denial count, and denied tool names. */
	@Test(timeout = 30000)
	public void withSessionDetailsSetsFields() {
		List<String> denied = Arrays.asList("Edit", "Bash");

		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-3", "Claude job")
				.withSessionDetails("success", false, 2, denied);

		assertEquals("success", event.getSubtype());
		assertFalse(event.isSessionError());
		assertEquals(2, event.getPermissionDenials());
		assertEquals(denied, event.getDeniedToolNames());
	}

	/** Verifies that {@code getDeniedToolNames} returns a non-null empty list when not explicitly set. */
	@Test(timeout = 30000)
	public void deniedToolNamesDefaultsToEmpty() {
		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-4", "Claude job");

		assertNotNull(event.getDeniedToolNames());
		assertTrue(event.getDeniedToolNames().isEmpty());
	}

	/** Verifies that {@code getCostByRunner} returns a non-null empty map when not explicitly set. */
	@Test(timeout = 30000)
	public void costByRunnerDefaultsToEmpty() {
		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-5", "Claude job");

		assertNotNull(event.getCostByRunner());
		assertTrue(event.getCostByRunner().isEmpty());
	}

	/** Verifies that the cost-by-runner map is correctly serialized and deserialized through a JSON round trip. */
	@Test(timeout = 30000)
	public void costByRunnerSurvivesJsonRoundTrip() {
		Map<String, Double> costs = new LinkedHashMap<>();
		costs.put("claude", 0.42);
		costs.put("opencode", 0.03);

		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-6", "Claude job")
				.withCostByRunner(costs);

		String json = event.toJson();
		Map<String, Double> parsed = JsonFieldExtractor.extractDoubleObject(json, "costByRunner");

		assertEquals(0.42, parsed.get("claude"), 1e-9);
		assertEquals(0.03, parsed.get("opencode"), 1e-9);
	}

	/** Verifies that the cost-incomplete flag defaults to false on a fresh event. */
	@Test(timeout = 30000)
	public void costIncompleteDefaultsToFalse() {
		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-7", "Claude job");

		assertFalse(event.isCostIncomplete());
	}

	/** Verifies that the cost-incomplete flag survives a JSON round trip. */
	@Test(timeout = 30000)
	public void costIncompleteSurvivesJsonRoundTrip() {
		CodingAgentJobEvent event = CodingAgentJobEvent.success("cc-8", "Killed job")
				.withCostIncomplete(true);

		String json = event.toJson();

		assertTrue("toJson must emit the costIncomplete flag",
				JsonFieldExtractor.extractBoolean(json, "costIncomplete"));
	}
}
