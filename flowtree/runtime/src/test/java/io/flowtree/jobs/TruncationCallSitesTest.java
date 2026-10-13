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

import io.flowtree.controller.JobStatsStore;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the string truncation each FlowTree call site applies, observed through
 * the public (or package-visible) behaviour of the class that performs it:
 * ellipsis truncation for Slack text, marker truncation for shell command
 * output, and a hard cut for linter/validator messages, wake-up prompts and
 * persisted job descriptions.
 */
public class TruncationCallSitesTest extends TestSuiteBase {

	/** Slack text keeps short input, maps null to empty, and ends long input with an ellipsis. */
	@Test(timeout = 30000)
	public void slackTruncationUsesEllipsis() {
		assertEquals("short", SlackNotifier.truncate("short", 10));
		assertEquals("", SlackNotifier.truncate(null, 10));
		assertEquals("abcdef", SlackNotifier.truncate("abcdef", 6));
		assertEquals("ab...", SlackNotifier.truncate("abcdefgh", 5));
		assertEquals("a...", SlackNotifier.truncate("abcdefgh", 4));
	}

	/**
	 * Slack truncation treats a limit no larger than the ellipsis as the
	 * shell-output truncation already did: a bare prefix, or an empty string for
	 * a non-positive limit. Previously a limit of 3 produced a bare {@code "..."}
	 * with no content, and a smaller limit threw a
	 * {@link StringIndexOutOfBoundsException}.
	 */
	@Test(timeout = 30000)
	public void slackTruncationHandlesSmallLimits() {
		assertEquals("abc", SlackNotifier.truncate("abcdefgh", 3));
		assertEquals("ab", SlackNotifier.truncate("abcdef", 2));
		assertEquals("", SlackNotifier.truncate("abcdef", 0));
		assertEquals("", SlackNotifier.truncate("abcdef", -1));
	}

	/** Shell output truncation ends long input with the bracketed marker at exactly the limit. */
	@Test(timeout = 30000)
	public void shellTruncationUsesMarker() {
		assertEquals("abcdefghij", ShellCommandJob.truncate("abcdefghij", 10));
		String truncated = ShellCommandJob.truncate("x".repeat(30), 20);
		assertEquals("xxxxxxxx [truncated]", truncated);
		assertEquals("abcdefghijkl", ShellCommandJob.truncate("abcdefghijklmnop", 12));
	}

	/** The prompt linter quotes an offending line cut hard at 120 characters. */
	@Test(timeout = 30000)
	public void promptLinterCutsQuotedLine() {
		String line = "Run the full test suite " + "y".repeat(200);
		List<String> violations = new PromptTestInstructionLinter(line).lint().getViolations();
		assertEquals(1, violations.size());
		String quoted = violations.get(0).substring(violations.get(0).indexOf("> ") + 2);
		assertEquals(line.substring(0, 120), quoted);
	}

	/** The command validator quotes a shard command cut hard at 200 characters. */
	@Test(timeout = 30000)
	public void commandValidatorCutsQuotedCommand() {
		String command = "AR_TEST_GROUP=1 echo " + "z".repeat(300);
		List<String> violations = new PostCompletionCommandValidator(command).validate().getViolations();
		String first = violations.get(0);
		int start = first.indexOf('"') + 1;
		int end = first.indexOf('"', start);
		assertEquals(command.substring(0, 200), first.substring(start, end));
	}

	/** The wake-up prompt includes the finished job's description cut hard at 200 characters. */
	@Test(timeout = 30000)
	public void wakeUpPromptCutsDescription() {
		Map<String, Workstream> workstreams = new HashMap<>();
		CompletionListenerFanout fanout = new CompletionListenerFanout(
				() -> true, () -> workstreams, null, null,
				id -> "http://test/api/workstreams/" + id,
				null, null, null, id -> null, null);
		String description = "d".repeat(250);
		JobCompletionEvent event = JobCompletionEvent.success("job-1", description);
		Workstream listener = new Workstream("ws-listener", "C_l", "#l");
		String prompt = fanout.buildWakeUpPrompt("ws-source", listener, event, "chain-1", 1);
		assertTrue(prompt.contains("Finished job description: " + "d".repeat(200) + "\n"));
	}

	/** Persisted descriptions are cut hard at 1000 characters, and a null description stays null. */
	@Test(timeout = 60000)
	public void jobStatsStoreCutsDescriptionAndKeepsNull() throws Exception {
		File tempDir = Files.createTempDirectory("truncation-test").toFile();
		tempDir.deleteOnExit();
		JobStatsStore store = new JobStatsStore(new File(tempDir, "stats").getAbsolutePath());
		store.initialize();
		try {
			String description = "s".repeat(1500);
			store.recordJobStarted("job-long", "ws-1", description, Instant.now());
			assertEquals(description.substring(0, 1000), store.getJob("job-long").getDescription());

			store.recordJobStarted("job-null", "ws-1", null, Instant.now());
			assertNull(store.getJob("job-null").getDescription());
		} finally {
			store.close();
		}
	}
}
