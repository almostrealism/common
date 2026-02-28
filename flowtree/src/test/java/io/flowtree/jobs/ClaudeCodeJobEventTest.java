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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ClaudeCodeJobEvent} covering inheritance,
 * Claude Code-specific builder methods, and default values.
 */
public class ClaudeCodeJobEventTest extends TestSuiteBase {

	@Test(timeout = 30000)
	public void extendsBaseEvent() {
		ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("cc-1", "Claude job");
		assertTrue(event instanceof JobCompletionEvent);
	}

	@Test(timeout = 30000)
	public void withClaudeCodeInfoSetsFields() {
		ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("cc-2", "Claude job")
				.withClaudeCodeInfo("prompt", "session-1", 0);

		assertEquals("prompt", event.getPrompt());
		assertEquals("session-1", event.getSessionId());
		assertEquals(0, event.getExitCode());
	}

	@Test(timeout = 30000)
	public void withSessionDetailsSetsFields() {
		List<String> denied = Arrays.asList("Edit", "Bash");

		ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("cc-3", "Claude job")
				.withSessionDetails("success", false, 2, denied);

		assertEquals("success", event.getSubtype());
		assertFalse(event.isSessionError());
		assertEquals(2, event.getPermissionDenials());
		assertEquals(denied, event.getDeniedToolNames());
	}

	@Test(timeout = 30000)
	public void deniedToolNamesDefaultsToEmpty() {
		ClaudeCodeJobEvent event = ClaudeCodeJobEvent.success("cc-4", "Claude job");

		assertNotNull(event.getDeniedToolNames());
		assertTrue(event.getDeniedToolNames().isEmpty());
	}
}
