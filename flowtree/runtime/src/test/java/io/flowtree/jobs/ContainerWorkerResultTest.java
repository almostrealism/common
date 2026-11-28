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
import org.junit.Assert;
import org.junit.Test;

/**
 * Exercises the worker-to-host framing on {@link ContainerWorkerResult}: a result
 * survives the {@code encodeLine}/{@code parseLine} round-trip even when its output
 * spans multiple lines, a heartbeat is recognised, and ordinary agent logging is not
 * mistaken for either.
 *
 * @author  Michael Murray
 */
public class ContainerWorkerResultTest extends TestSuiteBase {

	/** A result survives the framed-line round-trip with every field intact. */
	@Test(timeout = 10000)
	public void resultSurvivesLineRoundTrip() {
		ContainerWorkerResult original = new ContainerWorkerResult(7, "session-xyz",
				"line one\nline two\nline three");

		ContainerWorkerResult restored = ContainerWorkerResult.parseLine(original.encodeLine());

		Assert.assertNotNull("the framed line must parse back to a result", restored);
		Assert.assertEquals(7, restored.agentExitCode());
		Assert.assertEquals("session-xyz", restored.sessionId());
		Assert.assertEquals("line one\nline two\nline three", restored.output());
	}

	/** The prompt survives the round-trip and is restored onto the job output. */
	@Test(timeout = 10000)
	public void promptSurvivesRoundTripIntoJobOutput() {
		CodingAgentJobOutput emitted = new CodingAgentJobOutput("task-1", "fix\nthe build",
				"done", "session-1", 4);

		ContainerWorkerResult restored = ContainerWorkerResult.parseLine(
				ContainerWorkerResult.of(emitted).encodeLine());
		CodingAgentJobOutput output = restored.toJobOutput("task-1");

		Assert.assertEquals("task-1", output.getTaskId());
		Assert.assertEquals("fix\nthe build", output.getPrompt());
		Assert.assertEquals("done", output.getOutput());
		Assert.assertEquals("session-1", output.getSessionId());
		Assert.assertEquals(4, output.getExitCode());
	}

	/** The framed result line is a single line, so a multi-line transcript cannot split it. */
	@Test(timeout = 10000)
	public void resultLineIsSingleLine() {
		String line = new ContainerWorkerResult(0, "s", "a\nb\nc").encodeLine();

		Assert.assertFalse("the framed result must occupy exactly one line",
				line.contains("\n"));
	}

	/** The heartbeat line is recognised and is not parsed as a result. */
	@Test(timeout = 10000)
	public void heartbeatIsNotAResult() {
		Assert.assertTrue("the heartbeat line is recognised",
				ContainerWorkerResult.isHeartbeat(ContainerWorkerResult.HEARTBEAT_LINE));
		Assert.assertNull("a heartbeat is not a result line",
				ContainerWorkerResult.parseLine(ContainerWorkerResult.HEARTBEAT_LINE));
	}

	/** Ordinary agent logging on the same stream is neither a heartbeat nor a result. */
	@Test(timeout = 10000)
	public void agentLoggingIsNeither() {
		String log = "CodingAgentJob [task-1]: running the primary session";

		Assert.assertFalse(ContainerWorkerResult.isHeartbeat(log));
		Assert.assertNull(ContainerWorkerResult.parseLine(log));
	}
}
